/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool.ResponseType;

/**
 * Tests for {@link EditFormTool}.
 * <p>
 * Tests cover: tool metadata (name, description, schema, responseType),
 * parameter validation for required fields, help operation output.
 * <p>
 * Note: operations other than 'help' require Eclipse workspace and
 * PlatformUI (Display.syncExec, BM transactions), so only pre-dispatch
 * validation and the help operation can be tested without Eclipse runtime.
 */
public class EditFormToolTest
{
    // -- Tool metadata --

    @Test
    public void testName()
    {
        EditFormTool tool = new EditFormTool();
        assertEquals("edit_form", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        EditFormTool tool = new EditFormTool();
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        EditFormTool tool = new EditFormTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        EditFormTool tool = new EditFormTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"name\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"title\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"elementType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"dataPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"parentName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"beforeName\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaRequiredFields()
    {
        EditFormTool tool = new EditFormTool();
        String schema = tool.getInputSchema();

        // Check that required array includes projectName, formFqn, operation
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
    }

    // -- Required parameter validation --

    @Test
    public void testExecuteMissingProjectName()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("formFqn", "Catalog.Products.Form.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "TestField"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyProjectName()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", "Catalog.Products.Form.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingFormFqn()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "TestField"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("formFqn is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyFormFqn()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("formFqn is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingOperation()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", "Catalog.Products.Form.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyOperation()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", "Catalog.Products.Form.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    // -- Help operation --

    @Test
    public void testHelpOperation()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        // help does not require projectName or formFqn
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertNotNull(result);
        // Help should return documentation with operation descriptions
        assertTrue(result.contains("addField")); //$NON-NLS-1$
        assertTrue(result.contains("addGroup")); //$NON-NLS-1$
        assertTrue(result.contains("addButton")); //$NON-NLS-1$
        assertTrue(result.contains("addTable")); //$NON-NLS-1$
        assertTrue(result.contains("addDecoration")); //$NON-NLS-1$
        assertTrue(result.contains("removeItem")); //$NON-NLS-1$
        // Should not contain error
        assertFalse(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testHelpOperationCaseInsensitive()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertNotNull(result);
        // Case-insensitive help should also work
        assertTrue(result.contains("addField")); //$NON-NLS-1$
    }

    @Test
    public void testHelpOperationWithoutProjectName()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        // No projectName - help should still work

        String result = tool.execute(params);
        assertFalse(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("Operations")); //$NON-NLS-1$
    }

    @Test
    public void testHelpOperationContainsExamples()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Examples")); //$NON-NLS-1$
    }

    // -- Validation order --
    // help is checked BEFORE projectName/formFqn/operation validation.
    // Other operations require valid projectName, formFqn, and operation.
    // Invalid operation and name-required checks happen inside Display.syncExec,
    // which requires Eclipse runtime - not testable here.

    @Test
    public void testValidationOrderProjectNameBeforeFormFqn()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        // Both missing - projectName should be reported first
        params.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testValidationOrderFormFqnBeforeOperation()
    {
        EditFormTool tool = new EditFormTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        // formFqn missing, operation is provided
        params.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("formFqn is required")); //$NON-NLS-1$
    }
}
