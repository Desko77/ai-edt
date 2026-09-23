/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.PreviousRevision.Origin;
import ru.aiedt.mcp.server.support.PreviousRevision.Outcome;

/**
 * What came of looking for a previous revision is its own answer, and every outcome says which one
 * it is.
 *
 * <p>The lookup fails in several ways that used to arrive as one {@code null} - no repository, no
 * commits, the file never committed, a read that threw - and a diff reported each of them as a new
 * file. Each outcome has its own sentence here, and the sentences are checked to differ from one
 * another, so an outcome cannot quietly inherit another one's meaning.</p>
 *
 * <p>Reading a path out of HEAD takes a repository and a path and nothing from the workspace, so
 * the outcomes a repository decides - a revision read, a path the revision does not hold, a
 * repository with nothing committed, a read that threw - are decided here against a temporary
 * repository, with the git classes this installation ships and no IDE.</p>
 */
public class PreviousRevisionTest
{
    /** The byte order mark, built from its code point so this file stays ASCII. */
    private static final String BOM = Character.toString(0xFEFF); //$NON-NLS-1$

    private static Path repoRoot;

    private static Repository repository;

    private static Git git;

    @BeforeClass
    public static void aRepositoryWithOneCommit() throws Exception
    {
        repoRoot = Files.createTempDirectory("aiedt-previous-revision"); //$NON-NLS-1$
        Path module = repoRoot.resolve("src/Module.bsl"); //$NON-NLS-1$
        Files.createDirectories(module.getParent());
        Files.writeString(module, BOM + "// committed\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        git = Git.init().setDirectory(repoRoot.toFile()).call();
        git.add().addFilepattern("src/Module.bsl").call(); //$NON-NLS-1$
        git.commit().setAuthor("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
            .setMessage("Committed sources").call(); //$NON-NLS-1$
        repository = git.getRepository();
    }

    @AfterClass
    public static void theRepositoryGoes() throws Exception
    {
        if (git != null)
        {
            git.close();
        }
        if (repoRoot != null)
        {
            try (var walk = Files.walk(repoRoot))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    // -- The revision itself --

    /**
     * A revision that was read carries its text, its bytes and where it came from.
     */
    @Test
    public void aRevisionThatWasReadCarriesItsTextAndItsOrigin()
    {
        PreviousRevision revision = PreviousRevision.found("// text\n".getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$
            Origin.GIT_HEAD, "src/Module.bsl at HEAD"); //$NON-NLS-1$
        assertTrue(revision.isFound());
        assertEquals(Outcome.FOUND, revision.outcome());
        assertEquals(Origin.GIT_HEAD, revision.origin());
        assertEquals("git HEAD", revision.label()); //$NON-NLS-1$
        assertEquals("// text\n", revision.text()); //$NON-NLS-1$
        assertNotNull(revision.bytes());
        assertNull(revision.explanation());
    }

    /**
     * A revision read as text loses a leading byte order mark, the way a module file is read.
     */
    @Test
    public void aRevisionReadAsTextLosesTheByteOrderMark()
    {
        PreviousRevision revision = PreviousRevision.found((BOM + "// text").getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$
            Origin.LOCAL_HISTORY, null);
        assertEquals("// text", revision.text()); //$NON-NLS-1$
        assertEquals("local history", revision.label()); //$NON-NLS-1$
    }

    /**
     * An empty revision is empty text, not a failure.
     */
    @Test
    public void anEmptyRevisionIsEmptyText()
    {
        assertEquals("", PreviousRevision.found(new byte[0], Origin.GIT_HEAD, null).text()); //$NON-NLS-1$
    }

    /**
     * A revision that was not found carries no text at all, so a caller cannot mistake it for an
     * empty file.
     */
    @Test
    public void aRevisionThatWasNotFoundCarriesNoText()
    {
        PreviousRevision revision = PreviousRevision.missing(Outcome.NOT_UNDER_GIT, "no repository"); //$NON-NLS-1$
        assertFalse(revision.isFound());
        assertEquals(Outcome.NOT_UNDER_GIT, revision.outcome());
        assertNull(revision.origin());
        assertNull(revision.label());
        assertNull(revision.bytes());
        assertNull(revision.text());
        assertEquals("no repository", revision.note()); //$NON-NLS-1$
    }

    /**
     * FOUND is the one outcome that means there is a revision, so building it as missing is a
     * programming error and says so.
     */
    @Test
    public void foundCannotBeBuiltAsMissing()
    {
        try
        {
            PreviousRevision.missing(Outcome.FOUND, null);
            fail("FOUND carries a revision"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("FOUND")); //$NON-NLS-1$
        }
    }

    // -- What the answer says about each outcome --

    /**
     * Every outcome names itself, and no two outcomes say the same thing: this is what stops one
     * failure from being reported as another.
     */
    @Test
    public void everyOutcomeHasItsOwnSentence()
    {
        Set<String> sentences = new LinkedHashSet<>();
        for (Outcome outcome : Outcome.values())
        {
            if (outcome == Outcome.FOUND)
            {
                continue;
            }
            String sentence = PreviousRevision.missing(outcome, null).explanation();
            assertNotNull(outcome.name(), sentence);
            assertFalse(outcome.name(), sentence.isEmpty());
            sentences.add(sentence);
        }
        assertEquals("each outcome has its own sentence", Outcome.values().length - 1, sentences.size()); //$NON-NLS-1$
    }

    /**
     * Only the outcome that means the revision is missing the module reads as new. Each of the
     * others describes its own case, so this does not pin their words down; it pins down the one
     * statement none of them may make, which is the defect this whole type exists to remove.
     */
    @Test
    public void onlyTheMissingModuleOutcomeReadsAsNew()
    {
        for (Outcome outcome : Outcome.values())
        {
            if (outcome == Outcome.FOUND || outcome == Outcome.NOT_IN_HEAD)
            {
                continue;
            }
            String sentence = PreviousRevision.missing(outcome, null).explanation();
            assertFalse(outcome.name(), sentence.contains("is new")); //$NON-NLS-1$
            assertFalse(outcome.name(), sentence.contains("new module")); //$NON-NLS-1$
            assertFalse(outcome.name(), sentence.contains("new file")); //$NON-NLS-1$
        }
        String missing = PreviousRevision.missing(Outcome.NOT_IN_HEAD, null).explanation();
        assertTrue(missing, missing.contains("it is new")); //$NON-NLS-1$
    }

    /**
     * The provider that does not read previous revisions is named in the sentence, so the answer
     * says who could not answer rather than only that nobody did.
     */
    @Test
    public void theProviderOutcomeNamesTheProviderKind()
    {
        String sentence = PreviousRevision.missing(Outcome.PROVIDER_UNSUPPORTED, "ProbeModule").explanation(); //$NON-NLS-1$
        assertTrue(sentence, sentence.contains("the provider ProbeModule does not read previous revisions")); //$NON-NLS-1$
        // Without a kind the sentence still stands on its own.
        assertTrue(PreviousRevision.missing(Outcome.PROVIDER_UNSUPPORTED, null).explanation() //$NON-NLS-1$
            .contains("the provider does not read previous revisions")); //$NON-NLS-1$
    }

    /**
     * The detail a lookup left behind is carried into the sentence, so the answer says what was
     * tried and not only that it failed.
     */
    @Test
    public void theNoteIsCarriedIntoTheSentence()
    {
        assertTrue(PreviousRevision.missing(Outcome.NO_HEAD, "HEAD resolves to nothing").explanation() //$NON-NLS-1$ //$NON-NLS-2$
            .endsWith(": HEAD resolves to nothing")); //$NON-NLS-1$
        assertTrue(PreviousRevision.missing(Outcome.READ_ERROR, "the object is missing").explanation() //$NON-NLS-1$ //$NON-NLS-2$
            .contains("could not be read: the object is missing")); //$NON-NLS-1$
    }

    /**
     * The installation without the git classes is a case that cannot be provoked where the classes
     * are present, so the answer for it is selected and checked here: it names the absent classes
     * and does not read as a new file.
     */
    @Test
    public void theAbsentGitClassesAreNamedThoughThatCaseCannotBeReached()
    {
        String sentence = PreviousRevision.missing(Outcome.NO_EGIT, "NoClassDefFoundError").explanation(); //$NON-NLS-1$
        assertTrue(sentence, sentence.contains("the git classes are not available on this installation")); //$NON-NLS-1$
        assertFalse(sentence, sentence.contains("it is new")); //$NON-NLS-1$
    }

    // -- Reading a path out of a real repository --

    /**
     * A committed path comes out of HEAD as the blob holds it: bytes, origin and the revision it
     * was read from.
     */
    @Test
    public void aCommittedPathComesOutOfHead() throws Exception
    {
        PreviousRevision revision = GitDiffUtils.headRevision(repository, "src/Module.bsl"); //$NON-NLS-1$
        assertTrue(revision.note(), revision.isFound());
        assertEquals(Origin.GIT_HEAD, revision.origin());
        assertEquals("git HEAD", revision.label()); //$NON-NLS-1$
        // The byte order mark is in the bytes and out of the text, like every other module read.
        assertTrue(revision.bytes().length > 3);
        assertEquals("// committed\n", revision.text()); //$NON-NLS-1$
        assertTrue(revision.note(), revision.note().contains("src/Module.bsl at HEAD")); //$NON-NLS-1$
    }

    /**
     * A path the revision does not hold is the one outcome that means the module is new, and the
     * answer says which path was looked for.
     */
    @Test
    public void aPathTheRevisionDoesNotHoldIsTheNewModuleOutcome()
    {
        PreviousRevision revision = GitDiffUtils.headRevision(repository, "src/Nothing.bsl"); //$NON-NLS-1$
        assertFalse(revision.isFound());
        assertEquals(Outcome.NOT_IN_HEAD, revision.outcome());
        assertNull(revision.text());
        assertTrue(revision.note(), revision.note().contains("src/Nothing.bsl is not in HEAD")); //$NON-NLS-1$
    }

    /**
     * A repository with nothing committed has no revision to read, which is not the same as a file
     * that was never committed.
     */
    @Test
    public void aRepositoryWithNothingCommittedSaysSo() throws Exception
    {
        Path empty = Files.createTempDirectory("aiedt-no-commits"); //$NON-NLS-1$
        try (Git none = Git.init().setDirectory(empty.toFile()).call())
        {
            PreviousRevision revision = GitDiffUtils.headRevision(none.getRepository(), "src/Module.bsl"); //$NON-NLS-1$
            assertFalse(revision.isFound());
            assertEquals(Outcome.NO_HEAD, revision.outcome());
            assertTrue(revision.note(), revision.note().contains("HEAD resolves to nothing")); //$NON-NLS-1$
        }
        finally
        {
            try (var walk = Files.walk(empty))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    /**
     * A read that threw is reported as a read error with what was thrown, not as a new module: the
     * repository names a commit nothing holds, which is how a broken repository answers.
     */
    @Test
    public void aReadThatThrewIsNotANewModule() throws Exception
    {
        Path broken = Files.createTempDirectory("aiedt-broken-repo"); //$NON-NLS-1$
        try (Git none = Git.init().setDirectory(broken.toFile()).call())
        {
            Path ref = broken.resolve(".git/refs/heads/master"); //$NON-NLS-1$
            Files.createDirectories(ref.getParent());
            Files.writeString(ref, "0123456789012345678901234567890123456789\n", StandardCharsets.US_ASCII); //$NON-NLS-1$
            PreviousRevision revision = GitDiffUtils.headRevision(none.getRepository(), "src/Module.bsl"); //$NON-NLS-1$
            assertFalse(revision.isFound());
            assertEquals(revision.note(), Outcome.READ_ERROR, revision.outcome());
            assertNotNull(revision.note());
        }
        finally
        {
            try (var walk = Files.walk(broken))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
