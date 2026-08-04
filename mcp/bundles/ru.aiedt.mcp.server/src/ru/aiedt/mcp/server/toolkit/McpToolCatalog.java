/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;

/**
 * The tools this server can call, keyed by their stable capability id.
 * <p>
 * There is one registry for the whole plugin. It is filled in by the server before the socket opens
 * and read from several request threads at once, so the backing maps are concurrent and no caller
 * needs to lock.
 * </p>
 * <p>
 * A tool has two names. Its <em>capability id</em> - {@link IMcpTool#getCapabilityId()} - is the
 * frozen identity the disabled set, the unlisted set, the category membership and the preference keys
 * all key on; renaming the wire name never changes it, which is what keeps a preset that switched a
 * capability off from silently re-enabling it after a rename. Its <em>callable names</em> - the
 * primary wire name {@link IMcpTool#getName()} plus any {@link IMcpTool#getAliases() aliases} - are
 * what {@code tools/call} accepts and what {@code tools/list} advertises (the primary alone). Every
 * callable name resolves to one capability id, and the capability id resolves to the one tool.
 * </p>
 * <p>
 * Registration and enablement are different questions. A tool is <em>registered</em> when the server
 * knows how to run it; it is <em>enabled</em> when the user has not switched it off in the
 * preferences. Only enabled tools are advertised to clients, and the answer is recomputed on every
 * call - switching a tool off takes effect on the next request, with no server restart.
 * </p>
 */
public class McpToolCatalog
{
    private static final McpToolCatalog INSTANCE = new McpToolCatalog();

    private final Map<String, IMcpTool> toolsByCapability = new ConcurrentHashMap<>();

    private final Map<String, String> callableToCapability = new ConcurrentHashMap<>();

    private McpToolCatalog()
    {
        // Singleton
    }

    /**
     * Returns the registry.
     *
     * @return the single instance, never <code>null</code>
     */
    public static McpToolCatalog getInstance()
    {
        return INSTANCE;
    }

