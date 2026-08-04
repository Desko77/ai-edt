/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jface.preference.IPreferenceStore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.McpHttpEndpoint;
import ru.aiedt.mcp.server.OperatorSignal;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.wire.jsonrpc.InitializeResult;
import ru.aiedt.mcp.server.wire.jsonrpc.JsonRpcRequest;
import ru.aiedt.mcp.server.wire.jsonrpc.JsonRpcResponse;
import ru.aiedt.mcp.server.wire.jsonrpc.ToolCallResult;
import ru.aiedt.mcp.server.wire.jsonrpc.ToolsListResult;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.GenericPending;
import ru.aiedt.mcp.server.support.MutatorIdempotency;
import ru.aiedt.mcp.server.support.MutatorIdempotencyStore;
import ru.aiedt.mcp.server.support.PendingExecutor;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ResponseCap;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Turns a JSON-RPC document into a tool call and the tool's answer back into a JSON-RPC document.
 * <p>
 * Four methods are recognized - {@code initialize}, {@code notifications/initialized},
 * {@code tools/list} and {@code tools/call} - and everything else is a method-not-found. Transport
 * concerns (sockets, CORS, authentication, HTTP status, SSE framing) belong to the server that owns
 * this handler; nothing below knows they exist.
 * </p>
 * <p>
 * One instance serves every request, from several threads at once. It holds no request state, and it
 * must stay that way.
 * </p>
 * <p>
 * The handler answers even when it fails: a bad document, an unknown method or a tool that threw all
 * come back as a JSON-RPC document, never as a thrown exception.
 * </p>
 */
