/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import ru.aiedt.mcp.server.Activator;

/**
 * The previous revision of a file: git HEAD first, Eclipse's local history second.
 *
 * <p>Two questions, kept apart on purpose. Which repository the project lives in and where the file
 * sits inside it is {@link GitRepositoryAccess}'s answer; reading the file out of HEAD is
 * {@link #headRevision}, which takes a repository and a path and nothing from the workspace, so it
 * can be exercised on a temporary repository. Both used to be one reflective walk through EGit,
 * which answered {@code null} to every failure alike - absent classes, an unshared project, a file
 * never committed, a read that threw - and a caller could not tell a new file from one nobody could
 * read. {@link PreviousRevision} carries that answer instead.</p>
 */
public final class GitDiffUtils
{
    private GitDiffUtils()
    {
        // Utility class
    }

    /**
     * The previous revision of a file: HEAD of the repository that holds it, else local history.
     *
     * @param file the workspace file
     * @param project the containing project
     * @return the revision and what came of looking for it; never {@code null}
     */
    public static PreviousRevision previousRevision(IFile file, IProject project)
    {
        PreviousRevision fromGit = fromGitHead(file, project);
        if (fromGit.isFound())
        {
            return fromGit;
        }
        return fromLocalHistory(file, fromGit);
    }

    /**
     * Gets the previous version of a file from VCS as text.
     * Tries git HEAD first, falls back to Eclipse Local History.
     * <p>
     * HEAD is the revision's own text, UTF-8, with a leading byte order mark stripped. Local
     * history is lines of that text joined with a line feed, which is how a module file is read.
     * </p>
     *
     * @param file the workspace file
     * @param project the containing project
     * @return previous file content as String, or {@code null} if no history available
     */
    public static String getPreviousVersion(IFile file, IProject project)
    {
        PreviousRevision revision = previousRevision(file, project);
        if (revision.origin() == PreviousRevision.Origin.LOCAL_HISTORY)
        {
            return localHistoryText(revision.bytes());
        }
        return revision.text();
    }

