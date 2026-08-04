/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Knows which Eclipse launch configurations belong to 1C:EDT, and how to name, find and create one.
 * <p>
 * EDT launches come in two shapes. A <em>runtime client</em> configuration starts a fresh 1C client and
 * attaches the debugger to it; it names both a project and an application. An <em>attach</em>
 * configuration - remote or local - hooks onto a 1C debug server that is already running; it names a
 * project but usually no application, identifying its target by infobase alias or debug server URL
 * instead.
 * </p>
 * <p>
 * That difference is why {@link #getApplicationIdFor(ILaunchConfiguration)} exists. Every debug tool
 * here addresses a session by one string, the application id, and an attach configuration has none to
 * give - so one is made from its name. Everything else in the class is lookup: by name, by project and
 * application, or by type.
 * </p>
 */
public final class LaunchConfigAccess
{
    /** Launch type that starts a 1C client and debugs it. Supports both run and debug modes. */
    public static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    /** Launch type that attaches to a 1C debug server on another host. */
    public static final String TYPE_REMOTE_RUNTIME = "com._1c.g5.v8.dt.debug.core.RemoteRuntime"; //$NON-NLS-1$

    /** Launch type that attaches to a 1C debug server on this host. */
    public static final String TYPE_LOCAL_RUNTIME = "com._1c.g5.v8.dt.debug.core.LocalRuntime"; //$NON-NLS-1$

    /**
     * Every EDT debug launch type, runtime client first.
     * <p>
     * The order is the search order for a configuration named on its own, so a name that exists in more
     * than one type resolves to the runtime client - the one an agent almost always means.
     * </p>
     */
    public static final List<String> ALL_DEBUG_CONFIG_TYPE_IDS =
        List.of(LAUNCH_CONFIG_TYPE_ID, TYPE_REMOTE_RUNTIME, TYPE_LOCAL_RUNTIME);

    /** Launch attribute: the EDT project the launch belongs to. */
    public static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    /** Launch attribute: the EDT application (infobase binding) the launch starts. */
    public static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$

    /**
     * Launch attribute: the {@code /C} startup string handed to the 1C client.
     * <p>
     * How the test runners pass a run's parameters: they set this on a working copy of the
     * configuration, and the client reads it back inside the infobase.
     * </p>
     */
    public static final String ATTR_STARTUP_OPTION = "com._1c.g5.v8.dt.launching.core.ATTR_STARTUP_OPTION"; //$NON-NLS-1$

    /** Launch attribute: the infobase an attach configuration debugs, by alias. */
    public static final String ATTR_DEBUG_INFOBASE_ALIAS = "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_INFOBASE_ALIAS"; //$NON-NLS-1$

    /** Launch attribute: the debug server an attach configuration connects to. */
    public static final String ATTR_DEBUG_SERVER_URL = "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_SERVER_URL"; //$NON-NLS-1$

    /** Launch attribute: the 1C client type (ThickClient / ThinClient / WebViewClient). */
    public static final String ATTR_CLIENT_TYPE = "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_TYPE"; //$NON-NLS-1$

    /** Value for {@link #ATTR_CLIENT_TYPE}: the managed-application thin client. */
    public static final String CLIENT_TYPE_THIN =
        "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient"; //$NON-NLS-1$

    /** Launch attribute: whether to auto-select the client type (false = use the explicit one). */
    public static final String ATTR_CLIENT_AUTO_SELECT =
        "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT"; //$NON-NLS-1$

    /** Launch attribute: log in using the infobase access credentials stored in the project settings. */
    public static final String ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS"; //$NON-NLS-1$

    /** Launch attribute: auto-select the 1C platform runtime for the launch. */
    public static final String ATTR_RUNTIME_INSTALLATION_USE_AUTO =
        "com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO"; //$NON-NLS-1$

    /** Launch attribute: the local debug-server mode (AUTO / ON / OFF). */
    public static final String ATTR_USE_LOCAL_DEBUG_SERVER =
        "com._1c.g5.v8.dt.debug.core.ATTR_USE_LOCAL_DEBUG_SERVER"; //$NON-NLS-1$

    /**
     * Marks an application id that was invented for an attach configuration rather than read from one.
     * <p>
     * Callers test for this prefix to tell a real application apart from an attached session: there is
     * no infobase of ours behind it to update or deploy to.
     * </p>
     */
    public static final String ATTACH_APP_ID_PREFIX = "attach:"; //$NON-NLS-1$

    private LaunchConfigAccess()
    {
        // utility
    }

    /**
     * @param typeId a launch configuration type id
     * @return whether it is one of the two attach types
     */
    public static boolean isAttachConfigTypeId(String typeId)
    {
        return TYPE_REMOTE_RUNTIME.equals(typeId) || TYPE_LOCAL_RUNTIME.equals(typeId);
    }

    /**
     * @param config a launch configuration; may be <code>null</code>
     * @return whether it attaches to a running 1C debug server rather than starting a client
     */
    public static boolean isAttachConfig(ILaunchConfiguration config)
    {
        return isAttachConfigTypeId(getConfigTypeId(config));
    }

    /**
     * The application id a launch configuration answers to.
     * <p>
     * A runtime client configuration carries its own. An attach configuration does not, so one is made
     * from its name: stable for as long as the configuration keeps that name, which is what lets an
     * agent call {@code wait_for_break} on the same session it stepped a moment ago.
     * </p>
     * <p>
     * <code>null</code> is the answer for everything that is not an EDT debug launch, and it is load
     * bearing: it is how the debug tools ignore the Java, Ant and JUnit launches sitting in the same
     * workspace. A runtime client whose application attribute is empty is therefore invisible too - it
     * names no application, so there is nothing to address it by.
     * </p>
     *
     * @param config the configuration; may be <code>null</code>
     * @return the application id, the synthetic {@code attach:} id, or <code>null</code>
     */
    public static String getApplicationIdFor(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return null;
        }

        String applicationId = readAttribute(config, ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
        if (!applicationId.isEmpty())
        {
            return applicationId;
        }

        if (isAttachConfig(config))
        {
            return ATTACH_APP_ID_PREFIX + config.getName();
        }
        return null;
    }

    /**
     * @param launch a launch; may be <code>null</code>
     * @return the application id of the configuration behind it, or <code>null</code>
     * @see #getApplicationIdFor(ILaunchConfiguration)
     */
    public static String getApplicationIdFor(ILaunch launch)
    {
        return launch == null ? null : getApplicationIdFor(launch.getLaunchConfiguration());
    }

    /**
     * Finds the configuration of a type that names exactly this project and this application.
     * <p>
     * Both must match. There is deliberately no falling back to some other configuration of the same
     * project: a near miss here means running against an infobase the caller did not ask for, and
     * silently doing so is worse than not running at all.
     * </p>
     *
     * @param launchManager the launch manager
     * @param configType the type to search
     * @param projectName the project to match; must not be <code>null</code>
     * @param applicationId the application to match; must not be <code>null</code>
     * @return the first configuration matching both, or <code>null</code>
     */
    public static ILaunchConfiguration findLaunchConfig(ILaunchManager launchManager,
        ILaunchConfigurationType configType, String projectName, String applicationId)
    {
        try
        {
            for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(configType))
            {
                try
                {
                    if (projectName.equals(config.getAttribute(ATTR_PROJECT_NAME, "")) //$NON-NLS-1$
                        && applicationId.equals(config.getAttribute(ATTR_APPLICATION_ID, ""))) //$NON-NLS-1$
                    {
                        return config;
                    }
                }
                catch (CoreException e)
                {
                    Activator.logError("Failed to read launch configuration " + config.getName(), e); //$NON-NLS-1$
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to list launch configurations of type " + configType.getIdentifier(), e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Finds the configuration a caller meant, by name if it gave one and by project and application
     * otherwise.
     * <p>
     * A name wins outright, even when it belongs to a configuration for another project: the caller
     * named it, and answering with something else it did not name would be worse than answering with
     * what it asked for and letting it check.
     * </p>
     *
     * @param launchManager the launch manager; <code>null</code> yields <code>null</code>
     * @param launchConfigurationName the configuration to look up by name; may be <code>null</code>
     * @param projectName the project, used only when no name was given
     * @param applicationId the application, used only when no name was given
     * @return the configuration, or <code>null</code> when nothing matches or too little was given to
     *         look one up
     */
    public static ILaunchConfiguration resolveLaunchConfig(ILaunchManager launchManager,
        String launchConfigurationName, String projectName, String applicationId)
    {
        if (launchManager == null)
        {
            return null;
        }

        if (launchConfigurationName != null && !launchConfigurationName.isEmpty())
        {
            return findLaunchConfigByName(launchManager, launchConfigurationName);
        }

        if (projectName == null || projectName.isEmpty() || applicationId == null || applicationId.isEmpty())
        {
            return null;
        }

        ILaunchConfigurationType configType = launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE_ID);
        if (configType == null)
        {
            return null;
        }
        return findLaunchConfig(launchManager, configType, projectName, applicationId);
    }

    /**
     * Finds an EDT debug configuration by its exact name, searching the debug types in order.
     *
     * @param launchManager the launch manager; <code>null</code> yields <code>null</code>
     * @param name the configuration name; <code>null</code> or empty yields <code>null</code>
     * @return the first configuration with that name, or <code>null</code>
     */
    public static ILaunchConfiguration findLaunchConfigByName(ILaunchManager launchManager, String name)
    {
        if (launchManager == null || name == null || name.isEmpty())
        {
            return null;
        }

        for (String typeId : ALL_DEBUG_CONFIG_TYPE_IDS)
        {
            ILaunchConfigurationType configType = launchManager.getLaunchConfigurationType(typeId);
            if (configType == null)
            {
                continue;
            }

            try
            {
                for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(configType))
                {
                    if (name.equals(config.getName()))
                    {
                        return config;
                    }
                }
            }
            catch (CoreException e)
            {
                Activator.logError("Failed to list launch configurations of type " + typeId, e); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * @param launchManager the launch manager
     * @param configType the type to list
     * @return every configuration of that type; empty, never <code>null</code>
     */
    public static ILaunchConfiguration[] getAllRuntimeClientConfigs(ILaunchManager launchManager,
        ILaunchConfigurationType configType)
    {
        try
        {
            return launchManager.getLaunchConfigurations(configType);
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to list runtime client launch configurations", e); //$NON-NLS-1$
            return new ILaunchConfiguration[0];
        }
    }

    /**
     * Creates a runtime client configuration for a project and an application.
     * <p>
     * Exactly two attributes are written, and that is the whole point of the method. It would be easy
     * to copy an existing configuration as a template and easy to regret: a runtime client stores the
     * user name and password it signs in with, its data separation settings, its automated testing port
     * and its external object bindings. Cloning those into a launch aimed at a <em>different</em>
     * infobase leaks one infobase's credentials into another and misconfigures the run besides. EDT
     * fills the rest in from its own defaults, which is what an agent asking for a launch actually
     * wants.
     * </p>
     *
     * @param launchManager the launch manager, used to make the name unique
     * @param configType the runtime client type
     * @param projectName the project to launch
     * @param applicationId the application to launch
     * @param applicationName the name to base the configuration name on; falls back to the project name
     *            when <code>null</code> or empty
     * @return the saved configuration
     * @throws CoreException if the configuration cannot be created or saved
     */
    public static ILaunchConfiguration createRuntimeClientConfig(ILaunchManager launchManager,
        ILaunchConfigurationType configType, String projectName, String applicationId, String applicationName)
        throws CoreException
    {
        String baseName = applicationName == null || applicationName.isEmpty() ? projectName : applicationName;
        String name = launchManager.generateLaunchConfigurationName(baseName);

        ILaunchConfigurationWorkingCopy workingCopy = configType.newInstance(null, name);
        workingCopy.setAttribute(ATTR_PROJECT_NAME, projectName);
        workingCopy.setAttribute(ATTR_APPLICATION_ID, applicationId);
        workingCopy.setAttribute(ATTR_CLIENT_TYPE, CLIENT_TYPE_THIN);
        workingCopy.setAttribute(ATTR_CLIENT_AUTO_SELECT, false);
        workingCopy.setAttribute(ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, true);
        workingCopy.setAttribute(ATTR_RUNTIME_INSTALLATION_USE_AUTO, true);
        workingCopy.setAttribute(ATTR_USE_LOCAL_DEBUG_SERVER, "AUTO"); //$NON-NLS-1$
        workingCopy.setAttribute("process_factory_id", //$NON-NLS-1$
            "com._1c.g5.v8.dt.debug.core.RuntimeProcessFactory"); //$NON-NLS-1$
        workingCopy.setAttribute("org.eclipse.debug.core.MAPPED_RESOURCE_PATHS", //$NON-NLS-1$
            java.util.List.of("/" + projectName)); //$NON-NLS-1$
        workingCopy.setAttribute("org.eclipse.debug.core.MAPPED_RESOURCE_TYPES", //$NON-NLS-1$
            java.util.List.of("4")); //$NON-NLS-1$
        return workingCopy.doSave();
    }

    /**
     * @param launchManager the launch manager; <code>null</code> yields an empty list
     * @return every EDT debug configuration in the workspace, runtime clients first; never
     *         <code>null</code>
     */
    public static List<ILaunchConfiguration> getAllDebugConfigs(ILaunchManager launchManager)
    {
        List<ILaunchConfiguration> configs = new ArrayList<>();
        if (launchManager == null)
        {
            return configs;
        }

        for (String typeId : ALL_DEBUG_CONFIG_TYPE_IDS)
        {
            ILaunchConfigurationType configType = launchManager.getLaunchConfigurationType(typeId);
            if (configType == null)
            {
                continue;
            }

            try
            {
                for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(configType))
                {
                    configs.add(config);
                }
            }
            catch (CoreException e)
            {
                Activator.logError("Failed to list launch configurations of type " + typeId, e); //$NON-NLS-1$
            }
        }
        return configs;
    }

    /**
     * Every 1C launch configuration in the workspace, debug or not.
     * <p>
     * Wider than {@link #getAllDebugConfigs(ILaunchManager)} on purpose: this is what a listing tool
     * shows a user, and a mobile or client launch is still theirs to see.
     * </p>
     *
     * @param launchManager the launch manager; <code>null</code> yields an empty list
     * @return the 1C configurations; never <code>null</code>
     */
    public static List<ILaunchConfiguration> getAllEdtConfigs(ILaunchManager launchManager)
    {
        List<ILaunchConfiguration> configs = new ArrayList<>();
        if (launchManager == null)
        {
            return configs;
        }

        try
        {
            for (ILaunchConfiguration config : launchManager.getLaunchConfigurations())
            {
                if (isEdtConfig(config))
                {
                    configs.add(config);
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to list launch configurations", e); //$NON-NLS-1$
        }
        return configs;
    }

    /**
     * @param config a launch configuration; may be <code>null</code>
     * @return whether its type belongs to 1C, by vendor prefix rather than by an exact type list
     */
    public static boolean isEdtConfig(ILaunchConfiguration config)
    {
        String typeId = getConfigTypeId(config);
        return typeId.startsWith("com._1c.") || typeId.startsWith("com.e1c."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param config a launch configuration; may be <code>null</code>
     * @return its type id, or an empty string when there is none to be had. Never <code>null</code> -
     *         callers compare and prefix-test the result without checking it first
     */
    public static String getConfigTypeId(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return ""; //$NON-NLS-1$
        }

        try
        {
            ILaunchConfigurationType configType = config.getType();
            return configType == null ? "" : configType.getIdentifier(); //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Reads a launch attribute, treating an unreadable configuration as an absent value.
     *
     * @param config the configuration
     * @param attribute the attribute name
     * @param defaultValue what to answer when the attribute is not set or cannot be read
     * @return the attribute value, or the default
     */
    public static String readAttribute(ILaunchConfiguration config, String attribute, String defaultValue)
    {
        try
        {
            return config.getAttribute(attribute, defaultValue);
        }
        catch (CoreException e)
        {
            return defaultValue;
        }
    }

    /**
     * @return Eclipse's launch manager, or <code>null</code> when the debug plugin is not running
     */
    public static ILaunchManager getLaunchManager()
    {
        DebugPlugin plugin = DebugPlugin.getDefault();
        return plugin == null ? null : plugin.getLaunchManager();
    }
}
