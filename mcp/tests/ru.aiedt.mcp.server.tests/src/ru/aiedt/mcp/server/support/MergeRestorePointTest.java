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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.GitFileRestore;
import ru.aiedt.mcp.server.toolkit.ops.GitTool;
import ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool;

/**
 * A changing three-way comparison records a restore point first, and that point puts the project
 * files back.
 *
 * <p>The repository and the project are real. The comparison itself cannot run here - the other
 * side is not a configuration - so the tests show the point is taken before that attempt, that a
 * point which cannot be taken stops the call before the attempt, and that restoring the point
 * returns the files and leaves {@code git diff} empty.</p>
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
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(new GitTool());
        catalog.register(new GitFileRestore());
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
     * Putting the files back is the same write as revert_file, so a preset that switched that door
     * off refuses the restore before a byte is written.
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

    private static String compare(String projectName, String intent)
    {
        return new ThreeWayComparisonTool().execute(Map.of("projectName", projectName, //$NON-NLS-1$
            "otherPath", "no such directory", "intent", intent)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
