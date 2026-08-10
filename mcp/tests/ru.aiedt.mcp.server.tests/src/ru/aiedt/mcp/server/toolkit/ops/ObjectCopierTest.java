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
 * Covers what the copier decides before it reaches EDT.
 * <p>
 * The copying itself is EDT's and needs a workspace with two projects in it, so it belongs to a manual
 * pass. What is checked here is the part that is this tool's own: that it refuses a call it cannot
 * carry out, and that it tells an agent the one thing it will otherwise get wrong - that this is not
 * borrowing, and that the name of the copy is EDT's to choose.
 * </p>
 */
public class ObjectCopierTest
{
    private final ObjectCopier tool = new ObjectCopier();

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
    public void bothEndsHaveToBeNamed()
    {
        // Guessing either end would copy something somewhere, and an object created in the wrong
        // project is harder to notice than one that was never created.
        String noSource = tool.execute(params("objectFqn", "Document.X", //$NON-NLS-1$ //$NON-NLS-2$
            "targetProjectName", "Target")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noSource, noSource.contains("sourceProjectName")); //$NON-NLS-1$

        String noTarget = tool.execute(params("sourceProjectName", "Source", //$NON-NLS-1$ //$NON-NLS-2$
            "objectFqn", "Document.X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noTarget, noTarget.contains("targetProjectName")); //$NON-NLS-1$
    }

    @Test
    public void theObjectHasToBeNamed()
    {
        String noFqn = tool.execute(params("sourceProjectName", "Source", //$NON-NLS-1$ //$NON-NLS-2$
            "targetProjectName", "Target")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noFqn, noFqn.contains("objectFqn")); //$NON-NLS-1$
    }

    @Test
    public void theToolAnnouncesItselfAsJson()
    {
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertEquals("copy_object", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void theSchemaAsksForBothProjectsAndTheObject()
    {
        String schema = tool.getInputSchema();
        for (String field : new String[] { "sourceProjectName", "objectFqn", "targetProjectName" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("the schema should expose " + field, schema.contains(field)); //$NON-NLS-1$
        }
    }

    @Test
    public void theDescriptionSeparatesCopyingFromBorrowing()
    {
        // The confusion this tool exists to end: an agent reaching for borrow_object when the object
        // is not in the base configuration at all, and there is therefore nothing to intercept.
        String description = tool.getDescription();
        assertTrue(description, description.contains("borrow_object")); //$NON-NLS-1$
        assertTrue(description, description.contains("OWN")); //$NON-NLS-1$
    }

    @Test
    public void theDescriptionWarnsThatTheNameIsNotTheCallersChoice()
    {
        // EDT generates a name that does not collide in the target, so a caller that assumes the
        // source's name will address an object that is not there.
        assertTrue(tool.getDescription(), tool.getDescription().contains("name")); //$NON-NLS-1$
    }

    @Test
    public void theDescriptionDoesNotPromiseResolvedReferences()
    {
        // A copied object can point at types the target project does not have. Saying so is the
        // difference between a caller who validates afterwards and one who trusts the answer.
        String description = tool.getDescription();
        assertTrue(description, description.contains("NOT resolved") //$NON-NLS-1$
            || description.contains("not resolved")); //$NON-NLS-1$
        assertFalse(description.isEmpty());
    }
}
