/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.WorkspacePhase;

/**
 * Decides when the configured site gets asked, and is the one place that owns the feature's
 * lifetime: it holds the {@link UpkeepLedger} every consumer reads, the job that does the asking,
 * and the preference listener that reacts to the user changing their mind.
 * <p>
 * <b>A job, not a thread of our own.</b> Reading a repository can hang in the transport, and
 * interrupting a bare thread does not release it: it would outlive the bundle and pin this
 * classloader - the exact leak this plugin has already had to fix once. A job takes a progress
 * monitor, which the transport is expected to honour, and can be waited for with a deadline.
 * </p>
 * <p>
 * <b>No job family.</b> There is one watcher and this class holds the reference, so cancelling by
 * family would only be indirection. It would not help with the part that actually needs separate
 * handling either: the provisioning job at install time is created by p2, and nothing outside can
 * put it in a family of ours.
 * </p>
 * <p>
 * <b>The schedule counts from the last check, not from startup</b>, so six restarts in a day do not
 * produce six checks; and the mark is bound to the address it was taken for, so correcting a wrong
 * URL is answered promptly instead of being postponed by a stale mark from the old one.
 * </p>
 */
public final class ReleaseSweep
{
    /**
     * Wait before looking again while the workspace is busy. Short, because it is only a phase read
     * and the user is waiting for their first answer; long enough that a full initial index costs a
     * handful of wake-ups rather than hundreds.
     */
    private static final long BUSY_RETRY_MILLIS = 2L * 60L * 1000L;

    /** How long the bundle waits for an unfinished check before giving up on it and moving on. */
    private static final long SHUTDOWN_WAIT_MILLIS = 5L * 1000L;

