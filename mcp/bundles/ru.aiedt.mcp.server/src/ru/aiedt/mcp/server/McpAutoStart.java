/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IStartup;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.SupportSnapshotStore;
import ru.aiedt.mcp.server.upkeep.ReleaseSweep;

/**
 * Brings the plugin up with the workbench, and opens the endpoint if that is what the user asked
 * for.
 * <p>
 * The bundle is activated lazily, so left to itself it would lie there until somebody happened to
 * touch one of its classes. The <code>org.eclipse.ui.startup</code> extension naming this class is
 * what makes the workbench load it, which activates the bundle, which builds the {@link Activator}
 * and the {@link McpHttpEndpoint} behind it. That happens even with auto-start switched off - which is how
 * it ships - and it is half the reason this class exists: without it there would be no status bar,
 * no registry, and nothing to start by hand either.
 * </p>
 * <p>
 * The workbench instantiates it once per session and calls it on a thread of its own, well away from
 * the UI thread. Nothing here is scheduled, delayed or retried: the socket is bound on that thread,
 * there and then, or it is not bound at all.
 * </p>
 */
public class McpAutoStart
    implements IStartup
{
    /**
     * Opens the endpoint, if the preferences say to.
     * <p>
     * A port already in use is the failure that actually happens - a second EDT, or a process that
     * outlived its workbench. It is written to the log and nothing else: EDT is perfectly usable with
     * no server, a modal complaint during startup would be worse than the grey indicator in the
     * status bar that the user gets instead, and from that indicator the server can be started by
     * hand on a port that is free.
     * </p>
     * <p>
     * The whole body sits inside the catch, so that nothing of ours can reach the workbench's
     * early-startup runner. An {@link Error} is deliberately let through: a broken installation
     * should be loud, not logged.
     * </p>
     */
    @Override
    public void earlyStartup()
    {
        try
        {
            // The plugin is not checked for null. The workbench had to activate this bundle to reach
            // this class at all, so the activator has run and the server it builds is already there.
            Activator activator = Activator.getDefault();
            IPreferenceStore preferences = activator.getPreferenceStore();

            // Ahead of the auto-start check, and that placement is the whole point: watching for a
            // new version has nothing to do with whether the endpoint is listening, and the user
            // who keeps the server switched off is exactly the one who would otherwise never be
            // told. It never throws, so the endpoint below is not at its mercy.
            ReleaseSweep.get().start();

            // The second of the two moments cleanup runs; the other is when a merge settles.
            // A session that ended without settling one leaves its snapshot behind, and the
            // next start is the first chance anything has to look. Protected snapshots are
            // left alone here as everywhere: only the ordinary ones past the limit go.
            pruneSupportSnapshots();

            if (!preferences.getBoolean(PrefKeys.PREF_AUTO_START))
            {
                return;
            }

            int port = preferences.getInt(PrefKeys.PREF_PORT);
            activator.getMcpServer().start(port);
            // Read back rather than reported from the preference: the server takes the first free
            // port of a range, so the configured number and the listening number are two different
            // facts and only one of them can be dialled.
            port = activator.getMcpServer().getPort();
            Activator.logInfo("AI-EDT endpoint came up automatically on port " + port); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("AI-EDT endpoint could not come up automatically", e); //$NON-NLS-1$
        }
    }

    /**
     * Removes the ordinary support snapshots that sit past the limit, in every open project.
     * <p>
     * Never throws and never blocks the rest of start-up: a directory that cannot be read is one
     * project's snapshots left where they are, which costs disk and nothing else.
     * </p>
     */
    private static void pruneSupportSnapshots()
    {
        try
        {
            int removed = 0;
            for (org.eclipse.core.resources.IProject project : org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot().getProjects())
            {
                if (!project.isOpen() || project.getLocation() == null)
                {
                    continue;
                }
                removed += SupportSnapshotStore.prune(
                    project.getLocation().toFile().toPath().resolve(".settings"), //$NON-NLS-1$
                    SupportSnapshotStore.KEPT);
            }
            if (removed > 0)
            {
                Activator.logInfo("Removed " + removed + " support snapshot(s) past the limit of " //$NON-NLS-1$ //$NON-NLS-2$
                    + SupportSnapshotStore.KEPT + "; protected ones were left."); //$NON-NLS-1$
            }
        }
        catch (Exception | LinkageError cannotSweep)
        {
            Activator.logWarning("support snapshots could not be swept at start-up: " //$NON-NLS-1$
                + cannotSweep);
        }
    }
}
