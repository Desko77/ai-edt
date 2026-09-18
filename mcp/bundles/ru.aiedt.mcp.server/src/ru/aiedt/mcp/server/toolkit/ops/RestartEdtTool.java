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

        // Preflight BEFORE anything closes. A restart that cannot start anything is refused while
        // the workspace is still alive to hear it: the workbench restarts only when the launcher
        // relaunches it, and measured twice (16.09 and 17.09) PlatformUI's restart on this
        // instance answered success and the workbench never came back. The restart instead hands
        // off: this instance closes gracefully, and a detached watcher - outside this process, so
        // it survives - waits for the workspace to free and starts the same executable with the
        // same arguments.
        java.util.List<String> relaunchCommand = null;
        if (!shutdown)
        {
            relaunchCommand = relaunchCommandOf();
            if (relaunchCommand == null)
            {
                return ToolResult.error("The restart is refused before anything closes: " //$NON-NLS-1$
                    + relaunchProblem + " Nothing was closed.").toJson(); //$NON-NLS-1$
            }
        }

        final int finalDelay = delayMs;
        final java.util.List<String> command = relaunchCommand;
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
                        if (!shutdown && command != null)
                        {
                            // Started BEFORE the close: the watcher must outlive this process, and
                            // a process started from within a closing JVM dies with it - measured.
                            // It waits for the workspace to free and then launches the same
                            // executable with the same arguments.
                            startWatcher(command);
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
                    : "A detached watcher waits for the workspace to free and starts the same EDT " //$NON-NLS-1$
                        + "with the same arguments; what happens after this process closes is past " //$NON-NLS-1$
                        + "what this answer can see. Reconnect once /health answers again.")) //$NON-NLS-1$
            .toJson();
    }

    /**
     * The command line this instance was started with, as something relaunchable.
     * <p>
     * Read from the JVM, not reassembled: the launcher's own arguments include the workspace, the
     * VM and the memory settings, and a line put together here would get exactly one of them wrong
     * - measured: a command whose {@code -vm} path lost its quotes makes the launcher wait forever
     * without a window.
     * </p>
     *
     * @return the command line parts, or <code>null</code> when they cannot be read
     */
    static java.util.List<String> relaunchCommandOf()
    {
        relaunchProblem = null;
        String home = System.getProperty("eclipse.home.location"); //$NON-NLS-1$
        if (home == null)
        {
            relaunchProblem = "eclipse.home.location is not set - the installation root is unknown."; //$NON-NLS-1$
            return null;
        }
        java.io.File homeDir;
        String workspace = System.getProperty("osgi.instance.area"); //$NON-NLS-1$
        java.io.File workspaceDir;
        try
        {
            homeDir = new java.io.File(java.net.URI.create(home.replace(" ", "%20"))); //$NON-NLS-1$ //$NON-NLS-2$
            if (workspace == null)
            {
                relaunchProblem = "osgi.instance.area is not set - the workspace is unknown."; //$NON-NLS-1$
                return null;
            }
            workspaceDir = new java.io.File(java.net.URI.create(workspace.replace(" ", "%20"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IllegalArgumentException e)
        {
            relaunchProblem = "a location could not be read: " + e.getMessage(); //$NON-NLS-1$
            return null;
        }
        java.io.File launcher = new java.io.File(homeDir, "1cedt.exe"); //$NON-NLS-1$
        if (!launcher.isFile())
        {
            launcher = new java.io.File(homeDir, "eclipse"); //$NON-NLS-1$
            if (!launcher.isFile())
            {
                relaunchProblem = "no launcher executable (1cedt.exe / eclipse) under " + home; //$NON-NLS-1$
                return null;
            }
        }
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(launcher.getAbsolutePath());
        command.add("-data"); //$NON-NLS-1$
        command.add(workspaceDir.getAbsolutePath());
        String vm = System.getProperty("eclipse.vm"); //$NON-NLS-1$
        if (vm != null)
        {
            command.add("-vm"); //$NON-NLS-1$
            command.add(vm);
        }
        command.add("--launcher.appendVmargs"); //$NON-NLS-1$
        command.add("-vmargs"); //$NON-NLS-1$
        for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments())
        {
            command.add(arg);
        }
        return command;
    }

    /** Set by {@link #relaunchCommandOf} when the command cannot be assembled, naming why. */
    private static String relaunchProblem;

    /**
     * Starts the process that waits for this one to end and relaunches it.
     * <p>
     * The watcher is a JVM of its own running a class of this bundle, because a Java watcher
     * quotes no path and parses no command line: it is given the command part by part.
     * </p>
     *
     * @param command the command line to run once this process is gone.
     */
    private static void startWatcher(java.util.List<String> command)
    {
        try
        {
            String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
            java.io.File javaw = new java.io.File(new java.io.File(javaHome, "bin"), "javaw.exe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String javaExe = javaw.isFile() ? javaw.getAbsolutePath()
                : new java.io.File(new java.io.File(javaHome, "bin"), "java").getAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$
            java.util.List<String> pb = new java.util.ArrayList<>();
            pb.add(javaExe);
            pb.add("-cp"); //$NON-NLS-1$
            pb.add(watcherClassPath());
            pb.add(Relauncher.class.getName());
            pb.add(String.valueOf(ProcessHandle.current().pid()));
            pb.addAll(command);
            ProcessBuilder builder = new ProcessBuilder(pb);
            builder.redirectErrorStream(true);
            Process watcher = builder.start();
            // Nothing reads its output; close stdin so nothing blocks on it.
            watcher.getOutputStream().close();
            Activator.logInfo("restart_edt: relaunch watcher started, pid=" + watcher.pid()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("restart_edt: the relaunch watcher could not be started - " //$NON-NLS-1$
                + "EDT will close with nothing to bring it back", e); //$NON-NLS-1$
        }
    }

    private static String watcherClassPath()
    {
        java.net.URL url = RestartEdtTool.class.getProtectionDomain().getCodeSource().getLocation();
        try
        {
            return new java.io.File(url.toURI()).getAbsolutePath();
        }
        catch (java.net.URISyntaxException e)
        {
            return url.getPath();
        }
    }

    /**
     * The detached half of a restart: waits for the old instance to leave the workspace, then
     * starts the command it was given.
     * <p>
     * Started from within a closing JVM it would die with it; the watcher is a JVM of its own for
     * exactly that reason.
     * </p>
     */
    public static final class Relauncher
    {
        private Relauncher()
        {
        }

        /**
         * @param args pid of the instance to wait out, then the command line to start.
         */
        public static void main(String[] args)
        {
            if (args.length < 2)
            {
                return;
            }
            long pid = Long.parseLong(args[0]);
            java.util.List<String> command =
                java.util.Arrays.asList(args).subList(1, args.length);
            try
            {
                // The workbench takes a moment to leave the workspace; starting earlier meets
                // the old instance's lock and the new one dies or shows a dialog instead.
                Thread.sleep(5000);
                while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                {
                    Thread.sleep(1000);
                }
                new ProcessBuilder(command).start();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }
}
