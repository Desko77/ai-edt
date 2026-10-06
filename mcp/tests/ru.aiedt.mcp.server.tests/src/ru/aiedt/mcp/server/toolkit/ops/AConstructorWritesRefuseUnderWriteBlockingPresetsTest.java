/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Every write-blocking preset refuses each constructor facade's writes by its door, before the
 * workspace is read, and the reads and the dryRun previews of the same facades keep working.
 * <p>
 * Code Review keeps the constructors group on - reading what a constructor would produce is part
 * of a review - so the door each facade asks about before its first write is the only thing
 * standing between that preset's promise and a written project. Read-only and Debug &amp; Test
 * switch the whole group off at the router, but a facade reached as a Java call never passes the
 * router, so its own gate has to refuse there too. The calls below name a project that does not
 * exist: a refusal naming the door proves the gate fired first, and any other answer proves the
 * call got past it.
 * </p>
 */
public class AConstructorWritesRefuseUnderWriteBlockingPresetsTest
{
    private IPreferenceStore store;

    private String presetBefore;

    /**
     * Takes the live preference store and remembers which preset it held.
     */
    @Before
    public void aStoreToHoldThePreset()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
    }

    /**
     * Puts the preset back.
     */
    @After
    public void thePresetGoesBack()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /** One call to a facade with the given arguments, the facade built fresh for the call. */
    private static String call(Supplier<IMcpTool> tool, String... arguments)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return tool.get().execute(params);
    }

    /**
     * Whether the answer carries the gate's own wording.
     * <p>
     * A JSON-typed facade hands the message over as the {@code error} field, and the wire escaping
     * turns every apostrophe of it into {@code '}, so the raw string is not contained in the
     * serialized answer. The field is compared exactly instead, the way the cluster-door test
     * compares its facade's error; a markdown answer (edit_form) is checked by containment,
     * because that is what it is.
     * </p>
     *
     * @param answer the facade's answer
     * @param door the door the gate was expected to name
     * @return true when the refusal is the gate's own
     */
    private static boolean refusesByTheDoor(String answer, String door)
    {
        String message = ToolGate.writeDoorMessage(door);
        try
        {
            com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(answer)
                .getAsJsonObject();
            return parsed.has("error") //$NON-NLS-1$
                && parsed.get("error").getAsString().startsWith(message); //$NON-NLS-1$
        }
        catch (Exception notJson)
        {
            return answer.contains(message);
        }
    }

    /** One row of the matrices below: the facade's name, its door, and a call into it. */
    private static final class Row
    {
        final String facade;

        final Supplier<IMcpTool> tool;

        final String door;

        final String[] arguments;

        Row(String facade, Supplier<IMcpTool> tool, String door, String... arguments)
        {
            this.facade = facade;
            this.tool = tool;
            this.door = door;
            this.arguments = arguments;
        }

        String call()
        {
            return AConstructorWritesRefuseUnderWriteBlockingPresetsTest.call(tool, arguments);
        }
    }

    /**
     * One write per constructor facade, each named for a project that does not exist.
     *
     * @return the rows
     */
    private static List<Row> writes()
    {
        return Arrays.asList(
            new Row("edit_form", EditFormTool::new, "edit_form_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "add_field", "projectName", "NoProject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.None.Form.ItemForm.Form"), //$NON-NLS-1$
            new Row("edit_metadata", EditMetadataTool::new, "edit_metadata_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "create_object", "projectName", "NoProject"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Row("dcs_workshop", DcsWorkshopTool::new, "dcs_workshop_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "add_field", "projectName", "NoProject"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Row("mxl_workshop", MxlWorkshopTool::new, "mxl_workshop_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "set_cell", "projectName", "NoProject"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Row("xdto_workshop", XdtoWorkshopTool::new, "xdto_workshop_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "add_object_type", "projectName", "NoProject"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Row("external_object_workshop", ExternalObjectWorkshopTool::new, //$NON-NLS-1$
                "external_object_workshop_writes", //$NON-NLS-1$
                "operation", "create", "name", "Tool", "kind", "ExternalDataProcessor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Row("external_data_source_workshop", ExternalDataSourceWorkshopTool::new, //$NON-NLS-1$
                "external_data_source_workshop_writes", //$NON-NLS-1$
                "operation", "add_table", "projectName", "NoProject", "name", "T")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Under each write-blocking preset, one write of every constructor facade answers the gate's
     * own wording - refused before the workspace is read, since the project named does not exist
     * and no other refusal had the chance to fire.
     */
    @Test
    public void everyWriteIsRefusedByItsDoorUnderEveryWriteBlockingPreset()
    {
        List<String> leaks = new java.util.ArrayList<>();
        for (String preset : Arrays.asList(ToolProfile.READ_ONLY.name(),
            ToolProfile.DEBUG_AND_TEST.name(), ToolProfile.CODE_REVIEW.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            for (Row row : writes())
            {
                String answer = row.call();
                if (!refusesByTheDoor(answer, row.door))
                {
                    leaks.add(preset + " let " + row.facade + " write past its door: " + answer); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        assertTrue("these presets let a constructor write past its door: " + leaks, leaks.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Under the preset that keeps the constructors group on, the reading operations answer
     * something other than the door - they got past the gate, and failed on the project that is
     * not there, which is the read's own business.
     */
    @Test
    public void theReadsOfTheSameFacadesGetPastTheDoor()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CODE_REVIEW.name());
        List<Row> reads = Arrays.asList(
            new Row("edit_metadata help", EditMetadataTool::new, "edit_metadata_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "help"), //$NON-NLS-1$
            new Row("dcs_workshop help", DcsWorkshopTool::new, "dcs_workshop_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "help"), //$NON-NLS-1$
            new Row("mxl_workshop read_template", MxlWorkshopTool::new, "mxl_workshop_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "read_template", "projectName", "NoProject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "ownerFqn", "Catalog.None"), //$NON-NLS-1$
            new Row("xdto_workshop read", XdtoWorkshopTool::new, "xdto_workshop_writes", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "read", "projectName", "NoProject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "ownerFqn", "XDTOPackage.None"), //$NON-NLS-1$
            new Row("external_object_workshop help", ExternalObjectWorkshopTool::new, //$NON-NLS-1$
                "external_object_workshop_writes", "operation", "help"), //$NON-NLS-1$ //$NON-NLS-2$
            new Row("external_data_source_workshop list", ExternalDataSourceWorkshopTool::new, //$NON-NLS-1$
                "external_data_source_workshop_writes", //$NON-NLS-1$
                "operation", "list", "projectName", "NoProject", "dataSourceName", "None")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        List<String> held = new java.util.ArrayList<>();
        for (Row row : reads)
        {
            String answer = row.call();
            if (refusesByTheDoor(answer, row.door))
            {
                held.add(row.facade + " was refused by the write door: " + answer); //$NON-NLS-1$
            }
        }
        assertTrue("these reads have no business asking the write door: " + held, held.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The dryRun preview is a write the presets have no opinion about, and the read operations of
     * edit_metadata decide the same way the flow does - the helpers the gates call, held to the
     * strictest preset directly.
     */
    @Test
    public void previewsAndReadOperationsDecideAgainstTheDoor()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CODE_REVIEW.name());
        assertNull("a preview is not a write", EditFormTool.presetWriteGate(true)); //$NON-NLS-1$
        assertEquals("a real write is refused", ToolGate.writeDoorMessage(EditFormTool.WRITE_DOOR), //$NON-NLS-1$
            EditFormTool.presetWriteGate(false));
        assertNull(DcsWorkshopTool.presetWriteGate(true));
        assertEquals(ToolGate.writeDoorMessage(DcsWorkshopTool.WRITE_DOOR),
            DcsWorkshopTool.presetWriteGate(false));
        assertNull("a reading operation is not a write", //$NON-NLS-1$
            EditMetadataTool.presetWriteGate("get_template_content", false)); //$NON-NLS-1$
        assertNull("a preview is not a write", EditMetadataTool.presetWriteGate("create_object", true)); //$NON-NLS-1$
        assertEquals("a real write is refused", //$NON-NLS-1$
            ToolGate.writeDoorMessage(EditMetadataTool.WRITE_DOOR),
            EditMetadataTool.presetWriteGate("create_object", false)); //$NON-NLS-1$
    }

    /**
     * An operation whose handler does not read dryRun writes with it too, so passing dryRun does
     * not take it past the door. Every other write has a preview and goes past.
     */
    @Test
    public void aWriteWithoutAPreviewIsRefusedEvenWithDryRun()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CODE_REVIEW.name());
        for (String op : EditMetadataTool.WRITES_WITHOUT_PREVIEW)
        {
            assertEquals(op, ToolGate.writeDoorMessage(EditMetadataTool.WRITE_DOOR),
                EditMetadataTool.presetWriteGate(op, true));
            assertEquals(op, ToolGate.writeDoorMessage(EditMetadataTool.WRITE_DOOR),
                EditMetadataTool.presetWriteGate(op, false));
        }
        assertEquals(java.util.Set.of("sync_export", "remove_object", "delete_metadata_object", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "rename_metadata_object"), EditMetadataTool.WRITES_WITHOUT_PREVIEW); //$NON-NLS-1$
        assertNull(EditMetadataTool.presetWriteGate("add_field", true)); //$NON-NLS-1$
    }

    /** The refusal names the facade and the preset, not a checkbox that does not exist. */
    @Test
    public void theRefusalNamesTheFacadeAndThePreset()
    {
        String text = ToolGate.writeDoorMessage("edit_metadata_writes"); //$NON-NLS-1$
        assertTrue(text, text.contains("'edit_metadata'")); //$NON-NLS-1$
        assertTrue(text, text.contains("preset")); //$NON-NLS-1$
        assertTrue(text, !text.contains("edit_metadata_writes")); //$NON-NLS-1$
    }

    /**
     * Where no preset blocks writing, the same calls get past the door and fail on the project
     * that is not there - the facades whose flow past the gate needs a UI thread are held by
     * their helpers instead, which answer null under the same preset.
     */
    @Test
    public void withWritesAllowedTheDoorAnswersNothing()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        List<Row> flows = writes().subList(3, 7);
        List<String> refused = new java.util.ArrayList<>();
        for (Row row : flows)
        {
            String answer = row.call();
            if (refusesByTheDoor(answer, row.door))
            {
                refused.add(row.facade + " was refused with no preset blocking writes: " + answer); //$NON-NLS-1$
            }
        }
        assertTrue(refused.toString(), refused.isEmpty());
        assertNull(EditFormTool.presetWriteGate(false));
        assertNull(DcsWorkshopTool.presetWriteGate(false));
        assertNull(EditMetadataTool.presetWriteGate("create_object", false)); //$NON-NLS-1$
    }

    /**
     * A batch of writes is refused before any of it starts, naming the entries the preset blocks;
     * a reading entry in the same batch is not named, because it is not blocked.
     */
    @Test
    public void aBatchOfWritesIsRefusedBeforeItStarts() throws Exception
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CODE_REVIEW.name());
        String answer = runBatch("[{\"operation\":\"create_object\",\"objectType\":\"Catalog\"," //$NON-NLS-1$
            + "\"name\":\"X\"},{\"operation\":\"add_object_attribute\",\"name\":\"Y\"}]"); //$NON-NLS-1$
        assertTrue(answer, refusesByTheDoor(answer, EditMetadataTool.WRITE_DOOR));
        assertTrue(answer, answer.contains("This batch was not started")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("[0] create_object")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("[1] add_object_attribute")); //$NON-NLS-1$

        String mixed = runBatch("[{\"operation\":\"get_template_content\",\"projectName\":\"P\"}," //$NON-NLS-1$
            + "{\"operation\":\"create_object\",\"objectType\":\"Catalog\",\"name\":\"X\"}]"); //$NON-NLS-1$
        assertTrue(mixed, mixed.contains("[1] create_object")); //$NON-NLS-1$
        assertFalse(mixed, mixed.contains("[0] get_template_content")); //$NON-NLS-1$
    }

    /**
     * Calls the batch body directly, the way the pending machinery would, with no entry to note
     * progress on - the refusals under test all happen before progress is noted.
     *
     * @param operations the batch payload
     * @return the batch's answer
     * @throws Exception when the method cannot be reached
     */
    private static String runBatch(String operations) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operations", operations); //$NON-NLS-1$
        Method method = EditMetadataTool.class.getDeclaredMethod("executeBatch", //$NON-NLS-1$
            Map.class, PendingWorkRegistry.PendingEntry.class);
        method.setAccessible(true);
        return (String)method.invoke(new EditMetadataTool(), params, null);
    }
}
