/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.AbstractMap;
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
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.ExternalBinaryUnpacker;
import ru.aiedt.mcp.server.toolkit.ops.ExternalObjectWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseCreator;
import ru.aiedt.mcp.server.toolkit.ops.SyncControlTool;

/**
 * Every call that starts a Configuration process is weighed before it starts: it takes one of the
 * heavy permits, and the heap and the concurrency limit can refuse it before anything is launched.
 * <p>
 * The census behind this test walks the spawn sites - the Designer runs, the CREATEINFOBASE child
 * process, the staging infobase - and maps each to the name its call actually arrives under. Under
 * the Canonical preset that name is a facade's, so a spawn reached through one is weighed only when
 * the facade's route names it; without that, parallel calls on different bases start one client
 * each past the limiter.
 * </p>
 */
public class EveryThickClientSpawnTakesAHeavyPermitTest
{
    private final List<String> added = new ArrayList<>();

    /**
     * The facades a spawn is reached through, by wire tool name. The catalogue is filled when the
     * server starts and never is under the test harness, so the test registers them itself.
     */
    private static final Map<String, IMcpTool> FACADES = new LinkedHashMap<>();

    static
    {
        FACADES.put(ConfigIoFacadeTool.NAME, new ConfigIoFacadeTool());
        FACADES.put(InfobaseAdminFacadeTool.NAME, new InfobaseAdminFacadeTool());
        FACADES.put(ExternalObjectWorkshopTool.NAME, new ExternalObjectWorkshopTool());
        FACADES.put(SyncControlTool.NAME, new SyncControlTool());
        FACADES.put(ExtensionWorkshopTool.NAME, new ExtensionWorkshopTool());
    }

