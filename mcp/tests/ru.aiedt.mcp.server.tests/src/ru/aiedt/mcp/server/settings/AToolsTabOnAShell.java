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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import ru.aiedt.mcp.server.Activator;

/**
 * A Tools tab built on a shell that is never opened, over a store that is put back afterwards.
 * <p>
 * The tab takes both of its sets from {@link ToolSettingsStore} as it is built, so a test chooses
 * the starting selection by setting the store before opening it. Nothing appears on screen: the
 * shell is created and never opened, no window comes forward and no focus moves.
 * </p>
 * <p>
 * The three widget finders and {@link #tick(TreeItem, boolean)} exist because the tab's own
 * behaviour on a filter or a preset change runs off widget events, which are not reachable through
 * the tab's public methods. A tick is delivered the way the widget delivers a user's click: the item
 * is checked, selected, and a selection event carrying the check detail is sent to the viewers.
 * </p>
 */
final class AToolsTabOnAShell implements AutoCloseable
{
    /** Every key the tab's save and its Restore Defaults write. */
    private static final String[] WRITTEN =
        {PrefKeys.PREF_DISABLED_TOOLS, PrefKeys.PREF_UNLISTED_TOOLS, PrefKeys.PREF_TOOL_PRESET};

    private final Shell shell;

    private final ToolsPrefTab tab;

    private final IPreferenceStore store;

    private final Map<String, String> before = new LinkedHashMap<>();

    /**
     * Opens a tab with the store set as the caller asks.
     *
     * @param keyValuePairs keys to set before the tab is built, each followed by its value
     * @return the tab, to be closed by the caller
     */
    static AToolsTabOnAShell open(String... keyValuePairs)
    {
        // A widget can only be built by the thread that owns a display. The default display is made
        // on this thread when the harness has none, which is what happens under the test runtime;
        // one already owned elsewhere leaves nothing to build on, and the test says so instead of
        // failing on an SWT error from three frames down.
        Display display = Display.getDefault();
        assumeTrue("this test builds widgets, so it needs the thread that owns a display", //$NON-NLS-1$
            display.getThread() == Thread.currentThread());
        return new AToolsTabOnAShell(display, keyValuePairs);
    }

