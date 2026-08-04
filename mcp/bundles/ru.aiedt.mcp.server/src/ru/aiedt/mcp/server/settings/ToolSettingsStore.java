/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;

/**
 * Which tools the user has switched off - the one gate between the preference page and the wire.
 * <p>
 * The registry asks this on every <code>tools/list</code> and on every <code>tools/call</code>, and
 * this reads the preference store afresh each time. That is the point: a tool ticked off in the
 * preferences is gone from the next request, with nothing restarted and no cache to go stale. A lock
 * or a listener here would buy nothing and would change when a toggle takes effect, so there is
 * neither. The class keeps no state at all, which is what makes it safe on the server's worker
 * threads while the page writes from the UI thread; the worst a request in flight can see is the set
 * from just before the save, or the one from just after.
 * </p>
 * <p>
 * With no plugin - a headless workspace, or one being shut down - nothing is disabled and nothing
 * can be saved. Both are quiet: a tool that cannot read the preferences should run, not fail.
 * </p>
 */
public final class ToolSettingsStore
{
    private static final ToolSettingsStore INSTANCE = new ToolSettingsStore();

    private static final String SEPARATOR = ","; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private ToolSettingsStore()
    {
        // Singleton
    }

    /**
     * Returns the service.
     *
     * @return the single instance, never <code>null</code>
     */
    public static ToolSettingsStore getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the names the user has switched off, read from the store as it stands now.
     *
     * @return the disabled names, never <code>null</code>; empty when there is nothing to read them
     *         from
     */
    public Set<String> getDisabledTools()
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return Collections.emptySet();
        }
        return parseDisabledTools(store.getString(PrefKeys.PREF_DISABLED_TOOLS));
    }

    /**
     * Records which tools are switched off. Does nothing when there is no store to record it in.
     *
     * @param disabledTools the names to switch off; may be <code>null</code> or empty, which
     *                      switches everything back on
     */
    public void setDisabledTools(Set<String> disabledTools)
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return;
        }
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, serializeDisabledTools(disabledTools));
    }

    /**
     * Returns the names hidden from <code>tools/list</code> but still callable, read from the store as
     * it stands now.
     * <p>
     * This is the third visibility state: {@code getEnabledTools} on the registry subtracts these from
     * the advertised catalogue, while the call gate does not consult them, so an unlisted tool answers
     * a {@code tools/call} normally. Same wire format as the disabled set - comma-separated, sorted.
     * </p>
     *
     * @return the unlisted names, never <code>null</code>; empty when there is nothing to read them
     *         from
     */
    public Set<String> getUnlistedTools()
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return Collections.emptySet();
        }
        return parseDisabledTools(store.getString(PrefKeys.PREF_UNLISTED_TOOLS));
    }

    /**
     * Records which tools are hidden from <code>tools/list</code> yet still callable. Does nothing
     * when there is no store to record it in.
     *
     * @param unlistedTools the names to hide from the list; may be <code>null</code> or empty, which
     *                      advertises everything again
     */
    public void setUnlistedTools(Set<String> unlistedTools)
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return;
        }
        store.setValue(PrefKeys.PREF_UNLISTED_TOOLS, serializeDisabledTools(unlistedTools));
    }

    /**
     * Tells whether a tool is switched on.
     * <p>
     * Note what this does not ask: whether the tool exists. An unknown name is not disabled, so it
     * answers <code>true</code>. The registry has its own check that also demands the tool be
     * registered, and that is the one the protocol goes through.
     * </p>
     *
     * @param toolName the tool name
     * @return <code>true</code> unless the user has switched it off
     */
    public boolean isToolEnabled(String toolName)
    {
        return !getDisabledTools().contains(toolName);
    }

    /**
     * Switches one tool on or off.
     *
     * @param toolName the tool name
     * @param enabled  <code>true</code> to switch it on
     */
    public void setToolEnabled(String toolName, boolean enabled)
    {
        Set<String> disabled = new HashSet<>(getDisabledTools());
        if (enabled)
        {
            disabled.remove(toolName);
        }
        else
        {
            disabled.add(toolName);
        }
        setDisabledTools(disabled);
    }

    /**
     * Tells whether every tool in a group is switched on.
     *
     * @param group the group
     * @return <code>true</code> when not one of its members is disabled
     */
    public boolean isGroupFullyEnabled(ToolCategory group)
    {
        Set<String> disabled = getDisabledTools();
        for (String toolName : group.getToolNames())
        {
            if (disabled.contains(toolName))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a group is half on - which is what the tree paints grey.
     *
     * @param group the group
     * @return <code>true</code> when at least one member is on and at least one is off
     */
    public boolean isGroupPartiallyEnabled(ToolCategory group)
    {
        Set<String> disabled = getDisabledTools();
        boolean anyEnabled = false;
        boolean anyDisabled = false;
        for (String toolName : group.getToolNames())
        {
            if (disabled.contains(toolName))
            {
                anyDisabled = true;
            }
            else
            {
                anyEnabled = true;
            }
        }
        return anyEnabled && anyDisabled;
    }

    /**
     * Switches a whole group on or off.
     *
     * @param group   the group
     * @param enabled <code>true</code> to switch every member on
     */
    public void setGroupEnabled(ToolCategory group, boolean enabled)
    {
        Set<String> disabled = new HashSet<>(getDisabledTools());
        if (enabled)
        {
            disabled.removeAll(group.getToolNames());
        }
        else
        {
            disabled.addAll(group.getToolNames());
        }
        setDisabledTools(disabled);
    }

    /**
     * Applies a preset, replacing both the switched-off set and the unlisted set with the preset's.
     * <p>
     * {@link ToolProfile#CUSTOM} means "whatever the user chose" and has no sets of its own, so it is
     * quietly ignored rather than read as "switch everything on". Every other preset carries both a
     * disabled set and an unlisted set (empty for all but "Canonical"), so applying one clears
     * whichever the previous preset had left behind.
     * </p>
     *
     * @param preset the preset to apply; may be <code>null</code>
     */
    public void applyPreset(ToolProfile preset)
    {
        if (preset == null || preset.getDisabledTools() == null)
        {
            return;
        }
        setDisabledTools(preset.getDisabledTools());
        setUnlistedTools(preset.getUnlistedTools());
    }

    /**
     * Counts the tools that are switched on.
     * <p>
     * Grouped tools only - a tool in no group has no checkbox and cannot be switched off, so it has
     * nothing to say about how many are on. As long as every registered tool is in a group, which is
     * what <code>ToolCategoryCoverageTest</code> is there to make sure of, this is also the number of
     * tools an agent will be shown.
     * </p>
     *
     * @return how many grouped tools are enabled
     */
    public int getEnabledToolCount()
    {
        Set<String> disabled = getDisabledTools();
        int enabled = 0;
        for (ToolCategory group : ToolCategory.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (!disabled.contains(toolName))
                {
                    enabled++;
                }
            }
        }
        return enabled;
    }

    /**
     * Reads the stored form: names separated by commas.
     *
     * @param value what the store holds; may be <code>null</code>, empty or all spaces
     * @return an unmodifiable set of names, never <code>null</code>. Blanks around a name are
     *         dropped, and so are empty entries - a trailing comma is not a tool
     */
    static Set<String> parseDisabledTools(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return Collections.emptySet();
        }

        Set<String> names = new LinkedHashSet<>();
        for (String part : value.split(SEPARATOR))
        {
            String name = part.trim();
            if (!name.isEmpty())
            {
                names.add(name);
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Writes the stored form.
     * <p>
     * Sorted, so that saving the same choice twice produces the same line and a workspace file does
     * not churn on every OK.
     * </p>
     *
     * @param disabledTools the names; may be <code>null</code> or empty
     * @return the names in ascending order joined by commas, or an empty string
     */
    static String serializeDisabledTools(Set<String> disabledTools)
    {
        if (disabledTools == null || disabledTools.isEmpty())
        {
            return EMPTY;
        }
        List<String> sorted = new ArrayList<>(disabledTools);
        Collections.sort(sorted);
        return String.join(SEPARATOR, sorted);
    }

    /**
     * Returns the plugin's preference store.
     *
     * @return the store, or <code>null</code> when there is no plugin to ask
     */
    private static IPreferenceStore store()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }
}
