/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

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
 * Covers the one-shot claim the thick-client helpers take.
 * <p>
 * Its whole job is to make three outcomes distinguishable: I have it, somebody else has it and here
 * is who, and there is nothing here to claim. Collapsing the third into the second refuses an
 * operation for a neighbour that does not exist, which is a worse failure than the contention it
 * was meant to report.
 * </p>
 */
public class MonopolyClaimTest
{
    private static final String LOCK_DIR_PROPERTY = "aiedt.locks.dir"; //$NON-NLS-1$

    private Path locks;

    private String previous;

    @Before
    public void useATemporaryLockDirectory() throws Exception
    {
        locks = Files.createTempDirectory("aiedt-claim"); //$NON-NLS-1$
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

    /** A free subject is granted, and the claim carries no holder to report. */
    @Test
    public void afreeSubjectIsGranted()
    {
        try (MonopolyLock.Claim claim = MonopolyLock.claim("file:e:/bases/one", "update_database")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(claim.granted());
            assertNotNull(claim.held);
            assertNull("a granted claim has nobody to point at", claim.heldBy); //$NON-NLS-1$
        }
    }

    /**
     * A taken subject is refused WITH the holder, in one call.
     * <p>
     * One call because asking twice - take, then who has it - leaves a window in which the holder
     * finishes, and the answer then reads "another instance is working on this. null".
     * </p>
     */
    @Test
    public void ataken_subjectNamesItsHolder()
    {
        try (MonopolyLock.Claim first = MonopolyLock.claim("file:e:/bases/two", "export_extension")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(first.granted());

            MonopolyLock.Claim second = MonopolyLock.claim("file:e:/bases/two", "install_extension"); //$NON-NLS-1$ //$NON-NLS-2$

            assertFalse(second.granted());
            assertNotNull("a refusal without a holder is the bug this replaced", second.heldBy); //$NON-NLS-1$
            assertTrue("the holder should be named: " + second.heldBy, //$NON-NLS-1$
                second.heldBy.contains(InstanceRegistry.selfTitle()));
            second.close();
        }
    }

    /**
     * An infobase whose identity cannot be formed is not a contended one.
     * <p>
     * A connection string this cannot read yields no key. Refusing there would stop an operation on
     * behalf of a neighbour that does not exist - and the operation ran perfectly well before
     * claims were introduced, so it must go on running.
     * </p>
     */
    @Test
    public void anUnidentifiableSubjectIsGrantedRatherThanRefused()
    {
        for (String noKey : new String[] {null, ""}) //$NON-NLS-1$
        {
            MonopolyLock.Claim claim = MonopolyLock.claim(noKey, "export_configuration_to_cf"); //$NON-NLS-1$

            assertTrue("an unidentifiable infobase was reported as taken", claim.granted()); //$NON-NLS-1$
            assertNull(claim.heldBy);
            claim.close();
        }
    }

    /** Closing a granted claim frees the subject for the next caller. */
    @Test
    public void closingFreesIt()
    {
        MonopolyLock.claim("file:e:/bases/three", "update_database").close(); //$NON-NLS-1$ //$NON-NLS-2$

        MonopolyLock.Claim second = MonopolyLock.claim("file:e:/bases/three", "update_database"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(second.granted());
        second.close();
    }

    /** Closing a refused claim is harmless - it holds nothing to release. */
    @Test
    public void closingArefusedClaimReleasesNothing()
    {
        try (MonopolyLock.Claim held = MonopolyLock.claim("file:e:/bases/four", "update_database")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            MonopolyLock.Claim refused = MonopolyLock.claim("file:e:/bases/four", "export_object"); //$NON-NLS-1$ //$NON-NLS-2$
            refused.close();

            assertNotNull("closing a refusal must not release the real holder's claim", //$NON-NLS-1$
                MonopolyLock.heldBy("file:e:/bases/four")); //$NON-NLS-1$
        }
    }
}
