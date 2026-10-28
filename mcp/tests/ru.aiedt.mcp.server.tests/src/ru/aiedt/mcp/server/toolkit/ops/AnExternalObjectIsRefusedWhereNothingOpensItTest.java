/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;

import ru.aiedt.mcp.server.support.LaunchConfigAccess;

/**
 * An external object named to a launch that cannot open it is refused before anything starts.
 *
 * <p>An Attach configuration starts no client, and a launch whose dump service is not reachable
 * cannot build the object the client opens. Both used to answer success with nothing open. A
 * runtime-client configuration with a reachable service keeps its former answers.
 */
public class AnExternalObjectIsRefusedWhereNothingOpensItTest
{
    private static final String PROJECT = "ExternalReports"; //$NON-NLS-1$

    /**
     * An Attach configuration with an external object name is refused, and the refusal names the
     * configuration that would open it.
     */
    @Test
    public void anAttachConfigurationRefusesTheObjectName()
    {
        String refusal = DebugSessionStarter.attachExternalObjectRefusal(true, "Report1", null); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Attach")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("runtime-client configuration")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was launched")); //$NON-NLS-1$
    }

    /**
     * The project argument alone is refused the same way: it is as unusable without a client.
     */
    @Test
    public void anAttachConfigurationRefusesTheObjectProject()
    {
        assertNotNull(DebugSessionStarter.attachExternalObjectRefusal(true, null, PROJECT));
        assertNotNull(DebugSessionStarter.attachExternalObjectRefusal(true, "", PROJECT)); //$NON-NLS-1$
    }

    /**
     * A runtime-client configuration with an external object, and an Attach configuration
     * without one, pass as before.
     */
    @Test
    public void aClientConfigurationAndABareAttachPass()
    {
        assertNull(DebugSessionStarter.attachExternalObjectRefusal(false, "Report1", PROJECT)); //$NON-NLS-1$
        assertNull(DebugSessionStarter.attachExternalObjectRefusal(true, null, null));
        assertNull(DebugSessionStarter.attachExternalObjectRefusal(true, "", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The refusal follows the type of the configuration named by launchConfigurationName, the
     * only route that can reach an Attach configuration: a runtime-client configuration named the
     * same way passes with an external object, an Attach one of either kind is refused.
     */
    @Test
    public void theConfigurationTypeDecidesTheRefusal()
    {
        assertNull(DebugSessionStarter.attachExternalObjectRefusal(
            LaunchConfigAccess.isAttachConfigTypeId(LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID), "Report1", PROJECT)); //$NON-NLS-1$
        assertNotNull(DebugSessionStarter.attachExternalObjectRefusal(
            LaunchConfigAccess.isAttachConfigTypeId(LaunchConfigAccess.TYPE_REMOTE_RUNTIME), "Report1", null)); //$NON-NLS-1$
        assertNotNull(DebugSessionStarter.attachExternalObjectRefusal(
            LaunchConfigAccess.isAttachConfigTypeId(LaunchConfigAccess.TYPE_LOCAL_RUNTIME), "Report1", null)); //$NON-NLS-1$
    }

    /**
     * An unreachable dump service is a refusal naming the project, not a skipped check.
     */
    @Test
    public void anUnreachableDumpServiceRefusesTheLaunch()
    {
        String refusal = DebugSessionStarter.dumpReadiness(project(), false, null);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains(PROJECT));
        assertTrue(refusal, refusal.contains("not reachable")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was launched")); //$NON-NLS-1$
    }

    /**
     * A reachable service with generation on and a valid dump lets the launch go ahead.
     */
    @Test
    public void aReadyDumpServiceLetsTheLaunchGo()
    {
        assertNull(DebugSessionStarter.dumpReadiness(project(), false, dumps(true, new AtomicBoolean())));
    }

    /**
     * Generation switched off is refused without enableExternalObjectDump and switched on with it.
     */
    @Test
    public void generationSwitchedOffKeepsItsFormerAnswers()
    {
        AtomicBoolean switchedOn = new AtomicBoolean();
        String refusal = DebugSessionStarter.dumpReadiness(project(), false, dumps(false, switchedOn));
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("enableExternalObjectDump=true")); //$NON-NLS-1$
        assertTrue("refusing must not change the project", !switchedOn.get()); //$NON-NLS-1$

        assertNull(DebugSessionStarter.dumpReadiness(project(), true, dumps(false, switchedOn)));
        assertTrue(switchedOn.get());
    }

    private static IProject project()
    {
        return (IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(), new Class<?>[] { IProject.class },
            (proxy, method, args) -> "getName".equals(method.getName()) ? PROJECT : null); //$NON-NLS-1$
    }

    private static IExternalObjectDumpSupport dumps(boolean enabled, AtomicBoolean switchedOn)
    {
        return (IExternalObjectDumpSupport)Proxy.newProxyInstance(IExternalObjectDumpSupport.class.getClassLoader(),
            new Class<?>[] { IExternalObjectDumpSupport.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "isEnabled": //$NON-NLS-1$
                        return Boolean.valueOf(enabled);
                    case "setEnabled": //$NON-NLS-1$
                        switchedOn.set(true);
                        return null;
                    case "validateDumpGeneration": //$NON-NLS-1$
                        return Status.OK_STATUS;
                    default:
                        return null;
                }
            });
    }
}
