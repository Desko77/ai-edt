/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

/** The application-id adapter resolves display names by enumeration, never as mementos. */
public class LaunchConfigApplicationIdAccessTest
{
    @Test
    public void aManagerThatRejectsANameAsAMementoStillRestoresTheApplicationId()
        throws Exception
    {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, "application-one"); //$NON-NLS-1$
        attributes.put("anotherAttribute", "kept"); //$NON-NLS-1$ //$NON-NLS-2$
        ILaunchConfiguration configuration = configuration("Run one", attributes); //$NON-NLS-1$
        ILaunchManager manager = manager(configuration);
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(manager);

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        attributes.remove(LaunchConfigAccess.ATTR_APPLICATION_ID);
        LaunchApplicationIds.restore(access, snapshot);

        assertEquals("application-one", attributes.get(LaunchConfigAccess.ATTR_APPLICATION_ID)); //$NON-NLS-1$
        assertEquals("kept", attributes.get("anotherAttribute")); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            manager.getLaunchConfiguration("Run one"); //$NON-NLS-1$
            fail("the fake manager must reject a display name where Eclipse expects a memento"); //$NON-NLS-1$
        }
        catch (CoreException expected)
        {
            assertEquals("a display name is not a memento", expected.getMessage()); //$NON-NLS-1$
        }
    }

    private static ILaunchConfiguration configuration(String name, Map<String, Object> attributes)
    {
        ILaunchConfiguration[] saved = new ILaunchConfiguration[1];
        ILaunchConfigurationWorkingCopy copy = (ILaunchConfigurationWorkingCopy)Proxy.newProxyInstance(
            LaunchConfigApplicationIdAccessTest.class.getClassLoader(),
            new Class<?>[] { ILaunchConfigurationWorkingCopy.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "setAttribute": //$NON-NLS-1$
                    attributes.put((String)args[0], args[1]);
                    return null;
                case "doSave": //$NON-NLS-1$
                    return saved[0];
                case "getName": //$NON-NLS-1$
                    return name;
                default:
                    return defaultValue(method.getReturnType());
                }
            });
        saved[0] = (ILaunchConfiguration)Proxy.newProxyInstance(
            LaunchConfigApplicationIdAccessTest.class.getClassLoader(),
            new Class<?>[] { ILaunchConfiguration.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getName": //$NON-NLS-1$
                    return name;
                case "exists": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "getAttribute": //$NON-NLS-1$
                    return attributes.getOrDefault(args[0], args[1]);
                case "getWorkingCopy": //$NON-NLS-1$
                    return copy;
                default:
                    return defaultValue(method.getReturnType());
                }
            });
        return saved[0];
    }

    private static ILaunchManager manager(ILaunchConfiguration... configurations)
    {
        return (ILaunchManager)Proxy.newProxyInstance(
            LaunchConfigApplicationIdAccessTest.class.getClassLoader(),
            new Class<?>[] { ILaunchManager.class }, (proxy, method, args) -> {
                if ("getLaunchConfigurations".equals(method.getName())) //$NON-NLS-1$
                {
                    return configurations;
                }
                if ("getLaunchConfiguration".equals(method.getName())) //$NON-NLS-1$
                {
                    throw new CoreException(new Status(IStatus.ERROR, "test", //$NON-NLS-1$
                        "a display name is not a memento")); //$NON-NLS-1$
                }
                return defaultValue(method.getReturnType());
            });
    }

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
        return null;
    }
}
