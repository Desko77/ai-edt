/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
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
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
            // A host-wide autocrlf would rewrite the blob on the way back to the work tree, and a
            // restore that promises the committed bytes could not be told apart from one that
            // normalized them.
            git.getRepository().getConfig().setString("core", null, "autocrlf", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().save();
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
        catalog.register(new GitFileRestore());
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
     * A call without an operation is refused with every operation the tool knows.
     */
    @Test
    public void aCallWithoutAnOperationIsRefused()
    {
        String answer = new GitTool().execute(Map.of());
        assertTrue(answer, answer.contains("operation is required")); //$NON-NLS-1$
        for (String known : new String[] { "status", "branches", "log", "commit", "checkout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "show_file_changes", "revert_file", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "restore_merge_point" }) //$NON-NLS-1$
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
        assertTrue(answer.toString(), answer.get("error").getAsString().contains("not tracked")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A refusal for a missing author comes before the index is touched: the named file is not left
     * staged behind a refusal that says nothing was staged.
     */
    @Test
    public void aRefusedAuthorLeavesTheIndexAsItWas() throws Exception
    {
        Path file = repoRoot.resolve(PROJECT).resolve("src/NoAuthor.bsl"); //$NON-NLS-1$
        Files.writeString(file, "// no author\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            org.eclipse.jgit.lib.StoredConfig config = git.getRepository().getConfig();
            String name = config.getString("user", null, "name"); //$NON-NLS-1$ //$NON-NLS-2$
            String email = config.getString("user", null, "email"); //$NON-NLS-1$ //$NON-NLS-2$
            org.junit.Assume.assumeTrue("the machine's git configuration names an author", //$NON-NLS-1$
                name == null || name.isEmpty() || email == null || email.isEmpty());

            JsonObject answer = call("commit", "paths", PROJECT + "/src/NoAuthor.bsl", "message", "No author"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

            assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(answer.toString(), answer.get("error").getAsString().contains("names no author")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("the file must not be left staged", //$NON-NLS-1$
                git.status().call().getAdded().contains(PROJECT + "/src/NoAuthor.bsl")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Every named path is checked before any is staged: a refused second path does not leave the
     * first one in the index.
     */
    @Test
    public void aRefusedPathLeavesTheOthersUnstaged() throws Exception
    {
        Path first = repoRoot.resolve(PROJECT).resolve("src/First.bsl"); //$NON-NLS-1$
        Files.writeString(first, "// first\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            JsonObject answer = call("commit", "paths", PROJECT + "/src/First.bsl," + PROJECT + "/src/Nowhere.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "message", "Two", "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

            assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(answer.toString(), answer.get("error").getAsString().contains("Nothing was staged")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("the first path must not be left staged", //$NON-NLS-1$
                git.status().call().getAdded().contains(PROJECT + "/src/First.bsl")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(first);
        }
    }

    /**
     * The whole work tree, a directory or a pattern is an add-all by another name and is refused.
     */
    @Test
    public void anAddAllByAnotherNameIsRefused()
    {
        for (String path : new String[] { ".", "*", PROJECT + "/src", PROJECT + "/src/", PROJECT + "/src/*.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "../outside.bsl" }) //$NON-NLS-1$
        {
            JsonObject answer = call("commit", "paths", path, "message", "Everything", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertFalse(path + ": " + answer, answer.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(path + ": " + answer, answer.get("error").getAsString().contains("Nothing was staged")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * A tracked file deleted from the work tree is committed as a deletion, and the answer says so.
     */
    @Test
    public void aDeletedTrackedFileIsCommittedAsADeletion() throws Exception
    {
        Path file = repoRoot.resolve(PROJECT).resolve("src/Doomed.bsl"); //$NON-NLS-1$
        Files.writeString(file, "// doomed\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        JsonObject added = call("commit", "paths", PROJECT + "/src/Doomed.bsl", "message", "Doomed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(added.toString(), added.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(added.toString(), added.get("changes").toString().contains("added")); //$NON-NLS-1$ //$NON-NLS-2$
        Files.delete(file);

        JsonObject deleted = call("commit", "paths", PROJECT + "/src/Doomed.bsl", "message", "Gone", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(deleted.toString(), deleted.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(deleted.toString(), deleted.get("changes").toString().contains("deleted")); //$NON-NLS-1$ //$NON-NLS-2$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            org.eclipse.jgit.revwalk.RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            try (org.eclipse.jgit.treewalk.TreeWalk walk = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                git.getRepository(), PROJECT + "/src/Doomed.bsl", head.getTree())) //$NON-NLS-1$
            {
                assertNull("the file must be gone from the committed tree", walk); //$NON-NLS-1$
            }
        }
    }

    /**
     * Only the named paths enter the commit, even when the index holds something else already.
     */
    @Test
    public void aFileStagedBeforeTheCallStaysOutOfTheCommit() throws Exception
    {
        Path named = repoRoot.resolve(PROJECT).resolve("src/OnlyThis.bsl"); //$NON-NLS-1$
        Path staged = repoRoot.resolve(PROJECT).resolve("src/StagedElsewhere.bsl"); //$NON-NLS-1$
        Files.writeString(named, "// only this\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(staged, "// staged elsewhere\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            git.add().addFilepattern(PROJECT + "/src/StagedElsewhere.bsl").call(); //$NON-NLS-1$

            JsonObject commit = call("commit", "paths", PROJECT + "/src/OnlyThis.bsl", "message", "Only this", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

            assertTrue(commit.toString(), commit.get("success").getAsBoolean()); //$NON-NLS-1$
            org.eclipse.jgit.revwalk.RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            try (org.eclipse.jgit.treewalk.TreeWalk walk = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                git.getRepository(), PROJECT + "/src/StagedElsewhere.bsl", head.getTree())) //$NON-NLS-1$
            {
                assertNull("a file the call did not name must stay out of the commit", walk); //$NON-NLS-1$
            }
            assertTrue("and stay staged as it was", //$NON-NLS-1$
                git.status().call().getAdded().contains(PROJECT + "/src/StagedElsewhere.bsl")); //$NON-NLS-1$
            git.reset().addPath(PROJECT + "/src/StagedElsewhere.bsl").call(); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * A named file without a change is reported as unchanged; a call where no named file changed is
     * refused rather than committed empty.
     */
    @Test
    public void aCommitOfUnchangedFilesIsRefused()
    {
        JsonObject answer = call("commit", "paths", PROJECT + "/src/Module.bsl", "message", "Nothing new", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "authorName", "Probe", "authorEmail", "probe@example.invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.get("error").getAsString().contains("None of the named paths")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Paths are read the way the index spells them.
     */
    @Test
    public void aPathIsReadTheWayTheIndexSpellsIt()
    {
        assertEquals("a/b.bsl", GitTool.normalizePath(" ./a\\b.bsl ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(GitTool.pathRefusal("src/Module.bsl")); //$NON-NLS-1$
        assertNotNull(GitTool.pathRefusal("C:/repo/src/Module.bsl")); //$NON-NLS-1$
        assertNotNull(GitTool.pathRefusal("/src/Module.bsl")); //$NON-NLS-1$
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

    private static final String FORM = PROJECT + "/src/Catalogs/Items/Forms/ItemForm/Form.form"; //$NON-NLS-1$

    private static final String MDO = PROJECT + "/src/Catalogs/Items/Items.mdo"; //$NON-NLS-1$

    private static final String MODULE = PROJECT + "/src/TwoMethods.bsl"; //$NON-NLS-1$

    /**
     * A path inside the temporary repository, one segment at a time, so a forward slash is a
     * separator on every host.
     *
     * @param repoRelative the path relative to the repository root
     * @return the file
     */
    private static Path work(String repoRelative)
    {
        Path path = repoRoot;
        for (String segment : repoRelative.split("/")) //$NON-NLS-1$
        {
            path = path.resolve(segment);
        }
        return path;
    }

    /**
     * Stages the named paths and commits them. A blanket add would take the project's own
     * {@code .project}, and returning to the earlier commit would then delete it.
     *
     * @param git the repository
     * @param message the commit message
     * @param paths the work-tree paths to stage
     * @return the commit
     * @throws Exception when the repository cannot be written
     */
    private static RevCommit commitPaths(Git git, String message, String... paths) throws Exception
    {
        for (String path : paths)
        {
            git.add().addFilepattern(path).call();
        }
        return git.commit().setAuthor("Probe", "probe@example.invalid").setMessage(message).call(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The entry for one path in a file list.
     * <p>
     * A list read against the work tree also carries the files this class leaves untracked - the
     * project's own {@code .project} among them - so an entry is found by its path and not by its
     * place in the list.
     * </p>
     *
     * @param answer the tool answer holding the file list
     * @param repoPath the repository-relative path to find
     * @return the entry for that path
     */
    private static JsonObject entryFor(JsonObject answer, String repoPath)
    {
        for (JsonElement element : answer.getAsJsonArray("files")) //$NON-NLS-1$
        {
            JsonObject entry = element.getAsJsonObject();
            if (repoPath.equals(entry.get("filePath").getAsString())) //$NON-NLS-1$
            {
                return entry;
            }
        }
        throw new AssertionError(repoPath + " is not among " + answer); //$NON-NLS-1$
    }

    /**
     * Puts the repository back to a commit. Only the files these tests added are removed; a clean
     * of the whole work tree would delete the project's {@code .project}.
     *
     * @param head the commit to return to
     * @param addedPaths further paths this test created, removed as well; a path a later test does
     *            not know about would otherwise be left in the work tree as untracked
     * @throws Exception when the repository cannot be written
     */
    private static void backTo(String head, String... addedPaths) throws Exception
    {
        try (Git git = Git.open(repoRoot.toFile()))
        {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head).call();
        }
        deleteTree(work(PROJECT + "/src/Catalogs")); //$NON-NLS-1$
        Files.deleteIfExists(work(MODULE));
        for (String path : addedPaths)
        {
            deleteTree(work(path));
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Deletes a file or a directory tree the test created.
     *
     * @param path the file or directory
     * @throws Exception when a file cannot be deleted
     */
    private static void deleteTree(Path path) throws Exception
    {
        if (!Files.exists(path))
        {
            return;
        }
        try (var walk = Files.walk(path))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(each -> {
                try
                {
                    Files.deleteIfExists(each);
                }
                catch (java.io.IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * A line diff between two commits names the form and the metadata file, and the form's hunks
     * carry the line that changed.
     */
    @Test
    public void aLineDiffBetweenCommitsNamesHunksOfAFormAndMetadata() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        try
        {
            Files.createDirectories(work(FORM).getParent());
            Files.createDirectories(work(MDO).getParent());
            Files.writeString(work(FORM), "<form><title>Before</title></form>\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            Files.writeString(work(MDO), "<mdclass><name>Items</name></mdclass>\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            RevCommit first;
            RevCommit second;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                first = commitPaths(git, "Add form and metadata", FORM, MDO); //$NON-NLS-1$
                Files.writeString(work(FORM), "<form><title>After</title></form>\n", StandardCharsets.UTF_8); //$NON-NLS-1$
                Files.writeString(work(MDO), "<mdclass><name>Goods</name></mdclass>\n", StandardCharsets.UTF_8); //$NON-NLS-1$
                second = commitPaths(git, "Rename the title and the object", FORM, MDO); //$NON-NLS-1$
            }
            JsonObject listed = call("show_file_changes", "fromRef", first.getName(), "toRef", second.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(listed.toString(), listed.get("success").getAsBoolean()); //$NON-NLS-1$
            JsonArray files = listed.getAsJsonArray("files"); //$NON-NLS-1$
            assertEquals(listed.toString(), 2, files.size());
            for (JsonElement element : files)
            {
                JsonObject file = element.getAsJsonObject();
                assertTrue(file.toString(), file.get("linesAdded").getAsInt() >= 1); //$NON-NLS-1$
                assertTrue(file.toString(), file.get("linesRemoved").getAsInt() >= 1); //$NON-NLS-1$
            }
            JsonObject one = call("show_file_changes", "filePath", FORM, "fromRef", first.getName(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "toRef", second.getName()); //$NON-NLS-1$
            assertTrue(one.toString(), one.get("success").getAsBoolean()); //$NON-NLS-1$
            JsonArray hunks = one.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("hunks"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(one.toString(), hunks.size() >= 1);
            assertTrue(one.toString(), hunks.toString().contains("After")); //$NON-NLS-1$
        }
        finally
        {
            backTo(head);
        }
    }

    /**
     * Method granularity on a module names each procedure whose body changed, and the same
     * granularity on a form is refused.
     */
    @Test
    public void methodGranularityNamesTheTwoChangedProcedures() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        try
        {
            String before = "Procedure First()\n\tA = 1;\nEndProcedure\n\n" //$NON-NLS-1$
                + "Procedure Second()\n\tB = 2;\nEndProcedure\n"; //$NON-NLS-1$
            String after = "Procedure First()\n\tA = 10;\nEndProcedure\n\n" //$NON-NLS-1$
                + "Procedure Second()\n\tB = 20;\nEndProcedure\n"; //$NON-NLS-1$
            Files.createDirectories(work(MODULE).getParent());
            Files.writeString(work(MODULE), before, StandardCharsets.UTF_8);
            RevCommit first;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                first = commitPaths(git, "Two procedures", MODULE); //$NON-NLS-1$
            }
            Files.writeString(work(MODULE), after, StandardCharsets.UTF_8);
            JsonObject diff = call("show_file_changes", "filePath", MODULE, "fromRef", first.getName(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "granularity", "method"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(diff.toString(), diff.get("success").getAsBoolean()); //$NON-NLS-1$
            JsonArray hunks = diff.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("hunks"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(diff.toString(), 2, hunks.size());
            String names = hunks.toString();
            assertTrue(names, names.contains("First")); //$NON-NLS-1$
            assertTrue(names, names.contains("Second")); //$NON-NLS-1$

            JsonObject refused = call("show_file_changes", "filePath", FORM, "granularity", "method"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(refused.toString(), refused.get("error").getAsString().toLowerCase(java.util.Locale.ROOT).contains("bsl")); //$NON-NLS-1$
        }
        finally
        {
            backTo(head);
        }
    }

    /**
     * Putting a file back from the commit that holds it restores those bytes, in the line endings a
     * checkout of that path would write, and the work tree is clean again. A preview writes nothing.
     */
    @Test
    public void revertFileRestoresTheCommittedBytesAndAPreviewWritesNothing() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        byte[] original = "line\r\n".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Path form = work(FORM);
        try
        {
            Files.createDirectories(form.getParent());
            Files.write(form, original);
            RevCommit committed;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                committed = commitPaths(git, "Form with CRLF", FORM); //$NON-NLS-1$
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            Files.writeString(form, "changed\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$

            JsonObject preview = call("revert_file", "filePath", FORM, "fromRef", committed.getName(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(preview.toString(), preview.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(preview.get("dryRun").getAsBoolean()); //$NON-NLS-1$
            assertEquals(0, preview.get("bytesWritten").getAsInt()); //$NON-NLS-1$
            assertEquals("changed\r\n", Files.readString(form, StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertTrue(preview.toString(), preview.getAsJsonArray("files").get(0).getAsJsonObject() //$NON-NLS-1$
                .getAsJsonArray("hunks").size() >= 1); //$NON-NLS-1$

            JsonObject restored = call("revert_file", "filePath", FORM, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(committed.getName(), restored.get("restoredFrom").getAsString()); //$NON-NLS-1$
            assertEquals(original.length, restored.get("bytesWritten").getAsInt()); //$NON-NLS-1$
            assertEquals("CRLF", restored.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertEquals("clean", restored.get("fileStatus").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals(original, Files.readAllBytes(form));
            JsonObject status = call("status"); //$NON-NLS-1$
            assertFalse(status.toString(), status.get("modified").toString().contains("Form.form")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(restored.toString(), restored.get("advice").getAsString().contains("revalidate_objects")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            backTo(head);
        }
    }

    /**
     * A {@code .gitattributes} rule decides the line endings the file comes back with: the repository
     * stores LF, {@code eol=crlf} applies to the path, and the file lands CRLF with a clean status.
     * <p>
     * The module is committed in the form the rule keeps in the work tree, which is what the index
     * records the size of. Committing the LF form instead leaves the index holding five bytes and
     * the CRLF write six, and both git and JGit then report the path as modified from the size
     * alone, whatever the content normalizes to - measured 29.09 with the command-line git on the
     * same shape. A repository under such a rule never has the file in the other form, so the state
     * tested here is the one the operation meets.
     * </p>
     */
    @Test
    public void revertFileWritesTheLineEndingsTheRepositoryRuleAsksFor() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        String directory = PROJECT + "/src/eol"; //$NON-NLS-1$
        String attributes = directory + "/.gitattributes"; //$NON-NLS-1$
        String module = directory + "/Ruled.bsl"; //$NON-NLS-1$
        try
        {
            Files.createDirectories(work(directory));
            Files.writeString(work(attributes), "*.bsl text eol=crlf\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            Files.writeString(work(module), "line\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            RevCommit committed;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                committed = commitPaths(git, "Module under an eol rule", attributes, module); //$NON-NLS-1$
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            Files.writeString(work(module), "changed\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$

            JsonObject restored = call("revert_file", "filePath", module, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("CRLF", restored.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertArrayEquals("line\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(work(module))); //$NON-NLS-1$
            assertEquals("clean", restored.get("fileStatus").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(restored.toString(), restored.get("bytesWritten").getAsInt() > 0); //$NON-NLS-1$
        }
        finally
        {
            backTo(head, directory);
        }
    }

    /**
     * {@code core.autocrlf=true} decides the line endings just as an attribute does: the repository
     * stores LF, and the file comes back CRLF with a clean status.
     * <p>
     * The repository keeps {@code autocrlf=false} for the other tests in this class, which is also
     * what lets them tell the committed bytes apart from normalized ones; it is set back to that
     * value before the work tree is returned to the commit.
     * </p>
     */
    @Test
    public void revertFileUnderAutocrlfWritesCrlfAndTheStatusIsClean() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
            git.getRepository().getConfig().setString("core", null, "autocrlf", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().save();
        }
        String module = PROJECT + "/src/Auto.bsl"; //$NON-NLS-1$
        try
        {
            Files.writeString(work(module), "line\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            RevCommit committed;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                committed = commitPaths(git, "Module under autocrlf", module); //$NON-NLS-1$
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            Files.writeString(work(module), "changed\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$

            JsonObject restored = call("revert_file", "filePath", module, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals("CRLF", restored.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertArrayEquals("line\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(work(module))); //$NON-NLS-1$
            assertEquals("clean", restored.get("fileStatus").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            try (Git git = Git.open(repoRoot.toFile()))
            {
                git.getRepository().getConfig().setString("core", null, "autocrlf", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                git.getRepository().getConfig().save();
            }
            backTo(head, module);
        }
    }

    /**
     * A repository that states no line-ending rule keeps the file in the form it already has: the
     * commit holds LF, the work-tree file is CRLF, and CRLF is what comes back.
     * <p>
     * The index still holds the committed LF, so the file is listed as modified - which is what git
     * itself says of a CRLF file in a repository that stores LF and converts nothing. Writing the
     * commit's own ending instead would put a file nothing asked for into the work tree, and a
     * workspace full of CRLF modules is not the place to introduce one.
     * </p>
     */
    @Test
    public void revertFileKeepsTheFileEndingsWhenNoRuleIsStated() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        String module = PROJECT + "/src/Kept.bsl"; //$NON-NLS-1$
        try
        {
            Files.createDirectories(work(module).getParent());
            Files.writeString(work(module), "line\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            RevCommit committed;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                committed = commitPaths(git, "Module without a rule", module); //$NON-NLS-1$
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            Files.writeString(work(module), "changed\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$

            JsonObject restored = call("revert_file", "filePath", module, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("CRLF", restored.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertArrayEquals("line\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(work(module))); //$NON-NLS-1$
            assertEquals("modified", restored.get("fileStatus").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            backTo(head, module);
        }
    }

    /**
     * A file deleted from the work tree comes back in the line endings the repository rule names
     * for it, read from the commit's tree: one file under {@code eol=lf}, one under
     * {@code eol=crlf}, so the platform's own line separator cannot pass for the rule.
     */
    @Test
    public void revertFileOfADeletedFileFollowsTheRepositoryRule() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        String directory = PROJECT + "/src/gone"; //$NON-NLS-1$
        String attributes = directory + "/.gitattributes"; //$NON-NLS-1$
        String lf = directory + "/Lf.bsl"; //$NON-NLS-1$
        String crlf = directory + "/Crlf.txt"; //$NON-NLS-1$
        try
        {
            Files.createDirectories(work(directory));
            Files.writeString(work(attributes), "*.bsl text eol=lf\n*.txt text eol=crlf\n", //$NON-NLS-1$
                StandardCharsets.UTF_8);
            Files.writeString(work(lf), "line\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            Files.writeString(work(crlf), "line\r\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            RevCommit committed;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                committed = commitPaths(git, "Two files under eol rules", attributes, lf, crlf); //$NON-NLS-1$
            }
            Files.delete(work(lf));
            Files.delete(work(crlf));
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

            JsonObject restoredLf = call("revert_file", "filePath", lf, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(restoredLf.toString(), restoredLf.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("LF", restoredLf.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertArrayEquals("line\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(work(lf))); //$NON-NLS-1$

            JsonObject restoredCrlf = call("revert_file", "filePath", crlf, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(restoredCrlf.toString(), restoredCrlf.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("CRLF", restoredCrlf.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertArrayEquals("line\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(work(crlf))); //$NON-NLS-1$
        }
        finally
        {
            backTo(head, directory);
        }
    }

    /**
     * A file the attributes mark {@code -text} comes back byte for byte, as a checkout writes it,
     * whatever line endings the work-tree file had.
     */
    @Test
    public void revertFileOfAFileMarkedNotTextWritesTheStoredBytes() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        String directory = PROJECT + "/src/raw"; //$NON-NLS-1$
        String attributes = directory + "/.gitattributes"; //$NON-NLS-1$
        String module = directory + "/Mixed.bsl"; //$NON-NLS-1$
        byte[] stored = "first\r\nsecond\nthird\r\n".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        try
        {
            Files.createDirectories(work(directory));
            Files.writeString(work(attributes), "*.bsl -text\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            Files.write(work(module), stored);
            RevCommit committed;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                committed = commitPaths(git, "Module marked not text", attributes, module); //$NON-NLS-1$
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            Files.writeString(work(module), "changed\n", StandardCharsets.UTF_8); //$NON-NLS-1$

            JsonObject restored = call("revert_file", "filePath", module, "fromRef", committed.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("as stored", restored.get("lineEndings").getAsString()); //$NON-NLS-1$
            assertArrayEquals(stored, Files.readAllBytes(work(module)));
        }
        finally
        {
            backTo(head, directory);
        }
    }

    /**
     * A preview is answered while an editor holds the file with unsaved changes, and only the write
     * is refused then. The buffer is not something a headless run can dirty, so the decision the
     * operation takes is what is checked here.
     */
    @Test
    public void aPreviewIsAllowedWithAnUnsavedEditorAndTheWriteIsRefused()
    {
        String refused = GitFileRestore.writeRefusal(FORM, true, false);
        assertNotNull(refused);
        assertTrue(refused, refused.contains("unsaved changes")); //$NON-NLS-1$
        assertTrue(refused, refused.contains("Nothing was written")); //$NON-NLS-1$
        assertNull(GitFileRestore.writeRefusal(FORM, true, true));
        assertNull(GitFileRestore.writeRefusal(FORM, false, false));
        assertTrue(GitFileRestore.previewNote(true).contains("on disk")); //$NON-NLS-1$
        assertEquals("Nothing was written.", GitFileRestore.previewNote(false)); //$NON-NLS-1$
    }

    /**
     * A list answer stops at the limit and reports the whole count beside it, so a change larger
     * than the limit is still reported in full.
     */
    @Test
    public void aListAnswerStopsAtTheLimitAndReportsTheTotal() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        String[] modules = { PROJECT + "/src/One.bsl", PROJECT + "/src/Two.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            PROJECT + "/src/Three.bsl" }; //$NON-NLS-1$
        try
        {
            for (String module : modules)
            {
                Files.writeString(work(module), "before\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            }
            RevCommit first;
            RevCommit second;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                first = commitPaths(git, "Three modules", modules); //$NON-NLS-1$
                for (String module : modules)
                {
                    Files.writeString(work(module), "after\n", StandardCharsets.UTF_8); //$NON-NLS-1$
                }
                second = commitPaths(git, "All three changed", modules); //$NON-NLS-1$
            }
            JsonObject listed = call("show_file_changes", "fromRef", first.getName(), "toRef", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                second.getName(), "limit", "2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(listed.toString(), 2, listed.get("fileCount").getAsInt()); //$NON-NLS-1$
            assertEquals(listed.toString(), 3, listed.get("totalFileCount").getAsInt()); //$NON-NLS-1$
            assertTrue(listed.toString(), listed.get("truncated").getAsBoolean()); //$NON-NLS-1$

            JsonObject whole = call("show_file_changes", "fromRef", first.getName(), "toRef", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                second.getName()); //$NON-NLS-1$
            assertEquals(whole.toString(), 3, whole.get("fileCount").getAsInt()); //$NON-NLS-1$
            assertEquals(whole.toString(), 3, whole.get("totalFileCount").getAsInt()); //$NON-NLS-1$
            assertFalse(whole.toString(), whole.get("truncated").getAsBoolean()); //$NON-NLS-1$
        }
        finally
        {
            backTo(head, modules);
        }
    }

    /**
     * A changed file above the counting limit is listed by its size and says its lines were not
     * counted; naming that same file still answers with its line counts.
     */
    @Test
    public void aListGivesALargeFileByItsSizeAndNamingItCountsTheLines() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        String module = PROJECT + "/src/Large.bsl"; //$NON-NLS-1$
        long size = 5L * 1024L * 1024L + 16L;
        try
        {
            Files.createDirectories(work(module).getParent());
            Files.writeString(work(module), "y".repeat((int) size), StandardCharsets.UTF_8); //$NON-NLS-1$
            RevCommit first;
            try (Git git = Git.open(repoRoot.toFile()))
            {
                first = commitPaths(git, "Large module", module); //$NON-NLS-1$
            }
            Files.writeString(work(module), "x".repeat((int) size), StandardCharsets.UTF_8); //$NON-NLS-1$
            JsonObject listed = call("show_file_changes", "fromRef", first.getName()); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject entry = entryFor(listed, module);
            assertTrue(entry.toString(), entry.has("linesNotCounted")); //$NON-NLS-1$
            assertTrue(entry.toString(), entry.get("linesNotCounted").getAsBoolean()); //$NON-NLS-1$
            assertFalse(entry.toString(), entry.has("linesAdded")); //$NON-NLS-1$
            assertEquals(entry.toString(), size, entry.get("sizeInBytes").getAsLong()); //$NON-NLS-1$

            JsonObject named = call("show_file_changes", "filePath", module, "fromRef", first.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            JsonObject counted = entryFor(named, module);
            assertFalse(counted.toString(), counted.has("linesNotCounted")); //$NON-NLS-1$
            assertTrue(counted.toString(), counted.get("linesAdded").getAsInt() >= 1); //$NON-NLS-1$
        }
        finally
        {
            backTo(head, module);
        }
    }

    /**
     * A path the commit does not hold is refused, and nothing is written.
     */
    @Test
    public void revertFileRefusesAPathTheCommitDoesNotHold() throws Exception
    {
        JsonObject refused = call("revert_file", "filePath", PROJECT + "/src/missing.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.get("error").getAsString().contains("missing.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(work(PROJECT + "/src/missing.bsl"))); //$NON-NLS-1$
    }

    /**
     * With the write door unregistered, putting a file back is refused before the file is touched.
     */
    @Test
    public void anUnregisteredRevertDoorIsRefused() throws Exception
    {
        String head;
        try (Git git = Git.open(repoRoot.toFile()))
        {
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }
        Path module = work(PROJECT + "/src/Module.bsl"); //$NON-NLS-1$
        byte[] before = Files.readAllBytes(module);
        try
        {
            Files.writeString(module, "// touched\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            McpToolCatalog.getInstance().clear();
            String answer = new GitTool().execute(Map.of("operation", "revert_file", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "filePath", PROJECT + "/src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(answer, answer.contains("git_revert_file")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("is disabled and was not executed")); //$NON-NLS-1$
            assertArrayEquals("// touched\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module)); //$NON-NLS-1$
        }
        finally
        {
            backTo(head);
            assertArrayEquals(before, Files.readAllBytes(module));
        }
    }
}
