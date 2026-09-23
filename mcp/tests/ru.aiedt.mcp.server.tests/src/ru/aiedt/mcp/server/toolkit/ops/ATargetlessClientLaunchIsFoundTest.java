/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.LaunchApplicationIds;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;

/**
 * {@code terminate} finds a client that {@code start_client} launched - a run-mode launch with
 * no debug target - before and after a save of the infobase list.
 *
 * <p>The terminator addresses a launch by the application id of its configuration, which is the
 * only address a target-less client has. A save of the infobase list makes EDT strip that
 * attribute from every launch configuration, and a launch without it answers {@code null} to the
 * lookup and is skipped - the client keeps running and nothing the tool says can stop it. These
 * cases run the selection against fake launches: the client is found while the attribute
 * stands, lost when the strip is simulated, and found again once the guard has put the
 * attribute back.</p>
 */
public class ATargetlessClientLaunchIsFoundTest
{
    /** A fake launch configuration over a plain attribute map. */
    private static ILaunchConfiguration config(String name, String typeId, Map<String, Object> attributes)
    {
        ILaunchConfigurationType type = (ILaunchConfigurationType)Proxy.newProxyInstance(
            ATargetlessClientLaunchIsFoundTest.class.getClassLoader(),
            new Class<?>[] { ILaunchConfigurationType.class },
            (proxy, method, args) -> "getIdentifier".equals(method.getName()) ? typeId //$NON-NLS-1$
                : defaultValue(method.getReturnType()));
        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
            {
                switch (method.getName())
                {
                case "getName": //$NON-NLS-1$
                    return name;
                case "getType": //$NON-NLS-1$
                    return type;
                case "exists": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "getAttribute": //$NON-NLS-1$
                    Object value = attributes.get(args[0]);
                    return value != null ? value : args[1];
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString": //$NON-NLS-1$
                    return name;
                default:
                    return defaultValue(method.getReturnType());
                }
            }
        };
        return (ILaunchConfiguration)Proxy.newProxyInstance(
            ATargetlessClientLaunchIsFoundTest.class.getClassLoader(),
            new Class<?>[] { ILaunchConfiguration.class }, handler);
    }

    /** A fake launch: a configuration, a terminated flag and no debug targets. */
    private static ILaunch launch(ILaunchConfiguration config, boolean terminated)
    {
        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
            {
                switch (method.getName())
                {
                case "getLaunchConfiguration": //$NON-NLS-1$
                    return config;
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.valueOf(terminated);
                case "getDebugTargets": //$NON-NLS-1$
                    return new IDebugTarget[0];
                case "getLaunchMode": //$NON-NLS-1$
                    return "run"; //$NON-NLS-1$
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString": //$NON-NLS-1$
                    return "launch of " + config.getName(); //$NON-NLS-1$
                default:
                    return defaultValue(method.getReturnType());
                }
            }
        };
        return (ILaunch)Proxy.newProxyInstance(ATargetlessClientLaunchIsFoundTest.class.getClassLoader(),
            new Class<?>[] { ILaunch.class }, handler);
    }

    /** A fake launch manager handing out the launches it was given. */
    private static ILaunchManager manager(ILaunch... launches)
    {
        return (ILaunchManager)Proxy.newProxyInstance(
            ATargetlessClientLaunchIsFoundTest.class.getClassLoader(),
            new Class<?>[] { ILaunchManager.class },
            (proxy, method, args) -> "getLaunches".equals(method.getName()) ? launches //$NON-NLS-1$
                : defaultValue(method.getReturnType()));
    }

    /** What an unanswered proxy method returns: nothing, of the right shape. */
    private static Object defaultValue(Class<?> type)
    {
        if (type.isArray())
        {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (!type.isPrimitive())
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        if (type == double.class)
        {
            return Double.valueOf(0);
        }
        if (type == float.class)
        {
            return Float.valueOf(0);
        }
        return null;
    }

    @Test
    public void aClientLaunchWithoutATargetAnswersToItsApplicationId()
    {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, "app-1"); //$NON-NLS-1$
        ILaunch client = launch(config("MyApp", LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID, attributes), //$NON-NLS-1$
            false);

        assertEquals("the launch has no debug target - start_client runs one", //$NON-NLS-1$
            0, client.getDebugTargets().length);
        assertEquals("app-1", DebugSessionBook.findApplicationIdFor(client)); //$NON-NLS-1$
    }

    @Test
    public void theTerminatorSelectsTheTargetlessClientLaunch()
    {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, "app-1"); //$NON-NLS-1$
        ILaunch client = launch(config("MyApp", LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID, attributes), //$NON-NLS-1$
            false);

        List<String> ids = new ArrayList<>();
        List<ILaunch> active = LaunchTerminator.activeEdtLaunches(manager(client), ids);

        assertEquals(1, active.size());
        assertSame(client, active.get(0));
        assertEquals(List.of("app-1"), ids); //$NON-NLS-1$
    }

    @Test
    public void aListWriteHidesTheLaunchAndTheGuardBringsItBack()
    {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, "app-1"); //$NON-NLS-1$
        String name = "MyApp"; //$NON-NLS-1$
        String memento = "m-myapp"; //$NON-NLS-1$
        ILaunch client = launch(config(name, LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID, attributes), false);
        ILaunchManager mgr = manager(client);

        LaunchApplicationIds.Access access = new LaunchApplicationIds.Access()
        {
            @Override
            public List<LaunchApplicationIds.Configuration> configurations()
            {
                return List.of(new LaunchApplicationIds.Configuration(memento, name));
            }

            @Override
            public String readApplicationId(String addressed)
            {
                return memento.equals(addressed)
                    ? (String)attributes.get(LaunchConfigAccess.ATTR_APPLICATION_ID) : null;
            }

            @Override
            public void writeApplicationId(String addressed, String applicationId)
            {
                if (memento.equals(addressed))
                {
                    attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, applicationId);
                }
            }
        };
        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);

        // The save of the infobase list strips the attribute from every launch configuration.
        attributes.remove(LaunchConfigAccess.ATTR_APPLICATION_ID);
        assertNull(DebugSessionBook.findApplicationIdFor(client));
        assertTrue("without the attribute the terminator cannot see the client", //$NON-NLS-1$
            LaunchTerminator.activeEdtLaunches(mgr, new ArrayList<>()).isEmpty());

        LaunchApplicationIds.restore(access, snapshot);

        assertEquals("app-1", DebugSessionBook.findApplicationIdFor(client)); //$NON-NLS-1$
        List<ILaunch> active = LaunchTerminator.activeEdtLaunches(mgr, new ArrayList<>());
        assertEquals(1, active.size());
        assertSame(client, active.get(0));
    }

    @Test
    public void aTerminatedLaunchIsNotSelected()
    {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, "app-1"); //$NON-NLS-1$
        ILaunch client = launch(config("MyApp", LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID, attributes), //$NON-NLS-1$
            true);

        assertTrue(LaunchTerminator.activeEdtLaunches(manager(client), new ArrayList<>()).isEmpty());
    }

    @Test
    public void aForeignLaunchIsNeverSelected()
    {
        ILaunch java = launch(config("SomeJavaApp", "org.eclipse.jdt.launching.localJavaApplication", //$NON-NLS-1$ //$NON-NLS-2$
            new HashMap<>()), false);

        assertTrue(LaunchTerminator.activeEdtLaunches(manager(java), new ArrayList<>()).isEmpty());
    }
}
