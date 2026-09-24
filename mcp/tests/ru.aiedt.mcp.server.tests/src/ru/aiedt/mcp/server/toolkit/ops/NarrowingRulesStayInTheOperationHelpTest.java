/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * The sentences that say which scope and selector is a walk and which is a refusal left the
 * schema, and operation help is where a caller still reads them.
 */
public class NarrowingRulesStayInTheOperationHelpTest
{
    @Test
    public void queryHelpCarriesTheCombinationRulesTheSchemaDropped()
    {
        String help = help(new InsightsFacadeTool(), "detect_query_anti_patterns"); //$NON-NLS-1$
        assertTrue(help, help.contains("scope=project rejects every selector")); //$NON-NLS-1$
        assertTrue(help, help.contains("scope=method requires both")); //$NON-NLS-1$
        assertFalse(new InsightsFacadeTool().getInputSchema(),
            new InsightsFacadeTool().getInputSchema().contains("scope=project rejects every selector")); //$NON-NLS-1$
        assertFalse(new DetectQueryAntiPatternsTool().getInputSchema(),
            new DetectQueryAntiPatternsTool().getInputSchema().contains("scope=method requires both")); //$NON-NLS-1$
    }

    @Test
    public void metricsHelpCarriesTheNestedSubsystemRuleTheSchemaDropped()
    {
        String help = help(new InsightsFacadeTool(), "project_metrics"); //$NON-NLS-1$
        assertTrue(help, help.contains("Nested subsystems are included")); //$NON-NLS-1$
        assertFalse(new InsightsFacadeTool().getInputSchema(),
            new InsightsFacadeTool().getInputSchema().contains("Nested subsystems are included")); //$NON-NLS-1$
        assertFalse(new ProjectMetricsTool().getInputSchema(),
            new ProjectMetricsTool().getInputSchema().contains("Nested subsystems are included")); //$NON-NLS-1$
    }

    @Test
    public void rlsHelpCarriesTheSelectorRefusalTheSchemaDropped()
    {
        String help = help(new SecurityAuditFacadeTool(), "find_rls_violations"); //$NON-NLS-1$
        assertTrue(help, help.contains("rejects methodName")); //$NON-NLS-1$
        assertFalse(new SecurityAuditFacadeTool().getInputSchema(),
            new SecurityAuditFacadeTool().getInputSchema().contains("rejects methodName")); //$NON-NLS-1$
        assertFalse(new FindRlsViolationsTool().getInputSchema(),
            new FindRlsViolationsTool().getInputSchema().contains("rejects methodName")); //$NON-NLS-1$
    }

    @Test
    public void sensitiveHelpCarriesTheSubsystemRuleTheSchemaDropped()
    {
        String help = help(new SecurityAuditFacadeTool(), "sensitive_data_scan"); //$NON-NLS-1$
        assertTrue(help, help.contains("scope=subsystem requires subsystemName")); //$NON-NLS-1$
        assertFalse(new SecurityAuditFacadeTool().getInputSchema(),
            new SecurityAuditFacadeTool().getInputSchema().contains("scope=subsystem requires subsystemName")); //$NON-NLS-1$
        assertFalse(new SensitiveDataScanTool().getInputSchema(),
            new SensitiveDataScanTool().getInputSchema().contains("scope=subsystem requires subsystemName")); //$NON-NLS-1$
    }

    /**
     * The rule reads about both ends of an edge, and about a root, because that is what the walk
     * does. It said the target alone was checked, which was already untrue when the source of a
     * backward reference was looked at, and it said nothing about the mixed level carrying modules.
     */
    @Test
    public void graphHelpCarriesTheBothEndsRuleTheSchemaDoesNot()
    {
        String help = help(new InsightsFacadeTool(), "dependency_graph"); //$NON-NLS-1$
        assertTrue(help, help.contains("judged on BOTH ends")); //$NON-NLS-1$
        assertTrue(help, help.contains("a module end is kept on mixed")); //$NON-NLS-1$
        assertTrue("a root the level does not carry is named, not dropped in silence", //$NON-NLS-1$
            help.contains("internalRootsDropped")); //$NON-NLS-1$
        assertFalse("the rule that reads as if the target alone were checked", //$NON-NLS-1$
            help.contains("whose target is not a metadata object")); //$NON-NLS-1$
        assertFalse(new InsightsFacadeTool().getInputSchema(),
            new InsightsFacadeTool().getInputSchema().contains("judged on BOTH ends")); //$NON-NLS-1$
        assertFalse(new DependencyGraphTool().getInputSchema(),
            new DependencyGraphTool().getInputSchema().contains("judged on BOTH ends")); //$NON-NLS-1$
    }

    private static String help(IMcpTool facade, String topic)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", topic); //$NON-NLS-1$
        return facade.execute(params);
    }
}
