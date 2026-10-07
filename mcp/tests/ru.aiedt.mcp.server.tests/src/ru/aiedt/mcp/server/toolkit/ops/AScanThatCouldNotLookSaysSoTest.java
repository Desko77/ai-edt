/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.DebugSessionBook;

/**
 * A scan that could not look says so.
 *
 * <p>{@code wait_for_break} looks over the session's threads for one that is already stopped before it
 * starts waiting, so a break that has happened is answered at once. The look is best-effort - the wait
 * goes on whatever it finds - and it used to swallow its own failure, which left "there was nothing
 * stopped here" and "the threads could not be read at all" as the same answer. A caller that then
 * waited out the timeout had no way to tell which one it was in. The failure now reaches the log.</p>
 */
public class AScanThatCouldNotLookSaysSoTest
{
    private static final String APP = "d31-scan-that-could-not-look"; //$NON-NLS-1$

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSession()
    {
        registry.clearSnapshot(APP);
    }

    @Test
    public void aScanWhoseThreadsCouldNotBeReadIsLogged()
    {
        List<String> logged = new ArrayList<>();
        ILogListener listener = (status, plugin) ->
        {
            synchronized (logged)
            {
                logged.add(String.valueOf(status.getMessage()));
            }
        };
        Activator.getDefault().getLog().addLogListener(listener);
        try
        {
            SuspendWaiter.scanForAlreadySuspended(registry, APP, unreadableTarget());
        }
        finally
        {
            Activator.getDefault().getLog().removeLogListener(listener);
        }

        synchronized (logged)
        {
            assertFalse("the scan logged nothing at all, so this proves nothing", logged.isEmpty()); //$NON-NLS-1$
            boolean told = false;
            for (String line : logged)
            {
                if (line.contains("could not scan") && line.contains(APP)) //$NON-NLS-1$
                {
                    told = true;
                }
            }
            assertTrue("the caller waited out the timeout with no way to know why: " + logged, //$NON-NLS-1$
                told);
        }
    }

    @Test
    public void aFailedLookLeavesTheSessionAsUnstoppedAsItWas()
    {
        SuspendWaiter.scanForAlreadySuspended(registry, APP, unreadableTarget());

        assertFalse("a look that failed is not a stop, and the wait goes on", //$NON-NLS-1$
            registry.hasSnapshot(APP));
    }

    /**
     * @return the debug target of a session whose threads cannot be listed
     */
    private static IDebugTarget unreadableTarget()
    {
        return (IDebugTarget)Proxy.newProxyInstance(
            AScanThatCouldNotLookSaysSoTest.class.getClassLoader(),
            new Class<?>[] { IDebugTarget.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.FALSE;
                case "getThreads": //$NON-NLS-1$
                    throw new IllegalStateException("the debug target is gone"); //$NON-NLS-1$
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString": //$NON-NLS-1$
                    return APP;
                default:
                    return null;
                }
            });
    }
}
