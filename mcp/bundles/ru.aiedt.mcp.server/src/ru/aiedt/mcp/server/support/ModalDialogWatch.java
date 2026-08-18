/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Whether EDT is sitting on a modal dialog, so a caller can tell a question from a hang.
 * <p>
 * A modal dialog stops the workbench dead, and from outside it looks exactly like a wedged
 * process: the call that triggered it never comes back and nothing says why. The one seen
 * in practice is Eclipse's own secure-storage prompt - "the secure storage file has been
 * modified by another program" - which several EDT instances on one machine raise at each
 * other, because they all keep credentials in a single shared keyring and each holds its
 * own idea of when the file was last written. It waits for a human on a machine where an
 * agent is the only one watching.
 * </p>
 * <p>
 * Nothing here dismisses anything. Answering a dialog on the user's behalf would be
 * deciding for them - the secure-storage prompt in particular chooses between discarding
 * this instance's credentials and discarding another instance's. The point is only to say
 * that a dialog is up, what it says, and therefore that waiting longer will not help.
 * </p>
 */
public final class ModalDialogWatch
{
    /**
     * How long to give the UI thread. A modal dialog runs its own event loop, so a
     * runnable posted to it still gets dispatched - which is exactly why this works at
     * all. When it does NOT come back, the UI thread is wedged for some other reason, and
     * that is worth reporting in its own right rather than waiting on.
     */
    private static final long UI_ANSWER_MS = 700;

    /** The modal styles; a shell carrying any of them holds the workbench. */
    private static final int MODAL = SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;

    private ModalDialogWatch()
    {
    }

    /** What the workbench is showing, if anything, and whether it answered at all. */
    public static final class Reading
    {
        private final boolean uiResponded;
        private final List<Map<String, Object>> dialogs;

        Reading(boolean uiResponded, List<Map<String, Object>> dialogs)
        {
            this.uiResponded = uiResponded;
            this.dialogs = dialogs;
        }

        /** Whether the UI thread answered within the budget. */
        public boolean isUiResponding()
        {
            return this.uiResponded;
        }

        /** One entry per open modal dialog, each with its title and message. */
        public List<Map<String, Object>> getDialogs()
        {
            return this.dialogs;
        }

        /** Whether a modal dialog is holding the workbench right now. */
        public boolean isBlocked()
        {
            return !this.dialogs.isEmpty();
        }

        /**
         * One line for a caller that is deciding whether to keep waiting.
         *
         * @return the sentence, or <code>null</code> when there is nothing to say.
         */
        public String describe()
        {
            if (!this.uiResponded)
            {
                return "EDT's UI thread did not answer within " + UI_ANSWER_MS //$NON-NLS-1$
                    + "ms - it is busy or wedged, and an operation that needs it will wait."; //$NON-NLS-1$
            }
            if (this.dialogs.isEmpty())
            {
                return null;
            }
            StringBuilder sb = new StringBuilder("EDT is waiting on a dialog and needs a person: "); //$NON-NLS-1$
            for (int i = 0; i < this.dialogs.size(); i++)
            {
                if (i > 0)
                {
                    sb.append("; "); //$NON-NLS-1$
                }
                Map<String, Object> d = this.dialogs.get(i);
                sb.append('"').append(d.get("title")).append('"'); //$NON-NLS-1$
                Object message = d.get("message"); //$NON-NLS-1$
                if (message != null && !String.valueOf(message).isEmpty())
                {
                    sb.append(" - ").append(message); //$NON-NLS-1$
                }
            }
            sb.append(". Nothing is hung; the call that triggered it resumes once the dialog " //$NON-NLS-1$
                + "is answered."); //$NON-NLS-1$
            return sb.toString();
        }
    }

    /**
     * Looks at the workbench's shells.
     *
     * @return what it found; an empty, responding reading when there is no display at all
     *         (headless), because a machine with no UI cannot be showing a dialog.
     */
    public static Reading current()
    {
        Display display = existingDisplay();
        if (display == null || display.isDisposed())
        {
            return new Reading(true, new ArrayList<>());
        }
        AtomicReference<List<Map<String, Object>>> found = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);
        try
        {
            display.asyncExec(() -> {
                try
                {
                    found.set(collect(display));
                }
                finally
                {
                    answered.countDown();
                }
            });
            if (!answered.await(UI_ANSWER_MS, TimeUnit.MILLISECONDS))
            {
                return new Reading(false, new ArrayList<>());
            }
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return new Reading(false, new ArrayList<>());
        }
        catch (RuntimeException e)
        {
            // A disposed or shutting-down display throws rather than answering. Reported as
            // "no dialog" rather than as an error: the caller is asking why something is
            // slow, and a stack trace about SWT would answer a question nobody asked.
            return new Reading(true, new ArrayList<>());
        }
        List<Map<String, Object>> dialogs = found.get();
        return new Reading(true, dialogs == null ? new ArrayList<>() : dialogs);
    }

    /** Runs on the UI thread. */
    private static List<Map<String, Object>> collect(Display display)
    {
        List<Map<String, Object>> dialogs = new ArrayList<>();
        for (Shell shell : display.getShells())
        {
            if (shell == null || shell.isDisposed() || !shell.isVisible())
            {
                continue;
            }
            if ((shell.getStyle() & MODAL) == 0)
            {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", text(shell.getText())); //$NON-NLS-1$
            entry.put("message", firstLabel(shell)); //$NON-NLS-1$
            dialogs.add(entry);
        }
        return dialogs;
    }

    /**
     * The dialog's own words, taken from its first non-empty label.
     * <p>
     * A title alone rarely says enough - "Secure Storage" does not tell anyone what is
     * being asked, while the label under it does.
     * </p>
     */
    private static String firstLabel(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Label)
            {
                String value = text(((Label)child).getText());
                if (!value.isEmpty())
                {
                    return value;
                }
            }
            if (child instanceof Composite)
            {
                String nested = firstLabel((Composite)child);
                if (!nested.isEmpty())
                {
                    return nested;
                }
            }
        }
        return ""; //$NON-NLS-1$
    }

    /** Collapses the newlines a dialog lays its message out with, so it fits one line. */
    private static String text(String value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('&', ' ').trim();
    }

    /**
     * The display, only where asking for it is safe.
     * <p>
     * {@code Display.getDefault()} CREATES one when there is none, and on a headless
     * runtime that initialises the native toolkit - the very thing that is absent there,
     * and the reason this plugin asks the system properties rather than SWT whether it has
     * a UI. So the headless case is settled before SWT is touched at all: no UI means no
     * dialog, which is an answer and not a failure.
     * </p>
     */
    private static Display existingDisplay()
    {
        if (ru.aiedt.mcp.server.Activator.isHeadlessRuntime())
        {
            return null;
        }
        try
        {
            Display current = Display.getCurrent();
            return current != null ? current : Display.getDefault();
        }
        catch (Throwable noToolkit)
        {
            return null;
        }
    }
}
