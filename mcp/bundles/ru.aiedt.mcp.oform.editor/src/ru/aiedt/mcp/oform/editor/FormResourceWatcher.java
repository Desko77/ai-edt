/** AI-EDT Ordinary Form Editor. Copyright (C) 2026 Desko77. AGPL-3.0-or-later. */
package ru.aiedt.mcp.oform.editor;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.widgets.Display;
import ru.aiedt.mcp.oform.session.FormEditingSession;
import ru.aiedt.mcp.oform.session.OpenFormSessions;

/** One receiver per editor. Resource callbacks never wait for SWT or hold widgets. */
final class FormResourceWatcher implements OpenFormSessions.Notice, AutoCloseable
{
    private final Display display;
    private final FormEditingSession session;
    private final Runnable refresh;
    private final Runnable removed;
    private volatile boolean closed;

    FormResourceWatcher(Display display, FormEditingSession session, Runnable refresh, Runnable removed)
    {
        this.display = display; this.session = session; this.refresh = refresh; this.removed = removed;
        OpenFormSessions.addNotice(this);
    }
    private void schedule(FormEditingSession changed, boolean removal)
    {
        if (changed != session || closed || display.isDisposed()) { return; }
        try {
            display.asyncExec(() -> {
                if (!closed && !display.isDisposed()) {
                    if (removal || session.file() == null) { removed.run(); }
                    else { refresh.run(); }
                }
            });
        } catch (org.eclipse.swt.SWTException e) {
            if (!display.isDisposed()) { throw e; }
        }
    }
    public void moved(FormEditingSession s, IFile from, IFile to) { schedule(s, false); }
    public void changed(FormEditingSession s, IFile file) { schedule(s, false); }
    public void removed(FormEditingSession s, IFile file) { schedule(s, true); }
    public void close() { closed = true; OpenFormSessions.removeNotice(this); }
}
