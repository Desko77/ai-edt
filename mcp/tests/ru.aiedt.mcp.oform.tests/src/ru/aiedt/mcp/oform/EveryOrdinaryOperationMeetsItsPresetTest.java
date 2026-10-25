/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.support.ToolRoad;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;
import ru.aiedt.mcp.server.wire.ToolAnnotations;

/**
 * Every operation of the actual registered {@code ordinary_forms} tool meets the preset the
 * headless test runtime holds, taken through the road the way a client's call reaches the tool:
 * routable under the presets that keep the surface, the writing operations stopped at the write
 * door before any project is resolved under each write-blocking preset, the reads passing through
 * to the missing-project answer, and the published hints following the door. A hand-picked
 * selection that names the tool itself takes every operation off at the gate.
 *
 * <p>The selection lives in the plugin's own instance-scope node - the preset and both tool
 * lists - and every pass records all three keys before it writes and puts each back afterwards,
 * a key that was absent going back to absent rather than empty. The presets are changed in the
 * headless test runtime only; no real project, source file, infobase or background work is
 * touched, and no registration is added, replaced or removed.</p>
 */
public class EveryOrdinaryOperationMeetsItsPresetTest
{
    /** The plugin's preference node and the keys its tool selection is stored under. */
    private static final String PLUGIN_NODE = "ru.aiedt.mcp.server"; //$NON-NLS-1$

    private static final String PRESET_KEY = "mcpToolPreset"; //$NON-NLS-1$

    private static final String DISABLED_KEY = "mcpDisabledTools"; //$NON-NLS-1$

    private static final String UNLISTED_KEY = "mcpUnlistedTools"; //$NON-NLS-1$

    /** The tool's own wire name: what a caller asks for and what a hand-picked selection lists. */
    private static final String TOOL = "ordinary_forms"; //$NON-NLS-1$

    /** What the preset key holds when the selection is hand-picked rather than a named preset. */
    private static final String NO_PRESET = ""; //$NON-NLS-1$

    /** What a selection holds in the list a pass does not exercise. */
    private static final String NOTHING = ""; //$NON-NLS-1$

