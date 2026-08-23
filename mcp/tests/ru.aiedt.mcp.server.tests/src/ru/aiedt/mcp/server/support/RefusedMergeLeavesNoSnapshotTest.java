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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Holds what a refused merge leaves behind.
 * <p>
 * The support snapshot is taken before the merge is attempted, and several refusals arrive after
 * that: no decisions to apply, objects that could not be held back, a protection list past its
 * limit. Each of those wrote nothing at all, and each used to leave 976 KB in the project -
 * measured on a real configuration, three of them in one day. Beside the code that writes the file
 * stands its own rule, that reporting must leave no trace.
 * </p>
 * <p>
 * <b>What decides is not the refusal but whether writing could have begun.</b> That distinction is
 * the whole of this test. Deleting on any refusal would throw away the one thing that puts the
 * support model back after a merge that had started; keeping on every refusal is the litter this
 * fixes. An unknown state counts as written, deliberately: a stray megabyte costs disk, a missing
 * snapshot costs the modes a person set by hand.
 * </p>
 */
public class RefusedMergeLeavesNoSnapshotTest
{
    @Test
    public void aRefusalBeforeAnythingCouldBeWrittenTakesItsSnapshotWithIt() throws Exception
    {
        Path file = Files.createTempFile("aiedt-support-snapshot-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(file, "# AI-EDT support mode snapshot v1\n"); //$NON-NLS-1$

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.supportSnapshotFile = file.toString();
        outcome.mergeRefused = "no merge was run: there are no decisions to apply."; //$NON-NLS-1$
        outcome.writeMayHaveStarted = false;

        BmComparisonHelper.dropSnapshotNothingNeeds(outcome);

        assertFalse("a merge that wrote nothing has nothing to be restored from", //$NON-NLS-1$
            Files.exists(file));
        assertNull("the answer must stop naming a file that is no longer there", //$NON-NLS-1$
            outcome.supportSnapshotFile);
        assertNull(outcome.supportSnapshotKeptBecause);
    }

    @Test
    public void aRefusalAfterTheEnvironmentWasAskedToStartKeepsIt() throws Exception
    {
        // The environment can begin writing the moment it accepts the batch. Once that has
        // happened the snapshot may be the only way back, and it is kept even though the merge is
        // reported as refused.
        Path file = Files.createTempFile("aiedt-support-snapshot-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(file, "# AI-EDT support mode snapshot v1\n"); //$NON-NLS-1$

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.supportSnapshotFile = file.toString();
        outcome.mergeRefused = "the environment would not start the merge"; //$NON-NLS-1$
        outcome.writeMayHaveStarted = true;

        BmComparisonHelper.dropSnapshotNothingNeeds(outcome);

        assertTrue("what may be the only way back is not deleted on a guess", Files.exists(file)); //$NON-NLS-1$
        assertEquals(file.toString(), outcome.supportSnapshotFile);
        assertNotNull("keeping it without saying why reads as the litter this fixes", //$NON-NLS-1$
            outcome.supportSnapshotKeptBecause);
        Files.deleteIfExists(file);
    }

    @Test
    public void aMergeThatRanKeepsItsSnapshotUntouched() throws Exception
    {
        // No refusal at all: the ordinary successful merge, whose snapshot is the point of taking
        // one. Nothing here may remove it.
        Path file = Files.createTempFile("aiedt-support-snapshot-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.supportSnapshotFile = file.toString();
        outcome.mergeRefused = null;
        outcome.writeMayHaveStarted = true;

        BmComparisonHelper.dropSnapshotNothingNeeds(outcome);

        assertTrue(Files.exists(file));
        assertEquals(file.toString(), outcome.supportSnapshotFile);
        assertNull(outcome.supportSnapshotKeptBecause);
        Files.deleteIfExists(file);
    }

    @Test
    public void aFileAlreadyGoneIsNotAnError()
    {
        // Deleting what is not there has to be silent: a caller who removed the file themselves
        // between the merge and this point should not receive a complaint about it.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.supportSnapshotFile =
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
                "aiedt-no-such-snapshot-" + java.util.UUID.randomUUID() + ".tsv").toString(); //$NON-NLS-1$ //$NON-NLS-2$
        outcome.mergeRefused = "no merge was run"; //$NON-NLS-1$
        outcome.writeMayHaveStarted = false;

        BmComparisonHelper.dropSnapshotNothingNeeds(outcome);

        assertNull(outcome.supportSnapshotFile);
        assertNull(outcome.supportSnapshotKeptBecause);
    }

    @Test
    public void aTruncatedSnapshotReadsAsAGoodOneAndThatIsWhyItIsWrittenAside() throws Exception
    {
        // Not a defect in the reader, and not something to fix there: the header is all it can
        // check, and refusing a file whose row count it cannot know would refuse every valid one
        // too. It is the reason the writer publishes by renaming a finished file into place. Left
        // to write directly, a failure part-way through leaves this - a snapshot that restores a
        // fraction of the modes while reporting success.
        Path file = Files.createTempFile("aiedt-truncated-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        SupportSnapshot whole = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("id-1", "Demo", "3.2.1.505"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (int i = 0; i < 5; i++)
        {
            parent.modes.put(java.util.UUID.randomUUID(), "Edited"); //$NON-NLS-1$
        }
        whole.parents.add(parent);
        whole.write(file);

        java.util.List<String> lines = Files.readAllLines(file);
        Files.write(file, lines.subList(0, lines.size() - 3));

        SupportSnapshot half = SupportSnapshot.read(file);
        assertNull("the reader has no way to know rows are missing", half.cannotTell); //$NON-NLS-1$
        assertTrue("and it comes back short, which is the whole hazard", //$NON-NLS-1$
            half.entries() < whole.entries());
        Files.deleteIfExists(file);
    }

    @Test
    public void anEntryThatCannotBeWrittenDownStopsTheMerge() throws Exception
    {
        // Counting it was never enough. The count sat in the answer while the merge went ahead and
        // took those modes from the delivery, with nothing to put them back from - the one loss
        // the snapshot exists to prevent. The plan said this blocked a merge before the code did,
        // which is how it was found.
        SupportSnapshot snapshot = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("v1", "Demo", "3.2.1.505"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        parent.modes.put(java.util.UUID.randomUUID(), "Edited"); //$NON-NLS-1$
        snapshot.parents.add(parent);
        snapshot.withoutAnIdentifier = 1;

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.keepSnapshot("any", snapshot, outcome); //$NON-NLS-1$

        assertNotNull("a snapshot missing entries is not a snapshot", //$NON-NLS-1$
            outcome.supportSnapshotNote);
        assertNull("and nothing may be written from it", outcome.supportSnapshotFile); //$NON-NLS-1$
    }

    @Test
    public void aCompleteSnapshotIsNotStopped() throws Exception
    {
        // The ordinary case has to stay ordinary: a registry where every entry has an identity
        // must not be refused by the guard above.
        SupportSnapshot snapshot = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("v1", "Demo", "3.2.1.505"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        parent.modes.put(java.util.UUID.randomUUID(), "Edited"); //$NON-NLS-1$
        snapshot.parents.add(parent);
        snapshot.withoutAnIdentifier = 0;

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.keepSnapshot("no-such-project", snapshot, outcome); //$NON-NLS-1$

        assertNull("an absent project is a different matter and not this guard's business", //$NON-NLS-1$
            outcome.supportSnapshotNote);
    }

    @Test
    public void nothingHappensWhenNoSnapshotWasTaken()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.mergeRefused = "no merge was run"; //$NON-NLS-1$

        BmComparisonHelper.dropSnapshotNothingNeeds(outcome);

        assertNull(outcome.supportSnapshotFile);
        assertNull(outcome.supportSnapshotKeptBecause);
    }
}
