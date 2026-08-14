/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import ru.aiedt.mcp.server.Activator;

/**
 * One text widget that exists so the language has something to hold on to, and is never shown.
 * <p>
 * Content assist in this environment needs a real text widget: it registers a dispose listener on one
 * and keys a cache of document listeners by it. Withholding it fails outright, and faking the cache
 * around it is worse - the language then works with a listener built for nothing and answers with an
 * empty list, which reads exactly like "there is nothing to suggest here". That cost eight rounds of
 * looking in the wrong place.
 * </p>
 * <p>
 * So a widget is made, once, on a shell that is never opened. Nothing about it reaches the screen: no
 * tab appears, no focus moves, no window comes forward. It is only an identity the environment can
 * pin its bookkeeping to.
 * </p>
 */
public final class OffScreenWidget
{
    private static Shell shell;

    private static StyledText widget;

    private OffScreenWidget()
    {
    }

    /**
     * The widget, created on first use.
     * <p>
     * Must be called from the display thread, which is where content assist runs anyway - SWT will not
     * hand out widgets anywhere else.
     * </p>
     *
     * @return the widget, or <code>null</code> when there is no display to make one on
     */
    public static synchronized StyledText get()
    {
        if (widget != null && !widget.isDisposed())
        {
            return widget;
        }
        Display display = Display.getCurrent();
        if (display == null)
        {
            Activator.logWarning("An off-screen widget was asked for away from the display thread"); //$NON-NLS-1$
            return null;
        }
        // Never opened, so never drawn. A shell that is not open takes no space on any screen and
        // takes no focus from whoever is working in the window next to it.
        shell = new Shell(display, SWT.NONE);
        widget = new StyledText(shell, SWT.NONE);
        return widget;
    }

    /**
     * Lets go of the widget when the plugin stops.
     */
    public static synchronized void dispose()
    {
        if (widget != null && !widget.isDisposed())
        {
            widget.dispose();
        }
        if (shell != null && !shell.isDisposed())
        {
            shell.dispose();
        }
        widget = null;
        shell = null;
    }
}
