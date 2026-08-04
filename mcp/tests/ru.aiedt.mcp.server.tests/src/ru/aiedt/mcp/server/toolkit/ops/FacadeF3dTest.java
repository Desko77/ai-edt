/**
 * AI-EDT - 1C AI tools for EDT
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
 * Tests for {@link WorkspaceMarksFacadeTool} and {@link DocsLookupFacadeTool}.
 * <p>
 * Both facades route an operation straight to an already-registered standalone tool, so calling them
 * with a REAL operation name reaches EDT-dependent code (Activator.getDefault(), the workspace) that a
 * plain JUnit run cannot exercise. What a headless test CAN pin down without that runtime is the
 * routing table itself: which operation names each facade recognizes, that an unrecognized one is
 * rejected together with the full allowed list, and that help works. Live equivalence - that
 * operation=X on the facade produces the same result as calling the standalone X directly - is verified
 * separately, against a running EDT workspace.
 * <p>
 * None of the six standalones absorbed here (get_tags, get_objects_by_tags, get_bookmarks,
 * get_tasks, get_platform_documentation, get_object_help) declares its own {@code operation} parameter,
 * so - unlike {@code infobase_admin}'s sync_control - every operation on both facades is a plain
 * pass-through, with no remap needed.
 */
public class FacadeF3dTest
{
    // -- workspace_marks: tool metadata --

    @Test
    public void workspaceMarksName()
    {
        assertEquals("workspace_marks", new WorkspaceMarksFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void workspaceMarksResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new WorkspaceMarksFacadeTool().getResponseType());
    }

    @Test
    public void workspaceMarksDescriptionNotEmpty()
    {
        String description = new WorkspaceMarksFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void workspaceMarksSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new WorkspaceMarksFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"tags\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- workspace_marks: routing table --

    @Test
    public void workspaceMarksMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new WorkspaceMarksFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void workspaceMarksEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new WorkspaceMarksFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void workspaceMarksUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new WorkspaceMarksFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("get_tags")); //$NON-NLS-1$
        assertTrue(result.contains("get_objects_by_tags")); //$NON-NLS-1$
        assertTrue(result.contains("get_bookmarks")); //$NON-NLS-1$
        assertTrue(result.contains("get_tasks")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void workspaceMarksHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new WorkspaceMarksFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("get_tags")); //$NON-NLS-1$
        assertTrue(result.contains("get_objects_by_tags")); //$NON-NLS-1$
        assertTrue(result.contains("get_bookmarks")); //$NON-NLS-1$
        assertTrue(result.contains("get_tasks")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void workspaceMarksHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new WorkspaceMarksFacadeTool().execute(params);
        assertTrue(result.contains("get_tags")); //$NON-NLS-1$
    }

    @Test
    public void workspaceMarksHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new WorkspaceMarksFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- docs_lookup: tool metadata --

    @Test
    public void docsLookupName()
    {
        assertEquals("docs_lookup", new DocsLookupFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void docsLookupResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new DocsLookupFacadeTool().getResponseType());
    }

    @Test
    public void docsLookupDescriptionNotEmpty()
    {
        String description = new DocsLookupFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void docsLookupSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new DocsLookupFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"typeName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- docs_lookup: routing table --

    @Test
    public void docsLookupMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new DocsLookupFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void docsLookupEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DocsLookupFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void docsLookupUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DocsLookupFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("get_platform_documentation")); //$NON-NLS-1$
        assertTrue(result.contains("get_object_help")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void docsLookupHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DocsLookupFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("get_platform_documentation")); //$NON-NLS-1$
        assertTrue(result.contains("get_object_help")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void docsLookupHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DocsLookupFacadeTool().execute(params);
        assertTrue(result.contains("get_platform_documentation")); //$NON-NLS-1$
    }

    @Test
    public void docsLookupHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DocsLookupFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- cross-facade sanity --

    @Test
    public void facadeNamesAreDistinctFromEachOther()
    {
        assertFalse(WorkspaceMarksFacadeTool.NAME.equals(DocsLookupFacadeTool.NAME));
    }
}
