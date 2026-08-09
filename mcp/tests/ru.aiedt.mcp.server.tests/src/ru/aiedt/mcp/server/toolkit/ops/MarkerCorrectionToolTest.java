/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers what the correction tool decides before it reaches EDT.
 * <p>
 * The interesting part of applying someone else's correction is refusing to apply the wrong one: a
 * repair aimed at the wrong finding is harder to notice than one that never ran, because the project
 * still validates and something else changed. Targeting and refusals are checked here; performing a
 * correction needs a live workspace with a real marker and belongs to a manual pass.
 * </p>
 */
public class MarkerCorrectionToolTest
{
    private final MarkerCorrectionTool tool = new MarkerCorrectionTool();

    private static Map<String, String> params(String... pairs)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void aFindingHasToBeNamed()
    {
        // Without a check id there is no finding to correct, and picking one would be guessing.
        String noCheck = tool.execute(params("projectName", "Any")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noCheck, noCheck.contains("checkId")); //$NON-NLS-1$
        assertTrue(noCheck, noCheck.contains("\"success\": false") //$NON-NLS-1$
            || noCheck.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void aProjectHasToBeNamed()
    {
        String noProject = tool.execute(params("checkId", "some-check")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noProject, noProject.contains("projectName")); //$NON-NLS-1$
    }

    @Test
    public void onlyListAndApplyAreAccepted()
    {
        // An unknown operation must not fall through to a default that changes something.
        String bad = tool.execute(params("projectName", "Any", "checkId", "c", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "operation", "repair")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(bad, bad.contains("repair")); //$NON-NLS-1$
        assertTrue(bad, bad.contains("list") && bad.contains("apply")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theToolAnnouncesItselfAsJson()
    {
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertEquals("marker_corrections", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void theSchemaAsksForWhatTargetingNeeds()
    {
        String schema = tool.getInputSchema();
        for (String field : new String[] { "projectName", "checkId", "operation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "messageContains", "variant", "dryRun" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("the schema should expose " + field, schema.contains(field)); //$NON-NLS-1$
        }
    }

    @Test
    public void theDescriptionSaysWhyToPreferItOverAHandWrittenPatch()
    {
        // The point of the tool is that the correction comes from the check that raised the
        // problem. A description that does not say so leaves an agent inventing edits instead.
        String description = tool.getDescription();
        assertFalse(description.isEmpty());
        assertTrue(description, description.contains("check")); //$NON-NLS-1$
    }
}