    private AToolsTabOnAShell(Display display, String[] keyValuePairs)
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
        for (int i = 0; i < keyValuePairs.length; i += 2)
        {
            store.setValue(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        shell = new Shell(display, SWT.NONE);
        tab = new ToolsPrefTab(new CTabFolder(shell, SWT.NONE));
    }

    /** Puts the selection back to the shipped one, the way Restore Defaults does. */
    void restoreDefaults()
    {
        tab.performDefaults();
    }

    /** Saves the tab the way the page does. */
    void save()
    {
        tab.performOk();
    }

    /**
     * Whether the tab holds a selection the store has not been given.
     *
     * @return <code>true</code> when a save would write something
     */
    boolean hasChanges()
    {
        return tab.hasChanges();
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
     * The field the query is typed into.
     *
     * @return the search box
     */
    Text searchBox()
    {
        for (Control control : fields())
        {
            if (control instanceof Text && (control.getStyle() & SWT.SEARCH) != 0)
            {
                return (Text) control;
            }
        }
        fail("the tools tab has no search box"); //$NON-NLS-1$
        return null;
    }

    /**
     * The combo that names the preset.
     *
     * @return the preset combo
     */
    Combo presetCombo()
    {
        for (Control control : fields())
        {
            if (control instanceof Combo)
            {
                return (Combo) control;
            }
        }
        fail("the tools tab has no preset combo"); //$NON-NLS-1$
        return null;
    }

    /**
     * The tree of groups and tools.
     *
     * @return the tree
     */
    Tree tree()
    {
        for (Control control : fields())
        {
            if (control instanceof Tree)
            {
                return (Tree) control;
            }
        }
        fail("the tools tab has no tree"); //$NON-NLS-1$
        return null;
    }

    /**
     * The item a group or a tool is shown in.
     *
     * @param element a {@link ToolCategory} or a tool name
     * @return the item, or <code>null</code> when the tree does not show it
     */
    TreeItem itemFor(Object element)
    {
        TreeItem[] groups = tree().getItems();
        for (TreeItem group : groups)
        {
            if (element.equals(group.getData()))
            {
                return group;
            }
            for (TreeItem child : group.getItems())
            {
                if (element.equals(child.getData()))
                {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Delivers a tick on an item the way a user's click arrives: the box is set, the row is
     * selected, and a selection event goes to the tree's listeners.
     * <p>
     * The event has to carry the two things the viewer reads a click on a box off: the detail that
     * says the click landed on the box rather than on the row, and the row as its item. Without them
     * the viewer reads the event as a plain selection change and the tick reaches nobody.
     * </p>
     *
     * @param item the item to tick
     * @param checked the state to leave it in
     */
    void tick(TreeItem item, boolean checked)
    {
        item.setChecked(checked);
        tree().setSelection(item);
        Event event = new Event();
        event.item = item;
        event.detail = SWT.CHECK;
        tree().notifyListeners(SWT.Selection, event);
    }

    /**
     * Picks a preset in the combo the way a user does: the selection is moved and the event that
     * follows is sent.
     *
     * @param preset the preset to pick
     */
    void choosePreset(ToolProfile preset)
    {
        Combo combo = presetCombo();
        combo.select(preset.ordinal());
        combo.notifyListeners(SWT.Selection, new Event());
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
    }

    /**
     * The first tool name in the catalogue that the fragment matches.
     *
     * @param fragment the query
     * @return the name
     */
    static String firstNameContaining(String fragment)
    {
        for (ToolCategory group : ToolCategory.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (contains(toolName, fragment))
                {
                    return toolName;
                }
            }
        }
        throw new IllegalArgumentException("no tool name contains " + fragment); //$NON-NLS-1$
    }

    /**
     * A group holding both a name the fragment matches and a name it does not - the shape that makes
     * a ticked group header stand over a narrowed list.
     *
     * @param fragment the query
     * @return the group
     */
    static ToolCategory groupPartlyMatchedBy(String fragment)
    {
        for (ToolCategory group : ToolCategory.values())
        {
            if (nameContaining(group, fragment, true) != null
                && nameContaining(group, fragment, false) != null)
            {
                return group;
            }
        }
        throw new IllegalArgumentException("no group holds both a name that contains " + fragment //$NON-NLS-1$
            + " and one that does not"); //$NON-NLS-1$
    }

    /**
     * A tool name of a group, chosen by whether the fragment matches it.
     *
     * @param group the group
     * @param fragment the query
     * @param matching <code>true</code> for a name the query keeps, <code>false</code> for one it hides
     * @return the name
     */
    static String nameContaining(ToolCategory group, String fragment, boolean matching)
    {
        for (String toolName : group.getToolNames())
        {
            if (contains(toolName, fragment) == matching)
            {
                return toolName;
            }
        }
        return null;
    }

    private static boolean contains(String toolName, String fragment)
    {
        return toolName.toLowerCase().contains(fragment.toLowerCase());
    }

    /**
     * A tool name the shipped preset hides from the catalogue and the tree holds a row for.
     *
     * @return the name
     */
    static String firstNameTheShippedPresetHides()
    {
        for (ToolCategory group : ToolCategory.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (ToolProfile.CANONICAL.getUnlistedTools().contains(toolName))
                {
                    return toolName;
                }
            }
        }
        throw new IllegalArgumentException("the shipped preset hides no tool the tree shows"); //$NON-NLS-1$
    }

    /** Every control the tab is built from. */
    private List<Control> fields()
    {
        List<Control> found = new ArrayList<>();
        collect(tab.getControl(), found);
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
}
