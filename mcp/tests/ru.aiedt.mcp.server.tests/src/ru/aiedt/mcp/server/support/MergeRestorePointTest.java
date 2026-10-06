/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.GitFileRestore;
import ru.aiedt.mcp.server.toolkit.ops.GitMergePointCreateTool;
import ru.aiedt.mcp.server.toolkit.ops.GitMergePointDeleteTool;
import ru.aiedt.mcp.server.toolkit.ops.GitTool;
import ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool;

/**
 * A changing three-way comparison records a restore point first, and that point puts the project
 * files back.
 *
 * <p>The repository and the project are real. The comparison itself cannot run here - the other
 * side is not a configuration - so the tests show the point is taken before that attempt, that a
 * point which cannot be taken stops the call before the attempt, and that restoring the point
 * returns the files and leaves {@code git diff} empty. Taking and dropping a point answer to doors
 * of their own, a restore that stops halfway still cleans up, and a file that already matches the
 * point is not rewritten.</p>
 */
public class MergeRestorePointTest
{
    private static final String GIT_PROJECT = "AiEdtMergeProbe"; //$NON-NLS-1$

    private static final String COPY_PROJECT = "AiEdtMergeCopy"; //$NON-NLS-1$

    private static final String PROBE = "// probe\n"; //$NON-NLS-1$

    private static final String MERGED = "// merged\n"; //$NON-NLS-1$

    private static final String PLATFORM = "platform=before\n"; //$NON-NLS-1$

    private static final String PLATFORM_AFTER = "platform=after\n"; //$NON-NLS-1$

    private static Path repoRoot;

    private static Path copyRoot;

    private static Path store;

    private static IProject gitProject;

    private static IProject copyProject;

    private static String head;

