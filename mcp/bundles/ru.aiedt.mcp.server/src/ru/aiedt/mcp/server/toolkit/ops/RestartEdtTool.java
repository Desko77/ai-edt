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

    /** The launcher {@code .ini} VM block that produced this process, captured at activation. */
    private static volatile java.util.List<String> startupIniVmArguments;

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
        final String vmArgumentsNote = shutdown ? null : relaunchVmArgumentsNote;
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
                            // The watcher owns the relaunch, so the workbench is CLOSED, not
                            // restarted through the launcher: a launcher that honours the restart
                            // exit code would start its own replacement, and two would race for
                            // the same workspace. The watcher starts BEFORE the close because a
                            // process started from within a closing JVM dies with it - measured.
                            // A veto is caught below and the watcher taken down again, so nothing
                            // is left waiting for a PID whose owner stayed alive.
                            Process watcher = startWatcher(command);
                            boolean viaLauncher = restartsThroughLauncher(shutdown, watcher != null);
                            boolean ok = viaLauncher
                                ? PlatformUI.getWorkbench().restart()
                                : PlatformUI.getWorkbench().close();
                            if (!ok && watcher != null && watcher.isAlive())
                            {
                                // The workbench vetoed the close (an unsaved editor, a listener).
                                // EDT stays up, and the watcher must not stay behind waiting for
                                // a PID whose owner never left - or every later ordinary exit
                                // would bring EDT back on its own.
                                watcher.destroy();
                            }
                            if (!ok)
                            {
                                // A part/listener vetoed it (e.g. an unsaved editor
                                // cancelled the close). EDT stays up; the caller was
                                // already told it would go down, so surface it in the log.
                                Activator.logError("restart_edt: " + (viaLauncher ? "restart" : "close") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                    + "() returned false - a listener vetoed it; EDT is still running.", //$NON-NLS-1$
                                    null);
                            }
                        }
                        else
                        {
                            boolean ok = shutdown
                                ? PlatformUI.getWorkbench().close()
                                : PlatformUI.getWorkbench().restart();
                            if (!ok)
                            {
                                Activator.logError("restart_edt: " + (shutdown ? "close" : "restart") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                    + "() returned false - a listener vetoed it; EDT is still running.", //$NON-NLS-1$
                                    null);
                            }
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
            .put("vmArgumentsNote", vmArgumentsNote) //$NON-NLS-1$
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
     * Each piece is read by name - the installation root, the workspace, the VM and the arguments
     * the JVM reports - and never taken from a split command line: a path put back together from a
     * string gets one of them wrong, and measured, a command whose {@code -vm} path lost its quotes
     * makes the launcher wait forever without a window.
     * </p>
     *
     * @return the command line parts, or <code>null</code> when they cannot be read
     */
    static java.util.List<String> relaunchCommandOf()
    {
        relaunchProblem = null;
        relaunchVmArgumentsNote = null;
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
        RelaunchPlan plan = relaunchCommandOf(startupIniVmArguments,
            System.getProperty("eclipse.vmargs"), launcher.getAbsolutePath(), //$NON-NLS-1$
            workspaceDir.getAbsolutePath(), System.getProperty("eclipse.vm")); //$NON-NLS-1$
        relaunchVmArgumentsNote = vmArgumentsNoteOf(plan);
        return plan.command();
    }

    static String vmArgumentsNoteOf(RelaunchPlan plan)
    {
        return plan.vmArgumentsOmissionReason() == null ? null
            : "User VM arguments were not carried over: " //$NON-NLS-1$
                + plan.vmArgumentsOmissionReason()
                + " The launcher will use the current .ini VM block."; //$NON-NLS-1$
    }

    /**
     * Assembles the relaunch command line from the pieces it is made of.
     * <p>
     * {@code eclipse.vmargs} is the launcher's lossless, ordered account of its original VM
     * arguments. Its leading block is removed using the {@code .ini} snapshot taken when this
     * bundle activated, as are complete copies left by older restarts. Only the remaining user
     * arguments are passed with {@code --launcher.appendVmargs}; the launcher reads its current
     * {@code .ini} itself, so an edit made while EDT was running takes effect on restart.
     * </p>
     * <p>
     * When the property is absent or does not begin with the snapshot, no VM arguments are passed.
     * Guessing in that case would either regrow the command or let stale settings override the
     * current {@code .ini}; the plan names the omission so the tool can report it.
     * </p>
     *
     * @param iniVmArgumentsAtStartup the launcher's {@code .ini} VM block captured at activation.
     * @param eclipseVmargs the newline-separated {@code eclipse.vmargs} system property.
     * @param launcherPath the launcher executable to start.
     * @param workspacePath the workspace to open.
     * @param vm the VM the launcher is to use, or <code>null</code> to leave the choice to it.
     * @return the command and, when applicable, the reason user arguments were omitted.
     */
    static RelaunchPlan relaunchCommandOf(java.util.List<String> iniVmArgumentsAtStartup,
        String eclipseVmargs, String launcherPath, String workspacePath, String vm)
    {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(launcherPath);
        command.add("-data"); //$NON-NLS-1$
        command.add(workspacePath);
        if (vm != null && !vm.isEmpty())
        {
            command.add("-vm"); //$NON-NLS-1$
            command.add(vm);
        }

        String omissionReason = vmArgumentsOmissionReason(iniVmArgumentsAtStartup, eclipseVmargs);
        if (omissionReason == null)
        {
            java.util.List<String> userArguments = extraVmArguments(
                vmArgumentsFromProperty(eclipseVmargs), iniVmArgumentsAtStartup);
            if (!userArguments.isEmpty())
            {
                command.add("--launcher.appendVmargs"); //$NON-NLS-1$
                command.add("-vmargs"); //$NON-NLS-1$
                command.addAll(userArguments);
            }
        }
        return new RelaunchPlan(command, omissionReason);
    }

    private static String vmArgumentsOmissionReason(
        java.util.List<String> iniVmArgumentsAtStartup, String eclipseVmargs)
    {
        if (eclipseVmargs == null)
        {
            return "the eclipse.vmargs system property is not set."; //$NON-NLS-1$
        }
        if (iniVmArgumentsAtStartup == null || iniVmArgumentsAtStartup.isEmpty())
        {
            return "the startup .ini VM argument snapshot is empty."; //$NON-NLS-1$
        }
        if (!blockStartsAt(vmArgumentsFromProperty(eclipseVmargs), 0,
            iniVmArgumentsAtStartup))
        {
            return "eclipse.vmargs does not start with the startup .ini VM argument snapshot."; //$NON-NLS-1$
        }
        return null;
    }

    /** Splits the property exactly as {@code Main.setMultiValueProperty} joined it. */
    private static java.util.List<String> vmArgumentsFromProperty(String eclipseVmargs)
    {
        java.util.List<String> arguments = new java.util.ArrayList<>();
        if (eclipseVmargs == null || eclipseVmargs.isEmpty())
        {
            return arguments;
        }
        String[] lines = eclipseVmargs.split("\\n", -1); //$NON-NLS-1$
        int length = lines.length;
        if (length > 0 && lines[length - 1].isEmpty())
        {
            length--;
        }
        for (int i = 0; i < length; i++)
        {
            arguments.add(lines[i]);
        }
        return arguments;
    }

    /** Immutable output of the pure relaunch-command builder. */
    static final class RelaunchPlan
    {
        private final java.util.List<String> command;
        private final String vmArgumentsOmissionReason;

        RelaunchPlan(java.util.List<String> command, String vmArgumentsOmissionReason)
        {
            this.command = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(command));
            this.vmArgumentsOmissionReason = vmArgumentsOmissionReason;
        }

        java.util.List<String> command()
        {
            return command;
        }

        String vmArgumentsOmissionReason()
        {
            return vmArgumentsOmissionReason;
        }
    }

    /**
     * The arguments of an instance that are not the launcher's own {@code .ini} block.
     * <p>
     * Every complete copy of the block is dropped, not one: an instance that has already been
     * restarted carries one copy per restart, and one removal would leave the rest in place. An
     * argument of the caller's own is kept - only a run that reproduces the whole block, line for
     * line, is read as another copy of it, and an argument equal to a single line of the block
     * does not by itself reproduce one.
     * </p>
     *
     * @param inputArguments the ordered arguments recorded in {@code eclipse.vmargs}.
     * @param iniVmArguments the VM arguments of the launcher's own {@code .ini}.
     * @return the arguments that did not come from the {@code .ini}, in their original order.
     */
    static java.util.List<String> extraVmArguments(java.util.List<String> inputArguments,
        java.util.List<String> iniVmArguments)
    {
        java.util.List<String> extras = new java.util.ArrayList<>();
        if (inputArguments == null || inputArguments.isEmpty())
        {
            return extras;
        }
        int blockLength = iniVmArguments == null ? 0 : iniVmArguments.size();
        int i = 0;
        while (i < inputArguments.size())
        {
            if (blockLength > 0 && blockStartsAt(inputArguments, i, iniVmArguments))
            {
                i += blockLength;
                continue;
            }
            extras.add(inputArguments.get(i));
            i++;
        }
        return extras;
    }

    /**
     * Whether the {@code .ini} block stands here in full.
     *
     * @param arguments the arguments to look in.
     * @param offset where the block is expected to start.
     * @param block the block to look for; empty or <code>null</code> never matches.
     * @return whether every line of the block matches at this offset.
     */
    private static boolean blockStartsAt(java.util.List<String> arguments, int offset,
        java.util.List<String> block)
    {
        if (block == null || block.isEmpty() || offset + block.size() > arguments.size())
        {
            return false;
        }
        for (int i = 0; i < block.size(); i++)
        {
            if (!block.get(i).equals(arguments.get(offset + i)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The VM arguments the launcher takes from its own {@code .ini}.
     * <p>
     * The file is {@code <launcher>.ini} beside the executable, and the block is every raw line
     * after the exact {@code -vmargs} line. Eclipse documents whitespace as significant in this
     * file, so no line is trimmed or otherwise normalised.
     * </p>
     *
     * @param launcher the launcher executable.
     * @return the arguments after {@code -vmargs}, in file order, or empty when there are none.
     */
    static java.util.List<String> iniVmArgumentsOf(java.io.File launcher)
    {
        java.util.List<String> arguments = new java.util.ArrayList<>();
        if (launcher == null)
        {
            return arguments;
        }
        String name = launcher.getName();
        int dot = name.lastIndexOf('.');
        java.io.File ini = new java.io.File(launcher.getParentFile(),
            (dot > 0 ? name.substring(0, dot) : name) + ".ini"); //$NON-NLS-1$
        java.util.List<String> lines;
        try
        {
            lines = java.nio.file.Files.readAllLines(ini.toPath(),
                java.nio.charset.StandardCharsets.UTF_8);
        }
        catch (java.io.IOException | RuntimeException unreadable)
        {
            return arguments;
        }
        boolean inVmArgs = false;
        for (String line : lines)
        {
            if (!inVmArgs)
            {
                inVmArgs = "-vmargs".equals(line); //$NON-NLS-1$
                continue;
            }
            arguments.add(line);
        }
        return arguments;
    }

    /** Captures the launcher's VM block once, on the bundle activation thread. */
    public static void captureIniVmArgumentsAtStartup()
    {
        java.util.List<String> snapshot = java.util.Collections.emptyList();
        String home = System.getProperty("eclipse.home.location"); //$NON-NLS-1$
        if (home != null)
        {
            try
            {
                java.io.File homeDir = new java.io.File(
                    java.net.URI.create(home.replace(" ", "%20"))); //$NON-NLS-1$ //$NON-NLS-2$
                java.io.File launcher = new java.io.File(homeDir, "1cedt.exe"); //$NON-NLS-1$
                if (!launcher.isFile())
                {
                    launcher = new java.io.File(homeDir, "eclipse"); //$NON-NLS-1$
                }
                snapshot = iniVmArgumentsOf(launcher);
            }
            catch (IllegalArgumentException ignored)
            {
                // A restart can still use the current .ini; it will report that extras were omitted.
            }
        }
        startupIniVmArguments = java.util.Collections.unmodifiableList(
            new java.util.ArrayList<>(snapshot));
    }

    /** Set by {@link #relaunchCommandOf} when the command cannot be assembled, naming why. */
    private static String relaunchProblem;

    /** Explanation included in a successful restart response when extras could not be identified. */
    private static String relaunchVmArgumentsNote;

    /**
     * Whether the workbench is restarted through the launcher's restart exit code, or closed.
     * <p>
     * A watcher that owns the relaunch means the workbench is closed: a launcher that honours the
     * restart exit code would start a replacement of its own beside the watcher's, and the two
     * race for one workspace - the loser stays at the workspace-in-use dialog. Measured: 16
     * restarts left 16 such instances. Without a watcher the launcher is the only way back, and
     * a shutdown never restarts.
     * </p>
     *
     * @param shutdown whether the action is a shutdown rather than a restart.
     * @param watcherOwnsRelaunch whether a relaunch watcher was started for this restart.
     * @return {@code true} to call {@code restart()}, {@code false} to call {@code close()}.
     */
    static boolean restartsThroughLauncher(boolean shutdown, boolean watcherOwnsRelaunch)
    {
        return !shutdown && !watcherOwnsRelaunch;
    }

    /**
     * Starts the process that waits for this one to end and relaunches it.
     * <p>
     * The watcher is a JVM of its own running a class of this bundle, because a Java watcher
     * quotes no path and parses no command line: it is given the command part by part.
     * </p>
     *
     * @param command the command line to run once this process is gone.
     */
    private static Process startWatcher(java.util.List<String> command)
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
            return watcher;
        }
        catch (Exception e)
        {
            Activator.logError("restart_edt: the relaunch watcher could not be started - " //$NON-NLS-1$
                + "EDT will close with nothing to bring it back", e); //$NON-NLS-1$
            return null;
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
