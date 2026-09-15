/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.HeavyTools;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.DiagnosticsFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.EditMetadataTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.InsightsFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.SecurityAuditFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.YaxunitTestsTool;

/**
 * Counts the calls that reach a heavy tool without the server knowing it beforehand, and requires
 * none.
 * <p>
 * The heap guard and the limit on concurrent heavy work are decided before a call starts, from the
 * name it arrived under. Under the Canonical preset that name is a facade's, and a facade does no
 * work itself - so every heavy operation reached through one arrived unguarded. Measured on
 * 2026-09-15, before the fix: at least 25 such pairs across seven facades.
 * </p>
 * <p>
 * The number is not written down here. A count in a test is a number to update; this walks the
 * facades, asks each where its operations go, and requires the answer to be known wherever the
 * destination is heavy.
 * </p>
 */
public class NoHeavyCallArrivesUnguardedTest
{
    /**
     * The facades that route onward, each with the operations whose destination is heavy.
     * <p>
     * Constructed rather than looked up: the catalogue is filled when the server starts, and under
     * the test harness it never does - the first draft of this test asked it and weighed nothing at
     * all.
     * </p>
     */
    private static final Map<IMcpTool, String[]> ROUTES_TO_CHECK = new LinkedHashMap<>();

    static
    {
        ROUTES_TO_CHECK.put(new InsightsFacadeTool(), new String[] {"project_metrics", "dependency_graph", //$NON-NLS-1$ //$NON-NLS-2$
            "compare_configurations", "compare_three_way", "detect_query_anti_patterns", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "generate_health_snapshot", "impact_analysis", "semantic_metadata_search"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ROUTES_TO_CHECK.put(new SecurityAuditFacadeTool(), new String[] {"audit_role_rights", //$NON-NLS-1$
            "find_rls_violations", "sensitive_data_scan"}); //$NON-NLS-1$ //$NON-NLS-2$
        ROUTES_TO_CHECK.put(new ConfigIoFacadeTool(), new String[] {"export_object", //$NON-NLS-1$
            "export_configuration_to_xml", "import_configuration_from_xml", //$NON-NLS-1$ //$NON-NLS-2$
            "import_configuration_from_binary"}); //$NON-NLS-1$
        ROUTES_TO_CHECK.put(new DiagnosticsFacadeTool(), new String[] {"clean_project", "revalidate_objects", //$NON-NLS-1$ //$NON-NLS-2$
            "validate_for_export"}); //$NON-NLS-1$
        ROUTES_TO_CHECK.put(new InfobaseAdminFacadeTool(), new String[] {"update_database"}); //$NON-NLS-1$
        ROUTES_TO_CHECK.put(new ExtensionWorkshopTool(), new String[] {"install_extension", //$NON-NLS-1$
            "uninstall_extension", "list_extension", "export_extension", "list_interceptors"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ROUTES_TO_CHECK.put(new EditMetadataTool(), new String[] {"rename_metadata_object"}); //$NON-NLS-1$
    }

    private static Map<String, String> call(String operation)
    {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("operation", operation); //$NON-NLS-1$
        return arguments;
    }

    /**
     * Every operation that reaches a heavy tool is known to be heavy before it starts.
     */
    @Test
    public void everyHeavyOperationIsHeavyThroughItsFacade()
    {
        List<String> unguarded = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<IMcpTool, String[]> entry : ROUTES_TO_CHECK.entrySet())
        {
            IMcpTool facade = entry.getKey();
            for (String operation : entry.getValue())
            {
                checked++;
                String routed = facade.routesTo(call(operation));
                boolean known = HeavyTools.isHeavy(facade.getName())
                    || (routed != null && HeavyTools.isHeavy(routed));
                if (!known)
                {
                    unguarded.add(facade.getName() + " operation=" + operation //$NON-NLS-1$
                        + " reaches " + routed); //$NON-NLS-1$
                }
            }
        }
        assertTrue("nothing was checked - the catalogue answered no facade at all", checked > 0); //$NON-NLS-1$
        assertEquals("these calls reach a heavy tool and the server does not know it: " //$NON-NLS-1$
            + unguarded, 0, unguarded.size());
    }

    /**
     * A facade answers the tool an operation reaches, by name.
     */
    @Test
    public void aFacadeNamesTheToolItRoutesTo()
    {
        IMcpTool insights = new InsightsFacadeTool();
        assertEquals("project_metrics", insights.routesTo(call("project_metrics"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("an operation this facade does itself routes nowhere", //$NON-NLS-1$
            null, insights.routesTo(call("help"))); //$NON-NLS-1$
    }

    /**
     * A missing selector does not mean no work: yaxunit_tests without a mode runs the tests.
     */
    @Test
    public void aMissingSelectorStillNamesWhatWillRun()
    {
        IMcpTool tests = new YaxunitTestsTool();
        assertEquals("run_yaxunit_tests", tests.routesTo(new LinkedHashMap<>())); //$NON-NLS-1$
        Map<String, String> debug = new LinkedHashMap<>();
        debug.put("mode", "debug"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("debug_yaxunit_tests", tests.routesTo(debug)); //$NON-NLS-1$
    }

    /**
     * A batch may carry anything, including the operation that walks every reference, so it counts
     * as heavy without looking inside.
     */
    @Test
    public void aBatchIsHeavyWithoutLookingInside()
    {
        IMcpTool edit = new EditMetadataTool();
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("batch", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        String routed = edit.routesTo(batch);
        assertTrue("a batch has to be treated as heavy", //$NON-NLS-1$
            routed != null && HeavyTools.isHeavy(routed));
    }
}