    @Before
    public void theFacadesAreCallable()
    {
        FACADES.values().forEach(this::ensure);
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

    private static Map.Entry<String, Map<String, String>> call(String toolName, String operation)
    {
        return new AbstractMap.SimpleEntry<>(toolName, Map.of("operation", operation)); //$NON-NLS-1$
    }

    /**
     * The spawns reached through a facade, each with the selector its client sends.
     */
    private static List<Map.Entry<String, Map<String, String>>> throughFacades()
    {
        List<Map.Entry<String, Map<String, String>>> calls = new ArrayList<>();
        calls.add(call(ConfigIoFacadeTool.NAME, "export_configuration_to_cf")); //$NON-NLS-1$
        calls.add(call(ConfigIoFacadeTool.NAME, "unpack_external_binary")); //$NON-NLS-1$
        calls.add(call(InfobaseAdminFacadeTool.NAME, "create_infobase")); //$NON-NLS-1$
        calls.add(call(ExternalObjectWorkshopTool.NAME, "import_external_object")); //$NON-NLS-1$
        calls.add(call(SyncControlTool.NAME, "rebuild_dump_info")); //$NON-NLS-1$
        calls.add(call(ExtensionWorkshopTool.NAME, "check_platform_verdict")); //$NON-NLS-1$
        return calls;
    }

    /**
     * Each spawn takes a heavy permit and holds it until its ticket is spent.
     */
    @Test
    public void everySpawnThroughAFacadeTakesAHeavyPermit()
    {
        for (Map.Entry<String, Map<String, String>> spawn : throughFacades())
        {
            Semaphore permits = new Semaphore(1);
            ToolRoad road = new ToolRoad(permits);
            ToolRoad.Admission admission = road.admit(spawn.getKey(), spawn.getValue());
            assertNull(spawn.getKey() + " " + spawn.getValue() + " is admitted: " //$NON-NLS-1$ //$NON-NLS-2$
                + admission.refusal(), admission.refusal());
            assertTrue(spawn.getKey() + " takes one of the heavy permits", //$NON-NLS-1$
                admission.ticket().holdsPermit());
            assertTrue(spawn.getKey() + " holds the permit while the call runs", //$NON-NLS-1$
                permits.availablePermits() == 0);
            admission.ticket().release();
            assertTrue(spawn.getKey() + " returns the permit once it is done", //$NON-NLS-1$
                permits.availablePermits() == 1);
        }
    }

    /**
     * The same calls are turned away at the concurrency limit and when the heap has no room - the
     * two refusals a spawn must meet BEFORE a platform process starts.
     */
    @Test
    public void everySpawnIsRefusedAtTheLimitAndOnTheHeap()
    {
        for (Map.Entry<String, Map<String, String>> spawn : throughFacades())
        {
            ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
            assertEquals(spawn.getKey() + " meets the concurrency limit", ToolRoad.MSG_HEAVY_BUSY, //$NON-NLS-1$
                atTheLimit.admit(spawn.getKey(), spawn.getValue()).refusal());
            ToolRoad noHeap = new ToolRoad(new Semaphore(1),
                () -> ToolRoad.MSG_HEAP_EXHAUSTED + "test reading"); //$NON-NLS-1$
            assertEquals(spawn.getKey() + " meets the heap guard", //$NON-NLS-1$
                ToolRoad.MSG_HEAP_EXHAUSTED + "test reading", //$NON-NLS-1$
                noHeap.admit(spawn.getKey(), spawn.getValue()).refusal());
        }
    }

    /**
     * A call that arrives under the standalone name is weighed by that name, with no facade and no
     * route in between.
     */
    @Test
    public void everyStandaloneSpawnTakesAHeavyPermit()
    {
        ensure(new ExternalBinaryUnpacker());
        ensure(new InfobaseCreator());
        for (String name : new String[] {ExternalBinaryUnpacker.NAME, InfobaseCreator.NAME})
        {
            Semaphore permits = new Semaphore(1);
            ToolRoad road = new ToolRoad(permits);
            ToolRoad.Admission admission = road.admit(name, new LinkedHashMap<>());
            assertNull(name + " is admitted: " + admission.refusal(), admission.refusal()); //$NON-NLS-1$
            assertTrue(name + " takes one of the heavy permits", admission.ticket().holdsPermit()); //$NON-NLS-1$
            admission.ticket().release();

            ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
            assertEquals(name + " meets the concurrency limit", ToolRoad.MSG_HEAVY_BUSY, //$NON-NLS-1$
                atTheLimit.admit(name, new LinkedHashMap<>()).refusal());
        }
    }

    /**
     * The camelCase spelling a client may send is weighed the same way, wherever the facade's own
     * dispatch accepts it: those routes normalize the selector before looking it up.
     * <p>
     * {@code external_object_workshop} and {@code sync_control} are absent on purpose - their
     * dispatch matches the exact snake_case action, so a camelCase selector is refused before any
     * work runs and there is nothing to weigh.
     * </p>
     */
    @Test
    public void theCamelCaseSelectorIsWeighedWhereItIsAccepted()
    {
        Map<String, Map<String, String>> accepted = new LinkedHashMap<>();
        accepted.put(ConfigIoFacadeTool.NAME, Map.of("operation", "exportConfigurationToCf")); //$NON-NLS-1$ //$NON-NLS-2$
        accepted.put(InfobaseAdminFacadeTool.NAME, Map.of("operation", "createInfobase")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, Map<String, String>> spawn : accepted.entrySet())
        {
            Semaphore permits = new Semaphore(1);
            ToolRoad.Admission admission = new ToolRoad(permits).admit(spawn.getKey(), spawn.getValue());
            assertNull(spawn.getKey() + " " + spawn.getValue() + " is admitted: " //$NON-NLS-1$ //$NON-NLS-2$
                + admission.refusal(), admission.refusal());
            assertTrue(spawn.getKey() + " takes a heavy permit for the camelCase selector", //$NON-NLS-1$
                admission.ticket().holdsPermit());
            admission.ticket().release();

            ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
            assertEquals(spawn.getKey() + " meets the concurrency limit", ToolRoad.MSG_HEAVY_BUSY, //$NON-NLS-1$
                atTheLimit.admit(spawn.getKey(), spawn.getValue()).refusal());
        }
    }

    /**
     * The action carried in a second argument counts too: {@code infobase_admin} forwards
     * {@code syncOperation} to {@link SyncControlTool}, whose Designer run is the one
     * {@code rebuild_dump_info} starts.
     */
    @Test
    public void theActionForwardedInASecondArgumentTakesThePermit()
    {
        Map<String, String> forwarded = new LinkedHashMap<>();
        forwarded.put("operation", "sync_control"); //$NON-NLS-1$ //$NON-NLS-2$
        forwarded.put("syncOperation", "rebuild_dump_info"); //$NON-NLS-1$ //$NON-NLS-2$
        Semaphore permits = new Semaphore(1);
        ToolRoad.Admission admission = new ToolRoad(permits)
            .admit(InfobaseAdminFacadeTool.NAME, forwarded);
        assertNull("the forwarded Designer run is admitted: " + admission.refusal(), admission.refusal()); //$NON-NLS-1$
        assertTrue("the forwarded Designer run takes a heavy permit", admission.ticket().holdsPermit()); //$NON-NLS-1$
        admission.ticket().release();

        ToolRoad atTheLimit = new ToolRoad(new Semaphore(0));
        assertEquals("the forwarded Designer run meets the concurrency limit", //$NON-NLS-1$
            ToolRoad.MSG_HEAVY_BUSY, atTheLimit.admit(InfobaseAdminFacadeTool.NAME, forwarded).refusal());
    }
}
