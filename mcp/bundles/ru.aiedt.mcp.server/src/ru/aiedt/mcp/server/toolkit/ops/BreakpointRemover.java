/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BreakpointAccess;

/**
 * Removes a breakpoint, by the marker id an earlier call returned or by its coordinates.
 * <p>
 * Coordinate-based removal works for line breakpoints only, which is everything the set tool arms.
 * Removal by id works for any breakpoint the manager is holding, because the id is its marker id, and
 * the marker is what a breakpoint of any kind is built on.
 * </p>
 */
public class BreakpointRemover
    implements IMcpTool
{
    private static final String NAME = "remove_breakpoint"; //$NON-NLS-1$

    private static final String KEY_BREAKPOINT_ID = "breakpointId"; //$NON-NLS-1$
    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String KEY_MODULE = "module"; //$NON-NLS-1$
    private static final String KEY_LINE_NUMBER = "lineNumber"; //$NON-NLS-1$
    private static final String KEY_REMOVED = "removed"; //$NON-NLS-1$
    private static final String KEY_BREAKPOINT_IDS = "breakpointIds"; //$NON-NLS-1$
    private static final String KEY_ALL = "all"; //$NON-NLS-1$
    private static final String KEY_ALL_OF_MODULE = "allOfModule"; //$NON-NLS-1$
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$
    private static final String KEY_REMOVED_COUNT = "removedCount"; //$NON-NLS-1$
    private static final String KEY_REQUESTED_COUNT = "requestedCount"; //$NON-NLS-1$
    private static final String KEY_INVALID_IDS = "invalidIds"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=remove_breakpoint`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Removes one or more 1C BSL breakpoints. Single: pass breakpointId (from set_breakpoint), or " //$NON-NLS-1$
            + "look it up by coordinates via projectName+module+lineNumber. Batch: pass `breakpointIds` " //$NON-NLS-1$
            + "(a JSON array of marker ids), or `all=true` to clear every breakpoint, or " //$NON-NLS-1$
            + "`allOfModule=true` with module (+projectName) to clear every breakpoint on one module."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .integerProperty(KEY_BREAKPOINT_ID, "Marker id that set_breakpoint returned") //$NON-NLS-1$
            .stringProperty(KEY_PROJECT_NAME, "EDT project name (needed when looking up by coordinates or allOfModule)") //$NON-NLS-1$
            .stringProperty(KEY_MODULE, "EDT module path or an absolute path (needed when looking up by coordinates or allOfModule)") //$NON-NLS-1$
            .integerProperty(KEY_LINE_NUMBER, "1-based line number (needed when looking up by coordinates)") //$NON-NLS-1$
            .stringProperty(KEY_BREAKPOINT_IDS,
                "Batch mode: a JSON array of marker ids to remove in one call, e.g. [12, 13, 14]. " //$NON-NLS-1$
                    + "Unknown ids are skipped. Response: mode='byIds', removedCount, requestedCount, invalidIds.") //$NON-NLS-1$
            .booleanProperty(KEY_ALL,
                "Batch mode: true clears EVERY registered breakpoint regardless of model. " //$NON-NLS-1$
                    + "Response: mode='all', removedCount.") //$NON-NLS-1$
            .booleanProperty(KEY_ALL_OF_MODULE,
                "Batch mode: true (with module, and projectName when module-relative) clears " //$NON-NLS-1$
                    + "every breakpoint on that module. Response: mode='allOfModule', removedCount.") //$NON-NLS-1$
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
        long breakpointId = JsonUtils.extractLongArgument(params, KEY_BREAKPOINT_ID, -1L);
        String projectName = JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME);
        String module = JsonUtils.extractStringArgument(params, KEY_MODULE);
        int lineNumber = JsonUtils.extractIntArgument(params, KEY_LINE_NUMBER, -1);
        boolean all = JsonUtils.extractBooleanArgument(params, KEY_ALL, false);
        boolean allOfModule = JsonUtils.extractBooleanArgument(params, KEY_ALL_OF_MODULE, false);

        try
        {
            if (all)
            {
                int removed = BreakpointAccess.removeAllBreakpoints();
                return ToolResult.success()
                    .put(KEY_MODE, "all") //$NON-NLS-1$
                    .put(KEY_REMOVED_COUNT, removed)
                    .toJson();
            }
            String idsRaw = JsonUtils.extractStringArgument(params, KEY_BREAKPOINT_IDS);
            if (idsRaw != null && !idsRaw.trim().isEmpty())
            {
                List<Long> ids = new ArrayList<>();
                List<String> invalid = new ArrayList<>();
                List<String> idStrs = JsonUtils.extractArrayArgument(params, KEY_BREAKPOINT_IDS);
                if (idStrs == null)
                {
                    idStrs = java.util.Collections.emptyList();
                }
                for (String s : idStrs)
                {
                    if (s == null)
                    {
                        continue;
                    }
                    try
                    {
                        ids.add(Long.parseLong(s.trim()));
                    }
                    catch (NumberFormatException e)
                    {
                        invalid.add(s);
                    }
                }
                if (ids.isEmpty())
                {
                    return ToolResult.error("breakpointIds held no valid marker ids" //$NON-NLS-1$
                        + (invalid.isEmpty() ? "" //$NON-NLS-1$
                            : " (invalid: " + String.join(", ", invalid) + ")")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                int removed = BreakpointAccess.removeBreakpointsByIds(ids);
                ToolResult res = ToolResult.success()
                    .put(KEY_MODE, "byIds") //$NON-NLS-1$
                    .put(KEY_REMOVED_COUNT, removed)
                    .put(KEY_REQUESTED_COUNT, ids.size());
                if (!invalid.isEmpty())
                {
                    res.put(KEY_INVALID_IDS, String.join(", ", invalid)); //$NON-NLS-1$
                }
                return res.toJson();
            }
            if (allOfModule)
            {
                if (module == null || module.isEmpty())
                {
                    return ToolResult.error(
                        "allOfModule needs module (and projectName when module-relative)").toJson(); //$NON-NLS-1$
                }
                IFile file = BreakpointAccess.resolveModuleFile(projectName, module);
                if (file == null || !file.exists())
                {
                    return ToolResult.error("Could not find module file: " + module).toJson(); //$NON-NLS-1$
                }
                int removed = BreakpointAccess.removeAllBreakpointsInResource(file);
                return ToolResult.success()
                    .put(KEY_MODE, "allOfModule") //$NON-NLS-1$
                    .put(KEY_MODULE, module)
                    .put(KEY_REMOVED_COUNT, removed)
                    .toJson();
            }

            boolean removed;
            if (breakpointId > 0)
            {
                removed = BreakpointAccess.removeBreakpointById(breakpointId);
            }
            else
            {
                if (module == null || module.isEmpty() || lineNumber < 1)
                {
                    return ToolResult.error("Provide either breakpointId or module+lineNumber, " //$NON-NLS-1$
                        + "or use a batch mode (breakpointIds / all / allOfModule)").toJson(); //$NON-NLS-1$
                }
                IFile file = BreakpointAccess.resolveModuleFile(projectName, module);
                if (file == null || !file.exists())
                {
                    return ToolResult.error("Could not find module file: " + module).toJson(); //$NON-NLS-1$
                }
                removed = BreakpointAccess.removeBreakpointAt(file, lineNumber);
            }
            return ToolResult.success().put(KEY_REMOVED, removed).toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Removing the breakpoint raised an exception", e); //$NON-NLS-1$
            return ToolResult.error("Could not remove the breakpoint: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
