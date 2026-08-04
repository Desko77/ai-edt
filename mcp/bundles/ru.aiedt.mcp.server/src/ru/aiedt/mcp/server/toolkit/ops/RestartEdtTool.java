/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Gracefully restarts or shuts down the host 1C:EDT instance.
 *
 * <p>Companion to the {@code edt-selfupdate.ps1} self-update workflow: the script
 * runs the p2 director against the local build repository (which requires the IDE
 * closed), and this tool provides the in-process graceful close that flushes the
 * Business Model cleanly - safer than an OS-level kill. {@code action=restart}
 * relaunches the same workspace via {@link PlatformUI}'s workbench restart;
 * {@code action=shutdown} closes the workbench and leaves the IDE down.
 *
 * <p>The close/restart would tear down the MCP server (it runs inside this IDE) and
 * thus the in-flight HTTP response, so the action is deferred on a short-lived
 * daemon thread by {@code delayMs} (default 1000) and executed on the SWT UI thread.
 * The tool returns immediately; the IDE goes down a moment later. Callers must treat
 * the subsequent connection drop as expected, not an error, and reconnect once the
 * IDE (and its auto-started MCP server) is back up.
 */
public class RestartEdtTool implements IMcpTool
{
    public static final String NAME = "restart_edt"; //$NON-NLS-1$

    private static final int DEFAULT_DELAY_MS = 1000;
    private static final int MAX_DELAY_MS = 60000;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=restart_edt`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Gracefully restart or shut down the host 1C:EDT instance (this IDE). " //$NON-NLS-1$
            + "action=restart (default) relaunches the same workspace; action=shutdown closes it. " //$NON-NLS-1$
            + "The action is deferred by delayMs (default 1000) so this response can be sent first, " //$NON-NLS-1$
            + "then EDT goes down - the MCP server lives inside EDT, so the connection WILL drop; " //$NON-NLS-1$
            + "treat that as expected and reconnect once EDT (and its auto-started MCP server) is back. " //$NON-NLS-1$
            + "A graceful close flushes the Business Model cleanly, unlike an OS kill."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("action", //$NON-NLS-1$
                "restart (default) - relaunch the same workspace; shutdown - close EDT and leave it down.") //$NON-NLS-1$
            .integerProperty("delayMs", //$NON-NLS-1$
                "Delay before the action so this response is delivered first. Default 1000, max 60000. " //$NON-NLS-1$
                + "0 is valid - act immediately after the response is sent.") //$NON-NLS-1$
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
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$
        if (action == null || action.trim().isEmpty())
        {
            action = "restart"; //$NON-NLS-1$
        }
        action = action.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"restart".equals(action) && !"shutdown".equals(action)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Unknown action '" + action //$NON-NLS-1$
                + "' - use restart or shutdown.").toJson(); //$NON-NLS-1$
        }
        final boolean shutdown = "shutdown".equals(action); //$NON-NLS-1$

        Integer delayArg = JsonUtils.extractIntegerArgument(params, "delayMs"); //$NON-NLS-1$
        int delayMs = delayArg == null ? DEFAULT_DELAY_MS : delayArg.intValue();
        if (delayMs < 0)
        {
            delayMs = 0;
        }
        else if (delayMs > MAX_DELAY_MS)
        {
            delayMs = MAX_DELAY_MS;
        }

        if (!PlatformUI.isWorkbenchRunning())
        {
            return ToolResult.error("Workbench is not running - cannot " + action + " EDT.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        final Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("No live SWT display - cannot " + action + " EDT.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        final int finalDelay = delayMs;
        Thread worker = new Thread(() -> {
            try
            {
                Thread.sleep(finalDelay);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return;
            }
            if (display.isDisposed())
            {
                return;
            }
            // close()/restart() must run on the SWT UI thread.
            try
            {
                display.asyncExec(() -> {
                    try
                    {
                        if (!PlatformUI.isWorkbenchRunning())
                        {
                            return;
                        }
                        boolean ok = shutdown
                            ? PlatformUI.getWorkbench().close()
                            : PlatformUI.getWorkbench().restart();
                        if (!ok)
                        {
                            // A part/listener vetoed it (e.g. an unsaved editor
                            // cancelled the close). EDT stays up; the caller was
                            // already told it would go down, so surface it in the log.
                            Activator.logError("restart_edt: " + (shutdown ? "close" : "restart") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                + "() returned false - a listener vetoed it; EDT is still running.", //$NON-NLS-1$
                                null);
                        }
                    }
                    catch (Exception e)
                    {
                        Activator.logError("restart_edt: " + (shutdown ? "shutdown" : "restart") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + " failed", e); //$NON-NLS-1$
                    }
                });
            }
            catch (org.eclipse.swt.SWTException disposed)
            {
                // display was disposed between the isDisposed() check and asyncExec - nothing to do
            }
        }, "mcp-restart-edt"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();

        return ToolResult.success()
            .put("action", action) //$NON-NLS-1$
            .put("delayMs", finalDelay) //$NON-NLS-1$
            .put("note", "EDT will " + action + " in ~" + finalDelay //$NON-NLS-1$ //$NON-NLS-2$
                + "ms. The MCP connection WILL drop (the server runs inside EDT); this is expected. " //$NON-NLS-1$
                + (shutdown
                    ? "EDT stays down - relaunch it (and its MCP server auto-starts)." //$NON-NLS-1$
                    : "Reconnect once EDT and its auto-started MCP server are back up.")) //$NON-NLS-1$
            .toJson();
    }
}
