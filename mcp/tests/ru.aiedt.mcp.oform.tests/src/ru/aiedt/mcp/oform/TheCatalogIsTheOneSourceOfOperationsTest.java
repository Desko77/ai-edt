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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.ops.ColumnControls;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.oform.ops.ParamSpec;
import ru.aiedt.mcp.oform.ops.PropertyHandlers;

/**
 * The tool's operations, arguments, schema and help come from one catalog, and the catalog
 * describes what the hand-written tool described before it: the operations the tool had plus the
 * six of the command panels, their arguments with their types, the properties of set_property.
 * A theme extends the tool by registering with a catalog or a registry, not by editing the tool.
 */
public class TheCatalogIsTheOneSourceOfOperationsTest
{
    /** The operations, in the order the help lists them. */
    private static final List<String> OPERATIONS = List.of("audit", "layout", "diff_form", "inspect_layout", "describe_owner", "create_form", "import_from_infobase", "set_property", "bind_handler", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        "unbind_handler", "add_element", "add_page", "remove_page", "remove_element", "add_column", "remove_column", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "add_command_panel", "add_button", "add_submenu", "add_separator", "remove_button", "set_action_source", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "move_element", "help"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The operations that wrote before the catalog. */
    private static final List<String> WRITING = List.of("create_form", "import_from_infobase", "set_property", "bind_handler", "unbind_handler", "add_element", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "add_page", "remove_page", "remove_element", "add_column", "remove_column", "add_command_panel", "add_button", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "add_submenu", "add_separator", "remove_button", "set_action_source", "move_element"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** The arguments of the hand-written schema with their JSON types. */
    private static final Map<String, String> ARGUMENTS = new LinkedHashMap<>();

    static
    {
        String[] names = { "operation:string", "projectName:string", "formFqn:string", "modulePath:string", "dryRun:boolean", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "language:string", "limit:integer", "details:boolean", "ownerFqn:string", "name:string", "purpose:string", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "synonym:string", "title:string", "width:integer", "height:integer", "setAsDefault:boolean", "withFields:boolean", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "withTables:boolean", "withCommands:boolean", "columns:string", "tabularSections:string", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "module:string", "applicationId:string", //$NON-NLS-1$ //$NON-NLS-2$
            "elementName:string", "property:string", "value:string", "left:integer", "top:integer", "right:integer", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "bottom:integer", "fontFamily:string", "fontSize:integer", "bold:boolean", "italic:boolean", "underline:boolean", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "edge:string", "offset:integer", "event:string", "procedure:string", "stub:boolean", "type:string", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "parent:string", "page:string", "caption:string", "tooltip:string", "data:string", "cascade:boolean", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "editable:boolean", "control:string", "actionSource:string", "autoFill:boolean", "panel:string", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "action:string", "picture:integer", "before:string", "table:string" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String pair : names)
        {
            String[] parts = pair.split(":"); //$NON-NLS-1$
            ARGUMENTS.put(parts[0], parts[1]);
        }
    }

    /** The known and the writing operations are the ones the tool had, in the order the help listed them. */
    @Test
    public void theOperationsAreTheOnesTheToolHad()
    {
        assertEquals(OPERATIONS, OperationsCatalog.knownNames());
        assertEquals(WRITING, OperationsCatalog.writingNames());
        for (String name : OPERATIONS)
        {
            OperationSpec spec = OperationsCatalog.find(name.toUpperCase());
            assertNotNull(name, spec);
            assertEquals(name, spec.name);
        }
        assertNull(OperationsCatalog.find("no_such_operation")); //$NON-NLS-1$
    }

    /** The schema declares the arguments the hand-written schema declared, with the same types, and no others. */
    @Test
    public void theSchemaDeclaresTheSameArguments()
    {
        JsonObject schema = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        Map<String, String> declared = new LinkedHashMap<>();
        for (String name : properties.keySet())
        {
            declared.put(name, properties.getAsJsonObject(name).get("type").getAsString()); //$NON-NLS-1$
        }
        assertEquals(ARGUMENTS, declared);
        Map<String, ParamSpec.Type> types = OperationsCatalog.argumentTypes();
        assertEquals(ARGUMENTS.keySet(), types.keySet());
        // Every operation is named in the operation argument's description.
        String operation = properties.getAsJsonObject("operation").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : OPERATIONS)
        {
            assertTrue(name, operation.contains(name));
        }
        // The property argument lists every registered property, and the type argument every writable kind.
        String property = properties.getAsJsonObject("property").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : PropertyHandlers.names())
        {
            assertTrue(name, property.contains(name));
        }
        String type = properties.getAsJsonObject("type").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        for (String kind : ElementKinds.WRITABLE)
        {
            assertTrue(kind, type.contains(kind));
        }
        // The control argument lists every registered column control.
        String control = properties.getAsJsonObject("control").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : ColumnControls.names())
        {
            assertTrue(name, control.contains(name));
        }
    }

    /** The help lists every operation, the events, and answers without a project. */
    @Test
    public void theHelpListsEveryOperation()
    {
        String help = new OrdinaryFormsTool().execute(Map.of("operation", "help")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(help).getAsJsonObject();
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        String operations = answer.getAsJsonArray("operations").toString(); //$NON-NLS-1$
        for (String name : OPERATIONS)
        {
            assertTrue(name, operations.contains("\"" + name + " - ")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertNotNull(answer.getAsJsonObject("events").get("form")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = answer.getAsJsonObject("properties"); //$NON-NLS-1$
        assertNotNull(help, properties);
        assertEquals(PropertyHandlers.names(), List.copyOf(properties.keySet()));
        assertEquals(17, properties.size());
        for (String name : PropertyHandlers.names())
        {
            assertEquals(name, PropertyHandlers.descriptions().get(name), properties.get(name).getAsString());
        }
        assertTrue(operations, operations.contains("tabs")); //$NON-NLS-1$
        assertTrue(operations, operations.contains("editFormat")); //$NON-NLS-1$
    }

    /** The registries hold sixteen writable properties, one named refusal, four column controls and seventeen kinds. */
    @Test
    public void theRegistriesHoldTheMeasuredSet()
    {
        assertEquals(List.of("title", "caption", "tooltip", "size", "box", "visible", "enabled", "readOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "tabs", "font", "textColor", "backColor", "borderColor", "alignment", "format", "editFormat", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "anchor"), //$NON-NLS-1$
            PropertyHandlers.names());
        assertNotNull(PropertyHandlers.find("BOX")); //$NON-NLS-1$
        assertNotNull(PropertyHandlers.find("visible")); //$NON-NLS-1$
        assertNotNull(PropertyHandlers.find("ANCHOR")); //$NON-NLS-1$
        assertEquals(List.of("field", "checkBox", "selectField", "none"), ColumnControls.names()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(ColumnControls.find("field").editor); //$NON-NLS-1$
        assertFalse(ColumnControls.find("none").editor); //$NON-NLS-1$
        assertEquals("381ed624-9217-4e63-85db-c4c3cb87daae", ColumnControls.find("FIELD").uuid); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("35af3d93-d7c7-4a2e-a8eb-bac87a1a3f26", ColumnControls.find("checkBox").uuid); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("64483e7f-3833-48e2-8c75-2c31aac49f6e", ColumnControls.find("selectField").uuid); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("00000000-0000-0000-0000-000000000000", ColumnControls.find("none").uuid); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(17, ElementKinds.WRITABLE.size());
        assertTrue(ElementKinds.isWritable("tablefield")); //$NON-NLS-1$
        assertEquals("Field", ElementKinds.canonical("field")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("381ed624-9217-4e63-85db-c4c3cb87daae", ElementKinds.idOf("FIELD")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Panel", ElementKinds.nameOf(ElementKinds.PANEL_TYPE.toUpperCase())); //$NON-NLS-1$
        assertTrue(ElementKinds.isWritable("label")); //$NON-NLS-1$
        assertTrue(ElementKinds.isWritable("commandpanel")); //$NON-NLS-1$
    }

    /** An unknown operation is refused by name, with the known ones listed. */
    @Test
    public void anUnknownOperationIsRefusedByName()
    {
        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "paint_form", "projectName", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(answer.contains("Unknown operation: paint_form")); //$NON-NLS-1$
        assertTrue(answer.contains("add_column")); //$NON-NLS-1$
    }

    /** An argument's text does not open with its operation: the schema writes the operation in front of it. */
    @Test
    public void anArgumentTextDoesNotRepeatItsOperation()
    {
        for (String name : OperationsCatalog.knownNames())
        {
            for (ParamSpec param : OperationsCatalog.find(name).params)
            {
                assertFalse(name + "." + param.name, param.description().startsWith(name + ":")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        JsonObject properties = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        for (String argument : properties.keySet())
        {
            String description = properties.getAsJsonObject(argument).get("description").getAsString(); //$NON-NLS-1$
            for (String name : OperationsCatalog.knownNames())
            {
                assertFalse(argument, description.contains(name + ": " + name + ":")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }
}