    @BeforeClass
    public static void aRepositoryAndAProjectOutsideOne() throws Exception
    {
        store = Files.createTempDirectory("aiedt-merge-store"); //$NON-NLS-1$
        MergeRestorePoint.storageRoot = store;

        repoRoot = Files.createTempDirectory("aiedt-merge-repo"); //$NON-NLS-1$
        Path projectDir = repoRoot.resolve(GIT_PROJECT);
        Files.createDirectories(projectDir.resolve("src")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("DT-INF")); //$NON-NLS-1$
        Files.writeString(projectDir.resolve("src/Module.bsl"), PROBE, StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(projectDir.resolve("DT-INF/PROJECT.PMF"), PLATFORM, StandardCharsets.UTF_8); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(repoRoot.toFile()).call())
        {
            git.getRepository().getConfig().setString("core", null, "autocrlf", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().save();
        }
        // Eclipse writes .project when the project opens. That file, and DT-INF, have to be in the
        // commit: JGit's diff lists every work-tree file the index does not have.
        gitProject = openProject(GIT_PROJECT, projectDir);
        try (Git git = Git.open(repoRoot.toFile()))
        {
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setAuthor("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
                .setMessage("Probe sources").call(); //$NON-NLS-1$
            head = git.getRepository().resolve("HEAD").getName(); //$NON-NLS-1$
        }

        copyRoot = Files.createTempDirectory("aiedt-merge-copy"); //$NON-NLS-1$
        Files.createDirectories(copyRoot.resolve("src")); //$NON-NLS-1$
        Files.createDirectories(copyRoot.resolve("DT-INF")); //$NON-NLS-1$
        Files.writeString(copyRoot.resolve("src/Module.bsl"), PROBE, StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(copyRoot.resolve("DT-INF/PROJECT.PMF"), PLATFORM, StandardCharsets.UTF_8); //$NON-NLS-1$
        copyProject = openProject(COPY_PROJECT, copyRoot);
        try (GitRepositoryAccess.Resolved resolved = GitRepositoryAccess.of(copyProject))
        {
            assertNotNull("the copy project has to sit outside git so the point is a directory copy", //$NON-NLS-1$
                resolved.error);
        }
    }

    @AfterClass
    public static void theProjectsGo() throws Exception
    {
        MergeRestorePoint.storageRoot = null;
        deleteProject(gitProject);
        deleteProject(copyProject);
        deleteTree(repoRoot);
        deleteTree(copyRoot);
        deleteTree(store);
    }

    @Before
    public void theWorkTreeIsTheCommittedOne() throws Exception
    {
        Files.writeString(module(gitProject), PROBE, StandardCharsets.UTF_8);
        Files.writeString(platform(gitProject), PLATFORM, StandardCharsets.UTF_8);
        Files.deleteIfExists(added(gitProject));
        Files.writeString(module(copyProject), PROBE, StandardCharsets.UTF_8);
        Files.writeString(platform(copyProject), PLATFORM, StandardCharsets.UTF_8);
        Files.deleteIfExists(added(copyProject));
        MergeRestorePoint.refreshCalls = 0;
        MergeRestorePoint.contentComparisons = 0;
        MergeRestorePoint.refreshFailureForTest = null;
        MergeRestorePoint.deleteRefusalForTest = null;
        GitRepositoryAccess.deleteRefResultForTest = null;
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(new GitTool());
        catalog.register(new GitFileRestore());
        catalog.register(new GitMergePointCreateTool());
        catalog.register(new GitMergePointDeleteTool());
    }

    @After
    public void theCatalogIsCleared()
    {
        McpToolCatalog.getInstance().clear();
    }

    /**
     * MERGE and UPDATE_KEEPING_OURS record a point before the comparison runs, and a report does
     * not. The comparison then refuses the missing delivery, which is how this shows the point
     * came first: the refusal is the comparison's, and the files and HEAD are still the originals.
     */
    @Test
    public void aChangingComparisonRecordsAPointBeforeItMerges() throws Exception
    {
        int before = MergeRestorePoint.idsFor(GIT_PROJECT).size();
        String report = compare(GIT_PROJECT, "REPORT"); //$NON-NLS-1$
        assertEquals(before, MergeRestorePoint.idsFor(GIT_PROJECT).size());
        assertFalse(report, report.contains("mergeRestorePoint")); //$NON-NLS-1$

        String merge = compare(GIT_PROJECT, "MERGE"); //$NON-NLS-1$
        JsonObject answer = json(merge);
        assertFalse(merge, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(merge, merge.contains("No merge was started")); //$NON-NLS-1$
        assertTrue(merge, merge.contains("not a directory")); //$NON-NLS-1$
        assertTrue(merge, answer.has("mergeRestorePoint")); //$NON-NLS-1$
        assertTrue(merge, merge.contains(MergeRestorePoint.INFOBASE_NOT_ROLLED_BACK));
        assertEquals(before + 1, MergeRestorePoint.idsFor(GIT_PROJECT).size());
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
        assertHeadUnmoved();

        String keeping = compare(GIT_PROJECT, "UPDATE_KEEPING_OURS"); //$NON-NLS-1$
        assertTrue(keeping, keeping.contains("mergeRestorePoint")); //$NON-NLS-1$
        assertTrue(keeping, keeping.contains("not a directory")); //$NON-NLS-1$
        assertEquals(before + 2, MergeRestorePoint.idsFor(GIT_PROJECT).size());
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
        assertHeadUnmoved();
    }

    /**
     * A point that cannot be stored stops the merge before the comparison, so a bad delivery path
     * is never reached and the project files stay as they were.
     */
    @Test
    public void aPointThatCannotBeTakenStopsTheMergeBeforeAnyFileChanges() throws Exception
    {
        Path blocked = Files.createTempFile("aiedt-merge-block", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Path saved = MergeRestorePoint.storageRoot;
        MergeRestorePoint.storageRoot = blocked;
        try
        {
            byte[] before = Files.readAllBytes(module(copyProject));
            for (String intent : new String[] { "MERGE", "UPDATE_KEEPING_OURS" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String answer = compare(COPY_PROJECT, intent);
                assertTrue(answer, answer.contains("No merge was started")); //$NON-NLS-1$
                assertTrue(answer, answer.contains("\"mergeStarted\":false")); //$NON-NLS-1$
                assertFalse(answer, answer.contains("not a directory")); //$NON-NLS-1$
                assertFalse(answer, answer.contains("mergeRestorePoint")); //$NON-NLS-1$
                assertArrayEquals(before, Files.readAllBytes(module(copyProject)));
            }
        }
        finally
        {
            MergeRestorePoint.storageRoot = saved;
            Files.deleteIfExists(blocked);
        }
    }

    /**
     * Restoring the point writes the recorded bytes back, drops a file the merge added, and leaves
     * {@code git diff} empty. HEAD was not moved when the point was taken.
     */
    @Test
    public void restoringAPointPutsTheFilesBackAndLeavesGitDiffEmpty() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("git", created.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        String commit = created.get("commit").getAsString(); //$NON-NLS-1$
        assertHeadUnmoved();
        try (Git git = Git.open(repoRoot.toFile()))
        {
            ObjectId ref = git.getRepository().resolve(GitRepositoryAccess.restoreRef(pointId));
            assertNotNull(ref);
            assertEquals(commit, ref.getName());
            assertTrue(git.diff().call().toString(), git.diff().call().isEmpty());
        }

        Files.writeString(module(gitProject), MERGED, StandardCharsets.UTF_8);
        Files.writeString(platform(gitProject), PLATFORM_AFTER, StandardCharsets.UTF_8);
        Files.writeString(added(gitProject), "// added\n", StandardCharsets.UTF_8); //$NON-NLS-1$

        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(restored.toString(), restored.get("note").getAsString().contains(MergeRestorePoint.INFOBASE_NOT_ROLLED_BACK)); //$NON-NLS-1$
        assertTrue(restored.toString(), restored.get("restoredFiles").toString().contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("restoredFiles").toString().contains("DT-INF/PROJECT.PMF")); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
        assertArrayEquals(PLATFORM.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(platform(gitProject)));
        assertFalse(Files.exists(added(gitProject)));
        try (Git git = Git.open(repoRoot.toFile()))
        {
            assertTrue(git.diff().call().toString(), git.diff().call().isEmpty());
        }
        assertHeadUnmoved();
    }

    /**
     * Without a repository the point is a copy of the project directory, and restoring that copy
     * puts the files back.
     */
    @Test
    public void aCopyPointRestoresTheProjectWhenThereIsNoRepository() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("copy", created.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Files.isDirectory(Path.of(created.get("copyPath").getAsString()))); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$

        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        Files.writeString(added(copyProject), "// added\n", StandardCharsets.UTF_8); //$NON-NLS-1$

        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(restored.toString(), restored.get("note").getAsString().contains(MergeRestorePoint.INFOBASE_NOT_ROLLED_BACK)); //$NON-NLS-1$
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(copyProject)));
        assertFalse(Files.exists(added(copyProject)));
    }

    /** The store is the directory beside the workspace, not a folder inside the project. */
    @Test
    public void theStoreSitsBesideTheWorkspace()
    {
        Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        assertEquals(workspace.getParent().resolve(MergeRestorePoint.DIRECTORY_NAME),
            MergeRestorePoint.defaultStorageRoot());
    }

    /**
     * Putting the files back passes the write door of revert_file, so a preset that switched that
     * door off refuses the restore before a byte is written.
     */
    @Test
    public void anUnregisteredRestoreDoorWritesNothing() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        Files.writeString(module(gitProject), MERGED, StandardCharsets.UTF_8);
        McpToolCatalog.getInstance().clear();
        String answer = new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", created.get("pointId").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer, answer.contains("git_revert_file")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("is disabled and was not executed")); //$NON-NLS-1$
        assertArrayEquals(MERGED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
    }

    /**
     * Taking a point answers to a door of its own: with that door unregistered - which is what a
     * preset that disables it looks like to the gate - the create call is refused even while the
     * delete door is registered, and nothing is recorded.
     */
    @Test
    public void anUnregisteredCreateDoorRecordsNothing() throws Exception
    {
        dropEveryRestorePoint();
        Files.writeString(module(gitProject), MERGED, StandardCharsets.UTF_8);
        int before = MergeRestorePoint.idsFor(GIT_PROJECT).size();
        McpToolCatalog.getInstance().clear();
        McpToolCatalog.getInstance().register(new GitMergePointDeleteTool());
        String answer = new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT)); //$NON-NLS-1$
        assertTrue(answer, answer.contains("git_create_merge_restore_point")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("is disabled and was not executed")); //$NON-NLS-1$
        assertEquals(before, MergeRestorePoint.idsFor(GIT_PROJECT).size());
        assertNull(noRestoreRefs());
        assertArrayEquals(MERGED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
    }

    /**
     * Dropping a point answers to a door of its own as well: with only that door unregistered, the
     * delete call is refused and the point stays - both the ref and the index entry.
     */
    @Test
    public void anUnregisteredDeleteDoorDropsNothing() throws Exception
    {
        McpToolCatalog.getInstance().unregister(GitMergePointDeleteTool.DOOR);
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        String answer = new GitTool().execute(Map.of("operation", "delete_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("git_delete_merge_restore_point")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("is disabled and was not executed")); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            assertNotNull(git.getRepository().resolve(GitRepositoryAccess.restoreRef(pointId)));
        }
    }

    /**
     * Deleting a git point removes the ref and the index entry, touches no project file, and the
     * id no longer resolves for the project.
     */
    @Test
    public void deletingAGitPointRemovesTheRefAndTheIndexEntry() throws Exception
    {
        int before = MergeRestorePoint.idsFor(GIT_PROJECT).size();
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            assertNotNull(git.getRepository().resolve(GitRepositoryAccess.restoreRef(pointId)));
        }
        JsonObject dropped = json(new GitTool().execute(Map.of("operation", "delete_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dropped.toString(), dropped.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("git", dropped.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            assertNull(git.getRepository().resolve(GitRepositoryAccess.restoreRef(pointId)));
        }
        assertFalse(Files.exists(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, MergeRestorePoint.idsFor(GIT_PROJECT).size());
    }

    /**
     * Deleting a copy point removes the copied directory and the index entry.
     */
    @Test
    public void deletingACopyPointRemovesTheCopyAndTheIndexEntry() throws Exception
    {
        int before = MergeRestorePoint.idsFor(COPY_PROJECT).size();
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Path copyDir = Path.of(created.get("copyPath").getAsString()); //$NON-NLS-1$
        assertTrue(Files.isDirectory(copyDir));
        JsonObject dropped = json(new GitTool().execute(Map.of("operation", "delete_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dropped.toString(), dropped.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("copy", dropped.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(copyDir));
        assertFalse(Files.exists(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, MergeRestorePoint.idsFor(COPY_PROJECT).size());
    }

    /**
     * A point belongs to the project it was taken for: both the restore and the delete of another
     * project's point are refused and nothing changes on either side.
     */
    @Test
    public void aPointOfAnotherProjectIsRefusedForRestoreAndDelete() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        String restore = new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restore, restore.contains("No merge restore point")); //$NON-NLS-1$
        assertArrayEquals(MERGED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(copyProject)));
        String drop = new GitTool().execute(Map.of("operation", "delete_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(drop, drop.contains("No merge restore point")); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A restore that stops halfway - here one file of the point is missing from the copy - still
     * names the file it did not put back, still removes the files the point does not hold, and
     * still refreshes the workspace. The files it already handled stay handled.
     */
    @Test
    public void aPartialRestoreStillCleansUpAndRefreshes() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        Files.writeString(added(copyProject), "// added\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.deleteIfExists(Path.of(created.get("copyPath").getAsString()).resolve("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(restored.toString(), restored.get("error").getAsString().contains("src/Module.bsl")); //$NON-NLS-1$
        assertEquals(List.of("src/Module.bsl"), strings(restored, "unrestoredFiles")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(strings(restored, "removedFiles").contains("src/Added.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("restoredFiles").toString().contains("DT-INF/PROJECT.PMF") //$NON-NLS-1$ //$NON-NLS-2$
            || restored.get("unchangedFiles").toString().contains("DT-INF/PROJECT.PMF")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the workspace was not refreshed after a partial restore", //$NON-NLS-1$
            MergeRestorePoint.refreshCalls > 0);
        assertArrayEquals(MERGED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(copyProject)));
        assertFalse(Files.exists(added(copyProject)));
    }

    /**
     * A restore does not rewrite a file whose bytes already match the point: a second restore with
     * nothing changed on disk writes no file, and only a file that changed is written again.
     */
    @Test
    public void aGitRestoreDoesNotRewriteAFileThatAlreadyMatches() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(gitProject), MERGED, StandardCharsets.UTF_8);
        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(strings(restored, "restoredFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$

        FileTime moduleTime = mtime(module(gitProject));
        FileTime platformTime = mtime(platform(gitProject));
        JsonObject again = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.toString(), again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(strings(again, "restoredFiles").isEmpty()); //$NON-NLS-1$
        assertFalse(strings(again, "unchangedFiles").isEmpty()); //$NON-NLS-1$
        assertEquals(moduleTime, mtime(module(gitProject)));
        assertEquals(platformTime, mtime(platform(gitProject)));

        Files.writeString(module(gitProject), MERGED, StandardCharsets.UTF_8);
        FileTime rewritten = mtime(module(gitProject));
        JsonObject third = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(third.toString(), third.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(List.of("src/Module.bsl"), strings(third, "restoredFiles")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(platformTime, mtime(platform(gitProject)));
        assertTrue(mtime(module(gitProject)).compareTo(rewritten) >= 0);
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
    }

    /**
     * The same rule for a copy point: a second restore with nothing changed on disk writes no
     * file.
     */
    @Test
    public void aCopyRestoreDoesNotRewriteAFileThatAlreadyMatches() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        FileTime moduleTime = mtime(module(copyProject));
        JsonObject again = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.toString(), again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(strings(again, "restoredFiles").isEmpty()); //$NON-NLS-1$
        assertEquals(moduleTime, mtime(module(copyProject)));
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(copyProject)));
    }

    /**
     * A git point whose ref could not be dropped is not reported deleted: the answer is an error,
     * the ref and the index entry stay, and once the ref can be dropped the same call succeeds.
     */
    @Test
    public void aGitPointWhoseRefSurvivesIsNotReportedDeleted() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        GitRepositoryAccess.deleteRefResultForTest = RefUpdate.Result.REJECTED;
        try
        {
            JsonObject dropped = json(new GitTool().execute(Map.of("operation", //$NON-NLS-1$
                "delete_merge_restore_point", "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(dropped.toString(), dropped.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(dropped.toString(), dropped.get("error").getAsString() //$NON-NLS-1$
                .contains("the restore ref could not be deleted")); //$NON-NLS-1$
            assertTrue("the index entry has to stay while the ref does", //$NON-NLS-1$
                Files.isRegularFile(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            try (Git git = Git.open(repoRoot.toFile()))
            {
                assertNotNull("the ref itself has to stay", //$NON-NLS-1$
                    git.getRepository().resolve(GitRepositoryAccess.restoreRef(pointId)));
            }
        }
        finally
        {
            GitRepositoryAccess.deleteRefResultForTest = null;
        }
        JsonObject again = json(new GitTool().execute(Map.of("operation", "delete_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.toString(), again.get("success").getAsBoolean()); //$NON-NLS-1$
        try (Git git = Git.open(repoRoot.toFile()))
        {
            assertNull(git.getRepository().resolve(GitRepositoryAccess.restoreRef(pointId)));
        }
        assertFalse(Files.exists(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A copy point whose directory could not be removed is not reported deleted either: the answer
     * is an error, the index entry and the remains of the copy stay, and the same call succeeds
     * once the directory can be removed.
     */
    @Test
    public void aCopyPointWhoseDirectorySurvivesIsNotReportedDeleted() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Path copyDir = Path.of(created.get("copyPath").getAsString()); //$NON-NLS-1$
        assertTrue(Files.isDirectory(copyDir));
        Path heldFile = copyDir.resolve("src/Module.bsl"); //$NON-NLS-1$
        MergeRestorePoint.deleteRefusalForTest = path -> path.equals(heldFile);
        try
        {
            JsonObject dropped = json(new GitTool().execute(Map.of("operation", //$NON-NLS-1$
                "delete_merge_restore_point", "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(dropped.toString(), dropped.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(dropped.toString(), dropped.get("error").getAsString() //$NON-NLS-1$
                .contains("the restore copy directory could not be deleted")); //$NON-NLS-1$
            assertTrue("the index entry has to stay while the copy does", //$NON-NLS-1$
                Files.isRegularFile(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the copy directory itself has to stay", Files.isDirectory(copyDir)); //$NON-NLS-1$
        }
        finally
        {
            MergeRestorePoint.deleteRefusalForTest = null;
        }
        JsonObject again = json(new GitTool().execute(Map.of("operation", "delete_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.toString(), again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(Files.exists(copyDir));
        assertFalse(Files.exists(store.resolve("points").resolve(pointId + ".txt"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The comparison a copy restore makes between the copy and the project file decides by size
     * first: files of different lengths are never read, and files of one length are compared
     * whatever their bytes are.
     */
    @Test
    public void sameBytesDecidesBySizeBeforeContent() throws Exception
    {
        Path left = Files.createTempFile("aiedt-same-left", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        Path right = Files.createTempFile("aiedt-same-right", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.writeString(left, "aaaa", StandardCharsets.UTF_8); //$NON-NLS-1$
            Files.writeString(right, "aa", StandardCharsets.UTF_8); //$NON-NLS-1$
            MergeRestorePoint.contentComparisons = 0;
            assertFalse(MergeRestorePoint.sameBytes(left, right));
            assertEquals("files of different sizes were decided without their content", 0, //$NON-NLS-1$
                MergeRestorePoint.contentComparisons);

            Files.writeString(right, "aaaa", StandardCharsets.UTF_8); //$NON-NLS-1$
            MergeRestorePoint.contentComparisons = 0;
            assertTrue(MergeRestorePoint.sameBytes(left, right));
            assertEquals(1, MergeRestorePoint.contentComparisons);

            Files.writeString(right, "aaba", StandardCharsets.UTF_8); //$NON-NLS-1$
            MergeRestorePoint.contentComparisons = 0;
            assertFalse(MergeRestorePoint.sameBytes(left, right));
            assertEquals("files of one size are compared even when they differ", 1, //$NON-NLS-1$
                MergeRestorePoint.contentComparisons);
        }
        finally
        {
            Files.deleteIfExists(left);
            Files.deleteIfExists(right);
        }
    }

    /**
     * A copy restore decides files of different sizes without reading their content: both changed
     * files are rewritten although no comparison ran for them, and every unchanged file got
     * exactly one.
     */
    @Test
    public void aCopyRestoreRewritesDifferentSizesWithoutReadingContent() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        Files.writeString(platform(copyProject), PLATFORM_AFTER, StandardCharsets.UTF_8);
        MergeRestorePoint.contentComparisons = 0;
        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(strings(restored, "restoredFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(strings(restored, "restoredFiles").contains("DT-INF/PROJECT.PMF")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("every unchanged file got one content comparison and no rewritten one did", //$NON-NLS-1$
            strings(restored, "unchangedFiles").size(), MergeRestorePoint.contentComparisons); //$NON-NLS-1$
        assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(copyProject)));
        assertArrayEquals(PLATFORM.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(platform(copyProject)));
    }

    /**
     * A git restore decides a file of a different size without reading its content, and a file of
     * the point's own size is compared however far into it the difference sits - a large file is
     * compared across several buffers.
     */
    @Test
    public void aGitRestoreDecidesBySizeAndComparesLargeFilesInChunks() throws Exception
    {
        String large = "a".repeat(20000) + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(module(gitProject), large, StandardCharsets.UTF_8);
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$

        Files.writeString(module(gitProject), MERGED, StandardCharsets.UTF_8);
        MergeRestorePoint.contentComparisons = 0;
        JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(strings(restored, "restoredFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(strings(restored, "unchangedFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("every unchanged file got one content comparison and no rewritten one did", //$NON-NLS-1$
            strings(restored, "unchangedFiles").size(), MergeRestorePoint.contentComparisons); //$NON-NLS-1$
        assertArrayEquals(large.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));

        String sameSize = "a".repeat(19999) + "b\n"; //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(module(gitProject), sameSize, StandardCharsets.UTF_8);
        MergeRestorePoint.contentComparisons = 0;
        JsonObject again = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", GIT_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.toString(), again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(strings(again, "restoredFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(strings(again, "unchangedFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the file of the point's own size was compared once and rewritten", //$NON-NLS-1$
            strings(again, "unchangedFiles").size() + 1, MergeRestorePoint.contentComparisons); //$NON-NLS-1$
        assertArrayEquals(large.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(gitProject)));
    }

    /**
     * A restore that stopped halfway and then could not remove an extra names both: the primary
     * error stays the answer's text with one phrase about the cleanup, the stuck extra is listed
     * in cleanupFailures, and removedFiles claims only the file that really went.
     */
    @Test
    public void aStuckExtraBesideAPartialRestoreIsNamedToo() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        Files.writeString(added(copyProject), "// added\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(stuck(copyProject), "// stuck\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.deleteIfExists(Path.of(created.get("copyPath").getAsString()).resolve("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        Path heldFile = stuck(copyProject);
        MergeRestorePoint.deleteRefusalForTest = path -> path.equals(heldFile);
        try
        {
            JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("error").getAsString() //$NON-NLS-1$
                .contains("src/Module.bsl is missing from the restore copy.")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("error").getAsString() //$NON-NLS-1$
                .contains("The cleanup after the restore did not finish")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("cleanupFailures").toString() //$NON-NLS-1$
                .contains("src/Stuck.bsl")); //$NON-NLS-1$
            assertTrue(strings(restored, "removedFiles").contains("src/Added.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("a file that stayed is nobody's removed file", //$NON-NLS-1$
                strings(restored, "removedFiles").contains("src/Stuck.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(List.of("src/Module.bsl"), strings(restored, "unrestoredFiles")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the stuck file itself stays", Files.exists(stuck(copyProject))); //$NON-NLS-1$
        }
        finally
        {
            MergeRestorePoint.deleteRefusalForTest = null;
            Files.deleteIfExists(stuck(copyProject));
        }
    }

    /**
     * An extra that cannot be removed makes the restore an error on its own: nothing else went
     * wrong, the answer names the file in cleanupFailures, and the workspace was still refreshed.
     */
    @Test
    public void aStuckExtraAloneMakesTheRestoreAnError() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(stuck(copyProject), "// stuck\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Path heldFile = stuck(copyProject);
        MergeRestorePoint.deleteRefusalForTest = path -> path.equals(heldFile);
        try
        {
            JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("error").getAsString() //$NON-NLS-1$
                .contains("the cleanup after the restore did not finish")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("cleanupFailures").toString() //$NON-NLS-1$
                .contains("src/Stuck.bsl")); //$NON-NLS-1$
            assertTrue(strings(restored, "removedFiles").isEmpty()); //$NON-NLS-1$
            assertTrue(strings(restored, "restoredFiles").isEmpty()); //$NON-NLS-1$
            assertTrue("the workspace was still refreshed", MergeRestorePoint.refreshCalls > 0); //$NON-NLS-1$
            assertArrayEquals(PROBE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(module(copyProject)));
            assertTrue("the stuck file itself stays", Files.exists(stuck(copyProject))); //$NON-NLS-1$
        }
        finally
        {
            MergeRestorePoint.deleteRefusalForTest = null;
            Files.deleteIfExists(stuck(copyProject));
        }
    }

    /**
     * A refresh that fails beside a primary error is said beside it - the primary text stays, one
     * phrase names the refresh, and refreshFailure carries it. Alone, it is the whole error.
     */
    @Test
    public void aRefreshFailureIsSaidBesideThePrimaryErrorAndAlone() throws Exception
    {
        JsonObject created = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(created.toString(), created.get("success").getAsBoolean()); //$NON-NLS-1$
        String pointId = created.get("pointId").getAsString(); //$NON-NLS-1$
        Files.writeString(module(copyProject), MERGED, StandardCharsets.UTF_8);
        Files.deleteIfExists(Path.of(created.get("copyPath").getAsString()).resolve("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRestorePoint.refreshFailureForTest = "the refresh was refused"; //$NON-NLS-1$
        try
        {
            JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", COPY_PROJECT, "pointId", pointId))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("error").getAsString() //$NON-NLS-1$
                .contains("src/Module.bsl is missing from the restore copy.")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("error").getAsString() //$NON-NLS-1$
                .contains("The workspace refresh after the restore failed")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("refreshFailure").getAsString() //$NON-NLS-1$
                .contains("the refresh was refused")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("cleanupFailures").getAsJsonArray().isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            MergeRestorePoint.refreshFailureForTest = null;
        }

        JsonObject second = json(new GitTool().execute(Map.of("operation", "create_merge_restore_point", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", COPY_PROJECT))); //$NON-NLS-1$
        assertTrue(second.toString(), second.get("success").getAsBoolean()); //$NON-NLS-1$
        MergeRestorePoint.refreshFailureForTest = "the refresh was refused again"; //$NON-NLS-1$
        try
        {
            JsonObject restored = json(new GitTool().execute(Map.of("operation", "restore_merge_point", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", COPY_PROJECT, "pointId", second.get("pointId").getAsString()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(restored.toString(), restored.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("error").getAsString() //$NON-NLS-1$
                .contains("the workspace refresh after the restore failed")); //$NON-NLS-1$
            assertTrue(restored.toString(), restored.get("refreshFailure").getAsString() //$NON-NLS-1$
                .contains("the refresh was refused again")); //$NON-NLS-1$
            assertTrue(strings(restored, "unchangedFiles").contains("src/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(strings(restored, "restoredFiles").isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            MergeRestorePoint.refreshFailureForTest = null;
        }
    }

    /**
     * A failed index write names the storage directory and the reason, drops the ref it had
     * already written, and the merge does not start.
     */
    @Test
    public void aFailedIndexLeavesNoRefAndStartsNoMerge() throws Exception
    {
        dropEveryRestorePoint();
        Path blocked = Files.createTempFile("aiedt-merge-block", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Path saved = MergeRestorePoint.storageRoot;
        MergeRestorePoint.storageRoot = blocked;
        try
        {
            byte[] before = Files.readAllBytes(module(gitProject));
            String answer = compare(GIT_PROJECT, "MERGE"); //$NON-NLS-1$
            assertTrue(answer, answer.contains("No merge was started")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("\"mergeStarted\":false")); //$NON-NLS-1$
            String plain = answer.replace("\\\\", "\\"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(plain, plain.contains(blocked.toString()));
            assertFalse(answer, answer.contains("mergeRestorePoint")); //$NON-NLS-1$
            assertNull(answer, noRestoreRefs());
            assertArrayEquals(before, Files.readAllBytes(module(gitProject)));
        }
        finally
        {
            MergeRestorePoint.storageRoot = saved;
            Files.deleteIfExists(blocked);
        }
    }

    private static String compare(String projectName, String intent)
    {
        return new ThreeWayComparisonTool().execute(Map.of("projectName", projectName, //$NON-NLS-1$
            "otherPath", "no such directory", "intent", intent)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Whether the repository holds no merge restore ref at all.
     *
     * @return the refusal text when a ref is still there, or {@code null} when none is
     */
    private static String noRestoreRefs()
    {
        try (Git git = Git.open(repoRoot.toFile()))
        {
            var refs = git.getRepository().getRefDatabase().getRefsByPrefix("refs/aiedt/merge-restore/"); //$NON-NLS-1$
            return refs.isEmpty() ? null : refs.toString();
        }
        catch (Exception e)
        {
            return "the repository could not be read: " + e; //$NON-NLS-1$
        }
    }

    /**
     * Removes every point of both projects - the refs, the copies and the index entries - so a test
     * that asserts on the absence of points starts from none. The store is shared by the whole
     * class and other tests leave their points behind.
     *
     * @throws Exception when the repository or the store cannot be written
     */
    private static void dropEveryRestorePoint() throws Exception
    {
        try (Git git = Git.open(repoRoot.toFile()))
        {
            for (var ref : git.getRepository().getRefDatabase().getRefsByPrefix("refs/aiedt/merge-restore/")) //$NON-NLS-1$
            {
                GitRepositoryAccess.deleteRestoreRef(git.getRepository(),
                    ref.getName().substring("refs/aiedt/merge-restore/".length())); //$NON-NLS-1$
            }
        }
        Path points = store.resolve("points"); //$NON-NLS-1$
        if (Files.isDirectory(points))
        {
            try (var files = Files.list(points))
            {
                for (Path file : files.toList())
                {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /**
     * The strings of one array field of an answer.
     *
     * @param answer the answer JSON
     * @param field the array field
     * @return its values, in order
     */
    private static List<String> strings(JsonObject answer, String field)
    {
        List<String> values = new ArrayList<>();
        for (var element : answer.getAsJsonArray(field))
        {
            values.add(element.getAsString());
        }
        return values;
    }

    /**
     * When a file was last changed.
     *
     * @param file the file
     * @return its modification time
     * @throws Exception when the file cannot be read
     */
    private static FileTime mtime(Path file) throws Exception
    {
        return Files.readAttributes(file, BasicFileAttributes.class).lastModifiedTime();
    }

    private static JsonObject json(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    private static void assertHeadUnmoved() throws Exception
    {
        try (Git git = Git.open(repoRoot.toFile()))
        {
            assertEquals(head, git.getRepository().resolve("HEAD").getName()); //$NON-NLS-1$
        }
    }

    private static Path module(IProject project) throws Exception
    {
        return project.getLocation().toFile().toPath().resolve("src/Module.bsl"); //$NON-NLS-1$
    }

    private static Path platform(IProject project)
    {
        return project.getLocation().toFile().toPath().resolve("DT-INF/PROJECT.PMF"); //$NON-NLS-1$
    }

    private static Path added(IProject project)
    {
        return project.getLocation().toFile().toPath().resolve("src/Added.bsl"); //$NON-NLS-1$
    }

    private static Path stuck(IProject project)
    {
        return project.getLocation().toFile().toPath().resolve("src/Stuck.bsl"); //$NON-NLS-1$
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        if (project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return project;
    }

    private static void deleteProject(IProject project) throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
    }

    private static void deleteTree(Path root) throws Exception
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
