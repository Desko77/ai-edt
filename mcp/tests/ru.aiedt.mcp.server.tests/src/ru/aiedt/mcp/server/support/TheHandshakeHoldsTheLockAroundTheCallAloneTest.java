/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

/**
 * The per-infobase lock is taken after the release and given back before the reconnection.
 * <p>
 * Measured with a thread dump: EDT takes that same lock inside {@code connectInfobase}, after the
 * connection's monitor, while its background synchronization worker holds the monitor and waits for
 * the lock. A call that holds the lock across its own reconnection asks for the two in the opposite
 * order, and the two deadlock. The order below is therefore the fix, and this is what guards it -
 * a live check on one or two of the five paths would not.
 * </p>
 */
public class TheHandshakeHoldsTheLockAroundTheCallAloneTest
{
    /** A lock that writes down when it is taken and given back. */
    private static final class RecordingLock
        extends ReentrantLock
    {
        private static final long serialVersionUID = 1L;

        private final List<String> order;

        RecordingLock(List<String> order)
        {
            this.order = order;
        }

        @Override
        public void lock()
        {
            order.add("lock"); //$NON-NLS-1$
            super.lock();
        }

        @Override
        public void unlock()
        {
            order.add("unlock"); //$NON-NLS-1$
            super.unlock();
        }
    }

    @Test
    public void theCallRunsUnderTheLockAndTheInfobaseIsReleasedAroundIt() throws Exception
    {
        List<String> order = new ArrayList<>();
        Lock lock = new RecordingLock(order);

        BmInfobaseExtensionHelper.handshakeOrder(lock, () -> {
            order.add("release"); //$NON-NLS-1$
            return true;
        }, () -> order.add("launcher"), () -> order.add("reconnect")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("release", "lock", "launcher", "unlock", "reconnect"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void theLockIsGivenBackAndTheInfobaseTakenBackWhenTheCallThrows()
    {
        List<String> order = new ArrayList<>();
        Lock lock = new RecordingLock(order);

        try
        {
            BmInfobaseExtensionHelper.handshakeOrder(lock, () -> {
                order.add("release"); //$NON-NLS-1$
                return true;
            }, () -> {
                order.add("launcher"); //$NON-NLS-1$
                throw new IllegalStateException("the Designer failed"); //$NON-NLS-1$
            }, () -> order.add("reconnect")); //$NON-NLS-1$
            fail("the failure of the call has to reach the caller"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertEquals("the Designer failed", expected.getMessage()); //$NON-NLS-1$
        }

        assertEquals(Arrays.asList("release", "lock", "launcher", "unlock", "reconnect"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertFalse("the lock is not left held", ((ReentrantLock)lock).isLocked()); //$NON-NLS-1$
    }

    @Test
    public void anInfobaseThatWasNotConnectedIsNotReconnected() throws Exception
    {
        List<String> order = new ArrayList<>();
        Lock lock = new RecordingLock(order);

        BmInfobaseExtensionHelper.handshakeOrder(lock, () -> {
            order.add("release"); //$NON-NLS-1$
            return false;
        }, () -> order.add("launcher"), () -> order.add("reconnect")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("release", "lock", "launcher", "unlock"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void aReleaseThatThrowsRunsNeitherTheCallNorTheReconnection()
    {
        List<String> order = new ArrayList<>();
        Lock lock = new RecordingLock(order);

        try
        {
            BmInfobaseExtensionHelper.handshakeOrder(lock, () -> {
                order.add("release"); //$NON-NLS-1$
                throw new IllegalStateException("the infobase could not be released"); //$NON-NLS-1$
            }, () -> order.add("launcher"), () -> order.add("reconnect")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a release that failed has to reach the caller"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("could not be released")); //$NON-NLS-1$
        }

        // The Designer must not run against an infobase EDT still holds, and there is nothing to
        // take back.
        assertEquals(Arrays.asList("release"), order); //$NON-NLS-1$
        assertFalse("the lock was never taken", ((ReentrantLock)lock).isLocked()); //$NON-NLS-1$
    }

    @Test
    public void aRuntimeWithoutTheLockStillReleasesAndTakesTheInfobaseBack() throws Exception
    {
        List<String> order = new ArrayList<>();

        BmInfobaseExtensionHelper.handshakeOrder(null, () -> {
            order.add("release"); //$NON-NLS-1$
            return true;
        }, () -> order.add("launcher"), () -> order.add("reconnect")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("release", "launcher", "reconnect"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theInfobaseIsTakenBackEvenWhenGivingTheLockBackFails()
    {
        List<String> order = new ArrayList<>();
        Lock lock = new ReentrantLock()
        {
            private static final long serialVersionUID = 1L;

            @Override
            public void lock()
            {
                order.add("lock"); //$NON-NLS-1$
            }

            @Override
            public void unlock()
            {
                order.add("unlock"); //$NON-NLS-1$
                throw new IllegalMonitorStateException("not this thread's lock"); //$NON-NLS-1$
            }
        };

        try
        {
            BmInfobaseExtensionHelper.handshakeOrder(lock, () -> {
                order.add("release"); //$NON-NLS-1$
                return true;
            }, () -> order.add("launcher"), () -> order.add("reconnect")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("the failure has to reach the caller"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            // The infobase still has to come back: leaving it disconnected is the one outcome the
            // user sees and cannot undo from here.
        }

        assertEquals(Arrays.asList("release", "lock", "launcher", "unlock", "reconnect"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }
}
