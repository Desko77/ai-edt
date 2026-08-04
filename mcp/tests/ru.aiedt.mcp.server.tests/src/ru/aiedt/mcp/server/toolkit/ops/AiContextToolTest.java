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
 * Tests for {@link AiContextTool}.
 * <p>
 * Tests cover: tool metadata (name, description, schema, responseType),
 * parameter validation for required fields (projectName, target).
 * <p>
 * Note: tests that require Eclipse workspace or PlatformUI (BM, EMF,
 * Display.syncExec) are not included as they need a running Eclipse runtime.
 * Only pre-dispatch validation can be tested in unit environment.
 */
public class AiContextToolTest
{
    // -- Tool metadata --

    @Test
    public void testName()
    {
        AiContextTool tool = new AiContextTool();
        assertEquals("ai_context", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        AiContextTool tool = new AiContextTool();
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        AiContextTool tool = new AiContextTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        AiContextTool tool = new AiContextTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"target\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"depth\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"focusMethod\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"maxMethods\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"includeSource\"")); //$NON-NLS-1$
    }

    // -- Required parameter validation --

    @Test
    public void testExecuteMissingProjectName()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();
        params.put("target", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyProjectName()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("target", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingTarget()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("target is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyTarget()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("target", ""); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("target is required")); //$NON-NLS-1$
    }

    // -- Result file name --

    @Test
    public void testResultFileNameWithTarget()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();
        params.put("target", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        String fileName = tool.getResultFileName(params);
        assertEquals("ai-context-catalog-products.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithoutTarget()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();

        String fileName = tool.getResultFileName(params);
        assertEquals("ai-context.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithModulePath()
    {
        AiContextTool tool = new AiContextTool();
        Map<String, String> params = new HashMap<>();
        params.put("target", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String fileName = tool.getResultFileName(params);
        assertEquals("ai-context-commonmodules-mymodule-module-bsl.md", fileName); //$NON-NLS-1$
    }
}
