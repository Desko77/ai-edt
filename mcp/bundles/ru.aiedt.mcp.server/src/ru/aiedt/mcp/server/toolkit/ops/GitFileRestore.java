/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import ru.aiedt.mcp.server.support.EditorBuffer;
import ru.aiedt.mcp.server.support.GitDiffUtils;
import ru.aiedt.mcp.server.support.GitFileDiff;
import ru.aiedt.mcp.server.support.PreviousRevision;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code git_revert_file} - the write door of the git facade's revert_file operation, and the
 * operation itself.
 *
 * <p>The door is a registered tool so a preset can switch it off by name. The facade calls
 * {@link #apply} after that gate has passed. The bytes written are the blob's own bytes: nothing
 * is re-encoded and the line endings are the ones the commit stored. The index is left alone, so
 * afterwards the file either matches HEAD or is listed as modified.</p>
 */
public class GitFileRestore
    extends GitTool
{
    /** The name a preset switches this write off under. */
    public static final String DOOR = "git_revert_file"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return DOOR;
    }

    @Override
    public String getDescription()
    {
        return "Put one file back to the bytes a commit holds, inside the IDE. Alias of " //$NON-NLS-1$
            + "git operation=revert_file - the facade is the way to call it. dryRun previews " //$NON-NLS-1$
            + "the diff and writes nothing."; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Map<String, String> call = new java.util.LinkedHashMap<>(params);
        call.put("operation", "revert_file"); //$NON-NLS-1$ //$NON-NLS-2$
        return super.execute(call);
    }

    /**
     * Puts one file back, or previews that without writing.
     *
     * <p>An editor that holds the file with unsaved changes is refused: the buffer is the text the
     * user is looking at, and overwriting the file would drop it. A preview ({@code dryRun})
     * returns the diff of the work tree against the commit and does not touch the file.</p>
     *
     * @param project the project the file belongs to
     * @param git the repository
     * @param filePath the file, as the caller wrote it; required
     * @param fromRef the commit to read, or {@code null} for HEAD
     * @param dryRun {@code true} to preview and write nothing
     * @return the answer JSON
     * @throws Exception when the repository cannot be read
     */
    static String apply(IProject project, Git git, String filePath, String fromRef, boolean dryRun)
        throws Exception
    {
        if (filePath == null || filePath.trim().isEmpty())
        {
            return ToolResult.error("revert_file requires filePath - the one file to put back, " //$NON-NLS-1$
                + "relative to the repository work tree.").toJson(); //$NON-NLS-1$
        }
        String normalized = normalizePath(filePath);
        String refusal = pathRefusal(normalized);
        if (refusal != null)
        {
            return ToolResult.error(refusal + " Nothing was written.").toJson(); //$NON-NLS-1$
        }
        Repository repository = git.getRepository();
        String repoPath = toRepoPath(project, repository, normalized);
        if (fromRef == null || fromRef.trim().isEmpty())
        {
            fromRef = "HEAD"; //$NON-NLS-1$
        }
        else
        {
            fromRef = fromRef.trim();
        }
        PreviousRevision revision = GitDiffUtils.revisionAt(repository, repoPath, fromRef);
        if (!revision.isFound())
        {
            return ToolResult.error(repoPath + " is not in " + fromRef + ". Nothing was written.") //$NON-NLS-1$ //$NON-NLS-2$
                .put("filePath", repoPath) //$NON-NLS-1$
                .toJson();
        }
        IFile file = workspaceFile(project, repository, repoPath);
        if (EditorBuffer.hasUnsavedChanges(file))
        {
            return ToolResult.error("An editor holds unsaved changes for " + repoPath //$NON-NLS-1$
                + ". The editor's buffer is kept. Nothing was written.") //$NON-NLS-1$
                .put("filePath", repoPath) //$NON-NLS-1$
                .toJson();
        }
        ObjectId resolved = repository.resolve(fromRef);
        String restoredFrom = resolved == null ? fromRef : resolved.getName();
        if (dryRun)
        {
            GitFileDiff.Answer preview = GitFileDiff.between(repository, fromRef, GitFileDiff.WORK_TREE,
                repoPath, GitFileDiff.LINE);
            if (preview.error != null)
            {
                return ToolResult.error(preview.error).toJson();
            }
            return ToolResult.success()
                .put("operation", "revert_file") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", project.getName()) //$NON-NLS-1$
                .put("filePath", repoPath) //$NON-NLS-1$
                .put("dryRun", Boolean.TRUE) //$NON-NLS-1$
                .put("bytesWritten", Integer.valueOf(0)) //$NON-NLS-1$
                .put("restoredFrom", restoredFrom) //$NON-NLS-1$
                .put("files", preview.files) //$NON-NLS-1$
                .put("note", "Nothing was written.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        byte[] bytes = revision.bytes();
        java.nio.file.Path disk = disk(repository, repoPath);
        Files.createDirectories(disk.getParent());
        Files.write(disk, bytes);
        refresh(project, file);
        String note = "The index was not changed. A file that matches HEAD is clean; one that " //$NON-NLS-1$
            + "differs from HEAD is listed as modified."; //$NON-NLS-1$
        ToolResult answer = ToolResult.success()
            .put("operation", "revert_file") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("filePath", repoPath) //$NON-NLS-1$
            .put("restoredFrom", restoredFrom) //$NON-NLS-1$
            .put("bytesWritten", Integer.valueOf(bytes.length)) //$NON-NLS-1$
            .put("fileStatus", fileStatus(git, repoPath)) //$NON-NLS-1$
            .put("indexUntouched", Boolean.TRUE); //$NON-NLS-1$
        if (needsRevalidate(repoPath))
        {
            answer.put("advice", "revalidate_objects"); //$NON-NLS-1$ //$NON-NLS-2$
            note = note + " EDT does not re-read an external edit of a .form, .mdo or .dcs by " //$NON-NLS-1$
                + "itself; call revalidate_objects."; //$NON-NLS-1$
        }
        return answer.put("note", note).toJson(); //$NON-NLS-1$
    }

    /**
     * Whether EDT has to be asked to re-read the file. An external edit of a form, a metadata
     * object or a composition schema is not picked up on its own.
     *
     * @param repoPath the file
     * @return {@code true} for {@code .form}, {@code .mdo} and {@code .dcs}
     */
    private static boolean needsRevalidate(String repoPath)
    {
        String lower = repoPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".form") || lower.endsWith(".mdo") || lower.endsWith(".dcs"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The workspace file for a repository path, when the path sits inside the project.
     *
     * @param project the project
     * @param repository the repository
     * @param repoPath the work-tree-relative path
     * @return the file handle, whether or not it exists yet
     */
    private static IFile workspaceFile(IProject project, Repository repository, String repoPath)
    {
        String relative = repoPath;
        String prefix = projectPrefix(project, repository);
        if (!prefix.isEmpty() && repoPath.startsWith(prefix + "/")) //$NON-NLS-1$
        {
            relative = repoPath.substring(prefix.length() + 1);
        }
        return project.getFile(relative);
    }

    /**
     * Where the project sits inside the work tree, or empty when the project is the work tree.
     *
     * @param project the project
     * @param repository the repository
     * @return the prefix, with forward slashes and no trailing slash
     */
    private static String projectPrefix(IProject project, Repository repository)
    {
        java.nio.file.Path root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
        java.nio.file.Path projectDir = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        if (!projectDir.startsWith(root))
        {
            return ""; //$NON-NLS-1$
        }
        String prefix = root.relativize(projectDir).toString().replace('\\', '/');
        return ".".equals(prefix) ? "" : prefix; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The work-tree file.
     *
     * @param repository the repository
     * @param repoPath the path, with forward slashes
     * @return the file
     */
    private static java.nio.file.Path disk(Repository repository, String repoPath)
    {
        java.nio.file.Path file = repository.getWorkTree().toPath();
        for (String segment : repoPath.split("/")) //$NON-NLS-1$
        {
            file = file.resolve(segment);
        }
        return file;
    }

    /**
     * Makes the workspace notice the bytes just written, from the nearest ancestor that exists.
     *
     * @param project the project
     * @param file the file
     * @throws Exception when the refresh fails
     */
    private static void refresh(IProject project, IFile file) throws Exception
    {
        IResource start = file;
        while (start != null && !start.exists())
        {
            start = start.getParent();
        }
        if (start == null)
        {
            start = project;
        }
        start.refreshLocal(IResource.DEPTH_INFINITE, null);
    }

    /**
     * Whether the path is clean against HEAD and the index.
     *
     * @param git the repository
     * @param repoPath the file
     * @return {@code clean} or {@code modified}
     * @throws Exception when the status cannot be read
     */
    private static String fileStatus(Git git, String repoPath) throws Exception
    {
        Status status = git.status().addPath(repoPath).call();
        boolean dirty = status.getModified().contains(repoPath) || status.getChanged().contains(repoPath)
            || status.getAdded().contains(repoPath) || status.getRemoved().contains(repoPath)
            || status.getMissing().contains(repoPath) || status.getUntracked().contains(repoPath)
            || status.getConflicting().contains(repoPath);
        return dirty ? "modified" : "clean"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The path as the repository records it.
     *
     * <p>A path already under the project folder is kept. A path written relative to the project is
     * prefixed with where the project sits in the work tree.</p>
     *
     * @param project the project
     * @param repository the repository
     * @param normalized a path from {@link GitTool#normalizePath}, with no {@code ..}
     * @return the work-tree-relative path
     */
    static String toRepoPath(IProject project, Repository repository, String normalized)
    {
        String prefix = projectPrefix(project, repository);
        if (prefix.isEmpty() || normalized.equals(prefix) || normalized.startsWith(prefix + "/")) //$NON-NLS-1$
        {
            return normalized;
        }
        return prefix + "/" + normalized; //$NON-NLS-1$
    }
}
