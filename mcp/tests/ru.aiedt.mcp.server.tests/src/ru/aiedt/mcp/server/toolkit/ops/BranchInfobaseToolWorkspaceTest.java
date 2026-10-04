/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.support.BranchInfobaseBook;

/**
 * What {@code branch_infobase} answers against a real workspace project when the bindings file
 * is there but cannot be read.
 */
public class BranchInfobaseToolWorkspaceTest
{
    private ClusterWorkspaceProbe probe;

    /**
     * A temporary workspace project for the tool to resolve.
     *
     * @throws Exception when the workspace refuses the project
     */
    @Before
    public void aProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtBranchInfobase"); //$NON-NLS-1$
    }

    /**
     * Removes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
    @After
    public void theProjectGoes() throws Exception
    {
        if (probe != null)
        {
            probe.close();
        }
    }

    /**
     * A list over an unreadable file is an error naming the file, not an empty answer - an empty
     * answer reads as "no rules", and acting on that is what the file exists to prevent.
     */
    @Test
    public void listRefusesAnUnreadableFile() throws Exception
    {
        Path file = bindingsFile();
        Files.createDirectories(file.getParent());
        Files.write(file, "this: [is: not: valid".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        Map<String, String> params = new HashMap<>();
        params.put("projectName", probe.project.getName()); //$NON-NLS-1$
        params.put("action", "list"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new BranchInfobaseTool().execute(params);

        assertFalse("an unreadable file must not read as an empty list: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue("the answer names the file: " + answer, answer.contains(BranchInfobaseBook.FILE)); //$NON-NLS-1$
        assertTrue("and where it lives: " + answer, answer.contains(".settings")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A list over a healthy file still answers the bindings it holds. */
    @Test
    public void listOnAReadableFileAnswersTheBindings()
    {
        assertNull(BranchInfobaseBook.bind(probe.project, "main", "app")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("projectName", probe.project.getName()); //$NON-NLS-1$
        params.put("action", "list"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new BranchInfobaseTool().execute(params);

        assertTrue(answer, answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"main\":\"app\"")); //$NON-NLS-1$
    }

    /**
     * Where the probe's bindings live on disk.
     *
     * @return the bindings file path
     */
    private Path bindingsFile()
    {
        return probe.project.getLocation().toFile().toPath()
            .resolve(".settings").resolve(BranchInfobaseBook.FILE); //$NON-NLS-1$
    }
}
