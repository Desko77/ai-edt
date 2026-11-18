/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Uses only owned temporary lock files, never an actual information base. */
public class AnOwnedLeaseReportsItsActualReleaseTest
{
    private Path directory;
    private String previous;
    private static final String PROPERTY = "aiedt.locks.dir";

    @Before public void temporaryLockDirectory() throws Exception
    {
        directory = Files.createTempDirectory("aiedt-owned-lease");
        previous = System.getProperty(PROPERTY);
        System.setProperty(PROPERTY, directory.toString());
    }

    @After public void restoreDirectory() throws Exception
    {
        if (previous == null) System.clearProperty(PROPERTY);
        else System.setProperty(PROPERTY, previous);
        try (Stream<Path> entries = Files.walk(directory))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test public void exactLeaseIsHeldBeforeCloseAndReleasedAfterClose()
    {
        try (MonopolyLock.Claim claim = MonopolyLock.claim("synthetic:owned-lease", "test-owner"))
        {
            assertTrue(claim.granted());
            assertNotNull(claim.held);
            MonopolyLock lease = claim.held;
            assertTrue(lease.isStillHeld());
            claim.close();
            assertFalse("a released lease cannot report another owner's lock", lease.isStillHeld());
        }
    }

    @Test public void replacementClaimDoesNotReviveTheOldLease()
    {
        try (MonopolyLock.Claim first = MonopolyLock.claim("synthetic:replacement-lease", "first-owner"))
        {
            assertTrue(first.granted());
            MonopolyLock old = first.held;
            first.close();
            try (MonopolyLock.Claim replacement = MonopolyLock.claim("synthetic:replacement-lease", "replacement-owner"))
            {
                assertTrue(replacement.granted());
                assertTrue(replacement.held.isStillHeld());
                assertFalse("same subject is not the same owned lease", old.isStillHeld());
            }
        }
    }

    @Test public void deniedClaimHasNoLeaseAndCannotReleaseTheHolder()
    {
        try (MonopolyLock.Claim owner = MonopolyLock.claim("synthetic:denied-lease", "owner"))
        {
            assertTrue(owner.granted());
            try (MonopolyLock.Claim denied = MonopolyLock.claim("synthetic:denied-lease", "other"))
            {
                assertFalse(denied.granted());
                assertNull(denied.held);
                assertNotNull(denied.heldBy);
            }
            assertTrue(owner.held.isStillHeld());
        }
    }
}
