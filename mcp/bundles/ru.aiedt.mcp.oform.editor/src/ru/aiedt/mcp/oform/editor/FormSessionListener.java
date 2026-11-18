/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform.editor;

import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

import ru.aiedt.mcp.oform.session.FormEditingSession;

/**
 * One editor's receiver of its session's state changes, so every editor of the file shows the
 * same draft and the same dirty state after any one of them edits, undoes, redoes or saves. The
 * session may call on any thread; the refresh is scheduled on the editor's display, and a closed
 * or disposed receiver never touches the widgets.
 */
final class FormSessionListener implements FormEditingSession.ChangeListener, AutoCloseable
{
    private final Display display;
    private final FormEditingSession session;
    private final Runnable refresh;
    private volatile boolean closed;

    FormSessionListener(Display display, FormEditingSession session, Runnable refresh)
    {
        this.display = display;
        this.session = session;
        this.refresh = refresh;
        session.addChangeListener(this);
    }

    @Override public void sessionChanged(FormEditingSession changed)
    {
        if (changed != session || closed || display.isDisposed())
        {
            return;
        }
        try
        {
            display.asyncExec(() -> {
                if (!closed && !display.isDisposed())
                {
                    refresh.run();
                }
            });
        }
        catch (SWTException e)
        {
            if (!display.isDisposed())
            {
                throw e;
            }
        }
    }

    @Override public void close()
    {
        closed = true;
        session.removeChangeListener(this);
    }
}
