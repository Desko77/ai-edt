/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Covers how widely a text search looks when the caller does not say.
 * <p>
 * The project used to be required, which pushed the choice onto a caller who often does not know it
 * - a workspace here holds a configuration beside its extensions - and made the wrong guess return
 * an empty result. An empty result is the most misleading answer a search can give: it reads as "this
 * text does not exist", when it means "it is not in the one project I happened to name".
 * </p>
 */
public class CodeTextSearcherScopeTest
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

    /** Omitting the project is a request to search everything, not a mistake to refuse. */
    @Test
    public void theProjectIsNoLongerDemanded()
    {
        String answer = new CodeTextSearcher().execute(args("query", "ПроцедураКоторойНет")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("omitting the project must not be refused as a missing argument: " + answer, //$NON-NLS-1$
            answer.contains("projectName is required")); //$NON-NLS-1$
    }

    /** The query is still required: without it there is nothing to look for anywhere. */
    @Test
    public void theQueryIsStillRequired()
    {
        String answer = new CodeTextSearcher().execute(args("projectName", "AnyProject")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("query is required")); //$NON-NLS-1$
    }

    /** A named project that does not exist is still an error, not a silent workspace-wide search. */
    @Test
    public void aNamedProjectThatIsNotThereIsStillAnError()
    {
        String answer = new CodeTextSearcher()
            .execute(args("projectName", "NoSuchProjectAnywhere", "query", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("naming a project that is not there must be said, not widened silently: " //$NON-NLS-1$
            + answer, answer.startsWith("Error:")); //$NON-NLS-1$
    }

    /** What the caller may omit has to be visible in the schema, or nobody will omit it. */
    @Test
    public void theSchemaSaysTheProjectMayBeOmitted()
    {
        String schema = new CodeTextSearcher().getInputSchema();

        assertTrue("projectName must not be listed as required: " + schema, //$NON-NLS-1$
            !schema.contains("\"required\":[\"projectName\"")); //$NON-NLS-1$
        assertTrue("and the description must say what omitting it does", //$NON-NLS-1$
            schema.contains("every open project")); //$NON-NLS-1$
    }
}
