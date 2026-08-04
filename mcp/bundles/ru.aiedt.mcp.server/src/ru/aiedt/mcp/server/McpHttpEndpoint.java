/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.eclipse.jface.preference.IPreferenceStore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import ru.aiedt.mcp.server.settings.McpAuth;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.McpServerMeta;
import ru.aiedt.mcp.server.wire.McpRequestRouter;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.AttributeAdder;
import ru.aiedt.mcp.server.toolkit.ops.AiContextTool;
import ru.aiedt.mcp.server.toolkit.ops.AuditRoleRightsTool;
import ru.aiedt.mcp.server.toolkit.ops.BslCodeReviewTool;
import ru.aiedt.mcp.server.toolkit.ops.ProjectCleaner;
import ru.aiedt.mcp.server.toolkit.ops.CodeSearchTool;
import ru.aiedt.mcp.server.toolkit.ops.CodeTemplateTool;
import ru.aiedt.mcp.server.toolkit.ops.CompareConfigurationsTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseCreator;
import ru.aiedt.mcp.server.toolkit.ops.LaunchConfigCreator;
import ru.aiedt.mcp.server.toolkit.ops.ProjectCreator;
import ru.aiedt.mcp.server.toolkit.ops.DcsSearchTool;
import ru.aiedt.mcp.server.toolkit.ops.DcsWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.DebugSessionStarter;
import ru.aiedt.mcp.server.toolkit.ops.DebugStateReader;
import ru.aiedt.mcp.server.toolkit.ops.FindDeadCodeTool;
import ru.aiedt.mcp.server.toolkit.ops.YaxunitDebugRunner;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseRemover;
import ru.aiedt.mcp.server.toolkit.ops.MetadataObjectDeleter;
import ru.aiedt.mcp.server.toolkit.ops.ProjectRemover;
import ru.aiedt.mcp.server.toolkit.ops.DependencyGraphTool;
import ru.aiedt.mcp.server.toolkit.ops.DetectQueryAntiPatternsTool;
import ru.aiedt.mcp.server.toolkit.ops.DiffModuleTool;
import ru.aiedt.mcp.server.toolkit.ops.EditFormTool;
import ru.aiedt.mcp.server.toolkit.ops.EditMetadataTool;
import ru.aiedt.mcp.server.toolkit.ops.ExpressionEvaluator;
import ru.aiedt.mcp.server.toolkit.ops.CommonPictureExporter;
import ru.aiedt.mcp.server.toolkit.ops.ConfigurationXmlExporter;
import ru.aiedt.mcp.server.toolkit.ops.ExportExtensionTool;
import ru.aiedt.mcp.server.toolkit.ops.ExportObjectTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionDiffTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionLifecycleTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.ExternalDataSourceWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.ExternalObjectWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator;
import ru.aiedt.mcp.server.toolkit.ops.OutgoingStructuresReader;
import ru.aiedt.mcp.server.toolkit.ops.FindRlsViolationsTool;
import ru.aiedt.mcp.server.toolkit.ops.GenerateEventHandlersTool;
import ru.aiedt.mcp.server.toolkit.ops.GenerateHealthSnapshotTool;
import ru.aiedt.mcp.server.toolkit.ops.ApplicationsReader;
import ru.aiedt.mcp.server.toolkit.ops.BookmarksReader;
import ru.aiedt.mcp.server.toolkit.ops.CheckDocReader;
import ru.aiedt.mcp.server.toolkit.ops.GetCommandInterfaceTool;
import ru.aiedt.mcp.server.toolkit.ops.ConfigurationInfoReader;
import ru.aiedt.mcp.server.toolkit.ops.ContentAssistReader;
import ru.aiedt.mcp.server.toolkit.ops.EdtVersionReader;
import ru.aiedt.mcp.server.toolkit.ops.FormScreenshotGrabber;
import ru.aiedt.mcp.server.toolkit.ops.GetFormStructureTool;
import ru.aiedt.mcp.server.toolkit.ops.MetadataDetailsReader;
import ru.aiedt.mcp.server.toolkit.ops.MetadataObjectsReader;
import ru.aiedt.mcp.server.toolkit.ops.CallHierarchyReader;
import ru.aiedt.mcp.server.toolkit.ops.ModuleOutlineReader;
import ru.aiedt.mcp.server.toolkit.ops.GetObjectHelpTool;
import ru.aiedt.mcp.server.toolkit.ops.TaggedObjectsReader;
import ru.aiedt.mcp.server.toolkit.ops.PlatformDocReader;
import ru.aiedt.mcp.server.toolkit.ops.ProblemSummaryReader;
import ru.aiedt.mcp.server.toolkit.ops.ProfilingResultsReader;
import ru.aiedt.mcp.server.toolkit.ops.ProjectProblemsReader;
import ru.aiedt.mcp.server.toolkit.ops.GetSubsystemsTool;
import ru.aiedt.mcp.server.toolkit.ops.SymbolInfoReader;
import ru.aiedt.mcp.server.toolkit.ops.McpHistoryReader;
import ru.aiedt.mcp.server.toolkit.ops.SelfStatusTool;
import ru.aiedt.mcp.server.toolkit.ops.SelfUpkeepTool;
import ru.aiedt.mcp.server.toolkit.ops.TagsReader;
import ru.aiedt.mcp.server.toolkit.ops.TasksReader;
import ru.aiedt.mcp.server.toolkit.ops.DebugVariablesReader;
import ru.aiedt.mcp.server.toolkit.ops.DefinitionNavigator;
import ru.aiedt.mcp.server.toolkit.ops.ImpactAnalysisTool;
import ru.aiedt.mcp.server.toolkit.ops.ConfigurationXmlImporter;
import ru.aiedt.mcp.server.toolkit.ops.InstallExtensionTool;
import ru.aiedt.mcp.server.toolkit.ops.LaunchDebuggerTool;
import ru.aiedt.mcp.server.toolkit.ops.BreakpointsLister;
import ru.aiedt.mcp.server.toolkit.ops.LaunchConfigsLister;
import ru.aiedt.mcp.server.toolkit.ops.ListExtensionsTool;
import ru.aiedt.mcp.server.toolkit.ops.ListInterceptorsTool;
import ru.aiedt.mcp.server.toolkit.ops.ModulesLister;
import ru.aiedt.mcp.server.toolkit.ops.ProjectsLister;
import ru.aiedt.mcp.server.toolkit.ops.MxlWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.ObjectSummaryTool;
import ru.aiedt.mcp.server.toolkit.ops.ProjectMetricsTool;
import ru.aiedt.mcp.server.toolkit.ops.MethodSourceReader;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceReader;
import ru.aiedt.mcp.server.toolkit.ops.BreakpointRemover;
import ru.aiedt.mcp.server.toolkit.ops.MetadataObjectRenamer;
import ru.aiedt.mcp.server.toolkit.ops.RestartEdtTool;
import ru.aiedt.mcp.server.toolkit.ops.DebugResumer;
import ru.aiedt.mcp.server.toolkit.ops.DiskResynchronizer;
import ru.aiedt.mcp.server.toolkit.ops.ObjectsRevalidator;
import ru.aiedt.mcp.server.toolkit.ops.RunToLineTool;
import ru.aiedt.mcp.server.toolkit.ops.YaxunitTestRunner;
import ru.aiedt.mcp.server.toolkit.ops.CodeTextSearcher;
import ru.aiedt.mcp.server.toolkit.ops.SemanticMetadataSearchTool;
import ru.aiedt.mcp.server.toolkit.ops.SensitiveDataScanTool;
import ru.aiedt.mcp.server.toolkit.ops.BreakpointSetter;
import ru.aiedt.mcp.server.toolkit.ops.SetExceptionBreakpointTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseCredentialsWriter;
import ru.aiedt.mcp.server.toolkit.ops.DebugVariableWriter;
import ru.aiedt.mcp.server.toolkit.ops.ProfilingStarter;
import ru.aiedt.mcp.server.toolkit.ops.DebugStepper;
import ru.aiedt.mcp.server.toolkit.ops.SyncControlTool;
import ru.aiedt.mcp.server.toolkit.ops.LaunchTerminator;
import ru.aiedt.mcp.server.toolkit.ops.UninstallExtensionTool;
import ru.aiedt.mcp.server.toolkit.ops.DatabaseUpdater;
import ru.aiedt.mcp.server.toolkit.ops.ValidateForExportTool;
import ru.aiedt.mcp.server.toolkit.ops.QueryValidator;
import ru.aiedt.mcp.server.toolkit.ops.VanessaTool;
import ru.aiedt.mcp.server.toolkit.ops.SuspendWaiter;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;
import ru.aiedt.mcp.server.toolkit.ops.XdtoWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.YaxunitTestsTool;
import ru.aiedt.mcp.server.toolkit.ops.DiagnosticsFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.ProjectAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.InsightsFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.SecurityAuditFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.WorkspaceMarksFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.DocsLookupFacadeTool;
import ru.aiedt.mcp.server.support.HeavyTools;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.WorkspacePhase;

