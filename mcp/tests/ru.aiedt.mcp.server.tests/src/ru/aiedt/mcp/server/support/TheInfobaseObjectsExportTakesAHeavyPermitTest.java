/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;

/**
 * The Designer an {@code export_infobase_objects} call runs is work the server weighs before it
 * starts: the call takes one of the heavy permits, and the heap and the concurrency limit can
 * refuse it before anything is launched.
 * <p>
 * The operation has no standalone tool of its own - the facade runs it itself - so the road
 * learns its weight from where the facade's route says the call goes. A route that answers
 * nothing leaves the call unweighed, and parallel calls on different bases would start one
 * Designer each past the limiter and the heap gate.
 * </p>
 */
public class TheInfobaseObjectsExportTakesAHeavyPermitTest
{
    private final List<String> added = new ArrayList<>();

    /**
     * The catalogue is filled when the server starts and never is under the test harness, so the
     * test registers the one name it asks about and takes it back out.
     */
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
     * The call through the facade takes a heavy permit and holds it until its ticket is spent.
     */
    @Test
    public void theOperationThroughTheFacadeTakesAHeavyPermit()
    {
        ensure(new ConfigIoFacadeTool());
        Semaphore permits = new Semaphore(1);
        ToolRoad road = new ToolRoad(permits);
        ToolRoad.Admission admission = road.admit(ConfigIoFacadeTool.NAME,
            Map.of("operation", "export_infobase_objects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the export is admitted: " + admission.refusal(), admission.refusal());
        assertTrue("the Designer run takes one of the heavy permits", //$NON-NLS-1$
            admission.ticket().holdsPermit());
        assertTrue("the permit is held while the call runs", permits.availablePermits() == 0); //$NON-NLS-1$
        admission.ticket().release();
        assertTrue("the permit returns once the call is done", permits.availablePermits() == 1); //$NON-NLS-1$
    }

    /**
     * The same call is turned away at the concurrency limit and when the heap has no room - the
     * two refusals a thick-client spawn must meet BEFORE a Designer starts.
     */
    @Test
    public void theOperationIsRefusedAtTheLimitAndOnTheHeap()
    {
        ensure(new ConfigIoFacadeTool());
        ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
        assertEquals("the concurrency limit refuses the Designer run", ToolRoad.MSG_HEAVY_BUSY, //$NON-NLS-1$
            atTheLimit.admit(ConfigIoFacadeTool.NAME,
                Map.of("operation", "export_infobase_objects")).refusal()); //$NON-NLS-1$ //$NON-NLS-2$
        ToolRoad noHeap = new ToolRoad(new Semaphore(1),
            () -> ToolRoad.MSG_HEAP_EXHAUSTED + "test reading"); //$NON-NLS-1$
        assertEquals("the heap refuses the Designer run", //$NON-NLS-1$
            ToolRoad.MSG_HEAP_EXHAUSTED + "test reading", //$NON-NLS-1$
            noHeap.admit(ConfigIoFacadeTool.NAME,
                Map.of("operation", "export_infobase_objects")).refusal()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The camelCase spelling of the selector - accepted by the facade's own execute - is weighed
     * by the road the same way: the permit is taken, and the concurrency limit refuses it.
     */
    @Test
    public void theCamelCaseSelectorTakesThePermitAndMeetsTheLimit()
    {
        ensure(new ConfigIoFacadeTool());
        ToolRoad road = new ToolRoad(new Semaphore(1));
        ToolRoad.Admission admission = road.admit(ConfigIoFacadeTool.NAME,
            Map.of("operation", "exportInfobaseObjects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the camelCase selector is admitted: " + admission.refusal(), admission.refusal()); //$NON-NLS-1$
        assertTrue("the camelCase selector takes one of the heavy permits", //$NON-NLS-1$
            admission.ticket().holdsPermit());
        admission.ticket().release();

        ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
        assertEquals("the camelCase selector meets the concurrency limit", ToolRoad.MSG_HEAVY_BUSY, //$NON-NLS-1$
            atTheLimit.admit(ConfigIoFacadeTool.NAME,
                Map.of("operation", "exportInfobaseObjects")).refusal()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
