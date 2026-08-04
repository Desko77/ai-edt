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
 * Tests for {@link DiffModuleTool}.
 * <p>
 * Tests cover: tool metadata (name, description, schema, responseType),
 * parameter validation, mode validation, path traversal protection,
 * .bsl extension check, and project validation reach.
 * <p>
 * Note: tests that require Eclipse workspace (actual file I/O, git history)
 * are not included as they need a running Eclipse runtime.
 */
public class DiffModuleToolTest
{
    // -- Tool metadata --

    @Test
    public void testName()
    {
        DiffModuleTool tool = new DiffModuleTool();
        assertEquals("diff_module", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        DiffModuleTool tool = new DiffModuleTool();
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        DiffModuleTool tool = new DiffModuleTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        DiffModuleTool tool = new DiffModuleTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"contextLines\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"moduleType\"")); //$NON-NLS-1$
    }

    // -- Required parameter validation --

    @Test
    public void testExecuteMissingProjectName()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyProjectName()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingModulePathAndObjectName()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("either modulePath or objectName is required")); //$NON-NLS-1$
    }

    // -- Mode validation --

    @Test
    public void testExecuteInvalidMode()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "badMode"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("invalid mode")); //$NON-NLS-1$
        assertTrue(result.contains("badMode")); //$NON-NLS-1$
    }

    @Test
    public void testValidModeSummary()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "summary"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertFalse("summary should be a valid mode", //$NON-NLS-1$
            result.contains("invalid mode")); //$NON-NLS-1$
    }

    @Test
    public void testValidModeUnified()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "unified"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertFalse("unified should be a valid mode", //$NON-NLS-1$
            result.contains("invalid mode")); //$NON-NLS-1$
    }

    @Test
    public void testValidModeMethods()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "methods"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertFalse("methods should be a valid mode", //$NON-NLS-1$
            result.contains("invalid mode")); //$NON-NLS-1$
    }

    // -- Path traversal protection --

    @Test
    public void testExecutePathTraversal()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "../../etc/passwd.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("must not contain '..'")); //$NON-NLS-1$
    }

    // -- .bsl extension validation --

    @Test
    public void testExecuteNonBslFile()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Configuration/Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("only .bsl module files")); //$NON-NLS-1$
    }

    // -- objectName resolution --

    @Test
    public void testResolveObjectNameInvalidFormat()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "NoDot"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Pinned on the shape the message has to quote, not on the sentence carrying it.
        assertTrue(result.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(result.contains("'Type.Name'")); //$NON-NLS-1$
    }

    @Test
    public void testResolveObjectNameUnknownType()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "UnknownType.Name"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("unknown metadata type")); //$NON-NLS-1$
    }

    // -- Valid params reach project validation --

    @Test
    public void testValidParamsReachProjectValidation()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // All pre-checks passed, reaches workspace validation
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testDefaultModeSummaryReachesProjectValidation()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // No mode specified - defaults to summary

        String result = tool.execute(params);
        assertFalse(result.contains("invalid mode")); //$NON-NLS-1$
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveViaObjectNameReachesProjectValidation()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // objectName resolves to Documents/MyDoc/ObjectModule.bsl, reaches workspace
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    // -- Result file name --

    @Test
    public void testResultFileNameWithModulePath()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String fileName = tool.getResultFileName(params);
        assertEquals("diff-documents-mydoc-objectmodule.bsl.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithoutModulePath()
    {
        DiffModuleTool tool = new DiffModuleTool();
        Map<String, String> params = new HashMap<>();

        String fileName = tool.getResultFileName(params);
        assertEquals("diff-module.md", fileName); //$NON-NLS-1$
    }
}
