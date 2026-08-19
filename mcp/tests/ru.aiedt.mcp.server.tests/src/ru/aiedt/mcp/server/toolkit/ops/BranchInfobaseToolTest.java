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
 * Covers what {@code branch_infobase} promises before it reaches a workspace.
 * <p>
 * The parts that need a project are exercised through the workspace suite; what can be pinned here
 * is the contract a client reads - the name, the schema, and the refusals that happen before
 * anything is resolved.
 * </p>
 */
public class BranchInfobaseToolTest
{
    private final BranchInfobaseTool tool = new BranchInfobaseTool();

    /** The name is what the facade routes on and what the catalogue lists. */
    @Test
    public void itIsCalledWhatEverythingElseCallsIt()
    {
        assertEquals("branch_infobase", tool.getName()); //$NON-NLS-1$
        assertEquals(BranchInfobaseTool.NAME, tool.getName());
    }

    /** The description says what the tool prevents, not only what it stores. */
    @Test
    public void theDescriptionSaysWhatItIsFor()
    {
        String description = tool.getDescription();

        assertTrue("an agent choosing a tool needs the actions: " + description, //$NON-NLS-1$
            description.contains("bind") && description.contains("unbind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and why it matters, or it reads as bookkeeping: " + description, //$NON-NLS-1$
            description.contains("update_database")); //$NON-NLS-1$
    }

    /** Every argument a caller may pass is declared, or a client cannot pass it. */
    @Test
    public void theSchemaDeclaresEveryArgument()
    {
        String schema = tool.getInputSchema();

        for (String argument : new String[] {"projectName", "action", "branch", "applicationId"}) //$NON-NLS-1$
        {
            assertTrue("undeclared argument " + argument + " in " + schema, //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains('"' + argument + '"'));
        }
        assertTrue("projectName is the one thing that cannot be defaulted", //$NON-NLS-1$
            schema.contains("\"required\"")); //$NON-NLS-1$
    }

    /** The answer is structured, because a caller acts on the fields rather than reading prose. */
    @Test
    public void theAnswerIsStructured()
    {
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
    }

    /** Without a project name there is nothing to answer about, and it says so. */
    @Test
    public void aCallWithoutAProjectIsRefusedBeforeAnythingElse()
    {
        String answer = tool.execute(new HashMap<>());

        assertTrue("the refusal should name what is missing: " + answer, //$NON-NLS-1$
            answer.contains("projectName")); //$NON-NLS-1$
        assertFalse("a refusal must not report success", answer.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An empty project name is the same as none. */
    @Test
    public void anEmptyProjectNameIsNoProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(tool.execute(params).contains("projectName")); //$NON-NLS-1$
    }
}