/**
 * The HTTP endpoint agents connect to, and everything around the protocol layer that is not protocol:
 * sockets, threads, CORS, bearer authentication, SSE framing, and the machinery that lets a user
 * answer a call the agent is still waiting on.
 * <p>
 * There is one of these, created by the {@link Activator} and reachable through it. It can be started
 * and stopped repeatedly - the preference page and the status bar both do so - and starting it while
 * it runs restarts it.
 * </p>
 * <p>
 * A word on the two thread pools. Requests are served by a small fixed pool, and a request is refused
 * with a plain 503 well before that pool's queue could fill: a queue that overflows makes the JDK's
 * server reset the connection, and a client that sees a broken pipe has no idea it should come back
 * later, while a client that sees a 503 does. The open-ended event streams from {@code GET /mcp} get a
 * pool of their own, because a handful of them would otherwise sit in the request pool forever and
 * starve everything else.
 * </p>
 */
public class McpHttpEndpoint
{
    private static final String CONTEXT_MCP = "/mcp"; //$NON-NLS-1$

    private static final String CONTEXT_HEALTH = "/health"; //$NON-NLS-1$

    private static final String METHOD_GET = "GET"; //$NON-NLS-1$

    private static final String METHOD_POST = "POST"; //$NON-NLS-1$

    private static final String METHOD_DELETE = "DELETE"; //$NON-NLS-1$

    private static final String METHOD_OPTIONS = "OPTIONS"; //$NON-NLS-1$

    private static final String HEADER_ORIGIN = "Origin"; //$NON-NLS-1$

    private static final String HEADER_ACCEPT = "Accept"; //$NON-NLS-1$

    private static final String HEADER_AUTHORIZATION = "Authorization"; //$NON-NLS-1$

    private static final String HEADER_CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$

    private static final String HEADER_CACHE_CONTROL = "Cache-Control"; //$NON-NLS-1$

    private static final String HEADER_CONNECTION = "Connection"; //$NON-NLS-1$

    private static final String HEADER_RETRY_AFTER = "Retry-After"; //$NON-NLS-1$

    private static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate"; //$NON-NLS-1$

    private static final String HEADER_ALLOW_ORIGIN = "Access-Control-Allow-Origin"; //$NON-NLS-1$

    private static final String HEADER_ALLOW_METHODS = "Access-Control-Allow-Methods"; //$NON-NLS-1$

    private static final String HEADER_ALLOW_HEADERS = "Access-Control-Allow-Headers"; //$NON-NLS-1$

    private static final String ALLOWED_METHODS = "GET, POST, DELETE, OPTIONS"; //$NON-NLS-1$

    private static final String ALLOWED_HEADERS = "Content-Type, Accept, Authorization"; //$NON-NLS-1$

    private static final String MIME_JSON = "application/json"; //$NON-NLS-1$

    private static final String MIME_EVENT_STREAM = "text/event-stream"; //$NON-NLS-1$

    private static final String NO_CACHE = "no-cache"; //$NON-NLS-1$

    private static final String KEEP_ALIVE = "keep-alive"; //$NON-NLS-1$

    private static final String BEARER_REALM = "Bearer realm=\"mcp\""; //$NON-NLS-1$

    private static final String RETRY_AFTER_SECONDS = "2"; //$NON-NLS-1$

    /** A page served from a local file sends this as its origin, spelled out. */
    private static final String ORIGIN_FILE_PAGE = "null"; //$NON-NLS-1$

    private static final String[] ALLOWED_ORIGIN_PREFIXES = {"http://localhost", //$NON-NLS-1$
        "http://127.0.0.1", //$NON-NLS-1$
        "https://localhost", //$NON-NLS-1$
        "https://127.0.0.1", //$NON-NLS-1$
        "file://", //$NON-NLS-1$
        "vscode-webview://"}; //$NON-NLS-1$

    private static final int HTTP_OK = 200;

    private static final int HTTP_ACCEPTED = 202;

    private static final int HTTP_NO_CONTENT = 204;

    private static final int HTTP_UNAUTHORIZED = 401;

    private static final int HTTP_FORBIDDEN = 403;

    private static final int HTTP_METHOD_NOT_ALLOWED = 405;

    private static final int HTTP_INTERNAL_ERROR = 500;

    private static final int HTTP_UNAVAILABLE = 503;

    /** Answering with no body at all: the JDK sends the headers and nothing else. */
    private static final int NO_BODY = -1;

    /** A zero response length is how the JDK's server is told to stream without a length. */
    private static final int UNBOUNDED_BODY = 0;

    /**
     * A tool call holds its request thread for the tool's whole run (it blocks relaying the result),
     * so a handful of long tools must not consume every thread and leave {@code /health} and quick
     * tools queued behind them. The pool is sized for headroom; the genuinely expensive work is bounded
     * separately by the heavy-tool limiter, and idle threads are reclaimed (core-thread timeout).
     */
    private static final int REQUEST_POOL_SIZE = 24;

    /** A safety net for memory, not a queue anyone should reach: admission control bites first. */
    private static final int REQUEST_QUEUE_CAPACITY = 200;

    /** Beyond this many requests in flight, callers are turned away with a retryable 503. */
    private static final int ADMISSION_LIMIT = 50;

    private static final int SSE_POOL_SIZE = 10;

    private static final long POOL_KEEP_ALIVE_SECONDS = 60L;

    private static final int STOP_GRACE_SECONDS = 1;

    private static final long HEARTBEAT_INTERVAL_MS = 5000L;

    private static final long TOOL_POLL_INTERVAL_MS = 100L;

    private static final long MILLIS_PER_SECOND = 1000L;

    private static final int LOG_PREFIX_LIMIT = 200;

    private static final String TOOL_EXECUTOR_THREAD = "MCP-Tool-Executor"; //$NON-NLS-1$

    private static final String SSE_THREAD_PREFIX = "MCP-SSE-"; //$NON-NLS-1$

    private static final String UNKNOWN_TOOL = "unknown"; //$NON-NLS-1$

    private static final String SSE_HEARTBEAT = ": keep-alive\n\n"; //$NON-NLS-1$

    private static final String MSG_OVERLOADED = "Too many tools running, try again shortly"; //$NON-NLS-1$

    private static final String MSG_HEAVY_BUSY =
        "A heavy tool is already running at the concurrency limit; retry shortly"; //$NON-NLS-1$

    private static final String MSG_SSE_OVERLOADED = "Server overloaded"; //$NON-NLS-1$

    private static final String MSG_SHUTTING_DOWN = "The endpoint is stopping and takes no new calls"; //$NON-NLS-1$

    private static final String MSG_INVALID_ORIGIN = "Invalid Origin"; //$NON-NLS-1$

    private static final String MSG_UNAUTHORIZED = "Unauthorized"; //$NON-NLS-1$

    private static final String MSG_METHOD_NOT_ALLOWED = "Method not allowed"; //$NON-NLS-1$

    private static final String MSG_INTERNAL_ERROR = "Internal server error"; //$NON-NLS-1$

    private static final String PARAM_NAME = "name"; //$NON-NLS-1$

    private static final String PARAM_PARAMS = "params"; //$NON-NLS-1$

    private static final String PARAM_ID = "id"; //$NON-NLS-1$

