/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.MultiRule;

import ru.aiedt.mcp.oform.session.OpenFormSessions;

/**
 * The one door every write of a {@code Form.oform} container goes through. A write is the
 * container's read, the comparison with what the caller observed and the {@code setContents}
 * behind it, and all of that runs inside one rule of the workspace and under the registry lock
 * of the file, taken in that order: the rule first, the lock second, and nothing that holds the
 * lock ever asks for a rule or waits for the UI thread. That order is what keeps an agent's
 * write and an editor's edit from losing each other - the agent cannot slip a write between the
 * session's comparison of the file with its baseline and the fixation of an edit, and the session
 * cannot fix an edit over bytes the agent has already replaced.
 *
 * <p>Each write path calls {@link #refuseUnlessAllowed} at its own point of writing, under the
 * lock: a connected text buffer with unsaved changes refuses the write, as before the guard
 * existed, and so does a live session of the form editor that holds a changed entry the write
 * would replace - the session named as the initiator replaces its own edits, which is its save.</p>
 *
 * <p>The rule is the workspace's own: {@code modifyRule} of the file that exists, and for a
 * container still to be created the creation rule of the topmost folder that does not exist yet
 * in one rule with the file's creation rule, so the folders a creation or an import makes and
 * the file itself are written under the same rule as the rest of the write.</p>
 */
public final class ContainerWriteGuard
{
    /** The guarded work of one write path: the read, the comparison and the write. */
    @FunctionalInterface
    public interface GuardedWrite<T>
    {
        /**
         * Performs the work.
         *
         * @return what the work answers
         * @throws IOException when the work refuses or fails
         * @throws CoreException when the workspace refuses the write
         */
        T run() throws IOException, CoreException;
    }

    private ContainerWriteGuard()
    {
        // static guard
    }

    /**
     * Runs the write of one container under the workspace's rule on it and the registry lock of
     * its session.
     *
     * @param <T> what the work answers
     * @param container the {@code Form.oform} the work writes
     * @param work the write
     * @return what the work answered
     * @throws IOException when the work refuses or fails
     * @throws CoreException when the workspace refuses the rule or the write
     */
    public static <T> T write(IFile container, GuardedWrite<T> work) throws IOException, CoreException
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        ISchedulingRule rule = ruleFor(workspace.getRuleFactory(), container);
        Object[] out = new Object[1];
        IOException[] refusal = new IOException[1];
        IWorkspaceRunnable guarded = monitor -> {
            try
            {
                out[0] = underTheRegistryLock(container, work);
            }
            catch (IOException e)
            {
                refusal[0] = e;
            }
        };
        workspace.run(guarded, rule, IResource.NONE, new NullProgressMonitor());
        if (refusal[0] != null)
        {
            throw refusal[0];
        }
        return cast(out[0]);
    }

    /**
     * Runs the write of one container for a caller that already holds the workspace's rule on it -
     * a save that took the rule over its whole comparison, write and new baseline. Only the
     * registry lock is taken; a caller inside {@code IWorkspace.run} does not ask for its rule
     * again.
     *
     * @param <T> what the work answers
     * @param container the {@code Form.oform} the work writes
     * @param work the write
     * @return what the work answered
     * @throws IOException when the work refuses or fails
     * @throws CoreException when the workspace refuses the write
     */
    public static <T> T writeUnderHeldRule(IFile container, GuardedWrite<T> work)
        throws IOException, CoreException
    {
        return underTheRegistryLock(container, work);
    }

    /**
     * What every write path asks before it replaces the container: a connected text buffer with
     * unsaved changes refuses the write, and so does a live session of the form editor, other
     * than the initiator, that holds one of the entries the write replaces changed. A write that
     * replaces nothing - its text is the file's own - never reaches this.
     *
     * @param container the {@code Form.oform} the write replaces
     * @param initiator the session id of the writer, or {@code null} when no session writes
     * @param replacesForm whether the write replaces the layout entry
     * @param replacesModule whether the write replaces the module entry
     * @throws IOException when a dirty buffer or another session holds what the write replaces
     */
    public static void refuseUnlessAllowed(IFile container, String initiator, boolean replacesForm,
        boolean replacesModule) throws IOException
    {
        OrdinaryFormModule.refuseWhileAnEditorHoldsUnsavedChanges(container);
        if (OpenFormSessions.anotherHoldsChanged(container, initiator, replacesForm, replacesModule))
        {
            throw new IOException(container.getProjectRelativePath().toPortableString()
                + ": this form is open in the form editor with unsaved changes to the entry this " //$NON-NLS-1$
                + "write replaces. Save or close the editor and repeat the change. Nothing was written."); //$NON-NLS-1$
        }
    }

    /** The work under the registry lock of the file's session. */
    private static <T> T underTheRegistryLock(IFile container, GuardedWrite<T> work)
        throws IOException, CoreException
    {
        ReentrantLock lock = OpenFormSessions.lockFor(container);
        lock.lock();
        try
        {
            return work.run();
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * The rule of one write: the file's own modify rule when the file exists, and for a container
     * still to be created the creation rule of the topmost folder that does not exist yet in one
     * rule with the file's creation rule - the rule factory's creation rule of a folder covers
     * its parent and everything below it, so the whole chain of folders the write makes and the
     * file itself lies inside.
     *
     * @param factory the workspace's rule factory
     * @param container the {@code Form.oform} the work writes
     * @return the rule of the write
     */
    private static ISchedulingRule ruleFor(IResourceRuleFactory factory, IFile container)
    {
        if (container.exists())
        {
            return factory.modifyRule(container);
        }
        IContainer topmostMissing = null;
        for (IContainer parent = container.getParent(); parent != null && !parent.exists(); parent = parent
            .getParent())
        {
            topmostMissing = parent;
        }
        if (topmostMissing == null)
        {
            return factory.createRule(container);
        }
        return MultiRule.combine(factory.createRule(topmostMissing), factory.createRule(container));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value)
    {
        return (T)value;
    }
}
