/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jgit.lib.Repository;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.ops.GitFileRestore;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * A restore point taken before a three-way merge, and the way back from it.
 *
 * <p>The point and the restore cover the project files only. The infobase is not copied and is not
 * rolled back. When the project sits in git, the point is a commit of those files kept on a ref
 * that does not move HEAD. When it does not, the point is a copy of the project directory beside
 * the workspace. A point that cannot be taken is a refusal: the caller must not start the merge.</p>
 */
public final class MergeRestorePoint
{
    /** What a restore answer says about the infobase. */
    public static final String INFOBASE_NOT_ROLLED_BACK = "The infobase was not rolled back."; //$NON-NLS-1$

    /** The directory name, beside the workspace, that holds copies and the point index. */
    static final String DIRECTORY_NAME = "aiedt-merge-restore"; //$NON-NLS-1$

    /**
     * Where copies and the point index are kept. {@code null} uses {@link #defaultStorageRoot()}.
     * A test points this at a directory of its own, or at a file so creating the directory fails.
     */
    static Path storageRoot;

    /**
     * How many times {@link #refresh(IProject, Restored)} has run. A test resets this and reads it
     * back, because the refresh has no other visible effect a headless run can observe.
     */
    static int refreshCalls;

    private static final String FORMAT = "V1"; //$NON-NLS-1$

    private MergeRestorePoint()
    {
        // static access only
    }

    /**
     * A point that was recorded, or the reason it was not.
     */
    public static final class Created
    {
        /** The point id, when one was recorded. */
        public String pointId;

        /** {@code git} or {@code copy}, when one was recorded. */
        public String kind;

        /** The restore commit, when {@code kind} is {@code git}. */
        public String commit;

        /** The copy directory, when {@code kind} is {@code copy}. */
        public String copyPath;

        /** Project-relative files the point holds. */
        public List<String> files;

        /** Why no point was recorded. */
        public String error;
    }

    /**
     * What a restore did, or why it stopped.
     */
    public static final class Restored
    {
        /** The point that was asked for. */
        public String pointId;

        /** Project-relative files written back. */
        public List<String> restoredFiles = new ArrayList<>();

        /** Project-relative files the point holds whose bytes on disk already matched it. */
        public List<String> unchangedFiles = new ArrayList<>();

        /** Project-relative files removed because the point does not have them. */
        public List<String> removedFiles = new ArrayList<>();

        /** Project-relative files the point holds that were not put back. */
        public List<String> unrestoredFiles = new ArrayList<>();

        /** Why the restore did not finish. Files already written stay written. */
        public String error;
    }

    /**
     * A point that was deleted, or the reason it was not.
     */
    public static final class Deleted
    {
        /** The point that was deleted. */
        public String pointId;

        /** {@code git} or {@code copy}, when a point was deleted. */
        public String kind;

        /** Why nothing was deleted. */
        public String error;
    }

    /**
     * Records a restore point for the project files.
     *
     * <p>Git is used when the project is inside a repository: the commit does not move HEAD. Otherwise
     * the project directory is copied. Either failure leaves the project files untouched and returns
     * the reason in {@link Created#error}.</p>
     *
     * @param project the project to record
     * @return the point, or the reason none was recorded
     */
    public static synchronized Created create(IProject project)
    {
        Created created = new Created();
        if (project == null || !project.isAccessible())
        {
            created.error = "the project is not accessible"; //$NON-NLS-1$
            return created;
        }
        if (project.getLocation() == null)
        {
            created.error = "the project has no location on disk"; //$NON-NLS-1$
            return created;
        }
        List<String> files;
        try
        {
            files = listProjectFiles(projectDirectory(project));
        }
        catch (IOException e)
        {
            Activator.logError("merge restore point could not list project files", e); //$NON-NLS-1$
            created.error = "the project files could not be listed (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return created;
        }
        if (files.isEmpty())
        {
            created.error = "the project has no files to record"; //$NON-NLS-1$
            return created;
        }
        String pointId = newPointId();
        try (GitRepositoryAccess.Resolved resolved = GitRepositoryAccess.of(project))
        {
            if (resolved.error == null)
            {
                return createGit(project, resolved, pointId, files);
            }
        }
        return createCopy(project, pointId, files);
    }

