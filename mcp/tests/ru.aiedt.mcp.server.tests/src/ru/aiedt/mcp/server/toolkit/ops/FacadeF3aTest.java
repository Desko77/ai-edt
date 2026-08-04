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
 * Tests for {@link DiagnosticsFacadeTool} and {@link ProjectAdminFacadeTool}.
 * <p>
 * Both facades route an operation straight to an already-registered standalone tool, so calling
 * them with a REAL operation name reaches EDT-dependent code (Activator.getDefault(), the
 * workspace, the BM) that a plain JUnit run cannot exercise. What a headless test CAN pin down
 * without that runtime is the routing table itself: which operation names the facade recognizes,
 * that an unrecognized one is rejected together with the full allowed list, and that help works.
 * Live equivalence - that operation=X on the facade produces the same result as calling the
 * standalone X directly - is verified separately, against a running EDT workspace.
 */
public class FacadeF3aTest
{
    // -- diagnostics: tool metadata --

    @Test
    public void diagnosticsName()
    {
        assertEquals("diagnostics", new DiagnosticsFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void diagnosticsResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new DiagnosticsFacadeTool().getResponseType());
    }

    @Test
    public void diagnosticsDescriptionNotEmpty()
    {
        String description = new DiagnosticsFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void diagnosticsSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new DiagnosticsFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"checkId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- diagnostics: routing table --

    @Test
    public void diagnosticsMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new DiagnosticsFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void diagnosticsEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DiagnosticsFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void diagnosticsUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DiagnosticsFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("get_project_errors")); //$NON-NLS-1$
        assertTrue(result.contains("get_problem_summary")); //$NON-NLS-1$
        assertTrue(result.contains("revalidate_objects")); //$NON-NLS-1$
        assertTrue(result.contains("clean_project")); //$NON-NLS-1$
        assertTrue(result.contains("validate_for_export")); //$NON-NLS-1$
        assertTrue(result.contains("get_check_description")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void diagnosticsHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DiagnosticsFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("get_project_errors")); //$NON-NLS-1$
        assertTrue(result.contains("get_problem_summary")); //$NON-NLS-1$
        assertTrue(result.contains("revalidate_objects")); //$NON-NLS-1$
        assertTrue(result.contains("clean_project")); //$NON-NLS-1$
        assertTrue(result.contains("validate_for_export")); //$NON-NLS-1$
        assertTrue(result.contains("get_check_description")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void diagnosticsHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DiagnosticsFacadeTool().execute(params);
        assertTrue(result.contains("get_project_errors")); //$NON-NLS-1$
    }

    @Test
    public void diagnosticsHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DiagnosticsFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- project_admin: tool metadata --

    @Test
    public void projectAdminName()
    {
        assertEquals("project_admin", new ProjectAdminFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void projectAdminResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ProjectAdminFacadeTool().getResponseType());
    }

    @Test
    public void projectAdminDescriptionNotEmpty()
    {
        String description = new ProjectAdminFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void projectAdminSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new ProjectAdminFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"deleteContent\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- project_admin: routing table --

    @Test
    public void projectAdminMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ProjectAdminFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void projectAdminEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ProjectAdminFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void projectAdminUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ProjectAdminFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("list_projects")); //$NON-NLS-1$
        assertTrue(result.contains("list_configurations")); //$NON-NLS-1$
        assertTrue(result.contains("get_configuration_properties")); //$NON-NLS-1$
        assertTrue(result.contains("create_project")); //$NON-NLS-1$
        assertTrue(result.contains("delete_project")); //$NON-NLS-1$
        assertTrue(result.contains("resync_to_disk")); //$NON-NLS-1$
        assertTrue(result.contains("restart_edt")); //$NON-NLS-1$
        assertTrue(result.contains("list_subsystems")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void projectAdminHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ProjectAdminFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("list_projects")); //$NON-NLS-1$
        assertTrue(result.contains("list_configurations")); //$NON-NLS-1$
        assertTrue(result.contains("get_configuration_properties")); //$NON-NLS-1$
        assertTrue(result.contains("create_project")); //$NON-NLS-1$
        assertTrue(result.contains("delete_project")); //$NON-NLS-1$
        assertTrue(result.contains("resync_to_disk")); //$NON-NLS-1$
        assertTrue(result.contains("restart_edt")); //$NON-NLS-1$
        assertTrue(result.contains("list_subsystems")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void projectAdminHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ProjectAdminFacadeTool().execute(params);
        assertTrue(result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void projectAdminHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ProjectAdminFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- cross-facade sanity --

    @Test
    public void facadeNamesAreDistinctFromEachOther()
    {
        assertFalse(DiagnosticsFacadeTool.NAME.equals(ProjectAdminFacadeTool.NAME));
    }
}
