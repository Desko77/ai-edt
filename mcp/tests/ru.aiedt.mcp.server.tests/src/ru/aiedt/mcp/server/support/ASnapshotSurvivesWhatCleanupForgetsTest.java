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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Which support snapshots a project keeps, and which it may remove.
 * <p>
 * Every successful merge leaves one, around a megabyte and a half on a configuration of nine
 * thousand objects, and nothing removed them: five were lying in one project before an experiment
 * on 25.08. They are not litter - a snapshot is the only way to put the support model back - so the
 * question is which one may go, not how to delete them.
 * </p>
 */
public class ASnapshotSurvivesWhatCleanupForgetsTest
{
    /**
     * Its own directory rather than a JUnit rule: the test fragment imports org.junit,
     * org.junit.runner and org.junit.runners, and a package it does not import does not
     * resolve in the runtime the suite actually runs in.
     */
    private Path settings;

    @Before
    public void makeSettingsDirectory() throws IOException
    {
        settings = Files.createTempDirectory("aiedt-snapshot-test");
    }

    @After
    public void removeSettingsDirectory() throws IOException
    {
        if (settings == null)
        {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.walk(settings))
        {
            List<Path> deepestFirst = new java.util.ArrayList<>(
                entries.collect(java.util.stream.Collectors.toList()));
            java.util.Collections.reverse(deepestFirst);
            for (Path entry : deepestFirst)
            {
                Files.deleteIfExists(entry);
            }
        }
    }

