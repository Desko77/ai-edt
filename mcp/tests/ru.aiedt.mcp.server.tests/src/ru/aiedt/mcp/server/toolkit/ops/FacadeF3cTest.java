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
 * Tests for {@link InsightsFacadeTool} and {@link SecurityAuditFacadeTool}.
 * <p>
 * Both facades route an operation straight to an already-registered standalone tool, so calling
 * them with a REAL operation name reaches EDT-dependent code (Activator.getDefault(), the
 * workspace, the BM) that a plain JUnit run cannot exercise. What a headless test CAN pin down
 * without that runtime is the routing table itself: which operation names the facade recognizes,
 * that an unrecognized one is rejected together with the full allowed list, and that help works.
 * Live equivalence - that operation=X on the facade produces the same result as calling the
 * standalone X directly - is verified separately, against a running EDT workspace.
 * <p>
 * Unlike {@code infobase_admin}'s sync_control, none of the twelve standalones absorbed here
 * (project_metrics, dependency_graph, compare_configurations, detect_query_anti_patterns,
 * generate_health_snapshot, impact_analysis, object_summary, describe_db_tables,
 * semantic_metadata_search, audit_role_rights, find_rls_violations, sensitive_data_scan) declares
 * its own {@code operation}
 * or {@code action}-as-selector parameter that would collide with this facade's routing
 * {@code operation} - {@code impact_analysis}'s {@code action} is a free-form recommendation hint,
 * not a sub-operation switch, and {@code audit_role_rights}'s {@code mode} is a different key
 * entirely - so every operation here is a plain pass-through, with no remap needed.
 */
public class FacadeF3cTest
{
    // -- insights: tool metadata --

    @Test
    public void insightsName()
    {
        assertEquals("insights", new InsightsFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void insightsResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new InsightsFacadeTool().getResponseType());
    }

    @Test
    public void insightsDescriptionNotEmpty()
    {
        String description = new InsightsFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void insightsSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new InsightsFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"query\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- insights: routing table --

    @Test
    public void insightsMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new InsightsFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void insightsEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InsightsFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void insightsUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InsightsFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("project_metrics")); //$NON-NLS-1$
        assertTrue(result.contains("dependency_graph")); //$NON-NLS-1$
        assertTrue(result.contains("compare_configurations")); //$NON-NLS-1$
        assertTrue(result.contains("detect_query_anti_patterns")); //$NON-NLS-1$
        assertTrue(result.contains("generate_health_snapshot")); //$NON-NLS-1$
        assertTrue(result.contains("impact_analysis")); //$NON-NLS-1$
        assertTrue(result.contains("object_summary")); //$NON-NLS-1$
        assertTrue(result.contains("describe_db_tables")); //$NON-NLS-1$
        assertTrue(result.contains("semantic_metadata_search")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void insightsHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InsightsFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("project_metrics")); //$NON-NLS-1$
        assertTrue(result.contains("dependency_graph")); //$NON-NLS-1$
        assertTrue(result.contains("compare_configurations")); //$NON-NLS-1$
        assertTrue(result.contains("detect_query_anti_patterns")); //$NON-NLS-1$
        assertTrue(result.contains("generate_health_snapshot")); //$NON-NLS-1$
        assertTrue(result.contains("impact_analysis")); //$NON-NLS-1$
        assertTrue(result.contains("object_summary")); //$NON-NLS-1$
        assertTrue(result.contains("describe_db_tables")); //$NON-NLS-1$
        assertTrue(result.contains("semantic_metadata_search")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void insightsHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InsightsFacadeTool().execute(params);
        assertTrue(result.contains("project_metrics")); //$NON-NLS-1$
    }

    @Test
    public void insightsHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InsightsFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- security_audit: tool metadata --

    @Test
    public void securityAuditName()
    {
        assertEquals("security_audit", new SecurityAuditFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void securityAuditResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new SecurityAuditFacadeTool().getResponseType());
    }

    @Test
    public void securityAuditDescriptionNotEmpty()
    {
        String description = new SecurityAuditFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void securityAuditSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new SecurityAuditFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"roleName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"checks\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- security_audit: routing table --

    @Test
    public void securityAuditMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new SecurityAuditFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void securityAuditEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SecurityAuditFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void securityAuditUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SecurityAuditFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("audit_role_rights")); //$NON-NLS-1$
        assertTrue(result.contains("find_rls_violations")); //$NON-NLS-1$
        assertTrue(result.contains("sensitive_data_scan")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void securityAuditHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SecurityAuditFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("audit_role_rights")); //$NON-NLS-1$
        assertTrue(result.contains("find_rls_violations")); //$NON-NLS-1$
        assertTrue(result.contains("sensitive_data_scan")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void securityAuditHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SecurityAuditFacadeTool().execute(params);
        assertTrue(result.contains("audit_role_rights")); //$NON-NLS-1$
    }

    @Test
    public void securityAuditHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SecurityAuditFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- cross-facade sanity --

    @Test
    public void facadeNamesAreDistinctFromEachOther()
    {
        assertFalse(InsightsFacadeTool.NAME.equals(SecurityAuditFacadeTool.NAME));
    }
}
