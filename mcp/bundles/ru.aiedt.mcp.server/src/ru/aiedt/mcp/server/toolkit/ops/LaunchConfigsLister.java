/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;

/**
 * Lists the workspace's 1C launch configurations and says which of them are running.
 * <p>
 * The name of each entry is the handle every debug and run tool takes. Alongside it goes enough to
 * tell the configurations apart - the type, whether it attaches to a running server or starts a
 * client, the application it targets - and, for the ones with a live session behind them, the mode
 * they run in and whether that session is currently paused.
 * </p>
 */
public class LaunchConfigsLister
    implements IMcpTool
{
    private static final String FILTER_ALL = "all"; //$NON-NLS-1$

    private static final String FILTER_ATTACH = "attach"; //$NON-NLS-1$

    private static final String FILTER_CLIENT = "client"; //$NON-NLS-1$

    private static final String FILTER_RUNTIME = "runtime"; //$NON-NLS-1$

    private static final String FILTER_RUNTIME_CLIENT = "runtimeclient"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return "list_configurations"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=list_configurations`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List EDT launch configurations (runtime client + Attach + other 1C types) with their " //$NON-NLS-1$
            + "current running state. Each entry carries the configuration name (use it as " //$NON-NLS-1$
            + "launchConfigurationName in debug_launch / run_yaxunit_tests / debug_yaxunit_tests / " //$NON-NLS-1$
            + "update_database), type id, attach flag, applicationId (real or synthetic " //$NON-NLS-1$
            + "'attach:<name>'), project, infobase alias, debug server URL, and a 'running' flag (plus " //$NON-NLS-1$
            + "'suspended' when the session is paused on a breakpoint). Use type='attach' for " //$NON-NLS-1$
            + "server-side debug setups (HTTP services, background jobs), type='client' for " //$NON-NLS-1$
            + "1C:Enterprise client configs, or type='all' (default)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("type", //$NON-NLS-1$
                "Filter: 'attach' (RemoteRuntime + LocalRuntime), 'client' (RuntimeClient), or 'all' " //$NON-NLS-1$
                    + "(default - any 1C/EDT launch config)") //$NON-NLS-1$
            .stringProperty("projectName", "Optional project name filter") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        try
        {
            String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
            String projectFilter = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$

            ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Error: the launch manager is not published as a service").toJson(); //$NON-NLS-1$
            }

            Map<String, ILaunch> liveByAppId = indexLiveLaunches(launchManager);

            List<Object> configurations = new ArrayList<>();
            for (ILaunchConfiguration config : LaunchConfigAccess.getAllEdtConfigs(launchManager))
            {
                Map<String, Object> entry = describe(config, type, projectFilter, liveByAppId);
                if (entry != null)
                {
                    configurations.add(entry);
                }
            }

            return ToolResult.success()
                .put("configurations", configurations) //$NON-NLS-1$
                .put("count", configurations.size()) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Could not list launch configurations", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Describes one configuration, or refuses it when a filter excludes it.
     *
     * @param config the configuration
     * @param typeFilter the type filter argument
     * @param projectFilter the project filter argument
     * @param liveByAppId the running launches indexed by application id
     * @return the entry, or <code>null</code> when a filter rejects the configuration
     */
    private static Map<String, Object> describe(ILaunchConfiguration config, String typeFilter,
        String projectFilter, Map<String, ILaunch> liveByAppId)
    {
        String typeId = LaunchConfigAccess.getConfigTypeId(config);
        boolean attach = LaunchConfigAccess.isAttachConfigTypeId(typeId);
        boolean client = LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID.equals(typeId);

        if (!matchesTypeFilter(typeFilter, attach, client))
        {
            return null;
        }

        String project =
            LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        if (projectFilter != null && !projectFilter.isEmpty() && !projectFilter.equals(project))
        {
            return null;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", config.getName()); //$NON-NLS-1$
        entry.put("type", typeId); //$NON-NLS-1$
        entry.put("attach", attach); //$NON-NLS-1$

        String applicationId = LaunchConfigAccess.getApplicationIdFor(config);
        if (applicationId != null)
        {
            entry.put("applicationId", applicationId); //$NON-NLS-1$
        }
        if (!project.isEmpty())
        {
            entry.put("project", project); //$NON-NLS-1$
        }
        String infobaseAlias =
            LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
        if (!infobaseAlias.isEmpty())
        {
            entry.put("infobaseAlias", infobaseAlias); //$NON-NLS-1$
        }
        String debugServerUrl =
            LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
        if (!debugServerUrl.isEmpty())
        {
            entry.put("debugServerUrl", debugServerUrl); //$NON-NLS-1$
        }

        ILaunch live = applicationId != null ? liveByAppId.get(applicationId) : null;
        boolean running = live != null;
        entry.put("running", running); //$NON-NLS-1$
        if (running)
        {
            entry.put("mode", live.getLaunchMode()); //$NON-NLS-1$
            entry.put("suspended", isSuspended(live)); //$NON-NLS-1$
        }
        return entry;
    }

    /**
     * Indexes the non-terminated launches by their application id, the first launch to claim an id
     * winning it.
     *
     * @param launchManager the launch manager
     * @return the running launches keyed by application id
     */
    private static Map<String, ILaunch> indexLiveLaunches(ILaunchManager launchManager)
    {
        Map<String, ILaunch> liveByAppId = new HashMap<>();
        for (ILaunch launch : launchManager.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }
            String applicationId = LaunchConfigAccess.getApplicationIdFor(launch);
            if (applicationId != null)
            {
                liveByAppId.putIfAbsent(applicationId, launch);
            }
        }
        return liveByAppId;
    }

    /**
     * Applies the type filter, permissively: an unrecognized filter value keeps everything rather than
     * dropping it.
     *
     * @param typeFilter the filter argument; may be <code>null</code>
     * @param attach whether the configuration attaches to a running server
     * @param client whether the configuration is a runtime client
     * @return whether the configuration passes the filter
     */
    private static boolean matchesTypeFilter(String typeFilter, boolean attach, boolean client)
    {
        if (typeFilter == null || typeFilter.isEmpty())
        {
            return true;
        }
        switch (typeFilter.toLowerCase(Locale.ROOT))
        {
        case FILTER_ALL:
            return true;
        case FILTER_ATTACH:
            return attach;
        case FILTER_CLIENT:
        case FILTER_RUNTIME:
        case FILTER_RUNTIME_CLIENT:
            return client;
        default:
            return true;
        }
    }

    /**
     * Tells whether any thread of a launch is currently paused.
     *
     * @param launch the launch
     * @return <code>true</code> when at least one thread is suspended
     */
    private static boolean isSuspended(ILaunch launch)
    {
        for (IDebugTarget target : launch.getDebugTargets())
        {
            try
            {
                for (IThread thread : target.getThreads())
                {
                    if (thread.isSuspended())
                    {
                        return true;
                    }
                }
            }
            catch (DebugException e)
            {
                // This target would not report its threads; the others may still be suspended.
            }
        }
        return false;
    }
}