    /**
     * Masks the values a request must never leave in the log. {@code set_infobase_credentials} carries
     * a real infobase password in its arguments, and the platform log is a plain file on disk. The
     * value pattern steps over escaped characters so that a password containing a quote does not let
     * the rest of the document through unmasked.
     */
    private static final Pattern SECRETS =
        Pattern.compile("(\"(?:password|additionalParameters)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    private static final String SECRETS_MASK = "$1\"***\""; //$NON-NLS-1$

    /**
     * One server per bound address. Loopback binds both families (127.0.0.1 and ::1) so a client that
     * resolves "localhost" to IPv6 first does not pay a connect-timeout before falling back. All servers
     * share the same handlers and executor.
     */
    private volatile List<HttpServer> httpServers = Collections.emptyList();

    /**
     * Permits for concurrently running heavy tools. Never replaced - only its capacity is nudged to the
     * preference at each start, so permits still held by tools that outlive a restart are not stranded.
     */
    private final AdjustableSemaphore heavyPermits = new AdjustableSemaphore(PrefKeys.DEFAULT_HEAVY_TOOL_LIMIT);

    /** When the endpoint last opened, for the uptime reported by /health. */
    private volatile long serverStartMillis;

    private volatile McpRequestRouter protocolHandler;

    private volatile ThreadPoolExecutor requestPool;

    private volatile ThreadPoolExecutor ssePool;

    private volatile boolean running;

    private volatile int port;

    private final AtomicLong requestCount = new AtomicLong();

    private volatile String currentToolName;

    private volatile long toolStartedAt;

    /** Raised by the user when no call was in flight to answer; the next tool result carries it. */
    private final AtomicReference<OperatorSignal> pendingSignal = new AtomicReference<>();

    /** The call a user signal would pre-empt. One slot: the last call to arrive is the one on offer. */
    private volatile RunningToolCall activeToolCall;

    /** Guards set/clear of {@link #activeToolCall} so a finishing call cannot wipe a later running one. */
    private final Object activeCallLock = new Object();

    /**
     * Opens the endpoint, restarting it if it was already open.
     * <p>
     * The tools are registered before the socket is: the first request may arrive on the next
     * instruction, and it must find a full registry.
     * </p>
     *
     * @param serverPort the TCP port to listen on
     * @throws IOException when the port is taken, or the socket cannot be opened
     */
    public synchronized void start(int serverPort) throws IOException
    {
        if (running)
        {
            stop();
        }

        registerTools();
        protocolHandler = new McpRequestRouter();
        port = serverPort;

        configureJdkHttpTimeouts();
        heavyPermits.setLimit(heavyToolLimit());
        // Stamp before the first listener opens, so /health never reports a stale uptime from a prior run.
        serverStartMillis = System.currentTimeMillis();

        List<HttpServer> servers = new ArrayList<>();
        try
        {
            requestPool = createRequestPool();
            ssePool = createSsePool();
            McpHandler mcpHandler = new McpHandler();
            HealthHandler healthHandler = new HealthHandler();
            for (InetSocketAddress address : listenAddresses(serverPort))
            {
                HttpServer server;
                try
                {
                    server = HttpServer.create(address, 0);
                }
                catch (IOException e)
                {
                    // A loopback family that is not available (e.g. no IPv6 stack) is skipped, not
                    // fatal, as long as at least one address binds. A taken port fails every address
                    // and is caught below.
                    Activator.logInfo("MCP server could not bind " //$NON-NLS-1$
                        + address.getAddress().getHostAddress() + ": " + e.getMessage()); //$NON-NLS-1$
                    continue;
                }
                server.createContext(CONTEXT_MCP, mcpHandler);
                server.createContext(CONTEXT_HEALTH, healthHandler);
                server.setExecutor(requestPool);
                server.start();
                servers.add(server);
                Activator.logInfo("MCP server listening on " //$NON-NLS-1$
                    + address.getAddress().getHostAddress() + ":" + serverPort); //$NON-NLS-1$
            }
            if (servers.isEmpty())
            {
                throw new IOException("MCP server could not bind any address on port " + serverPort); //$NON-NLS-1$
            }
        }
        catch (RuntimeException | IOException e)
        {
            // Do not leak a half-open endpoint: stop whatever already bound and drop the pools before
            // letting the failure out, so a retry starts from a clean slate rather than hitting a
            // "port in use" from an orphaned listener.
            for (HttpServer started : servers)
            {
                started.stop(0);
            }
            requestPool = shutdown(requestPool);
            ssePool = shutdown(ssePool);
            throw e;
        }
        httpServers = servers;
        running = true;
    }

    /**
     * Builds the {@code /health} body: the base status and EDT version, plus live server metrics so an
     * operator or a monitor can see load and the current call at a glance. All fields are additive - the
     * original {@code status} and {@code edt_version} keys are unchanged.
     *
     * @return the health JSON
     */
    private String healthJson()
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("edt_version", EdtVersionReader.getEdtVersion()); //$NON-NLS-1$
        payload.addProperty("phase", WorkspacePhase.current()); //$NON-NLS-1$
        long started = serverStartMillis;
        payload.addProperty("uptimeSeconds", //$NON-NLS-1$
            started == 0L ? 0L : (System.currentTimeMillis() - started) / MILLIS_PER_SECOND);
        ThreadPoolExecutor pool = requestPool;
        if (pool != null)
        {
            payload.addProperty("queueDepth", pool.getQueue().size()); //$NON-NLS-1$
            payload.addProperty("poolActive", pool.getActiveCount()); //$NON-NLS-1$
            payload.addProperty("poolSize", pool.getMaximumPoolSize()); //$NON-NLS-1$
        }
        ThreadPoolExecutor sse = ssePool;
        if (sse != null)
        {
            payload.addProperty("sseActive", sse.getActiveCount()); //$NON-NLS-1$
        }
        payload.addProperty("heavyAvailable", heavyPermits.availablePermits()); //$NON-NLS-1$
        RunningToolCall active = getActiveToolCall();
        if (active != null)
        {
            JsonObject call = new JsonObject();
            call.addProperty("name", active.getToolName()); //$NON-NLS-1$
            call.addProperty("ageSeconds", active.getElapsedSeconds()); //$NON-NLS-1$
            payload.add("activeCall", call); //$NON-NLS-1$
        }
        return GsonHolder.toJson(payload);
    }

