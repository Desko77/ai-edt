/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.CommandBarExtInfo;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormStandardCommand;
import com._1c.g5.v8.dt.form.model.FormStandardCommandSource;
import com._1c.g5.v8.dt.form.model.Table;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * What a call to {@code set_excluded_commands} would do, and what {@code get_form_structure} reports
 * back.
 *
 * <p>Pinned without a runtime, on a form model built from the factories: the standard command list
 * of a form object is a plain model list the platform fills when it builds the form, so filling it
 * by hand exercises the same planning the live path runs. The plan is asked directly - the write
 * transaction is not entered here.
 *
 * <p>The elements a new exclusion takes are the objects of the object's own command list, not new
 * ones, and the names the reader emits are the names the writer takes: both are held by an object
 * identity assertion and by reading a list back through the write.
 */
public class FormExcludedCommandsOpsTest
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

    /** A standard command with a name. */
    private static FormStandardCommand command(String name)
    {
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName(name);
        return command;
    }

    /** A form whose own standard command list holds the given names. */
    private static Form formWithCommands(String... names)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        for (String name : names)
        {
            form.getCommands().add(command(name));
        }
        return form;
    }

    /** The command of a source whose name is the given one. */
    private static FormStandardCommand commandOf(FormStandardCommandSource source, String name)
    {
        for (FormStandardCommand candidate : source.getCommands())
        {
            if (name.equals(candidate.getName()))
            {
                return candidate;
            }
        }
        throw new IllegalArgumentException(name);
    }

    /** Keeps the named commands of the source out of its command interface. */
    private static void exclude(FormStandardCommandSource source, String... names)
    {
        for (String name : names)
        {
            source.getExcludedCommands().add(commandOf(source, name));
        }
    }

    /** The names an object keeps out of its command interface, in model order. */
    private static List<String> excludedNames(FormStandardCommandSource source)
    {
        List<String> names = new ArrayList<>();
        for (FormStandardCommand command : source.getExcludedCommands())
        {
            names.add(command.getName());
        }
        return names;
    }

    /** What {@code get_form_structure} reports about an element. */
    private static JsonObject propertiesOf(Object item)
    {
        return new GetFormStructureTool().collectProperties(item);
    }

    /** The names the reader emitted for an element, or an empty list when it emitted none. */
    private static List<String> readExcluded(JsonObject properties)
    {
        List<String> names = new ArrayList<>();
        if (properties == null || !properties.has("excludedCommands")) //$NON-NLS-1$
        {
            return names;
        }
        for (JsonElement name : properties.getAsJsonArray("excludedCommands")) //$NON-NLS-1$
        {
            names.add(name.getAsString());
        }
        return names;
    }

    /**
     * Replace leaves exactly the names passed, and each is the object's own command, not a new one.
     */
    @Test
    public void replaceSetsTheListToExactlyTheNamesPassed()
    {
        Form form = formWithCommands("Create", "Post", "Write"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        exclude(form, "Write"); //$NON-NLS-1$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("Post"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(plan.refusal);
        assertEquals(List.of("Post"), plan.wanted); //$NON-NLS-1$
        assertEquals(List.of("Post"), plan.added); //$NON-NLS-1$
        assertEquals(List.of("Write"), plan.removed); //$NON-NLS-1$
        assertTrue(plan.changed);

        assertNull(FormExcludedCommandsOps.applyPlan(plan));

        assertEquals(List.of("Post"), excludedNames(form)); //$NON-NLS-1$
        // The element written is the command of the form's own list, not a new one.
        assertSame(commandOf(form, "Post"), form.getExcludedCommands().get(0)); //$NON-NLS-1$
    }

    /**
     * An empty list under replace clears every exclusion.
     */
    @Test
    public void anEmptyCommandListUnderReplaceClearsTheExclusions()
    {
        Form form = formWithCommands("Create", "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        exclude(form, "Post"); //$NON-NLS-1$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", List.of(), //$NON-NLS-1$
            "replace"); //$NON-NLS-1$

        assertNull(plan.refusal);
        assertTrue(plan.wanted.isEmpty());
        assertEquals(List.of("Post"), plan.removed); //$NON-NLS-1$
        assertTrue(plan.changed);

        assertNull(FormExcludedCommandsOps.applyPlan(plan));

        assertTrue(excludedNames(form).isEmpty());
    }

    /**
     * Add keeps what was excluded and appends the new names in the order the object lists them.
     */
    @Test
    public void addKeepsTheOrderAndAppendsInTheObjectsOwnOrder()
    {
        Form form = formWithCommands("Create", "Post", "Write"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        exclude(form, "Write"); //$NON-NLS-1$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("Post", "Write"), "add"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(plan.refusal);
        assertEquals(List.of("Write", "Post"), plan.wanted); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Post"), plan.added); //$NON-NLS-1$
        assertTrue(plan.removed.isEmpty());
        assertTrue(plan.changed);

        assertNull(FormExcludedCommandsOps.applyPlan(plan));

        assertEquals(List.of("Write", "Post"), excludedNames(form)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Remove drops the named ones and leaves the rest of the list alone.
     */
    @Test
    public void removeDropsTheNamedOnesAndKeepsTheRest()
    {
        Form form = formWithCommands("Create", "Post", "Write", "Copy"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        exclude(form, "Write", "Post"); //$NON-NLS-1$ //$NON-NLS-2$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("Post"), "remove"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(plan.refusal);
        assertEquals(List.of("Write"), plan.wanted); //$NON-NLS-1$
        assertEquals(List.of("Post"), plan.removed); //$NON-NLS-1$
        assertTrue(plan.added.isEmpty());
        assertTrue(plan.changed);

        assertNull(FormExcludedCommandsOps.applyPlan(plan));

        assertEquals(List.of("Write"), excludedNames(form)); //$NON-NLS-1$
    }

    /**
     * A call that asks for the list already there reports no change and touches nothing.
     */
    @Test
    public void aRepeatOfTheSameListChangesNothingAndWritesNothing()
    {
        Form form = formWithCommands("Create", "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        exclude(form, "Post"); //$NON-NLS-1$

        FormExcludedCommandsOps.Plan replace = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("pOsT"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        FormExcludedCommandsOps.Plan add = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("Post"), "add"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(replace.refusal);
        assertFalse(replace.changed);
        assertTrue(replace.added.isEmpty());
        assertTrue(replace.removed.isEmpty());
        assertFalse(add.changed);

        FormStandardCommand before = form.getExcludedCommands().get(0);
        assertNull(FormExcludedCommandsOps.applyPlan(replace));
        assertNull(FormExcludedCommandsOps.applyPlan(add));

        assertEquals(1, form.getExcludedCommands().size());
        assertSame(before, form.getExcludedCommands().get(0));
    }

    /**
     * A name the object does not have is refused with the names it does have, and the list stays as it was.
     */
    @Test
    public void anUnknownNameIsRefusedWithTheNamesTheObjectAllows()
    {
        Form form = formWithCommands("Create", "Post", "Write"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        exclude(form, "Write"); //$NON-NLS-1$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("Posts"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(plan.refusal);
        assertTrue(plan.refusal, plan.refusal.contains("'Posts'")); //$NON-NLS-1$
        assertTrue(plan.refusal, plan.refusal.contains("Did you mean 'Post'?")); //$NON-NLS-1$
        assertTrue(plan.refusal, plan.refusal.contains("Create, Post, Write")); //$NON-NLS-1$
        assertTrue(plan.refusal, plan.refusal.contains("Nothing was written")); //$NON-NLS-1$
        assertEquals(List.of("Create", "Post", "Write"), plan.available); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("Write"), excludedNames(form)); //$NON-NLS-1$
    }

    /**
     * An object whose standard command list is not built is refused in its own words.
     */
    @Test
    public void anObjectWithoutAStandardCommandListSaysSoInItsOwnWords()
    {
        Form form = FormFactory.eINSTANCE.createForm();

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("Post"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(plan.refusal);
        assertTrue(plan.refusal, plan.refusal.contains("standard command list of this form is not built")); //$NON-NLS-1$
        assertTrue(plan.available.isEmpty());
    }

    /**
     * A command bar is refused with the address of the object it takes its commands from.
     */
    @Test
    public void aCommandBarIsRefusedWithTheAddressOfItsSource()
    {
        Form form = formWithCommands("Create", "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Товары"); //$NON-NLS-1$
        table.getCommands().add(command("Create")); //$NON-NLS-1$
        form.getItems().add(table);
        FormGroup bar = FormFactory.eINSTANCE.createFormGroup();
        bar.setName("КоманднаяПанель"); //$NON-NLS-1$
        CommandBarExtInfo extInfo = FormFactory.eINSTANCE.createCommandBarExtInfo();
        extInfo.setCommandSource(table);
        bar.setExtInfo(extInfo);
        form.getItems().add(bar);

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Item.КоманднаяПанель", //$NON-NLS-1$
            List.of("Create"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(plan.refusal);
        assertTrue(plan.refusal, plan.refusal.contains("Item.КоманднаяПанель")); //$NON-NLS-1$
        assertTrue(plan.refusal, plan.refusal.contains("Item.Товары")); //$NON-NLS-1$
        assertTrue(plan.refusal, plan.refusal.contains("Nothing was written")); //$NON-NLS-1$
    }

    /**
     * An address no element of the form carries is refused.
     */
    @Test
    public void anAddressTheFormDoesNotHoldIsRefused()
    {
        Form form = formWithCommands("Create"); //$NON-NLS-1$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Item.НетТакого", //$NON-NLS-1$
            List.of("Create"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(plan.refusal);
        assertTrue(plan.refusal, plan.refusal.contains("'НетТакого'")); //$NON-NLS-1$
    }

    /**
     * A name is matched without regard to case and lands in the list once, in the object's own spelling.
     */
    @Test
    public void aNameIsTakenInEitherCaseAndIsWrittenOnce()
    {
        Form form = formWithCommands("Create", "Post"); //$NON-NLS-1$ //$NON-NLS-2$

        FormExcludedCommandsOps.Plan plan = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            List.of("pOsT", "CREATE", "post"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNull(plan.refusal);
        assertEquals(List.of("Create", "Post"), plan.wanted); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A table and a field keep their own exclusions, and the form keeps its own.
     */
    @Test
    public void aTableAndAFieldKeepTheirOwnExclusions()
    {
        Form form = formWithCommands("Create", "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Товары"); //$NON-NLS-1$
        table.getCommands().add(command("Create")); //$NON-NLS-1$
        table.getCommands().add(command("Post")); //$NON-NLS-1$
        form.getItems().add(table);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Поле"); //$NON-NLS-1$
        field.getCommands().add(command("Post")); //$NON-NLS-1$
        form.getItems().add(field);

        FormExcludedCommandsOps.Plan onTable = FormExcludedCommandsOps.plan(form, "Item.Товары", //$NON-NLS-1$
            List.of("Post"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        FormExcludedCommandsOps.Plan onField = FormExcludedCommandsOps.plan(form, "Поле", //$NON-NLS-1$
            List.of("Post"), "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(onTable.refusal);
        assertNull(onField.refusal);
        assertNull(FormExcludedCommandsOps.applyPlan(onTable));
        assertNull(FormExcludedCommandsOps.applyPlan(onField));

        assertEquals(List.of("Post"), excludedNames(table)); //$NON-NLS-1$
        assertSame(commandOf(table, "Post"), table.getExcludedCommands().get(0)); //$NON-NLS-1$
        assertEquals(List.of("Post"), excludedNames(field)); //$NON-NLS-1$
        assertSame(commandOf(field, "Post"), field.getExcludedCommands().get(0)); //$NON-NLS-1$
        // The form's own list is untouched by an item's call.
        assertTrue(excludedNames(form).isEmpty());
    }

    /**
     * What get_form_structure reports is what the operation accepts, and passing it back changes nothing.
     */
    @Test
    public void theReaderEmitsTheNamesTheWriterTakesBack()
    {
        Form form = formWithCommands("Create", "Post", "Write"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        exclude(form, "Post", "Write"); //$NON-NLS-1$ //$NON-NLS-2$
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Товары"); //$NON-NLS-1$
        // Every object carries its own command list, so the table's commands are its own objects.
        for (String name : List.of("Create", "Post", "Write")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            table.getCommands().add(command(name));
        }
        form.getItems().add(table);
        exclude(table, "Create"); //$NON-NLS-1$
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Поле"); //$NON-NLS-1$
        field.getCommands().add(command("Post")); //$NON-NLS-1$
        form.getItems().add(field);
        exclude(field, "Post"); //$NON-NLS-1$

        JsonObject formProps = propertiesOf(form);
        JsonObject tableProps = propertiesOf(table);
        JsonObject fieldProps = propertiesOf(field);

        assertEquals(List.of("Post", "Write"), readExcluded(formProps)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Create"), readExcluded(tableProps)); //$NON-NLS-1$
        assertEquals(List.of("Post"), readExcluded(fieldProps)); //$NON-NLS-1$

        // What the reader emitted is what the writer takes, and taking it changes nothing.
        FormExcludedCommandsOps.Plan onForm = FormExcludedCommandsOps.plan(form, "Form", //$NON-NLS-1$
            readExcluded(formProps), "replace"); //$NON-NLS-1$
        FormExcludedCommandsOps.Plan onTable = FormExcludedCommandsOps.plan(form, "Item.Товары", //$NON-NLS-1$
            readExcluded(tableProps), "replace"); //$NON-NLS-1$
        FormExcludedCommandsOps.Plan onField = FormExcludedCommandsOps.plan(form, "Item.Поле", //$NON-NLS-1$
            readExcluded(fieldProps), "replace"); //$NON-NLS-1$

        assertNull(onForm.refusal);
        assertNull(onTable.refusal);
        assertNull(onField.refusal);
        assertFalse(onForm.changed);
        assertFalse(onTable.changed);
        assertFalse(onField.changed);
    }

    /**
     * An object that excludes nothing reports no excluded commands at all.
     */
    @Test
    public void anObjectThatKeepsNothingSaysNothingAboutIt()
    {
        Form form = formWithCommands("Create"); //$NON-NLS-1$

        JsonObject props = propertiesOf(form);

        assertTrue(props == null || !props.has("excludedCommands")); //$NON-NLS-1$
    }

    /**
     * Under a write-blocking preset the write is refused by the door and the preview is not.
     */
    @Test
    public void theWriteIsRefusedByItsDoorAndThePreviewIsNot()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CODE_REVIEW.name());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "set_excluded_commands"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "NoProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", "Catalog.None.Form.F"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("commands", "Post"); //$NON-NLS-1$ //$NON-NLS-2$

        IMcpTool tool = new EditMetadataTool();
        assertTrue(refusesByTheDoor(tool.execute(params)));

        assertEquals(ToolGate.writeDoorMessage(EditMetadataTool.WRITE_DOOR),
            EditMetadataTool.presetWriteGate("set_excluded_commands", false)); //$NON-NLS-1$
        assertNull("a preview is not a write", //$NON-NLS-1$
            EditMetadataTool.presetWriteGate("set_excluded_commands", true)); //$NON-NLS-1$
    }

    /**
     * Whether the answer is the write door's own refusal.
     *
     * @param answer a facade's answer
     * @return true when the refusal is the gate's
     */
    private static boolean refusesByTheDoor(String answer)
    {
        String message = ToolGate.writeDoorMessage(EditMetadataTool.WRITE_DOOR);
        try
        {
            JsonObject parsed = JsonParser.parseString(answer).getAsJsonObject();
            return parsed.has("error") && parsed.get("error").getAsString().startsWith(message); //$NON-NLS-1$
        }
        catch (Exception notJson)
        {
            return answer.contains(message);
        }
    }
}
