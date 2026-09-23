/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
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
 */
public final class LaunchApplicationIds
{
    /**
     * The three things the guard asks of the launch configurations, so the logic runs against a
     * fake in tests and against Eclipse's launch manager in the product.
     */
    public interface Access
    {
        /**
         * @return the names of every launch configuration, in the manager's order
         */
        List<String> configurationNames();

        /**
         * @param name the configuration
         * @return its application id, or <code>null</code> when the configuration is gone or the
         *         attribute is not set
         */
        String readApplicationId(String name);

        /**
         * Sets the application id of a configuration, every other attribute untouched.
         *
         * @param name the configuration
         * @param applicationId the value to set
         * @throws Exception when the write fails
         */
        void writeApplicationId(String name, String applicationId)
            throws Exception;
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

        /** Configurations whose id could not be written back or did not read back as written. */
        public final List<String> failed = new ArrayList<>();

        /**
         * @return whether the restore has nothing to report - no write, no conflict, no loss
         */
        public boolean isQuiet()
        {
            return restored.isEmpty() && changedMeanwhile.isEmpty() && gone.isEmpty()
                && failed.isEmpty();
        }

        /**
         * The report as one sentence for a tool answer.
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
     * @return configuration name to application id, in the manager's order; never <code>null</code>
     */
    public static Map<String, String> snapshot(Access access)
    {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String name : access.configurationNames())
        {
            String applicationId = access.readApplicationId(name);
            if (applicationId != null && !applicationId.isEmpty())
            {
                ids.put(name, applicationId);
            }
        }
        return ids;
    }

    /**
     * Puts back every id the write stripped. An id is written only where none stands now: a
     * configuration whose attribute still holds the snapshotted value is not touched, and one
     * whose attribute was set to another value after the snapshot keeps that value and is named
     * in the report instead. Every write is read back; one that does not read back as written is
     * named as failed rather than claimed.
     *
     * @param access the launch configurations
     * @param snapshot what {@link #snapshot(Access)} returned before the write
     * @return what was restored, kept, refused, lost and failed; never <code>null</code>
     */
    public static RestoreReport restore(Access access, Map<String, String> snapshot)
    {
        RestoreReport report = new RestoreReport();
        Set<String> names = new HashSet<>(access.configurationNames());
        for (Map.Entry<String, String> entry : snapshot.entrySet())
        {
            String name = entry.getKey();
            String expected = entry.getValue();
            if (!names.contains(name))
            {
                report.gone.add(name);
                continue;
            }
            String current = access.readApplicationId(name);
            if (expected.equals(current))
            {
                report.kept.add(name);
                continue;
            }
            if (current != null && !current.isEmpty())
            {
                // Somebody set another value after the snapshot; that edit wins over the guard.
                report.changedMeanwhile.add(name);
                continue;
            }
            try
            {
                access.writeApplicationId(name, expected);
            }
            catch (Exception e)
            {
                report.failed.add(name);
                continue;
            }
            if (expected.equals(access.readApplicationId(name)))
            {
                report.restored.add(name);
            }
            else
            {
                report.failed.add(name);
            }
        }
        return report;
    }
}
