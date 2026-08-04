/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Pins {@link ToolGate}'s two responsibilities: the exact wording a caller sees for a disabled tool,
 * and the one-line contract of {@link ToolGate#gateOrNull} - {@code null} when the named tool is
 * enabled, the wording above otherwise.
 * <p>
 * {@link ToolGate#gateOrNull} asks {@code McpToolCatalog.getInstance()}, a live JVM-wide singleton. A
 * bare JUnit run never registers a tool into it - that only happens when {@code McpServer} starts
 * against a real EDT workspace - so {@code isToolEnabled} is deterministically {@code false} for any
 * name here, independent of any preset. That is enough to pin the "not enabled" branch and prove it
 * always answers with {@link ToolGate#disabledMessage}, which is the whole point of the seam: the
 * router and a gate-checked facade delegation must produce byte-identical text. It is NOT enough to
 * exercise the "enabled" branch ({@code gateOrNull} returning {@code null}) or a genuinely
 * user-disabled tool - both read the Eclipse preference store through
 * {@code ToolSettingsStore}/{@code Activator.getDefault()}, which is unavailable headless and returns
 * empty sets rather than throwing (see {@code ToolSettingsStore}'s own class javadoc). Confirming
 * those two branches against a real preset needs a live EDT workspace; that is a live-verify item,
 * not something this test can force.
 * </p>
 */
public class ToolGateTest
{
    @Test
    public void disabledMessageIsTheExactRouterWording()
    {
        // Byte-identical to McpRequestRouter's own literal before this class existed - the point of
        // ToolGate is that this is now the only place the string is written.
        assertEquals(
            "The tool 'x' is disabled and was not executed." //$NON-NLS-1$
                + " Ask the user to enable it in EDT Preferences > AI-EDT > Tools tab, then try again.", //$NON-NLS-1$
            ToolGate.disabledMessage("x")); //$NON-NLS-1$
    }

    @Test
    public void disabledMessageEmbedsTheGivenToolName()
    {
        String message = ToolGate.disabledMessage("delete_metadata_object"); //$NON-NLS-1$
        assertEquals(
            "The tool 'delete_metadata_object' is disabled and was not executed." //$NON-NLS-1$
                + " Ask the user to enable it in EDT Preferences > AI-EDT > Tools tab, then try again.", //$NON-NLS-1$
            message);
    }

    @Test
    public void gateOrNullReturnsExactlyDisabledMessageWhenTheToolIsNotEnabled()
    {
        // A name unique enough that no other test in this JVM could plausibly have registered it -
        // see the class javadoc for why that makes isToolEnabled(...) deterministically false here,
        // regardless of test execution order.
        String toolName = "definitely_not_a_registered_tool_" + System.nanoTime(); //$NON-NLS-1$
        String gate = ToolGate.gateOrNull(toolName);
        assertNotNull("an unregistered tool must never gate through as enabled", gate); //$NON-NLS-1$
        assertEquals(ToolGate.disabledMessage(toolName), gate);
    }
}
