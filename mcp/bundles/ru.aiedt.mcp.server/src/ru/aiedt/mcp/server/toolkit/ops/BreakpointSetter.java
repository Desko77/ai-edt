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

import org.eclipse.core.resources.IFile;
import org.eclipse.debug.core.model.IBreakpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BreakpointAccess;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Sets a line breakpoint on a BSL module, one at a time or many in a single call.
 * <p>
 * A single call resolves the module, asks EDT for a real BSL breakpoint, and falls back to a
 * marker-only one when EDT's class is unreachable - and says so, because a breakpoint that will not
 * fire is worse than none. The options a debugger breakpoint carries (a condition, a hit count, a
 * logpoint) are applied where the breakpoint supports them, and reported as applied or rejected so an
 * agent does not wait at a breakpoint that stops on every pass.
 * </p>
 * <p>
 * Batch mode exists because an arming a scenario breakpoint by breakpoint is slow, and an agent that
 * knows the layout of a test up front can name every line in one call. Each item is its own attempt:
 * one failure does not roll back the others, and the response reports per-item outcome plus a tally.
 * </p>
 */
public class BreakpointSetter
    implements IMcpTool
{
    private static final String NAME = "set_breakpoint"; //$NON-NLS-1$

    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String KEY_MODULE = "module"; //$NON-NLS-1$
    private static final String KEY_MODULE_PATH = "modulePath"; //$NON-NLS-1$
    private static final String KEY_LINE_NUMBER = "lineNumber"; //$NON-NLS-1$
    private static final String KEY_LINE = "line"; //$NON-NLS-1$
    private static final String KEY_BREAKPOINTS = "breakpoints"; //$NON-NLS-1$
    private static final String KEY_CONDITION = "condition"; //$NON-NLS-1$
    private static final String KEY_HIT_COUNT = "hitCount"; //$NON-NLS-1$
    private static final String KEY_HIT_CONDITION = "hitCondition"; //$NON-NLS-1$
    private static final String KEY_LOG_EXPRESSION = "logExpression"; //$NON-NLS-1$

    private static final String KEY_BREAKPOINT_ID = "breakpointId"; //$NON-NLS-1$
    private static final String KEY_RESOLVED_FILE = "resolvedFile"; //$NON-NLS-1$
    private static final String KEY_DEGRADED = "degraded"; //$NON-NLS-1$
    private static final String KEY_WARNING = "warning"; //$NON-NLS-1$

    private static final String KEY_BATCH = "batch"; //$NON-NLS-1$
    private static final String KEY_OK = "ok"; //$NON-NLS-1$
    private static final String KEY_FAIL = "fail"; //$NON-NLS-1$
    private static final String KEY_BREAKPOINT_RESULTS = "breakpointResults"; //$NON-NLS-1$
    private static final String KEY_INDEX = "index"; //$NON-NLS-1$
    private static final String KEY_RESPONSE = "response"; //$NON-NLS-1$
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    private static final String SUCCESS_TRUE = "\"success\":true"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=add_breakpoint`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Arms a line breakpoint on a 1C BSL module. Accepts either an EDT module-relative path " //$NON-NLS-1$
            + "(e.g. 'CommonModules/Foo/Module.bsl') or an absolute filesystem path. Optional: " //$NON-NLS-1$
            + "condition (suspend only when a BSL expression evaluates true), hitCount/hitCondition (suspend " //$NON-NLS-1$
            + "on the Nth hit), logExpression (a logpoint - evaluate and keep running without suspending). Batch: " //$NON-NLS-1$
            + "pass `breakpoints` (a JSON array) to arm many breakpoints in one call. Follow up with " //$NON-NLS-1$
            + "wait_for_break to block until one is hit."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty(KEY_PROJECT_NAME,
                "EDT project name (required when module is a module-relative path). In batch mode it is inherited by items that omit it.") //$NON-NLS-1$
            .stringProperty(KEY_MODULE,
                "Module identifier - an EDT module path (CommonModules/Foo/Module.bsl) or an absolute file path. The modulePath alias also works. Required in single mode; ignored when `breakpoints` is given.") //$NON-NLS-1$
            .integerProperty(KEY_LINE_NUMBER,
                "1-based line number (the line alias also works). Required in single mode; ignored when `breakpoints` is given.") //$NON-NLS-1$
            .stringProperty(KEY_BREAKPOINTS,
                "Batch mode: a JSON array of breakpoint objects armed in one call, each {\"module\":\"CommonModules/Foo/Module.bsl\",\"lineNumber\":42, ...optional condition / hitCount / hitCondition / logExpression}. The modulePath/line aliases work per item; projectName is inherited from the outer call when an item omits it. When present, top-level module/lineNumber/condition/... are ignored. Response: breakpointResults[] (index, module, lineNumber, ok, response) plus ok / fail counts.") //$NON-NLS-1$
            .stringProperty(KEY_CONDITION,
                "Conditional breakpoint: a BSL expression; the debugger suspends only once it evaluates to true (e.g. \"Counter > 100\"). Needs the EDT BSL breakpoint class - not the marker-only fallback.") //$NON-NLS-1$
            .integerProperty(KEY_HIT_COUNT,
                "Hit-count breakpoint: suspend on the Nth hit (paired with hitCondition).") //$NON-NLS-1$
            .stringProperty(KEY_HIT_CONDITION,
                "Hit-count comparison: EQUALS (default) / EQUAL_OR_LESS / EQUAL_OR_HIGHER / MULTIPLIER. Only meaningful alongside hitCount.") //$NON-NLS-1$
            .stringProperty(KEY_LOG_EXPRESSION,
                "Logpoint (tracepoint): evaluates this BSL expression and CONTINUES without suspending. Instruments a hot path with no code edit and no worker block; the value is written with a stack trace to the debug output.") //$NON-NLS-1$
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
        String breakpointsRaw = JsonUtils.extractStringArgument(params, KEY_BREAKPOINTS);
        if (breakpointsRaw != null && !breakpointsRaw.trim().isEmpty())
        {
            return executeBatch(breakpointsRaw, params);
        }
        return setOne(params);
    }

    /**
     * Sets one breakpoint. Reused by the batch path, which is why it takes its arguments as a map and
     * returns the raw JSON rather than throwing: a caller that wants to keep going on a single failure
     * needs both.
     *
     * @param params the arguments, single-call shape or one batch item
     * @return the result document
     */
    private String setOne(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME);

        String module = JsonUtils.extractStringArgument(params, KEY_MODULE);
        if (module == null || module.isEmpty())
        {
            module = JsonUtils.extractStringArgument(params, KEY_MODULE_PATH);
        }

        int lineNumber = JsonUtils.extractIntArgument(params, KEY_LINE_NUMBER, -1);
        if (lineNumber < 1)
        {
            lineNumber = JsonUtils.extractIntArgument(params, KEY_LINE, -1);
        }

        if (module == null || module.isEmpty())
        {
            return ToolResult.error("module (or modulePath) must be provided").toJson(); //$NON-NLS-1$
        }
        if (lineNumber < 1)
        {
            return ToolResult.error("lineNumber (or line) must be 1 or greater").toJson(); //$NON-NLS-1$
        }

        boolean modulePathStyle = !BreakpointAccess.looksLikeAbsolutePath(module);
        if (modulePathStyle && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error("projectName is required when module is an EDT module path").toJson(); //$NON-NLS-1$
        }

        try
        {
            // checkReadyOrError + resolveModuleFile + the existence check all live inside the try so
            // a workspace failure during shutdown is reported as an error instead of propagating as
            // an uncaught NPE.
            if (modulePathStyle)
            {
                String notReady = ProjectStateGuard.checkReadyOrError(projectName);
                if (notReady != null)
                {
                    return ToolResult.error(notReady).toJson();
                }
            }

            IFile file = BreakpointAccess.resolveModuleFile(projectName, module);
            if (file == null || !file.exists())
            {
                String where = modulePathStyle ? " in project " + projectName : ""; //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.error("Could not find module file: " + module + where).toJson(); //$NON-NLS-1$
            }

            IBreakpoint bp = BreakpointAccess.createLineBreakpoint(file, lineNumber);
            long markerId = bp.getMarker() != null ? bp.getMarker().getId() : -1L;
            boolean degraded = bp instanceof BreakpointAccess.MarkerOnlyBreakpoint;

            String condition = JsonUtils.extractStringArgument(params, KEY_CONDITION);
            Integer hitCount = JsonUtils.extractIntegerArgument(params, KEY_HIT_COUNT);
            String hitCondition = JsonUtils.extractStringArgument(params, KEY_HIT_CONDITION);
            String logExpression = JsonUtils.extractStringArgument(params, KEY_LOG_EXPRESSION);

            Map<String, Object> options =
                BreakpointAccess.applyBreakpointOptions(bp, condition, hitCount, hitCondition, logExpression);

            Activator.logInfo("Breakpoint armed: " + file.getFullPath() + ":" + lineNumber //$NON-NLS-1$ //$NON-NLS-2$
                + (degraded ? " (degraded - marker-only)" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + (options.isEmpty() ? "" : " options=" + options.keySet())); //$NON-NLS-1$ //$NON-NLS-2$

            ToolResult res = ToolResult.success()
                .put(KEY_BREAKPOINT_ID, markerId)
                .put(KEY_MODULE, module)
                .put(KEY_RESOLVED_FILE, file.getFullPath().toString())
                .put(KEY_LINE_NUMBER, lineNumber);
            for (Map.Entry<String, Object> entry : options.entrySet())
            {
                res.put(entry.getKey(), entry.getValue());
            }
            if (degraded)
            {
                res.put(KEY_DEGRADED, true);
                res.put(KEY_WARNING,
                    "The EDT BSL breakpoint class could not be reached, so a marker-only breakpoint was created; it may NOT trigger debug suspend events. Check in EDT that the breakpoint shows up in the Breakpoints view."); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Arming the breakpoint raised an exception", e); //$NON-NLS-1$
            return ToolResult.error("Could not arm the breakpoint: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Sets a batch of breakpoints. Each item is resolved through {@link #setOne(Map)}, so the single
     * path's validation, readiness gate and option handling apply per item. An item's failure does not
     * stop the rest.
     *
     * @param raw the JSON array
     * @param outer the outer parameters, for the projectName an item may inherit
     * @return the batch result document
     */
    private String executeBatch(String raw, Map<String, String> outer)
    {
        JsonArray arr;
        try
        {
            JsonElement el = JsonParser.parseString(raw.trim());
            if (!el.isJsonArray())
            {
                return ToolResult
                    .error("breakpoints must be a JSON array of {module, lineNumber, ...} objects").toJson(); //$NON-NLS-1$
            }
            arr = el.getAsJsonArray();
        }
        catch (Exception e)
        {
            return ToolResult.error("breakpoints is not valid JSON: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        if (arr.size() == 0)
        {
            return ToolResult.error("the breakpoints array is empty").toJson(); //$NON-NLS-1$
        }

        String outerProject = JsonUtils.extractStringArgument(outer, KEY_PROJECT_NAME);
        List<Map<String, Object>> results = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;

        for (int i = 0; i < arr.size(); i++)
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(KEY_INDEX, Integer.valueOf(i));

            Map<String, String> item;
            try
            {
                item = flattenItem(arr.get(i));
            }
            catch (Exception itemEx)
            {
                entry.put(KEY_OK, Boolean.FALSE);
                entry.put(KEY_ERROR, "item " + i + " is not a JSON object"); //$NON-NLS-1$ //$NON-NLS-2$
                failCount++;
                results.add(entry);
                continue;
            }

            if (!item.containsKey(KEY_MODULE) && item.containsKey(KEY_MODULE_PATH))
            {
                item.put(KEY_MODULE, item.get(KEY_MODULE_PATH));
            }
            if (!item.containsKey(KEY_LINE_NUMBER) && item.containsKey(KEY_LINE))
            {
                item.put(KEY_LINE_NUMBER, item.get(KEY_LINE));
            }
            if (!item.containsKey(KEY_PROJECT_NAME) && outerProject != null && !outerProject.isEmpty())
            {
                item.put(KEY_PROJECT_NAME, outerProject);
            }

            entry.put(KEY_MODULE, item.get(KEY_MODULE));
            entry.put(KEY_LINE_NUMBER, item.get(KEY_LINE_NUMBER));

            String json;
            try
            {
                json = setOne(item);
            }
            catch (Exception setEx)
            {
                Activator.logError("batch breakpoint item " + i + " raised an exception", setEx); //$NON-NLS-1$ //$NON-NLS-2$
                entry.put(KEY_OK, Boolean.FALSE);
                entry.put(KEY_ERROR, setEx.getMessage() != null ? setEx.getMessage() : setEx.toString());
                failCount++;
                results.add(entry);
                continue;
            }

            entry.put(KEY_RESPONSE, json);

            boolean ok = detectSuccess(json);
            entry.put(KEY_OK, Boolean.valueOf(ok));
            if (ok)
            {
                okCount++;
            }
            else
            {
                failCount++;
            }
            results.add(entry);
        }

        return ToolResult.success()
            .put(KEY_BATCH, true)
            .put(KEY_OK, okCount)
            .put(KEY_FAIL, failCount)
            .put(KEY_BREAKPOINT_RESULTS, results)
            .toJson();
    }

    /**
     * Unpacks one batch item into the flat string map {@link #setOne(Map)} reads. Null-valued members
     * are dropped; primitives become their string form; anything else is rendered back to JSON.
     *
     * @param element the item as the parser saw it
     * @return the flat map
     * @throws Exception when the item is not an object
     */
    private static Map<String, String> flattenItem(JsonElement element) throws Exception
    {
        JsonObject obj = element.getAsJsonObject();
        Map<String, String> item = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet())
        {
            JsonElement value = e.getValue();
            if (value.isJsonNull())
            {
                continue;
            }
            String flat = value.isJsonPrimitive() ? value.getAsString() : value.toString();
            item.put(e.getKey(), flat);
        }
        return item;
    }

    /**
     * Tells whether a per-item JSON answer reported success. The structure is well-defined, but a
     * single field is cheap to read and impossible to misread, so it is read directly - and, failing
     * that, by substring, because whatever setOne returned is what the agent needs to see.
     *
     * @param json the per-item result document
     * @return whether it reported success
     */
    private static boolean detectSuccess(String json)
    {
        try
        {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has(KEY_SUCCESS) && obj.get(KEY_SUCCESS).getAsBoolean();
        }
        catch (Exception ignored)
        {
            // Tolerate the space Gson puts after the colon ("success": true) so this dead-ish
            // fallback is still correct if the primary JsonObject parse ever fails.
            return json != null
                && json.replaceAll("\"success\"\\s*:\\s*true", "\"success\":true").contains(SUCCESS_TRUE); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
