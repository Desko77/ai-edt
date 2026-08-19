/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers the contract of the three-way comparison: its shape, and above all what it refuses to do.
 * <p>
 * The comparison itself needs a workspace with a project in it, so the counts are measured on a
 * stand rather than here. What is pinned here is the boundary: this tool reads. A merge writes into
 * a configuration and is not undone by a button, so "it cannot merge" is a property worth a test
 * that fails the day someone adds one.
 * </p>
 */
public class ThreeWayComparisonToolTest
{
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void itIsNamedForWhatItDoesAndAnswersJson()
    {
        ThreeWayComparisonTool tool = new ThreeWayComparisonTool();

        assertEquals("compare_three_way", tool.getName()); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertNotNull(tool.getDescription());
    }

    /**
     * The description is what an agent reads before choosing the tool, so the one thing it must not
     * leave open is whether this writes.
     */
    @Test
    public void theDescriptionSaysItOnlyReads()
    {
        String description = new ThreeWayComparisonTool().getDescription().toLowerCase();

        assertTrue("the description must say it reads only: " + description, //$NON-NLS-1$
            description.contains("reads only")); //$NON-NLS-1$
        assertTrue("and that it never merges: " + description, //$NON-NLS-1$
            description.contains("never merges")); //$NON-NLS-1$
    }

    /** Three sides means three inputs, and the third one is what makes it three-way. */
    @Test
    public void theSchemaNamesAllThreeSides()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();

        assertTrue(schema.contains("projectName")); //$NON-NLS-1$
        assertTrue(schema.contains("otherPath")); //$NON-NLS-1$
        assertTrue(schema.contains("ancestorPath")); //$NON-NLS-1$
        assertTrue("the ancestor is what separates this from a plain diff", //$NON-NLS-1$
            schema.contains("COMMON_ANCESTOR")); //$NON-NLS-1$
    }

    /**
     * Missing arguments are refused before anything is asked of the environment, and the refusal
     * carries a reason rather than an empty answer.
     */
    @Test
    public void aRequestWithoutItsSidesIsRefusedWithAReason()
    {
        String answer = new ThreeWayComparisonTool().execute(args());

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name what is missing: " + answer, //$NON-NLS-1$
            answer.contains("required")); //$NON-NLS-1$
    }

    /** A path that is not a directory is named as such, not reported as an empty comparison. */
    @Test
    public void aPathThatIsNotADirectoryIsNamed()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "AnyProject", "otherPath", "no such directory here")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("a missing path must not read as a finished comparison", //$NON-NLS-1$
            answer.contains("\"nodes\"")); //$NON-NLS-1$
    }

    /**
     * The merge entry points must stay out of reach. Checked against the class rather than the
     * source text so the test still holds if the file is reorganised.
     */
    @Test
    public void nothingHereCanStartAMerge()
    {
        for (java.lang.reflect.Method m : ThreeWayComparisonTool.class.getDeclaredMethods())
        {
            String name = m.getName().toLowerCase();
            assertFalse("no method here may be about merging: " + m.getName(), //$NON-NLS-1$
                name.contains("merge")); //$NON-NLS-1$
        }
        for (java.lang.reflect.Method m : ru.aiedt.mcp.server.support.BmComparisonHelper.class
            .getDeclaredMethods())
        {
            String name = m.getName().toLowerCase();
            assertFalse("the helper must not grow a merge path: " + m.getName(), //$NON-NLS-1$
                name.startsWith("merge") || name.contains("startmerge")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
