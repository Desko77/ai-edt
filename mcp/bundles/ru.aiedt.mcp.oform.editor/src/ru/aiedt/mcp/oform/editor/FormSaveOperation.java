/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform.editor;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.jface.operation.IRunnableWithProgress;

import ru.aiedt.mcp.oform.session.FormEditingSession;

/**
 * One save as a progress runnable the editor hands to the Eclipse progress service (or runs
 * directly when its caller is not the UI thread). The service forks the runnable and keeps the UI
 * thread dispatching events while the caller waits for it, so the save may wait for the
 * workspace's rule an MCP call holds without the UI thread waiting for that rule. Cancellation
 * comes from either monitor: the service's own and the caller's.
 */
final class FormSaveOperation implements IRunnableWithProgress
{
    private final FormEditingSession session;

    private final IProgressMonitor caller;

    private FormEditingSession.Save save;

    FormSaveOperation(FormEditingSession session, IProgressMonitor caller)
    {
        this.session = session;
        this.caller = caller;
    }

    @Override public void run(IProgressMonitor monitor) throws InterruptedException
    {
        save = session.save(new Combined(monitor, caller));
    }

    /** @return what the save did, once {@link #run} returned */
    FormEditingSession.Save save()
    {
        return save;
    }

    /** Runs the operation without the progress service, for a caller that is not the UI thread. */
    FormEditingSession.Save runDirectly()
    {
        IProgressMonitor monitor = caller == null ? new NullProgressMonitor() : caller;
        try
        {
            run(monitor);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return save;
    }

    /** The service monitor with the caller's cancellation added. */
    private static final class Combined extends ProgressMonitorWrapper
    {
        private final IProgressMonitor caller;

        Combined(IProgressMonitor service, IProgressMonitor caller)
        {
            super(service);
            this.caller = caller;
        }

        @Override public boolean isCanceled()
        {
            return super.isCanceled() || (caller != null && caller.isCanceled());
        }
    }
}