    /**
     * Adds a tool.
     * <p>
     * A tool without a wire name cannot be called and is dropped rather than stored under a key nobody
     * can ask for. A capability id is registered once: when a second tool arrives under a capability id
     * that is already taken, the first is kept, the second is ignored, and the clash is logged as an
     * error. The server registers a fixed list at startup, so a duplicate capability is a programming
     * error - it is surfaced in the log instead of silently overwriting the first tool, while still
     * leaving a working registry rather than aborting startup. A callable name that two tools want is
     * the same kind of error and is logged the same way, with the first binding kept.
     * </p>
     *
     * @param tool the tool to register; ignored when <code>null</code>, unnamed, or a duplicate
     *             capability id
     */
    public void register(IMcpTool tool)
    {
        if (tool == null)
        {
            return;
        }
        String wireName = tool.getName();
        if (wireName == null)
        {
            return;
        }
        String capabilityId = capabilityOf(tool);
        // The primary wire name is what tools/list advertises and what tools/call resolves by, so two
        // tools may not share one. A second tool arriving under a primary name another capability already
        // owns would otherwise be stored under its own capability and advertised under that name, yet a
        // call to the name would still resolve to the first - a ghost entry in the list. Reject the whole
        // registration here, before the capability is stored, so neither the tool nor its schema appears.
        String ownerOfWire = callableToCapability.get(wireName);
        if (ownerOfWire != null && !ownerOfWire.equals(capabilityId))
        {
            Activator.logError("MCP wire name '" + wireName + "' is already the primary name of " //$NON-NLS-1$ //$NON-NLS-2$
                + "capability '" + ownerOfWire + "'; cannot register capability '" + capabilityId //$NON-NLS-1$ //$NON-NLS-2$
                + "' (" + tool.getClass().getSimpleName() + ") under the same name", null); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IMcpTool existing = toolsByCapability.putIfAbsent(capabilityId, tool);
        if (existing != null)
        {
            Activator.logError("Duplicate MCP capability id '" + capabilityId + "': keeping " //$NON-NLS-1$ //$NON-NLS-2$
                + existing.getClass().getSimpleName() + ", ignoring " //$NON-NLS-1$
                + tool.getClass().getSimpleName(), null);
            return;
        }
        bindCallable(wireName, capabilityId);
        for (String alias : aliasesOf(tool))
        {
            bindCallable(alias, capabilityId);
        }
        Activator.logInfo("tool now callable: " + wireName //$NON-NLS-1$
            + (aliasesOf(tool).isEmpty() ? "" : " (aliases: " + String.join(", ", aliasesOf(tool)) + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Looks a tool up by any name a client may call it by - the primary wire name or an alias.
     *
     * @param callableName the name the caller used; may be <code>null</code>
     * @return the tool, or <code>null</code> when no tool goes by that name
     */
    public IMcpTool getTool(String callableName)
    {
        if (callableName == null)
        {
            return null;
        }
        String capabilityId = callableToCapability.get(callableName);
        return capabilityId != null ? toolsByCapability.get(capabilityId) : null;
    }

    /**
     * Removes a tool by any of its names, or by its capability id.
     *
     * @param name a callable name or the capability id; ignored when <code>null</code>
     */
    public void unregister(String name)
    {
        if (name == null)
        {
            return;
        }
        String capabilityId = callableToCapability.get(name);
        if (capabilityId == null)
        {
            capabilityId = name;
        }
        IMcpTool removed = toolsByCapability.remove(capabilityId);
        if (removed != null)
        {
            callableToCapability.values().removeIf(capabilityId::equals);
        }
        else
        {
            callableToCapability.remove(name);
        }
    }

    /**
     * Returns every registered tool, whether or not the user has it switched on.
     *
     * @return an unmodifiable view of all tools
     */
    public Collection<IMcpTool> getAllTools()
    {
        return Collections.unmodifiableCollection(toolsByCapability.values());
    }

    /**
     * Returns the tools a client may see in <code>tools/list</code>.
     * <p>
     * Both sets are read afresh here rather than cached, which is what lets the preference page take
     * effect on the very next request. A tool is dropped from the advertised catalogue when its
     * capability id is in the disabled set <em>or</em> in the unlisted set: disabled tools are hidden
     * and rejected, unlisted tools are hidden but still answer a {@code tools/call}. Listing subtracts
     * the union; the call gate in {@link #isToolEnabled} subtracts the disabled set alone, and that
     * divergence is the whole of the third "callable-but-unlisted" state. The advertised name is the
     * primary wire name, never an alias.
     * </p>
     *
     * @return an unmodifiable view of the listed tools
     */
    public Collection<IMcpTool> getEnabledTools()
    {
        Set<String> disabled = disabledTools();
        Set<String> unlisted = unlistedTools();
        if (disabled.isEmpty() && unlisted.isEmpty())
        {
            return Collections.unmodifiableCollection(toolsByCapability.values());
        }
        List<IMcpTool> listed = new ArrayList<>(toolsByCapability.size());
        for (IMcpTool tool : toolsByCapability.values())
        {
            String capabilityId = capabilityOf(tool);
            if (!disabled.contains(capabilityId) && !unlisted.contains(capabilityId))
            {
                listed.add(tool);
            }
        }
        return Collections.unmodifiableList(listed);
    }

    /**
     * Tells whether a tool is registered under the given callable name and still callable.
     * <p>
     * This is the call gate, and it consults the disabled set only - never the unlisted set - and it
     * judges the tool by its <em>capability id</em>, not by the name the caller used. A tool the user
     * merely hid from the list (unlisted) is therefore still callable, while a disabled tool is not,
     * even when a preset also unlisted it: disabled wins because this method never looks at the
     * unlisted set at all. Gating by capability id is what keeps an alias - or a renamed wire name -
     * under the same preset decision as the primary name.
     * </p>
     *
     * @param callableName the name the caller used; may be <code>null</code>
     * @return <code>true</code> only when a tool answers that name and the user has not disabled its
     *         capability
     */
    public boolean isToolEnabled(String callableName)
    {
        if (callableName == null)
        {
            return false;
        }
        String capabilityId = callableToCapability.get(callableName);
        if (capabilityId == null)
        {
            return false;
        }
        return !disabledTools().contains(capabilityId);
    }

    /**
     * Tells whether the active preset switched a capability off, whether or not a tool of that id is
     * registered.
     * <p>
     * Unlike {@link #isToolEnabled}, this consults only the disabled set and never the registry, so it
     * answers for a capability that a preset gates by name but that is not itself a registered tool - a
     * facade mode, for instance, whose category name a preset can disable even though no standalone tool
     * of that name exists. A facade that reaches such a capability calls this before running it, so a
     * preset that disabled the name blocks the facade shortcut too.
     * </p>
     *
     * @param capabilityName the capability id to test; may be <code>null</code>
     * @return <code>true</code> when the active preset lists this capability as disabled
     */
    public boolean isDisabledByPreset(String capabilityName)
    {
        return capabilityName != null && disabledTools().contains(capabilityName);
    }

    /**
     * Returns how many tools are registered, disabled ones included.
     *
     * @return the number of registered tools
     */
    public int getToolCount()
    {
        return toolsByCapability.size();
    }

    /**
     * Forgets every tool. The server calls this before it registers its list, so registering twice in
     * one session - which is what happens when the server is started after the plugin already
     * registered the tools for the preference pages - leaves the registry with one copy of each.
     */
    public void clear()
    {
        toolsByCapability.clear();
        callableToCapability.clear();
    }

    /**
     * Binds one callable name to a capability id, logging a clash and keeping the first binding.
     *
     * @param callableName the name a caller may use
     * @param capabilityId the capability it resolves to
     */
    private void bindCallable(String callableName, String capabilityId)
    {
        if (callableName == null || callableName.isEmpty())
        {
            return;
        }
        String owner = callableToCapability.putIfAbsent(callableName, capabilityId);
        if (owner != null && !owner.equals(capabilityId))
        {
            Activator.logError("MCP callable name '" + callableName //$NON-NLS-1$
                + "' is already bound to capability '" + owner //$NON-NLS-1$
                + "'; cannot rebind to '" + capabilityId + "', keeping the first binding", null); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Returns the capability id of a tool, falling back to its wire name when the tool does not name
     * one. The fallback is what makes the capability seam a no-op for every tool that has not been
     * renamed: capability id and wire name are the same string until a future version freezes the one
     * and changes the other.
     *
     * @param tool the tool; not <code>null</code>
     * @return its capability id, never <code>null</code> or empty for a tool with a wire name
     */
    private static String capabilityOf(IMcpTool tool)
    {
        String capabilityId = tool.getCapabilityId();
        return (capabilityId != null && !capabilityId.isEmpty()) ? capabilityId : tool.getName();
    }

    /**
     * Returns the aliases a tool advertises, never <code>null</code>.
     *
     * @param tool the tool; not <code>null</code>
     * @return its alias names, possibly empty
     */
    private static List<String> aliasesOf(IMcpTool tool)
    {
        List<String> aliases = tool.getAliases();
        return aliases == null ? Collections.emptyList() : aliases;
    }

    /**
     * Returns the names the user has switched off.
     *
     * @return the disabled capability ids, never <code>null</code>, possibly empty
     */
    private static Set<String> disabledTools()
    {
        return ToolSettingsStore.getInstance().getDisabledTools();
    }

    /**
     * Returns the names hidden from the list but still callable.
     *
     * @return the unlisted capability ids, never <code>null</code>, possibly empty
     */
    private static Set<String> unlistedTools()
    {
        return ToolSettingsStore.getInstance().getUnlistedTools();
    }
}
