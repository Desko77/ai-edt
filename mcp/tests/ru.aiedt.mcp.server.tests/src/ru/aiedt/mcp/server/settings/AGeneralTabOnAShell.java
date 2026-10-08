/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import ru.aiedt.mcp.server.Activator;

/**
 * A General tab built on a shell that is never opened, over a store that is put back afterwards.
 * <p>
 * The tab takes the value of every field from the store as the field is built, so a test chooses
 * what the tab shows by setting the store before opening it. Nothing appears on screen: the shell is
 * created and never opened, no window comes forward and no focus moves - the arrangement the plugin
 * already uses for the widget content assist needs.
 * </p>
 * <p>
 * The tab is driven through {@link GeneralPrefTab#performOk()} and
 * {@link GeneralPrefTab#performDefaults()}, which is what the page calls, rather than through the
 * buttons, which would raise dialogs. Every key those two write is captured on opening and put back
 * on closing, so a test leaves the store as it found it.
 * </p>
 */
final class AGeneralTabOnAShell implements AutoCloseable
{
    /** Every key the tab's save and its Restore Defaults write. */
    private static final String[] WRITTEN =
        {PrefKeys.PREF_PORT, PrefKeys.PREF_PORT_SPAN, PrefKeys.PREF_AUTO_START,
            PrefKeys.PREF_CHECKS_FOLDER, PrefKeys.PREF_BSL_LS_JAR, PrefKeys.PREF_BSL_LS_JAVA,
            PrefKeys.PREF_VANESSA_EPF, PrefKeys.PREF_VANESSA_1C_EXE,
            PrefKeys.PREF_NAPARNIK_BRIDGE_ENABLED, PrefKeys.PREF_NAPARNIK_ALL_TOOLS_ENABLED,
            PrefKeys.PREF_PLAIN_TEXT_MODE, PrefKeys.PREF_BIND_ALL_INTERFACES,
            PrefKeys.PREF_ALLOW_NULL_ORIGIN, PrefKeys.PREF_AUTH_ENABLED, PrefKeys.PREF_AUTH_TOKEN,
            PrefKeys.PREF_HISTORY_ENABLED, PrefKeys.PREF_HISTORY_DEPTH,
            PrefKeys.PREF_HISTORY_ARG_CHARS, PrefKeys.PREF_HISTORY_RESULT_CHARS,
            PrefKeys.PREF_HISTORY_FILE_ENABLED, PrefKeys.PREF_HISTORY_FILE_REDACT,
            PrefKeys.PREF_HISTORY_DISK_ENABLED, PrefKeys.PREF_HISTORY_DISK_DAYS,
            PrefKeys.PREF_HISTORY_DISK_PATH, PrefKeys.PREF_UPKEEP_ENABLED,
            PrefKeys.PREF_UPKEEP_SITE_URL, PrefKeys.PREF_UPKEEP_INTERVAL_HOURS,
            PrefKeys.PREF_UPKEEP_NOTIFY_POPUP, PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE,
            PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR, PrefKeys.PREF_MARKERS_DECORATION_STYLE,
            PrefKeys.LEGACY_MARKERS_SHOW_IN_NAVIGATOR, PrefKeys.LEGACY_MARKERS_DECORATION_STYLE};

    private final Shell shell;

    private final GeneralPrefTab tab;

    private final IPreferenceStore store;

    private final Map<String, String> before = new LinkedHashMap<>();

    /**
     * Opens a tab with the store set as the caller asks.
     *
     * @param keyValuePairs keys to set before the tab is built, each followed by its value
     * @return the tab, to be closed by the caller
     */
    static AGeneralTabOnAShell open(String... keyValuePairs)
    {
        // A widget can only be built by the thread that owns a display. The default display is made
        // on this thread when the harness has none, which is what happens under the test runtime;
        // one already owned elsewhere leaves nothing to build on, and the test says so instead of
        // failing on an SWT error from three frames down.
        Display display = Display.getDefault();
        assumeTrue("this test builds widgets, so it needs the thread that owns a display", //$NON-NLS-1$
            display.getThread() == Thread.currentThread());
        return new AGeneralTabOnAShell(display, keyValuePairs);
    }

    private AGeneralTabOnAShell(Display display, String[] keyValuePairs)
    {
        if (keyValuePairs.length % 2 != 0)
        {
            throw new IllegalArgumentException("a key without a value: " + keyValuePairs.length); //$NON-NLS-1$
        }
        store = Activator.getDefault().getPreferenceStore();
        for (String key : WRITTEN)
        {
            before.put(key, store.getString(key));
        }
        // The token is the one field whose save reaches a store of its own; away from the real one it
        // stays out of the workspace.
        McpAuth.useStore(new MemoryTokens());
        McpAuth.forgetPublished();
        for (int i = 0; i < keyValuePairs.length; i += 2)
        {
            store.setValue(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        shell = new Shell(display, SWT.NONE);
        tab = new GeneralPrefTab(new CTabFolder(shell, SWT.NONE));
    }

    /**
     * Saves the tab the way the page does.
     *
     * @return <code>null</code> when everything was saved, otherwise what was not and why
     */
    String save()
    {
        return tab.performOk();
    }

    /** Puts the fields back to the shipped values, the way Restore Defaults does. */
    void restoreDefaults()
    {
        tab.performDefaults();
    }

    /**
     * The store the tab writes to and reads from.
     *
     * @return the plugin's preference store
     */
    IPreferenceStore store()
    {
        return store;
    }

    /**
     * The spinner showing a given number.
     *
     * @param selection the number the spinner was built with
     * @return the spinner
     */
    Spinner spinnerHolding(int selection)
    {
        for (Control control : fields())
        {
            if (control instanceof Spinner && ((Spinner) control).getSelection() == selection)
            {
                return (Spinner) control;
            }
        }
        fail("no spinner on the tab shows " + selection); //$NON-NLS-1$
        return null;
    }

    /**
     * The text field showing a given text.
     *
     * @param text the text the field was built with
     * @return the field
     */
    Text textHolding(String text)
    {
        for (Control control : fields())
        {
            if (control instanceof Text && text.equals(((Text) control).getText()))
            {
                return (Text) control;
            }
        }
        fail("no text field on the tab shows " + text); //$NON-NLS-1$
        return null;
    }

    @Override
    public void close()
    {
        tab.dispose();
        shell.dispose();
        for (Map.Entry<String, String> entry : before.entrySet())
        {
            store.setValue(entry.getKey(), entry.getValue());
        }
        McpAuth.useStore(null);
        McpAuth.forgetPublished();
    }

    /** Every control the three sections of the tab hold. */
    private List<Control> fields()
    {
        List<Control> found = new ArrayList<>();
        collect(tab.getControl(), found);
        collect(tab.getDataControl(), found);
        collect(tab.getWorkbenchControl(), found);
        return found;
    }

    private static void collect(Control control, List<Control> found)
    {
        found.add(control);
        if (control instanceof Composite)
        {
            for (Control child : ((Composite) control).getChildren())
            {
                collect(child, found);
            }
        }
    }

    /**
     * A token store in memory, so a save never reaches the workspace the tests run in.
     */
    private static final class MemoryTokens
        implements McpAuth.TokenStore
    {
        private String token = ""; //$NON-NLS-1$

        @Override
        public String read()
        {
            return token;
        }

        @Override
        public void write(String value)
        {
            token = value == null ? "" : value; //$NON-NLS-1$
        }

        @Override
        public void flush()
        {
            // Memory needs no flush.
        }
    }
}
