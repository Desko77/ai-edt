/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

/**
 * Eclipse's launch configurations as the guard meets them.
 *
 * <p>A configuration is identified by its memento and carries a display name; the two are
 * different strings, and several configurations of different types may share one name. A working
 * copy changes nothing in the stored configuration until {@code doSave} commits it - an adapter
 * that edits the copy and never saves changes nothing at all. Handing a display name where a
 * memento belongs fails the way Eclipse fails it.</p>
 *
 * <p>{@link #stripApplicationIds()} is what the infobase-list reload does to the stored
 * configurations. It is called between the snapshot and the restore on the same thread, which is
 * where the strip happens in EDT: measured on 2026.2, the whole chain from
 * {@code IInfobaseManager.update} to the stripped working copy's {@code doSave} runs before that
 * call returns.</p>
 */
final class FakeLaunchConfigurations
{
    /** The attribute the guard minds. */
    static final String APP_ID = LaunchConfigAccess.ATTR_APPLICATION_ID;

    /** One configuration as Eclipse holds it. */
    static final class Configuration
    {
        final String memento;
        final String name;
        final String typeId;

        /** What the stored configuration holds. */
        final Map<String, Object> stored = new LinkedHashMap<>();

        /** How many times {@code doSave} committed a working copy. */
        int saves;

        /** When set, the configuration cannot be read at all, the way a broken file behaves. */
        boolean unreadable;

        Configuration(String memento, String name, String typeId)
        {
            this.memento = memento;
            this.name = name;
            this.typeId = typeId;
        }

        String applicationId()
        {
            return (String)stored.get(APP_ID);
        }

