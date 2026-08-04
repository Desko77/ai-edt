/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * A tool call that is still running, and the connection the agent is waiting on.
 * <p>
 * It exists so that a user can answer a call the agent is blocked on without waiting for the tool to
 * finish. The status bar hands a {@link OperatorSignal} to {@link #sendSignalResponse(OperatorSignal)}, which
 * answers the agent there and then; the tool itself keeps running inside EDT and its result is thrown
 * away when it eventually arrives.
 * </p>
 * <p>
 * Two threads race for this connection - the one that is running the tool and the one the user is
 * clicking on - and only one answer may go out. A one-shot latch decides who wins, so the loser
 * writes nothing at all rather than half a document on top of someone else's.
 * </p>
 */
public class RunningToolCall
{
    private static final String MEMBER_JSONRPC = "jsonrpc"; //$NON-NLS-1$

    private static final String MEMBER_RESULT = "result"; //$NON-NLS-1$

    private static final String MEMBER_ID = "id"; //$NON-NLS-1$

    private static final String MEMBER_CONTENT = "content"; //$NON-NLS-1$

    private static final String MEMBER_TYPE = "type"; //$NON-NLS-1$

    private static final String MEMBER_TEXT = "text"; //$NON-NLS-1$

    private static final String CONTENT_TYPE_TEXT = "text"; //$NON-NLS-1$

    private static final String HEADER_CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$

    private static final String MIME_JSON = "application/json"; //$NON-NLS-1$

    /**
     * Literal, and load bearing. The protocol layer stamps the same token on a signal it delivers the
     * other way, and agents are trained to look for it. Do not translate or decorate it.
     */
    private static final String SIGNAL_TOKEN = "USER SIGNAL"; //$NON-NLS-1$

    private static final int HTTP_OK = 200;

    private static final long MILLIS_PER_SECOND = 1000L;

    private final HttpExchange exchange;

    private final String toolName;

    private final Object requestId;

    private final long startedAt;

    /** Set exactly once, by whoever answers first. */
    private final AtomicBoolean responded = new AtomicBoolean();

    /**
     * The cancellation flag for this call, owned from construction so it exists before the call is
     * ever made the active one - the request thread can raise it (on a cancel signal) and the worker
     * thread's scope reads the very same instance, with no window in between.
     */
    private final ToolCallScope.Cancellation cancellation = new ToolCallScope.Cancellation();

    /**
     * Takes charge of the connection a tool call arrived on.
     *
     * @param exchange the open connection the agent is waiting on
     * @param toolName the tool being run, as the user will see it named
     * @param requestId the JSON-RPC id to answer with: a {@link String}, a {@link Number}, or
     *            <code>null</code> when the request carried none
     */
    public RunningToolCall(HttpExchange exchange, String toolName, Object requestId)
    {
        this.exchange = exchange;
        this.toolName = toolName;
        this.requestId = requestId;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * The cancellation flag for this call. The scope created for the call shares this same instance,
     * so a cooperative loop that reads it through the scope sees a cancel raised here.
     *
     * @return the call's cancellation flag, never <code>null</code>
     */
    public ToolCallScope.Cancellation cancellation()
    {
        return cancellation;
    }

    /**
     * Returns the tool being run.
     *
     * @return the tool name
     */
    public String getToolName()
    {
        return toolName;
    }

    /**
     * Returns the JSON-RPC id this call must be answered with.
     *
     * @return a {@link String}, a {@link Number}, or <code>null</code>
     */
    public Object getRequestId()
    {
        return requestId;
    }

    /**
     * Returns how long the call has been running.
     *
     * @return whole seconds since the call was registered
     */
    public long getElapsedSeconds()
    {
        return (System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND;
    }

    /**
     * Tells whether this call has already been answered.
     *
     * @return <code>true</code> once an answer has gone out, or been attempted and failed
     */
    public boolean hasResponded()
    {
        return responded.get();
    }

    /**
     * Answers the waiting agent with the user's signal instead of the tool's result.
     * <p>
     * The connection is closed on the way out whatever happens. That is what keeps it from leaking:
     * the thread running the tool comes back later, finds the call already answered, and knows to
     * leave the connection alone.
     * </p>
     *
     * @param signal what the user wants the agent to do
     * @return <code>true</code> when the signal reached the agent; <code>false</code> when this call
     *         had already been answered, or when the connection broke while answering it
     */
    public synchronized boolean sendSignalResponse(OperatorSignal signal)
    {
        if (!responded.compareAndSet(false, true))
        {
            return false;
        }
        try
        {
            write(buildSignalDocument(signal));
            Activator.logInfo("User signal answered the pending call to tool: " + toolName); //$NON-NLS-1$
            return true;
        }
        catch (IOException e)
        {
            // The latch stays set: there is no second connection to try, and the tool thread must
            // still be told to keep its hands off this one.
            Activator.logError("Could not deliver the user signal for tool: " + toolName, e); //$NON-NLS-1$
            return false;
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Answers the waiting agent with an ordinary result document, under the same one-shot latch.
     *
     * @param response the JSON-RPC document to send
     * @return <code>true</code> when it was sent; <code>false</code> when this call had already been
     *         answered, or when the connection broke while answering it
     */
    public synchronized boolean sendNormalResponse(String response)
    {
        if (!responded.compareAndSet(false, true))
        {
            return false;
        }
        try
        {
            write(response);
            return true;
        }
        catch (IOException e)
        {
            Activator.logError("Could not deliver the result of tool: " + toolName, e); //$NON-NLS-1$
            return false;
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Writes a document to the waiting agent.
     *
     * @param document the JSON document
     * @throws IOException when the connection is gone
     */
    private void write(String document) throws IOException
    {
        byte[] body = document.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, MIME_JSON);
        exchange.sendResponseHeaders(HTTP_OK, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /**
     * Builds the answer the agent receives in place of the tool's result.
     * <p>
     * It is the plainest successful tool result there is - one block of text, no structured content,
     * no embedded resource - because it has to be understood by every client, whatever the interrupted
     * tool would have answered with.
     * </p>
     *
     * @param signal the user's signal
     * @return the JSON-RPC document
     */
    private String buildSignalDocument(OperatorSignal signal)
    {
        JsonObject textItem = new JsonObject();
        textItem.addProperty(MEMBER_TYPE, CONTENT_TYPE_TEXT);
        textItem.addProperty(MEMBER_TEXT, buildSignalText(signal));

        JsonArray content = new JsonArray();
        content.add(textItem);

        JsonObject result = new JsonObject();
        result.add(MEMBER_CONTENT, content);

        JsonObject document = new JsonObject();
        document.addProperty(MEMBER_JSONRPC, McpServerMeta.JSONRPC_VERSION);
        document.add(MEMBER_RESULT, result);
        if (requestId instanceof String)
        {
            document.addProperty(MEMBER_ID, (String)requestId);
        }
        else if (requestId instanceof Number)
        {
            document.addProperty(MEMBER_ID, (Number)requestId);
        }
        // An id of any other kind, null included, is left out: an id the client cannot match is
        // worse than no id at all.
        return GsonHolder.toJson(document);
    }

    /**
     * Writes out the signal for the agent to read.
     * <p>
     * The closing note is not a formality. A cancel signal raises the call's cancellation flag, so a
     * cooperative operation stops at its next checkpoint - but work already inside EDT still runs to
     * the end, and any other signal leaves the tool running untouched. Either way the result is
     * discarded; an agent that assumed the operation had been fully undone would act on a false
     * premise, so the note spells out what actually happened.
     * </p>
     *
     * @param signal the user's signal
     * @return the text of the answer
     */
    private String buildSignalText(OperatorSignal signal)
    {
        String type = signal.getType() != null ? signal.getType().name() : ""; //$NON-NLS-1$
        String message = signal.getMessage();
        if (message == null || message.isEmpty())
        {
            message = type;
        }
        // A cancel now raises the call's cancellation flag, so a cooperative operation (a broad code
        // search, for one) stops at its next checkpoint. Work already inside EDT still runs to the end.
        // Any other signal leaves the tool running untouched. Either way the result is discarded and
        // the signal is the agent's answer.
        boolean cancel = signal.getType() == OperatorSignal.SignalType.CANCEL;
        String tail = cancel
            ? "A cancel was requested: cooperative operations stop at their next checkpoint; work" //$NON-NLS-1$
                + " already running inside EDT finishes and its result is discarded. Take this signal" //$NON-NLS-1$
                + " as your answer to the call." //$NON-NLS-1$
            : "The tool was interrupted, not cancelled: the EDT operation behind it is still running" //$NON-NLS-1$
                + " and its result will be discarded. Take this signal as your answer to the call."; //$NON-NLS-1$
        return String.format("%s: %s%n%n" //$NON-NLS-1$
            + "Signal: %s%n" //$NON-NLS-1$
            + "Tool: %s%n" //$NON-NLS-1$
            + "Running for: %d s%n%n" //$NON-NLS-1$
            + "%s", //$NON-NLS-1$
            SIGNAL_TOKEN, message, type, toolName, Long.valueOf(getElapsedSeconds()), tail);
    }
}
