/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.model.IBreakpoint;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BreakpointAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Sets a 1C BSL exception breakpoint - the debugger suspends where an error is
 * RAISED, without knowing the line. Scoped to a project. With {@code message} it
 * fires only for exceptions whose text matches; otherwise (catchAll=true, the
 * default) it breaks on any raised error.
 *
 * <p>Complements line breakpoints: use this to catch the origin of an error you
 * cannot localise. Requires the EDT debug-core plugin (no marker-only fallback).
 */
public class SetExceptionBreakpointTool implements IMcpTool
{
    public static final String NAME = "set_exception_breakpoint"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=set_exception_breakpoint`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Set a BSL exception breakpoint: suspend where an error is RAISED, without a line. " //$NON-NLS-1$
            + "Scoped to projectName. message (optional) limits to exceptions whose text matches; " //$NON-NLS-1$
            + "catchAll (default true) breaks on any raised error. Use wait_for_break afterwards. " //$NON-NLS-1$
            + "Requires the EDT debug-core plugin."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT project to scope the exception breakpoint to (required)", //$NON-NLS-1$ //$NON-NLS-2$
                true)
            .stringProperty("message", //$NON-NLS-1$
                "Optional: only break for exceptions whose text matches this message. " //$NON-NLS-1$
                    + "Omit to break on any error.") //$NON-NLS-1$
            .booleanProperty("catchAll", //$NON-NLS-1$
                "Break on any raised error. Default true; default false when message is set. " //$NON-NLS-1$
                    + "Pass explicitly to override the default.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("projectName", //$NON-NLS-1$
                "set_exception_breakpoint projectName=MyProject [message=...] [catchAll=true]")).toJson(); //$NON-NLS-1$
        }
        String message = JsonUtils.extractStringArgument(params, "message"); //$NON-NLS-1$
        boolean hasMessage = message != null && !message.trim().isEmpty();
        // Default catchAll=true, but a specific message implies catchAll=false.
        Boolean catchAllArg = JsonUtils.extractBooleanArgumentNullable(params, "catchAll"); //$NON-NLS-1$
        boolean catchAll = catchAllArg != null ? catchAllArg.booleanValue() : !hasMessage;

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        try
        {
            IBreakpoint bp = BreakpointAccess.createExceptionBreakpoint(project, message, catchAll);
            long markerId = bp.getMarker() != null ? bp.getMarker().getId() : -1L;
            Activator.logInfo("Exception breakpoint set in " + projectName //$NON-NLS-1$
                + (hasMessage ? " for message '" + message.trim() + "'" : " (all errors)")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ToolResult res = ToolResult.success()
                .put("breakpointId", markerId) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("catchAll", catchAll); //$NON-NLS-1$
            if (hasMessage)
            {
                res.put("message", message.trim()); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to set exception breakpoint", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set exception breakpoint: " //$NON-NLS-1$
                + TextSuggest.safeMessage(e)).toJson();
        }
    }
}
