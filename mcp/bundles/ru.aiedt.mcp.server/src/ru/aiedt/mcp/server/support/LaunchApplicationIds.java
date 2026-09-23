/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Puts back the application id of EDT launch configurations that a save of the infobase list
 * strips.
 *
 * <p>Saving the list ({@code IInfobaseManager.update}, which
 * {@code IInfobaseAccessManager.updateSettings} calls on every credentials write) makes EDT
 * reload it; the reload announces every application as deleted, and EDT's launch-configuration
 * manager answers a deletion by removing {@code ATTR_APPLICATION_ID} from every launch
 * configuration that names the application. The configuration itself survives, the binding does
 * not: the next launch builds a fresh configuration, and the debug tools stop recognising the
 * launches behind the old one - including a client started by {@code start_client}, whose launch
 * is addressed by that attribute alone.</p>
 *
 * <p>The guard brackets the write: {@link #snapshot(Access)} remembers the application id of
 * every configuration that carries one, {@link #restore(Access, Map)} puts back the ones the
 * write removed. Restore compares before it writes: an id that reappeared on its own, or that
 * somebody set to another value between the snapshot and the restore, is left alone and named in
 * the report - the guard repairs one known loss, it does not overwrite edits it knows nothing
 * about.</p>
 *
 * <p>A configuration is addressed by its memento throughout: one display name occurs in several
 * launch configuration types at once, and a name-addressed search takes whichever of them comes
 * first in the manager's list. The name travels with the memento for the answer only.</p>
 *
 * <p><b>The strip is over before the write returns.</b> Measured on EDT 2026.2 bytecode: from
 * {@code IInfobaseAccessManager.updateSettings} to {@code InfobaseManager.save}, on through
 * {@code reload}, {@code InfobaseApplicationProvisionDelegate.infobasesReloaded} and
 * {@code ApplicationManager.notifyLifecycleStateChanged}, into
 * {@code IApplicationListener.applicationChanged} and the stripped working copy's
 * {@code doSave} - every link a plain call on the calling thread, with no {@code Job.schedule},
 * {@code Executor}, {@code CompletableFuture} or {@code Display.asyncExec} anywhere on the path.
 * The restore therefore runs after the strip and can settle the outcome itself: each write is
 * read back, and the report speaks by that reading, not by the write having returned.</p>
 *
 * <p><b>Two windows are known and left open.</b> A configuration created after the snapshot
 * carries an application id the guard never saw: when the write strips it, there is nothing to
 * put back and the report cannot name the loss. A configuration that disappears between the
 * restore's listing and its write is named as one whose id "did not stay", which is
 * indistinguishable from a write the store accepted and dropped.</p>
 *
 * <p>Every infobase-list write runs under {@link #WRITE_LOCK}: the snapshot, the write and the
 * restore of one write must not interleave with another's.</p>
 */
public final class LaunchApplicationIds
{
    /**
     * The one lock every write of the infobase list runs under: the snapshot, the write itself
     * and the restore. Shared by {@code set_infobase_credentials}, {@code create_infobase} and
     * {@code delete_infobase}. Without it two writes at once interleave: the second snapshot is
     * taken after the first write stripped the ids, so the second write protects nothing while
     * the first answer has already claimed a restore.
     */
    public static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    /**
     * One launch configuration as the guard addresses it: a memento, which identifies it, and the
     * display name, which is only ever printed. A configuration that could not be addressed by a
     * memento is listed with an empty one; the snapshot names it as unprotected, and the restore
     * names a snapshotted configuration of that name as not read rather than as gone.
     */
    public static final class Configuration
    {
        /** Identifies the configuration in the launch manager; empty when it could not be had. */
        public final String memento;

        /** What the answer calls the configuration. */
        public final String name;

        /**
         * Why the memento could not be had, or <code>null</code> when it could. Set only together
         * with an empty {@link #memento}; the answer carries it so the failure is named, not
         * guessed at.
         */
        public final String addressingFailure;

        /**
         * @param memento the configuration's memento
         * @param name the configuration's display name
         */
        public Configuration(String memento, String name)
        {
            this(memento, name, null);
        }

        /**
         * @param memento the configuration's memento, empty when it could not be had
         * @param name the configuration's display name
         * @param addressingFailure why the memento could not be had; <code>null</code> when it
         *        could
         */
        public Configuration(String memento, String name, String addressingFailure)
        {
            this.memento = memento;
            this.name = name;
            this.addressingFailure = addressingFailure;
        }
    }

    /**
     * The three things the guard asks of the launch configurations, so the logic runs against a
     * fake in tests and against Eclipse's launch manager in the product.
     */
    public interface Access
    {
        /**
         * @return every launch configuration, in the manager's order; a configuration that could
         *         not be addressed by a memento is listed with an empty one, the reason set on
         *         {@link Configuration#addressingFailure}
         * @throws Exception when the configurations cannot be listed at all - the caller must not
         *         read an empty answer as "the workspace has none"
         */
        List<Configuration> configurations()
            throws Exception;

        /**
         * @param memento the configuration's memento
         * @return its application id, or <code>null</code> when the configuration is gone or the
         *         attribute is not set
         */
        String readApplicationId(String memento);

        /**
         * Sets the application id of a configuration, every other attribute untouched.
         *
         * @param memento the configuration's memento
         * @param applicationId the value to set
         * @throws Exception when the write fails
         */
        void writeApplicationId(String memento, String applicationId)
            throws Exception;
    }

    /**
     * An infobase-list write run under {@link #WRITE_LOCK}: the write itself and whatever it needs
     * around it, with the guard's snapshot in hand. Every operation that saves the infobase list
     * ({@code set_infobase_credentials}, {@code create_infobase}, {@code delete_infobase}) runs
     * its write through {@link #underWriteLock(Access, GuardedWrite)}.
     */
    public interface GuardedWrite<R>
    {
        /**
         * @param snapshot what the guard held before the write, or <code>null</code> when the
         *            launch configurations could not be reached at all
         * @return what the caller needs from the write
         */
        R write(SnapshotResult snapshot);
    }

    /** One configuration as it stood before the write. */
    public static final class SnapshotEntry
    {
        /**
         * The display name as it stood then. The answer names the configuration by it: the
         * memento encodes the {@code .launch} file's path and name, so a rename made during the
         * write stops it from resolving and the guard can only name the configuration gone.
         */
        public final String name;

        /** The application id that has to stand after the write. */
        public final String applicationId;

        /**
         * @param name the configuration's display name at snapshot time
         * @param applicationId the application id it carried
         */
        public SnapshotEntry(String name, String applicationId)
        {
            this.name = name;
            this.applicationId = applicationId;
        }
    }

    /** What a restore did, configuration by configuration, for the caller's answer. */
    public static final class RestoreReport
    {
        /** Configurations whose stripped id was put back and read back as written. */
        public final List<String> restored = new ArrayList<>();

        /** Configurations whose id was still there; nothing was written to them. */
        public final List<String> kept = new ArrayList<>();

        /** Configurations whose id was set to another value since the snapshot; left untouched. */
        public final List<String> changedMeanwhile = new ArrayList<>();

        /** Configurations that no longer exist. */
        public final List<String> gone = new ArrayList<>();

        /**
         * Configurations that were listed after the write but could not be addressed, as
         * "name (reason)": the configuration is there, the guard cannot reach it, and its id
         * stays off. Naming them "gone" would guess a rename or a deletion nobody saw.
         */
        public final List<String> notReadOnRestore = new ArrayList<>();

        /** Excluded configurations whose id is gone, as their application was deleted. */
        public final List<String> excludedStripped = new ArrayList<>();

        /** Excluded configurations that still carry an id: the binding outlived its application. */
        public final List<String> excludedKept = new ArrayList<>();

        /** Configurations whose id was written and did not read back: the value did not stay. */
        public final List<String> lost = new ArrayList<>();

        /** Configurations whose id could not be written back at all. */
        public final List<String> failed = new ArrayList<>();

        /**
         * Why the configurations could not be listed after the write, or <code>null</code> when
         * they were. Nothing is put back when this is set: without the list the guard cannot tell
         * a gone configuration from a stripped one.
         */
        public String listingFailed;

        /**
         * @return whether the restore has nothing to report - no write, no conflict, no loss
         */
        public boolean isQuiet()
        {
            return restored.isEmpty() && changedMeanwhile.isEmpty() && gone.isEmpty()
                && notReadOnRestore.isEmpty() && excludedStripped.isEmpty() && excludedKept.isEmpty()
                && lost.isEmpty() && failed.isEmpty() && listingFailed == null;
        }

        /**
         * The report as one sentence for a tool answer. Configurations that were left alone
         * because nothing had happened to them are not named: silence about them is the fact.
         *
         * @return what the restore did; an empty string when it did nothing worth reporting
         */
        public String describe()
        {
            List<String> parts = new ArrayList<>();
            if (listingFailed != null)
            {
                parts.add(listingFailed);
            }
            if (!restored.isEmpty())
            {
                parts.add("restored the application id of: " + String.join(", ", restored)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!changedMeanwhile.isEmpty())
            {
                parts.add("left " + String.join(", ", changedMeanwhile) //$NON-NLS-1$ //$NON-NLS-2$
                    + " untouched - the application id was set to another value after the snapshot"); //$NON-NLS-1$
            }
            if (!gone.isEmpty())
            {
                parts.add("found gone (renamed or deleted during the write): " //$NON-NLS-1$
                    + String.join(", ", gone)); //$NON-NLS-1$
            }
            if (!notReadOnRestore.isEmpty())
            {
                parts.add("not read on restore, so the application id was not put back: " //$NON-NLS-1$
                    + String.join(", ", notReadOnRestore)); //$NON-NLS-1$
            }
            if (!excludedStripped.isEmpty())
            {
                parts.add("left without an application id after deletion: " //$NON-NLS-1$
                    + String.join(", ", excludedStripped)); //$NON-NLS-1$
            }
            if (!excludedKept.isEmpty())
            {
                parts.add("still carry the application id of the deleted application: " //$NON-NLS-1$
                    + String.join(", ", excludedKept)); //$NON-NLS-1$
            }
            if (!lost.isEmpty())
            {
                parts.add("put the application id back and it did not stay: " //$NON-NLS-1$
                    + String.join(", ", lost)); //$NON-NLS-1$
            }
            if (!failed.isEmpty())
            {
                parts.add("could not restore the application id of: " + String.join(", ", failed)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return String.join("; ", parts); //$NON-NLS-1$
        }
    }

    private LaunchApplicationIds()
    {
    }

    /**
     * What the snapshot holds and what it could not protect, for the caller's answer. A snapshot
     * that protects nothing says so: silence here would read as "nothing was at risk".
     */
    public static final class SnapshotResult
    {
        /** Configuration memento to what it held before the write, in the manager's order. */
        public final Map<String, SnapshotEntry> held = new LinkedHashMap<>();

        /**
         * Configurations that could not be addressed by a memento, named so the answer can say
         * the write protected nothing of theirs.
         */
        public final List<String> unprotected = new ArrayList<>();

        /**
         * Why the configurations could not be listed at all, or <code>null</code> when they were.
         * Nothing is held when this is set.
         */
        public String listingFailed;

        /**
         * @return whether the snapshot ran clean - every configuration listed and addressed
         */
        public boolean isQuiet()
        {
            return listingFailed == null && unprotected.isEmpty();
        }

        /**
         * The snapshot's own outcome as one sentence for a tool answer: what could not be listed,
         * and what could not be addressed.
         *
         * @return the sentence; an empty string when the snapshot ran clean
         */
        public String describe()
        {
            List<String> parts = new ArrayList<>();
            if (listingFailed != null)
            {
                parts.add(listingFailed);
            }
            if (!unprotected.isEmpty())
            {
                parts.add("not protected: no memento: " + String.join(", ", unprotected)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return String.join("; ", parts); //$NON-NLS-1$
        }
    }

    /**
     * Remembers the application id of every configuration that carries one. Call this before the
     * write that saves the infobase list.
     *
     * @param access the launch configurations
     * @return what the write must put back, and what the snapshot could not protect; never
     *         <code>null</code>
     */
    public static SnapshotResult snapshot(Access access)
    {
        SnapshotResult result = new SnapshotResult();
        List<Configuration> configurations;
        try
        {
            configurations = access.configurations();
        }
        catch (Exception e)
        {
            // A failed listing and an empty workspace look the same from here. Only the failure
            // is reported, so a write that protected nothing is never mistaken for one that had
            // nothing to protect.
            result.listingFailed = "the launch configurations could not be listed before the write (" //$NON-NLS-1$
                + message(e) + "), so the write protected nothing"; //$NON-NLS-1$
            return result;
        }
        for (Configuration configuration : configurations)
        {
            if (configuration.memento == null || configuration.memento.isEmpty())
            {
                result.unprotected.add(configuration.name);
                continue;
            }
            String applicationId = access.readApplicationId(configuration.memento);
            if (applicationId != null && !applicationId.isEmpty())
            {
                result.held.put(configuration.memento,
                    new SnapshotEntry(configuration.name, applicationId));
            }
        }
        return result;
    }

    /**
     * Puts back every id the write stripped. An id is written only where none stands now: a
     * configuration whose attribute still holds the snapshotted value is not touched, and one
     * whose attribute was set to another value after the snapshot keeps that value and is named
     * in the report instead. Every write is read back once all of them are done, and the report
     * is built from that reading: one that does not read back as written is named as lost rather
     * than claimed. When the configurations cannot be listed at all, nothing is written and the
     * report names that failure instead.
     *
     * @param access the launch configurations
     * @param snapshot the held map of what {@link #snapshot(Access)} returned before the write
     * @return what was restored, kept, refused, lost and failed, or why nothing was; never
     *         <code>null</code>
     */
    public static RestoreReport restore(Access access, Map<String, SnapshotEntry> snapshot)
    {
        return restore(access, snapshot, Collections.emptySet());
    }

    /**
     * Puts back every stripped id except those belonging to an application that the list write
     * deliberately deleted. Exclusions name configurations by memento, rather than by application
     * id, because application ids are scoped to projects and the same spelling can validly occur
     * in two projects.
     *
     * @param access the launch configurations
     * @param snapshot the held map of what {@link #snapshot(Access)} returned before the write
     * @param excludedMementos configurations whose removed application id must stay removed
     * @return what was restored, kept, refused, deliberately omitted, lost and failed, or why
     *         nothing was
     */
    public static RestoreReport restore(Access access, Map<String, SnapshotEntry> snapshot,
        Set<String> excludedMementos)
    {
        return restore(access, snapshot, excludedMementos, Collections.emptySet());
    }

    /**
     * Puts back every stripped id except two kinds of configurations. The ones named by
     * {@code excludedMementos} belong to the application the list write deliberately deleted;
     * their removed id stays removed and the report names them as excluded. The ones named by
     * {@code unverifiedMementos} the caller could not place - it could not read them or resolve
     * what they point at - so their id stays removed too, but the report never names them: the
     * caller that excluded them names them in its own answer, and naming them here as well would
     * say the same thing twice.
     *
     * @param access the launch configurations
     * @param snapshot the held map of what {@link #snapshot(Access)} returned before the write
     * @param excludedMementos configurations whose removed application id must stay removed
     * @param unverifiedMementos configurations left unrestored because the caller could not place
     *        them; never named in the report
     * @return what was restored, kept, refused, deliberately omitted, lost and failed, or why
     *         nothing was; never <code>null</code>
     */
    public static RestoreReport restore(Access access, Map<String, SnapshotEntry> snapshot,
        Set<String> excludedMementos, Set<String> unverifiedMementos)
    {
        RestoreReport report = new RestoreReport();
        List<Configuration> configurations;
        try
        {
            configurations = access.configurations();
        }
        catch (Exception e)
        {
            // Without the list the guard cannot tell a gone configuration from a stripped one. It
            // writes nothing and names the failure rather than calling every configuration gone.
            report.listingFailed = "the launch configurations could not be listed after the write (" //$NON-NLS-1$
                + message(e) + "), so nothing was put back"; //$NON-NLS-1$
            return report;
        }
        Set<String> present = new HashSet<>();
        Map<String, String> unaddressable = new HashMap<>();
        for (Configuration configuration : configurations)
        {
            if (configuration.memento == null || configuration.memento.isEmpty())
            {
                // Listed but unaddressable: the configuration is there, the guard just cannot
                // reach it. A snapshotted entry of this name is named as not read, not as gone.
                unaddressable.put(configuration.name, configuration.addressingFailure == null
                    ? "no memento" : configuration.addressingFailure); //$NON-NLS-1$
            }
            else
            {
                present.add(configuration.memento);
            }
        }
        // The mementos written to, in the order they were written: read back once, after all
        // writes, so what the report says is what the store holds when the guard is done.
        Map<String, String> written = new LinkedHashMap<>();
        for (Map.Entry<String, SnapshotEntry> entry : snapshot.entrySet())
        {
            String memento = entry.getKey();
            SnapshotEntry before = entry.getValue();
            if (!present.contains(memento))
            {
                if (unverifiedMementos.contains(memento))
                {
                    // The caller that excluded it names it in its own answer; the report stays
                    // silent about it.
                    continue;
                }
                String addressingFailure = unaddressable.get(before.name);
                if (addressingFailure != null)
                {
                    report.notReadOnRestore.add(before.name + " (" + addressingFailure + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                {
                    report.gone.add(before.name);
                }
                continue;
            }
            String current = access.readApplicationId(memento);
            boolean stripped = current == null || current.isEmpty();
            if (excludedMementos.contains(memento))
            {
                // The application is gone with its infobase; putting the binding back would point
                // the configuration at an application that no longer exists.
                (stripped ? report.excludedStripped : report.excludedKept).add(before.name);
                continue;
            }
            if (unverifiedMementos.contains(memento))
            {
                // Excluded without being identified: the caller that excluded it names it in its
                // own answer; the report stays silent about it.
                continue;
            }
            if (!stripped)
            {
                if (before.applicationId.equals(current))
                {
                    report.kept.add(before.name);
                }
                else
                {
                    // Somebody set another value after the snapshot; that edit wins over the guard.
                    report.changedMeanwhile.add(before.name);
                }
                continue;
            }
            try
            {
                access.writeApplicationId(memento, before.applicationId);
            }
            catch (Exception e)
            {
                report.failed.add(before.name);
                continue;
            }
            written.put(memento, before.name);
        }
        for (Map.Entry<String, String> entry : written.entrySet())
        {
            String expected = snapshot.get(entry.getKey()).applicationId;
            String current = access.readApplicationId(entry.getKey());
            if (expected.equals(current))
            {
                report.restored.add(entry.getValue());
            }
            else
            {
                report.lost.add(entry.getValue());
            }
        }
        return report;
    }

    /**
     * Runs one infobase-list write under the one write lock: the snapshot, the write and the
     * restore of one write must not interleave with another's, or the second write snapshots the
     * ids the first write already stripped and protects nothing while the first answer has
     * already claimed a restore.
     *
     * @param access the launch configurations; <code>null</code> when there is no launch manager,
     *        in which case the write gets a <code>null</code> snapshot
     * @param guarded the write to run under the lock
     * @return what the write returned
     */
    public static <R> R underWriteLock(Access access, GuardedWrite<R> guarded)
    {
        WRITE_LOCK.lock();
        try
        {
            return guarded.write(access == null ? null : snapshot(access));
        }
        finally
        {
            WRITE_LOCK.unlock();
        }
    }

    /** The message of a failure, or its class name when it carries none. */
    private static String message(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
