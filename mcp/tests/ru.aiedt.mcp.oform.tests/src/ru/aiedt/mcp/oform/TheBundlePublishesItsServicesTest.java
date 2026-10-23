/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ToolRoad;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * The bundle's components publish the module source provider and the tool as services, and the
 * plugin's whiteboard picks both up: the provider answers the module registry, the tool is
 * callable, and it declares its writes as one gated capability rather than a whole-tool write -
 * the queries of a mixed tool stay callable while that one capability is switched off.
 *
 * <p>The tool's own name is a second, independent switch: hand-picking the name takes the whole
 * tool off the surface and out of the call gate - its reading operations with its writing ones -
 * while the write capability answers only to the name it is gated by. Both presets that keep the
 * whole surface leave the tool and its writes available, and the write capability is restored by
 * an empty selection, which is what makes the refusals above a property of the selection rather
 * than of the tool.</p>
 *
 * <p>The selection is written into the plugin's own instance-scope node, which is the state the
 * plugin reads - the store of the bundle's activator and that node are two roads to one selection.
 * Every pass records the preset and both tool lists before it writes and puts each of them back
 * afterwards, removing a key that was not there rather than leaving it empty.</p>
 */
public class TheBundlePublishesItsServicesTest
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

    @Test
    public void theProviderIsRegistered()
    {
        assertTrue("no OrdinaryFormModuleProvider among " + ModuleSources.providers(), //$NON-NLS-1$
            ModuleSources.providers().stream().anyMatch(OrdinaryFormModuleProvider.class::isInstance));
    }

    /** The tool is an external service of the plugin and names one capability for all its writes. */
    @Test
    public void theToolDeclaresItsWritesAsOneCapability()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        assertNotNull(catalog.getTool(TOOL));
        assertTrue(catalog.getExternalTools().containsKey(TOOL));
        assertEquals(Boolean.TRUE, catalog.externalWritesDeclared(TOOL));
        assertEquals(List.of(OrdinaryFormsTool.WRITE_DOOR), catalog.getTool(TOOL)
            .getGatedWriteNames());
    }

    /**
     * A hand-picked selection that switches the write capability off leaves the tool callable and
     * the preset set consulted alone answers for the capability; a name nothing registered is not
     * in the disabled set, and the tool's own name is not what its writes are gated by.
     */
    @Test
    public void theWriteCapabilityAnswersToTheDisabledSet()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        theSelectionIs(NO_PRESET, OrdinaryFormsTool.WRITE_DOOR);
        assertTrue(catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
        assertFalse(catalog.isDisabledByPreset(TOOL));
        for (String operation : OperationsCatalog.writingNames())
        {
            String answer = new OrdinaryFormsTool().execute(Map.of("operation", operation)); //$NON-NLS-1$
            assertTrue(answer, answer.contains("switched off by the tool settings")); //$NON-NLS-1$
            assertFalse(answer, answer.contains("active tool preset")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("nothing was executed - not even as dryRun")); //$NON-NLS-1$
        }
    }

    /**
     * A hand-picked selection that lists the tool's own name really takes the tool off the surface:
     * the applied selection names the name, the call gate answers false for it, the tool is not
     * among the enabled ones, and the refusal a call meets is the gate's own wording - taken
     * through the road, the way a client's call reaches the tool, and for a reading operation and
     * every writing one alike. The write capability is not switched off by that, because it is a
     * capability of its own and this selection does not name it.
     */
    @Test
    public void theToolNameIsWhatTheHandPickedSelectionSwitchesOff()
    {
        theSelectionIs(NO_PRESET, TOOL);
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        assertTrue("the selection is applied and names the tool", catalog.isDisabledByPreset(TOOL)); //$NON-NLS-1$
        assertFalse("the call gate answers false for the name a client calls", //$NON-NLS-1$
            catalog.isToolEnabled(TOOL));
        assertFalse("a switched-off tool is not among the enabled ones", listedNames().contains(TOOL)); //$NON-NLS-1$
        assertFalse("the write capability is a name of its own and this selection does not list it", //$NON-NLS-1$
            catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
        everyOperationUnderThatNameMeetsTheGate();
    }

    /**
     * Putting an empty selection in place brings the tool back: it is listed again, the gate answers
     * for it, and its write capability is declared as it was - which is what makes the refusals of
     * the pass above the selection's doing rather than a property of the tool.
     */
    @Test
    public void theSelectionTakenBackLeavesTheToolAvailable()
    {
        theSelectionIs(NO_PRESET, TOOL);
        assertFalse(McpToolCatalog.getInstance().isToolEnabled(TOOL));
        theSelectionIs(NO_PRESET, NOTHING);
        theToolAndItsWritesAreAvailable("an empty selection"); //$NON-NLS-1$
    }

    /**
     * The All Tools preset keeps the whole surface: it lists nothing as switched off, so the tool
     * stays listed and callable with its write capability declared.
     *
     * <p>The tool's name is left in the list of switched-off tools while the preset is recorded, so
     * the pass reads the preset rather than that leftover list: a stored name this version does not
     * know is treated as a hand-picked selection, which falls back to the list, and the assertions
     * below would then fail on the very selection the preset was chosen to replace.</p>
     */
    @Test
    public void theAllToolsPresetLeavesTheToolAvailable()
    {
        theSelectionIs("ALL_TOOLS", TOOL); //$NON-NLS-1$
        theToolAndItsWritesAreAvailable("All Tools"); //$NON-NLS-1$
    }

    /**
     * The Canonical preset hides the standalone names its facades took over, and the ordinary tool
     * is not one of them: it stays listed and callable with its write capability declared. The list
     * of switched-off tools is left holding the tool's name here too, for the reason given above.
     */
    @Test
    public void theCanonicalPresetLeavesTheToolAvailable()
    {
        theSelectionIs("CANONICAL", TOOL); //$NON-NLS-1$
        theToolAndItsWritesAreAvailable("Canonical"); //$NON-NLS-1$
    }

    /**
     * The write into the bundle activator's store and the write into the plugin's node are one
     * selection: what the store records the node holds, and what the node records the store reads
     * back - so what this class writes is what the plugin's own code reads, and a pass that writes
     * through either road is the same pass.
     */
    @Test
    public void theStoreAndTheNodeAreOneSelection()
    {
        theSelectionIs(NO_PRESET, NOTHING);
        IPreferenceStore store = Activator.getDefault() == null ? null : Activator.getDefault()
            .getPreferenceStore();
        assertNotNull("the bundle has no preference store in this runtime", store); //$NON-NLS-1$
        store.setValue(DISABLED_KEY, TOOL);
        assertEquals("the node holds what the store recorded", TOOL, node.get(DISABLED_KEY, null)); //$NON-NLS-1$
        assertFalse("the catalogue reads the selection the store recorded", //$NON-NLS-1$
            McpToolCatalog.getInstance().isToolEnabled(TOOL));
        node.put(DISABLED_KEY, OrdinaryFormsTool.WRITE_DOOR);
        assertEquals("the store reads back what the node recorded", //$NON-NLS-1$
            OrdinaryFormsTool.WRITE_DOOR, store.getString(DISABLED_KEY));
        assertTrue(McpToolCatalog.getInstance().isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
    }

    /**
     * One call per reading operation and per writing operation of the tool meets the gate's own
     * refusal for the tool's name, and never the refusal the registry answers to a name nothing
     * registered - so the refusal really is the disabled name's.
     */
    private static void everyOperationUnderThatNameMeetsTheGate()
    {
        ToolRoad road = new ToolRoad(new Semaphore(1));
        String unregistered = road.call("ai_edt_unknown_tool_for_the_gate_test", //$NON-NLS-1$
            Map.of("operation", "help"), "test").refusal(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("a name nothing registered has to meet the registry's refusal", unregistered); //$NON-NLS-1$
        assertTrue(unregistered, unregistered.contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the tool has to have a writing operation for this pass to mean anything", //$NON-NLS-1$
            !OperationsCatalog.writingNames().isEmpty());
        List<String> operations = List.copyOf(OperationsCatalog.knownNames());
        assertEquals("all registered operation kinds meet the same whole-tool gate", 24, operations.size()); //$NON-NLS-1$
        for (String operation : operations)
        {
            String where = TOOL + " " + operation; //$NON-NLS-1$
            ToolRoadOutcome outcome = road.call(TOOL, Map.of("operation", operation), "test"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(where, outcome.refused());
            String refusal = outcome.refusal();
            assertNotNull(where, refusal);
            assertTrue(refusal, refusal.contains(TOOL));
            assertTrue(refusal, refusal.contains("disabled")); //$NON-NLS-1$
            assertNotEquals("the refusal is the gate's, not the registry's", unregistered, refusal); //$NON-NLS-1$
        }
    }

    /**
     * The tool is among the enabled ones and callable, and its write capability is declared - the
     * state every selection that keeps the surface has to leave behind.
     *
     * @param what the selection under test, for the failure message
     */
    private static void theToolAndItsWritesAreAvailable(String what)
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        assertTrue(what, catalog.isToolEnabled(TOOL));
        assertTrue(what, listedNames().contains(TOOL));
        assertFalse(what, catalog.isDisabledByPreset(TOOL));
        assertFalse(what, catalog.isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
        assertEquals(what, Boolean.TRUE, catalog.externalWritesDeclared(TOOL));
    }

    /** The names of the tools the catalogue offers for the surface. */
    private static Collection<String> listedNames()
    {
        List<String> names = new ArrayList<>();
        for (IMcpTool tool : McpToolCatalog.getInstance().getEnabledTools())
        {
            names.add(tool.getName());
        }
        return names;
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
