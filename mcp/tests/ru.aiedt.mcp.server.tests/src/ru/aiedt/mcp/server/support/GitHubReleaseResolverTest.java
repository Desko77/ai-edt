/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers how a source string is recognized as a GitHub repository.
 * <p>
 * Everything downstream is a network call, but this part is not: the same parameter accepts a path
 * on disk, a URL and a repository reference, and this is what tells them apart. Returning a repo for
 * something that was meant to be a file sends the install down the wrong road entirely, so a
 * non-match has to be an unambiguous {@code null} rather than a best guess.
 * </p>
 */
public class GitHubReleaseResolverTest
{
    @Test
    public void aRepositoryReferenceIsSplitIntoOwnerAndRepo()
    {
        assertArrayEquals(new String[]{"owner/repo", null}, //$NON-NLS-1$
            GitHubReleaseResolver.parseRepoSource("github:owner/repo")); //$NON-NLS-1$
    }

    @Test
    public void theShortPrefixMeansTheSameThing()
    {
        assertArrayEquals(GitHubReleaseResolver.parseRepoSource("github:owner/repo"), //$NON-NLS-1$
            GitHubReleaseResolver.parseRepoSource("gh:owner/repo")); //$NON-NLS-1$
    }

    @Test
    public void thePrefixIsCaseInsensitive()
    {
        assertArrayEquals(new String[]{"owner/repo", null}, //$NON-NLS-1$
            GitHubReleaseResolver.parseRepoSource("GitHub:owner/repo")); //$NON-NLS-1$
    }

    @Test
    public void surroundingSpaceIsIgnored()
    {
        assertArrayEquals(new String[]{"owner/repo", null}, //$NON-NLS-1$
            GitHubReleaseResolver.parseRepoSource("  github:owner/repo  ")); //$NON-NLS-1$
    }

    @Test
    public void aFragmentSelectsTheAssetPrefix()
    {
        assertArrayEquals(new String[]{"owner/repo", "MyExtension"}, //$NON-NLS-1$ //$NON-NLS-2$
            GitHubReleaseResolver.parseRepoSource("github:owner/repo#MyExtension")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyFragmentIsNoFragment()
    {
        // "#" with nothing after it is a caller mistake, not a request for an asset named "".
        assertArrayEquals(new String[]{"owner/repo", null}, //$NON-NLS-1$
            GitHubReleaseResolver.parseRepoSource("github:owner/repo#")); //$NON-NLS-1$
        assertArrayEquals(new String[]{"owner/repo", null}, //$NON-NLS-1$
            GitHubReleaseResolver.parseRepoSource("github:owner/repo#   ")); //$NON-NLS-1$
    }

    @Test
    public void aPathOrUrlIsNotARepositoryReference()
    {
        // The caller falls through to treating these as a file, which is the whole point of the
        // null: a wrong positive here turns a local install into a failed download.
        assertNull(GitHubReleaseResolver.parseRepoSource("C:/builds/MyExtension.cfe")); //$NON-NLS-1$
        assertNull(GitHubReleaseResolver.parseRepoSource("https://example.com/MyExtension.cfe")); //$NON-NLS-1$
        assertNull(GitHubReleaseResolver.parseRepoSource("owner/repo")); //$NON-NLS-1$
    }

    @Test
    public void aPrefixWithNothingBehindItIsNotARepository()
    {
        assertNull(GitHubReleaseResolver.parseRepoSource("github:")); //$NON-NLS-1$
        assertNull(GitHubReleaseResolver.parseRepoSource("gh:#MyExtension")); //$NON-NLS-1$
    }

    @Test
    public void nothingParsesToNothing()
    {
        assertNull(GitHubReleaseResolver.parseRepoSource(null));
        assertNull(GitHubReleaseResolver.parseRepoSource("")); //$NON-NLS-1$
        assertNull(GitHubReleaseResolver.parseRepoSource("   ")); //$NON-NLS-1$
    }
}
