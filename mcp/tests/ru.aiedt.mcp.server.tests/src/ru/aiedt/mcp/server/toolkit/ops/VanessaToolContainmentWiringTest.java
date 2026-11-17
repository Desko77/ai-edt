/*
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import ru.aiedt.mcp.server.support.FakeOwnedProcessContainment;
import ru.aiedt.mcp.server.support.OwnedProcessContainment;

/**
 * The production wiring between one owned run and its containment.
 * <p>
 * The late-child case is the reason the wiring exists: the root may be dead while a child it
 * started just before exiting still lives. The run must ask the containment, and the containment
 * must keep answering alive until the whole tree is gone.
 * </p>
 */
public class VanessaToolContainmentWiringTest
{
    @Test
    public void aLateChildKeepsTheRunOwnedUntilTheTreeIsGone()
    {
        String key = "synthetic-wiring-" + System.nanoTime(); //$NON-NLS-1$
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        AtomicReference<OwnedProcessContainment> reference = new AtomicReference<>(containment);
        AtomicInteger releases = new AtomicInteger();
        OwnedVanessaRun run = VanessaTool.ownRun(key, reference, () -> "done", //$NON-NLS-1$
            releases::incrementAndGet);
        try
        {
            run.observe();
            assertNull("the tree is alive", run.result()); //$NON-NLS-1$
            assertEquals(0, releases.get());

            // The root is gone; the child keeps the tree associated with the job.
            containment.treeAlive.set(true);
            run.observe();
            assertNull("the late child still owns the run", run.result()); //$NON-NLS-1$
            assertEquals(0, releases.get());

            containment.treeAlive.set(false);
            run.observe();
            assertEquals("done", run.result()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, releases.get());
        }
        finally
        {
            VanessaTool.refusedBeforeLaunch(key);
        }
    }

    @Test
    public void aStopThatCannotConfirmKeepsTheRunOwned()
    {
        String key = "synthetic-stop-" + System.nanoTime(); //$NON-NLS-1$
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.terminateSucceeds = false;
        AtomicReference<OwnedProcessContainment> reference = new AtomicReference<>(containment);
        AtomicInteger releases = new AtomicInteger();
        OwnedVanessaRun run = VanessaTool.ownRun(key, reference, () -> "done", //$NON-NLS-1$
            releases::incrementAndGet);
        try
        {
            assertFalse("a stop that did not see the tree gone is not a stop", run.cancel()); //$NON-NLS-1$
            assertNull(run.result());
            assertEquals(0, releases.get());
            assertEquals(1, containment.terminates.get());
            assertEquals("the handle stays open for a later stop", 0, containment.closes.get()); //$NON-NLS-1$

            containment.terminateSucceeds = true;
            assertTrue(run.cancel());
            assertNotNull(run.result());
            assertEquals(1, releases.get());
            assertEquals(1, containment.closes.get());
        }
        finally
        {
            VanessaTool.refusedBeforeLaunch(key);
        }
    }

    @Test
    public void anUnconfirmedContainmentReleaseKeepsTheRunOwnedAndRetries()
    {
        String key = "synthetic-release-" + System.nanoTime(); //$NON-NLS-1$
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.closeSucceeds = false;
        AtomicReference<OwnedProcessContainment> reference = new AtomicReference<>(containment);
        AtomicInteger cleanups = new AtomicInteger();
        OwnedVanessaRun run = VanessaTool.ownRun(key, reference, () -> "done", //$NON-NLS-1$
            () -> {
                cleanups.incrementAndGet();
                return containment.tryClose();
            });
        try
        {
            containment.treeAlive.set(false);
            run.observe();
            assertNull("a release that is not confirmed must not publish", run.result()); //$NON-NLS-1$
            assertTrue(run.cleanupPending());
            assertEquals("cleanupRequired", run.phase()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, cleanups.get());
            assertEquals("no release confirmed", 0, containment.closes.get()); //$NON-NLS-1$

            containment.closeSucceeds = true;
            assertEquals("done", run.await(3000)); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(run.cleanupPending());
            assertEquals(2, cleanups.get());
            assertEquals(1, containment.closes.get());
        }
        finally
        {
            VanessaTool.refusedBeforeLaunch(key);
        }
    }

    @Test
    public void aStopThatCannotReleaseKeepsTheRunOwned()
    {
        String key = "synthetic-stop-release-" + System.nanoTime(); //$NON-NLS-1$
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.terminateSucceeds = true;
        containment.closeSucceeds = false;
        AtomicReference<OwnedProcessContainment> reference = new AtomicReference<>(containment);
        AtomicInteger releases = new AtomicInteger();
        OwnedVanessaRun run = VanessaTool.ownRun(key, reference, () -> "done", //$NON-NLS-1$
            () -> VanessaTool.releaseContainmentFirst(containment, releases::incrementAndGet));
        try
        {
            assertFalse("a stop that cannot release is not a stop", run.cancel()); //$NON-NLS-1$
            assertNull(run.result());
            assertEquals(0, releases.get());
            assertEquals(1, containment.terminates.get());
            assertEquals("the containment stays open for a retry", 0, containment.closes.get()); //$NON-NLS-1$

            containment.closeSucceeds = true;
            assertTrue(run.cancel());
            assertNotNull(run.await(3000));
            assertEquals(1, releases.get());
            assertEquals(1, containment.closes.get());
        }
        finally
        {
            VanessaTool.refusedBeforeLaunch(key);
        }
    }

