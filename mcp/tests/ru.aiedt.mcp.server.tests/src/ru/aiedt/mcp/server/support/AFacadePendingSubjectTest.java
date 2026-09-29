/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.DiagnosticsFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InsightsFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.ProjectMetricsTool;
import ru.aiedt.mcp.server.toolkit.ops.SecurityAuditFacadeTool;

/**
 * The subject a call runs under in the generic Pending flow is the tool that does the work, not
 * the name the request arrived under.
 * <p>
 * Read by name alone, a facade is not listed in {@link GenericPending} while the tool it routes to
 * is, so the same work answered {@code Pending} when called directly and held the request thread
 * when called through the facade. The subject closes that: the facade reads the operation it
 * dispatches by and names the delegate. An operation that routes to nothing listed - {@code
 * impact_analysis}, {@code help} - keeps running inline, and a tool listed under its own name names
 * itself.
 * </p>
 */
public class AFacadePendingSubjectTest
{
    @Test
    public void aListedToolNamesItselfAsItsSubject()
    {
        assertEquals("project_metrics", //$NON-NLS-1$
            ToolRoad.pendingSubject(new ProjectMetricsTool(), Map.of()));
    }

    @Test
    public void aFacadeNamesTheDelegateItsOperationRoutesTo()
    {
        assertEquals("project_metrics", //$NON-NLS-1$
            ToolRoad.pendingSubject(new InsightsFacadeTool(),
                Map.of("operation", "project_metrics"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("find_rls_violations", //$NON-NLS-1$
            ToolRoad.pendingSubject(new SecurityAuditFacadeTool(),
                Map.of("operation", "find_rls_violations"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("validate_for_export", //$NON-NLS-1$
            ToolRoad.pendingSubject(new DiagnosticsFacadeTool(),
                Map.of("operation", "validate_for_export"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("list_extension", //$NON-NLS-1$
            ToolRoad.pendingSubject(new ExtensionWorkshopTool(),
                Map.of("operation", "list_extension"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An operation outside the list has no subject, whether it routes to an unlisted tool or to
     * none at all, so it runs the way it did before the subject existed.
     */
    @Test
    public void anOperationOutsideTheListHasNoSubject()
    {
        assertNull(ToolRoad.pendingSubject(new InsightsFacadeTool(),
            Map.of("operation", "impact_analysis"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ToolRoad.pendingSubject(new InsightsFacadeTool(),
            Map.of("operation", "object_summary"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ToolRoad.pendingSubject(new InsightsFacadeTool(), Map.of("operation", "help"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ToolRoad.pendingSubject(new InsightsFacadeTool(), Map.of()));
    }

    /**
     * The same work reached through either door lands on one key: the facade's operation argument
     * selects which tool runs, and the subject already carries that choice.
     */
    @Test
    public void eitherDoorOntoTheSameWorkComputesOneKey()
    {
        Map<String, String> throughFacade = new HashMap<>();
        throughFacade.put("operation", "project_metrics"); //$NON-NLS-1$ //$NON-NLS-2$
        throughFacade.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        throughFacade.put("checks", "all"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> direct = new HashMap<>();
        direct.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        direct.put("checks", "all"); //$NON-NLS-1$ //$NON-NLS-2$

        IMcpTool facade = new InsightsFacadeTool();
        String subject = ToolRoad.pendingSubject(facade, throughFacade);
        assertEquals("project_metrics", subject); //$NON-NLS-1$
        assertEquals(PendingWorkRegistry.computeRunKey(subject,
            GenericPending.canonicalParams(throughFacade)),
            PendingWorkRegistry.computeRunKey("project_metrics", //$NON-NLS-1$
                GenericPending.canonicalParams(direct)));
    }

    /**
     * A different operation of the same facade is a different subject, and so a different run.
     */
    @Test
    public void twoOperationsOfOneFacadeAreTwoSubjects()
    {
        IMcpTool facade = new InsightsFacadeTool();
        String metrics = ToolRoad.pendingSubject(facade, Map.of("operation", "project_metrics")); //$NON-NLS-1$ //$NON-NLS-2$
        String graph = ToolRoad.pendingSubject(facade, Map.of("operation", "dependency_graph")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("project_metrics", metrics); //$NON-NLS-1$
        assertEquals("dependency_graph", graph); //$NON-NLS-1$
    }
}
