/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.session;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.Activator;

/**
 * Tracks file changes in the workspace for scoped validation.
 * <p>
 * Monitors changes to 1C-related files (.bsl, .mdo, .form, .dcs, .mxl)
 * and provides API for querying which files were modified since last reset.
 * This enables scoped validation - checking only changed objects instead
 * of the entire project.
 * </p>
 */
public final class SessionChangeTracker
{
    /** File extensions to track */
    private static final Set<String> TRACKED_EXTENSIONS = Set.of(
        "bsl", "mdo", "form", "dcs", "os", "mxl" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    );

    /** Path segments to ignore */
    private static final String[] IGNORED_SEGMENTS = {
        "/.derived/", "/.bm/", "/.settings/", "/bin/", "/target/", "/.git/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    };

    /** Event listener flags: POST_CHANGE | PRE_DELETE | PRE_CLOSE */
    private static final int LISTENER_FLAGS =
        IResourceChangeEvent.POST_CHANGE
        | IResourceChangeEvent.PRE_DELETE
        | IResourceChangeEvent.PRE_CLOSE;

    /** Set of modified file paths (thread-safe) */
    private static final Set<String> modifiedPaths = ConcurrentHashMap.newKeySet();

    /** Total events processed */
    private static final AtomicLong eventCount = new AtomicLong(0);

    /** Resource change listener */
    private static volatile IResourceChangeListener listener;

    private SessionChangeTracker()
    {
        // Static utility class
    }

    /**
     * Initializes the tracker by registering a workspace change listener.
     * Safe to call multiple times - subsequent calls are no-ops.
     */
    public static synchronized void initialize()
    {
        if (listener != null)
        {
            return;
        }

        try
        {
            listener = SessionChangeTracker::handleEvent;
            ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, LISTENER_FLAGS);
            Activator.logInfo("SessionChangeTracker initialized"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to initialize SessionChangeTracker", e); //$NON-NLS-1$
            listener = null;
        }
    }

    /**
     * Shuts down the tracker and removes the workspace listener.
     */
    public static synchronized void shutdown()
    {
        if (listener != null)
        {
            try
            {
                ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
            }
            catch (Exception e)
            {
                // Workspace may already be closing
            }
            listener = null;
        }
        modifiedPaths.clear();
        eventCount.set(0);
        Activator.logInfo("SessionChangeTracker shut down"); //$NON-NLS-1$
    }

    /**
     * Returns an unmodifiable copy of all modified file paths since last clear.
     *
     * @return set of modified file paths
     */
    public static Set<String> getModifiedPaths()
    {
        return Collections.unmodifiableSet(Set.copyOf(modifiedPaths));
    }

    /**
     * Checks if a specific path was modified.
     *
     * @param path the file path to check
     * @return true if the path was modified
     */
    public static boolean contains(String path)
    {
        return modifiedPaths.contains(path);
    }

    /**
     * Clears all tracked modifications (e.g., after validation).
     */
    public static void clear()
    {
        modifiedPaths.clear();
    }

    /**
     * Returns the number of currently tracked modified paths.
     *
     * @return count of modified paths
     */
    public static int size()
    {
        return modifiedPaths.size();
    }

    /**
     * Returns the total number of resource change events processed.
     *
     * @return event count
     */
    public static long getEventCount()
    {
        return eventCount.get();
    }

    /**
     * Checks if the tracker is currently active.
     *
     * @return true if initialized and listening
     */
    public static boolean isActive()
    {
        return listener != null;
    }

    /**
     * Handles a workspace resource change event.
     */
    private static void handleEvent(IResourceChangeEvent event)
    {
        eventCount.incrementAndGet();

        int type = event.getType();

        // For project deletion/close - remove all paths from that project
        if (type == IResourceChangeEvent.PRE_DELETE || type == IResourceChangeEvent.PRE_CLOSE)
        {
            IResource resource = event.getResource();
            if (resource instanceof IProject)
            {
                String projectPrefix = "/" + resource.getName() + "/"; //$NON-NLS-1$ //$NON-NLS-2$
                modifiedPaths.removeIf(path -> path.startsWith(projectPrefix));
            }
            return;
        }

        // For POST_CHANGE - walk the delta tree
        IResourceDelta delta = event.getDelta();
        if (delta == null)
        {
            return;
        }

        try
        {
            delta.accept(resourceDelta -> {
                IResource resource = resourceDelta.getResource();

                // Only track files
                if (!(resource instanceof IFile))
                {
                    return true; // continue visiting children
                }

                int kind = resourceDelta.getKind();

                // Only track additions and changes
                if (kind != IResourceDelta.ADDED && kind != IResourceDelta.CHANGED)
                {
                    return true;
                }

                // For CHANGED events, check that content actually changed
                if (kind == IResourceDelta.CHANGED)
                {
                    int flags = resourceDelta.getFlags();
                    if ((flags & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) == 0)
                    {
                        return true; // no content change
                    }
                }

                String path = resource.getFullPath().toString();

                // Check extension
                if (!hasTrackedExtension(path))
                {
                    return true;
                }

                // Check ignored paths
                if (shouldIgnore(path))
                {
                    return true;
                }

                modifiedPaths.add(path);
                return true;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error processing resource change event", e); //$NON-NLS-1$
        }
    }

    /**
     * Checks if the file has a tracked extension.
     */
    private static boolean hasTrackedExtension(String path)
    {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0)
        {
            return false;
        }
        String ext = path.substring(dotIndex + 1).toLowerCase();
        return TRACKED_EXTENSIONS.contains(ext);
    }

    /**
     * Checks if the path should be ignored.
     */
    private static boolean shouldIgnore(String path)
    {
        return Arrays.stream(IGNORED_SEGMENTS).anyMatch(path::contains);
    }
}
