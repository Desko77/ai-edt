/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.upkeep.ReleaseOffer.State;
import ru.aiedt.mcp.server.upkeep.ReleaseSweep.Schedule;
import ru.aiedt.mcp.server.upkeep.UpkeepLedger.Work;

/**
 * Covers what the watcher decides before it ever talks to anything: whether there is work to do,
 * when to do it next, and which settings it is allowed to react to.
 * <p>
 * The last of those is not a detail. The watcher writes the last-check mark through the same
 * preference store it listens to, so an allow-list that is a little too generous turns every
 * completed check into an immediate reschedule - a loop that would look like a working feature
 * while hammering somebody else's server.
 * </p>
 * <p>
 * Start and shutdown are exercised on a watcher of the test's own, never on the shared instance:
 * stopping that one would leave it stopped for the rest of the session.
 * </p>
 */
public class ReleaseSweepTest
{
    private static final String SITE = "https://example.org/aiedt/"; //$NON-NLS-1$
    private static final String OTHER_SITE = "https://mirror.example.org/aiedt/"; //$NON-NLS-1$
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long MINUTE = 60L * 1000L;

    @Test
    public void aFreshInstallationHasNothingToWatch()
    {
        Schedule schedule = ReleaseSweep.read(new PreferenceStore());
        assertFalse(schedule.runnable());
        assertEquals(State.DORMANT, schedule.startingPoint().state());
    }

    @Test
    public void withNoPluginToReadTheWatcherStaysOff()
    {
        // Reached while the bundle is stopping, when the store has already gone. Answering "off" is
        // what stops a run in flight from trying to schedule another one on the way out.
        Schedule schedule = ReleaseSweep.read(null);
        assertFalse(schedule.runnable());
        assertEquals(State.DORMANT, schedule.startingPoint().state());
    }

