/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
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

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;

/**
 * Terminates a running 1C launch (runtime client / debug session / YAXUnit run).
 *
 * <p>Closes the debug-lifecycle hole: we can start, step and resume a launch but
 * had no way to stop one. Targets an EDT launch by {@code applicationId} (real
 * {@code ATTR_APPLICATION_ID} or synthetic {@code attach:<configName>}); with
 * {@code all=true} terminates every active EDT launch; with no argument it
 * terminates the single active EDT launch when there is exactly one (otherwise it
 * lists the candidates and asks for an explicit applicationId).
 *
 * <p>Only EDT launches are considered (those whose configuration resolves to an
 * applicationId) - foreign launches (Java apps, Ant, ...) are never touched.
 * {@link ILaunch#terminate()} requests termination of all of a launch's debug
 * targets and processes; termination is asynchronous, so the response reports the
 * request plus the immediate {@code terminated} flag.
 */
public class LaunchTerminator implements IMcpTool
{
    public static final String NAME = "terminate_launch"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=terminate`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Terminate a running 1C launch (runtime client / debug session / YAXUnit run). " //$NON-NLS-1$
            + "Pass applicationId to stop one launch, all=true to stop every active EDT launch, " //$NON-NLS-1$
            + "or no argument to stop the single active launch when there is exactly one. " //$NON-NLS-1$
            + "Only EDT launches are affected. Termination is asynchronous."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id of the launch to terminate (real or synthetic 'attach:<name>'). " //$NON-NLS-1$
                    + "Omit to terminate the lone active launch.") //$NON-NLS-1$
            .booleanProperty("all", //$NON-NLS-1$
                "Terminate every active EDT launch (default false).") //$NON-NLS-1$
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
        boolean all = JsonUtils.extractBooleanArgument(params, "all", false); //$NON-NLS-1$

        DebugSessionBook.get().ensureListenerRegistered();

        try
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                return ToolResult.error("Error: the debug plugin is not published as a service").toJson(); //$NON-NLS-1$
            }
            ILaunchManager mgr = debugPlugin.getLaunchManager();

            // Collect active EDT launches (appId != null, not terminated).
            List<ILaunch> active = new ArrayList<>();
            List<String> activeIds = new ArrayList<>();
            for (ILaunch launch : mgr.getLaunches())
            {
                if (launch.isTerminated())
                {
                    continue;
                }
                String appId = DebugSessionBook.findApplicationIdFor(launch);
                if (appId == null)
                {
                    continue; // not an EDT launch - never touch
                }
                active.add(launch);
                activeIds.add(appId);
            }

            if (active.isEmpty())
            {
                return ToolResult.error("No active EDT launch to terminate.").toJson(); //$NON-NLS-1$
            }

            // Decide which launches to terminate.
            List<ILaunch> selected = new ArrayList<>();
            if (all)
            {
                selected.addAll(active);
            }
            else if (filterAppId != null && !filterAppId.isEmpty())
            {
                for (ILaunch launch : active)
                {
                    if (filterAppId.equals(DebugSessionBook.findApplicationIdFor(launch)))
                    {
                        selected.add(launch);
                    }
                }
                if (selected.isEmpty())
                {
                    return ToolResult.error("No active EDT launch with applicationId '" //$NON-NLS-1$
                        + filterAppId + "'. Active: " + String.join(", ", activeIds)).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else if (active.size() == 1)
            {
                selected.add(active.get(0));
            }
            else
            {
                return ToolResult.error("Multiple active launches - pass applicationId or all=true. " //$NON-NLS-1$
                    + "Active: " + String.join(", ", activeIds)).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            List<Map<String, Object>> results = new ArrayList<>();
            int terminatedCount = 0;
            for (ILaunch launch : selected)
            {
                Map<String, Object> entry = new LinkedHashMap<>();
                String appId = DebugSessionBook.findApplicationIdFor(launch);
                entry.put("applicationId", appId); //$NON-NLS-1$
                ILaunchConfiguration config = launch.getLaunchConfiguration();
                if (config != null)
                {
                    entry.put("launchConfiguration", config.getName()); //$NON-NLS-1$
                }
                entry.put("mode", launch.getLaunchMode()); //$NON-NLS-1$
                if (launch.isTerminated())
                {
                    entry.put("alreadyTerminated", true); //$NON-NLS-1$
                    results.add(entry);
                    continue;
                }
                if (!launch.canTerminate())
                {
                    entry.put("requested", false); //$NON-NLS-1$
                    entry.put("error", "launch cannot be terminated (canTerminate=false)"); //$NON-NLS-1$ //$NON-NLS-2$
                    results.add(entry);
                    continue;
                }
                try
                {
                    launch.terminate();
                    entry.put("requested", true); //$NON-NLS-1$
                    // Termination is asynchronous; report the immediate state.
                    entry.put("terminated", launch.isTerminated()); //$NON-NLS-1$
                    // Drop any cached suspend snapshot now; the TERMINATE event
                    // listener also purges it, but clearing here avoids a stale
                    // read between request and event delivery.
                    if (appId != null)
                    {
                        DebugSessionBook.get().clearSnapshot(appId);
                    }
                    terminatedCount++;
                }
                catch (Exception termEx)
                {
                    entry.put("requested", false); //$NON-NLS-1$
                    entry.put("error", termEx.getMessage()); //$NON-NLS-1$
                }
                results.add(entry);
            }

            return ToolResult.success()
                .put("results", results) //$NON-NLS-1$
                .put("requestedCount", terminatedCount) //$NON-NLS-1$
                .put("note", "Termination is asynchronous - use debug_status to confirm the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "launch is gone.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error: the launch could not be terminated", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
