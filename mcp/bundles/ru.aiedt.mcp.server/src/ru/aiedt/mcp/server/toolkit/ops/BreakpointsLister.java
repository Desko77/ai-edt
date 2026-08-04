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
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Lists the breakpoints the debug platform is holding, one entry per registered breakpoint.
 * <p>
 * Filters down to a project on request, and reads each breakpoint defensively: a breakpoint whose
 * line number or enabled flag cannot be read still appears, with the reason in its entry, rather than
 * dropping it or failing the whole call.
 * </p>
 */
public class BreakpointsLister
    implements IMcpTool
{
    private static final String NAME = "list_breakpoints"; //$NON-NLS-1$

    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$

    private static final String KEY_BREAKPOINTS = "breakpoints"; //$NON-NLS-1$
    private static final String KEY_COUNT = "count"; //$NON-NLS-1$

    private static final String KEY_BREAKPOINT_ID = "breakpointId"; //$NON-NLS-1$
    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$
    private static final String KEY_FILE = "file"; //$NON-NLS-1$
    private static final String KEY_LINE_NUMBER = "lineNumber"; //$NON-NLS-1$
    private static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$
    private static final String KEY_MODEL_ID = "modelId"; //$NON-NLS-1$
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=list_breakpoints`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Lists the line breakpoints currently armed, optionally narrowed to one project."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty(KEY_PROJECT_NAME, "Restrict the list to this project (optional)") //$NON-NLS-1$
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
        String projectFilter = JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME);

        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null)
        {
            return ToolResult.error("The DebugPlugin cannot be reached").toJson(); //$NON-NLS-1$
        }

        IBreakpointManager bpManager = debugPlugin.getBreakpointManager();
        List<Map<String, Object>> out = new ArrayList<>();

        for (IBreakpoint bp : bpManager.getBreakpoints())
        {
            org.eclipse.core.resources.IMarker m = bp.getMarker();
            if (m == null || m.getResource() == null)
            {
                continue;
            }
            // getProject() is null for workspace-root / non-project resources (legal for some EDT
            // internal breakpoints); treat a missing project as an empty name so it does not NPE
            // the whole list.
            String projectName = m.getResource().getProject() == null
                ? "" : m.getResource().getProject().getName(); //$NON-NLS-1$
            if (projectFilter != null && !projectFilter.isEmpty()
                && !projectFilter.equals(projectName))
            {
                continue;
            }

            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put(KEY_BREAKPOINT_ID, Long.valueOf(m.getId()));
            dto.put(KEY_PROJECT, projectName);
            dto.put(KEY_FILE, m.getResource().getFullPath().toString());

            try
            {
                if (bp instanceof ILineBreakpoint)
                {
                    dto.put(KEY_LINE_NUMBER, Integer.valueOf(((ILineBreakpoint)bp).getLineNumber()));
                }
                dto.put(KEY_ENABLED, Boolean.valueOf(bp.isEnabled()));
                dto.put(KEY_MODEL_ID, bp.getModelIdentifier());
            }
            catch (Exception ex)
            {
                dto.put(KEY_ERROR, ex.getMessage());
            }

            out.add(dto);
        }

        return ToolResult.success().put(KEY_BREAKPOINTS, out).put(KEY_COUNT, out.size()).toJson();
    }
}
