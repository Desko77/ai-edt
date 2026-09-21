/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.GitRepositoryAccess;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * The git answers come from the repository itself, inside the IDE.
 *
 * <p>Development happens in EDT, and what git answers - what changed, what branch is this, what
 * is behind - belongs to the same window. JGit ships with both supported EDT releases, so the
 * answer comes from the repository rather than a git.exe that may not be installed.</p>
 *
 * <p>The repository here is real: a temporary one initialised through JGit, with a workspace
 * project whose location sits inside it, so every operation - status, branches, log, commit,
 * checkout - runs against the same objects it would run against on a stand. The three git tools
 * are registered for the run because the two write doors are gate-checked by name and an
 * unregistered name reads as disabled.</p>
 */
public class AGitAnswerComesFromTheRepositoryTest
{
    private static final String PROJECT = "AiEdtGitProbe"; //$NON-NLS-1$

    private static Path repoRoot;

    private static IProject project;

    @BeforeClass
    public static void aRepositoryWithAProjectInside() throws Exception
    {
        repoRoot = Files.createTempDirectory("aiedt-git-probe"); //$NON-NLS-1$
        Path projectDir = repoRoot.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src")); //$NON-NLS-1$
        Files.writeString(projectDir.resolve("src/Module.bsl"), "// probe\n", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        try (Git git = Git.init().setDirectory(repoRoot.toFile()).call())
        {
            git.add().addFilepattern(PROJECT + "/src/Module.bsl").call(); //$NON-NLS-1$
            git.commit().setAuthor("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
                .setMessage("Probe sources").call(); //$NON-NLS-1$
        }
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    @AfterClass
    public static void theProjectAndTheRepositoryGo() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (repoRoot != null)
        {
            try (var walk = Files.walk(repoRoot))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Before
    public void theToolsAreRegistered()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(new GitTool());
        catalog.register(new GitCommitTool());
        catalog.register(new GitCheckoutTool());
    }

    @After
    public void theCatalogIsCleared()
    {
        McpToolCatalog.getInstance().clear();
    }

    private static JsonObject call(String operation, String... pairs)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", operation); //$NON-NLS-1$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            params.put(pairs[i], pairs[i + 1]);
        }
        return JsonParser.parseString(new GitTool().execute(params)).getAsJsonObject();
    }

    /**
     * A call without an operation is refused with the five the tool knows.
     */
    @Test
    public void aCallWithoutAnOperationIsRefused()
    {
        String answer = new GitTool().execute(Map.of());
        assertTrue(answer, answer.contains("operation is required")); //$NON-NLS-1$
        for (String known : new String[] { "status", "branches", "log", "commit", "checkout" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue(answer, answer.contains(known));
        }
    }

    /**
     * An operation the tool does not know is refused with the known ones named.
     */
    @Test
    public void anUnknownOperationIsRefused()
    {
        String answer = new GitTool().execute(Map.of("operation", "merge")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("Unknown operation")); //$NON-NLS-1$
    }

    /**
     * A call without a project is refused before any repository is opened.
     */
    @Test
    public void aCallWithoutAProjectIsRefusedFirst()
    {
        String answer = new GitTool().execute(Map.of("operation", "status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("projectName must be provided")); //$NON-NLS-1$
    }

    /**
     * The discovery walks upward from a path and follows what git itself would follow; a path
     * outside any repository says so instead of answering about the nearest unrelated one.
     */
    @Test
    public void theRepositoryIsFoundByWalkingUpward() throws Exception
    {
        try (GitRepositoryAccess.Resolved inside = GitRepositoryAccess.at(repoRoot.resolve(PROJECT).resolve("src").toString())) //$NON-NLS-1$
        {
            assertNull(inside.error, inside.error);
            assertNotNull(inside.git);
            assertEquals(repoRoot.toRealPath(), inside.repository.getWorkTree().toPath().toRealPath());
        }
        Path outside = Files.createTempDirectory("aiedt-no-repo"); //$NON-NLS-1$
        try (GitRepositoryAccess.Resolved none = GitRepositoryAccess.at(outside.toString()))
        {
            assertNotNull(none.error);
            assertTrue(none.error, none.error.contains("Not inside a git repository")); //$NON-NLS-1$
        }
        finally
        {
            Files.delete(outside);
        }
    }

    /**
     * Status reads the work tree and the index against HEAD; a fresh file is untracked, and the
     * branch is the one HEAD points at.
     */
    @Test
    public void statusReadsTheWorkTree() throws Exception
    {
        Path fresh = repoRoot.resolve(PROJECT).resolve("src/Fresh.bsl"); //$NON-NLS-1$
        Files.writeString(fresh, "// fresh\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        try
        {
            JsonObject status = call("status"); //$NON-NLS-1$
            assertTrue(status.toString(), status.get("success").getAsBoolean()); //$NON-NLS-1$
            assertFalse(status.get("isClean").getAsBoolean()); //$NON-NLS-1$
            assertTrue(status.toString(), status.get("untracked").toString().contains("src/Fresh.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(status.get("branch")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(fresh);
        }
    }

    /**
     * The log lists the commit the repository was started with, newest first, capped by the limit.
     */
    @Test
    public void logListsTheHistory()
    {
        JsonObject log = call("log", "limit", "5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(log.toString(), log.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(log.get("count").getAsInt() >= 1); //$NON-NLS-1$
        assertTrue(log.toString(), log.get("commits").toString().contains("Probe sources")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A commit stages what is named and nothing else, and answers with the sha it made.
     */
    @Test
    public void aCommitStagesWhatIsNamedAndNothingElse() throws Exception
    {
        Path named = repoRoot.resolve(PROJECT).resolve("src/Named.bsl"); //$NON-NLS-1$
        Path unnamed = repoRoot.resolve(PROJECT).resolve("src/Unnamed.bsl"); //$NON-NLS-1$
        Files.writeString(named, "// named\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(unnamed, "// unnamed\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        try
        {
            JsonObject commit = call("commit", "paths", PROJECT + "/src/Named.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "message", "Named only", "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            assertTrue(commit.toString(), commit.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(40, commit.get("sha").getAsString().length()); //$NON-NLS-1$
            assertEquals(1, commit.get("filesCount").getAsInt()); //$NON-NLS-1$
            JsonObject status = call("status"); //$NON-NLS-1$
            assertTrue(status.toString(), status.get("untracked").toString().contains("src/Unnamed.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(status.toString(), status.get("untracked").toString().contains("src/Named.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(unnamed);
        }
    }

    /**
     * A commit with no paths is refused: there is no add-all, and the refusal says why.
     */
    @Test
    public void aCommitWithoutPathsIsRefused()
    {
        JsonObject answer = call("commit", "message", "Everything"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.get("error").getAsString().contains("no add-all")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A path that names nothing in the work tree is refused before anything is staged.
     */
    @Test
    public void aCommitOfAMissingPathIsRefused()
    {
        JsonObject answer = call("commit", "paths", PROJECT + "/src/Nowhere.bsl", "message", "Nowhere"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.get("error").getAsString().contains("does not exist")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A checkout creates a branch and moves there, refuses to create one that exists, refuses to
     * switch to one that does not, and comes back naming what it left.
     */
    @Test
    public void aCheckoutMovesBetweenBranches()
    {
        JsonObject before = call("branches"); //$NON-NLS-1$
        String home = before.get("current").getAsString(); //$NON-NLS-1$
        JsonObject created = call("checkout", "branch", "probe/feature", "createBranch", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(home, created.get("previousBranch").getAsString()); //$NON-NLS-1$
        assertTrue(created.get("created").getAsBoolean()); //$NON-NLS-1$
        assertEquals("probe/feature", call("branches").get("current").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject again = call("checkout", "branch", "probe/feature", "createBranch", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertFalse(again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(again.toString(), again.get("error").getAsString().contains("already exists")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject nowhere = call("checkout", "branch", "probe/nowhere"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(nowhere.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(nowhere.toString(), nowhere.get("error").getAsString().contains("does not exist")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject back = call("checkout", "branch", home); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(back.toString(), back.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(home, call("branches").get("current").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The two write doors are their own registered names, so a preset can switch them; each
     * forwards to the facade's operation of the same name.
     */
    @Test
    public void theWriteDoorsForwardToTheFacade()
    {
        assertEquals("git_commit", new GitCommitTool().getName()); //$NON-NLS-1$
        assertEquals("git_checkout", new GitCheckoutTool().getName()); //$NON-NLS-1$
        String answer = new GitCommitTool().execute(Map.of("projectName", PROJECT, "message", "Everything")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer, answer.contains("no add-all")); //$NON-NLS-1$
        String checkout = new GitCheckoutTool().execute(Map.of("projectName", PROJECT)); //$NON-NLS-1$
        assertTrue(checkout, checkout.contains("checkout requires branch")); //$NON-NLS-1$
    }

    /**
     * With the write doors unregistered - which is what a preset that disables them looks like to
     * the gate - a commit is refused before a file is staged, and a read still answers.
     */
    @Test
    public void anUnregisteredWriteDoorIsRefusedAndReadsStillAnswer()
    {
        McpToolCatalog.getInstance().clear();
        String commit = new GitTool().execute(Map.of("operation", "commit", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "paths", PROJECT + "/src/Module.bsl", "message", "Gated")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(commit, commit.contains("git_commit")); //$NON-NLS-1$
        assertFalse(commit, commit.contains("\"sha\"")); //$NON-NLS-1$
        JsonObject status = call("status"); //$NON-NLS-1$
        assertTrue(status.toString(), status.get("success").getAsBoolean()); //$NON-NLS-1$
    }
}
