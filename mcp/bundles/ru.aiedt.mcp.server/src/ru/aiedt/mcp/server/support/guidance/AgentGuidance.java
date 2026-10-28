/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.guidance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.support.GateCheck;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.ExternalObjectWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.LaunchDebuggerTool;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * The routes of this build, how an agent reaches them, and what stands in the way at the moment it
 * reads them.
 * <p>
 * A route is a job - start a client, open an external object under debug, take a current
 * configuration file - not a tool name. Each route names every entry point that reaches the same
 * job, the conditions the call has to meet, and the preset gate each entry point passes. The
 * availability marks are computed when the document is read: they are a photograph of the current
 * registry and the current preset, never a promise about a later call. The call itself is the final
 * check and answers a refusal by name.
 * </p>
 * <p>
 * The gate checks are not retyped here. A facade publishes {@code gateChecksOf(operation)}, the same
 * list its {@code execute} runs, and this class executes that list through
 * {@link GateCheck#firstRefusal(List)}. A route therefore cannot describe a gate the call does not
 * have, which is what keeps a document written under one preset from teaching a refusal that no
 * longer exists after the preset changes.
 * </p>
 * <p>
 * Two answers about a name are different and stay different. A name hidden from {@code tools/list}
 * is callable, and is marked {@code hidden, callable}; only a name the disabled set carries is
 * marked off. The Canonical preset is the one that hides names without switching them off, and an
 * agent that reads the hidden state as off loses a working call.
 * </p>
 */
public final class AgentGuidance
{
    /**
     * The short text the handshake carries, kept independent of the current preset because
     * {@code server/discover} is cached by clients. It names the resource this class serves and
     * nothing that would go stale with a preference change.
     */
    public static final String BRIEF =
        "AI-EDT serves a running 1C:EDT with the target project open; there is no headless mode. " //$NON-NLS-1$
            + "The routes of this build, with the entry points and conditions each one has, are in " //$NON-NLS-1$
            + "the MCP resource aiedt://guidance (resources/read). The Canonical preset hides " //$NON-NLS-1$
            + "compatibility tool names from tools/list while they remain callable; a name absent " //$NON-NLS-1$
            + "from the list is not necessarily off. A refusal on one argument does not mean the " //$NON-NLS-1$
            + "whole tool or the environment is unavailable: read the refusal text and the facade " //$NON-NLS-1$
            + "help. Data of a running infobase is not served by this server."; //$NON-NLS-1$

    /** The phrase a boundary outside this server carries, and nothing else can carry it. */
    public static final String NOT_SERVED = "not served here"; //$NON-NLS-1$

    /** R1 entry point: the facade operation that starts a client without a debugger. */
    private static final Entry E1 = new Entry("E1", InfobaseAdminFacadeTool.NAME, //$NON-NLS-1$
        "start_client", InfobaseAdminFacadeTool.gateChecksOf("start_client")); //$NON-NLS-1$ //$NON-NLS-2$

    /** R1 entry point: the standalone client starter, hidden while Canonical is in force. */
    private static final Entry E2 = new Entry("E2", "start_client", null, List.of()); //$NON-NLS-1$ //$NON-NLS-2$

    /** R2 entry point: the debugger facade's launch action. */
    private static final Entry E3 = new Entry("E3", LaunchDebuggerTool.NAME, "launch", //$NON-NLS-1$ //$NON-NLS-2$
        LaunchDebuggerTool.gateChecksOf("launch")); //$NON-NLS-1$

    /** R2 entry point: the standalone debug launcher, hidden while Canonical is in force. */
    private static final Entry E4 = new Entry("E4", "debug_launch", null, List.of()); //$NON-NLS-1$ //$NON-NLS-2$

    /** R3 entry point: the workshop operation that imports a ready external object. */
    private static final Entry E5 = new Entry("E5", ExternalObjectWorkshopTool.NAME, //$NON-NLS-1$
        "import_external_object", //$NON-NLS-1$ //$NON-NLS-2$
        ExternalObjectWorkshopTool.gateChecksOf("import_external_object")); //$NON-NLS-1$

    /** R4 entry point: the query checker. */
    private static final Entry E6 = new Entry("E6", "validate_query", null, List.of()); //$NON-NLS-1$ //$NON-NLS-2$

    /** R5 entry point: the facade operation that reads the file event log. */
    private static final Entry E7 = new Entry("E7", InfobaseAdminFacadeTool.NAME, //$NON-NLS-1$
        "read_event_log", InfobaseAdminFacadeTool.gateChecksOf("read_event_log")); //$NON-NLS-1$ //$NON-NLS-2$

    /** R5 entry point: the standalone event-log reader. */
    private static final Entry E8 = new Entry("E8", "read_event_log", null, List.of()); //$NON-NLS-1$ //$NON-NLS-2$

    /** R6 entry point: the guarded .cf dump. */
    private static final Entry E9 = new Entry("E9", ConfigIoFacadeTool.NAME, //$NON-NLS-1$
        "export_database_configuration", //$NON-NLS-1$ //$NON-NLS-2$
        ConfigIoFacadeTool.gateChecksOf("export_database_configuration")); //$NON-NLS-1$

    /** R6 entry point: the facade's own .cf export. */
    private static final Entry E10 = new Entry("E10", ConfigIoFacadeTool.NAME, //$NON-NLS-1$
        "export_configuration_to_cf", //$NON-NLS-1$ //$NON-NLS-2$
        ConfigIoFacadeTool.gateChecksOf("export_configuration_to_cf")); //$NON-NLS-1$

    /** R6 entry point: the guarded .cfe dump. */
    private static final Entry E11 = new Entry("E11", ConfigIoFacadeTool.NAME, //$NON-NLS-1$
        "export_database_extension", //$NON-NLS-1$ //$NON-NLS-2$
        ConfigIoFacadeTool.gateChecksOf("export_database_extension")); //$NON-NLS-1$

    /** S1: the update step run through the infobase-admin facade. */
    private static final Entry S1 = new Entry("S1", InfobaseAdminFacadeTool.NAME, //$NON-NLS-1$
        "update_database", InfobaseAdminFacadeTool.gateChecksOf("update_database")); //$NON-NLS-1$ //$NON-NLS-2$

    /** S2: the update step run as a standalone tool. */
    private static final Entry S2 = new Entry("S2", "update_database", null, List.of()); //$NON-NLS-1$ //$NON-NLS-2$

    /** P1: the facade publishes the update switch of R1. */
    private static final ArgumentPlace P1 = new ArgumentPlace("P1", InfobaseAdminFacadeTool.NAME, //$NON-NLS-1$
        "updateBeforeLaunch"); //$NON-NLS-1$

    /** P2: the standalone client starter publishes the update switch of R1. */
    private static final ArgumentPlace P2 = new ArgumentPlace("P2", "start_client", //$NON-NLS-1$ //$NON-NLS-2$
        "updateBeforeLaunch"); //$NON-NLS-1$

    /** P3: the debugger facade publishes the update switch of R2. */
    private static final ArgumentPlace P3 = new ArgumentPlace("P3", LaunchDebuggerTool.NAME, //$NON-NLS-1$
        "updateBeforeLaunch"); //$NON-NLS-1$

    /** P4: the standalone debug launcher publishes the update switch of R2. */
    private static final ArgumentPlace P4 = new ArgumentPlace("P4", "debug_launch", //$NON-NLS-1$ //$NON-NLS-2$
        "updateBeforeLaunch"); //$NON-NLS-1$

    /** P5: the debugger facade publishes the dump switch of R2. */
    private static final ArgumentPlace P5 = new ArgumentPlace("P5", LaunchDebuggerTool.NAME, //$NON-NLS-1$
        "enableExternalObjectDump"); //$NON-NLS-1$

    /** P6: the standalone debug launcher publishes the dump switch of R2. */
    private static final ArgumentPlace P6 = new ArgumentPlace("P6", "debug_launch", //$NON-NLS-1$ //$NON-NLS-2$
        "enableExternalObjectDump"); //$NON-NLS-1$

    /** P7: the infobase-admin facade publishes the R1 startup string. */
    private static final ArgumentPlace P7 = new ArgumentPlace("P7", InfobaseAdminFacadeTool.NAME, //$NON-NLS-1$
        "startupOption"); //$NON-NLS-1$

    /** M1: a real update before a client starts, required by R1. */
    private static final Mode M1 = new Mode("M1", "updateBeforeLaunch", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "update_database", List.of(S1, S2), null, false, List.of(P1, P2)); //$NON-NLS-1$

    /** M2: a real update before a debug launch, default true and required by R2. */
    private static final Mode M2 = new Mode("M2", "updateBeforeLaunch", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "update_database", List.of(S1, S2), null, false, List.of(P3, P4)); //$NON-NLS-1$

    /**
     * M3: turning dump generation on changes the external-object project's settings. The launch
     * asks the {@code launch_debugger_writes} door before it does so, so a preset that blocks
     * writing refuses it while the launch itself stays available.
     */
    private static final Mode M3 = new Mode("M3", "enableExternalObjectDump", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        null, List.of(), "launch_debugger_writes", true, List.of(P5, P6)); //$NON-NLS-1$

    /** X1: data of a running infobase is outside this server. */
    private static final Boundary X1 = new Boundary("X1", //$NON-NLS-1$
        "data of a running infobase"); //$NON-NLS-1$

    /** X2: starting a ready file outside an EDT project is outside this server. */
    private static final Boundary X2 = new Boundary("X2", //$NON-NLS-1$
        "a direct launch of a ready .epf or .erf outside an EDT project"); //$NON-NLS-1$

    /** R1: a client without a debugger. */
    private static final Route R1 = new Route("R1", "Start a client without debugging", //$NON-NLS-1$ //$NON-NLS-2$
        List.of(E1, E2), List.of(), List.of(M1), List.of(), List.of(), null, //$NON-NLS-1$
        "A saved launch configuration is named by launchConfigurationName, or by projectName plus " //$NON-NLS-1$
            + "applicationId; a missing one is created and saved. startupOption carries the /C string " //$NON-NLS-1$
            + "in this launch's own copy, the saved configuration is not changed; /Execute is set by " //$NON-NLS-1$
            + "the environment from the configuration's external-object binding. A client already " //$NON-NLS-1$
            + "running answers alreadyRunning without override arguments and is refused with them; " //$NON-NLS-1$
            + "allowSecondSession=true starts a second client. updateBeforeLaunch defaults to false. " //$NON-NLS-1$
            + "Stop it with launch_debugger action=terminate."); //$NON-NLS-1$

    /** R2: an external object of the workspace opened in a client under debug. */
    private static final Route R2 = new Route("R2", "Open an external object of an EDT project in a client under debug", //$NON-NLS-1$ //$NON-NLS-2$
        List.of(E3, E4), List.of(), List.of(M2, M3), List.of(), List.of(), null, //$NON-NLS-1$
        "externalObjectName and externalObjectProject name an external-object project the workspace " //$NON-NLS-1$
            + "holds; a ready file goes in first through external_object_workshop " //$NON-NLS-1$
            + "operation=import_external_object. The launch configuration has to be a client " //$NON-NLS-1$
            + "configuration: an Attach configuration opens no client and is refused when an external " //$NON-NLS-1$
            + "object is named. With dump generation off the launch is refused when the environment's " //$NON-NLS-1$
            + "dump service answers; enableExternalObjectDump=true turns it on in the project's " //$NON-NLS-1$
            + "settings. updateBeforeLaunch defaults to true; an unnamed argument under a preset " //$NON-NLS-1$
            + "without update_database launches without updating and says " //$NON-NLS-1$
            + "databaseUpdate=SKIPPED_BY_PRESET, an explicit true is refused. A running session with " //$NON-NLS-1$
            + "override arguments is refused; stop it with action=terminate."); //$NON-NLS-1$

    /** R3: a received external object put into an EDT project, then opened by R2. */
    private static final Route R3 = new Route("R3", "Put a received .epf or .erf into an EDT project", //$NON-NLS-1$ //$NON-NLS-2$
        List.of(E5), List.of(), List.of(), List.of(), List.of(X2), "R2", //$NON-NLS-1$ //$NON-NLS-2$
        "targetProjectName has to be an existing V8ExternalObjectsNature container; inputPath is " //$NON-NLS-1$
            + "the .epf or .erf. applicationId picks the infobase whose Designer converts it, and " //$NON-NLS-1$
            + "several applications without it are refused; baseProjectName resolves the project " //$NON-NLS-1$
            + "when the container has no parent. An object already under that name is refused, and " //$NON-NLS-1$
            + "a new single object is operation=create."); //$NON-NLS-1$

    /** R4: a query checked before it runs. */
    private static final Route R4 = new Route("R4", "Check a 1C query before it runs", //$NON-NLS-1$ //$NON-NLS-2$
        List.of(E6), List.of(), List.of(), List.of(), List.of(), null, //$NON-NLS-1$
        "Call it before the query is executed against an infobase or written into code that " //$NON-NLS-1$
            + "executes it. dcsMode=true checks a data composition schema; describeResult is skipped " //$NON-NLS-1$
            + "when the check reports errors."); //$NON-NLS-1$

    /** R5: the file event log of an infobase, and the boundary to live data. */
    private static final Route R5 = new Route("R5", "Read data of a running infobase", //$NON-NLS-1$ //$NON-NLS-2$
        List.of(), List.of(), List.of(), List.of(new Target("event log", List.of(E7, E8))), //$NON-NLS-1$ //$NON-NLS-2$
        List.of(X1), null, //$NON-NLS-1$
        "This server has no route to the data of an infobase: get_variables and evaluate read the " //$NON-NLS-1$
            + "variables of the current debug frame, not tables. The file event log is read as files " //$NON-NLS-1$
            + "without the platform; a server infobase or a 1Cv8.lgd path is refused."); //$NON-NLS-1$

    /** R6: a current .cf or .cfe, after a real update succeeded. */
    private static final Route R6 = new Route("R6", "Get a current .cf or .cfe of an EDT project", //$NON-NLS-1$ //$NON-NLS-2$
        List.of(), List.of(S1, S2), List.of(), //$NON-NLS-1$
        List.of(new Target(".cf", List.of(E9, E10)), new Target(".cfe", List.of(E11))), //$NON-NLS-1$ //$NON-NLS-2$
        List.of(), null, //$NON-NLS-1$
        "The order matters: a real update (dryRun=false; dryRun=true only previews what the update " //$NON-NLS-1$
            + "would meet and does not count as the step), a successful outcome (a Pending answer is " //$NON-NLS-1$
            + "picked up by runKey; a refusal, an error or confirmationRequired stops the route), and " //$NON-NLS-1$
            + "only then the export, or it reads the infobase's previous configuration. " //$NON-NLS-1$
            + "export_database_configuration and export_database_extension refuse a busy outputPath " //$NON-NLS-1$
            + "and an infobase not in EQUAL; overwrite and allowOutOfSync are read by those two only. " //$NON-NLS-1$
            + "export_configuration_to_cf performs its own check before exporting, its findings and a " //$NON-NLS-1$
            + "failed check block the export unless skipValidation=true; diagnostics " //$NON-NLS-1$
            + "operation=validate_for_export is the fuller check and does not affect availability."); //$NON-NLS-1$

    private static final List<Route> ROUTES = List.of(R1, R2, R3, R4, R5, R6);

    private static final List<Entry> TARGET_ENTRIES =
        List.of(E1, E2, E3, E4, E5, E6, E7, E8, E9, E10, E11);

    private static final List<Entry> UPDATE_STEPS = List.of(S1, S2);

    private static final List<Mode> MODES = List.of(M1, M2, M3);

    private static final List<ArgumentPlace> ARGUMENT_PLACES =
        List.of(P1, P2, P3, P4, P5, P6, P7);

    private static final List<Boundary> BOUNDARIES = List.of(X1, X2);

    private AgentGuidance()
    {
        // static registry
    }

    /**
     * Builds the document {@code aiedt://guidance} returns, from the registry and the preset as they
     * stand now.
     *
     * @return the markdown document, never {@code null}
     */
    public static String document()
    {
        ToolProfile preset = ToolSettingsStore.getInstance().getActivePreset();
        StringBuilder out = new StringBuilder(8192);
        out.append("# AI-EDT agent guidance\n\n"); //$NON-NLS-1$
        out.append("Plugin version: ").append(McpServerMeta.PLUGIN_VERSION).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("Tool preset: ").append(preset.getDisplayName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("This server works against a running 1C:EDT with the target project open; there is " //$NON-NLS-1$
            + "no headless mode.\n\n"); //$NON-NLS-1$
        out.append("Availability reflects the moment of reading; the call itself is the final check - " //$NON-NLS-1$
            + "a gate answers with a named refusal.\n\n"); //$NON-NLS-1$
        out.append("A name hidden from tools/list but callable is marked hidden, callable; a name " //$NON-NLS-1$
            + "switched off is marked off. The two are different: a hidden name still answers a " //$NON-NLS-1$
            + "call.\n\n"); //$NON-NLS-1$
        out.append("## Routes\n\n"); //$NON-NLS-1$
        out.append("| Task | Entry points | Conditions | Availability |\n"); //$NON-NLS-1$
        out.append("|---|---|---|---|\n"); //$NON-NLS-1$
        for (Route route : ROUTES)
        {
            out.append("| ").append(route.id()).append(' ').append(route.task()).append(" | ") //$NON-NLS-1$
                .append(route.entryPointsText()).append(" | ") //$NON-NLS-1$
                .append(route.conditions()).append(" | ") //$NON-NLS-1$
                .append(route.availabilityText()).append(" |\n"); //$NON-NLS-1$
        }
        out.append("\n## Hidden names\n\n"); //$NON-NLS-1$
        out.append("A name in tools/list is an entry point, not a list of everything callable. The " //$NON-NLS-1$
            + "Canonical preset hides the standalone names the facades cover while every one of " //$NON-NLS-1$
            + "them still answers a call; a preset that disables a name removes it from both sides. " //$NON-NLS-1$
            + "A name absent from tools/list is therefore not evidence that the route is off.\n"); //$NON-NLS-1$
        return out.toString();
    }

    /**
     * The route registry, in reading order.
     *
     * @return an unmodifiable list of routes, never {@code null}
     */
    public static List<Route> routes()
    {
        return ROUTES;
    }

    /**
     * The entry points of the jobs, E1 through E11.
     *
     * @return an unmodifiable list of entry points, never {@code null}
     */
    public static List<Entry> targetEntries()
    {
        return TARGET_ENTRIES;
    }

    /**
     * The entry points of the update step R6 requires, S1 and S2.
     *
     * @return an unmodifiable list of entry points, never {@code null}
     */
    public static List<Entry> updateStepEntries()
    {
        return UPDATE_STEPS;
    }

    /**
     * The argument modes that change a route's conditions, M1 through M3.
     *
     * @return an unmodifiable list of modes, never {@code null}
     */
    public static List<Mode> modes()
    {
        return MODES;
    }

    /**
     * The tool schemas that publish the modes' arguments, P1 through P7.
     *
     * @return an unmodifiable list of publication places, never {@code null}
     */
    public static List<ArgumentPlace> argumentPlaces()
    {
        return ARGUMENT_PLACES;
    }

    /**
     * The boundaries outside this server, X1 and X2.
     *
     * @return an unmodifiable list of boundaries, never {@code null}
     */
    public static List<Boundary> boundaries()
    {
        return BOUNDARIES;
    }

    /**
     * Finds a route by its identifier.
     *
     * @param id the identifier, for example {@code R6}; may be {@code null}
     * @return the route, or {@code null} when no route carries that identifier
     */
    public static Route route(String id)
    {
        for (Route route : ROUTES)
        {
            if (route.id().equals(id))
            {
                return route;
            }
        }
        return null;
    }

    /**
     * Finds an entry point by its identifier, whether it belongs to a route or to the update step.
     *
     * @param id the identifier, for example {@code E5} or {@code S2}; may be {@code null}
     * @return the entry point, or {@code null} when nothing carries that identifier
     */
    public static Entry entry(String id)
    {
        for (Entry entry : TARGET_ENTRIES)
        {
            if (entry.id().equals(id))
            {
                return entry;
            }
        }
        for (Entry entry : UPDATE_STEPS)
        {
            if (entry.id().equals(id))
            {
                return entry;
            }
        }
        return null;
    }

    /**
     * How a route, an entry point or a mode stands at the moment of reading.
     */
    public enum State
    {
        /** Visible in {@code tools/list} and callable, or a mode that may be used. */
        AVAILABLE,

        /** Callable but hidden from {@code tools/list}, which is not the same as off. */
        HIDDEN,

        /** Refused by the disabled set or by one of the gate checks. */
        OFF,

        /** Outside this server. */
        NOT_SERVED,

        /** Available, and a write the caller should put to the user first. */
        CHANGES_PROJECT
    }

    /**
     * One way to reach a job: a tool, an operation inside a facade, and the gate checks the facade
     * runs before it.
     */
    public static final class Entry
    {
        private final String id;

        private final String tool;

        private final String operation;

        private final List<GateCheck> checks;

        private Entry(String id, String tool, String operation, List<GateCheck> checks)
        {
            this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
            this.tool = Objects.requireNonNull(tool, "tool"); //$NON-NLS-1$
            this.operation = operation;
            this.checks = List.copyOf(checks);
        }

        /**
         * @return the identifier, for example {@code E1}
         */
        public String id()
        {
            return id;
        }

        /**
         * @return the registered tool name a caller uses
         */
        public String tool()
        {
            return tool;
        }

        /**
         * @return the operation or action inside a facade, or {@code null} for a standalone tool
         */
        public String operation()
        {
            return operation;
        }

        /**
         * @return the gate checks the facade runs, in its own order; empty for a standalone tool
         */
        public List<GateCheck> checks()
        {
            return checks;
        }

        /**
         * @return the callable name as an agent writes it, for example
         *         {@code infobase_admin operation=start_client}
         */
        public String callName()
        {
            return operation == null ? tool : tool + " operation=" + operation; //$NON-NLS-1$
        }

        /**
         * Answers from the registry and the disabled set as they stand now.
         *
         * @return the state of this entry point at the moment of reading
         */
        public State state()
        {
            IMcpTool registered = McpToolCatalog.getInstance().getTool(tool);
            if (registered == null)
            {
                return State.OFF;
            }
            if (!McpToolCatalog.getInstance().isToolEnabled(tool))
            {
                return State.OFF;
            }
            for (GateCheck check : checks)
            {
                if (check.refusal() != null)
                {
                    return State.OFF;
                }
            }
            return isListed(tool) ? State.AVAILABLE : State.HIDDEN;
        }

        /**
         * @return whether a call through this entry point is accepted now; hidden counts as
         *         callable
         */
        public boolean callable()
        {
            State state = state();
            return state == State.AVAILABLE || state == State.HIDDEN;
        }

        /**
         * The mark the document shows for this entry point.
         *
         * @return {@code available via ...}, {@code hidden, callable via ...} or {@code off ...}
         */
        public String label()
        {
            switch (state())
            {
                case AVAILABLE:
                    return "available via " + callName(); //$NON-NLS-1$
                case HIDDEN:
                    return "hidden, callable via " + callName(); //$NON-NLS-1$
                default:
                    return refusalText();
            }
        }

        private String refusalText()
        {
            if (McpToolCatalog.getInstance().getTool(tool) == null)
            {
                return "off: " + tool + " is not registered"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!McpToolCatalog.getInstance().isToolEnabled(tool))
            {
                return "off by preset " + tool; //$NON-NLS-1$
            }
            for (GateCheck check : checks)
            {
                if (check.refusal() != null)
                {
                    return "off: " + check.name() + " disabled"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            return "off"; //$NON-NLS-1$
        }
    }

    /**
     * One result a route produces, with its own entry points. R6's {@code .cf} and {@code .cfe} are
     * two targets because availability is decided for each separately.
     */
    public static final class Target
    {
        private final String name;

        private final List<Entry> entries;

        private Target(String name, List<Entry> entries)
        {
            this.name = Objects.requireNonNull(name, "name"); //$NON-NLS-1$
            this.entries = List.copyOf(entries);
        }

        /**
         * @return the target name shown to the reader, for example {@code .cfe}
         */
        public String name()
        {
            return name;
        }

        /**
         * @return the entry points that produce this target
         */
        public List<Entry> entries()
        {
            return entries;
        }

        /**
         * @return whether at least one entry point for this target is callable now
         */
        public boolean available()
        {
            for (Entry entry : entries)
            {
                if (entry.callable())
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return the target name and the mark of every entry point in it
         */
        public String label()
        {
            List<String> labels = new ArrayList<>();
            for (Entry entry : entries)
            {
                labels.add(entry.label());
            }
            return name + ": " + String.join("; ", labels); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A route argument that changes what the call does or requires. Its availability is separate
     * from the route's own: the launch may be available while the update mode is closed.
     */
    public static final class Mode
    {
        private final String id;

        private final String argument;

        private final String value;

        private final String requiredName;

        private final List<Entry> requiredSteps;

        private final String writeDoor;

        private final boolean changesProject;

        private final List<ArgumentPlace> places;

        private Mode(String id, String argument, String value, String requiredName,
            List<Entry> requiredSteps, String writeDoor, boolean changesProject,
            List<ArgumentPlace> places)
        {
            this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
            this.argument = Objects.requireNonNull(argument, "argument"); //$NON-NLS-1$
            this.value = Objects.requireNonNull(value, "value"); //$NON-NLS-1$
            this.requiredName = requiredName;
            this.requiredSteps = List.copyOf(requiredSteps);
            this.writeDoor = writeDoor;
            this.changesProject = changesProject;
            this.places = List.copyOf(places);
        }

        /**
         * @return the identifier, for example {@code M3}
         */
        public String id()
        {
            return id;
        }

        /**
         * @return the argument the mode sets
         */
        public String argument()
        {
            return argument;
        }

        /**
         * @return the value that switches the mode on
         */
        public String value()
        {
            return value;
        }

        /**
         * @return the required previous step named in a refusal, or {@code null} when the mode
         *         requires none
         */
        public String requiredName()
        {
            return requiredName;
        }

        /**
         * @return the alternative entry points of the required step; empty when none is required
         */
        public List<Entry> requiredSteps()
        {
            return requiredSteps;
        }

        /**
         * @return the write door the mode asks before it runs, or {@code null} when it has none
         */
        public String writeDoor()
        {
            return writeDoor;
        }

        /**
         * @return whether the mode writes project settings and should be put to the user first
         */
        public boolean changesProject()
        {
            return changesProject;
        }

        /**
         * @return the tool schemas that publish this mode's argument
         */
        public List<ArgumentPlace> places()
        {
            return places;
        }

        /**
         * Answers from the preset, the registry and the required step as they stand now.
         *
         * @return the state of this mode at the moment of reading
         */
        public State state()
        {
            if (writeDoor != null && ToolGate.gateWriteDoor(writeDoor) != null)
            {
                return State.OFF;
            }
            if (!requiredSteps.isEmpty() && !anyRequiredStepCallable())
            {
                return State.OFF;
            }
            return changesProject ? State.CHANGES_PROJECT : State.AVAILABLE;
        }

        private boolean anyRequiredStepCallable()
        {
            for (Entry entry : requiredSteps)
            {
                if (entry.callable())
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * The mark the document shows for this mode.
         *
         * @return the argument, its state, and - when it writes - what to put to the user
         */
        public String label()
        {
            String head = argument + "=" + value + ": "; //$NON-NLS-1$ //$NON-NLS-2$
            switch (state())
            {
                case OFF:
                    if (writeDoor != null)
                    {
                        return head + "off: " + writeDoor + " disabled" //$NON-NLS-1$ //$NON-NLS-2$
                            + changesSuffix();
                    }
                    return head + "off: " + requiredName + " disabled"; //$NON-NLS-1$ //$NON-NLS-2$
                case CHANGES_PROJECT:
                    return head + "changes the project; ask the user"; //$NON-NLS-1$
                default:
                    return head + "available"; //$NON-NLS-1$
            }
        }

        private String changesSuffix()
        {
            return changesProject ? "; true changes the project; ask the user" : ""; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A tool schema that publishes a mode's argument. The completeness test walks these, so a mode
     * whose argument is advertised nowhere cannot enter the registry unnoticed.
     */
    public static final class ArgumentPlace
    {
        private final String id;

        private final String tool;

        private final String argument;

        private ArgumentPlace(String id, String tool, String argument)
        {
            this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
            this.tool = Objects.requireNonNull(tool, "tool"); //$NON-NLS-1$
            this.argument = Objects.requireNonNull(argument, "argument"); //$NON-NLS-1$
        }

        /**
         * @return the identifier, for example {@code P1}
         */
        public String id()
        {
            return id;
        }

        /**
         * @return the registered tool whose schema publishes the argument
         */
        public String tool()
        {
            return tool;
        }

        /**
         * @return the argument name
         */
        public String argument()
        {
            return argument;
        }

        /**
         * Tells whether the registered tool's schema declares the argument now.
         *
         * @return {@code true} when the tool is registered and its schema has the property
         */
        public boolean published()
        {
            IMcpTool registered = McpToolCatalog.getInstance().getTool(tool);
            if (registered == null)
            {
                return false;
            }
            JsonElement schema = JsonParser.parseString(registered.getInputSchema());
            if (!schema.isJsonObject())
            {
                return false;
            }
            JsonObject properties = schema.getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
            return properties != null && properties.has(argument);
        }
    }

    /**
     * A place outside this server: the route names it so an agent does not read the absence as a
     * failed call, and no registry lookup is made for it.
     */
    public static final class Boundary
    {
        private final String id;

        private final String description;

        private Boundary(String id, String description)
        {
            this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
            this.description = Objects.requireNonNull(description, "description"); //$NON-NLS-1$
        }

        /**
         * @return the identifier, for example {@code X1}
         */
        public String id()
        {
            return id;
        }

        /**
         * @return what lies outside this server
         */
        public String description()
        {
            return description;
        }

        /**
         * @return the permanent boundary mark
         */
        public String state()
        {
            return NOT_SERVED;
        }

        /**
         * @return the identifier, the description and the permanent boundary mark
         */
        public String label()
        {
            return id + " " + description + ": " + NOT_SERVED; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * One job: its entry points, the previous step it may require, the modes that change it, the
     * targets it produces, the boundaries outside it, and the text an agent reads about it.
     */
    public static final class Route
    {
        private final String id;

        private final String task;

        private final List<Entry> entries;

        private final List<Entry> requiredSteps;

        private final List<Mode> modes;

        private final List<Target> targets;

        private final List<Boundary> boundaries;

        private final String nextRouteId;

        private final String conditions;

        private Route(String id, String task, List<Entry> entries, List<Entry> requiredSteps,
            List<Mode> modes, List<Target> targets, List<Boundary> boundaries, String nextRouteId,
            String conditions)
        {
            this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
            this.task = Objects.requireNonNull(task, "task"); //$NON-NLS-1$
            this.entries = List.copyOf(entries);
            this.requiredSteps = List.copyOf(requiredSteps);
            this.modes = List.copyOf(modes);
            this.targets = List.copyOf(targets);
            this.boundaries = List.copyOf(boundaries);
            this.nextRouteId = nextRouteId;
            this.conditions = Objects.requireNonNull(conditions, "conditions"); //$NON-NLS-1$
        }

        /**
         * @return the identifier, for example {@code R1}
         */
        public String id()
        {
            return id;
        }

        /**
         * @return the job this route serves
         */
        public String task()
        {
            return task;
        }

        /**
         * @return the direct entry points of the route; empty when the route's entry points belong
         *         to its targets
         */
        public List<Entry> entries()
        {
            return entries;
        }

        /**
         * @return the alternative entry points of the previous step the route requires, or an empty
         *         list when it requires none
         */
        public List<Entry> requiredSteps()
        {
            return requiredSteps;
        }

        /**
         * @return the modes that change the route's conditions
         */
        public List<Mode> modes()
        {
            return modes;
        }

        /**
         * @return the targets the route produces, each with its own entry points
         */
        public List<Target> targets()
        {
            return targets;
        }

        /**
         * @return the boundaries outside this server the route names
         */
        public List<Boundary> boundaries()
        {
            return boundaries;
        }

        /**
         * @return the identifier of the route this one hands over to, or {@code null} when it hands
         *         over to none
         */
        public String nextRouteId()
        {
            return nextRouteId;
        }

        /**
         * @return the conditions an agent has to meet on this route
         */
        public String conditions()
        {
            return conditions;
        }

        /**
         * @return the entry points as the document names them
         */
        public String entryPoints()
        {
            return entryPointsText();
        }

        /**
         * The availability column of this route in the document, recomputed from the registry and
         * the preset as they stand now.
         *
         * @return the marks of the entries, the required step, the targets, the modes, the
         *         boundaries and the next route
         */
        public String availability()
        {
            return availabilityText();
        }

        /**
         * @return whether at least one target entry point is callable now, and the required step -
         *         when there is one - is callable too
         */
        public boolean available()
        {
            if (!requiredSteps.isEmpty() && !anyCallable(requiredSteps))
            {
                return false;
            }
            if (!entries.isEmpty())
            {
                return anyCallable(entries);
            }
            for (Target target : targets)
            {
                if (target.available())
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * @param name the tool name an entry point uses
         * @return the entry point carrying that tool name, or {@code null} when none does
         */
        public Entry entryForTool(String name)
        {
            for (Entry entry : allEntries())
            {
                if (entry.tool().equals(name))
                {
                    return entry;
                }
            }
            return null;
        }

        /**
         * @param name the target name, for example {@code .cfe}
         * @return the target, or {@code null} when the route has none by that name
         */
        public Target target(String name)
        {
            for (Target target : targets)
            {
                if (target.name().equals(name))
                {
                    return target;
                }
            }
            return null;
        }

        private List<Entry> allEntries()
        {
            List<Entry> all = new ArrayList<>(entries);
            for (Target target : targets)
            {
                all.addAll(target.entries());
            }
            return all;
        }

        private static boolean anyCallable(List<Entry> candidates)
        {
            for (Entry entry : candidates)
            {
                if (entry.callable())
                {
                    return true;
                }
            }
            return false;
        }

        private String entryPointsText()
        {
            List<String> parts = new ArrayList<>();
            for (Entry entry : entries)
            {
                parts.add(entry.callName());
            }
            for (Target target : targets)
            {
                List<String> calls = new ArrayList<>();
                for (Entry entry : target.entries())
                {
                    calls.add(entry.callName());
                }
                parts.add(target.name() + " = " + String.join(" / ", calls)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            for (Boundary boundary : boundaries)
            {
                parts.add(boundary.description());
            }
            return String.join("; ", parts); //$NON-NLS-1$
        }

        private String availabilityText()
        {
            List<String> parts = new ArrayList<>();
            for (Entry entry : entries)
            {
                parts.add(entry.label());
            }
            if (!requiredSteps.isEmpty())
            {
                List<String> labels = new ArrayList<>();
                for (Entry entry : requiredSteps)
                {
                    labels.add(entry.label());
                }
                parts.add("requires " + requiredStepName() + " - " + String.join("; ", labels)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            for (Target target : targets)
            {
                parts.add(target.label());
            }
            for (Mode mode : modes)
            {
                parts.add(mode.label());
            }
            for (Boundary boundary : boundaries)
            {
                parts.add(boundary.label());
            }
            if (nextRouteId != null)
            {
                Route next = route(nextRouteId);
                parts.add("next step: " + nextRouteId + " " //$NON-NLS-1$ //$NON-NLS-2$
                    + (next != null && next.available() ? "available" : "off")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return String.join("; ", parts); //$NON-NLS-1$
        }

        private String requiredStepName()
        {
            if (!requiredSteps.isEmpty())
            {
                for (Mode mode : MODES)
                {
                    if (mode.requiredSteps().contains(requiredSteps.get(0)))
                    {
                        return mode.requiredName();
                    }
                }
            }
            return "the required step"; //$NON-NLS-1$
        }
    }

    private static boolean isListed(String toolName)
    {
        for (IMcpTool listed : McpToolCatalog.getInstance().getEnabledTools())
        {
            if (listed.getName().equals(toolName))
            {
                return true;
            }
        }
        return false;
    }
}
