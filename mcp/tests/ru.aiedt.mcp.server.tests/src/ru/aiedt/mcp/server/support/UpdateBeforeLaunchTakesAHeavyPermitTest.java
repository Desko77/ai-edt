/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.ClientSessionStarter;
import ru.aiedt.mcp.server.toolkit.ops.DebugSessionStarter;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.LaunchDebuggerTool;

/**
 * A call that updates the infobase before it starts a client is weighed like the update it runs:
 * {@code update_database} is heavy, and a debug launch updates by default while a plain client
 * start updates only on request. The permit and the two refusals are decided before anything is
 * launched, from the name the call arrives under - the facade's under the Canonical preset - so
 * each caller's route has to answer {@code update_database} exactly when its own dispatch will
 * update.
 */
public class UpdateBeforeLaunchTakesAHeavyPermitTest
{
    private final List<String> added = new ArrayList<>();

    /**
     * Registers the tools a call is admitted under. The catalogue is filled when the server starts
     * and never is under the test harness, so the test registers them itself.
     */
    @Before
    public void theToolsAreCallable()
    {
        ensure(new LaunchDebuggerTool());
        ensure(new DebugSessionStarter());
        ensure(new ClientSessionStarter());
        ensure(new InfobaseAdminFacadeTool());
        ensure(new ExtensionWorkshopTool());
    }

    @After
    public void theToolGoes()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        for (String name : added)
        {
            catalog.unregister(name);
        }
    }

    /**
     * Registers a tool the catalogue does not hold, if it does not hold it already.
     *
     * @param tool the tool a call is about to be admitted under
     */
    private void ensure(IMcpTool tool)
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        if (catalog.getTool(tool.getName()) == null)
        {
            catalog.register(tool);
            added.add(tool.getName());
        }
    }

    /**
     * Requires the call to take one of the heavy permits and to meet the concurrency limit.
     *
     * @param toolName the name the call arrives under
     * @param arguments the call arguments
     */
    private void assertTakesAPermit(String toolName, Map<String, String> arguments)
    {
        Semaphore permits = new Semaphore(1);
        ToolRoad.Admission admission = new ToolRoad(permits).admit(toolName, arguments);
        assertNull(toolName + " " + arguments + " is admitted: " + admission.refusal(), //$NON-NLS-1$ //$NON-NLS-2$
            admission.refusal());
        assertTrue(toolName + " " + arguments + " takes one of the heavy permits", //$NON-NLS-1$ //$NON-NLS-2$
            admission.ticket().holdsPermit());
        assertTrue(toolName + " holds the permit while the call runs", permits.availablePermits() == 0); //$NON-NLS-1$
        admission.ticket().release();
        ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
        assertEquals(toolName + " " + arguments + " meets the concurrency limit", //$NON-NLS-1$ //$NON-NLS-2$
            ToolRoad.MSG_HEAVY_BUSY, atTheLimit.admit(toolName, arguments).refusal());
    }

    /**
     * Requires the call to be weighed as light: no permit, and admitted even at the limit.
     *
     * @param toolName the name the call arrives under
     * @param arguments the call arguments
     */
    private void assertTakesNoPermit(String toolName, Map<String, String> arguments)
    {
        Semaphore permits = new Semaphore(1);
        ToolRoad.Admission admission = new ToolRoad(permits).admit(toolName, arguments);
        assertNull(toolName + " " + arguments + " is admitted: " + admission.refusal(), //$NON-NLS-1$ //$NON-NLS-2$
            admission.refusal());
        assertFalse(toolName + " " + arguments + " takes no heavy permit", //$NON-NLS-1$ //$NON-NLS-2$
            admission.ticket().holdsPermit());
        assertTrue(toolName + " leaves the permits alone", permits.availablePermits() == 1); //$NON-NLS-1$
        ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
        assertNull(toolName + " " + arguments + " starts nothing heavy, so the limit does not bite", //$NON-NLS-1$ //$NON-NLS-2$
            atTheLimit.admit(toolName, arguments).refusal());
    }

    /**
     * The arguments of one call.
     *
     * @param pairs name and value, in order
     * @return the arguments as a map
     */
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            arguments.put(pairs[i], pairs[i + 1]);
        }
        return arguments;
    }

    /**
     * A debug launch updates the database before the client starts unless the caller opts out, and
     * the update is the heavy work - through the facade and at the standalone name.
     */
    @Test
    public void aDebugLaunchUpdatesByDefaultAndTakesAPermit()
    {
        assertTakesAPermit(LaunchDebuggerTool.NAME, args("action", "launch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesAPermit(LaunchDebuggerTool.NAME, args("action", "debug_launch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesAPermit(DebugSessionStarter.NAME, args());
    }

    /**
     * The opt-out is read the way the tool itself reads it: with {@code updateBeforeLaunch=false}
     * nothing updates and nothing is weighed.
     */
    @Test
    public void aDebugLaunchWithoutTheUpdateTakesNoPermit()
    {
        assertTakesNoPermit(LaunchDebuggerTool.NAME,
            args("action", "launch", "updateBeforeLaunch", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTakesNoPermit(DebugSessionStarter.NAME, args("updateBeforeLaunch", "false")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A plain client start updates only when asked: without the argument nothing heavy runs, with
     * {@code updateBeforeLaunch=true} the update is weighed - at the standalone name and through
     * {@code infobase_admin}.
     */
    @Test
    public void aClientStartTakesAPermitOnlyForTheUpdateItWasAskedFor()
    {
        assertTakesNoPermit(ClientSessionStarter.NAME, args());
        assertTakesAPermit(ClientSessionStarter.NAME, args("updateBeforeLaunch", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesNoPermit(InfobaseAdminFacadeTool.NAME, args("operation", "start_client")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesAPermit(InfobaseAdminFacadeTool.NAME,
            args("operation", "start_client", "updateBeforeLaunch", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * The verdict route accepts only the spelling the facade's own dispatch accepts: the exact
     * snake_case operation. An uppercase or a padded spelling is refused as an unknown operation
     * before any work runs, so the road must not weigh it - and the exact spelling stays weighed.
     */
    @Test
    public void theVerdictRouteAcceptsOnlyTheSpellingTheDispatchAccepts()
    {
        ExtensionWorkshopTool workshop = new ExtensionWorkshopTool();
        assertNull("an uppercase operation is refused by the dispatch, so the route answers nothing", //$NON-NLS-1$
            workshop.routesTo(args("operation", "CHECK_PLATFORM_VERDICT"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a padded operation is refused by the dispatch, so the route answers nothing", //$NON-NLS-1$
            workshop.routesTo(args("operation", " check_platform_verdict "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesNoPermit(ExtensionWorkshopTool.NAME,
            args("operation", "CHECK_PLATFORM_VERDICT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesNoPermit(ExtensionWorkshopTool.NAME,
            args("operation", " check_platform_verdict ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesAPermit(ExtensionWorkshopTool.NAME,
            args("operation", "check_platform_verdict")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
