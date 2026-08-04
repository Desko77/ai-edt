/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.upkeep.ReleaseOffer;

/**
 * A small window that says a newer build has been published, and gets out of the way.
 * <p>
 * <b>Written on plain SWT rather than using the platform's notification framework.</b>
 * {@code org.eclipse.jface.notifications} is present in the EDT that runs this plugin but absent
 * from the target platform it is built against, so using it would mean editing the target and taking
 * a dependency whose absence in a future EDT would stop the whole bundle from resolving. A borderless
 * shell costs neither.
 * </p>
 * <p>
 * <b>It does not take focus and it does not block.</b> The shell is created with
 * {@link SWT#NO_FOCUS} and made visible rather than opened, because opening activates: someone typing
 * when this appears keeps typing into their editor. It closes itself after a few seconds, or on a
 * click anywhere in it.
 * </p>
 * <p>
 * This is the only proactive window this plugin raises. Everything else it shows is the answer to a
 * click, and that asymmetry is deliberate - the case for interrupting someone is that a version they
 * do not have is available, and even then only once per version.
 * </p>
 */
public final class UpdateNoticePopup
{
    private static final int VISIBLE_MILLIS = 12000;

    private static final int MARGIN = 12;

    private static final int EDGE_GAP = 24;

    private UpdateNoticePopup()
    {
    }

    /**
     * Shows the notice, if there is a window to show it beside.
     * <p>
     * Answers <code>false</code> rather than queueing when the IDE has no active window - during
     * another application's turn on screen, a message parked over their work would be both rude and
     * unread. The caller polls, so the notice appears the next time the IDE is in front, which is
     * when it can actually be seen.
     * </p>
     *
     * @param offer the snapshot to describe; must carry an update
     * @param onInstall what to run if the reader accepts, on the UI thread
     * @return <code>true</code> when the notice was put on screen
     */
    public static boolean show(ReleaseOffer offer, Runnable onInstall)
    {
        if (offer == null || !offer.hasUpdate())
        {
            return false;
        }
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
        {
            return false;
        }
        Shell parent = display.getActiveShell();
        if (parent == null || parent.isDisposed())
        {
            return false;
        }
        try
        {
            build(display, parent, offer, onInstall);
            return true;
        }
        catch (RuntimeException e)
        {
            // A notice nobody could draw is not worth failing a check over.
            Activator.logError("The AI-EDT update notice could not be shown", e); //$NON-NLS-1$
            return false;
        }
    }

    private static void build(Display display, Shell parent, ReleaseOffer offer, Runnable onInstall)
    {
        Shell shell = new Shell(parent, SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = MARGIN;
        layout.marginHeight = MARGIN;
        shell.setLayout(layout);

        Label heading = new Label(shell, SWT.NONE);
        heading.setText("AI-EDT " + offer.offered() + " is available"); //$NON-NLS-1$ //$NON-NLS-2$

        Label detail = new Label(shell, SWT.NONE);
        detail.setText("Installed: " + offer.installed()); //$NON-NLS-1$
        detail.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        MouseAdapter dismiss = new MouseAdapter()
        {
            @Override
            public void mouseUp(MouseEvent e)
            {
                close(shell);
            }
        };
        shell.addMouseListener(dismiss);
        for (Control child : shell.getChildren())
        {
            child.addMouseListener(dismiss);
        }

        // Added after the dismiss listeners are wired to the rest, so this one control acts instead
        // of closing. Clicking anywhere else dismisses, which is what a notice this size should do.
        Label action = new Label(shell, SWT.NONE);
        action.setText(onInstall == null ? "Click to dismiss." //$NON-NLS-1$
            : "Click here to install and restart, or anywhere else to dismiss."); //$NON-NLS-1$
        action.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        action.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseUp(MouseEvent e)
            {
                close(shell);
                if (onInstall != null)
                {
                    onInstall.run();
                }
            }
        });

        shell.pack();
        place(shell, parent);
        // Visible, not open: open() activates the shell and would take the caret out of whatever the
        // user was typing into.
        shell.setVisible(true);
        display.timerExec(VISIBLE_MILLIS, () -> close(shell));
    }

    /**
     * Puts the notice near the bottom-right corner of the window it belongs to, clamped to the
     * monitor so it cannot land off screen on a multi-display desktop.
     *
     * @param shell the notice
     * @param parent the window it belongs to
     */
    private static void place(Shell shell, Shell parent)
    {
        Point size = shell.getSize();
        Rectangle area = parent.getMonitor().getClientArea();
        Rectangle owner = parent.getBounds();
        int x = Math.min(owner.x + owner.width, area.x + area.width) - size.x - EDGE_GAP;
        int y = Math.min(owner.y + owner.height, area.y + area.height) - size.y - EDGE_GAP;
        shell.setLocation(Math.max(area.x, x), Math.max(area.y, y));
    }

    private static void close(Shell shell)
    {
        if (shell != null && !shell.isDisposed())
        {
            shell.dispose();
        }
    }
}