    /** Every preset that blocks writes, as the store records them. */
    private static final List<String> WRITE_BLOCKING = List.of("READ_ONLY", "CODE_REVIEW", "DEBUG_AND_TEST"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The presets that keep the whole surface, as the store records them. */
    private static final List<String> SURFACE_KEPT = List.of("ALL_TOOLS", "CANONICAL"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The origin the road calls are recorded under. */
    private static final String ORIGIN = "ru.aiedt.mcp.oform.tests"; //$NON-NLS-1$

    private IEclipsePreferences node;

    private String presetBefore;

    private String disabledBefore;

    private String unlistedBefore;

    /** Records the whole selection - the preset and both lists - absent keys included. */
    @Before
    public void theSelectionAsItStands()
    {
        node = InstanceScope.INSTANCE.getNode(PLUGIN_NODE);
        presetBefore = node.get(PRESET_KEY, null);
        disabledBefore = node.get(DISABLED_KEY, null);
        unlistedBefore = node.get(UNLISTED_KEY, null);
    }

    /** Puts the selection back, a key that was absent going back to absent rather than empty. */
    @After
    public void theSelectionGoesBack()
    {
        restore(node, PRESET_KEY, presetBefore);
        restore(node, DISABLED_KEY, disabledBefore);
        restore(node, UNLISTED_KEY, unlistedBefore);
    }

    /**
     * Under the two presets that keep the whole surface, the actual registered tool answers all
     * 24 catalog operations through the road with no projectName: no whole-tool refusal and no
     * write-door refusal, help returns actual success, and every other operation reaches the
     * tool's own missing-project refusal. This proves routing availability, not successful
     * business execution - no project exists here and none is resolved.
     */
    @Test
    public void everyOperationIsRoutableUnderTheOpenPresets()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        IMcpTool registered = catalog.getTool(TOOL);
        assertTrue("the registered tool is the actual OrdinaryFormsTool", //$NON-NLS-1$
            registered instanceof OrdinaryFormsTool);
        assertTrue("published as an external tool", catalog.getExternalTools().containsKey(TOOL)); //$NON-NLS-1$
        assertEquals("its writes are declared as one external capability", //$NON-NLS-1$
            Boolean.TRUE, catalog.externalWritesDeclared(TOOL));
        assertEquals("24 operations", 24, OperationsCatalog.knownNames().size()); //$NON-NLS-1$
        for (String preset : SURFACE_KEPT)
        {
            theSelectionIs(preset, NOTHING);
            assertTrue(preset, catalog.isToolEnabled(TOOL));
            assertFalse(preset, catalog.isDisabledByPreset(TOOL));
            assertFalse(preset, catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
            everyOperationReachesTheTool(preset);
        }
    }

    /**
     * Under each write-blocking preset the inventory splits exactly: the 18 writers meet the
     * door's no-preview refusal - with a dryRun, without a project and with a bogus project
     * alike, before any project validation or resolution - and the 6 reads reach the project
     * resolution or answer as help, under every preset rather than only the first. The exact
     * 24/18/6 inventory is asserted first, so the split cannot pass vacuously. No real database
     * effect is claimed or checked.
     */
    @Test
    public void everyOperationMeetsItsPresetDoorUnderTheWriteBlockers()
    {
        List<String> reading = readingNames();
        assertEquals("24 operations", 24, OperationsCatalog.knownNames().size()); //$NON-NLS-1$
        assertEquals("18 writers", 18, OperationsCatalog.writingNames().size()); //$NON-NLS-1$
        assertEquals("6 reads", 6, reading.size()); //$NON-NLS-1$
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        for (String preset : WRITE_BLOCKING)
        {
            theSelectionIs(preset, NOTHING);
            assertTrue("the door is closed by " + preset, //$NON-NLS-1$
                catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
            assertTrue("the tool stays callable under " + preset, catalog.isToolEnabled(TOOL)); //$NON-NLS-1$
            everyWriterMeetsTheDoor(preset);
            everyReadReachesTheProject(preset, reading);
        }
    }

    /**
     * The hints of the actual registered tool follow its door: readOnlyHint and idempotentHint
     * under every write blocker, absent under both surface-keeping presets, openWorldHint false
     * throughout. The tool declares no aliases - asserted as a precondition, while alias
     * execution is the separate framework case of the generic alias tests. A hand-picked
     * selection naming the tool itself then turns every one of the 24 operations away at the
     * gate, by the gate's own refusal rather than the registry's.
     */
    @Test
    public void theHintsFollowTheDoorAndTheNameDisablesTheWholeTool()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        IMcpTool registered = catalog.getTool(TOOL);
        assertTrue("the registered tool is the actual OrdinaryFormsTool", //$NON-NLS-1$
            registered instanceof OrdinaryFormsTool);
        assertTrue("the actual tool declares no aliases", registered.getAliases().isEmpty()); //$NON-NLS-1$
        for (String preset : WRITE_BLOCKING)
        {
            theSelectionIs(preset, NOTHING);
            assertTrue(preset, catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
            Map<String, Object> hints = ToolAnnotations.of(registered, catalog);
            assertEquals(preset, Boolean.TRUE, hints.get("readOnlyHint")); //$NON-NLS-1$
            assertEquals(preset, Boolean.TRUE, hints.get("idempotentHint")); //$NON-NLS-1$
            assertEquals(preset, Boolean.FALSE, hints.get("openWorldHint")); //$NON-NLS-1$
        }
        for (String preset : SURFACE_KEPT)
        {
            theSelectionIs(preset, NOTHING);
            assertFalse(preset, catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
            Map<String, Object> hints = ToolAnnotations.of(registered, catalog);
            assertNull(preset, hints.get("readOnlyHint")); //$NON-NLS-1$
            assertNull(preset, hints.get("idempotentHint")); //$NON-NLS-1$
            assertEquals(preset, Boolean.FALSE, hints.get("openWorldHint")); //$NON-NLS-1$
        }
        theSelectionIs(NO_PRESET, TOOL);
        assertTrue("the hand-picked selection names the tool", catalog.isDisabledByPreset(TOOL)); //$NON-NLS-1$
        assertFalse("the call gate answers false for the name", catalog.isToolEnabled(TOOL)); //$NON-NLS-1$
        ToolRoad road = new ToolRoad(new Semaphore(1));
        for (String name : OperationsCatalog.knownNames())
        {
            String where = TOOL + " " + name; //$NON-NLS-1$
            ToolRoadOutcome outcome = road.call(TOOL, Map.of("operation", name), ORIGIN); //$NON-NLS-1$
            assertTrue(where, outcome.refused());
            String refusal = outcome.refusal();
            assertNotNull(where, refusal);
            assertTrue(refusal, refusal.contains(TOOL));
            assertTrue(refusal, refusal.contains("disabled")); //$NON-NLS-1$
        }
    }

    /**
     * One call per catalog operation through the road, with no projectName: help answers success,
     * every other operation meets the tool's own missing-project refusal - never the road's
     * refusal and never the write door's.
     *
     * @param preset the preset in force, for the failure messages
     */
    private static void everyOperationReachesTheTool(String preset)
    {
        ToolRoad road = new ToolRoad(new Semaphore(1));
        for (String name : OperationsCatalog.knownNames())
        {
            String where = preset + " " + name; //$NON-NLS-1$
            ToolRoadOutcome outcome = road.call(TOOL, Map.of("operation", name), ORIGIN); //$NON-NLS-1$
            assertFalse(where, outcome.refused());
            assertNull(where, outcome.failure());
            String text = outcome.text();
            assertNotNull(where, text);
            assertFalse(where, text.contains("switched off by the tool settings")); //$NON-NLS-1$
            if ("help".equals(name)) //$NON-NLS-1$
            {
                assertTrue(where, JsonParser.parseString(text).getAsJsonObject().get("success") //$NON-NLS-1$
                    .getAsBoolean());
            }
            else
            {
                assertTrue(where, text.contains("projectName must be provided")); //$NON-NLS-1$
            }
        }
    }

    /**
     * The 18 writers under one write-blocking preset meet the door's refusal in all three
     * argument shapes and never a project message.
     *
     * @param preset the preset in force, for the failure messages
     */
    private static void everyWriterMeetsTheDoor(String preset)
    {
        ToolRoad road = new ToolRoad(new Semaphore(1));
        for (String name : OperationsCatalog.writingNames())
        {
            assertDoorRefusal(road, preset, name, Map.of("operation", name, "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertDoorRefusal(road, preset, name, Map.of("operation", name)); //$NON-NLS-1$
            assertDoorRefusal(road, preset, name, Map.of("operation", name, "projectName", "no-such-project")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * One writing call meets the door's own no-preview refusal in the tool's answer text: the
     * road did not refuse the call, the refusal names the switched-off capability and the absent
     * preview, and no project validation or resolution wording appears.
     *
     * @param road the road the call takes
     * @param preset the preset in force, for the failure messages
     * @param name the writing operation, for the failure messages
     * @param arguments the call arguments of the shape under test
     */
    private static void assertDoorRefusal(ToolRoad road, String preset, String name,
        Map<String, Object> arguments)
    {
        String where = preset + " " + name; //$NON-NLS-1$
        ToolRoadOutcome outcome = road.call(TOOL, arguments, ORIGIN);
        assertFalse(where + ": the refusal is the tool's own text, not the road's", //$NON-NLS-1$
            outcome.refused());
        String text = outcome.text();
        assertNotNull(where, text);
        assertTrue(where, text.contains("switched off by the tool settings")); //$NON-NLS-1$
        assertTrue(where, text.contains("nothing was executed - not even as dryRun")); //$NON-NLS-1$
        assertFalse(where, text.contains("projectName must be provided")); //$NON-NLS-1$
        assertFalse(where, text.contains("no-such-project")); //$NON-NLS-1$
    }

    /**
     * The reads under one write-blocking preset: the project-needing ones reach the
     * missing-project refusal, not the door, and help answers with success.
     *
     * @param preset the preset in force, for the failure messages
     * @param reading the reading operations, help included
     */
    private static void everyReadReachesTheProject(String preset, List<String> reading)
    {
        ToolRoad road = new ToolRoad(new Semaphore(1));
        for (String name : reading)
        {
            String where = preset + " " + name; //$NON-NLS-1$
            ToolRoadOutcome outcome = road.call(TOOL, Map.of("operation", name), ORIGIN); //$NON-NLS-1$
            assertFalse(where, outcome.refused());
            String text = outcome.text();
            assertNotNull(where, text);
            assertFalse(where, text.contains("switched off by the tool settings")); //$NON-NLS-1$
            if ("help".equals(name)) //$NON-NLS-1$
            {
                assertTrue(where, JsonParser.parseString(text).getAsJsonObject().get("success") //$NON-NLS-1$
                    .getAsBoolean());
            }
            else
            {
                assertTrue(where, text.contains("projectName must be provided")); //$NON-NLS-1$
            }
        }
    }

    /**
     * The reading operations: every known name that is not a writing one, in the catalog's
     * order.
     *
     * @return the reading operation names, help included
     */
    private static List<String> readingNames()
    {
        List<String> reading = new ArrayList<>(OperationsCatalog.knownNames());
        reading.removeAll(OperationsCatalog.writingNames());
        return reading;
    }

    /**
     * Records the selection under test as one whole state: the preset, the list of switched-off
     * tools and the list of unlisted ones, so that no pass reads a list another pass left behind.
     *
     * @param preset the preset to record, or an empty string for a hand-picked selection
     * @param disabled the tool names the selection lists as switched off, as the store holds them
     */
    private void theSelectionIs(String preset, String disabled)
    {
        node.put(PRESET_KEY, preset);
        node.put(DISABLED_KEY, disabled);
        node.put(UNLISTED_KEY, NOTHING);
    }

    /**
     * Puts one key back to the state the test found it in: removed when it was absent, its
     * recorded value otherwise.
     *
     * @param node the preference node the selection lives in
     * @param key the preference key
     * @param value the value the key held before the test, or {@code null} when it was absent
     */
    private static void restore(IEclipsePreferences node, String key, String value)
    {
        if (value == null)
        {
            node.remove(key);
        }
        else
        {
            node.put(key, value);
        }
    }
}
