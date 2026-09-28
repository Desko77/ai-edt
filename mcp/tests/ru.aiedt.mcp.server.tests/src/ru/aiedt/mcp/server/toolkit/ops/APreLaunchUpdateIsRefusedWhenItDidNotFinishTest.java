/**
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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.support.ApplicationUpdater;

/**
 * A debug launch that updates the infobase first starts the client only over an infobase that is
 * up to date: an update another process is running, a load that leaves any other state, an
 * application that is not registered and one the manager fails to resolve all refuse the launch.
 */
public class APreLaunchUpdateIsRefusedWhenItDidNotFinishTest
{
    /**
     * A stub application.
     *
     * @return the application
     */
    private static IApplication application()
    {
        return (IApplication)Proxy.newProxyInstance(
            APreLaunchUpdateIsRefusedWhenItDidNotFinishTest.class.getClassLoader(),
            new Class<?>[] { IApplication.class },
            (proxy, method, args) -> "getId".equals(method.getName()) //$NON-NLS-1$
                || "getName".equals(method.getName()) ? "app-1" : null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An application manager that reports one state before the update and hands back another from
     * the update itself, recording the calls it receives.
     *
     * @param before the state reported before the update.
     * @param after the state the update returns.
     * @param calls receives the names of the called methods.
     * @return the manager
     */
    private static IApplicationManager manager(ApplicationUpdateState before,
        ApplicationUpdateState after, List<String> calls)
    {
        return (IApplicationManager)Proxy.newProxyInstance(
            APreLaunchUpdateIsRefusedWhenItDidNotFinishTest.class.getClassLoader(),
            new Class<?>[] { IApplicationManager.class },
            (proxy, method, args) -> {
                calls.add(method.getName());
                if ("getUpdateState".equals(method.getName())) //$NON-NLS-1$
                {
                    return before;
                }
                if ("update".equals(method.getName())) //$NON-NLS-1$
                {
                    return after;
                }
                return null;
            });
    }

    /**
     * Only an update that ends in UPDATED lets the launch go on; every other state it ends in is a
     * refusal naming that state.
     */
    @Test
    public void onlyAnUpdateThatEndsUpdatedLetsTheLaunchGoOn()
    {
        for (ApplicationUpdateState after : ApplicationUpdateState.values())
        {
            List<String> calls = new ArrayList<>();
            ApplicationUpdater.Result update = DebugSessionStarter.updateDatabase(
                manager(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED, after, calls),
                application(), null);
            String refusal = DebugSessionStarter.preLaunchRefusal(update);
            assertTrue(after + ": the update was asked for", calls.contains("update")); //$NON-NLS-1$
            if (after == ApplicationUpdateState.UPDATED)
            {
                assertNull(after + ": " + refusal, refusal); //$NON-NLS-1$
            }
            else
            {
                assertNotNull(after + " must refuse the launch", refusal); //$NON-NLS-1$
                assertTrue(refusal, refusal.contains(after.name()));
                assertTrue(refusal, refusal.contains("Nothing was launched")); //$NON-NLS-1$
            }
        }
    }

    /**
     * Before the update: an infobase that is up to date goes on without loading, one another
     * process is updating refuses without loading, and every other state loads.
     */
    @Test
    public void theStateBeforeTheUpdateDecidesWhetherItLoads()
    {
        for (ApplicationUpdateState before : ApplicationUpdateState.values())
        {
            List<String> calls = new ArrayList<>();
            ApplicationUpdater.Result update = DebugSessionStarter.updateDatabase(
                manager(before, ApplicationUpdateState.UPDATED, calls), application(), null);
            String refusal = DebugSessionStarter.preLaunchRefusal(update);
            if (before == ApplicationUpdateState.BEING_UPDATED)
            {
                assertEquals(ApplicationUpdater.Outcome.BEING_UPDATED_BY_ANOTHER, update.outcome);
                assertNotNull("an update already running refuses the launch", refusal); //$NON-NLS-1$
                assertFalse(calls.contains("update")); //$NON-NLS-1$
            }
            else if (before == ApplicationUpdateState.UPDATED)
            {
                assertNull(refusal);
                assertFalse(calls.contains("update")); //$NON-NLS-1$
            }
            else
            {
                assertNull(before + ": " + refusal, refusal); //$NON-NLS-1$
                assertTrue(before + ": the update was asked for", calls.contains("update")); //$NON-NLS-1$
            }
        }
    }

    /**
     * A launch by configuration name whose application is not registered, or cannot be resolved,
     * is refused instead of starting against an infobase nobody updated.
     */
    @Test
    public void anUnresolvedApplicationRefusesTheLaunch()
    {
        IApplicationManager none = (IApplicationManager)Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] { IApplicationManager.class },
            (proxy, method, args) -> "getApplication".equals(method.getName()) //$NON-NLS-1$
                ? Optional.empty() : null);
        ApplicationUpdater.Result missing =
            DebugSessionStarter.updateResolvedApplication(none, null, "app-missing"); //$NON-NLS-1$
        assertEquals(ApplicationUpdater.Outcome.APPLICATION_NOT_FOUND, missing.outcome);
        String refusal = DebugSessionStarter.preLaunchRefusal(missing);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("app-missing")); //$NON-NLS-1$

        IApplicationManager failing = (IApplicationManager)Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] { IApplicationManager.class },
            (proxy, method, args) -> {
                if ("getApplication".equals(method.getName())) //$NON-NLS-1$
                {
                    throw new ApplicationException("registry unavailable"); //$NON-NLS-1$
                }
                return null;
            });
        ApplicationUpdater.Result failed =
            DebugSessionStarter.updateResolvedApplication(failing, null, "app-1"); //$NON-NLS-1$
        assertEquals(ApplicationUpdater.Outcome.FAILED, failed.outcome);
        String failure = DebugSessionStarter.preLaunchRefusal(failed);
        assertNotNull(failure);
        assertTrue(failure, failure.contains("registry unavailable")); //$NON-NLS-1$
    }

    /**
     * A launch that attempted no update goes on.
     */
    @Test
    public void noUpdateIsNotARefusal()
    {
        assertNull(DebugSessionStarter.preLaunchRefusal(null));
    }
}
