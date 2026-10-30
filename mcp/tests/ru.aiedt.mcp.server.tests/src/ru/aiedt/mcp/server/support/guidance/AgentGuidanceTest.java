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

import com.google.gson.JsonParser;

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
import ru.aiedt.mcp.server.toolkit.ops.EditMetadataTool;
import ru.aiedt.mcp.server.toolkit.ops.EventLogTool;
import ru.aiedt.mcp.server.toolkit.ops.ExternalObjectWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.LaunchDebuggerTool;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceReader;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;
import ru.aiedt.mcp.server.toolkit.ops.QueryValidator;
import ru.aiedt.mcp.server.wire.McpRequestRouter;
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

    private OrdinaryFormsProbe ordinaryForms;

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
        register(new EditMetadataTool());
        register(new ModuleSourceReader());
        register(new ModuleSourceWriter());

        // The ordinary-forms tool lives in an optional bundle. The probe carries the same
        // contract that bundle declares: it writes, and it gates every write on its own door.
        ordinaryForms = new OrdinaryFormsProbe();
        catalog.registerExternal(ordinaryForms, true);

        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
    }

    @After
    public void theRegistryAndTheStoreGoBack()
    {
        // An external tool survives clear(), so it leaves through its own door first.
        catalog.unregisterExternal(ordinaryForms);
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
        assertEquals("E set", 20, AgentGuidance.targetEntries().size()); //$NON-NLS-1$
        assertEquals("S set", 2, AgentGuidance.updateStepEntries().size()); //$NON-NLS-1$
        assertEquals("M set", 3, AgentGuidance.modes().size()); //$NON-NLS-1$
        assertEquals("P set", 7, AgentGuidance.argumentPlaces().size()); //$NON-NLS-1$
        assertEquals("X set", 2, AgentGuidance.boundaries().size()); //$NON-NLS-1$
        assertEquals("R set", 9, AgentGuidance.routes().size()); //$NON-NLS-1$
        for (String id : List.of("R7", "R8", "R9")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertNotNull("the route " + id + " is not declared", AgentGuidance.route(id)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String id : List.of("E12", "E13", "E14", "E15", "E16", "E17", "E18", "E19", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "E20")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNotNull("the entry point " + id + " is not declared", AgentGuidance.entry(id)); //$NON-NLS-1$ //$NON-NLS-2$
        }

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

    /**
     * The call the document prints is a call its facade answers.
     * <p>
     * The routed facades do not agree on the argument that selects the operation: the debugger
     * facade reads {@code action} and refuses a call that carries none, while the other three read
     * {@code operation}. A document that prints the wrong name publishes a call refused before the
     * operation runs, so the printed argument is probed against the facade itself rather than
     * trusted.
     * </p>
     */
    @Test
    public void everyPublishedCallNamesTheFacadesOwnDispatchArgument()
    {
        assertEquals("launch_debugger action=launch", AgentGuidance.entry("E3").callName()); //$NON-NLS-1$ //$NON-NLS-2$

        for (Entry entry : AgentGuidance.targetEntries())
        {
            if (entry.operation() == null)
            {
                continue;
            }
            String published = entry.callName();
            String call = entry.tool() + " "; //$NON-NLS-1$
            assertTrue(entry.id() + " prints " + published, published.startsWith(call)); //$NON-NLS-1$
            String assignment = published.substring(call.length());
            int equals = assignment.indexOf('=');
            assertTrue(entry.id() + " prints no argument in " + published, equals > 0); //$NON-NLS-1$
            String dispatch = assignment.substring(0, equals);
            assertEquals(entry.id() + " prints the wrong operation in " + published, //$NON-NLS-1$
                entry.operation(), assignment.substring(equals + 1));

            IMcpTool tool = catalog.getTool(entry.tool());
            assertNotNull(entry.id() + " names a tool nothing registered: " + entry.tool(), tool); //$NON-NLS-1$
            Map<String, String> probe = new HashMap<>();
            probe.put(dispatch, "help"); //$NON-NLS-1$ //$NON-NLS-2$
            String answer = tool.execute(probe);
            assertFalse(entry.id() + " publishes " + published + ", which " + entry.tool() //$NON-NLS-1$
                + " does not dispatch on: " + answer, answer.contains("is required")); //$NON-NLS-1$ //$NON-NLS-2$
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
     * Without the optional bundle the ordinary operations are off, while the two global module
     * tools keep their name - the module address, not the tool name, is what the bundle resolves.
     */
    @Test
    public void ordinaryRoutesWithoutTheOptionalToolAreNotAvailable()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        catalog.unregisterExternal(ordinaryForms);
        assertFalse("this scenario has both routed doors open", //$NON-NLS-1$
            catalog.isDisabledByPreset("edit_metadata_writes")); //$NON-NLS-1$
        assertFalse("this scenario has both routed doors open", //$NON-NLS-1$
            catalog.isDisabledByPreset("ordinary_forms_writes")); //$NON-NLS-1$

        for (String id : List.of("E12", "E13", "E14", "E15", "E18", "E19", "E20")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertEquals(id + " has to be off without ordinary_forms", //$NON-NLS-1$
                State.OFF, AgentGuidance.entry(id).state());
        }
        assertEquals("off: ordinary_forms is not registered", AgentGuidance.entry("E12").label()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("off: ordinary_forms is not registered", AgentGuidance.entry("E19").label()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the global module reader keeps its name: " + AgentGuidance.entry("E16").label(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.entry("E16").callable());
        assertTrue("the global module writer keeps its name: " + AgentGuidance.entry("E17").label(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.entry("E17").callable());

        assertFalse("R7 needs the optional tool", AgentGuidance.route("R7").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the read target is not available through a module name", //$NON-NLS-1$
            AgentGuidance.route("R8").target("read").available()); //$NON-NLS-1$
        assertFalse("R8 needs the optional tool for what it reads and edits", //$NON-NLS-1$
            AgentGuidance.route("R8").available());
        assertTrue("R9 is the existing update and does not depend on the bundle", //$NON-NLS-1$
            AgentGuidance.route("R9").available());

        String document = AgentGuidance.document();
        assertTrue(document, document.contains("off: ordinary_forms is not registered")); //$NON-NLS-1$
        assertFalse(document, document.contains("available via ordinary_forms")); //$NON-NLS-1$
        assertTrue("the module address condition is named: " + document, //$NON-NLS-1$
            document.contains("ordinaryFormsRegistered=false")); //$NON-NLS-1$
        assertTrue("the module entry is a name only: " + AgentGuidance.route("R8").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R8").availability().contains("tool name only (read_module_source)")); //$NON-NLS-1$
    }

    /** The registered optional tool makes its own operations available under Canonical. */
    @Test
    public void ordinaryRoutesAreAvailableWhenTheToolIsRegisteredAndEnabled()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);

        assertEquals("available via ordinary_forms operation=create_form", //$NON-NLS-1$
            AgentGuidance.entry("E12").label()); //$NON-NLS-1$
        assertEquals("available via ordinary_forms operation=layout", //$NON-NLS-1$
            AgentGuidance.entry("E13").label()); //$NON-NLS-1$
        assertEquals("available via ordinary_forms operation=inspect_layout", //$NON-NLS-1$
            AgentGuidance.entry("E14").label()); //$NON-NLS-1$
        assertEquals("available via ordinary_forms operation=set_property", //$NON-NLS-1$
            AgentGuidance.entry("E15").label()); //$NON-NLS-1$
        assertEquals("available via ordinary_forms operation=import_from_infobase", //$NON-NLS-1$
            AgentGuidance.entry("E18").label()); //$NON-NLS-1$
        assertEquals("available via ordinary_forms operation=audit", //$NON-NLS-1$
            AgentGuidance.entry("E20").label()); //$NON-NLS-1$
        assertEquals("available via edit_metadata operation=create_form", //$NON-NLS-1$
            AgentGuidance.entry("E19").label()); //$NON-NLS-1$

        assertTrue(AgentGuidance.route("R7").available()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R8").available()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R9").available()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R8").target("read").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R8").target("edit").available()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Hidden is callable: the route stays open and the label says so. */
    @Test
    public void hiddenOrdinaryFormsStaysCallableOnItsRoutes()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        ToolSettingsStore.getInstance().setUnlistedTools(Set.of("ordinary_forms")); //$NON-NLS-1$

        assertEquals(State.HIDDEN, AgentGuidance.entry("E12").state()); //$NON-NLS-1$
        assertTrue("hidden is not off: " + AgentGuidance.entry("E12").label(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.entry("E12").label()
                .startsWith("hidden, callable via ordinary_forms operation=create_form")); //$NON-NLS-1$
        assertTrue(AgentGuidance.entry("E12").callable()); //$NON-NLS-1$
        assertTrue("the routed entry is still listed", AgentGuidance.entry("E19").callable()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R7").available()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R8").available()); //$NON-NLS-1$
        assertFalse("the ordinary tool is absent from tools/list", //$NON-NLS-1$
            listed("ordinary_forms")); //$NON-NLS-1$
    }

    /** A disabled tool is off, and so is the routed branch that would delegate to it. */
    @Test
    public void disabledOrdinaryFormsClosesDirectAndRoutedEntries()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        ToolSettingsStore.getInstance().setDisabledTools(Set.of("ordinary_forms")); //$NON-NLS-1$

        for (String id : List.of("E12", "E13", "E14", "E15", "E18", "E20")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertEquals(id + " is off with the tool", State.OFF, AgentGuidance.entry(id).state()); //$NON-NLS-1$
            assertTrue(AgentGuidance.entry(id).label(), //$NON-NLS-1$
                AgentGuidance.entry(id).label().contains("off by preset ordinary_forms")); //$NON-NLS-1$
        }
        assertEquals(State.OFF, AgentGuidance.entry("E19").state()); //$NON-NLS-1$
        assertTrue("the routed branch names the optional tool: " + AgentGuidance.entry("E19").label(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.entry("E19").label().contains("ordinary_forms")); //$NON-NLS-1$
        assertFalse(AgentGuidance.route("R7").available()); //$NON-NLS-1$
        assertFalse(AgentGuidance.route("R8").available()); //$NON-NLS-1$
        assertTrue("the module reader does not depend on that name", //$NON-NLS-1$
            AgentGuidance.entry("E16").callable()); //$NON-NLS-1$
    }

    /**
     * The mixed-tool contract: with its write door closed the tool keeps its reads, while every
     * write - a dryRun preview included - is refused, and the routed branch shares the door.
     */
    @Test
    public void closedOrdinaryWriteDoorKeepsReadsAndRefusesEveryWrite()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        ToolSettingsStore.getInstance().setDisabledTools(Set.of("ordinary_forms_writes")); //$NON-NLS-1$

        assertTrue("the mixed tool stays callable for its reads", //$NON-NLS-1$
            catalog.isToolEnabled("ordinary_forms")); //$NON-NLS-1$
        assertTrue(catalog.isDisabledByPreset("ordinary_forms_writes")); //$NON-NLS-1$
        for (String id : List.of("E12", "E15", "E18")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertEquals(id + " writes", State.OFF, AgentGuidance.entry(id).state()); //$NON-NLS-1$
            assertEquals("off: ordinary_forms_writes disabled", AgentGuidance.entry(id).label()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals("the routed write shares the tool's door", //$NON-NLS-1$
            "off: ordinary_forms_writes disabled", AgentGuidance.entry("E19").label()); //$NON-NLS-1$ //$NON-NLS-2$
        for (String id : List.of("E13", "E14", "E20", "E16")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertEquals(id + " reads", State.AVAILABLE, AgentGuidance.entry(id).state()); //$NON-NLS-1$
        }
        assertFalse(AgentGuidance.route("R7").available()); //$NON-NLS-1$
        assertTrue("reads stay: " + AgentGuidance.route("R8").availability(), //$NON-NLS-1$ //$NON-NLS-2$
            AgentGuidance.route("R8").available());
        assertTrue(AgentGuidance.route("R8").target("read").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(AgentGuidance.route("R8").target("edit").available()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A closed facade door does not reach the direct ordinary creation; the routed branch fails. */
    @Test
    public void closedFacadeWriteDoorLeavesDirectOrdinaryCreationOpen()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        ToolSettingsStore.getInstance().setDisabledTools(Set.of("edit_metadata_writes")); //$NON-NLS-1$

        assertEquals(State.AVAILABLE, AgentGuidance.entry("E12").state()); //$NON-NLS-1$
        assertEquals(State.AVAILABLE, AgentGuidance.entry("E18").state()); //$NON-NLS-1$
        assertEquals(State.AVAILABLE, AgentGuidance.entry("E15").state()); //$NON-NLS-1$
        assertEquals(State.OFF, AgentGuidance.entry("E19").state()); //$NON-NLS-1$
        assertEquals("off: edit_metadata_writes disabled", AgentGuidance.entry("E19").label()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the direct creation is untouched", AgentGuidance.route("R7").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R8").target("edit").available()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The update is delivery, not a condition of reading or editing: without update_database the
     * read and edit targets stay open and only R9 closes.
     */
    @Test
    public void closedUpdateDatabaseLeavesReadsAndEditsAndClosesDelivery()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        ToolSettingsStore.getInstance().setDisabledTools(Set.of("update_database")); //$NON-NLS-1$

        for (String id : List.of("E13", "E14", "E20", "E15")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertEquals(id + " does not need update_database", State.AVAILABLE, AgentGuidance.entry(id).state()); //$NON-NLS-1$
        }
        assertTrue(AgentGuidance.route("R8").available()); //$NON-NLS-1$
        assertTrue(AgentGuidance.route("R8").target("read").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(AgentGuidance.route("R8").target("edit").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("delivery needs a real update", AgentGuidance.route("R9").available()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the read and edit availability does not name the update", //$NON-NLS-1$
            AgentGuidance.route("R8").availability().contains("update_database")); //$NON-NLS-1$
    }

    /** The resource text names the route, the addresses, the two doors and the provider condition. */
    @Test
    public void theDocumentNamesTheOrdinaryRoutesAndTheirConditions()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        String document = AgentGuidance.document();

        for (String route : List.of("R7", "R8", "R9")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(route + " is missing from the document", //$NON-NLS-1$
                document.contains(route + " " + AgentGuidance.route(route).task())); //$NON-NLS-1$
        }
        for (String fragment : List.of("Forms/<Form>/Module.bsl", //$NON-NLS-1$
            "relative to src/", //$NON-NLS-1$
            "projectName is a separate argument", //$NON-NLS-1$
            "import_from_infobase", //$NON-NLS-1$
            "edit_metadata_writes", //$NON-NLS-1$
            "ordinary_forms_writes", //$NON-NLS-1$
            "ordinaryFormsRegistered=false", //$NON-NLS-1$
            "handlerChanges=warn|refuse", //$NON-NLS-1$
            "searchReplace", //$NON-NLS-1$
            "replaceMethod", //$NON-NLS-1$
            "confirmFullReplace", //$NON-NLS-1$
            "the form or module entry the write replaces", //$NON-NLS-1$
            "unsavedInEditor=true", //$NON-NLS-1$
            "No Form.oform", //$NON-NLS-1$
            "runKey", //$NON-NLS-1$
            "protectData", //$NON-NLS-1$
            "acceptDataLoss", //$NON-NLS-1$
            "delivery statement", //$NON-NLS-1$
            "registered ordinary_forms tool is not proof that its module provider is registered", //$NON-NLS-1$
            "next: R8", //$NON-NLS-1$
            "next: R9")) //$NON-NLS-1$
        {
            assertTrue("missing from the guidance document: " + fragment + "\n" + document, //$NON-NLS-1$ //$NON-NLS-2$
                document.contains(fragment));
        }
    }

    /** The same states are what resources/read hands a client through the router. */
    @Test
    public void theResourceReadThroughTheRouterCarriesTheOrdinaryRoutes()
    {
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{\"uri\":\"" //$NON-NLS-1$
            + McpServerMeta.RESOURCE_GUIDANCE + "\"}}"; //$NON-NLS-1$
        String answer = new McpRequestRouter().processRequest(body);
        String text = JsonParser.parseString(answer).getAsJsonObject().getAsJsonObject("result") //$NON-NLS-1$
            .getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(text, text.contains("R8 Read or edit an existing ordinary form in the project")); //$NON-NLS-1$
        assertTrue(text, text.contains("available via ordinary_forms operation=layout")); //$NON-NLS-1$
        assertTrue(text, text.contains("available via edit_metadata operation=create_form")); //$NON-NLS-1$
    }

    private static boolean listed(String name)
    {
        for (IMcpTool tool : McpToolCatalog.getInstance().getEnabledTools())
        {
            if (tool.getName().equals(name))
            {
                return true;
            }
        }
        return false;
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

    /**
     * A stand-in for the optional ordinary-forms tool. It carries the two facts the real bundle
     * declares - it writes and it gates those writes on {@code ordinary_forms_writes} - and its
     * answer names the operations the guidance registry describes, so the census can ask it for
     * its catalogue exactly as it asks a facade.
     */
    private static final class OrdinaryFormsProbe implements IMcpTool
    {
        @Override
        public String getName()
        {
            return "ordinary_forms"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "a stand-in for the optional ordinary-forms tool"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public List<String> getGatedWriteNames()
        {
            return List.of("ordinary_forms_writes"); //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "create_form import_from_infobase layout inspect_layout audit set_property"; //$NON-NLS-1$
        }
    }
}
