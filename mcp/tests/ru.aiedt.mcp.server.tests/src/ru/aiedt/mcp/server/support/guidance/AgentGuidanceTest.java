/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance.ArgumentPlace;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance.Boundary;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance.Entry;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance.Mode;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance.Route;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance.State;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.ClientSessionStarter;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.DatabaseUpdater;
import ru.aiedt.mcp.server.toolkit.ops.DebugSessionStarter;
import ru.aiedt.mcp.server.toolkit.ops.EventLogTool;
import ru.aiedt.mcp.server.toolkit.ops.ExternalObjectWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.LaunchDebuggerTool;
import ru.aiedt.mcp.server.toolkit.ops.QueryValidator;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * Holds the route document to the registry it claims to describe.
 * <p>
 * The census is deliberately made of separate sets rather than one count: a route with two entry
 * points is not one route, an update step is not a target, and a mode's argument is published by a
 * schema rather than by the mode object. Counting them together would let one disappear behind
 * another. E, S, M, P and X are checked at 11, 2, 3, 7 and 2, the numbers section 6 of the plan
 * fixed.
 * </p>
 * <p>
 * Availability is checked by changing the real {@code ToolSettingsStore}, because that is what the
 * server reads. The marks are then computed twice under the same preset, which is also the property
 * the document promises: a read reflects the setting in force, not a decision frozen when the class
 * loaded.
 * </p>
 */
public class AgentGuidanceTest
{
    private McpToolCatalog catalog;

    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    private String unlistedBefore;

    @Before
    public void aRegistryAndAStoreToChange()
    {
        catalog = McpToolCatalog.getInstance();
        catalog.clear();
        register(new InfobaseAdminFacadeTool());
        register(new ClientSessionStarter());
        register(new LaunchDebuggerTool());
        register(new DebugSessionStarter());
        register(new ExternalObjectWorkshopTool());
        register(new QueryValidator());
        register(new EventLogTool());
        register(new ConfigIoFacadeTool());
        register(new DatabaseUpdater());

        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
    }

    @After
    public void theRegistryAndTheStoreGoBack()
    {
        catalog.clear();
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_UNLISTED_TOOLS, unlistedBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    private void register(IMcpTool tool)
    {
        catalog.register(tool);
    }

    /** Each entry point is a real registered tool or a real operation of a routed facade. */
    @Test
    public void everyEntryPointOfEverySetIsDeclared()
    {
        assertEquals("E set", 11, AgentGuidance.targetEntries().size()); //$NON-NLS-1$
        assertEquals("S set", 2, AgentGuidance.updateStepEntries().size()); //$NON-NLS-1$
        assertEquals("M set", 3, AgentGuidance.modes().size()); //$NON-NLS-1$
        assertEquals("P set", 7, AgentGuidance.argumentPlaces().size()); //$NON-NLS-1$
        assertEquals("X set", 2, AgentGuidance.boundaries().size()); //$NON-NLS-1$

        assertUniqueIds();
        for (Entry entry : AgentGuidance.targetEntries())
        {
            assertEntryExists(entry);
        }
        for (Entry entry : AgentGuidance.updateStepEntries())
        {
            assertEntryExists(entry);
        }
    }

    private void assertEntryExists(Entry entry)
    {
        IMcpTool tool = catalog.getTool(entry.tool());
        assertNotNull(entry.id() + " names a tool nothing registered: " + entry.tool(), tool); //$NON-NLS-1$
        if (entry.operation() == null)
        {
            return;
        }
        Map<String, String> helpParams = new HashMap<>();
        helpParams.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        helpParams.put("action", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String help = tool.execute(helpParams);
        assertTrue(entry.id() + " names operation " + entry.operation() //$NON-NLS-1$
            + " that the facade catalog does not list: " + help, help.contains(entry.operation())); //$NON-NLS-1$
    }

    private static void assertUniqueIds()
    {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Entry entry : AgentGuidance.targetEntries())
        {
            if (!seen.add(entry.id()))
            {
                duplicates.add(entry.id());
            }
        }
        for (Entry entry : AgentGuidance.updateStepEntries())
        {
            if (!seen.add(entry.id()))
            {
                duplicates.add(entry.id());
            }
        }
        for (Mode mode : AgentGuidance.modes())
        {
            if (!seen.add(mode.id()))
            {
                duplicates.add(mode.id());
            }
        }
        for (ArgumentPlace place : AgentGuidance.argumentPlaces())
        {
            if (!seen.add(place.id()))
            {
                duplicates.add(place.id());
            }
        }
        for (Boundary boundary : AgentGuidance.boundaries())
        {
            if (!seen.add(boundary.id()))
            {
                duplicates.add(boundary.id());
            }
        }
        assertTrue("identifiers must stay unique: " + duplicates, duplicates.isEmpty()); //$NON-NLS-1$
    }

    /** Every mode's argument is advertised by every schema the mode claims publishes it. */
    @Test
    public void everyModeArgumentIsPublishedByItsSchemas()
    {
        for (ArgumentPlace place : AgentGuidance.argumentPlaces())
        {
            assertTrue(place.id() + " claims " + place.tool() + " declares " + place.argument() //$NON-NLS-1$ //$NON-NLS-2$
                + ", but its schema does not", place.published()); //$NON-NLS-1$
        }
    }

    /** The document names the installed version, the active preset and the moment of reading. */
    @Test
    public void theDocumentNamesTheVersionThePresetAndTheReadingMoment()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);