    /** Settings that change when or whether the watcher runs. */
    private static final Set<String> AFFECTS_SCHEDULE = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(PrefKeys.PREF_UPKEEP_ENABLED, PrefKeys.PREF_UPKEEP_SITE_URL,
            PrefKeys.PREF_UPKEEP_INTERVAL_HOURS, PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES,
            PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE)));

    /**
     * Settings that invalidate what is already known, because they change which site the answer
     * would have come from. An interval is not one of them: it moves the next question without
     * making the previous answer wrong.
     */
    private static final Set<String> INVALIDATES_OFFER =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PrefKeys.PREF_UPKEEP_SITE_URL,
            PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE)));

    private static final ReleaseSweep INSTANCE = new ReleaseSweep();

    private final UpkeepLedger ledger = new UpkeepLedger();

    private final IPropertyChangeListener settingsListener = this::settingChanged;

    private boolean started;

    private boolean stopped;

    private boolean listening;

    private Job watcher;

    /**
     * Package-private rather than private so a test can drive a watcher of its own. Exercising
     * start and shutdown against the shared instance would leave it permanently stopped for
     * whatever ran afterwards in the same session.
     */
    ReleaseSweep()
    {
    }

    /**
     * @return the one instance; the tool, the status bar and the watcher all read the same state
     */
    public static ReleaseSweep get()
    {
        return INSTANCE;
    }

    /**
     * @return the state every consumer of this feature reads and the only place work is serialized
     */
    public UpkeepLedger ledger()
    {
        return ledger;
    }

    /**
     * Brings the watcher up: attaches the preference listener, establishes the initial state and
     * schedules the first check if there is anything to check.
     * <p>
     * The listener is attached even when the feature is switched off, and that is the point of
     * doing this unconditionally at startup. The feature ships dormant, so every first-time user
     * arrives by switching it on in a running IDE; without a listener already in place, nothing
     * would happen until they restarted, and the setting would look broken.
     * </p>
     * <p>
     * Never throws. It is called from workbench startup, ahead of unrelated work, and a fault in
     * update checking is not a reason for the rest of that startup not to happen.
     * </p>
     */
    public void start()
    {
        try
        {
            synchronized (this)
            {
                if (started || stopped)
                {
                    return;
                }
                started = true;
                watcher = createWatcher();
            }
            attachListener();
            replan(true);
        }
        catch (RuntimeException e)
        {
            Activator.logError("The AI-EDT update watcher could not be started", e); //$NON-NLS-1$
        }
    }

    /**
     * Takes the watcher back down. Safe to call when it was never started.
     * <p>
     * The stopped flag is set first and checked by every path that schedules, so a run finishing
     * concurrently cannot put itself back on the queue after this point. Then the listener is
     * handed back - left attached, it would keep this bundle's classloader alive through the
     * preference store - and the job is cancelled and waited for with a deadline.
     * </p>
     * <p>
     * The wait is bounded on purpose. Cancelling only asks; a transport stuck in a socket read may
     * not answer, and blocking the bundle from stopping would be worse than recording that
     * something was left running.
     * </p>
     */
    public void shutdown()
    {
        Job job;
        synchronized (this)
        {
            stopped = true;
            job = watcher;
            watcher = null;
        }
        detachListener();
        if (job == null)
        {
            return;
        }
        job.cancel();
        try
        {
            if (!job.join(SHUTDOWN_WAIT_MILLIS, null))
            {
                Activator.logWarning(
                    "The AI-EDT update check did not stop in time and was left running"); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (OperationCanceledException e)
        {
            // join() reports the wait being cancelled, not the job failing. Nothing to add.
        }
    }

    /**
     * Whether a run is queued or in flight.
     *
     * @return <code>true</code> when the watcher exists and is not idle
     */
    synchronized boolean pending()
    {
        return watcher != null && watcher.getState() != Job.NONE;
    }


    /**
     * Everything about the preferences that the schedule depends on, read in one go so a run works
     * from one consistent picture rather than re-reading fields that may change under it.
     */
    static final class Schedule
    {
        private final boolean enabled;

        private final UpkeepPolicy.SiteVerdict site;

        private final int intervalHours;

        private final int startupDelayMinutes;

        private final String markedSite;

        private final long markedMillis;

        private Schedule(boolean enabled, UpkeepPolicy.SiteVerdict site, int intervalHours,
            int startupDelayMinutes, String markedSite, long markedMillis)
        {
            this.enabled = enabled;
            this.site = site;
            this.intervalHours = intervalHours;
            this.startupDelayMinutes = startupDelayMinutes;
            this.markedSite = markedSite;
            this.markedMillis = markedMillis;
        }

        /**
         * @return the verdict on the configured address, never <code>null</code>
         */
        UpkeepPolicy.SiteVerdict site()
        {
            return site;
        }

        /**
         * Whether there is anything to run.
         *
         * @return <code>true</code> when the feature is on and its address is usable
         */
        boolean runnable()
        {
            return enabled && site.accepted();
        }

        /**
         * How long to wait before the next check.
         *
         * @param now current wall-clock time
         * @return milliseconds to wait
         */
        long delayFrom(long now)
        {
            long mark = UpkeepPolicy.markFor(site.uri(), markedSite, markedMillis);
            return UpkeepPolicy.delayUntilDueMillis(now, mark, UpkeepPolicy.intervalMillis(intervalHours),
                UpkeepPolicy.startupDelayMillis(startupDelayMinutes));
        }

        /**
         * @return the wait after a check that could not be completed
         */
        long retryDelay()
        {
            return UpkeepPolicy.retryIntervalMillis(intervalHours);
        }

        /**
         * @return the wait after a check that succeeded
         */
        long normalDelay()
        {
            return UpkeepPolicy.intervalMillis(intervalHours);
        }

        /**
         * The state to show when nothing has been asked yet.
         *
         * @return dormant while switched off or unconfigured, the refusal when the address is
         *         unusable, otherwise a snapshot awaiting its first answer
         */
        ReleaseOffer startingPoint()
        {
            if (!enabled || !site.configured())
            {
                return ReleaseOffer.dormant();
            }
            if (!site.accepted())
            {
                // Time zero, not "now": no check was attempted, so claiming one happened would put
                // a timestamp on the status bar that never corresponded to a request.
                return ReleaseOffer.failed(null, null, site.reason(), 0L);
            }
            return ReleaseOffer.noData(site.uri());
        }
    }

    /**
     * Reads the schedule out of a preference store.
     *
     * @param store the store, or <code>null</code> when the plugin is not available
     * @return the schedule; switched off when there is no store to read
     */
    static Schedule read(IPreferenceStore store)
    {
        if (store == null)
        {
            return new Schedule(false, UpkeepPolicy.examineSite(null, false),
                PrefKeys.DEFAULT_UPKEEP_INTERVAL_HOURS,
                PrefKeys.DEFAULT_UPKEEP_STARTUP_DELAY_MINUTES, null, 0L);
        }
        return new Schedule(store.getBoolean(PrefKeys.PREF_UPKEEP_ENABLED),
            UpkeepPolicy.examineSite(store.getString(PrefKeys.PREF_UPKEEP_SITE_URL),
                store.getBoolean(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE)),
            store.getInt(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS),
            store.getInt(PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES),
            store.getString(PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE),
            store.getLong(PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS));
    }

    /**
     * Whether a changed preference has any bearing on the schedule.
     * <p>
     * Deliberately a small allow-list. The last-check mark is written by the watcher itself and
     * fires a change event like any other setting; reacting to it would make every completed check
     * reschedule itself.
     * </p>
     *
     * @param property the changed preference key
     * @return <code>true</code> when the watcher has to reconsider
     */
    static boolean affectsSchedule(String property)
    {
        return property != null && AFFECTS_SCHEDULE.contains(property);
    }

    /**
     * Whether a changed preference makes an existing answer obsolete.
     *
     * @param property the changed preference key
     * @return <code>true</code> when what is known came from an address no longer configured
     */
    static boolean invalidatesOffer(String property)
    {
        return property != null && INVALIDATES_OFFER.contains(property);
    }

    /**
     * Whether a finished pass produced an answer worth remembering as the last check.
     * <p>
     * A failure does not. The stored mark is what survives a restart, and it carries no room for
     * "and that one failed": a failed attempt recorded as an ordinary check would be read back as a
     * completed one, and the next session would wait out the full interval instead of the short
     * retry the failure was supposed to earn. Leaving the mark alone costs one check shortly after
     * each restart while a site is unreachable, which is fewer than the hourly retries already
     * scheduled inside a session - so there is no runaway to guard against here.
     * </p>
     *
     * @param state the state a completed pass ended in
     * @return <code>true</code> when the last-check mark should move
     */
    static boolean recordsAnswer(ReleaseOffer.State state)
    {
        return state != ReleaseOffer.State.CHECK_FAILED;
    }

    private void settingChanged(PropertyChangeEvent event)
    {
        String property = event == null ? null : event.getProperty();
        if (!affectsSchedule(property))
        {
            return;
        }
        replan(invalidatesOffer(property));
    }

    /**
     * Reconsiders when to run next.
     *
     * @param invalidate whether what is currently known has to be dropped first
     */
    private void replan(boolean invalidate)
    {
        Schedule schedule = read(store());
        if (!schedule.runnable())
        {
            // reconfigured() rather than publish(): it drops results still in flight for the old
            // configuration, and it keeps a pending restart, which is a fact about this process
            // rather than about any site.
            ledger.reconfigured(schedule.startingPoint());
            cancelPending();
            return;
        }
        if (invalidate)
        {
            ledger.reconfigured(schedule.startingPoint());
            // The record of what has already been announced belongs to the old address. Keeping it
            // would silence the notice for a version number that happens to match on the new one.
            forgetAnnouncedVersion();
        }
        planNow(schedule.delayFrom(System.currentTimeMillis()));
    }

    /**
     * One pass: decide whether to ask, ask, publish the answer and set the next wake-up.
     *
     * @param monitor the job's monitor
     * @return always a non-failing status; failures are reported through the ledger, which is where
     *         the user can see them, rather than through the platform's error log
     */
    private IStatus sweep(IProgressMonitor monitor)
    {
        if (isStopped())
        {
            return Status.CANCEL_STATUS;
        }
        // Read fresh rather than captured when the run was queued: hours may have passed and the
        // settings may be different ones by now.
        return sweep(read(store()), monitor);
    }

    /**
     * The pass itself, against a schedule handed to it.
     * <p>
     * Separated from reading the preferences so the branches that decide whether to come back at
     * all can be driven by a test without a preference store and without a site to talk to.
     * </p>
     *
     * @param schedule the settings this pass works from
     * @param monitor the job's monitor, may be <code>null</code>
     * @return the status for the job
     */
    IStatus sweep(Schedule schedule, IProgressMonitor monitor)
    {
        if (!schedule.runnable())
        {
            // The settings changed since this run was queued. The listener has already published
            // the new state and is responsible for scheduling; there is nothing to do here.
            return Status.OK_STATUS;
        }
        if (!UpkeepPolicy.mayStartWork(ledger.current().state()))
        {
            // A pending restart, and deliberately no rescheduling: nothing but restarting changes
            // it, and restarting ends this process. Coming back hourly to be refused again would
            // be a loop that can never do anything.
            return Status.OK_STATUS;
        }
        if (WorkspacePhase.busy())
        {
            // No check happened, so the mark stays where it is: postponing must not look like an
            // answer, or a long index would push the first real check a whole interval away.
            planAfterRun(BUSY_RETRY_MILLIS);
            return Status.OK_STATUS;
        }
        // The work slot is taken inside performCheck, which also gives it back. Claiming it here as
        // well would be a slot claimed twice by the same pass: the second attempt fails, the check
        // never runs, and the first claim is never released - a watcher permanently busy with
        // nothing.
        int generation = ledger.generation();
        ReleaseOffer result;
        try
        {
            result = performCheck(schedule, monitor);
        }
        catch (OperationCanceledException e)
        {
            // Cancelled means somebody else decided what happens next - the bundle is stopping, or
            // a setting changed and the listener has already scheduled the replacement run. No
            // answer, no mark, and deliberately no rescheduling from here.
            return Status.CANCEL_STATUS;
        }
        if (result == null)
        {
            if (ledger.generation() == generation)
            {
                // Something else holds the slot - an install, most likely. Come back for it.
                planAfterRun(schedule.retryDelay());
            }
            // Otherwise the settings changed under this run and the listener has already scheduled
            // the replacement. Scheduling again here would overwrite that with a delay worked out
            // from settings nobody is using any more.
            return Status.OK_STATUS;
        }
        if (!result.managed())
        {
            // p2 does not manage this installation, so no answer from any site could be acted on.
            // Stop asking rather than repeating a question with no useful answer.
            Activator.logInfo(
                "AI-EDT update checks are off: this installation is not managed by p2"); //$NON-NLS-1$
            return Status.OK_STATUS;
        }
        planAfterRun(recordsAnswer(result.state()) ? schedule.normalDelay() : schedule.retryDelay());
        return Status.OK_STATUS;
    }

    /**
     * Asks the site once, publishes the answer and moves the mark if there was one.
     * <p>
     * Shared by the background pass and the manual one so both take the slot the same way, record
     * the same thing and cannot run alongside each other. What differs between them - whether to
     * wait for a busy workspace, and how the next wake-up is scheduled - stays with the callers.
     * </p>
     *
     * @param schedule the settings this check works from
     * @param monitor progress monitor, may be <code>null</code>
     * @return the answer, or <code>null</code> when the work slot was already taken
     */
    private ReleaseOffer performCheck(Schedule schedule, IProgressMonitor monitor)
    {
        Optional<UpkeepLedger.Lease> claim = ledger.begin(UpkeepLedger.Work.CHECK);
        if (claim.isEmpty())
        {
            return null;
        }
        UpkeepLedger.Lease lease = claim.get();
        try
        {
            if (monitor != null && monitor.isCanceled())
            {
                // Cancelled between taking the slot and using it. Bailing out here saves a request
                // whose answer would be discarded anyway.
                throw new OperationCanceledException();
            }
            URI site = schedule.site().uri();
            ReleaseOffer result = ReleaseFeed.inspect(site, monitor);
            if (!ledger.complete(lease, result))
            {
                // The configuration moved on while this ran, so the answer describes a site nobody
                // asked about any more. It is not published, and it must not be recorded or acted
                // on either: marking it would attribute a check to the wrong address, and returning
                // it would report a version from a site that is no longer configured.
                return null;
            }
            if (result.managed() && recordsAnswer(result.state()))
            {
                markChecked(site, System.currentTimeMillis());
            }
            return result;
        }
        finally
        {
            // complete() has already given the slot back on the ordinary path; this covers
            // cancellation and anything thrown on the way out, and is a no-op otherwise.
            ledger.release(lease);
        }
    }

    /**
     * Asks the site right now, on the calling thread, and returns what it said.
     * <p>
     * The throttle is deliberately not consulted: somebody asked. The workspace being busy is
     * ignored for the same reason - that wait exists to keep an unattended task out of the way, and
     * this task is not unattended.
     * </p>
     * <p>
     * Synchronous, and honestly so: it returns when the site answers. There is no network deadline
     * yet - how to impose one without changing the transport for every other p2 operation in the
     * IDE is still an open question - so a caller that cannot afford to wait indefinitely should
     * read the state instead of asking for a check.
     * </p>
     *
     * @return the state in effect afterwards, never <code>null</code>
     */
    public ReleaseOffer checkNow(IProgressMonitor monitor)
    {
        Schedule schedule = read(store());
        if (!schedule.runnable() || !UpkeepPolicy.mayStartWork(ledger.current().state()))
        {
            return ledger.current();
        }
        ReleaseOffer result;
        try
        {
            result = performCheck(schedule, monitor);
        }
        catch (OperationCanceledException e)
        {
            return ledger.current();
        }
        if (result == null || !result.managed())
        {
            return ledger.current();
        }
        // Realign the background schedule: the next automatic check is due an interval from this
        // answer, not from whatever the last one was.
        planNow(recordsAnswer(result.state()) ? schedule.normalDelay() : schedule.retryDelay());
        return result;
    }

    /**
     * Installs the offer currently on the table, on the calling thread.
     * <p>
     * The offer is not re-fetched. What gets installed must be what the caller was shown and
     * approved, and its site is compared against the setting as it stands now: an address edited
     * between the answer and the confirmation invalidates the approval rather than redirecting it.
     * </p>
     *
     * @param monitor progress monitor, may be <code>null</code>
     * @return the state in effect afterwards, never <code>null</code>
     */
    public ReleaseOffer installNow(IProgressMonitor monitor)
    {
        ReleaseOffer offer = ledger.current();
        if (!offer.hasUpdate())
        {
            return offer;
        }
        Schedule schedule = read(store());
        if (!schedule.runnable() || !sameSite(schedule, offer))
        {
            return ledger.current();
        }
        Optional<UpkeepLedger.Lease> claim = ledger.begin(UpkeepLedger.Work.INSTALL);
        if (claim.isEmpty())
        {
            return ledger.current();
        }
        UpkeepLedger.Lease lease = claim.get();
        try
        {
            ReleaseAdoption.Outcome outcome =
                ReleaseAdoption.install(offer.site(), offer.offered(), monitor);
            if (!outcome.applied())
            {
                ledger.complete(lease, ReleaseOffer.failed(offer.site(), offer.installed(),
                    outcome.problem(), System.currentTimeMillis()));
                return ledger.current();
            }
            // Published whatever else has changed meanwhile: the profile on disk now differs from
            // the code running, and that is a fact about this process rather than about any site.
            ledger.complete(lease, ReleaseOffer.restartPending(offer.site(), offer.installed(),
                outcome.version(), System.currentTimeMillis()));
            return ledger.current();
        }
        catch (OperationCanceledException e)
        {
            return ledger.current();
        }
        finally
        {
            // complete() has already handed the slot back on the ordinary paths; this covers
            // cancellation and anything thrown on the way out, and is a no-op otherwise.
            ledger.release(lease);
        }
    }

    /**
     * Whether the offer still describes the site that is configured now.
     *
     * @param schedule the settings as they stand
     * @param offer the offer to be acted on
     * @return <code>true</code> when they agree
     */
    private static boolean sameSite(Schedule schedule, ReleaseOffer offer)
    {
        URI configured = schedule.site().uri();
        return configured != null && configured.equals(offer.site());
    }

    /**
     * Whether the watcher is up.
     * <p>
     * Worth reporting: it comes up with the workbench, so a runtime without one - a headless
     * launch, or a startup that failed - has no schedule at all, and that is the difference between
     * "nothing found" and "nothing looked".
     * </p>
     *
     * @return <code>true</code> between {@link #start()} and {@link #shutdown()}
     */
    public synchronized boolean isRunning()
    {
        return started && !stopped;
    }

    /**
     * Schedules from outside a run.
     * <p>
     * The cancel is not optional: a job already sleeping ignores {@code schedule}, so without it a
     * shortened interval would not take effect until the wait it was meant to shorten had elapsed.
     * If the watcher happens to be running, the cancel is what stops it working on settings that no
     * longer apply.
     * </p>
     *
     * @param delayMillis how long to wait
     */
    private synchronized void planNow(long delayMillis)
    {
        if (stopped || watcher == null)
        {
            return;
        }
        watcher.cancel();
        watcher.schedule(delayMillis);
    }

    /**
     * Schedules from inside the running job, where {@code schedule} means "again, once this run
     * finishes" and a cancel would only be this run cancelling itself on its way out.
     *
     * @param delayMillis how long to wait
     */
    private synchronized void planAfterRun(long delayMillis)
    {
        if (stopped || watcher == null)
        {
            return;
        }
        watcher.schedule(delayMillis);
    }

    private synchronized void cancelPending()
    {
        if (watcher != null)
        {
            watcher.cancel();
        }
    }

    private synchronized boolean isStopped()
    {
        return stopped;
    }

    private Job createWatcher()
    {
        Job job = new Job("AI-EDT update check") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return sweep(monitor);
            }
        };
        // System, because nobody asked for it: a progress entry appearing by itself every day would
        // be noise. Long, because it waits on a network read.
        job.setSystem(true);
        job.setPriority(Job.LONG);
        return job;
    }

    /**
     * Attaches the preference listener.
     * <p>
     * Registration happens under the lock together with the flag that records it. Splitting the two
     * leaves a window in which a concurrent shutdown removes a listener that has not been added
     * yet, and the add then lands after the remove - a listener left attached to the preference
     * store, holding this bundle's classloader, with the flag saying there is none.
     * </p>
     */
    private void attachListener()
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return;
        }
        synchronized (this)
        {
            if (listening || stopped)
            {
                return;
            }
            listening = true;
            store.addPropertyChangeListener(settingsListener);
        }
    }

    private void detachListener()
    {
        IPreferenceStore store = store();
        synchronized (this)
        {
            if (!listening)
            {
                return;
            }
            listening = false;
            if (store != null)
            {
                store.removePropertyChangeListener(settingsListener);
            }
        }
    }

    /**
     * Records that this site was asked, so restarting the IDE does not ask it again immediately.
     *
     * @param site the address that was checked
     * @param millis when it was checked
     */
    private static void markChecked(URI site, long millis)
    {
        IPreferenceStore store = store();
        if (store == null || site == null)
        {
            return;
        }
        store.setValue(PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS, millis);
        store.setValue(PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE, site.toString());
        try
        {
            // Straight to disk rather than waiting for the store to be saved at shutdown: an IDE
            // that is killed rather than closed would otherwise lose every mark it ever took, and
            // updating this plugin is one of the things that kills it.
            Preferences node = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
            node.flush();
        }
        catch (BackingStoreException | RuntimeException e)
        {
            // The mark is in the store either way; only its trip to disk failed, and the cost of
            // that is one extra check after the next restart.
            Activator.logDebug("upkeep: last-check mark could not be flushed: " + e); //$NON-NLS-1$
        }
    }

    /**
     * Drops the note of which version has already been announced, so the next one announces again.
     */
    private static void forgetAnnouncedVersion()
    {
        IPreferenceStore store = store();
        if (store != null)
        {
            store.setValue(PrefKeys.PREF_UPKEEP_NOTIFIED_VERSION, ""); //$NON-NLS-1$
        }
    }

    private static IPreferenceStore store()
    {
        Activator plugin = Activator.getDefault();
        return plugin == null ? null : plugin.getPreferenceStore();
    }
}
