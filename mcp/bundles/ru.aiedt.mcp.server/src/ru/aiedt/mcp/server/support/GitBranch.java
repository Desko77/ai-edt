/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.resources.IProject;

/**
 * Which branch a project is on, read straight off disk.
 * <p>
 * No git library, and none wanted. The one question asked here is answered by one file: {@code HEAD}
 * names a ref, and the last part of the ref is the branch. Pulling in a git implementation to read
 * a line of text would be the tail wagging the dog, and it would land this plugin in the business of
 * having opinions about repositories - which the agent driving it already has a shell for.
 * </p>
 * <p>
 * The cases that are not one file are the ones worth being careful about: a worktree and a submodule
 * both put a {@code .git} FILE where a directory is expected, pointing elsewhere, and a repository
 * with no commits yet names a branch that does not exist. Each is handled and each is a real
 * situation somebody works in.
 * </p>
 */
public final class GitBranch
{
    private static final String GIT = ".git"; //$NON-NLS-1$

    private static final String HEAD = "HEAD"; //$NON-NLS-1$

    private static final String REF_PREFIX = "ref:"; //$NON-NLS-1$

    private static final String GITDIR_PREFIX = "gitdir:"; //$NON-NLS-1$

    private static final int DETACHED_SHA_SHOWN = 8;

    private GitBranch()
    {
    }

    /**
     * The branch a project's working tree is on.
     *
     * @param project the project, possibly {@code null} or without a location on disk.
     * @return the branch name, a short form of the commit when the head is detached, or {@code null}
     *         when the project is in no repository at all
     */
    public static String of(IProject project)
    {
        if (project == null || project.getLocation() == null)
        {
            return null;
        }
        return at(project.getLocation().toFile().toPath());
    }

    /**
     * The branch a directory is on.
     *
     * @param start where to begin looking; the search walks upward from here.
     * @return the branch name, a short commit for a detached head, or {@code null} outside a
     *         repository
     */
    public static String at(Path start)
    {
        Path gitDir = gitDirectoryAbove(start);
        if (gitDir == null)
        {
            return null;
        }
        Path head = gitDir.resolve(HEAD);
        if (!Files.isRegularFile(head))
        {
            return null;
        }
        try
        {
            String text = new String(Files.readAllBytes(head), StandardCharsets.UTF_8).trim();
            if (text.startsWith(REF_PREFIX))
            {
                String ref = text.substring(REF_PREFIX.length()).trim();
                int lastSlash = ref.lastIndexOf('/');
                // A branch name may itself contain slashes - feature/thing/more - and only the
                // refs/heads/ part in front is fixed. Cutting at the LAST slash would call that
                // branch "more"; cutting the known prefix keeps the name somebody typed.
                String head5 = "refs/heads/"; //$NON-NLS-1$
                if (ref.startsWith(head5))
                {
                    String branch = ref.substring(head5.length());
                    return branch.isEmpty() ? null : branch;
                }
                return lastSlash >= 0 && lastSlash + 1 < ref.length() ? ref.substring(lastSlash + 1) : null;
            }
            if (text.length() >= DETACHED_SHA_SHOWN)
            {
                // Detached. Reported rather than hidden, because a binding made here would be made
                // against something that is not a branch and will not come back.
                return "(detached at " + text.substring(0, DETACHED_SHA_SHOWN) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return null;
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Whether a name came back from {@link #at} as a real branch rather than a detached head.
     *
     * @param branch whatever {@link #at} returned.
     * @return true when it is a branch somebody could check out by name
     */
    public static boolean isBranch(String branch)
    {
        return branch != null && !branch.isEmpty() && !branch.startsWith("(detached"); //$NON-NLS-1$
    }

    /**
     * Finds the git directory governing a path.
     * <p>
     * Walks upward, because an EDT project usually sits in a subdirectory of the repository rather
     * than at its root. A {@code .git} file rather than a directory is a worktree or a submodule,
     * and it says where the real one is.
     * </p>
     *
     * @param start where to begin.
     * @return the git directory, or {@code null} when there is none above this path
     */
    private static Path gitDirectoryAbove(Path start)
    {
        Path here = start;
        while (here != null)
        {
            Path candidate = here.resolve(GIT);
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            if (Files.isRegularFile(candidate))
            {
                // A .git FILE is the repository boundary whether or not it can be followed. Walking
                // past a broken one lands in the enclosing repository and answers with ITS branch -
                // a plausible name for the wrong tree, which is worse than no name at all.
                return readGitLink(candidate, here);
            }
            here = here.getParent();
        }
        return null;
    }

    /**
     * Follows a {@code .git} file to the directory it names.
     *
     * @param link the {@code .git} file.
     * @param base the directory it sits in, for resolving a relative target.
     * @return the directory, or {@code null} when the file says nothing usable
     */
    private static Path readGitLink(Path link, Path base)
    {
        try
        {
            String text = new String(Files.readAllBytes(link), StandardCharsets.UTF_8).trim();
            if (!text.startsWith(GITDIR_PREFIX))
            {
                return null;
            }
            Path target = Paths.get(text.substring(GITDIR_PREFIX.length()).trim());
            Path resolved = target.isAbsolute() ? target : base.resolve(target).normalize();
            return Files.isDirectory(resolved) ? resolved : null;
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }
}
