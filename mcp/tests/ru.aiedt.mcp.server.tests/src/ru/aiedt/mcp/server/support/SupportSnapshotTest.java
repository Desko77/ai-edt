/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Guards the record that makes an update reversible.
 * <p>
 * A merge takes the vendor support model from the delivery, and the environment's merge rules do not
 * prevent it - measured on a stand. So the modes a person set are lost by an ordinary update, and
 * the only route back is a per-object record taken beforehand. What that record has to get right is
 * tested here: telling three kinds of difference apart, and surviving a trip through a file.
 * </p>
 */
public class SupportSnapshotTest
{
    private static final String VENDOR = "3ec1d97e-0000-0000-0000-000000000001"; //$NON-NLS-1$

    private static final UUID KEPT = UUID.fromString("11111111-1111-1111-1111-111111111111"); //$NON-NLS-1$

    private static final UUID LOST = UUID.fromString("22222222-2222-2222-2222-222222222222"); //$NON-NLS-1$

    private static final UUID REMOVED = UUID.fromString("33333333-3333-3333-3333-333333333333"); //$NON-NLS-1$

    private static final UUID DELIVERED = UUID.fromString("44444444-4444-4444-4444-444444444444"); //$NON-NLS-1$

    private Path folder;

    @Before
    public void makeFolder() throws IOException
    {
        folder = Files.createTempDirectory("aiedt-support-snapshot"); //$NON-NLS-1$
    }

