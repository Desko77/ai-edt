/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.model.IDebugTarget;
import org.osgi.framework.Bundle;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;

/**
 * Toggles 1C line-level performance profiling on a running debug target. Reaches the EDT profiling core
 * service through its wiring bundle by reflection, because neither bundle is visible at compile time.
 */
public final class ProfilingStarter implements IMcpTool
{
    private static final String NAME = "start_profiling"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=start_profiling`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Toggles 1C's line-level performance profiler (EDT calls this " //$NON-NLS-1$
        + "замер производительности) on the active " //$NON-NLS-1$
        + "debug target: once enabled it counts calls and times every BSL line that executes. Call " //$NON-NLS-1$
        + "get_profiling_results after the run finishes to see what was covered. Needs an active debug " //$NON-NLS-1$
        + "session (debug_launch or debug_yaxunit_tests)."; //$NON-NLS-1$

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

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
            .stringProperty("applicationId", "Application id of the active debug session (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId parameter is required").toJson(); //$NON-NLS-1$
        }

        try
        {
            IDebugTarget target = DebugSessionBook.findActiveTarget(applicationId);
            if (target == null)
            {
                return ToolResult.error("There is no active debug target for applicationId: " + applicationId //$NON-NLS-1$
                    + ". Start a debug session first, via debug_launch or debug_yaxunit_tests.").toJson(); //$NON-NLS-1$
            }

            Bundle debugBundle = Platform.getBundle(DEBUG_CORE_BUNDLE);
            if (debugBundle == null)
            {
                return ToolResult.error("Debug core bundle is not available").toJson(); //$NON-NLS-1$
            }

            Bundle profilingBundle = Platform.getBundle(PROFILING_CORE_BUNDLE);
            if (profilingBundle == null)
            {
                return ToolResult.error("Profiling core bundle is not available").toJson(); //$NON-NLS-1$
            }

            Class<?> profileTargetClass = profilingBundle
                .loadClass("com._1c.g5.v8.dt.profiling.core.IProfileTarget"); //$NON-NLS-1$

            Object profileTarget;
            if (profileTargetClass.isInstance(target))
            {
                profileTarget = target;
            }
            else
            {
                profileTarget = target.getAdapter(profileTargetClass);
            }
            if (profileTarget == null)
            {
                return ToolResult.error("This debug target does not support profiling. Target class: " //$NON-NLS-1$
                    + target.getClass().getName()).toJson();
            }

            Bundle wiringBundle = Platform.getBundle(WIRING_BUNDLE);
            if (wiringBundle == null)
            {
                return ToolResult.error("Wiring bundle is not available").toJson(); //$NON-NLS-1$
            }

            Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
            Class<?> profilingServiceClass = profilingBundle
                .loadClass("com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$

            Method getService = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
            Object profilingService = getService.invoke(null, profilingServiceClass);
            if (profilingService == null)
            {
                return ToolResult.error("IProfilingService is not available").toJson(); //$NON-NLS-1$
            }

            Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
            toggleProfiling.invoke(profilingService, profileTarget);

            Activator.logInfo("Toggled profiling via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success().put("toggled", true) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("message", //$NON-NLS-1$
                    "Profiling toggled (on/off). Since this is a toggle, calling it again turns profiling " //$NON-NLS-1$
                        + "back off. Run your test, then call get_profiling_results.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("start_profiling failed", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
