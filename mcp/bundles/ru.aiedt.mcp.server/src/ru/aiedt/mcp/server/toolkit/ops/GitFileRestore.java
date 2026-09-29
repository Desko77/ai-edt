/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.EolStreamTypeUtil;

import ru.aiedt.mcp.server.support.EditorBuffer;
import ru.aiedt.mcp.server.support.GitDiffUtils;
import ru.aiedt.mcp.server.support.GitFileDiff;
import ru.aiedt.mcp.server.support.LineDelimiters;
import ru.aiedt.mcp.server.support.PreviousRevision;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code git_revert_file} - the write door of the git facade's revert_file operation, and the
 * operation itself.
 *
 * <p>The door is a registered tool so a preset can switch it off by name. The facade calls
 * {@link #apply} after that gate has passed. Nothing inside the file is re-encoded: the text is
 * written with the line endings a checkout of that path would give it, which is what keeps the
 * file in the form the repository expects rather than in the form the commit's bytes happen to
 * carry. The index is left alone, so afterwards the file either matches HEAD or is listed as
 * modified.</p>
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
     * returns the diff of the work tree against the commit and does not touch the file, so a
     * preview is answered even while such an editor is open - the diff is read from the file on
     * disk and the answer says which of the two it read.</p>
     *
     * <p>The text is written with the line endings a checkout of that path would give it, and the
     * answer names them. See {@link #restored}.</p>
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
        boolean unsaved = EditorBuffer.hasUnsavedChanges(file);
        String editorRefusal = writeRefusal(repoPath, unsaved, dryRun);
        if (editorRefusal != null)
        {
            return ToolResult.error(editorRefusal)
                .put("filePath", repoPath) //$NON-NLS-1$
                .toJson();
        }
        ObjectId resolved = repository.resolve(fromRef);
        String restoredFrom = resolved == null ? fromRef : resolved.getName();
        Restored restored = restored(repository, repoPath, revision.bytes(), LineDelimiters.of(file));
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
                .put("lineEndings", restored.lineEndings) //$NON-NLS-1$
                .put("files", preview.files) //$NON-NLS-1$
                .put("note", previewNote(unsaved)) //$NON-NLS-1$
                .toJson();
        }
        java.nio.file.Path disk = disk(repository, repoPath);
        Files.createDirectories(disk.getParent());
        Files.write(disk, restored.bytes);
        refresh(project, file);
        String note = "The index was not changed. A file that matches HEAD is clean; one that " //$NON-NLS-1$
            + "differs from HEAD is listed as modified."; //$NON-NLS-1$
        ToolResult answer = ToolResult.success()
            .put("operation", "revert_file") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("filePath", repoPath) //$NON-NLS-1$
            .put("restoredFrom", restoredFrom) //$NON-NLS-1$
            .put("bytesWritten", Integer.valueOf(restored.bytes.length)) //$NON-NLS-1$
            .put("lineEndings", restored.lineEndings) //$NON-NLS-1$
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
     * Why this call may not write, or {@code null} when it may.
     *
     * <p>A preview writes nothing, so an editor holding the file with unsaved changes does not stop
     * it: the caller asked what the commit holds, and that answer is read from the file on disk.
     * Only the write is refused, because the buffer is the text the user is looking at and
     * overwriting the file would drop it.</p>
     *
     * @param repoPath the file
     * @param unsavedChanges whether an editor holds the file with unsaved changes
     * @param dryRun whether the call only previews
     * @return the refusal, or {@code null} when the call may write
     */
    static String writeRefusal(String repoPath, boolean unsavedChanges, boolean dryRun)
    {
        if (dryRun || !unsavedChanges)
        {
            return null;
        }
        return "An editor holds unsaved changes for " + repoPath //$NON-NLS-1$
            + ". The editor's buffer is kept. Nothing was written."; //$NON-NLS-1$
    }

    /**
     * What a preview says it read.
     *
     * @param unsavedChanges whether an editor holds the file with unsaved changes
     * @return the note for the preview answer
     */
    static String previewNote(boolean unsavedChanges)
    {
        if (unsavedChanges)
        {
            return "Nothing was written. An editor holds unsaved changes for this file, so the " //$NON-NLS-1$
                + "diff compares the commit with the file on disk and not with the buffer."; //$NON-NLS-1$
        }
        return "Nothing was written."; //$NON-NLS-1$
    }

    /**
     * The bytes to write for a file put back, and the line endings they carry.
     */
    static final class Restored
    {
        /** The bytes a checkout would leave in the work tree. */
        final byte[] bytes;

        /** What {@link #bytes} carries: {@code CRLF}, {@code LF} or {@code as stored}. */
        final String lineEndings;

        /**
         * @param bytes the bytes to write
         * @param lineEndings the endings they carry
         */
        Restored(byte[] bytes, String lineEndings)
        {
            this.bytes = bytes;
            this.lineEndings = lineEndings;
        }
    }

    /**
     * The bytes to write for a file put back, with the line endings a checkout would give them.
     *
     * <p>Git rewrites a file on its way out of the repository: the {@code eol} and {@code text}
     * attributes of {@code .gitattributes} and {@code core.autocrlf} decide which line ending the
     * work-tree file gets. A file written straight from the blob skips that, and the next status
     * call then shows a diff on every line of it. The stream type is the one JGit's checkout uses
     * for this exact path, so the file lands in the form that call would leave it in.</p>
     *
     * <p>One case that call does not answer is a repository that states no rule at all: then the
     * ending the work-tree file already has is kept, as every other write site here does - a
     * workspace is not the place to introduce a form nobody asked for. A binary has no line ending
     * to convert and is written as the commit stores it.</p>
     *
     * @param repository the repository
     * @param repoPath the file, work-tree-relative
     * @param blob the bytes the commit holds
     * @param kept the line endings the work-tree file has now
     * @return the bytes to write and what they carry
     * @throws IOException when the attributes or the conversion cannot be read
     */
    static Restored restored(Repository repository, String repoPath, byte[] blob, String kept)
        throws IOException
    {
        if (RawText.isBinary(blob))
        {
            return new Restored(blob, "as stored"); //$NON-NLS-1$
        }
        CoreConfig.EolStreamType type = checkoutStreamType(repository, repoPath);
        if (type == null || type == CoreConfig.EolStreamType.DIRECT)
        {
            type = LineDelimiters.LF.equals(kept) ? CoreConfig.EolStreamType.TEXT_LF
                : CoreConfig.EolStreamType.TEXT_CRLF;
        }
        boolean crlf = type == CoreConfig.EolStreamType.TEXT_CRLF
            || type == CoreConfig.EolStreamType.AUTO_CRLF;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(blob.length + 64);
        try (OutputStream out = EolStreamTypeUtil.wrapOutputStream(buffer, type))
        {
            out.write(blob);
        }
        return new Restored(buffer.toByteArray(), crlf ? "CRLF" : "LF"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The line-ending rule the repository states for one path on checkout.
     *
     * <p>The walk is what reads {@code .gitattributes}: JGit applies the rules of the file's own
     * directory and of every directory above it there, and the working-tree options of the
     * repository - {@code core.autocrlf}, {@code core.eol} - come from the same place. The call is
     * the one a checkout makes, so the answer is the checkout's answer rather than a reading of the
     * attributes by this class.</p>
     *
     * @param repository the repository
     * @param repoPath the file, work-tree-relative
     * @return the stream type, or {@code null} when the work tree does not hold that path
     * @throws IOException when the attributes cannot be read
     */
    private static CoreConfig.EolStreamType checkoutStreamType(Repository repository, String repoPath)
        throws IOException
    {
        try (TreeWalk walk = new TreeWalk(repository))
        {
            walk.addTree(new FileTreeIterator(repository));
            walk.setRecursive(true);
            walk.setFilter(PathFilter.create(repoPath));
            walk.setAttributesNodeProvider(repository.createAttributesNodeProvider());
            if (!walk.next())
            {
                return null;
            }
            return walk.getEolStreamType(TreeWalk.OperationType.CHECKOUT_OP);
        }
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