        String document = AgentGuidance.document();

        assertTrue(document, document.contains("Plugin version: " + McpServerMeta.PLUGIN_VERSION)); //$NON-NLS-1$
        assertTrue(document, document.contains("Tool preset: " //$NON-NLS-1$
            + ToolSettingsStore.getInstance().getActivePreset().getDisplayName()));
        assertTrue(document, document.contains("Availability reflects the moment of reading")); //$NON-NLS-1$
        for (Route route : AgentGuidance.routes())
        {
            assertTrue(route.id() + " is missing from the document", //$NON-NLS-1$
                document.contains(route.id() + " " + route.task())); //$NON-NLS-1$
        }
        assertTrue(document, document.contains(AgentGuidance.NOT_SERVED));
    }

    /**
     * Canonical disables nothing: the facade is the visible way in, the standalone is hidden but
     * callable, and no route is off.
     */
    @Test
    public void canonicalShowsTheFacadeAndHidesTheStandalone()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);

        assertEquals("available via infobase_admin operation=start_client", //$NON-NLS-1$
            AgentGuidance.entry("E1").label()); //$NON-NLS-1$
        assertEquals(State.HIDDEN, AgentGuidance.entry("E2").state()); //$NON-NLS-1$
        assertTrue(AgentGuidance.entry("E2").label(), //$NON-NLS-1$
            AgentGuidance.entry("E2").label().startsWith("hidden, callable via start_client")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a hidden name is still callable", AgentGuidance.entry("E2").callable()); //$NON-NLS-1$ //$NON-NLS-2$

        for (Route route : AgentGuidance.routes())
        {
            assertFalse(route.id() + " must not be off under Canonical: " + route.availability(), //$NON-NLS-1$
                route.availability().contains("off")); //$NON-NLS-1$
            assertTrue(route.id() + " must be available under Canonical", route.available()); //$NON-NLS-1$
        }
    }

    /**
     * Debug &amp; Test keeps launching on and closes the routes that need a write, while the mode
     * that turns dump generation on is refused by its write door and still warns about the project.
     */
    @Test
    public void debugAndTestMovesEachRouteToItsOpenEntryPoint()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.DEBUG_AND_TEST);

        assertEquals(State.OFF, AgentGuidance.entry("E1").state()); //$NON-NLS-1$
        assertEquals(State.AVAILABLE, AgentGuidance.entry("E2").state()); //$NON-NLS-1$
        assertTrue("R1 is open through the standalone: " + AgentGuidance.route("R1").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R1").availability().contains("available via start_client")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R1").available()); //$NON-NLS-1$

        Mode update = mode("M1"); //$NON-NLS-1$
        assertEquals(State.OFF, update.state());
        assertTrue("the update mode names what closed it: " + update.label(), //$NON-NLS-1$
            update.label().contains("update_database")); //$NON-NLS-1$

        assertFalse("R3's import is a write", AgentGuidance.route("R3").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("R3 names its door: " + AgentGuidance.entry("E5").label(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.entry("E5").label().contains("external_object_workshop_writes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("R3 hands over to an open R2: " + AgentGuidance.route("R3").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R3").availability().contains("next step: R2 available")); //$NON-NLS-1$ //$NON-NLS-2$

        Mode dump = mode("M3"); //$NON-NLS-1$
        assertEquals(State.OFF, dump.state());
        assertTrue("the dump mode names its door: " + dump.label(), //$NON-NLS-1$
            dump.label().contains("launch_debugger_writes")); //$NON-NLS-1$
        assertTrue("and it still says what it would change: " + dump.label(), //$NON-NLS-1$
            dump.label().contains("changes the project")); //$NON-NLS-1$

        assertTrue("the file event log is open through the standalone: " //$NON-NLS-1$
            + AgentGuidance.route("R5").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R5").availability().contains("available via read_event_log")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("R6 cannot export without the update", AgentGuidance.route("R6").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(AgentGuidance.route("R6").target(".cf").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(AgentGuidance.route("R6").target(".cfe").available()); //$NON-NLS-1$ //$NON-NLS-2$
        String export = AgentGuidance.route("R6").availability();
        assertTrue("the .cf entry names its facade: " + export, export.contains("config_io")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the step names the update: " + export, export.contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Read-only leaves the query check open and closes every route that writes or launches. */
    @Test
    public void readOnlyClosesEveryRouteThatWritesOrLaunches()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.READ_ONLY);

        assertFalse(AgentGuidance.route("R1").available()); //$NON-NLS-1$
        assertFalse(AgentGuidance.route("R2").available()); //$NON-NLS-1$
        assertFalse(AgentGuidance.route("R3").available()); //$NON-NLS-1$
        assertFalse(AgentGuidance.route("R6").available()); //$NON-NLS-1$
        assertTrue("a query check writes nothing: " + AgentGuidance.route("R4").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R4").available()); //$NON-NLS-1$

        assertFalse("the event log belongs to the applications group", //$NON-NLS-1$
            AgentGuidance.route("R5").target("event log").available()); //$NON-NLS-1$ //$NON-NLS-2$
        Boundary liveData = AgentGuidance.boundaries().get(0);
        assertEquals("X1", liveData.id()); //$NON-NLS-1$
        assertEquals(AgentGuidance.NOT_SERVED, liveData.state());
        assertTrue(liveData.label(), liveData.label().contains(AgentGuidance.NOT_SERVED));
    }

    /** A custom preset that disables the launch key closes R2 through both entry points. */
    @Test
    public void aCustomPresetWithoutTheLaunchKeyClosesTheDebugRoute()
    {
        disable("debug_launch"); //$NON-NLS-1$

        assertFalse(AgentGuidance.route("R2").available()); //$NON-NLS-1$
        assertTrue("the facade action is gated by the same key: " //$NON-NLS-1$
            + AgentGuidance.route("R2").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R2").availability().contains("debug_launch disabled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("R1 is untouched by that key", AgentGuidance.route("R1").available()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Without the infobase facade, R6 still runs through the standalone update and the exports. */
    @Test
    public void aCustomPresetWithoutTheInfobaseFacadeKeepsTheExportRoute()
    {
        disable("infobase_admin"); //$NON-NLS-1$

        assertTrue("the standalone update step keeps R6 open: " //$NON-NLS-1$
            + AgentGuidance.route("R6").availability(), AgentGuidance.route("R6").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(State.OFF, AgentGuidance.entry("S1").state()); //$NON-NLS-1$
        assertEquals(State.AVAILABLE, AgentGuidance.entry("S2").state()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R6").target(".cf").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R6").target(".cfe").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("R1 is open through the standalone: " //$NON-NLS-1$
            + AgentGuidance.route("R1").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R1").availability().contains("available via start_client")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Disabling both update entry points closes the export route even when the exports stay on. */
    @Test
    public void aCustomPresetWithoutAnyUpdateClosesTheExportRoute()
    {
        disable("infobase_admin", "update_database"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(AgentGuidance.route("R6").available()); //$NON-NLS-1$
        assertTrue("the required step is what closed it: " //$NON-NLS-1$
            + AgentGuidance.route("R6").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R6").availability().contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the guarded export itself is still on", //$NON-NLS-1$
            AgentGuidance.route("R6").target(".cf").available()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A name the export operations do not carry cannot switch them off one by one. */
    @Test
    public void aCustomPresetWithoutAnUnrelatedExportNameLeavesTheExportRouteOpen()
    {
        disable("export_extension"); //$NON-NLS-1$

        assertTrue(AgentGuidance.route("R6").available()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R6").target(".cf").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R6").target(".cfe").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unrelated name must not be read as a gate", //$NON-NLS-1$
            AgentGuidance.entry("E11").label().contains("export_extension")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The route document states the order in which an export becomes current. */
    @Test
    public void theExportRouteStatesUpdateOutcomeThenExport()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        String document = AgentGuidance.document();

        assertTrue("a real update is dryRun=false: " + document, document.contains("dryRun=false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a Pending result is collected by runKey: " + document, //$NON-NLS-1$
            document.contains("runKey")); //$NON-NLS-1$
        assertTrue("the export follows the successful outcome: " + document, //$NON-NLS-1$
            document.contains("only then the export")); //$NON-NLS-1$
        assertTrue("and nothing claims an export before it: " + document, //$NON-NLS-1$
            document.contains("a successful outcome")); //$NON-NLS-1$
    }

    /** The brief is short enough for a handshake and names the resource without a preset result. */
    @Test
    public void theBriefNamesTheGuidanceResourceWithinItsBudget()
    {
        assertTrue("the brief has a budget of 1200 characters: " + AgentGuidance.BRIEF.length(), //$NON-NLS-1$
            AgentGuidance.BRIEF.length() <= 1200);
        assertTrue(AgentGuidance.BRIEF, AgentGuidance.BRIEF.contains(McpServerMeta.RESOURCE_GUIDANCE));
        assertFalse("the brief is cached with discovery and must not carry a preset result: " //$NON-NLS-1$
            + AgentGuidance.BRIEF, AgentGuidance.BRIEF.contains("Tool preset:")); //$NON-NLS-1$
    }

    private static Mode mode(String id)
    {
        for (Mode mode : AgentGuidance.modes())
        {
            if (mode.id().equals(id))
            {
                return mode;
            }
        }
        fail("no mode " + id); //$NON-NLS-1$
        return null;
    }

    /** Records exactly the given names as disabled, with nothing hidden from the list. */
    private static void disable(String... names)
    {
        ToolSettingsStore store = ToolSettingsStore.getInstance();
        store.setDisabledTools(names.length == 0 ? Set.of() : Set.of(names));
        store.setUnlistedTools(Set.of());
    }
}