    /**
     * Reads a path out of HEAD. A repository and a work-tree-relative path are the whole input, so
     * this answers for any repository, including a temporary one.
     *
     * @param repository the repository to read
     * @param repoRelativePath the path inside the work tree, with forward slashes
     * @return the blob as that revision holds it, or the outcome that left nothing to read
     */
    public static PreviousRevision headRevision(Repository repository, String repoRelativePath)
    {
        try (RevWalk walk = new RevWalk(repository))
        {
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null)
            {
                return PreviousRevision.missing(PreviousRevision.Outcome.NO_HEAD,
                    "HEAD resolves to nothing"); //$NON-NLS-1$
            }
            RevTree tree = walk.parseCommit(head).getTree();
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, repoRelativePath, tree))
            {
                if (treeWalk == null)
                {
                    return PreviousRevision.missing(PreviousRevision.Outcome.NOT_IN_HEAD,
                        repoRelativePath + " is not in HEAD"); //$NON-NLS-1$
                }
                byte[] bytes = repository.open(treeWalk.getObjectId(0)).getBytes();
                return PreviousRevision.found(bytes, PreviousRevision.Origin.GIT_HEAD,
                    repoRelativePath + " at HEAD"); //$NON-NLS-1$
            }
        }
        catch (IOException | RuntimeException e)
        {
            return PreviousRevision.missing(PreviousRevision.Outcome.READ_ERROR, describe(e));
        }
    }

    /**
     * Reads the file out of HEAD of the repository that holds the project.
     */
    private static PreviousRevision fromGitHead(IFile file, IProject project)
    {
        try (GitRepositoryAccess.Resolved resolved = GitRepositoryAccess.of(project))
        {
            if (resolved.repository == null)
            {
                return PreviousRevision.missing(PreviousRevision.Outcome.NOT_UNDER_GIT,
                    resolved.error);
            }
            String path = repoRelativePath(file, resolved.repository);
            if (path == null)
            {
                return PreviousRevision.missing(PreviousRevision.Outcome.NOT_UNDER_GIT,
                    "the file is outside the work tree of " //$NON-NLS-1$
                        + resolved.repository.getWorkTree().getAbsolutePath());
            }
            return headRevision(resolved.repository, path);
        }
        catch (LinkageError e)
        {
            // The git classes are absent from this installation: nothing to read with, and the
            // outcome says so rather than reporting a new file.
            return PreviousRevision.missing(PreviousRevision.Outcome.NO_EGIT, describe(e));
        }
        catch (RuntimeException e)
        {
            return PreviousRevision.missing(PreviousRevision.Outcome.READ_ERROR, describe(e));
        }
    }

    /**
     * Where the file sits inside the repository's work tree, or {@code null} when it is not under
     * it - a linked resource, or a project location beside the repository rather than inside it.
     */
    private static String repoRelativePath(IFile file, Repository repository)
    {
        File workTree = repository.getWorkTree();
        if (workTree == null || file.getLocation() == null)
        {
            return null;
        }
        Path root = workTree.toPath().toAbsolutePath().normalize();
        Path target = file.getLocation().toPath().toAbsolutePath().normalize();
        if (!target.startsWith(root))
        {
            return null;
        }
        return root.relativize(target).toString().replace(File.separatorChar, '/');
    }

    /**
     * The local history of the file, when git gave nothing to compare with. The reason git had
     * nothing is carried into the answer: a comparison against local history is a weaker one, and
     * the caller can see why it was the one made.
     */
    private static PreviousRevision fromLocalHistory(IFile file, PreviousRevision gitOutcome)
    {
        IFileState[] history;
        try
        {
            history = file.getHistory(null);
        }
        catch (Exception e)
        {
            return unreadableLocalHistory(e);
        }
        return fromLocalHistory(history, gitOutcome);
    }

    /**
     * The local-history answer from states already fetched.
     *
     * <p>A history state is read as bytes, not as text: a provider comparing against it receives
     * them through {@code IModuleSource.linesIn}, which is handed the revision as the history holds
     * it, so line terminators, a byte order mark, a missing last newline and an encoding that is
     * not UTF-8 all have to survive the read. A module that has a file of its own is compared with
     * {@link #localHistoryText} of those same bytes, the line-based read this path used before the
     * bytes were kept.</p>
     *
     * <p>Package-private so a state that throws can be fed in: a read that fails is a read error,
     * and the outcome it replaced is carried in the note - reporting the earlier outcome there
     * would claim the revision does not hold the file on the strength of a read that never
     * happened.</p>
     *
     * @param history the states the file has, newest first; may be {@code null} or empty
     * @param gitOutcome what looking in HEAD came to, carried into the note
     * @return the revision read out of the newest state, or an outcome saying why there is none
     */
    static PreviousRevision fromLocalHistory(IFileState[] history, PreviousRevision gitOutcome)
    {
        if (history == null || history.length == 0)
        {
            return gitOutcome;
        }
        try
        {
            byte[] bytes = readHistoryBytes(history[0]);
            return PreviousRevision.found(bytes, PreviousRevision.Origin.LOCAL_HISTORY,
                "git gave " + gitOutcome.outcome() + ": " + gitOutcome.note()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return unreadableLocalHistory(e);
        }
    }

    /**
     * One local-history state as it is stored, with nothing done to it on the way through.
     */
    private static byte[] readHistoryBytes(IFileState state) throws IOException, CoreException
    {
        try (InputStream is = state.getContents())
        {
            return is.readAllBytes();
        }
    }

    /**
     * The text an ordinary module is compared with when the revision is the local history.
     * <p>
     * UTF-8, terminators dropped, lines joined with {@code \n}, a leading byte order mark taken
     * off. That is how this path read a history state before the bytes were kept, and it is the
     * shape {@code readFileLines} gives the current text of a module file. The bytes themselves
     * stay on the revision: a provider is handed them unchanged.
     * </p>
     *
     * @param bytes the history state, or {@code null}
     * @return the lines joined with {@code \n}, or {@code null} when {@code bytes} is {@code null}
     */
    public static String localHistoryText(byte[] bytes)
    {
        if (bytes == null)
        {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)))
        {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (text.length() > 0)
                {
                    text.append("\n"); //$NON-NLS-1$
                }
                text.append(line);
            }
            if (text.length() > 0 && text.codePointAt(0) == 0xFEFF)
            {
                return text.substring(1);
            }
            return text.toString();
        }
        catch (IOException e)
        {
            // The source is a byte array and the charset replaces what it cannot decode, so this
            // read does not fail. The declaration on the reader is what remains.
            throw new IllegalStateException(e);
        }
    }

    /**
     * A local history that could not be read is a read error naming what was thrown. The git
     * outcome is part of the note, not the answer: the revision was never looked at.
     */
    private static PreviousRevision unreadableLocalHistory(Exception e)
    {
        Activator.logInfo("Local History not available: " + e.getMessage()); //$NON-NLS-1$
        return PreviousRevision.missing(PreviousRevision.Outcome.READ_ERROR,
            "reading the local history failed: " + describe(e)); //$NON-NLS-1$
    }

    /**
     * What to say about a failure: the message when there is one, the type otherwise.
     */
    private static String describe(Throwable e)
    {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getName() : message;
    }
}
