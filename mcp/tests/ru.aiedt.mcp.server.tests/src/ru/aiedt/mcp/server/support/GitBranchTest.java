/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers reading the current branch off disk.
 * <p>
 * Every case here is a real shape of {@code .git}, and the ones that are not a plain directory are
 * the point: a worktree and a submodule both put a file where a directory is expected, and a branch
 * whose name contains a slash is the one a naive reading gets wrong.
 * </p>
 */
public class GitBranchTest
{
    private Path root;

    @Before
    public void makeATree() throws Exception
    {
        root = Files.createTempDirectory("aiedt-git"); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(root))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** The ordinary case: a .git directory with HEAD naming a ref. */
    @Test
    public void anOrdinaryRepositoryReportsItsBranch() throws Exception
    {
        head(gitDir(root), "ref: refs/heads/main\n"); //$NON-NLS-1$

        assertEquals("main", GitBranch.at(root)); //$NON-NLS-1$
    }

    /**
     * A branch name with slashes comes back whole.
     * <p>
     * Cutting at the last slash - the obvious way to write this - would call
     * {@code feature/tax/report} just "report", and two different branches would then share a
     * binding.
     * </p>
     */
    @Test
    public void aBranchNameWithSlashesSurvives() throws Exception
    {
        head(gitDir(root), "ref: refs/heads/feature/tax/report\n"); //$NON-NLS-1$

        assertEquals("feature/tax/report", GitBranch.at(root)); //$NON-NLS-1$
    }

    /** A project inside the repository, not at its root, finds the repository above it. */
    @Test
    public void theSearchWalksUpward() throws Exception
    {
        head(gitDir(root), "ref: refs/heads/main\n"); //$NON-NLS-1$
        Path project = root.resolve("configuration").resolve("MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(project);

        assertEquals("main", GitBranch.at(project)); //$NON-NLS-1$
    }

    /** A worktree or submodule puts a file where the directory would be, pointing elsewhere. */
    @Test
    public void aGitFilePointingElsewhereIsFollowed() throws Exception
    {
        Path real = root.resolve("real-git"); //$NON-NLS-1$
        Files.createDirectories(real);
        head(real, "ref: refs/heads/wip\n"); //$NON-NLS-1$
        Path work = root.resolve("worktree"); //$NON-NLS-1$
        Files.createDirectories(work);
        Files.write(work.resolve(".git"), //$NON-NLS-1$
            ("gitdir: " + real.toAbsolutePath()).getBytes(StandardCharsets.UTF_8));

        assertEquals("wip", GitBranch.at(work)); //$NON-NLS-1$
    }

    /** A relative gitdir is resolved against the file that names it. */
    @Test
    public void aRelativeGitdirIsResolved() throws Exception
    {
        Path real = root.resolve("real-git"); //$NON-NLS-1$
        Files.createDirectories(real);
        head(real, "ref: refs/heads/wip\n"); //$NON-NLS-1$
        Path work = root.resolve("worktree"); //$NON-NLS-1$
        Files.createDirectories(work);
        Files.write(work.resolve(".git"), "gitdir: ../real-git".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("wip", GitBranch.at(work)); //$NON-NLS-1$
    }

    /**
     * A detached head is reported as detached rather than as a branch.
     * <p>
     * It matters because a binding made against it would be made against something that is not a
     * branch and will not come back, so the caller has to be able to tell.
     * </p>
     */
    @Test
    public void aDetachedHeadIsNotABranch() throws Exception
    {
        head(gitDir(root), "9f2c4b1e7a3d5c8f0b6e2a4d7c9f1b3e5a7c9d1f\n"); //$NON-NLS-1$

        String reported = GitBranch.at(root);

        assertTrue("it should say what it is: " + reported, reported.startsWith("(detached")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GitBranch.isBranch(reported));
    }

    /** Outside a repository there is no branch, and nothing throws. */
    @Test
    public void outsideARepositoryThereIsNothing()
    {
        assertNull(GitBranch.at(root));
        assertNull("a null path must not throw", GitBranch.of(null)); //$NON-NLS-1$
        assertFalse(GitBranch.isBranch(null));
        assertFalse(GitBranch.isBranch("")); //$NON-NLS-1$
    }

    /** A .git directory with no HEAD is not a branch either. */
    @Test
    public void aRepositoryWithoutAHeadReportsNothing() throws Exception
    {
        Files.createDirectories(root.resolve(".git")); //$NON-NLS-1$

        assertNull(GitBranch.at(root));
    }

    /** A .git file pointing at somewhere that is not there is not followed into nonsense. */
    @Test
    public void aBrokenGitLinkIsIgnored() throws Exception
    {
        Files.write(root.resolve(".git"), //$NON-NLS-1$
            "gitdir: /nowhere/at/all".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertNull(GitBranch.at(root));
    }

    /**
     * A broken .git file stops the search where it stands.
     * <p>
     * It is the repository boundary whether or not it can be followed. Walking past it lands in
     * whatever repository encloses this one and answers with ITS branch - a plausible name for the
     * wrong working tree, which is worse than admitting there is no answer.
     * </p>
     *
     * @throws Exception when the tree cannot be built
     */
    @Test
    public void aBrokenGitLinkDoesNotFallThroughToTheRepositoryAbove() throws Exception
    {
        head(gitDir(root), "ref: refs/heads/outer\n"); //$NON-NLS-1$
        Path inner = root.resolve("inner"); //$NON-NLS-1$
        Files.createDirectories(inner);
        Files.write(inner.resolve(".git"), "gitdir: /nowhere/at/all".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("the enclosing repository's branch is not this tree's branch", //$NON-NLS-1$
            GitBranch.at(inner));
    }

    private static Path gitDir(Path where) throws Exception
    {
        Path git = where.resolve(".git"); //$NON-NLS-1$
        Files.createDirectories(git);
        return git;
    }

    private static void head(Path gitDir, String content) throws Exception
    {
        Files.write(gitDir.resolve("HEAD"), content.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
    }
}