    /**
     * Records a restore point and renders the answer a git call returns.
     *
     * @param project the project to record
     * @return the answer JSON
     */
    public static String createJson(IProject project)
    {
        Created created = create(project);
        if (created.error != null)
        {
            return ToolResult.error(created.error + " Nothing in the project was changed.") //$NON-NLS-1$
                .put("operation", "create_merge_restore_point") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", project == null ? null : project.getName()) //$NON-NLS-1$
                .put("mergeStarted", false) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", "create_merge_restore_point") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("pointId", created.pointId) //$NON-NLS-1$
            .put("kind", created.kind) //$NON-NLS-1$
            .put("commit", created.commit) //$NON-NLS-1$
            .put("copyPath", created.copyPath) //$NON-NLS-1$
            .put("files", created.files) //$NON-NLS-1$
            .put("fileCount", created.files.size()) //$NON-NLS-1$
            .put("note", "The infobase is not part of this point.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Puts the project files back to a recorded point. The infobase is not touched.
     *
     * @param project the project to restore
     * @param pointId the point, or {@code null} or blank for the latest point of this project
     * @return what was restored, or why nothing more was written
     */
    public static synchronized Restored restore(IProject project, String pointId)
    {
        Restored restored = new Restored();
        if (project == null || !project.isAccessible() || project.getLocation() == null)
        {
            restored.error = "the project is not accessible. Nothing was written."; //$NON-NLS-1$
            return restored;
        }
        Record record;
        try
        {
            record = resolve(project.getName(), pointId);
        }
        catch (IOException e)
        {
            Activator.logError("merge restore point could not be read", e); //$NON-NLS-1$
            restored.error = "the restore point could not be read (" + e.getMessage() //$NON-NLS-1$
                + "). Nothing was written."; //$NON-NLS-1$
            return restored;
        }
        if (record == null)
        {
            restored.error = "No merge restore point exists for this project. Nothing was written."; //$NON-NLS-1$
            return restored;
        }
        restored.pointId = record.pointId;
        if ("git".equals(record.kind)) //$NON-NLS-1$
        {
            return restoreGit(project, record, restored);
        }
        return restoreCopy(project, record, restored);
    }

    /**
     * Puts the project files back and renders the answer a git call returns.
     *
     * @param project the project to restore
     * @param pointId the point, or {@code null} or blank for the latest point of this project
     * @return the answer JSON
     */
    public static String restoreJson(IProject project, String pointId)
    {
        Restored restored = restore(project, pointId);
        String projectName = project == null ? null : project.getName();
        if (restored.error != null)
        {
            return ToolResult.error(restored.error)
                .put("operation", "restore_merge_point") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("pointId", restored.pointId) //$NON-NLS-1$
                .put("restoredFiles", restored.restoredFiles) //$NON-NLS-1$
                .put("unchangedFiles", restored.unchangedFiles) //$NON-NLS-1$
                .put("unrestoredFiles", restored.unrestoredFiles) //$NON-NLS-1$
                .put("removedFiles", restored.removedFiles) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", "restore_merge_point") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("pointId", restored.pointId) //$NON-NLS-1$
            .put("restoredFiles", restored.restoredFiles) //$NON-NLS-1$
            .put("restoredCount", restored.restoredFiles.size()) //$NON-NLS-1$
            .put("unchangedFiles", restored.unchangedFiles) //$NON-NLS-1$
            .put("removedFiles", restored.removedFiles) //$NON-NLS-1$
            .put("note", "Project files were restored. " + INFOBASE_NOT_ROLLED_BACK) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Deletes a recorded point and renders the answer a git call returns.
     *
     * @param project the project whose point is deleted
     * @param pointId the point, required
     * @return the answer JSON
     */
    public static String deleteJson(IProject project, String pointId)
    {
        Deleted deleted = delete(project, pointId);
        String projectName = project == null ? null : project.getName();
        if (deleted.error != null)
        {
            return ToolResult.error(deleted.error)
                .put("operation", "delete_merge_restore_point") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", projectName) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", "delete_merge_restore_point") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("pointId", deleted.pointId) //$NON-NLS-1$
            .put("kind", deleted.kind) //$NON-NLS-1$
            .put("note", "The restore point was deleted. The project files were not touched.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Deletes a recorded point: the ref that keeps the commit, or the copy directory, and the index
     * entry. The project files are not touched, and neither is the infobase. Storage that could not
     * be deleted keeps the index entry, so the point stays addressable and can be deleted again.
     *
     * @param project the project whose point is deleted
     * @param pointId the point, required; a point of another project is not resolved
     * @return what was deleted, or why nothing was
     */
    public static synchronized Deleted delete(IProject project, String pointId)
    {
        Deleted deleted = new Deleted();
        if (pointId == null || pointId.isBlank())
        {
            deleted.error = "delete_merge_restore_point requires pointId - the point to delete. " //$NON-NLS-1$
                + "The latest point is not picked for you here: name what goes."; //$NON-NLS-1$
            return deleted;
        }
        if (project == null || !project.isAccessible() || project.getLocation() == null)
        {
            deleted.error = "the project is not accessible. Nothing was deleted."; //$NON-NLS-1$
            return deleted;
        }
        Record record;
        try
        {
            record = resolve(project.getName(), pointId);
        }
        catch (IOException e)
        {
            Activator.logError("merge restore point could not be read", e); //$NON-NLS-1$
            deleted.error = "the restore point could not be read (" + e.getMessage() //$NON-NLS-1$
                + "). Nothing was deleted."; //$NON-NLS-1$
            return deleted;
        }
        if (record == null)
        {
            deleted.error = "No merge restore point " + pointId.trim() + " exists for this project. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Nothing was deleted."; //$NON-NLS-1$
            return deleted;
        }
        deleted.pointId = record.pointId;
        deleted.kind = record.kind;
        if ("git".equals(record.kind)) //$NON-NLS-1$
        {
            try (GitRepositoryAccess.Resolved resolved = GitRepositoryAccess.of(project))
            {
                if (resolved.error != null)
                {
                    deleted.error = resolved.error + " Nothing was deleted."; //$NON-NLS-1$
                    return deleted;
                }
                String problem = GitRepositoryAccess.deleteRestoreRef(resolved.repository, record.pointId);
                if (problem != null)
                {
                    deleted.error = problem + ". The index entry was kept, so the point can be " //$NON-NLS-1$
                        + "deleted again."; //$NON-NLS-1$
                    return deleted;
                }
            }
            catch (Exception e)
            {
                Activator.logError("merge restore ref could not be deleted", e); //$NON-NLS-1$
                deleted.error = "the restore ref could not be deleted (" + e.getMessage() //$NON-NLS-1$
                    + "). The index entry was kept."; //$NON-NLS-1$
                return deleted;
            }
        }
        else
        {
            String problem = deleteTree(record.copyPath == null ? null : Path.of(record.copyPath));
            if (problem != null)
            {
                deleted.error = problem + ". The index entry was kept, so the point can be deleted " //$NON-NLS-1$
                    + "again."; //$NON-NLS-1$
                return deleted;
            }
        }
        try
        {
            deleteRecord(record.pointId);
        }
        catch (IOException e)
        {
            Activator.logError("merge restore point entry could not be deleted", e); //$NON-NLS-1$
            deleted.error = "the point was deleted, but its index entry could not be (" //$NON-NLS-1$
                + e.getMessage() + ")"; //$NON-NLS-1$
            return deleted;
        }
        return deleted;
    }

    /**
     * The point ids recorded for a project, in the order they were read.
     *
     * @param projectName the project
     * @return the ids, empty when none are recorded or the index cannot be read
     */
    static List<String> idsFor(String projectName)
    {
        List<String> ids = new ArrayList<>();
        try
        {
            for (Record record : readAll(projectName))
            {
                ids.add(record.pointId);
            }
        }
        catch (IOException e)
        {
            Activator.logError("merge restore points could not be listed", e); //$NON-NLS-1$
        }
        return ids;
    }

    /**
     * The directory beside the workspace that holds copies and the point index. Does not create it.
     *
     * @return that directory, or {@code null} when the workspace has no location
     */
    static Path defaultStorageRoot()
    {
        if (ResourcesPlugin.getWorkspace() == null
            || ResourcesPlugin.getWorkspace().getRoot().getLocation() == null)
        {
            return null;
        }
        Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        Path parent = workspace.getParent();
        if (parent == null)
        {
            return workspace.resolve(DIRECTORY_NAME);
        }
        return parent.resolve(DIRECTORY_NAME);
    }

    /**
     * Records the files as a commit and writes the index entry. A failure to write the index drops
     * the ref, so a point the caller cannot name does not stay behind.
     *
     * @param project the project
     * @param resolved the open repository
     * @param pointId the new point id
     * @param files the project-relative files
     * @return the point, or the reason none was recorded
     */
    private static Created createGit(IProject project, GitRepositoryAccess.Resolved resolved, String pointId,
        List<String> files)
    {
        Created created = new Created();
        GitRepositoryAccess.RestoreCommit commit = GitRepositoryAccess.recordProjectFiles(resolved.git, project,
            pointId, files);
        if (commit.error != null)
        {
            created.error = commit.error;
            return created;
        }
        try
        {
            writeRecord(pointId, project.getName(), "git", commit.hash, null, files); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            GitRepositoryAccess.deleteRestoreRef(resolved.repository, pointId);
            Activator.logError("merge restore point was not recorded", e); //$NON-NLS-1$
            created.error = "the restore point index could not be written to " + storageDescription() //$NON-NLS-1$
                + " (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return created;
        }
        created.pointId = pointId;
        created.kind = "git"; //$NON-NLS-1$
        created.commit = commit.hash;
        created.files = files;
        return created;
    }

    /**
     * Copies the project directory and writes the index entry. A failure deletes the partial copy.
     *
     * @param project the project
     * @param pointId the new point id
     * @param files the project-relative files
     * @return the point, or the reason none was recorded
     */
    private static Created createCopy(IProject project, String pointId, List<String> files)
    {
        Created created = new Created();
        Path copy = null;
        try
        {
            copy = storage().resolve("copies").resolve(pointId); //$NON-NLS-1$
            Files.createDirectories(copy);
            Path projectDir = projectDirectory(project);
            for (String relative : files)
            {
                Path to = copy.resolve(relative);
                if (to.getParent() != null)
                {
                    Files.createDirectories(to.getParent());
                }
                Files.copy(projectDir.resolve(relative), to, StandardCopyOption.REPLACE_EXISTING);
            }
            writeRecord(pointId, project.getName(), "copy", null, copy.toString(), files); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            deleteTree(copy);
            Activator.logError("merge restore copy failed", e); //$NON-NLS-1$
            created.error = "the project directory could not be copied to " //$NON-NLS-1$
                + (copy == null ? storageDescription() : copy.toString())
                + " (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return created;
        }
        created.pointId = pointId;
        created.kind = "copy"; //$NON-NLS-1$
        created.copyPath = copy.toString();
        created.files = files;
        return created;
    }

    /**
     * Writes each file from the restore commit, then removes project files the point does not have.
     * The blob of a point holds the work-tree bytes as they were read, so they are written as stored
     * by {@link GitFileRestore#putBytes}; the checkout line-ending rule {@code revert_file} applies
     * to a commit blob is not applied to them. A file whose bytes on disk already match the point is
     * not rewritten.
     *
     * <p>A file that cannot be put back stops the loop there; the cleanup and the workspace refresh
     * still run, so the extras of the merge do not stay beside a half-restored point.</p>
     *
     * @param project the project
     * @param record the point
     * @param restored the answer being filled in
     * @return {@code restored}
     */
    private static Restored restoreGit(IProject project, Record record, Restored restored)
    {
        try (GitRepositoryAccess.Resolved resolved = GitRepositoryAccess.of(project))
        {
            if (resolved.error != null)
            {
                restored.error = resolved.error + " Nothing was written."; //$NON-NLS-1$
                return restored;
            }
            Repository repository = resolved.repository;
            for (String relative : record.files)
            {
                String repoPath = GitRepositoryAccess.repoPath(project, repository, relative);
                if (repoPath == null)
                {
                    restored.error = relative + " is outside the git work tree."; //$NON-NLS-1$
                    break;
                }
                PreviousRevision revision = GitDiffUtils.revisionAt(repository, repoPath, record.commit);
                if (!revision.isFound() || revision.bytes() == null)
                {
                    restored.error = relative + " is not in the restore point."; //$NON-NLS-1$
                    break;
                }
                if (alreadyOnDisk(repository, repoPath, revision.bytes()))
                {
                    restored.unchangedFiles.add(relative);
                    continue;
                }
                String problem = GitFileRestore.putBytes(project, repository, repoPath, revision.bytes(), false);
                if (problem != null)
                {
                    restored.error = problem;
                    break;
                }
                restored.restoredFiles.add(relative);
            }
        }
        catch (Exception e)
        {
            Activator.logError("merge restore failed", e); //$NON-NLS-1$
            restored.error = "the restore failed (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        finish(project, record, restored);
        return restored;
    }

    /**
     * Copies the saved project directory back over the project, then removes files the point does
     * not have. A file whose bytes on disk already match the point is not rewritten.
     *
     * <p>A file that cannot be put back stops the loop there; the cleanup and the workspace refresh
     * still run, so the extras of the merge do not stay beside a half-restored point.</p>
     *
     * @param project the project
     * @param record the point
     * @param restored the answer being filled in
     * @return {@code restored}
     */
    private static Restored restoreCopy(IProject project, Record record, Restored restored)
    {
        try
        {
            Path copy = Path.of(record.copyPath);
            if (!Files.isDirectory(copy))
            {
                restored.error = "the restore copy is missing. Nothing was written."; //$NON-NLS-1$
                return restored;
            }
            Path projectDir = projectDirectory(project);
            for (String relative : record.files)
            {
                Path from = copy.resolve(relative);
                if (!Files.isRegularFile(from))
                {
                    restored.error = relative + " is missing from the restore copy."; //$NON-NLS-1$
                    break;
                }
                Path to = projectDir.resolve(relative);
                if (Files.isRegularFile(to) && Arrays.equals(Files.readAllBytes(from), Files.readAllBytes(to)))
                {
                    restored.unchangedFiles.add(relative);
                    continue;
                }
                org.eclipse.core.resources.IFile file = project.getFile(relative);
                if (EditorBuffer.hasUnsavedChanges(file))
                {
                    restored.error = "An editor holds unsaved changes for " + relative //$NON-NLS-1$
                        + ". The editor's buffer is kept."; //$NON-NLS-1$
                    break;
                }
                if (to.getParent() != null)
                {
                    Files.createDirectories(to.getParent());
                }
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                restored.restoredFiles.add(relative);
            }
        }
        catch (IOException e)
        {
            Activator.logError("merge restore from copy failed", e); //$NON-NLS-1$
            restored.error = "the restore failed (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        finish(project, record, restored);
        return restored;
    }

    /**
     * Completes a restore after its loop: names the files of the point that were not put back,
     * removes the project files the point does not hold, and makes the workspace notice. A restore
     * that stopped halfway runs this as well - the caller asked for the point, and half of it with
     * the extras of the merge still lying beside it is further from the point than half of it alone.
     *
     * @param project the project
     * @param record the point
     * @param restored the answer being filled in
     */
    private static void finish(IProject project, Record record, Restored restored)
    {
        Set<String> settled = new HashSet<>(restored.restoredFiles);
        settled.addAll(restored.unchangedFiles);
        for (String relative : record.files)
        {
            if (!settled.contains(relative))
            {
                restored.unrestoredFiles.add(relative);
            }
        }
        try
        {
            restored.removedFiles.addAll(deleteExtras(projectDirectory(project), record.files));
        }
        catch (IOException e)
        {
            Activator.logError("merge restore cleanup failed", e); //$NON-NLS-1$
            if (restored.error == null)
            {
                restored.error = "the cleanup after the restore failed (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        refresh(project, restored);
    }

    /**
     * Whether the work-tree file already holds exactly these bytes, so a restore would rewrite it
     * with what it already has.
     *
     * @param repository the repository
     * @param repoPath the work-tree-relative path
     * @param bytes the bytes the point holds
     * @return {@code true} when the file on disk matches the point
     * @throws IOException when the file cannot be read
     */
    private static boolean alreadyOnDisk(Repository repository, String repoPath, byte[] bytes) throws IOException
    {
        Path disk = repository.getWorkTree().toPath();
        for (String segment : repoPath.split("/")) //$NON-NLS-1$
        {
            disk = disk.resolve(segment);
        }
        if (!Files.isRegularFile(disk))
        {
            return false;
        }
        return Arrays.equals(bytes, Files.readAllBytes(disk));
    }

    /**
     * The point a restore should use: the named one when it belongs to this project, otherwise the
     * latest.
     *
     * @param projectName the project
     * @param pointId the requested id, or blank for the latest
     * @return the record, or {@code null} when there is nothing to restore
     * @throws IOException when the index cannot be read
     */
    private static Record resolve(String projectName, String pointId) throws IOException
    {
        if (pointId != null && !pointId.isBlank())
        {
            if (!safeId(pointId.trim()))
            {
                return null;
            }
            Record record = readRecord(pointId.trim());
            if (record == null || !projectName.equals(record.projectName))
            {
                return null;
            }
            return record;
        }
        return latest(projectName);
    }

    /**
     * Whether a point id is safe to use as a file name and a ref suffix.
     *
     * @param pointId the id
     * @return {@code true} when it is made of ASCII letters, digits, dots, underscores and dashes
     */
    private static boolean safeId(String pointId)
    {
        for (int i = 0; i < pointId.length(); i++)
        {
            char c = pointId.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
            if (!ok)
            {
                return false;
            }
        }
        return !pointId.isEmpty();
    }

    /**
     * A new point id. The stamp sorts in time order; the tail keeps two calls in the same
     * millisecond apart. The characters are safe in a ref name and a file name.
     *
     * @return the id
     */
    private static String newPointId()
    {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date()); //$NON-NLS-1$
        return stamp + "-" + Long.toHexString(System.nanoTime()); //$NON-NLS-1$
    }

    /**
     * The project directory on disk.
     *
     * @param project the project
     * @return the absolute normalized directory
     */
    private static Path projectDirectory(IProject project)
    {
        return project.getLocation().toFile().toPath().toAbsolutePath().normalize();
    }

    /**
     * Regular files under the project, skipping git metadata and this store. Paths use forward
     * slashes and are relative to the project directory.
     *
     * @param projectDir the project directory
     * @return the files, sorted
     * @throws IOException when a file cannot be visited
     */
    private static List<String> listProjectFiles(Path projectDir) throws IOException
    {
        List<String> files = new ArrayList<>();
        IOException[] failed = new IOException[1];
        Files.walkFileTree(projectDir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                if (isGitDirectory(dir) || isStorage(dir))
                {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (attrs.isRegularFile() && !isStorage(file))
                {
                    files.add(projectDir.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
            {
                failed[0] = exc;
                return FileVisitResult.TERMINATE;
            }
        });
        if (failed[0] != null)
        {
            throw failed[0];
        }
        Collections.sort(files);
        return files;
    }

    /**
     * Whether this directory is git's own metadata.
     *
     * @param dir a directory being walked
     * @return {@code true} for a directory named {@code .git}
     */
    private static boolean isGitDirectory(Path dir)
    {
        Path name = dir.getFileName();
        return name != null && ".git".equals(name.toString()); //$NON-NLS-1$
    }

    /**
     * Whether a path is the store or lies inside it, so a point is not recorded inside itself.
     *
     * @param path a file or directory under the project
     * @return {@code true} when the store contains it
     */
    private static boolean isStorage(Path path)
    {
        Path root = storageRoot != null ? storageRoot : defaultStorageRoot();
        if (root == null)
        {
            return false;
        }
        Path base = root.toAbsolutePath().normalize();
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(base);
    }

    /**
     * The store directory, creating it. A file where the directory should be fails the point.
     *
     * @return the store directory
     * @throws IOException when it cannot be created, including when there is no space
     */
    private static Path storage() throws IOException
    {
        Path root = storageRoot != null ? storageRoot : defaultStorageRoot();
        if (root == null)
        {
            throw new IOException("the workspace has no location, so there is nowhere to keep the point"); //$NON-NLS-1$
        }
        Files.createDirectories(root);
        return root;
    }

    /**
     * Writes one index entry.
     *
     * @param pointId the point
     * @param projectName the project
     * @param kind {@code git} or {@code copy}
     * @param commit the commit hash, or {@code null}
     * @param copyPath the copy directory, or {@code null}
     * @param files the project-relative files
     * @throws IOException when the entry cannot be written
     */
    private static void writeRecord(String pointId, String projectName, String kind, String commit,
        String copyPath, List<String> files) throws IOException
    {
        Path dir = storage().resolve("points"); //$NON-NLS-1$
        Files.createDirectories(dir);
        StringBuilder body = new StringBuilder();
        body.append(FORMAT).append('\n');
        body.append(pointId).append('\n');
        body.append(projectName).append('\n');
        body.append(kind).append('\n');
        body.append(commit == null ? "-" : commit).append('\n'); //$NON-NLS-1$
        body.append(copyPath == null ? "-" : copyPath).append('\n'); //$NON-NLS-1$
        body.append(System.currentTimeMillis()).append('\n');
        for (String file : files)
        {
            body.append(file).append('\n');
        }
        Files.writeString(dir.resolve(pointId + ".txt"), body.toString(), StandardCharsets.UTF_8); //$NON-NLS-1$
    }

    /**
     * Reads one index entry.
     *
     * @param pointId the point
     * @return the record, or {@code null} when it is absent or unreadable
     * @throws IOException when the entry cannot be read
     */
    private static Record readRecord(String pointId) throws IOException
    {
        Path dir = pointsDirOrNull();
        if (dir == null)
        {
            return null;
        }
        Path file = dir.resolve(pointId + ".txt"); //$NON-NLS-1$
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        return parse(file);
    }

    /**
     * Every readable point for one project.
     *
     * @param projectName the project
     * @return the records
     * @throws IOException when the index cannot be listed
     */
    private static List<Record> readAll(String projectName) throws IOException
    {
        List<Record> records = new ArrayList<>();
        Path dir = pointsDirOrNull();
        if (dir == null)
        {
            return records;
        }
        try (var walk = Files.list(dir))
        {
            for (Path file : walk.filter(Files::isRegularFile).toList())
            {
                Record record = parse(file);
                if (record != null && projectName.equals(record.projectName))
                {
                    records.add(record);
                }
            }
        }
        return records;
    }

    /**
     * The newest point for a project.
     *
     * @param projectName the project
     * @return the record, or {@code null} when the project has none
     * @throws IOException when the index cannot be read
     */
    private static Record latest(String projectName) throws IOException
    {
        Record best = null;
        for (Record record : readAll(projectName))
        {
            if (best == null || record.epoch > best.epoch
                || (record.epoch == best.epoch && record.pointId.compareTo(best.pointId) > 0))
            {
                best = record;
            }
        }
        return best;
    }

    /**
     * The points directory when it already exists.
     *
     * @return the directory, or {@code null} when there is nowhere to look
     */
    private static Path pointsDirOrNull()
    {
        Path root = storageRoot != null ? storageRoot : defaultStorageRoot();
        if (root == null)
        {
            return null;
        }
        Path points = root.resolve("points"); //$NON-NLS-1$
        return Files.isDirectory(points) ? points : null;
    }

    /**
     * Parses one index file.
     *
     * @param file the file
     * @return the record, or {@code null} when the file is not one of ours
     * @throws IOException when the file cannot be read
     */
    private static Record parse(Path file) throws IOException
    {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 7 || !FORMAT.equals(lines.get(0)))
        {
            return null;
        }
        Record record = new Record();
        record.pointId = lines.get(1);
        record.projectName = lines.get(2);
        record.kind = lines.get(3);
        record.commit = "-".equals(lines.get(4)) ? null : lines.get(4); //$NON-NLS-1$
        record.copyPath = "-".equals(lines.get(5)) ? null : lines.get(5); //$NON-NLS-1$
        try
        {
            record.epoch = Long.parseLong(lines.get(6));
        }
        catch (NumberFormatException bad)
        {
            return null;
        }
        record.files = new ArrayList<>();
        for (int i = 7; i < lines.size(); i++)
        {
            if (!lines.get(i).isEmpty())
            {
                record.files.add(lines.get(i));
            }
        }
        return record;
    }

    /**
     * Deletes regular files under the project that the point does not hold. Git metadata is left
     * alone.
     *
     * @param projectDir the project directory
     * @param keep the project-relative files to leave in place
     * @return the project-relative paths that were removed
     * @throws IOException when a file cannot be deleted
     */
    private static List<String> deleteExtras(Path projectDir, List<String> keep) throws IOException
    {
        Set<String> held = new HashSet<>(keep);
        List<Path> files = new ArrayList<>();
        IOException[] failed = new IOException[1];
        Files.walkFileTree(projectDir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                if (isGitDirectory(dir))
                {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (attrs.isRegularFile())
                {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
            {
                failed[0] = exc;
                return FileVisitResult.TERMINATE;
            }
        });
        if (failed[0] != null)
        {
            throw failed[0];
        }
        List<String> removed = new ArrayList<>();
        for (Path file : files)
        {
            String relative = projectDir.relativize(file).toString().replace('\\', '/');
            if (held.contains(relative) || relative.startsWith(".git/") || ".git".equals(relative)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            Files.deleteIfExists(file);
            removed.add(relative);
        }
        Collections.sort(removed);
        return removed;
    }

    /**
     * Deletes a copy directory: a partial one a refused create leaves behind, or the one a delete
     * drops. A failure is logged and named in the answer, so a caller that has to keep the point
     * addressable can refuse.
     *
     * @param root the copy directory, or {@code null}
     * @return {@code null} when the directory is gone, or what could not be deleted and why
     */
    private static String deleteTree(Path root)
    {
        if (root == null || !Files.exists(root))
        {
            return null;
        }
        String reason = null;
        int left = 0;
        try (var walk = Files.walk(root))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException e)
                {
                    Activator.logError("could not delete " + path, e); //$NON-NLS-1$
                    left++;
                    if (reason == null)
                    {
                        reason = e.getMessage();
                    }
                }
            }
        }
        catch (IOException e)
        {
            Activator.logError("could not delete restore copy " + root, e); //$NON-NLS-1$
            return "the restore copy directory could not be read (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (left > 0)
        {
            return "the restore copy directory could not be deleted (" + reason + "): " + left //$NON-NLS-1$ //$NON-NLS-2$
                + " item(s) remain under " + root; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Makes the workspace notice the restored files. Counted, because a partial restore has to run
     * it too and no other visible state says it happened.
     *
     * @param project the project
     * @param restored the answer being filled in; a failed refresh becomes its error when it has
     *            none yet
     */
    private static void refresh(IProject project, Restored restored)
    {
        refreshCalls++;
        try
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
        }
        catch (CoreException e)
        {
            Activator.logError("merge restore refresh failed", e); //$NON-NLS-1$
            if (restored.error == null)
            {
                restored.error = "the workspace refresh after the restore failed (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Deletes one index entry.
     *
     * @param pointId the point
     * @throws IOException when the entry cannot be deleted
     */
    private static void deleteRecord(String pointId) throws IOException
    {
        Path dir = pointsDirOrNull();
        if (dir == null)
        {
            return;
        }
        Files.deleteIfExists(dir.resolve(pointId + ".txt")); //$NON-NLS-1$
    }

    /**
     * The storage root as a refusal names it. Never fails on its own: a refusal about a storage
     * that cannot even be named would hide the reason it was being named.
     *
     * @return the root path, or a stand-in when there is none
     */
    private static String storageDescription()
    {
        Path root = storageRoot != null ? storageRoot : defaultStorageRoot();
        return root == null ? "the workspace storage" : root.toString(); //$NON-NLS-1$
    }

    /**
     * One line of the point index.
     */
    private static final class Record
    {
        private String pointId;

        private String projectName;

        private String kind;

        private String commit;

        private String copyPath;

        private long epoch;

        private List<String> files;
    }
}
