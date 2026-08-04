/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * The seam a facade calls before it delegates an operation to a separately-registered standalone
 * tool, so that a preset which disabled the standalone also blocks the facade's shortcut to it.
 * <p>
 * {@code edit_metadata} folds {@code delete_metadata_object}, {@code rename_metadata_object} and
 * {@code add_metadata_attribute} in as delegated operations, yet the three standalones live in the
 * REFACTORING group while {@code edit_metadata} itself lives in CONSTRUCTORS - a preset such as Code
 * Review disables the former and keeps the latter. Without this seam a plain
 * {@code operation=delete_metadata_object} call would still run under that preset even though the
 * standalone tool of the same name is rejected: a preset bypass. Routing the delegated handler through
 * {@link #gateOrNull} first makes both paths key on the very same {@link McpToolCatalog#isToolEnabled}
 * check and the very same rejection text. The wording is identical; the MCP envelope is not - a
 * JSON-typed facade wraps the rejection as a JSON document while the router answers a disabled tool as
 * text content - but the preset decision and the message agree by construction.
 * </p>
 * <p>
 * {@code McpRequestRouter} rejects a disabled tool with this exact wording before a call ever reaches
 * a tool's {@code execute}; this class is now the single place that string lives, so the router and
 * every gate-checked facade delegation share it instead of each carrying their own copy.
 * </p>
 * <p>
 * Two seams, one rule. A folded operation that is itself a registered standalone uses
 * {@link #gateOrNull} - registered and not disabled, exactly as the router judges it. A capability
 * reached through a facade mode and keyed by a ToolCategory name uses {@link #gateIfPresetDisabled} -
 * disabled-set membership only, so the gate does not depend on that name still being registered.
 * </p>
 */
public final class ToolGate
{
    private ToolGate()
    {
        // static utility
    }

    /**
     * The message handed back for a tool the user has switched off.
     * <p>
     * Byte-identical to the router's own literal - this is the one place that string is written, so
     * the router and every gate-checked facade delegation agree on the wording without either side
     * copying it from the other.
     * </p>
     *
     * @param toolName the tool that was asked for
     * @return the rejection message, never {@code null}
     */
    public static String disabledMessage(String toolName)
    {
        return "The tool '" + toolName + "' is disabled and was not executed." //$NON-NLS-1$ //$NON-NLS-2$
            + " Ask the user to enable it in EDT Preferences > AI-EDT > Tools tab, then try again."; //$NON-NLS-1$
    }

    /**
     * Checks whether a standalone tool a facade is about to delegate to is currently enabled.
     *
     * @param toolName the standalone tool's registered name
     * @return {@code null} when the tool is enabled and the facade should proceed with the
     *         delegation; otherwise the exact rejection text the facade should return in its place
     */
    public static String gateOrNull(String toolName)
    {
        return McpToolCatalog.getInstance().isToolEnabled(toolName) ? null : disabledMessage(toolName);
    }

    /**
     * Gates a facade capability by its preset key - a ToolCategory name a preset can switch off -
     * consulting the disabled set alone, independent of whether a tool of that name is registered.
     * <p>
     * Use this, not {@link #gateOrNull}, when the capability is reached through a facade MODE rather than
     * a delegated standalone. The name may be a live deprecated alias today (so {@code isToolEnabled}
     * would happen to work), but the mode's enablement should not hinge on that registration: once the
     * alias is unregistered in a future major version {@code gateOrNull} would reject the mode
     * unconditionally, whereas membership gating keeps answering from the preset alone.
     * </p>
     *
     * @param capabilityName the preset-gated name the facade is about to run (a ToolCategory member)
     * @return {@code null} when the active preset has not disabled the name and the facade should
     *         proceed; otherwise the rejection text the facade should return in its place
     */
    public static String gateIfPresetDisabled(String capabilityName)
    {
        return McpToolCatalog.getInstance().isDisabledByPreset(capabilityName)
            ? disabledMessage(capabilityName) : null;
    }
}
