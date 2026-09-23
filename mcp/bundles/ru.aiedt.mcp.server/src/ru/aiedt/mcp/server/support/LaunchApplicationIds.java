/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public final class LaunchApplicationIds
{
    /**
     * One launch configuration as the guard addresses it: a memento, which identifies it, and the
     * display name, which is only ever printed.
     */
    public static final class Configuration
    {
        /** Identifies the configuration in the launch manager. */
        public final String memento;

        /** What the answer calls the configuration. */
        public final String name;

        /**
         * @param memento the configuration's memento
         * @param name the configuration's display name
         */
        public Configuration(String memento, String name)
        {
            this.memento = memento;
            this.name = name;
        }
    }

    /**
     * The three things the guard asks of the launch configurations, so the logic runs against a
     * fake in tests and against Eclipse's launch manager in the product.
     */
    public interface Access
    {
        /**
         * @return every launch configuration, in the manager's order
         */
        List<Configuration> configurations();

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

    /** One configuration as it stood before the write. */
    public static final class SnapshotEntry
    {
        /** The display name as it stood then: a memento survives a rename, a name does not. */
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

        /** Excluded configurations whose id is gone, as their application was deleted. */
        public final List<String> excludedStripped = new ArrayList<>();

        /** Excluded configurations that still carry an id: the binding outlived its application. */
        public final List<String> excludedKept = new ArrayList<>();

        /** Configurations whose id was written and did not read back: the value did not stay. */
        public final List<String> lost = new ArrayList<>();

        /** Configurations whose id could not be written back at all. */
        public final List<String> failed = new ArrayList<>();

        /**
         * @return whether the restore has nothing to report - no write, no conflict, no loss
         */
        public boolean isQuiet()
        {
            return restored.isEmpty() && changedMeanwhile.isEmpty() && gone.isEmpty()
                && excludedStripped.isEmpty() && excludedKept.isEmpty() && lost.isEmpty()
                && failed.isEmpty();
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
                parts.add("found gone: " + String.join(", ", gone)); //$NON-NLS-1$ //$NON-NLS-2$
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
     * Remembers the application id of every configuration that carries one. Call this before the
     * write that saves the infobase list.
     *
     * @param access the launch configurations
     * @return configuration memento to what it held before the write, in the manager's order;
     *         never <code>null</code>
     */
    public static Map<String, SnapshotEntry> snapshot(Access access)
    {
        Map<String, SnapshotEntry> held = new LinkedHashMap<>();
        for (Configuration configuration : access.configurations())
        {
            String applicationId = access.readApplicationId(configuration.memento);
            if (applicationId != null && !applicationId.isEmpty())
            {
                held.put(configuration.memento, new SnapshotEntry(configuration.name, applicationId));
            }
        }
        return held;
    }

    /**
     * Puts back every id the write stripped. An id is written only where none stands now: a
     * configuration whose attribute still holds the snapshotted value is not touched, and one
     * whose attribute was set to another value after the snapshot keeps that value and is named
     * in the report instead. Every write is read back once all of them are done, and the report
     * is built from that reading: one that does not read back as written is named as lost rather
     * than claimed.
     *
     * @param access the launch configurations
     * @param snapshot what {@link #snapshot(Access)} returned before the write
     * @return what was restored, kept, refused, lost and failed; never <code>null</code>
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
     * @param snapshot what {@link #snapshot(Access)} returned before the write
     * @param excludedMementos configurations whose removed application id must stay removed
     * @return what was restored, kept, refused, deliberately omitted, lost and failed
     */
    public static RestoreReport restore(Access access, Map<String, SnapshotEntry> snapshot,
        Set<String> excludedMementos)
    {
        RestoreReport report = new RestoreReport();
        Set<String> present = new HashSet<>();
        for (Configuration configuration : access.configurations())
        {
            present.add(configuration.memento);
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
                report.gone.add(before.name);
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
}