    @Test
    public void aConfiguredSiteRunsOnlyOnceTheFeatureIsSwitchedOn()
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PrefKeys.PREF_UPKEEP_SITE_URL, SITE);

        Schedule off = ReleaseSweep.read(store);
        assertFalse(off.runnable());
        // Dormant, not "awaiting an answer": the address is fine, nobody asked for it to be used.
        assertEquals(State.DORMANT, off.startingPoint().state());

        store.setValue(PrefKeys.PREF_UPKEEP_ENABLED, true);
        Schedule on = ReleaseSweep.read(store);
        assertTrue(on.runnable());
        assertEquals(State.NO_DATA, on.startingPoint().state());
        assertEquals(SITE, on.startingPoint().site().toString());
    }

    @Test
    public void anAddressThatCannotBeUsedSaysSoInsteadOfGoingQuiet()
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PrefKeys.PREF_UPKEEP_ENABLED, true);
        store.setValue(PrefKeys.PREF_UPKEEP_SITE_URL, "http://example.org/aiedt/"); //$NON-NLS-1$

        Schedule schedule = ReleaseSweep.read(store);
        assertFalse(schedule.runnable());

        ReleaseOffer starting = schedule.startingPoint();
        assertEquals(State.CHECK_FAILED, starting.state());
        assertNotNull(starting.note());
        assertTrue(starting.note().contains("https")); //$NON-NLS-1$
        // No check was attempted, so there is no time to report one at.
        assertEquals(0L, starting.checkedAtMillis());
    }

    @Test
    public void aLocalSiteNeedsItsOwnPermission()
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PrefKeys.PREF_UPKEEP_ENABLED, true);
        store.setValue(PrefKeys.PREF_UPKEEP_SITE_URL, "file:/tmp/aiedt-site/"); //$NON-NLS-1$

        assertFalse(ReleaseSweep.read(store).runnable());

        store.setValue(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE, true);
        assertTrue(ReleaseSweep.read(store).runnable());
    }

    @Test
    public void theFirstCheckWaitsOutTheStartupDelay()
    {
        Schedule schedule = configured(store(SITE, 24, 5));
        assertEquals(5L * MINUTE, schedule.delayFrom(NOW));
    }

    @Test
    public void aCheckJustMadeIsNotRepeatedOnTheNextRestart()
    {
        PreferenceStore store = store(SITE, 24, 5);
        store.setValue(PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE, SITE);
        store.setValue(PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS, NOW);

        // Six restarts in a day must not produce six checks; the wait counts from the last answer.
        assertEquals(24L * HOUR, configured(store).delayFrom(NOW));
    }

    @Test
    public void aMarkLeftOverFromAnotherSiteDoesNotPostponeTheNewOne()
    {
        PreferenceStore store = store(SITE, 24, 5);
        store.setValue(PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE, OTHER_SITE);
        store.setValue(PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS, NOW);

        // Someone correcting a wrong address expects an answer today, not tomorrow.
        assertEquals(5L * MINUTE, configured(store).delayFrom(NOW));
    }

    @Test
    public void aFailedCheckIsRetriedSoonerThanTheFullInterval()
    {
        Schedule schedule = configured(store(SITE, 24, 5));
        assertEquals(24L * HOUR, schedule.normalDelay());
        assertTrue(schedule.retryDelay() < schedule.normalDelay());
    }

    @Test
    public void anIntervalOfZeroDoesNotBecomeAHotLoop()
    {
        // Nothing in the preference page can produce this, but a hand-edited settings file can, and
        // the failure mode is a request per pass against somebody else's server.
        Schedule schedule = configured(store(SITE, 0, 0));
        assertTrue(schedule.normalDelay() > 0L);
        assertTrue(schedule.delayFrom(NOW) > 0L);
    }

    @Test
    public void theWatcherIgnoresTheMarkItWritesItself()
    {
        assertFalse(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS));
        assertFalse(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE));
        assertFalse(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_NOTIFIED_VERSION));
        assertFalse(ReleaseSweep.affectsSchedule(null));
    }

    @Test
    public void theWatcherReactsToEverySettingThatChangesItsWork()
    {
        assertTrue(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_ENABLED));
        assertTrue(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_SITE_URL));
        assertTrue(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS));
        assertTrue(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES));
        assertTrue(ReleaseSweep.affectsSchedule(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE));
    }

    @Test
    public void onlyAChangeOfSourceThrowsAwayWhatWasKnown()
    {
        assertTrue(ReleaseSweep.invalidatesOffer(PrefKeys.PREF_UPKEEP_SITE_URL));
        assertTrue(ReleaseSweep.invalidatesOffer(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE));
        // A new interval moves the next question without making the last answer wrong.
        assertFalse(ReleaseSweep.invalidatesOffer(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS));
        assertFalse(ReleaseSweep.invalidatesOffer(PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES));
    }

    @Test
    public void anythingThatInvalidatesAnAnswerAlsoReschedules()
    {
        // Otherwise the offer would be dropped and nothing would ever be scheduled to replace it.
        for (String property : new String[] {PrefKeys.PREF_UPKEEP_SITE_URL,
            PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE})
        {
            assertTrue(property, ReleaseSweep.affectsSchedule(property));
        }
    }

    @Test
    public void aFailedCheckIsNotRememberedAsAnAnswer()
    {
        // The stored mark survives a restart and has no room for "and that one failed". Recording a
        // failure there would read back next session as a completed check, and the short retry the
        // failure earned would silently become the full interval.
        assertFalse(ReleaseSweep.recordsAnswer(State.CHECK_FAILED));
        assertTrue(ReleaseSweep.recordsAnswer(State.UP_TO_DATE));
        assertTrue(ReleaseSweep.recordsAnswer(State.UPDATE_AVAILABLE));
    }

    @Test
    public void everyConsumerSeesTheSameState()
    {
        assertSame(ReleaseSweep.get(), ReleaseSweep.get());
        assertSame(ReleaseSweep.get().ledger(), ReleaseSweep.get().ledger());
        assertNotNull(ReleaseSweep.get().ledger().current());
    }

    @Test
    public void aWatcherThatWasNeverStartedShutsDownCleanly()
    {
        ReleaseSweep sweep = new ReleaseSweep();
        sweep.shutdown();
        assertFalse(sweep.pending());
    }

    @Test
    public void startingOnAFreshInstallationSchedulesNothing()
    {
        // The shipped state: no site, so no job, no network and nothing in the status bar. This is
        // also what keeps the feature out of the way in a headless runtime like this one.
        ReleaseSweep sweep = new ReleaseSweep();
        try
        {
            sweep.start();
            assertTrue(sweep.isRunning());
            assertFalse(sweep.pending());
            assertEquals(State.DORMANT, sweep.ledger().current().state());
        }
        finally
        {
            sweep.shutdown();
        }
    }

    @Test
    public void aSlotHeldByOtherWorkOnlyPostponesTheCheck()
    {
        ReleaseSweep sweep = new ReleaseSweep();
        try
        {
            sweep.start();
            assertFalse(sweep.pending());
            // An install holds the slot. The check has to wait its turn, not give up.
            assertTrue(sweep.ledger().begin(Work.INSTALL).isPresent());

            sweep.sweep(ReleaseSweep.read(store(SITE, 24, 5)), null);
            assertTrue(sweep.pending());
        }
        finally
        {
            sweep.shutdown();
        }
    }

    @Test
    public void aFinishedPassGivesTheWorkSlotBack()
    {
        // The invariant a whole class of bugs hides behind: whatever a pass decides, it must not
        // walk away holding the slot. One left behind is silent and permanent - every later check
        // is refused, the state sticks at "checking", and nothing in the log says why.
        //
        // The site is a local directory that does not exist, so this settles without a network
        // request whichever way it goes: unmanaged when p2 does not run this instance, otherwise a
        // failure to read the directory.
        ReleaseSweep sweep = new ReleaseSweep();
        try
        {
            sweep.start();
            PreferenceStore store = store("file:/aiedt-tests-no-such-update-site/", 24, 5); //$NON-NLS-1$
            store.setValue(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE, true);

            sweep.sweep(ReleaseSweep.read(store), null);

            assertFalse("the pass finished still holding the work slot", //$NON-NLS-1$
                sweep.ledger().busy());
            assertNotEquals("the state was left mid-flight", //$NON-NLS-1$
                State.CHECKING, sweep.ledger().current().state());
            // Proof the pass got as far as taking the slot, rather than turning back at one of the
            // guards before it. Without this the assertions above would also hold for a pass that
            // did nothing at all, and the bug they exist to catch would slip through.
            assertNotEquals("the pass never reached the check", //$NON-NLS-1$
                State.DORMANT, sweep.ledger().current().state());
        }
        finally
        {
            sweep.shutdown();
        }
    }

    @Test
    public void aPendingRestartStopsTheWatcherAskingAgain()
    {
        ReleaseSweep sweep = new ReleaseSweep();
        try
        {
            sweep.start();
            sweep.ledger().publish(ReleaseOffer.restartPending(URI.create(SITE),
                Version.create("3.1.0.202608011200"), //$NON-NLS-1$
                Version.create("3.2.0.202609011200"), NOW)); //$NON-NLS-1$

            sweep.sweep(ReleaseSweep.read(store(SITE, 24, 5)), null);

            // Nothing but a restart changes this, and a restart ends the process. Coming back every
            // hour to be refused again would be a loop that can never do anything.
            assertFalse(sweep.pending());
            assertEquals(State.RESTART_PENDING, sweep.ledger().current().state());
        }
        finally
        {
            sweep.shutdown();
        }
    }

    @Test
    public void aWatcherDoesNotComeBackAfterItHasBeenShutDown()
    {
        // The bundle stops once, and a start arriving afterwards is a race with shutdown rather
        // than a request: honouring it would put back the listener and the job that shutdown exists
        // to take away.
        ReleaseSweep sweep = new ReleaseSweep();
        sweep.shutdown();
        sweep.start();
        assertFalse(sweep.isRunning());
        assertFalse(sweep.pending());
    }

    private static PreferenceStore store(String site, int intervalHours, int startupDelayMinutes)
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PrefKeys.PREF_UPKEEP_ENABLED, true);
        store.setValue(PrefKeys.PREF_UPKEEP_SITE_URL, site);
        store.setValue(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS, intervalHours);
        store.setValue(PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES, startupDelayMinutes);
        return store;
    }

    private static Schedule configured(PreferenceStore store)
    {
        Schedule schedule = ReleaseSweep.read(store);
        assertTrue("the fixture should be runnable", schedule.runnable()); //$NON-NLS-1$
        return schedule;
    }
}