public class McpRequestRouter
{
    /** Any well-formed date shape is accepted as a revision, which keeps older clients talking. */
    private static final Pattern MCP_REVISION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}"); //$NON-NLS-1$

    /** Echoed when the client's id cannot be determined; clients tolerate it, a null id would vanish. */
    private static final Object FALLBACK_REQUEST_ID = Integer.valueOf(1);

    private static final String EMBEDDED_URI_PREFIX = "embedded://"; //$NON-NLS-1$

    private static final String MIME_MARKDOWN = "text/markdown"; //$NON-NLS-1$

    private static final String MIME_PNG = "image/png"; //$NON-NLS-1$

    /** Literal, and load bearing: agents key on this token, and the interrupt path emits it too. */
    private static final String SIGNAL_TOKEN = "USER SIGNAL"; //$NON-NLS-1$

    private static final String PARAM_PROTOCOL_VERSION = "protocolVersion"; //$NON-NLS-1$

    private static final String MEMBER_USER_SIGNAL = "userSignal"; //$NON-NLS-1$

    private static final String MEMBER_TYPE = "type"; //$NON-NLS-1$

    private static final String MEMBER_MESSAGE = "message"; //$NON-NLS-1$

    private static final String MSG_INVALID_REQUEST = "Invalid request: JSON-RPC 2.0 expected"; //$NON-NLS-1$

    private static final String MSG_PARSE_ERROR = "Parse error: request body is not valid JSON"; //$NON-NLS-1$

    private static final String MSG_METHOD_NOT_FOUND = "Method not found"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private final McpToolCatalog toolRegistry;

    /**
     * Creates a handler over the tool registry. The registry may still be empty at this point; it is
     * consulted per request, not captured.
     */
    public McpRequestRouter()
    {
        this.toolRegistry = McpToolCatalog.getInstance();
    }

    /**
     * Handles one JSON-RPC document.
     *
     * @param requestBody the raw request body; may be malformed or <code>null</code>
     * @return the response document, or <code>null</code> when the request was a notification and
     *         there is nothing to answer
     */
    public String processRequest(String requestBody)
    {
        Object requestId = FALLBACK_REQUEST_ID;
        try
        {
            JsonElement document;
            try
            {
                document = parseDocument(requestBody);
            }
            catch (JsonParseException e)
            {
                Activator.logError("Malformed JSON-RPC request body", e); //$NON-NLS-1$
                return failure(requestId, McpServerMeta.ERROR_PARSE, MSG_PARSE_ERROR);
            }

            JsonRpcRequest request = readRequest(document);
            requestId = resolveRequestId(request);

            if (request == null || !McpServerMeta.JSONRPC_VERSION.equals(request.getJsonrpc()))
            {
                return failure(requestId, McpServerMeta.ERROR_INVALID_REQUEST, MSG_INVALID_REQUEST);
            }

            String method = request.getMethod();
            if (McpServerMeta.METHOD_INITIALIZE.equals(method))
            {
                return answer(requestId, buildInitializeResult(request));
            }
            if (McpServerMeta.METHOD_INITIALIZED.equals(method))
            {
                return null;
            }
            if (McpServerMeta.METHOD_TOOLS_LIST.equals(method))
            {
                return answer(requestId, buildToolsList());
            }
            if (McpServerMeta.METHOD_TOOLS_CALL.equals(method))
            {
                return callTool(request, requestId);
            }
            return failure(requestId, McpServerMeta.ERROR_METHOD_NOT_FOUND, MSG_METHOD_NOT_FOUND);
        }
        catch (Exception e)
        {
            Activator.logError("MCP request handling failed", e); //$NON-NLS-1$
            // A tool's exception message can carry PII (a query that echoed user data, a
            // path with an INN, ...) and would otherwise bypass the redaction applied to
            // normal results.
            String msg = e.getMessage();
            if (piiRedactEnabled() && msg != null)
            {
                msg = ru.aiedt.mcp.server.support.SensitiveTextMasker.redact(msg);
            }
            return failure(requestId, McpServerMeta.ERROR_INTERNAL, msg);
        }
    }

    /**
     * Reads the body as a JSON document, without yet caring what is in it.
     * <p>
     * Splitting this from {@link #readRequest(JsonElement)} is what lets the two failures be told
     * apart: a body that is not JSON at all is a parse error, a body that is fine JSON but not a
     * JSON-RPC request is an invalid request. They carry different codes.
     * </p>
     *
     * @param requestBody the raw body
     * @return the document, or <code>null</code> when the body is empty
     * @throws JsonParseException when the body is not JSON
     */
    private static JsonElement parseDocument(String requestBody)
    {
        if (requestBody == null || requestBody.trim().isEmpty())
        {
            return null;
        }
        return JsonParser.parseString(requestBody);
    }

    /**
     * Reads a parsed document as a JSON-RPC request.
     *
     * @param document the parsed body; may be <code>null</code>
     * @return the request, or <code>null</code> when the document is not a JSON object, or is one
     *         whose members are not the shape a request expects. Neither is a parse error - the JSON
     *         itself was well formed - so both come back as an invalid request.
     */
    private static JsonRpcRequest readRequest(JsonElement document)
    {
        if (document == null || !document.isJsonObject())
        {
            return null;
        }
        try
        {
            return GsonHolder.get().fromJson(document, JsonRpcRequest.class);
        }
        catch (JsonParseException e)
        {
            Activator.logError("JSON-RPC request has a member of the wrong type", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Works out the id to echo.
     * <p>
     * A whole number now arrives as a {@link Long} and needs nothing done to it. The {@link Double}
     * branch survives for the number that is not whole, and for the client that predates the parser
     * settings: an id echoed as {@code 0.0} no longer matches the request it answers.
     * </p>
     * <p>
     * Strings pass through, and a missing id falls back rather than becoming <code>null</code> (a
     * <code>null</code> id would drop the member from the envelope entirely).
     * </p>
     *
     * @param request the parsed request; may be <code>null</code>
     * @return the id to echo, never <code>null</code>
     */
    private static Object resolveRequestId(JsonRpcRequest request)
    {
        Object id = request != null ? request.getId() : null;
        if (id == null)
        {
            return FALLBACK_REQUEST_ID;
        }
        if (id instanceof Double)
        {
            double value = ((Double)id).doubleValue();
            if (value == Math.floor(value) && !Double.isInfinite(value) && value >= Long.MIN_VALUE
                && value <= Long.MAX_VALUE)
            {
                return Long.valueOf((long)value);
            }
        }
        return id;
    }

    /**
     * Answers the handshake.
     * <p>
     * A revision the client asks for is granted as long as it looks like a revision at all. There is
     * no allow-list: refusing an unknown one makes older clients give up, and agreeing costs nothing.
     * </p>
     *
     * @param request the initialize request
     * @return the handshake result
     */
    private static InitializeResult buildInitializeResult(JsonRpcRequest request)
    {
        String requested = request.getStringParam(PARAM_PROTOCOL_VERSION);
        String revision = requested != null && MCP_REVISION.matcher(requested).matches() ? requested
            : McpServerMeta.PROTOCOL_VERSION;

        return new InitializeResult(revision, McpServerMeta.SERVER_NAME, McpServerMeta.PLUGIN_VERSION,
            McpServerMeta.AUTHOR);
    }

    /**
     * Builds the catalogue of tools a client may call.
     * <p>
     * Disabled tools are left out entirely rather than advertised and then refused. Each schema is
     * parsed into a tree: handed over as a string, it would be a string on the wire too, and clients
     * reject such a tool. A tool whose schema does not parse takes the whole catalogue down with it,
     * which is the loudest and therefore the right way for that bug to surface.
     * </p>
     *
     * @return the catalogue
     */
    private ToolsListResult buildToolsList()
    {
        ToolsListResult catalogue = new ToolsListResult();
        for (IMcpTool tool : toolRegistry.getEnabledTools())
        {
            JsonElement schema = JsonParser.parseString(tool.getInputSchema());
            catalogue.addTool(tool.getName(), tool.getDescription(), schema);
        }
        return catalogue;
    }

    /**
     * Runs a tool and shapes its answer.
     *
     * @param request the tools/call request
     * @param requestId the id to echo
     * @return the response document
     */
    private String callTool(JsonRpcRequest request, Object requestId)
    {
        String toolName = request.getToolName();
        IMcpTool tool = toolName != null ? toolRegistry.getTool(toolName) : null;
        if (tool == null)
        {
            return failure(requestId, McpServerMeta.ERROR_METHOD_NOT_FOUND, "Tool not found: " + toolName); //$NON-NLS-1$
        }
        if (!toolRegistry.isToolEnabled(toolName))
        {
            // Not an error on purpose: an agent that is handed a protocol error gives up, while an
            // agent that is handed instructions asks the human to act on them.
            return answer(requestId, ToolCallResult.text(disabledToolMessage(toolName)));
        }

        Map<String, String> arguments = flattenArguments(request.getArguments());
        Activator.logInfo("MCP tool call: " + toolName); //$NON-NLS-1$

        String result;
        try
        {
            result = execute(tool, arguments);
        }
        catch (UiSync.UiBusyException busy)
        {
            // A read tool timed out waiting on a wedged UI thread. Surface it once, here, as
            // a tagged retryable result - matching the tools that catch this locally - so a
            // propagating UiBusyException reads as "UI busy, retry" rather than a bare internal
            // error. Tools that already tag it locally never reach this.
            result = ToolResult.error(busy.getMessage()).put("tag", busy.tag()).toJson(); //$NON-NLS-1$
        }
        // Cap an oversized response centrally, before the redact / parse / frame steps below
        // copy it several times over. Images are exempt: a truncated base64 blob is a broken
        // image, not a smaller one. The cap bounds the wire and that downstream amplification;
        // it does not stop a tool from having built the giant string in the first place (that
        // is a per-tool concern via ToolCallScope.responseByteLimit, a follow-up).
        int responseLimit = effectiveResponseLimit();
        if (responseLimit > 0 && tool.getResponseType() != IMcpTool.ResponseType.IMAGE
            && ResponseCap.exceeds(result, responseLimit))
        {
            long originalBytes = ResponseCap.byteLength(result);
            // Reassigning result to the bounded head drops the giant original for collection,
            // and forces a plain-TEXT answer that bypasses the JSON parse / base64 shaping a
            // truncated payload would fail.
            result = ResponseCap.truncateUtf8(result, responseLimit);
            if (piiRedactEnabled())
            {
                result = ru.aiedt.mcp.server.support.SensitiveTextMasker.redact(result);
            }
            OperatorSignal capSignal = consumeUserSignal();
            String notice = "\n\n[" + ErrorTags.TRUNCATED.wire() //$NON-NLS-1$
                + "=true: the response was " + originalBytes //$NON-NLS-1$
                + " bytes; its payload was truncated to " + responseLimit //$NON-NLS-1$
                + " bytes before this notice. Narrow the request (add filters, or an offset/limit) " //$NON-NLS-1$
                + "or call a more specific tool.]"; //$NON-NLS-1$
            return answer(requestId, ToolCallResult.text(withPlainSignal(result + notice, capSignal)));
        }
        if (piiRedactEnabled() && tool.getResponseType() != IMcpTool.ResponseType.IMAGE)
        {
            // Redact PII from the tool's OWN output (text / JSON / markdown) BEFORE it is
            // wrapped in the response - NOT from the JSON-RPC envelope and NOT from image
            // blobs. redactJson over the whole response would corrupt a matching envelope id
            // and any PII-looking run inside base64.
            result = ru.aiedt.mcp.server.support.SensitiveTextMasker.redact(result);
        }
        OperatorSignal signal = consumeUserSignal();
        boolean plainTextMode = isPlainTextMode();

        return answer(requestId, shapeResult(tool, arguments, result, signal, plainTextMode));
    }

    /** Whether 152-FZ PII masking is enabled in the preferences (default off). */
    private static boolean piiRedactEnabled()
    {
        return Activator.getDefault().getPreferenceStore()
            .getBoolean(ru.aiedt.mcp.server.settings.PrefKeys.PREF_PII_REDACT_ENABLED);
    }

    /**
     * The effective response byte cap for this call: a per-call scope override when one was set,
     * otherwise the preference, clamped to the hard ceiling. {@code 0} means capping is off.
     */
    private static int effectiveResponseLimit()
    {
        ToolCallScope scope = ToolCallScope.current();
        long raw;
        if (scope != null && scope.responseByteLimit() != ToolCallScope.UNSET)
        {
            raw = scope.responseByteLimit();
        }
        else
        {
            raw = Activator.getDefault().getPreferenceStore()
                .getInt(PrefKeys.PREF_MAX_RESPONSE_BYTES);
        }
        return ResponseCap.applyCeiling(raw);
    }

    /**
     * Executes a tool, announcing it to the status bar for as long as it runs.
     *
     * @param tool the tool to run
     * @param arguments the flattened arguments
     * @return whatever the tool produced
     */
    private static String execute(IMcpTool tool, Map<String, String> arguments)
    {
        McpHttpEndpoint server = getServer();
        if (server != null)
        {
            server.setCurrentToolName(tool.getName());
        }
        long start = System.currentTimeMillis();
        String result = null;
        boolean success = true;
        String error = null;
        try
        {
            result = runToolBody(tool, arguments);
            return result;
        }
        catch (RuntimeException re)
        {
            // Record the failure for the history buffer, then propagate unchanged.
            success = false;
            error = re.getMessage();
            throw re;
        }
        finally
        {
            if (server != null)
            {
                server.setCurrentToolName(null);
            }
            String resultSummary = success ? result
                : "exception: " + (error != null ? error : "RuntimeException"); //$NON-NLS-1$ //$NON-NLS-2$
            // A tool that returned {success:false} is a logical failure for the stats, even
            // though it did not throw (tools report failure via the result, not an exception).
            boolean logicalSuccess = success && !looksFailed(result);
            McpHistory.record(tool.getName(), summarizeArgs(arguments), resultSummary,
                System.currentTimeMillis() - start, logicalSuccess);
        }
    }

    /**
     * Runs the tool body, routing the slow read-only analysis tools through the
     * generic soft-timeout / {@code runKey} Pending flow (see {@link GenericPending})
     * and every other tool inline.
     * <p>
     * For a wrapped tool the real work runs on the {@link PendingWorkRegistry#GENERIC}
     * executor; the request thread waits a fixed soft timeout and then returns a
     * resumable {@code Pending} instead of staying pinned for the whole call. The
     * soft wait is a fixed server value - it never reads the tool's own
     * {@code timeoutSeconds}, which stays the tool's work budget and part of its
     * run identity. Only read-only, side-effect-free tools are wrapped, because the
     * flow cache-replays and coalesces; a resume request (carrying {@code runKey})
     * is served from the same registry without re-running the work.
     *
     * @param tool the resolved tool
     * @param arguments the flattened arguments
     * @return the finished result, or a {@code Pending} JSON with a runKey
     */
    private static String runToolBody(IMcpTool tool, Map<String, String> arguments)
    {
        String name = tool.getName();
        // An optional client operationId makes an allowlisted mutator at-most-once: a repeat
        // with the same id replays the first result instead of mutating again. Absent the id
        // (the case for every client today) this is a no-op and the call runs as before.
        String operationId = arguments.get("operationId"); //$NON-NLS-1$
        if (MutatorIdempotency.applies(name, operationId, arguments))
        {
            String key = MutatorIdempotency.key(name, operationId);
            return MutatorIdempotencyStore.INSTANCE.call(key, () -> tool.execute(arguments),
                MutatorIdempotencyStore.DEFAULT_WAITER_TIMEOUT_MS);
        }
        if (!GenericPending.applies(name))
        {
            return tool.execute(arguments);
        }
        String runKey = PendingWorkRegistry.computeRunKey(name,
            GenericPending.canonicalParams(arguments));
        return PendingExecutor.execute(PendingWorkRegistry.GENERIC, name, arguments, runKey,
            PendingExecutor.DEFAULT_SOFT_TIMEOUT_MS, () -> tool.execute(arguments), null);
    }

    /** Flattens a tool's arguments into a short {@code k=v; k=v} summary for the history buffer. */
    private static String summarizeArgs(Map<String, String> arguments)
    {
        if (arguments == null || arguments.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : arguments.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(e.getKey()).append('=');
            String v = e.getValue();
            if (v != null && isSensitiveArgKey(e.getKey()))
            {
                // Never leak credentials (set_infobase_credentials.password, tokens, ...) into
                // the in-memory history buffer.
                sb.append("***"); //$NON-NLS-1$
            }
            else
            {
                // Cap each value so a huge argument (source code, long JSON) does not build an
                // unbounded temporary string before the 250-char summary cap applies.
                sb.append(v == null ? "null" : (v.length() > 80 ? v.substring(0, 80) + "..." : v)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (sb.length() > 250)
            {
                sb.append("..."); //$NON-NLS-1$
                break;
            }
        }
        return sb.toString();
    }

    /** Argument keys whose values must not be recorded (credentials, tokens, secrets). */
    private static boolean isSensitiveArgKey(String key)
    {
        if (key == null)
        {
            return false;
        }
        String lc = key.toLowerCase();
        return lc.contains("password") || lc.contains("passwd") || lc.contains("pwd") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lc.contains("token") || lc.contains("secret") || lc.contains("apikey") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lc.contains("credential") || lc.contains("authorization"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A tool result that carries {@code success:false} is a logical failure for history stats. */
    private static boolean looksFailed(String result)
    {
        return result != null
            && (result.contains("\"success\":false") //$NON-NLS-1$
                || result.contains("\"success\": false")); //$NON-NLS-1$
    }

    /**
     * Shapes a tool's answer according to the kind of content it produces.
     *
     * @param tool the tool that ran
     * @param arguments the arguments it ran with, needed to name the resource it produced
     * @param rawResult what it produced
     * @param signal a user signal picked up while it ran, or <code>null</code>
     * @param plainTextMode whether rich shapes must degrade to text
     * @return the tools/call result
     */
    private static ToolCallResult shapeResult(IMcpTool tool, Map<String, String> arguments,
        String rawResult, OperatorSignal signal, boolean plainTextMode)
    {
        String result = rawResult != null ? rawResult : EMPTY;

        switch (tool.getResponseType())
        {
        case IMAGE:
            return shapeImage(tool, arguments, result);
        case JSON:
            return shapeStructured(result, signal, plainTextMode);
        case MARKDOWN:
        {
            // The signal rides in the markdown itself, so plain-text mode carries it along.
            String annotated = withMarkdownSignal(result, signal);
            return plainTextMode ? ToolCallResult.text(annotated)
                : ToolCallResult.resource(embeddedUri(tool, arguments), MIME_MARKDOWN, annotated);
        }
        case TEXT:
        default:
            return ToolCallResult.text(withPlainSignal(result, signal));
        }
    }

    /**
     * Shapes the answer of an image tool.
     * <p>
     * An image tool does not always return an image: asked to save the screenshot to disk it returns
     * a confirmation instead, and when it fails it returns an error - both JSON objects. Those are
     * routed to the structured shape, because a client that base64-decoded them would render a broken
     * image. The test is safe: the base64 alphabet has no opening brace, so a real image can never
     * look like a JSON object.
     * </p>
     * <p>
     * Neither plain-text mode nor a user signal applies here: a megabyte of base64 delivered as text
     * would swamp the client, and a signal has nowhere to go inside a binary payload.
     * </p>
     *
     * @param tool the tool that ran
     * @param arguments the arguments it ran with
     * @param result what it produced: base64, or a JSON object
     * @return the tools/call result
     */
    private static ToolCallResult shapeImage(IMcpTool tool, Map<String, String> arguments,
        String result)
    {
        JsonObject payload = asJsonObject(result);
        if (payload != null)
        {
            return ToolCallResult.json(payload);
        }
        return ToolCallResult.resourceBlob(embeddedUri(tool, arguments), MIME_PNG, result);
    }

    /**
     * Shapes a JSON answer as structured content.
     * <p>
     * The payload is parsed rather than passed on as text, so the client receives a real tree. A tool
     * that declares JSON and produces something that is not JSON fails the call - it is a bug in the
     * tool, and hiding it would only move the confusion downstream.
     * </p>
     *
     * @param result the JSON document the tool produced
     * @param signal a user signal to fold in, or <code>null</code>
     * @param plainTextMode whether the structure must be delivered as text
     * @return the tools/call result
     */
    private static ToolCallResult shapeStructured(String result, OperatorSignal signal,
        boolean plainTextMode)
    {
        JsonElement payload = JsonParser.parseString(result);
        if (signal != null)
        {
            attachSignal(payload, signal);
        }
        // Plain-text mode hands the client text instead of a tree, but the text is still the JSON
        // document: the signal was folded into the payload, so what the client reads still parses.
        return plainTextMode ? ToolCallResult.text(GsonHolder.toJson(payload))
            : ToolCallResult.json(payload);
    }

    /**
     * Folds a user signal into a structured payload, if there is an object to fold it into.
     * <p>
     * An array or a primitive is passed through untouched, and a signal never fails a call that has
     * already succeeded.
     * </p>
     *
     * @param payload the parsed tool payload
     * @param signal the signal to attach
     */
    private static void attachSignal(JsonElement payload, OperatorSignal signal)
    {
        try
        {
            if (payload == null || !payload.isJsonObject())
            {
                return;
            }
            JsonObject member = new JsonObject();
            member.addProperty(MEMBER_TYPE, signal.getType() != null ? signal.getType().name() : null);
            member.addProperty(MEMBER_MESSAGE, signal.getMessage());
            payload.getAsJsonObject().add(MEMBER_USER_SIGNAL, member);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not attach the user signal to a tool result", e); //$NON-NLS-1$
        }
    }

    /**
     * Appends a pending user signal to a text answer.
     *
     * @param text the tool's text
     * @param signal the signal, or <code>null</code>
     * @return the text, with the signal spelled out at the end when there is one
     */
    private static String withPlainSignal(String text, OperatorSignal signal)
    {
        if (signal == null)
        {
            return text;
        }
        return text + "\n\n---\n" + SIGNAL_TOKEN + ": " + signalMessage(signal); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Appends a pending user signal to a markdown answer.
     *
     * @param markdown the tool's markdown
     * @param signal the signal, or <code>null</code>
     * @return the markdown, with the signal spelled out at the end when there is one
     */
    private static String withMarkdownSignal(String markdown, OperatorSignal signal)
    {
        if (signal == null)
        {
            return markdown;
        }
        return markdown + "\n\n---\n**" + SIGNAL_TOKEN + ":** " + signalMessage(signal); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Renders a signal for a human reader.
     *
     * @param signal the signal
     * @return its message, falling back to the kind of signal it is when it carries no message
     */
    private static String signalMessage(OperatorSignal signal)
    {
        String message = signal.getMessage();
        if (message != null && !message.isEmpty())
        {
            return message;
        }
        return signal.getType() != null ? signal.getType().name() : EMPTY;
    }

    /**
     * Names the resource a tool produced.
     *
     * @param tool the tool that ran
     * @param arguments the arguments it ran with; tools derive a meaningful name from them
     * @return the resource URI
     */
    private static String embeddedUri(IMcpTool tool, Map<String, String> arguments)
    {
        return EMBEDDED_URI_PREFIX + tool.getResultFileName(arguments);
    }

    /**
     * Reads a string as a JSON object, if that is what it is.
     *
     * @param value the string to inspect; may be <code>null</code>
     * @return the object, or <code>null</code> when the string is anything else
     */
    private static JsonObject asJsonObject(String value)
    {
        if (value == null)
        {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.charAt(0) != '{')
        {
            // Cheap gate, and the reason a megabyte of base64 never reaches the parser.
            return null;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(candidate);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (JsonParseException e)
        {
            return null;
        }
    }

    /**
     * Flattens the arguments of a tools/call into the string map tools are written against.
     * <p>
     * Structure survives as compact JSON; everything else is stringified. A whole number keeps its
     * form on the way through ({@code 10} reaches a tool as {@code "10"}, and stays {@code 10}
     * inside a nested array) because the parser boxes it as a {@link Long} rather than a
     * {@link Double} - see {@link GsonHolder}. An explicit {@code null} is dropped: a tool cannot
     * tell it from an argument that was never sent, and every tool is written for the latter.
     * </p>
     *
     * @param arguments the raw arguments object; may be <code>null</code>
     * @return the flattened arguments, never <code>null</code>
     */
    private static Map<String, String> flattenArguments(Map<String, Object> arguments)
    {
        Map<String, String> flattened = new LinkedHashMap<>();
        if (arguments == null)
        {
            return flattened;
        }
        for (Map.Entry<String, Object> argument : arguments.entrySet())
        {
            Object value = argument.getValue();
            if (value == null)
            {
                continue;
            }
            if (value instanceof String)
            {
                flattened.put(argument.getKey(), (String)value);
            }
            else if (value instanceof List || value instanceof Map)
            {
                flattened.put(argument.getKey(), GsonHolder.toJson(value));
            }
            else
            {
                flattened.put(argument.getKey(), String.valueOf(value));
            }
        }
        return flattened;
    }

    /**
     * Tells the agent that the tool it asked for exists but has been switched off, and what to do
     * about it.
     *
     * @param toolName the tool that was called
     * @return the message to hand back
     */
    private static String disabledToolMessage(String toolName)
    {
        return ToolGate.disabledMessage(toolName);
    }

    /**
     * Reads the plain-text preference, which collapses rich answers into a single text block for
     * clients that render nothing else.
     *
     * @return <code>true</code> when the mode is on; the default when there is no preference store to
     *         ask, which is the case outside a running workbench
     */
    private static boolean isPlainTextMode()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return PrefKeys.DEFAULT_PLAIN_TEXT_MODE;
        }
        IPreferenceStore preferences = activator.getPreferenceStore();
        if (preferences == null)
        {
            return PrefKeys.DEFAULT_PLAIN_TEXT_MODE;
        }
        return preferences.getBoolean(PrefKeys.PREF_PLAIN_TEXT_MODE);
    }

    /**
     * Takes the signal the user raised while a tool was running, if any. Reading it clears it, so ask
     * once per call.
     *
     * @return the signal, or <code>null</code>
     */
    private static OperatorSignal consumeUserSignal()
    {
        McpHttpEndpoint server = getServer();
        return server != null ? server.consumeUserSignal() : null;
    }

    /**
     * Returns the running server.
     *
     * @return the server, or <code>null</code> when the plugin is not up
     */
    private static McpHttpEndpoint getServer()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getMcpServer() : null;
    }

    /**
     * Renders a successful response.
     *
     * @param requestId the id to echo
     * @param result the payload
     * @return the response document
     */
    private static String answer(Object requestId, Object result)
    {
        return GsonHolder.toJson(JsonRpcResponse.success(requestId, result));
    }

    /**
     * Renders a failed response.
     *
     * @param requestId the id to echo
     * @param code one of the {@code McpServerMeta.ERROR_*} codes
     * @param message what went wrong; may be <code>null</code>
     * @return the response document
     */
    private static String failure(Object requestId, int code, String message)
    {
        return GsonHolder.toJson(JsonRpcResponse.error(requestId, code, message));
    }
}