    /**
     * Where to listen.
     * <p>
     * Loopback unless told otherwise. The tools behind this endpoint read and write the infobase and
     * the source code, and the bearer token is off by default - so a socket open to the network would
     * hand the workspace to anyone who can route to this machine. Every local client (the MCP config of
     * every workspace) connects to 127.0.0.1, so loopback costs them nothing.
     * </p>
     * <p>
     * Turning {@link PrefKeys#PREF_BIND_ALL_INTERFACES} on opens the socket to the network.
     * Doing that without a token is logged as a warning rather than refused: an operator may have a
     * reason, and silently ignoring a setting is worse than obeying it loudly.
     * </p>
     *
     * @param serverPort the TCP port to listen on
     * @return the addresses to bind, in order; never empty
     */
    private static List<InetSocketAddress> listenAddresses(int serverPort)
    {
        if (bindsEveryInterface())
        {
            if (McpAuth.activeToken() == null)
            {
                Activator.logWarning(
                    "MCP server is listening on every network interface with no bearer token: any host " //$NON-NLS-1$
                        + "that can reach this machine can read and modify the infobase and the sources. " //$NON-NLS-1$
                        + "Set a token, or turn the setting off."); //$NON-NLS-1$
            }
            return Collections.singletonList(new InetSocketAddress(serverPort));
        }
        // Loopback only, but both families: "localhost" resolves to ::1 and 127.0.0.1, and a client
        // that tries the IPv6 one first must not wait out a connect-timeout before falling back.
        List<InetSocketAddress> addresses = new ArrayList<>(2);
        addLoopback(addresses, "127.0.0.1", serverPort); //$NON-NLS-1$
        addLoopback(addresses, "::1", serverPort); //$NON-NLS-1$
        if (addresses.isEmpty())
        {
            addresses.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort));
        }
        return addresses;
    }

    /**
     * Appends a loopback address for the given literal host, skipping it if the literal cannot be
     * parsed. Whether the family actually binds is decided later by the socket, not here.
     *
     * @param into the list to append to
     * @param host a numeric loopback literal ("127.0.0.1" or "::1")
     * @param serverPort the TCP port
     */
    private static void addLoopback(List<InetSocketAddress> into, String host, int serverPort)
    {
        try
        {
            into.add(new InetSocketAddress(InetAddress.getByName(host), serverPort));
        }
        catch (UnknownHostException e)
        {
            // A numeric literal should always parse; if it somehow does not, skip this family.
        }
    }

    /**
     * Whether the operator asked for a socket open to the network.
     *
     * @return <code>true</code> to bind every interface, <code>false</code> for loopback only
     */
    private static boolean bindsEveryInterface()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return PrefKeys.DEFAULT_BIND_ALL_INTERFACES;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        if (store == null)
        {
            return PrefKeys.DEFAULT_BIND_ALL_INTERFACES;
        }
        return store.getBoolean(PrefKeys.PREF_BIND_ALL_INTERFACES);
    }

    /**
     * How many heavy tools may run at once, from the preference.
     *
     * @return the limit, always at least 1 (an unset or non-positive preference falls back to the
     *         shipped default)
     */
    private static int heavyToolLimit()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return PrefKeys.DEFAULT_HEAVY_TOOL_LIMIT;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        if (store == null)
        {
            return PrefKeys.DEFAULT_HEAVY_TOOL_LIMIT;
        }
        int limit = store.getInt(PrefKeys.PREF_HEAVY_TOOL_LIMIT);
        return limit > 0 ? limit : PrefKeys.DEFAULT_HEAVY_TOOL_LIMIT;
    }

    /**
     * Closes the endpoint, giving whatever is in flight a moment to finish.
     */
    public synchronized void stop()
    {
        boolean wasRunning = !httpServers.isEmpty();
        for (HttpServer server : httpServers)
        {
            server.stop(STOP_GRACE_SECONDS);
        }
        httpServers = Collections.emptyList();
        running = false;
        serverStartMillis = 0L;
        // Always drop the pools, even when no server was tracked: a start() that failed partway may
        // have created them before it threw.
        requestPool = shutdown(requestPool);
        ssePool = shutdown(ssePool);
        if (wasRunning)
        {
            Activator.logInfo("MCP server stopped"); //$NON-NLS-1$
        }
    }

    /**
     * Closes the endpoint and opens it again, possibly on a different port.
     *
     * @param serverPort the TCP port to listen on
     * @throws IOException when the port is taken, or the socket cannot be opened
     */
    public void restart(int serverPort) throws IOException
    {
        stop();
        start(serverPort);
    }

    /**
     * Tells whether the endpoint is open.
     *
     * @return <code>true</code> when the server is listening
     */
    public boolean isRunning()
    {
        return running;
    }

    /**
     * Returns the port the server was last asked to listen on.
     *
     * @return the port
     */
    public int getPort()
    {
        return port;
    }

    /**
     * Returns how many requests have arrived since the plugin started.
     *
     * @return the request count
     */
    public long getRequestCount()
    {
        return requestCount.get();
    }

    /**
     * Counts one more request.
     */
    public void incrementRequestCount()
    {
        requestCount.incrementAndGet();
    }

    /**
     * Returns the tool that is running.
     *
     * @return the tool name, or <code>null</code> when nothing is running
     */
    public String getCurrentToolName()
    {
        return currentToolName;
    }

    /**
     * Announces which tool is running, so the status bar can show it and time it.
     *
     * @param toolName the tool that has just started, or <code>null</code> now that it has finished
     */
    public void setCurrentToolName(String toolName)
    {
        currentToolName = toolName;
        toolStartedAt = toolName != null ? System.currentTimeMillis() : 0L;
    }

    /**
     * Tells whether a tool is running.
     *
     * @return <code>true</code> while a tool is executing
     */
    public boolean isToolExecuting()
    {
        return currentToolName != null;
    }

    /**
     * Returns how long the running tool has been running.
     *
     * @return whole seconds, or {@code 0} when nothing is running
     */
    public long getToolExecutionSeconds()
    {
        long startedAt = toolStartedAt;
        if (startedAt == 0L)
        {
            return 0L;
        }
        return (System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND;
    }

    /**
     * Leaves a signal for the agent to pick up.
     * <p>
     * There is one place to leave it, and it is read once: the next tool call to finish carries it
     * back, and no other call ever sees it. A second signal left before the first is collected takes
     * its place.
     * </p>
     *
     * @param signal what the user wants the agent to do
     */
    public void setUserSignal(OperatorSignal signal)
    {
        pendingSignal.set(signal);
    }

    /**
     * Collects the signal the user left, if any. Reading it takes it away, so ask once per tool call.
     *
     * @return the signal, or <code>null</code> when there is none
     */
    public OperatorSignal consumeUserSignal()
    {
        return pendingSignal.getAndSet(null);
    }

    /**
     * Answers the call the agent is waiting on with the user's signal, instead of the tool's result.
     * <p>
     * The tool is <em>not</em> stopped. It cannot be: an EDT operation runs to the end whatever
     * happens here, and its result is discarded when it arrives. What this does is free the agent from
     * waiting for it, which is the whole point of the feature and is what the answer says.
     * </p>
     *
     * @param signal what the user wants the agent to do
     * @return <code>true</code> when the agent was answered; <code>false</code> when no call was
     *         waiting, it had already been answered, or the connection had gone
     */
    public synchronized boolean interruptToolCall(OperatorSignal signal)
    {
        RunningToolCall call = activeToolCall;
        if (call == null || call.hasResponded())
        {
            return false;
        }
        // A cancel signal raises the call's cancellation flag so a cooperative loop can bail out at
        // its next checkpoint. Only CANCEL does this - a background/retry/expert signal must leave a
        // useful scan running. Opaque EDT work and not-yet-instrumented loops run to the end anyway;
        // this is a best-effort stop of our own cancellable loops, not a hard kill.
        if (signal != null && signal.getType() == OperatorSignal.SignalType.CANCEL)
        {
            call.cancellation().cancel("cancelled by operator"); //$NON-NLS-1$
        }
        if (!call.sendSignalResponse(signal))
        {
            return false;
        }
        setCurrentToolName(null);
        clearActiveToolCall(call);
        return true;
    }

    /**
     * Offers up the call a user signal may pre-empt.
     *
     * @param call the call now in flight
     */
    public void setActiveToolCall(RunningToolCall call)
    {
        synchronized (activeCallLock)
        {
            activeToolCall = call;
        }
    }

    /**
     * Returns the call a user signal would pre-empt.
     *
     * @return the call in flight, or <code>null</code>
     */
    public RunningToolCall getActiveToolCall()
    {
        return activeToolCall;
    }

    /**
     * Withdraws the call on offer.
     */
    public void clearActiveToolCall(RunningToolCall call)
    {
        // Compare-and-clear: only wipe the slot if it still holds this call. A call finishing must not
        // erase a later call that has since overwritten the slot and is still running.
        synchronized (activeCallLock)
        {
            if (activeToolCall == call)
            {
                activeToolCall = null;
            }
        }
    }

    /**
     * Fills the registry with every tool this server can run.
     * <p>
     * The registry is emptied first, so calling this twice - which is what happens in a normal session,
     * once for the preference pages and once when the server starts - leaves one copy of each tool.
     * </p>
     * <p>
     * Several entries below overlap on purpose and must not be weeded out. {@code YaxunitTestsTool}
     * supersedes the two yaxunit tools either side of it, {@code CodeSearchTool} and
     * {@code LaunchDebuggerTool} are single doors onto the search and debug tools respectively,
     * {@code DiagnosticsFacadeTool}, {@code ProjectAdminFacadeTool}, {@code InfobaseAdminFacadeTool},
     * {@code ConfigIoFacadeTool}, {@code InsightsFacadeTool}, {@code SecurityAuditFacadeTool},
     * {@code WorkspaceMarksFacadeTool} and {@code DocsLookupFacadeTool}
     * are single doors onto the validation/health, project/configuration-administration,
     * infobase/launch-administration, configuration-import/export, configuration-insight,
     * security-audit, workspace-marks and documentation-lookup tools respectively, and
     * {@code EditFormTool} is an older name for what {@code EditMetadataTool} does. All of them stay
     * registered: agents in the field call them by the names they know.
     * </p>
     */
    void registerTools()
    {
        McpToolCatalog registry = McpToolCatalog.getInstance();
        registry.clear();

        for (IMcpTool tool : declareAllTools())
        {
            registry.register(tool);
        }

        Activator.logInfo("Registered " + registry.getToolCount() + " MCP tools"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The fixed set of tools this server registers, in a stable, reviewable order.
     * <p>
     * {@link #registerTools()} walks this list into the registry, so this is the single place a tool
     * is declared. The registry is keyed by name, so the order here is for the reader only and does
     * not affect behaviour; it is kept in the historical order purely so a diff stays legible. Holding
     * the set in one declared place also lets a test reconcile it - no two tools may share a
     * {@code getName()} - without standing the server up.
     * </p>
     *
     * @return a fresh, immutable list of tool instances to register, never <code>null</code>
     */
    static List<IMcpTool> declareAllTools()
    {
        return List.of(
            new EdtVersionReader(),
            new ProjectsLister(),
            new ProjectRemover(),
            new SyncControlTool(),
            new ConfigurationInfoReader(),
            new ProjectCleaner(),
            new ObjectsRevalidator(),
            new DiskResynchronizer(),
            new ProblemSummaryReader(),
            new ProjectProblemsReader(),
            new BookmarksReader(),
            new TasksReader(),
            new CheckDocReader(),
            new ContentAssistReader(),
            new PlatformDocReader(),
            new MetadataObjectsReader(),
            new GetSubsystemsTool(),
            new GetCommandInterfaceTool(),
            new MetadataDetailsReader(),
            new ReferenceLocator(),
            new OutgoingStructuresReader(),
            new TagsReader(),
            new TaggedObjectsReader(),
            new McpHistoryReader(),
            new SelfStatusTool(),
            new SelfUpkeepTool(),
            new ApplicationsReader(),
            new DatabaseUpdater(),
            new DebugSessionStarter(),
            new LaunchConfigsLister(),
            new ConfigurationXmlExporter(),
            new ConfigurationXmlImporter(),
            new YaxunitTestsTool(),
            new YaxunitTestRunner(),
            new BreakpointSetter(),
            new SetExceptionBreakpointTool(),
            new RunToLineTool(),
            new BreakpointRemover(),
            new BreakpointsLister(),
            new SuspendWaiter(),
            new DebugVariablesReader(),
            new DebugVariableWriter(),
            new DebugStepper(),
            new DebugResumer(),
            new LaunchTerminator(),
            new RestartEdtTool(),
            new BslCodeReviewTool(),
            new VanessaTool(),
            new ExpressionEvaluator(),
            new YaxunitDebugRunner(),
            new DebugStateReader(),
            new ProfilingStarter(),
            new ProfilingResultsReader(),
            new LaunchDebuggerTool(),
            new ModuleSourceReader(),
            new ModuleSourceWriter(),
            new ModuleOutlineReader(),
            new ModulesLister(),
            new CodeTextSearcher(),
            new DcsSearchTool(),
            new MethodSourceReader(),
            new CallHierarchyReader(),
            new DefinitionNavigator(),
            new SymbolInfoReader(),
            new CodeSearchTool(),
            new FormScreenshotGrabber(),
            new GetFormStructureTool(),
            new GetObjectHelpTool(),
            new ExportObjectTool(),
            new CommonPictureExporter(),
            new InfobaseCredentialsWriter(),
            new InfobaseCreator(),
            new LaunchConfigCreator(),
            new InfobaseRemover(),
            new ListExtensionsTool(),
            new UninstallExtensionTool(),
            new ExportExtensionTool(),
            new InstallExtensionTool(),
            new QueryValidator(),
            new DiffModuleTool(),
            new AiContextTool(),
            new MetadataObjectRenamer(),
            new MetadataObjectDeleter(),
            new AttributeAdder(),
            new EditFormTool(),
            new EditMetadataTool(),
            new DcsWorkshopTool(),
            new MxlWorkshopTool(),
            new XdtoWorkshopTool(),
            new ExtensionWorkshopTool(),
            new ExternalObjectWorkshopTool(),
            new ExternalDataSourceWorkshopTool(),
            new ProjectCreator(),
            new DependencyGraphTool(),
            new DetectQueryAntiPatternsTool(),
            new ProjectMetricsTool(),
            new CompareConfigurationsTool(),
            new ValidateForExportTool(),
            new ImpactAnalysisTool(),
            new AuditRoleRightsTool(),
            new FindRlsViolationsTool(),
            new SensitiveDataScanTool(),
            new GenerateEventHandlersTool(),
            new GenerateHealthSnapshotTool(),
            new CodeTemplateTool(),
            new ExtensionLifecycleTool(),
            new ExtensionDiffTool(),
            new ListInterceptorsTool(),
            new FindDeadCodeTool(),
            new ObjectSummaryTool(),
            new SemanticMetadataSearchTool(),
            new DiagnosticsFacadeTool(),
            new ProjectAdminFacadeTool(),
            new InfobaseAdminFacadeTool(),
            new ConfigIoFacadeTool(),
            new InsightsFacadeTool(),
            new SecurityAuditFacadeTool(),
            new WorkspaceMarksFacadeTool(),
            new DocsLookupFacadeTool());
    }

    /**
     * Tells the JDK's HTTP server to be patient.
     * <p>
     * A tool call can sit inside EDT for minutes, and the defaults would cut the request or the
     * response off long before it came back. These are read once, in a static initializer, the first
     * time the JDK's server configuration is loaded - so they are set here, immediately before the
     * first server is created, and setting them any later would do nothing at all.
     * </p>
     */
    private static void configureJdkHttpTimeouts()
    {
        System.setProperty("sun.net.httpserver.idleInterval", "300"); //$NON-NLS-1$ //$NON-NLS-2$
        System.setProperty("sun.net.httpserver.maxIdleConnections", "32"); //$NON-NLS-1$ //$NON-NLS-2$
        System.setProperty("sun.net.httpserver.maxReqTime", "600"); //$NON-NLS-1$ //$NON-NLS-2$
        System.setProperty("sun.net.httpserver.maxRspTime", "600"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Builds the pool that serves requests.
     *
     * @return the request pool
     */
    private static ThreadPoolExecutor createRequestPool()
    {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(REQUEST_POOL_SIZE, REQUEST_POOL_SIZE,
            POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(REQUEST_QUEUE_CAPACITY));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Builds the pool that carries event streams.
     * <p>
     * It hands off directly rather than queueing, so the eleventh stream is refused at once instead of
     * waiting for a thread that is never coming. The threads are daemons: a stream nobody closed must
     * not keep the workbench alive.
     * </p>
     *
     * @return the SSE pool
     */
    private static ThreadPoolExecutor createSsePool()
    {
        return new ThreadPoolExecutor(0, SSE_POOL_SIZE, POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, SSE_THREAD_PREFIX + System.currentTimeMillis());
                thread.setDaemon(true);
                return thread;
            });
    }

    /**
     * Shuts a pool down.
     *
     * @param pool the pool; may be <code>null</code>
     * @return <code>null</code>, to be assigned back over the field
     */
    private static ThreadPoolExecutor shutdown(ThreadPoolExecutor pool)
    {
        if (pool != null)
        {
            pool.shutdownNow();
        }
        return null;
    }

    /**
     * A semaphore whose total capacity can be changed after construction. The heavy-tool limiter uses
     * one instance for the life of the endpoint: a restart adjusts its capacity to the current
     * preference rather than replacing it, so permits still held by tools that outlive the restart are
     * returned to the same object and the "at most N heavy at once" invariant survives.
     */
    private static final class AdjustableSemaphore
        extends Semaphore
    {
        private static final long serialVersionUID = 1L;

        private int limit;

        AdjustableSemaphore(int permits)
        {
            super(permits);
            this.limit = permits;
        }

        /**
         * Changes the capacity. Raising it frees the extra permits at once; lowering it removes permits
         * as they come back, without disturbing those a tool currently holds.
         *
         * @param newLimit the new capacity, at least 1
         */
        synchronized void setLimit(int newLimit)
        {
            if (newLimit > this.limit)
            {
                release(newLimit - this.limit);
            }
            else if (newLimit < this.limit)
            {
                reducePermits(this.limit - newLimit);
            }
            this.limit = newLimit;
        }
    }

    /**
     * Serves {@code /mcp}: the endpoint itself.
     */
    private final class McpHandler
        implements HttpHandler
    {
        /**
         * Numbers the events this server sends. Clients use it to resume a stream, which this server
         * does not support, so nothing rests on it being gapless.
         */
        private final AtomicLong eventId = new AtomicLong();

        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            if (METHOD_GET.equals(exchange.getRequestMethod()))
            {
                // Hands the connection to a thread of its own, and must not close it here.
                handleGet(exchange);
                return;
            }
            try
            {
                handleRequest(exchange);
            }
            catch (IOException e)
            {
                // The client hung up. There is nobody left to tell.
                Activator.logInfo("MCP client disconnected: " + e.getMessage()); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Activator.logError("MCP request failed", e); //$NON-NLS-1$
                try
                {
                    sendBody(exchange, HTTP_INTERNAL_ERROR, JsonUtils.buildJsonRpcError(
                        McpServerMeta.ERROR_INTERNAL, MSG_INTERNAL_ERROR, null));
                }
                catch (IOException failed)
                {
                    Activator.logInfo("Could not report the failure to the client: " //$NON-NLS-1$
                        + failed.getMessage());
                }
            }
            finally
            {
                exchange.close();
            }
        }

        /**
         * Runs a request through the gates and on to whatever answers it.
         *
         * @param exchange the connection
         * @throws IOException when the connection breaks
         */
        private void handleRequest(HttpExchange exchange) throws IOException
        {
            if (!admit(exchange))
            {
                return;
            }
            if (!applyCors(exchange))
            {
                sendBody(exchange, HTTP_FORBIDDEN,
                    JsonUtils.buildJsonRpcError(McpServerMeta.ERROR_INVALID_REQUEST, MSG_INVALID_ORIGIN, null));
                return;
            }

            String method = exchange.getRequestMethod();
            if (METHOD_OPTIONS.equals(method))
            {
                // Never behind the token: a browser does not put an Authorization header on a
                // preflight, so demanding one here would lock out every browser client the moment
                // authentication was switched on.
                sendNoBody(exchange, HTTP_NO_CONTENT);
                return;
            }
            if (!authorize(exchange))
            {
                return;
            }
            if (METHOD_POST.equals(method))
            {
                handlePost(exchange);
                return;
            }
            if (METHOD_DELETE.equals(method))
            {
                // MCP ends a session with this. There is no session here to end, so it is accepted and
                // forgotten.
                sendNoBody(exchange, HTTP_OK);
                return;
            }
            sendBody(exchange, HTTP_METHOD_NOT_ALLOWED, JsonUtils.buildSimpleError(MSG_METHOD_NOT_ALLOWED));
        }

        /**
         * Turns a caller away while the server is saturated, before any work is done on their behalf.
         *
         * @param exchange the connection
         * @return <code>true</code> when the request may proceed
         * @throws IOException when the connection breaks
         */
        private boolean admit(HttpExchange exchange) throws IOException
        {
            ThreadPoolExecutor pool = requestPool;
            if (pool == null)
            {
                return true;
            }
            int inFlight = pool.getQueue().size() + pool.getActiveCount();
            if (inFlight <= ADMISSION_LIMIT)
            {
                return true;
            }
            Activator.logInfo("MCP server is saturated (" + inFlight //$NON-NLS-1$
                + " requests in flight); asking the client to come back"); //$NON-NLS-1$
            exchange.getResponseHeaders().add(HEADER_RETRY_AFTER, RETRY_AFTER_SECONDS);
            sendBody(exchange, HTTP_UNAVAILABLE, JsonUtils.buildSimpleError(MSG_OVERLOADED));
            return false;
        }

        /**
         * Answers a JSON-RPC document.
         *
         * @param exchange the connection
         * @throws IOException when the connection breaks
         */
        private void handlePost(HttpExchange exchange) throws IOException
        {
            incrementRequestCount();
            Activator.logInfo("MCP request from " + exchange.getRemoteAddress()); //$NON-NLS-1$

            String body;
            try
            {
                body = readBody(exchange);
            }
            catch (IOException e)
            {
                // The request never arrived in full; the client is presumed gone, so nothing is sent
                // back.
                Activator.logInfo("MCP client disconnected while sending its request: " //$NON-NLS-1$
                    + e.getMessage());
                return;
            }
            Activator.logInfo("MCP request: " + redactSecrets(body)); //$NON-NLS-1$

            // Routed by looking for the method name in the raw text rather than by parsing it. The
            // protocol layer parses it properly a moment later; all this decides is which of the two
            // ways of running it is used, and it costs nothing to be wrong.
            boolean initialize = body.contains(quoted(McpServerMeta.METHOD_INITIALIZE));
            boolean toolCall = body.contains(quoted(McpServerMeta.METHOD_TOOLS_CALL));

            String document;
            try
            {
                if (toolCall)
                {
                    ToolCallOutcome outcome = runToolCall(exchange, body);
                    if (outcome.answered)
                    {
                        // The user got there first. The answer is already on the wire.
                        return;
                    }
                    document = outcome.document;
                }
                else
                {
                    document = protocolHandler.processRequest(body);
                }
            }
            catch (Exception e)
            {
                // A tool threw, or the protocol layer did. Either way the agent gets a JSON-RPC error
                // in a perfectly good HTTP response - it asked a question and this is the answer.
                Activator.logError("MCP request handling failed", e); //$NON-NLS-1$
                deliver(exchange,
                    JsonUtils.buildJsonRpcError(McpServerMeta.ERROR_INTERNAL, e.getMessage(), null), initialize);
                return;
            }

            if (document == null)
            {
                // A notification: it wanted nothing back.
                Activator.logInfo("MCP notification accepted"); //$NON-NLS-1$
                sendNoBody(exchange, HTTP_ACCEPTED);
                return;
            }
            deliver(exchange, document, initialize);
        }

        /**
         * Runs a tool call on a thread of its own, so that the user can still get at the connection
         * while it runs.
         * <p>
         * This thread waits, looking up every so often to see whether the user has answered the call
         * from the status bar. The tool's thread is never interrupted: EDT operations do not take
         * kindly to it, and a signal is not a cancellation. If the user does answer, the tool goes on
         * running and whatever it eventually returns is dropped on the floor.
         * </p>
         *
         * @param exchange the connection the agent is waiting on
         * @param body the raw request
         * @return what to send back, or the news that it has already been sent
         * @throws Exception whatever the tool threw
         */
        private ToolCallOutcome runToolCall(HttpExchange exchange, String body) throws Exception
        {
            JsonObject header = asJsonObject(body);
            String toolName = readToolName(header);
            Semaphore permits = heavyPermits;
            boolean heavy = HeavyTools.isHeavy(toolName);
            if (heavy && !permits.tryAcquire())
            {
                // At the heavy-tool limit: turn this one away at once, freeing the request thread,
                // rather than piling another expensive run onto EDT and starving everything else.
                Activator.logInfo("Heavy tool '" + toolName //$NON-NLS-1$
                    + "' refused: concurrency limit reached"); //$NON-NLS-1$
                exchange.getResponseHeaders().add(HEADER_RETRY_AFTER, RETRY_AFTER_SECONDS);
                sendBody(exchange, HTTP_UNAVAILABLE, JsonUtils.buildSimpleError(MSG_HEAVY_BUSY));
                return ToolCallOutcome.answered();
            }
            // The permit, if taken, is released by the worker when the tool truly finishes.
            Runnable releasePermit = heavy ? permits::release : null;
            RunningToolCall call =
                new RunningToolCall(exchange, toolName, readRequestId(header));
            setActiveToolCall(call);
            boolean workerStarted = false;
            try
            {
                ToolExecution execution = new ToolExecution(body, call, releasePermit);
                Thread worker = new Thread(execution, TOOL_EXECUTOR_THREAD);
                worker.start();
                workerStarted = true;

                if (!awaitToolOrUser(execution, call))
                {
                    // The server is going down under us. Say nothing and let the connection close.
                    return ToolCallOutcome.answered();
                }
                if (call.hasResponded())
                {
                    return ToolCallOutcome.answered();
                }
                if (execution.failure != null)
                {
                    throw execution.failure;
                }
                return ToolCallOutcome.of(execution.document);
            }
            finally
            {
                if (!workerStarted && releasePermit != null)
                {
                    // Building or starting the worker threw, so it will never release the permit: do it
                    // here to avoid leaking a heavy slot.
                    releasePermit.run();
                }
                clearActiveToolCall(call);
            }
        }

        /**
         * Waits for the tool to finish, or for the user to answer the agent instead.
         *
         * @param execution the running tool
         * @param call the connection the agent is waiting on
         * @return <code>false</code> when this thread was interrupted and neither happened
         */
        private boolean awaitToolOrUser(ToolExecution execution, RunningToolCall call)
        {
            try
            {
                while (!execution.awaitCompletion(TOOL_POLL_INTERVAL_MS) && !call.hasResponded())
                {
                    // Waiting on the tool, with an eye on the user.
                }
                return true;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * Sends a response document, as JSON or wrapped in a single server-sent event, according to
         * what the client said it would accept.
         *
         * @param exchange the connection
         * @param document the JSON-RPC document
         * @param initialize whether this answers a handshake, which is the only time a session id is
         *            handed out
         * @throws IOException when the connection breaks
         */
        private void deliver(HttpExchange exchange, String document, boolean initialize) throws IOException
        {
            Activator.logInfo("endpoint reply: " + truncate(document)); //$NON-NLS-1$

            Headers headers = exchange.getResponseHeaders();
            if (initialize)
            {
                // Some clients will not proceed without one. Nothing here ever reads it back: this
                // server keeps no session state at all.
                headers.add(McpServerMeta.HEADER_SESSION_ID, UUID.randomUUID().toString());
            }
            if (acceptsEventStream(exchange))
            {
                headers.add(HEADER_CONTENT_TYPE, MIME_EVENT_STREAM);
                headers.add(HEADER_CACHE_CONTROL, NO_CACHE);
                headers.add(HEADER_CONNECTION, KEEP_ALIVE);
                sendBody(exchange, HTTP_OK, event(document));
                return;
            }
            headers.add(HEADER_CONTENT_TYPE, MIME_JSON);
            headers.add(HEADER_CONNECTION, KEEP_ALIVE);
            sendBody(exchange, HTTP_OK, document);
        }

        /**
         * Wraps a document in one server-sent event.
         * <p>
         * One event, a known length, and then the connection closes: this is an envelope, not a
         * stream. It exists because some clients will only speak this content type, not because there
         * is anything more to send - the server never speaks first.
         * </p>
         *
         * @param document the JSON-RPC document
         * @return the event
         */
        private String event(String document)
        {
            return "event: message\nid: " + eventId.incrementAndGet() + "\ndata: " + document + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        /**
         * Answers a {@code GET}: either the open-ended event stream some clients insist on
         * establishing before they will say anything, or a description of the server.
         * <p>
         * The token is checked here, before the origin is - the other way round from every other
         * request. It is an accident of how the stream was bolted on, and both checks still happen, so
         * it stays: putting it right would turn today's 401 into a 403 for somebody.
         * </p>
         *
         * @param exchange the connection
         * @throws IOException when the connection breaks
         */
        private void handleGet(HttpExchange exchange) throws IOException
        {
            if (!authorize(exchange))
            {
                exchange.close();
                return;
            }

            ThreadPoolExecutor pool = ssePool;
            if (pool == null || pool.isShutdown())
            {
                // close() in a finally: the client is already being turned away, and a write that
                // fails here must not also leak the exchange.
                try
                {
                    sendBody(exchange, HTTP_UNAVAILABLE, JsonUtils.buildSimpleError(MSG_SHUTTING_DOWN));
                }
                finally
                {
                    exchange.close();
                }
                return;
            }
            try
            {
                pool.execute(() -> serveGet(exchange));
            }
            catch (RejectedExecutionException e)
            {
                // Every stream is taken. Better to say so than to let the connection hang.
                try
                {
                    sendBody(exchange, HTTP_UNAVAILABLE, JsonUtils.buildSimpleError(MSG_SSE_OVERLOADED));
                }
                finally
                {
                    exchange.close();
                }
            }
        }

        /**
         * Serves a {@code GET} on a thread of the stream pool, which owns the connection from here to
         * the end.
         *
         * @param exchange the connection
         */
        private void serveGet(HttpExchange exchange)
        {
            try
            {
                if (!applyCors(exchange))
                {
                    sendBody(exchange, HTTP_FORBIDDEN, JsonUtils.buildJsonRpcError(
                        McpServerMeta.ERROR_INVALID_REQUEST, MSG_INVALID_ORIGIN, null));
                    return;
                }
                if (acceptsEventStream(exchange))
                {
                    streamHeartbeat(exchange);
                    return;
                }
                sendJson(exchange, HTTP_OK, JsonUtils.buildServerInfo(McpServerMeta.SERVER_NAME,
                    McpServerMeta.PLUGIN_VERSION, EdtVersionReader.getEdtVersion(),
                    McpServerMeta.PROTOCOL_VERSION));
            }
            catch (IOException e)
            {
                Activator.logInfo("MCP event stream closed: " + e.getMessage()); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Activator.logError("MCP GET request failed", e); //$NON-NLS-1$
            }
            finally
            {
                exchange.close();
            }
        }

        /**
         * Holds an event stream open, saying nothing.
         * <p>
         * The comment line it writes is ignored by every client; its only job is to keep the connection
         * from being reaped by whatever sits between here and the agent. No MCP message is ever pushed
         * down this stream, because this server has none to push. It ends when the client goes away and
         * the write fails.
         * </p>
         *
         * @param exchange the connection
         * @throws IOException when the client has gone
         */
        private void streamHeartbeat(HttpExchange exchange) throws IOException
        {
            Headers headers = exchange.getResponseHeaders();
            headers.add(HEADER_CONTENT_TYPE, MIME_EVENT_STREAM);
            headers.add(HEADER_CACHE_CONTROL, NO_CACHE);
            headers.add(HEADER_CONNECTION, KEEP_ALIVE);
            exchange.sendResponseHeaders(HTTP_OK, UNBOUNDED_BODY);

            OutputStream out = exchange.getResponseBody();
            byte[] heartbeat = SSE_HEARTBEAT.getBytes(StandardCharsets.UTF_8);
            while (!Thread.currentThread().isInterrupted())
            {
                out.write(heartbeat);
                out.flush();
                try
                {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Serves {@code /health}.
     * <p>
     * Behind neither the token nor the origin check. Monitoring probes and this project's own install
     * scripts poll it, they send no credentials and no origin, and a health endpoint that answers only
     * the authorized is not much of a health endpoint. The origin is still read, but only to decide
     * whether to echo the CORS headers back - a stranger gets a 200 without them, not a 403.
     * </p>
     */
    private final class HealthHandler
        implements HttpHandler
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            try
            {
                // CORS first, so a preflight of /health is answered with the headers a browser needs.
                applyCors(exchange);
                if (METHOD_OPTIONS.equals(exchange.getRequestMethod()))
                {
                    sendNoBody(exchange, HTTP_NO_CONTENT);
                    return;
                }
                sendJson(exchange, HTTP_OK, healthJson());
            }
            catch (IOException e)
            {
                Activator.logInfo("Health probe disconnected: " + e.getMessage()); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Activator.logError("Health probe failed", e); //$NON-NLS-1$
            }
            finally
            {
                exchange.close();
            }
        }
    }

    /**
     * A tool call running on its own thread, and whatever it comes back with.
     */
    private final class ToolExecution
        implements Runnable
    {
        private final String body;

        private final RunningToolCall call;

        private final Runnable onComplete;

        private final CountDownLatch finished = new CountDownLatch(1);

        private volatile String document;

        private volatile Exception failure;

        ToolExecution(String body, RunningToolCall call, Runnable onComplete)
        {
            this.body = body;
            this.call = call;
            this.onComplete = onComplete;
        }

        @Override
        public void run()
        {
            try
            {
                // Inside the try so that even if scope setup throws an Error, the finally still runs
                // countDown() - otherwise the waiting request thread would poll forever.
                ToolCallScope.enter(ToolCallScope.create(call));
                document = protocolHandler.processRequest(body);
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                ToolCallScope.exit();
                try
                {
                    if (onComplete != null)
                    {
                        // Release the heavy permit (if any) before signalling completion, so a following
                        // heavy call cannot briefly see the slot as still taken. This runs when the tool
                        // truly finishes - which may be after the request thread already returned, since a
                        // user signal answers the agent early while the tool keeps running - so the permit
                        // is held for the tool's real lifetime. The nested finally keeps countDown
                        // guaranteed even if the callback throws.
                        onComplete.run();
                    }
                }
                finally
                {
                    finished.countDown();
                }
            }
        }

        /**
         * Waits a little while for the tool to finish.
         *
         * @param millis how long to wait
         * @return <code>true</code> when it has finished
         * @throws InterruptedException when the waiting thread is interrupted
         */
        boolean awaitCompletion(long millis) throws InterruptedException
        {
            return finished.await(millis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * What came of a tool call: a document to send, or the news that the user has already been sent
     * one.
     */
    private static final class ToolCallOutcome
    {
        private final String document;

        private final boolean answered;

        private ToolCallOutcome(String document, boolean answered)
        {
            this.document = document;
            this.answered = answered;
        }

        /**
         * @param document the response document; may be <code>null</code> for a notification
         * @return an outcome still to be sent
         */
        static ToolCallOutcome of(String document)
        {
            return new ToolCallOutcome(document, false);
        }

        /**
         * @return an outcome that has already gone out over the connection
         */
        static ToolCallOutcome answered()
        {
            return new ToolCallOutcome(null, true);
        }
    }

    /**
     * Checks the bearer token, when there is one to check.
     * <p>
     * With authentication switched off - which is how it ships - not even the header is looked at.
     * </p>
     *
     * @param exchange the connection
     * @return <code>true</code> when the request may proceed; <code>false</code> when it has been
     *         answered with a 401
     * @throws IOException when the connection breaks
     */
    private static boolean authorize(HttpExchange exchange) throws IOException
    {
        String expected = McpAuth.activeToken();
        if (expected == null)
        {
            return true;
        }
        String presented = McpAuth.extractBearer(exchange.getRequestHeaders().getFirst(HEADER_AUTHORIZATION));
        if (McpAuth.constantTimeEquals(expected, presented))
        {
            return true;
        }
        exchange.getResponseHeaders().add(HEADER_WWW_AUTHENTICATE, BEARER_REALM);
        sendBody(exchange, HTTP_UNAUTHORIZED,
            JsonUtils.buildJsonRpcError(McpServerMeta.ERROR_INVALID_REQUEST, MSG_UNAUTHORIZED, null));
        return false;
    }

    /**
     * Decides whether a browser may talk to this server, and tells it so.
     * <p>
     * A request with no origin at all is not a browser and is let straight through: that is every MCP
     * client there is - the agent's own process, an editor, a script.
     * </p>
     *
     * @param exchange the connection
     * @return <code>false</code> when an origin was sent and it is not one this server talks to
     */
    private static boolean applyCors(HttpExchange exchange)
    {
        String origin = exchange.getRequestHeaders().getFirst(HEADER_ORIGIN);
        if (origin == null)
        {
            return true;
        }
        if (!isOriginAllowed(origin))
        {
            return false;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.add(HEADER_ALLOW_ORIGIN, origin);
        headers.add(HEADER_ALLOW_METHODS, ALLOWED_METHODS);
        headers.add(HEADER_ALLOW_HEADERS, ALLOWED_HEADERS);
        return true;
    }

    /**
     * Tells whether an origin is one of ours.
     *
     * @param origin the origin the browser sent
     * @return <code>true</code> when it may talk to this server
     */
    private static boolean isOriginAllowed(String origin)
    {
        // A page opened from a file has no origin to speak of and says so, in as many letters.
        if (ORIGIN_FILE_PAGE.equals(origin))
        {
            return true;
        }
        for (String prefix : ALLOWED_ORIGIN_PREFIXES)
        {
            if (origin.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the client asked for an event stream.
     *
     * @param exchange the connection
     * @return <code>true</code> when it will accept one
     */
    private static boolean acceptsEventStream(HttpExchange exchange)
    {
        String accept = exchange.getRequestHeaders().getFirst(HEADER_ACCEPT);
        return accept != null && accept.contains(MIME_EVENT_STREAM);
    }

    /**
     * Reads the request body.
     * <p>
     * Line by line, and the line breaks are dropped: JSON does not care, and the documents that arrive
     * here are JSON. There is no size limit, on purpose - a tool argument can be a whole BSL module.
     * </p>
     *
     * @param exchange the connection
     * @return the body, as one line of text
     * @throws IOException when the request never arrives in full
     */
    private static String readBody(HttpExchange exchange) throws IOException
    {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)))
        {
            String line = reader.readLine();
            while (line != null)
            {
                body.append(line);
                line = reader.readLine();
            }
        }
        return body.toString();
    }

    /**
     * Masks the secrets in a request before it is written to the log.
     *
     * @param body the raw request
     * @return the request, with anything secret in it replaced
     */
    private static String redactSecrets(String body)
    {
        if (body == null || body.isEmpty())
        {
            return body;
        }
        return SECRETS.matcher(body).replaceAll(SECRETS_MASK);
    }

    /**
     * Reads the JSON-RPC id out of a request, without insisting that the request make sense.
     * <p>
     * A number comes back as a {@link Long}: it arrives as a floating-point value, and an id echoed
     * back as {@code 7.0} is not the id the client is waiting for.
     * </p>
     *
     * @param request the parsed request; may be <code>null</code>
     * @return the id, as a {@link String} or a {@link Long}, or <code>null</code> when there is none to
     *         be had
     */
    private static Object readRequestId(JsonObject request)
    {
        if (request == null)
        {
            return null;
        }
        JsonElement id = request.get(PARAM_ID);
        if (id == null || !id.isJsonPrimitive())
        {
            return null;
        }
        JsonPrimitive primitive = id.getAsJsonPrimitive();
        if (primitive.isString())
        {
            return primitive.getAsString();
        }
        if (primitive.isNumber())
        {
            double value = primitive.getAsDouble();
            if (value == Math.floor(value) && !Double.isInfinite(value))
            {
                return Long.valueOf((long)value);
            }
        }
        return null;
    }

    /**
     * Reads the name of the tool being called, for the user to see in the status bar.
     *
     * @param request the parsed request; may be <code>null</code>
     * @return the tool name, or a stand-in when the request will not give one up
     */
    private static String readToolName(JsonObject request)
    {
        if (request == null)
        {
            return UNKNOWN_TOOL;
        }
        JsonElement params = request.get(PARAM_PARAMS);
        if (params == null || !params.isJsonObject())
        {
            return UNKNOWN_TOOL;
        }
        JsonElement name = params.getAsJsonObject().get(PARAM_NAME);
        if (name == null || !name.isJsonPrimitive())
        {
            return UNKNOWN_TOOL;
        }
        return name.getAsString();
    }

    /**
     * Parses a request far enough to look at it, and no further.
     * <p>
     * Nothing here may abort a request: this reading of the document is only for the status bar's
     * benefit, and the protocol layer will parse it properly - and complain properly - a moment later.
     * </p>
     *
     * @param body the raw request
     * @return the document, or <code>null</code> when it is not one
     */
    private static JsonObject asJsonObject(String body)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("Could not read the header of an MCP tool call: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Sends a JSON document.
     *
     * @param exchange the connection
     * @param status the HTTP status
     * @param body the document
     * @throws IOException when the connection breaks
     */
    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException
    {
        exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, MIME_JSON);
        sendBody(exchange, status, body);
    }

    /**
     * Sends a body of known length.
     *
     * @param exchange the connection
     * @param status the HTTP status
     * @param body the body
     * @throws IOException when the connection breaks
     */
    private static void sendBody(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(bytes);
        }
    }

    /**
     * Sends headers and nothing else.
     *
     * @param exchange the connection
     * @param status the HTTP status
     * @throws IOException when the connection breaks
     */
    private static void sendNoBody(HttpExchange exchange, int status) throws IOException
    {
        exchange.sendResponseHeaders(status, NO_BODY);
    }

    /**
     * Quotes a method name, so that it can be looked for in a raw document.
     *
     * @param method the JSON-RPC method name
     * @return the name with its quotes around it
     */
    private static String quoted(String method)
    {
        return "\"" + method + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Cuts a document down to something worth putting in a log.
     *
     * @param text the document; may be <code>null</code>
     * @return its beginning
     */
    private static String truncate(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (text.length() <= LOG_PREFIX_LIMIT)
        {
            return text;
        }
        return text.substring(0, LOG_PREFIX_LIMIT) + "..."; //$NON-NLS-1$
    }
}