    @After
    public void removeFolder() throws IOException
    {
        if (folder == null || !Files.exists(folder))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void anObjectThatSurvivedAndLostItsModeIsTheDamage()
    {
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.parents.get(0).modes.put(LOST, "CHANGES_ALLOWED"); //$NON-NLS-1$
        SupportSnapshot after = snapshotWith(VENDOR, "2.5.16.9"); //$NON-NLS-1$
        after.parents.get(0).modes.put(LOST, "CHANGES_NOT_ALLOWED"); //$NON-NLS-1$

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertFalse(drift.isClean());
        assertEquals(1, drift.changed.size());
        assertTrue("a count says damage happened; only the identity says which work is now " //$NON-NLS-1$
            + "unprotected", drift.changed.get(0).contains(LOST.toString()));
        assertTrue(drift.changed.get(0).contains("CHANGES_ALLOWED")); //$NON-NLS-1$
        assertTrue(drift.changed.get(0).contains("CHANGES_NOT_ALLOWED")); //$NON-NLS-1$
    }

    @Test
    public void anObjectTheDeliveryBroughtIsNotDamage()
    {
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        SupportSnapshot after = snapshotWith(VENDOR, "2.5.16.9"); //$NON-NLS-1$
        after.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        after.parents.get(0).modes.put(DELIVERED, "CHANGES_NOT_ALLOWED"); //$NON-NLS-1$

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertTrue("an object with no mode in the snapshot takes the default from the update " //$NON-NLS-1$
            + "rules, and counting that as loss would inflate the damage figure", drift.isClean());
        assertEquals(1, drift.arrived);
        assertEquals(0, drift.gone);
    }

    @Test
    public void anObjectTheUpdateRemovedCannotTakeAMode()
    {
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.parents.get(0).modes.put(REMOVED, "CHANGES_ALLOWED"); //$NON-NLS-1$
        SupportSnapshot after = snapshotWith(VENDOR, "2.5.16.9"); //$NON-NLS-1$

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertTrue(drift.isClean());
        assertEquals(1, drift.gone);
        assertEquals(0, drift.arrived);
    }

    @Test
    public void vendorsAreMatchedOnIdentityAndNotOnVersion()
    {
        // The whole point of a snapshot is to survive an update, and an update changes the vendor
        // version. Matching on version would find no vendor at all afterwards.
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        SupportSnapshot after = snapshotWith(VENDOR, "2.5.16.9"); //$NON-NLS-1$
        after.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertTrue(drift.isClean());
        assertEquals(1, drift.parentsMatched.size());
        assertTrue("a report that hid the version change would leave a person unable to see " //$NON-NLS-1$
            + "which update they are looking at", drift.parentsMatched.get(0).contains("2.5.14.20"));
        assertTrue(drift.parentsGone.isEmpty());
        assertTrue(drift.parentsNew.isEmpty());
    }

    @Test
    public void aVendorNoLongerListedTakesItsObjectsWithIt()
    {
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        before.parents.get(0).modes.put(LOST, "CANCELLED"); //$NON-NLS-1$
        SupportSnapshot after = new SupportSnapshot();

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertTrue("with no vendor there is no mode to compare against, so this is not a mode " //$NON-NLS-1$
            + "that changed", drift.isClean());
        assertEquals(2, drift.gone);
        assertEquals(1, drift.parentsGone.size());
    }

    @Test
    public void aModeSetWhereThereWasNoneCountsAsChanged()
    {
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.parents.get(0).modes.put(KEPT, null);
        SupportSnapshot after = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        after.parents.get(0).modes.put(KEPT, "CHANGES_NOT_ALLOWED"); //$NON-NLS-1$

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertFalse("an object that had no mode and now has one was changed by the update just " //$NON-NLS-1$
            + "as much as one whose mode was replaced", drift.isClean());
        assertEquals(1, drift.changed.size());
    }

    @Test
    public void aSnapshotSurvivesTheTripThroughAFile() throws IOException
    {
        SupportSnapshot before = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        before.projectName = "Demo"; //$NON-NLS-1$
        before.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        before.parents.get(0).modes.put(LOST, null);
        Path file = folder.resolve("modes.tsv"); //$NON-NLS-1$
        before.write(file);

        SupportSnapshot back = SupportSnapshot.read(file);
        assertNull(back.cannotTell);
        assertEquals("Demo", back.projectName); //$NON-NLS-1$
        assertEquals(2, back.entries());
        SupportSnapshot.Parent parent = back.parentById(VENDOR);
        assertNotNull(parent);
        assertEquals("Standard", parent.name); //$NON-NLS-1$
        assertEquals("2.5.14.20", parent.version); //$NON-NLS-1$
        assertEquals("CHANGES_ALLOWED", parent.modes.get(KEPT)); //$NON-NLS-1$
        assertTrue("an object recorded with no mode has to come back with no mode, not be " //$NON-NLS-1$
            + "dropped: it is one of the objects a restore has to account for",
            parent.modes.containsKey(LOST));
        assertNull(parent.modes.get(LOST));
        assertTrue(SupportSnapshot.compare(before, back).isClean());
    }

    @Test
    public void theWriteCreatesTheFolderItWasPointedAt() throws IOException
    {
        // The path a merge writes to is inside the project's settings folder, which a project
        // taken fresh from version control need not have yet.
        SupportSnapshot snapshot = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        snapshot.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        Path file = folder.resolve("not-there-yet").resolve("modes.tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        snapshot.write(file);
        assertTrue(Files.exists(file));
    }

    @Test
    public void afileThatIsNotASnapshotIsRefusedRatherThanParsed() throws IOException
    {
        Path file = folder.resolve("something-else.txt"); //$NON-NLS-1$
        Files.writeString(file, "id\tmode\nsomething\telse\n"); //$NON-NLS-1$

        SupportSnapshot read = SupportSnapshot.read(file);
        assertNotNull("a restore driven by the wrong file would write modes nobody chose, and " //$NON-NLS-1$
            + "there is no undo for that beyond another snapshot", read.cannotTell);
        assertTrue(read.isEmpty());
    }

    @Test
    public void anEmptyFileIsRefusedToo() throws IOException
    {
        Path file = folder.resolve("empty.tsv"); //$NON-NLS-1$
        Files.writeString(file, ""); //$NON-NLS-1$
        assertNotNull(SupportSnapshot.read(file).cannotTell);
    }

    @Test
    public void anUnreadableRowIsCountedRatherThanSkippedSilently() throws IOException
    {
        Path file = folder.resolve("partly-broken.tsv"); //$NON-NLS-1$
        Files.writeString(file, SupportSnapshot.HEADER + "\n" //$NON-NLS-1$
            + "# vendor\t" + VENDOR + "\tStandard\t2.5.14.20\n" //$NON-NLS-1$ //$NON-NLS-2$
            + VENDOR + "\t" + KEPT + "\tCHANGES_ALLOWED\n" //$NON-NLS-1$ //$NON-NLS-2$
            + VENDOR + "\tnot-an-identity\tCHANGES_ALLOWED\n"); //$NON-NLS-1$

        SupportSnapshot read = SupportSnapshot.read(file);
        assertNull(read.cannotTell);
        assertEquals(1, read.entries());
        assertEquals("a restore that skipped rows without saying so would report a complete run", //$NON-NLS-1$
            1, read.unresolved);
    }

    /**
     * A snapshot read off disk remembers where it came from.
     * <p>
     * A restore is itself a write, and the record of what it replaced has to land beside the file
     * that caused it. Without the path there is nowhere obvious to put that record, and a restore
     * that turns out to have been the wrong one has nowhere to go back to.
     * </p>
     */
    @Test
    public void aSnapshotReadFromAFileRemembersThePath() throws IOException
    {
        SupportSnapshot snapshot = snapshotWith(VENDOR, "2.5.14.20"); //$NON-NLS-1$
        snapshot.parents.get(0).modes.put(KEPT, "CHANGES_ALLOWED"); //$NON-NLS-1$
        Path file = folder.resolve("modes.tsv"); //$NON-NLS-1$
        snapshot.write(file);

        SupportSnapshot back = SupportSnapshot.read(file);
        assertEquals(file.toString(), back.sourcePath);
        assertNull("one built in memory has no file to point at, and inventing one would put a " //$NON-NLS-1$
            + "record of a restore somewhere nobody looks", snapshot.sourcePath);
    }

    @Test
    public void anEmptySnapshotHasNothingToRestore()
    {
        assertTrue(new SupportSnapshot().isEmpty());
        assertEquals(0, new SupportSnapshot().entries());
        assertNull(new SupportSnapshot().parentById(VENDOR));
    }

    /**
     * Builds a snapshot holding one vendor configuration and no modes yet.
     *
     * @param id the vendor identity.
     * @param version the vendor version.
     * @return the snapshot
     */
    private static SupportSnapshot snapshotWith(String id, String version)
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        snapshot.parents.add(new SupportSnapshot.Parent(id, "Standard", version)); //$NON-NLS-1$
        return snapshot;
    }
}
