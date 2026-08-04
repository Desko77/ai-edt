/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;

/**
 * Reports the active 1C debug launches and their suspend state: application id, launch configuration
 * name/type, mode, whether the target is suspended, thread count, and where the top suspended frame sits.
 * Optionally filtered by application id.
 */
public final class DebugStateReader implements IMcpTool
{
    public static final String NAME = "debug_status"; //$NON-NLS-1$

    private static final String DESC =
        "Back-compat alias of `launch_debugger` `action=debug_status`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Lists the currently active debug launches: applicationId (either a real one or a synthetic " //$NON-NLS-1$
            + "'attach:<name>'), the launch configuration's name and type, mode (debug/run), whether the " //$NON-NLS-1$
            + "target is suspended right now, its thread count, and where the top suspended frame sits. " //$NON-NLS-1$
            + "Can be narrowed down to one applicationId."; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESC;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("applicationId", "Restrict the results to this application id") //$NON-NLS-1$ //$NON-NLS-2$
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
        String filterAppId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        DebugSessionBook.get().ensureListenerRegistered();

        try
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                return ToolResult.error("the DebugPlugin service is unavailable").toJson(); //$NON-NLS-1$
            }
            ILaunchManager mgr = debugPlugin.getLaunchManager();

            List<Map<String, Object>> launches = new ArrayList<>();
            for (ILaunch launch : mgr.getLaunches())
            {
                if (launch.isTerminated())
                {
                    continue;
                }
                String appId = DebugSessionBook.findApplicationIdFor(launch);
                if (appId == null)
                {
                    // skip non-EDT launches: Java apps, Ant tasks and the like
                    continue;
                }
                if (filterAppId != null && !filterAppId.isEmpty() && !filterAppId.equals(appId))
                {
                    continue;
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("applicationId", appId); //$NON-NLS-1$
                entry.put("mode", launch.getLaunchMode()); //$NON-NLS-1$
                entry.put("debug", ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())); //$NON-NLS-1$

                ILaunchConfiguration config = launch.getLaunchConfiguration();
                if (config != null)
                {
                    entry.put("launchConfiguration", config.getName()); //$NON-NLS-1$
                    String typeId = LaunchConfigAccess.getConfigTypeId(config);
                    entry.put("configurationType", typeId); //$NON-NLS-1$
                    entry.put("attach", LaunchConfigAccess.isAttachConfigTypeId(typeId)); //$NON-NLS-1$

                    String project = LaunchConfigAccess.readAttribute(config,
                        LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    if (project != null && !project.isEmpty())
                    {
                        entry.put("project", project); //$NON-NLS-1$
                    }
                    String alias = LaunchConfigAccess.readAttribute(config,
                        LaunchConfigAccess.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
                    if (alias != null && !alias.isEmpty())
                    {
                        entry.put("infobaseAlias", alias); //$NON-NLS-1$
                    }
                    String url = LaunchConfigAccess.readAttribute(config,
                        LaunchConfigAccess.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
                    if (url != null && !url.isEmpty())
                    {
                        entry.put("debugServerUrl", url); //$NON-NLS-1$
                    }
                }

                IDebugTarget[] targets = launch.getDebugTargets();
                int threadCount = 0;
                boolean anySuspended = false;
                String suspendedAt = null;
                for (IDebugTarget t : targets)
                {
                    if (t == null || t.isTerminated())
                    {
                        continue;
                    }
                    try
                    {
                        for (IThread th : t.getThreads())
                        {
                            threadCount++;
                            if (th.isSuspended())
                            {
                                anySuspended = true;
                                if (suspendedAt == null)
                                {
                                    IStackFrame top = th.getTopStackFrame();
                                    if (top != null)
                                    {
                                        suspendedAt = top.getName() + " @ " + top.getLineNumber(); //$NON-NLS-1$
                                    }
                                }
                            }
                        }
                    }
                    catch (Exception ignore)
                    {
                        // best-effort per-target/thread scan
                    }
                }

                entry.put("threadCount", threadCount); //$NON-NLS-1$
                entry.put("suspended", anySuspended); //$NON-NLS-1$
                if (suspendedAt != null)
                {
                    entry.put("suspendedAt", suspendedAt); //$NON-NLS-1$
                }
                entry.put("registered", DebugSessionBook.get().hasSnapshot(appId)); //$NON-NLS-1$

                launches.add(entry);
            }

            Map<String, Object> registryInfo = DebugSessionBook.get().snapshotInfo();
            return ToolResult.success().put("launches", launches) //$NON-NLS-1$
                .put("count", launches.size()) //$NON-NLS-1$
                .put("registry", registryInfo).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("debug_status tool failed", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
