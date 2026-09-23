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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;

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

    /**
     * The dump-info-only Designer call holds the per-infobase lock for the call alone, and a failure
     * thrown inside the reflected method reaches the caller as that failure rather than wrapped in
     * {@link java.lang.reflect.InvocationTargetException}.
     */
    @Test
    public void theDesignerCallRunsUnderTheInfobaseLockAndUnwrapsTheCause() throws Exception
    {
        List<String> order = new ArrayList<>();
        RecordingLock lock = new RecordingLock(order);
        Object target = new Object()
        {
            @SuppressWarnings("unused")
            public void blow()
            {
                order.add(lock.isHeldByCurrentThread() ? "held" : "not-held"); //$NON-NLS-1$ //$NON-NLS-2$
                throw new IllegalStateException("bad credentials"); //$NON-NLS-1$
            }
        };
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod("blow"); //$NON-NLS-1$
        try
        {
            BmInfobaseExtensionHelper.invokeUnderInfobaseLock(lock, method, target);
            fail("the cause has to reach the caller"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertEquals("bad credentials", expected.getMessage()); //$NON-NLS-1$
        }
        assertEquals(Arrays.asList("lock", "held", "unlock"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the lock is not left held", lock.isLocked()); //$NON-NLS-1$
    }

    /**
     * The full hierarchical dump - the rebuild's fallback - holds the same per-infobase lock
     * around the launcher call as the dump-info-only run: without it this EDT's own thick-client
     * callers run their Designer side by side with the dump.
     */
    @Test
    public void theFullDumpHoldsTheInfobaseLockAroundTheCall() throws Exception
    {
        List<String> order = new ArrayList<>();
        RecordingLock lock = new RecordingLock(order);
        BmInfobaseExtensionHelper.LauncherContext ctx = new BmInfobaseExtensionHelper.LauncherContext();
        ctx.lock = lock;
        ctx.launcher = recordingLauncher(order, lock, null);

        BmInfobaseExtensionHelper.runFullDumpUnderInfobaseLock(ctx,
            java.nio.file.Paths.get("dump")); //$NON-NLS-1$

        assertEquals(Arrays.asList("lock", "held", "unlock"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the lock is not left held", lock.isLocked()); //$NON-NLS-1$
    }

    /**
     * A full dump that throws gives the lock back with the failure: the rebuild's failure path
     * must not leave the infobase lock held against this EDT's own callers.
     */
    @Test
    public void theFullDumpGivesTheLockBackWhenTheCallThrows() throws Exception
    {
        List<String> order = new ArrayList<>();
        RecordingLock lock = new RecordingLock(order);
        BmInfobaseExtensionHelper.LauncherContext ctx = new BmInfobaseExtensionHelper.LauncherContext();
        ctx.lock = lock;
        ctx.launcher = recordingLauncher(order, lock,
            new IllegalStateException("the Designer exited with code 1")); //$NON-NLS-1$

        try
        {
            BmInfobaseExtensionHelper.runFullDumpUnderInfobaseLock(ctx,
                java.nio.file.Paths.get("dump")); //$NON-NLS-1$
            fail("the launcher's failure has to reach the caller"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertEquals("the Designer exited with code 1", expected.getMessage()); //$NON-NLS-1$
        }

        assertEquals(Arrays.asList("lock", "held", "unlock"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the lock is not left held", lock.isLocked()); //$NON-NLS-1$
    }

    /**
     * A launcher whose {@code exportFullXmlFromInfobase} records whether the lock was held while
     * it ran, and answers or throws as told.
     */
    private static IThickClientLauncher recordingLauncher(List<String> order, RecordingLock lock,
        RuntimeException failure)
    {
        return (IThickClientLauncher)Proxy.newProxyInstance(
            IThickClientLauncher.class.getClassLoader(),
            new Class<?>[] { IThickClientLauncher.class }, (proxy, method, args) -> {
                if (!method.getName().equals("exportFullXmlFromInfobase")) //$NON-NLS-1$
                {
                    return null;
                }
                order.add(lock.isHeldByCurrentThread() ? "held" : "not-held"); //$NON-NLS-1$ //$NON-NLS-2$
                if (failure != null)
                {
                    throw failure;
                }
                return null;
            });
    }
}