    @Test
    public void anUnconfirmedContainmentReleaseTouchesNothingElse()
    {
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.closeSucceeds = false;
        AtomicBoolean touched = new AtomicBoolean();
        assertFalse("an unconfirmed release must stop before the rest", //$NON-NLS-1$
            VanessaTool.releaseContainmentFirst(containment, () -> touched.set(true)));
        assertFalse("hold, claim, files, run map, cancel mark and permit stay untouched", //$NON-NLS-1$
            touched.get());
        containment.closeSucceeds = true;
        assertTrue(VanessaTool.releaseContainmentFirst(containment, () -> touched.set(true)));
        assertTrue(touched.get());
    }

    @Test
    public void anUnassignedRootAnswerAlwaysNamesItsRunKey()
    {
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.established = false;
        containment.pid = 777L;
        String answer = VanessaTool.unassignedRootAnswer("run-1", containment); //$NON-NLS-1$
        assertTrue(answer, answer.contains("runKey")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("run-1")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("cleanupRequired")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("777")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("notYetProven")); //$NON-NLS-1$

        containment.proof = OwnedProcessContainment.Proof.ROOT_HANDLE_EXIT;
        String exited = VanessaTool.unassignedRootAnswer("run-1", containment); //$NON-NLS-1$
        assertTrue(exited, exited.contains("unassignedSuspended")); //$NON-NLS-1$
        assertTrue(exited, exited.contains("rootHandleExit")); //$NON-NLS-1$
        assertFalse(exited, exited.contains("jobObjectActiveProcessesZero")); //$NON-NLS-1$
    }

    @Test
    public void containmentProofIsReadWithoutAskingLiveness()
    {
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.proof = OwnedProcessContainment.Proof.JOB_OBJECT_ACTIVE_PROCESSES_ZERO;
        int before = containment.treeAliveCalls.get();
        assertEquals("jobObjectActiveProcessesZero", //$NON-NLS-1$
            VanessaTool.containmentProofOf(containment));
        assertEquals("the proof reader must not probe liveness", before, //$NON-NLS-1$
            containment.treeAliveCalls.get());

        containment.proof = OwnedProcessContainment.Proof.ROOT_HANDLE_EXIT;
        assertEquals("rootHandleExit", VanessaTool.containmentProofOf(containment)); //$NON-NLS-1$
        containment.proof = OwnedProcessContainment.Proof.NOT_PROVEN;
        assertEquals("notYetProven", VanessaTool.containmentProofOf(containment)); //$NON-NLS-1$
        assertEquals("the proof reader must stay read-only", before, //$NON-NLS-1$
            containment.treeAliveCalls.get());
    }

    @Test
    public void closeWithoutObservedZeroDoesNotBecomeAZeroProof()
    {
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.proof = OwnedProcessContainment.Proof.NOT_PROVEN;
        containment.treeAlive.set(false);
        assertTrue(containment.tryClose());
        assertEquals(OwnedProcessContainment.Proof.NOT_PROVEN, containment.proof);
        assertEquals("notYetProven", VanessaTool.containmentProofOf(containment)); //$NON-NLS-1$
    }

    @Test
    public void containmentIdentityDoesNotDependOnProof()
    {
        FakeOwnedProcessContainment established = new FakeOwnedProcessContainment();
        established.proof = OwnedProcessContainment.Proof.NOT_PROVEN;
        assertEquals("windowsJobObject", VanessaTool.containmentOf(established)); //$NON-NLS-1$
        established.established = false;
        assertEquals("unassignedSuspended", VanessaTool.containmentOf(established)); //$NON-NLS-1$
        assertEquals("notStarted", VanessaTool.containmentOf(null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void closeReportsAnUnconfirmedReleaseInsteadOfIgnoringIt()
    {
        FakeOwnedProcessContainment containment = new FakeOwnedProcessContainment();
        containment.closeSucceeds = false;
        try
        {
            containment.close();
            fail("an unconfirmed release must not disappear in close()"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("retained")); //$NON-NLS-1$
        }
    }

    @Test
    public void aRunWithoutAPublishedContainmentStaysPending()
    {
        String key = "synthetic-pre-spawn-" + System.nanoTime(); //$NON-NLS-1$
        AtomicReference<OwnedProcessContainment> reference = new AtomicReference<>();
        AtomicInteger releases = new AtomicInteger();
        OwnedVanessaRun run = VanessaTool.ownRun(key, reference, () -> "done", //$NON-NLS-1$
            releases::incrementAndGet);
        try
        {
            run.observe();
            assertNull("no containment yet is not an exit", run.result()); //$NON-NLS-1$
            assertEquals(0, releases.get());
        }
        finally
        {
            VanessaTool.refusedBeforeLaunch(key);
        }
    }
}
