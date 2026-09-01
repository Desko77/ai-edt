/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    @Test
    public void aClaimThatNamesItsHolderStillNamesThem() throws Exception
    {
        try (MonopolyLock held = MonopolyLock.take(SUBJECT, "update_database").orElseThrow())
        {
            MonopolyLock.Claim again = MonopolyLock.claim(SUBJECT, "export_extension");

            assertFalse(again.granted());
            assertNotNull(again.heldBy);
            assertFalse("a named holder is not the no-retry state",
                MonopolyLock.isHeldByThisInstance(again.heldBy));
        }
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
