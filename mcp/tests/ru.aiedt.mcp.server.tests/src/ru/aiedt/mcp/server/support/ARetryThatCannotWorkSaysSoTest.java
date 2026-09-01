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
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A refusal that no retry can clear must not advise a retry.
 * <p>
 * The stuck state is this: an operation took the lock and has not returned, and its claim record
 * is gone - which is what cancelling it does. The lock is then held by this very instance while
 * nothing names a holder. The old answer was "it has finished since this call tried to claim it -
 * run the operation again", and every following call said the same, without end.
 * </p>
 */
public class ARetryThatCannotWorkSaysSoTest
{
    private static final String LOCK_DIR_PROPERTY = "aiedt.locks.dir";

    private static final String SUBJECT = "file:e:/bases/stuck";

    private Path locks;

    private String previous;

    @Before
    public void useATemporaryLockDirectory() throws Exception
    {
        locks = Files.createTempDirectory("aiedt-locks");
        previous = System.getProperty(LOCK_DIR_PROPERTY);
        System.setProperty(LOCK_DIR_PROPERTY, locks.toString());
    }

    @After
    public void putItBack() throws Exception
    {
        if (previous == null)
        {
            System.clearProperty(LOCK_DIR_PROPERTY);
        }
        else
        {
            System.setProperty(LOCK_DIR_PROPERTY, previous);
        }
        try (Stream<Path> entries = Files.walk(locks))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private void dropTheClaimRecordsKeepingTheLockFiles() throws Exception
    {
        try (Stream<Path> entries = Files.list(locks))
        {
            for (Path each : entries.toList())
            {
                if (each.getFileName().toString().endsWith(".lock.json"))
                {
                    Files.delete(each);
                }
            }
        }
    }

    @Test
    public void aLockThisInstanceWillNotGiveBackIsNamedAsSuch() throws Exception
    {
        try (MonopolyLock stuck = MonopolyLock.take(SUBJECT, "update_database").orElseThrow())
        {
            dropTheClaimRecordsKeepingTheLockFiles();

            MonopolyLock.Claim again = MonopolyLock.claim(SUBJECT, "update_database");

            assertFalse("the lock is still held, so this must not be granted", again.granted());
            assertNotNull(again.heldBy);
            assertTrue("the caller must not be told to repeat a call that cannot succeed",
                MonopolyLock.isHeldByThisInstance(again.heldBy));
            assertFalse("and the old advice must be gone",
                again.heldBy.contains("run the operation again"));
        }
    }

    /**
     * A hold by this instance is reported as such even while its claim record is still there.
     * <p>
     * The claim record survives for as long as the lock does, so reading it first answered
     * "another AI-EDT instance is working on this infobase" about ourselves. This test used to
     * assert exactly that, which is how the wrong behaviour looked correct.
     * </p>
     */
    @Test
    public void aHoldByThisInstanceIsNamedAsOursNotAsANeighbour() throws Exception
    {
        try (MonopolyLock held = MonopolyLock.take(SUBJECT, "update_database").orElseThrow())
        {
            MonopolyLock.Claim again = MonopolyLock.claim(SUBJECT, "export_extension");

            assertFalse(again.granted());
            assertNotNull(again.heldBy);
            assertTrue("the refusal has to say the holder is us",
                MonopolyLock.isHeldByThisInstance(again.heldBy));
            assertTrue("and it still names what is holding it",
                again.heldBy.contains("update_database"));
            assertFalse("ordinary contention must not be called unclearable",
                again.heldBy.contains("cannot clear"));
        }
    }

    @Test
    public void whatThisInstanceHoldsSurvivesCancellationAndNamesTheInfobase() throws Exception
    {
        assertNull("nothing is held before anything is taken",
            MonopolyLock.outstandingHere(SUBJECT));

        try (MonopolyLock stuck = MonopolyLock.take(SUBJECT, "update_database").orElseThrow())
        {
            MonopolyLock.Outstanding held = MonopolyLock.outstandingHere(SUBJECT);
            assertNotNull("the infobase has to say it is busy", held);
            assertEquals("update_database", held.operation);
            assertTrue("and for how long", held.heldMs() >= 0);

            // What cancelling a run does: the tracking entry goes, the claim record goes, and the
            // platform call carries on. The record must NOT go with them.
            dropTheClaimRecordsKeepingTheLockFiles();
            assertNotNull("cancellation must not clear it",
                MonopolyLock.outstandingHere(SUBJECT));

            assertNull("and it says nothing about another infobase",
                MonopolyLock.outstandingHere("file:e:/bases/elsewhere"));
        }

        assertNull("the confirmed stop is the call returning, and only then is it clear",
            MonopolyLock.outstandingHere(SUBJECT));
    }

    @Test
    public void theRefusalNamesWhatIsHoldingTheInfobase() throws Exception
    {
        try (MonopolyLock stuck = MonopolyLock.take(SUBJECT, "update_database").orElseThrow())
        {
            dropTheClaimRecordsKeepingTheLockFiles();

            MonopolyLock.Claim again = MonopolyLock.claim(SUBJECT, "update_database");

            assertTrue(MonopolyLock.isHeldByThisInstance(again.heldBy));
            assertTrue("the caller is told what has it",
                again.heldBy.contains("update_database"));
        }
    }

    /**
     * The refusal sentence is formed once, by the claim, not by each caller.
     * <p>
     * Seven call sites used to prefix "Another AI-EDT instance is working on this infobase"
     * unconditionally, which is wrong whenever the holder is this instance.
     * </p>
     */
    @Test
    public void theClaimFormsTheWholeRefusal() throws Exception
    {
        try (MonopolyLock held = MonopolyLock.take(SUBJECT, "update_database").orElseThrow())
        {
            MonopolyLock.Claim ours = MonopolyLock.claim(SUBJECT, "export_extension");

            assertNotNull(ours.refusal());
            assertFalse("our own hold must not be called a neighbour",
                ours.refusal().contains("Another AI-EDT instance"));
            assertEquals("the refusal is the sentence itself", ours.heldBy, ours.refusal());
        }

        MonopolyLock.Claim free = MonopolyLock.claim(SUBJECT, "update_database");
        assertTrue(free.granted());
        assertNull("a granted claim refuses nothing", free.refusal());
        free.close();
    }

    @Test
    public void aFreeInfobaseIsGranted()
    {
        MonopolyLock.Claim first = MonopolyLock.claim(SUBJECT, "update_database");
        assertTrue("nothing holds it", first.granted());
        first.close();

        MonopolyLock.Claim second = MonopolyLock.claim(SUBJECT, "update_database");
        assertTrue("and it is free again once released", second.granted());
        second.close();
    }
}
