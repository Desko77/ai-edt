/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.GitRepositoryAccess;

/**
 * The git answers come from the repository itself, inside the IDE.
 *
 * <p>Development happens in EDT, and what git answers - what changed, what branch is this, what
 * is behind - belongs to the same window. JGit ships with both supported EDT releases, so the
 * answer comes from the repository rather than a git.exe that may not be installed. The guards
 * below cover what is decidable without a repository: the refusal wording for a call that names
 * no operation, and the schema the client builds from.</p>
 */
public class AGitAnswerComesFromTheRepositoryTest
{
    /**
     * A call without an operation is refused with the three the tool knows.
     */
    @Test
    public void aCallWithoutAnOperationIsRefused()
    {
        String answer = new GitTool().execute(java.util.Map.of());
        assertTrue(answer, answer.contains("operation is required")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("status")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("branches")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("log")); //$NON-NLS-1$
    }

    /**
     * An operation the tool does not know is refused with the known ones named.
     */
    @Test
    public void anUnknownOperationIsRefused()
    {
        String answer = new GitTool().execute(java.util.Map.of("operation", "merge")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("Unknown operation")); //$NON-NLS-1$
    }

    /**
     * A call without a project is refused before any repository is opened.
     */
    @Test
    public void aCallWithoutAProjectIsRefusedFirst()
    {
        String answer = new GitTool().execute(java.util.Map.of("operation", "status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("projectName must be provided")); //$NON-NLS-1$
    }

    /**
     * The schema advertises the operation, the project and the log's limit - the whole surface
     * a client builds a call from.
     */
    @Test
    public void theSchemaAdvertisesTheWholeSurface()
    {
        String schema = new GitTool().getInputSchema();
        assertTrue(schema, schema.contains("operation")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("projectName")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("limit")); //$NON-NLS-1$
    }

    /**
     * The access helper exists and is the one the tool asks.
     */
    @Test
    public void theAccessHelperExists()
    {
        assertNotNull(GitRepositoryAccess.class);
    }
}
