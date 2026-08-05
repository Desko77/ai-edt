/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolCategory;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers what {@code start_client} promises before it ever reaches a launch manager: that it refuses
 * an unresolvable request rather than guessing, and that a preset which says no launching means it.
 * <p>
 * Actually starting a client needs a workspace with a configured application behind it, so that half
 * is left to a live EDT. What is pinned here is the part a caller relies on being true without one.
 * </p>
 */
public class ClientSessionStarterTest
{
    @Test
    public void tooLittleToIdentifyAConfigurationIsRefused()
    {
        // The dangerous alternative is picking "the only client config" and running against an
        // infobase nobody asked about. Saying no costs a round trip; guessing costs data.
        String out = new ClientSessionStarter().execute(new HashMap<>());
        assertTrue("an empty request should not resolve to a configuration", //$NON-NLS-1$
            out.contains("No launch configuration matched") //$NON-NLS-1$
                || out.contains("launch manager is not published")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownConfigurationNameIsRefusedRatherThanSubstituted()
    {
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "aiedt-tests-no-such-configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        String out = new ClientSessionStarter().execute(params);
        assertTrue("a name that matches nothing should not fall back to another config", //$NON-NLS-1$
            out.contains("No launch configuration matched") //$NON-NLS-1$
                || out.contains("launch manager is not published")); //$NON-NLS-1$
    }

    @Test
    public void theToolDescribesItselfWellEnoughToBeChosen()
    {
        IMcpTool tool = new ClientSessionStarter();
        assertEquals("start_client", tool.getName()); //$NON-NLS-1$
        String description = tool.getDescription();
        // The point of the tool is that a caller stops hand-rolling a command line, so the
        // description has to say so - otherwise an agent keeps reaching for the shell.
        assertTrue("the description should steer callers off 1cv8.exe", //$NON-NLS-1$
            description.contains("1cv8.exe")); //$NON-NLS-1$
        // And it has to point at the debugger, or a caller wanting breakpoints picks this by mistake
        // and finds out only when one fails to fire.
        assertTrue("the description should point at launch_debugger for debugging", //$NON-NLS-1$
            description.contains("launch_debugger")); //$NON-NLS-1$
        assertTrue("the schema should accept a configuration name", //$NON-NLS-1$
            tool.getInputSchema().contains("launchConfigurationName")); //$NON-NLS-1$
    }

    @Test
    public void startingAClientIsNotAReadOnlyAction()
    {
        // It launches a process against a live infobase. A preset that promises read-only and still
        // allows this would be lying in the most literal way available.
        assertTrue("read-only must not allow start_client", //$NON-NLS-1$
            ToolProfile.READ_ONLY.getDisabledTools().contains(ClientSessionStarter.NAME));
        assertTrue("code review must not allow start_client", //$NON-NLS-1$
            ToolProfile.CODE_REVIEW.getDisabledTools().contains(ClientSessionStarter.NAME));
    }

    @Test
    public void theToolBelongsToTheGroupThatCarriesItsNeighbours()
    {
        // Presets switch whole groups off, so a launch tool outside the runtime group would slip
        // through every one of them.
        assertTrue("start_client belongs with the other launch tools", //$NON-NLS-1$
            ToolCategory.APPLICATIONS.getToolNames().contains(ClientSessionStarter.NAME));
        assertFalse("running tests must stay able to start a client", //$NON-NLS-1$
            ToolProfile.DEBUG_AND_TEST.getDisabledTools().contains(ClientSessionStarter.NAME));
    }
}