        ILaunchConfiguration asLaunchConfiguration()
        {
            return (ILaunchConfiguration)Proxy.newProxyInstance(
                FakeLaunchConfigurations.class.getClassLoader(),
                new Class<?>[] { ILaunchConfiguration.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getName": //$NON-NLS-1$
                        return name;
                    case "getMemento": //$NON-NLS-1$
                        if (unreadable)
                        {
                            throw new CoreException(status("the .launch file could not be read")); //$NON-NLS-1$
                        }
                        return memento;
                    case "getType": //$NON-NLS-1$
                        return launchType(typeId);
                    case "exists": //$NON-NLS-1$
                        return Boolean.TRUE;
                    case "getAttribute": //$NON-NLS-1$
                        if (unreadable)
                        {
                            throw new CoreException(status("the .launch file could not be read")); //$NON-NLS-1$
                        }
                        if (args != null && args.length == 2)
                        {
                            Object value = stored.get(args[0]);
                            return value != null ? value : args[1];
                        }
                        return null;
                    case "getWorkingCopy": //$NON-NLS-1$
                        return workingCopy();
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }

        ILaunchConfigurationWorkingCopy workingCopy()
        {
            Map<String, Object> edits = new LinkedHashMap<>();
            return (ILaunchConfigurationWorkingCopy)Proxy.newProxyInstance(
                FakeLaunchConfigurations.class.getClassLoader(),
                new Class<?>[] { ILaunchConfigurationWorkingCopy.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "setAttribute": //$NON-NLS-1$
                        edits.put((String)args[0], args[1]);
                        return null;
                    case "doSave": //$NON-NLS-1$
                        // The one moment the stored configuration changes: what the copy holds is
                        // committed here, and only here.
                        stored.putAll(edits);
                        edits.clear();
                        saves++;
                        return asLaunchConfiguration();
                    case "getName": //$NON-NLS-1$
                        return name;
                    case "getMemento": //$NON-NLS-1$
                        return memento;
                    case "exists": //$NON-NLS-1$
                        return Boolean.TRUE;
                    case "getAttribute": //$NON-NLS-1$
                        if (args != null && args.length == 2)
                        {
                            Object value = edits.containsKey(args[0]) ? edits.get(args[0])
                                : stored.get(args[0]);
                            return value != null ? value : args[1];
                        }
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }
    }

    private final List<Configuration> configurations = new ArrayList<>();

    /** When set, listing the configurations fails with this message. */
    String listFailure;

    /**
     * A runtime-client configuration of {@code projectName}, bound to {@code applicationId} when
     * one is given.
     */
    Configuration add(String memento, String name, String projectName, String applicationId)
    {
        Configuration configuration = add(memento, name, LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID);
        configuration.stored.put(LaunchConfigAccess.ATTR_PROJECT_NAME, projectName);
        if (applicationId != null)
        {
            configuration.stored.put(APP_ID, applicationId);
        }
        return configuration;
    }

    /** A configuration of an arbitrary launch type. */
    Configuration add(String memento, String name, String typeId)
    {
        Configuration configuration = new Configuration(memento, name, typeId);
        configurations.add(configuration);
        return configuration;
    }

    Configuration byName(String name)
    {
        for (Configuration configuration : configurations)
        {
            if (configuration.name.equals(name))
            {
                return configuration;
            }
        }
        throw new IllegalArgumentException("no configuration named " + name); //$NON-NLS-1$
    }

    /** What the infobase-list reload does: the application id leaves every stored configuration. */
    void stripApplicationIds()
    {
        for (Configuration configuration : configurations)
        {
            configuration.stored.remove(APP_ID);
        }
    }

    ILaunchManager manager()
    {
        ILaunchConfiguration[] all = new ILaunchConfiguration[configurations.size()];
        for (int i = 0; i < configurations.size(); i++)
        {
            all[i] = configurations.get(i).asLaunchConfiguration();
        }
        return (ILaunchManager)Proxy.newProxyInstance(
            FakeLaunchConfigurations.class.getClassLoader(),
            new Class<?>[] { ILaunchManager.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getLaunchConfigurations": //$NON-NLS-1$
                    if (listFailure != null)
                    {
                        throw new CoreException(status(listFailure));
                    }
                    return all;
                case "getLaunchConfiguration": //$NON-NLS-1$
                    return byMemento((String)args[0]);
                default:
                    return defaultValue(method.getReturnType());
                }
            });
    }

    /** Eclipse's own rule: a memento resolves a configuration, a display name does not. */
    private ILaunchConfiguration byMemento(String memento) throws CoreException
    {
        for (Configuration configuration : configurations)
        {
            if (configuration.memento.equals(memento))
            {
                return configuration.asLaunchConfiguration();
            }
        }
        for (Configuration configuration : configurations)
        {
            if (configuration.name.equals(memento))
            {
                throw new CoreException(status("a display name is not a memento")); //$NON-NLS-1$
            }
        }
        return null;
    }

    private static ILaunchConfigurationType launchType(String typeId)
    {
        return (ILaunchConfigurationType)Proxy.newProxyInstance(
            FakeLaunchConfigurations.class.getClassLoader(),
            new Class<?>[] { ILaunchConfigurationType.class },
            (proxy, method, args) -> "getId".equals(method.getName()) //$NON-NLS-1$
                || "getIdentifier".equals(method.getName()) //$NON-NLS-1$
                    ? typeId : defaultValue(method.getReturnType()));
    }

    private static IStatus status(String message)
    {
        return new Status(IStatus.ERROR, "ru.aiedt.mcp.server.tests", message); //$NON-NLS-1$
    }

    /** The value a proxy returns for a method the fake does not care about. */
    static Object defaultValue(Class<?> type)
    {
        if (type.isArray())
        {
            return Array.newInstance(type.getComponentType(), 0);
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
        if (type == short.class)
        {
            return Short.valueOf((short)0);
        }
        if (type == byte.class)
        {
            return Byte.valueOf((byte)0);
        }
        if (type == double.class)
        {
            return Double.valueOf(0);
        }
        if (type == float.class)
        {
            return Float.valueOf(0);
        }
        if (type == char.class)
        {
            return Character.valueOf('\0');
        }
        return null;
    }
}
