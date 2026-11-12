/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Watches the workspace for a move or a removal of a container that has a live editing session.
 * On {@code POST_CHANGE} the session is re-registered under the new path, or marked removed when
 * the delta has no {@code MOVED_TO}, before any {@link OpenFormSessions.Notice} runs.
 * <p>
 * The callback does not read or write file bytes, does not move a resource, does not take a
 * workspace scheduling rule and does not wait for the UI thread. The resource tree is already
 * updated when {@code POST_CHANGE} runs, so the new path is the one a later write has to lock.
 * </p>
 */
final class SessionResourceListener implements org.eclipse.core.resources.IResourceChangeListener
{
    private static final SessionResourceListener INSTANCE = new SessionResourceListener();

    private static volatile boolean installed;

    /** One listener for the workspace. */
    private SessionResourceListener()
    {
        // the single workspace listener
    }

    /** Installs the listener. A second call does nothing. */
    static synchronized void install()
    {
        if (installed)
        {
            return;
        }
        workspace().addResourceChangeListener(INSTANCE, org.eclipse.core.resources.IResourceChangeEvent.POST_CHANGE);
        installed = true;
    }

    /** Removes the listener. A call when it is not installed does nothing. */
    static synchronized void uninstall()
    {
        if (!installed)
        {
            return;
        }
        workspace().removeResourceChangeListener(INSTANCE);
        installed = false;
    }

    /** @return whether {@link #install} is in effect */
    static boolean isInstalled()
    {
        return installed;
    }

    /** @return the workspace the listener is registered with */
    private static IWorkspace workspace()
    {
        return ResourcesPlugin.getWorkspace();
    }

    /**
     * Re-registers every live session the delta moved, and marks one removed when the delta has
     * no {@code MOVED_TO}. Both happen before {@link OpenFormSessions#acceptNotices} runs. This
     * method does not read bytes, change a resource, take a workspace scheduling rule or wait
     * for the UI thread.
     *
     * @param event the workspace event; anything other than {@code POST_CHANGE} is ignored
     */
    @Override
    public void resourceChanged(org.eclipse.core.resources.IResourceChangeEvent event)
    {
        if (event.getType() != org.eclipse.core.resources.IResourceChangeEvent.POST_CHANGE)
        {
            return;
        }
        IResourceDelta delta = event.getDelta();
        if (delta == null)
        {
            return;
        }
        OpenFormSessions.beginDispatch();
        List<OpenFormSessions.PendingNotice> notices = new ArrayList<>();
        try
        {
            for (OpenFormSessions.Binding binding : OpenFormSessions.liveBindings())
            {
                IFile file = binding.file();
                if (file == null)
                {
                    continue;
                }
                IFile destination = movedFile(delta, file);
                if (destination != null)
                {
                    OpenFormSessions.Move move = OpenFormSessions.rebind(binding.session(), file, destination);
                    if (move == OpenFormSessions.Move.MOVED)
                    {
                        notices.add(OpenFormSessions.PendingNotice.moved(binding.session(), file, destination));
                    }
                    else if (move == OpenFormSessions.Move.REFUSED && binding.session().isRemoved())
                    {
                        notices.add(OpenFormSessions.PendingNotice.removed(binding.session(), file));
                    }
                }
                else if (delta.findMember(file.getFullPath()) != null
                    && delta.findMember(file.getFullPath()).getKind() == IResourceDelta.CHANGED)
                {
                    notices.add(OpenFormSessions.PendingNotice.changed(binding.session(), file));
                }
                else if (removedWithoutMove(delta, file))
                {
                    if (OpenFormSessions.detachRemoved(binding.session(), file))
                    {
                        notices.add(OpenFormSessions.PendingNotice.removed(binding.session(), file));
                    }
                }
            }
        }
        finally
        {
            OpenFormSessions.endDispatch();
        }
        OpenFormSessions.acceptNotices(notices);
    }

    /**
     * The file the container moved to: its own {@code MOVED_TO} when the delta has one, otherwise
     * the same relative path under an ancestor that moved.
     *
     * @param root the workspace delta
     * @param file the container the session is bound to
     * @return the file at the new path, or {@code null} when this delta is not a move of it
     */
    private static IFile movedFile(IResourceDelta root, IFile file)
    {
        IPath path = file.getFullPath();
        IResourceDelta own = root.findMember(path);
        if (own != null && movedTo(own) != null)
        {
            return file.getWorkspace().getRoot().getFile(movedTo(own));
        }
        IPath cursor = path;
        while (cursor.segmentCount() > 1)
        {
            cursor = cursor.removeLastSegments(1);
            IResourceDelta ancestor = root.findMember(cursor);
            IPath moved = ancestor == null ? null : movedTo(ancestor);
            if (moved != null)
            {
                IPath relative = path.makeRelativeTo(ancestor.getFullPath());
                IFile destination = file.getWorkspace().getRoot().getFile(moved.append(relative));
                // A child deleted in the same batch is not resurrected by its ancestor's move.
                return destination.exists() ? destination : null;
            }
        }
        return null;
    }

    /**
     * Whether the container was removed and neither it nor an ancestor carries {@code MOVED_TO}.
     *
     * @param root the workspace delta
     * @param file the container
     * @return whether the session should be marked removed
     */
    private static boolean removedWithoutMove(IResourceDelta root, IFile file)
    {
        IPath path = file.getFullPath();
        IResourceDelta own = root.findMember(path);
        if (own != null && own.getKind() == IResourceDelta.REMOVED && movedTo(own) == null)
        {
            return movedFile(root, file) == null;
        }
        IPath cursor = path;
        while (cursor.segmentCount() > 1)
        {
            cursor = cursor.removeLastSegments(1);
            IResourceDelta ancestor = root.findMember(cursor);
            if (ancestor == null)
            {
                continue;
            }
            if (movedTo(ancestor) != null)
            {
                return false;
            }
            if (ancestor.getKind() == IResourceDelta.REMOVED)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param root the workspace delta
     * @param path a resource path
     * @return the {@code MOVED_TO} path of the nearest ancestor, or {@code null}
     */
    private static IPath ancestorMove(IResourceDelta root, IPath path)
    {
        IPath cursor = path;
        while (cursor.segmentCount() > 1)
        {
            cursor = cursor.removeLastSegments(1);
            IResourceDelta ancestor = root.findMember(cursor);
            if (ancestor != null && movedTo(ancestor) != null)
            {
                return movedTo(ancestor);
            }
        }
        return null;
    }

    /**
     * @param delta a delta
     * @return its {@code MOVED_TO} path, or {@code null} when the flag is not set
     */
    private static IPath movedTo(IResourceDelta delta)
    {
        if ((delta.getFlags() & IResourceDelta.MOVED_TO) == 0)
        {
            return null;
        }
        return delta.getMovedToPath();
    }
}