    private Path snapshot(String stamp, boolean marked) throws IOException
    {
        Path file = settings.resolve("aiedt-support-snapshot-" + stamp + ".tsv");
        List<String> lines = marked
            ? Arrays.asList(SupportSnapshot.HEADER, SupportSnapshot.PROTECTED, "# project\tP",
                "a\tb\tCHANGES_ALLOWED\tName")
            : Arrays.asList(SupportSnapshot.HEADER, "# project\tP", "a\tb\tCHANGES_ALLOWED\tName");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void aMarkedSnapshotIsProtectedAndAPlainOneIsNot() throws IOException
    {
        assertTrue(SupportSnapshotStore.isProtected(snapshot("20260101-000001-aaaaaaaa", true)));
        assertFalse(SupportSnapshotStore.isProtected(snapshot("20260101-000002-bbbbbbbb", false)));
    }

    /**
     * A file a crash left mid-publish reads like this. Calling it ordinary would delete exactly the
     * snapshot that matters most, so the unknown answer is the safe one.
     */
    @Test
    public void whatCannotBeReadCountsAsProtected() throws IOException
    {
        Path torn = settings.resolve("aiedt-support-snapshot-20260101-000003-cccccccc.tsv");
        Files.write(torn, new byte[] { (byte)0xff, (byte)0xfe, (byte)0xff });
        assertTrue(SupportSnapshotStore.isProtected(torn));
    }

    @Test
    public void theLimitLeavesTheNewestOrdinaryOnes() throws IOException
    {
        for (int i = 1; i <= 5; i++)
        {
            snapshot("2026010" + i + "-000000-0000000" + i, false);
        }
        assertEquals(2, SupportSnapshotStore.prune(settings, 3));
        assertEquals(3, SupportSnapshotStore.list(settings).size());
        assertTrue("the newest ordinary snapshot stays",
            Files.exists(settings
                .resolve("aiedt-support-snapshot-20260105-000000-00000005.tsv")));
    }

    /** The whole point: a protected snapshot is not counted and is never the one removed. */
    @Test
    public void aProtectedSnapshotIsNeitherCountedNorRemoved() throws IOException
    {
        Path held = snapshot("20260101-000000-00000001", true);
        for (int i = 2; i <= 5; i++)
        {
            snapshot("2026010" + i + "-000000-0000000" + i, false);
        }
        SupportSnapshotStore.prune(settings, 3);
        assertTrue("the oldest file of all is the one that had to survive", Files.exists(held));
        assertEquals("three ordinary ones plus the protected one",
            4, SupportSnapshotStore.list(settings).size());
    }

    /**
     * More protected snapshots than the limit does not block anything and does not remove them:
     * refusing new merges would let one indeterminate merge stop all further work, which is worse
     * than the room they take.
     */
    @Test
    public void moreProtectedThanTheLimitIsAllowed() throws IOException
    {
        for (int i = 1; i <= 5; i++)
        {
            snapshot("2026010" + i + "-000000-0000000" + i, true);
        }
        assertEquals(0, SupportSnapshotStore.prune(settings, 3));
        assertEquals(5, SupportSnapshotStore.protectedIn(settings).size());
    }

    @Test
    public void releasingASnapshotMakesItOrdinary() throws IOException
    {
        Path held = snapshot("20260101-000000-00000001", true);
        assertNull(SupportSnapshotStore.clearProtection(held));
        assertFalse(SupportSnapshotStore.isProtected(held));
        assertTrue("the rows have to survive the rewrite",
            Files.readAllLines(held, StandardCharsets.UTF_8).contains("a\tb\tCHANGES_ALLOWED\tName"));
    }

    /** Releasing what is already ordinary is not an error: the caller asked for a state it is in. */
    @Test
    public void releasingTwiceIsNotAnError() throws IOException
    {
        Path held = snapshot("20260101-000000-00000001", true);
        assertNull(SupportSnapshotStore.clearProtection(held));
        assertNull(SupportSnapshotStore.clearProtection(held));
    }

    @Test
    public void releasingWhatIsNotThereSaysSo()
    {
        assertNotNull(SupportSnapshotStore.clearProtection(
            settings.resolve("aiedt-support-snapshot-nope.tsv")));
    }

    /** Only snapshots are listed; whatever else the settings directory holds is not ours. */
    @Test
    public void onlySnapshotsAreListed() throws IOException
    {
        snapshot("20260101-000000-00000001", false);
        Files.write(settings.resolve("aiedt-markers.yaml"),
            Arrays.asList("markers:"), StandardCharsets.UTF_8);
        Files.write(settings.resolve("aiedt-clusters.yaml"),
            Arrays.asList("clusters:"), StandardCharsets.UTF_8);
        assertEquals(1, SupportSnapshotStore.list(settings).size());
    }

    /**
     * Restoring support modes writes its undo record beside the snapshot it restored from, named
     * after it. That record is the only way to reverse a restore, and the file pattern alone counts
     * it as an ordinary snapshot - so cleanup would delete exactly that.
     */
    @Test
    public void anUndoRecordIsNotASnapshot() throws IOException
    {
        Path snapshotFile = snapshot("20260101-000000-00000001", false);
        String undoName = snapshotFile.getFileName() + ".before-restore.tsv";
        Path undo = settings.resolve(undoName);
        Files.write(undo, Arrays.asList(SupportSnapshot.HEADER), StandardCharsets.UTF_8);
        Path secondUndo = settings.resolve(snapshotFile.getFileName() + ".before-restore.2.tsv");
        Files.write(secondUndo, Arrays.asList(SupportSnapshot.HEADER), StandardCharsets.UTF_8);

        assertEquals("only the snapshot itself is ours", 1, SupportSnapshotStore.list(settings).size());
        SupportSnapshotStore.prune(settings, 0);
        assertTrue("the undo record survives cleanup", Files.exists(undo));
        assertTrue("and so does a numbered one", Files.exists(secondUndo));
    }

    @Test
    public void whatIsNamedLikeASnapshotButIsNotIsLeftAlone()
    {
        assertTrue(SupportSnapshotStore.isSnapshotName("aiedt-support-snapshot-20260101-000000-a1.tsv"));
        assertFalse(SupportSnapshotStore
            .isSnapshotName("aiedt-support-snapshot-20260101-000000-a1.tsv.before-restore.tsv"));
        assertFalse(SupportSnapshotStore
            .isSnapshotName("aiedt-support-snapshot-20260101-000000-a1.tsv.writing"));
        assertFalse(SupportSnapshotStore.isSnapshotName("aiedt-support-snapshot-.tsv.other.tsv"));
        assertFalse(SupportSnapshotStore.isSnapshotName("aiedt-markers.yaml"));
        assertFalse(SupportSnapshotStore.isSnapshotName(null));
    }

    @Test
    public void nothingThereIsNotAFailure()
    {
        assertTrue(SupportSnapshotStore.list(settings).isEmpty());
        assertEquals(0, SupportSnapshotStore.prune(settings, 3));
        assertTrue(SupportSnapshotStore.protectedIn(settings).isEmpty());
    }
}
