/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Boils a structured answer down to the one line that rides in the text block beside it.
 * <p>
 * A structured result carries its payload in {@code structuredContent} and must still ship a
 * {@code content} array, because MCP says so. That array used to hold the word "Done". Every client
 * that renders content and ignores structured output therefore saw one word - and so did the agents
 * driving them, which is how a refusal that carefully explained which parameter was wrong reached its
 * caller as "Done" and was read as "the tool returned nothing".
 * </p>
 * <p>
 * The whole payload is deliberately NOT repeated here. A metadata answer runs to tens of kilobytes and
 * duplicating it would double every response for the clients that read both blocks. What goes in is a
 * line: the failure text when the answer is a failure, and otherwise what the answer is about.
 * </p>
 */
public final class PayloadSummary
{
    /** Beyond this the line stops being a summary. */
    private static final int MAX_LENGTH = 400;

    /** How many fields of a successful answer are worth naming. */
    private static final int MAX_FIELDS = 6;

    /** Said of an answer with nothing in it to say. */
    private static final String NOTHING_TO_SAY = "Done"; //$NON-NLS-1$

    /** Carried by every answer and never worth printing: it is what the rest of the line reflects. */
    private static final String SUCCESS = "success"; //$NON-NLS-1$

    private static final String ERROR = "error"; //$NON-NLS-1$

    private static final String OPERATION = "operation"; //$NON-NLS-1$

    /** The member a pending operator signal is folded into before the answer goes out. */
    private static final String USER_SIGNAL = "userSignal"; //$NON-NLS-1$

    /** Literal, and load bearing: agents key on this token wherever a signal is rendered. */
    private static final String SIGNAL_TOKEN = "USER SIGNAL"; //$NON-NLS-1$

    private PayloadSummary()
    {
        // static utility
    }

    /**
     * Returns the line to put in the text block for a structured payload.
     *
     * @param payload the answer, already parsed; may be <code>null</code>
     * @return a single line, never <code>null</code> and never empty
     */
    public static String of(JsonElement payload)
    {
        if (payload == null || !payload.isJsonObject())
        {
            return describeNonObject(payload);
        }
        JsonObject object = payload.getAsJsonObject();

        String failure = failureText(object);
        // The failure text goes out whole and first. This is the case the class exists for: an
        // invisible error is worse than an invisible result, because the caller concludes the tool
        // is broken rather than that the call was wrong.
        String line = failure != null ? clip(failure) : clip(describeSuccess(object));
        return withSignal(line, object);
    }

    /**
     * Appends the operator's signal, if the answer carries one.
     * <p>
     * Appended after the line is clipped rather than folded into it: the signal is the operator
     * asking for something - cancel, retry, take another route - and it must not be the part that
     * falls off the end. It is the only nested member printed here, and it is printed even when the
     * answer is a failure, because a signal that arrived while the call was failing is exactly the
     * one worth reading.
     * </p>
     *
     * @param line the summary so far
     * @param object the answer
     * @return the line, with the signal appended when there is one
     */
    private static String withSignal(String line, JsonObject object)
    {
        JsonElement signal = object.get(USER_SIGNAL);
        if (signal == null || !signal.isJsonObject())
        {
            return line;
        }
        JsonObject fields = signal.getAsJsonObject();
        String type = asText(fields.get("type")); //$NON-NLS-1$
        String message = asText(fields.get("message")); //$NON-NLS-1$
        if (type == null && message == null)
        {
            return line;
        }
        StringBuilder tail = new StringBuilder(line).append(" | ").append(SIGNAL_TOKEN).append(": "); //$NON-NLS-1$ //$NON-NLS-2$
        tail.append(type != null ? type : ""); //$NON-NLS-1$
        if (message != null)
        {
            tail.append(type != null ? " - " : "").append(message); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return tail.toString();
    }

    private static String asText(JsonElement value)
    {
        if (value == null || !value.isJsonPrimitive())
        {
            return null;
        }
        String text = value.getAsString();
        return text.isEmpty() ? null : text;
    }

    /**
     * Returns the failure message when the answer is a failure.
     *
     * @param object the answer
     * @return the message, or <code>null</code> when the answer is not a failure
     */
    private static String failureText(JsonObject object)
    {
        JsonElement success = object.get(SUCCESS);
        boolean failed = success != null && success.isJsonPrimitive()
            && !success.getAsJsonPrimitive().getAsBoolean();
        if (!failed)
        {
            return null;
        }
        JsonElement error = object.get(ERROR);
        String text = error != null && error.isJsonPrimitive() ? error.getAsString() : null;
        return text != null && !text.isEmpty() ? text : "The operation failed."; //$NON-NLS-1$
    }

    /**
     * Names what a successful answer is about: the operation, then the fields that carry its shape.
     *
     * @param object the answer
     * @return the line
     */
    private static String describeSuccess(JsonObject object)
    {
        StringBuilder line = new StringBuilder();
        JsonElement operation = object.get(OPERATION);
        if (operation != null && operation.isJsonPrimitive())
        {
            line.append(operation.getAsString()).append(": "); //$NON-NLS-1$
        }
        int named = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
        {
            if (named >= MAX_FIELDS)
            {
                break;
            }
            String key = entry.getKey();
            if (SUCCESS.equals(key) || OPERATION.equals(key))
            {
                continue;
            }
            String value = describeValue(entry.getValue());
            if (value == null)
            {
                continue;
            }
            if (named > 0)
            {
                line.append(", "); //$NON-NLS-1$
            }
            line.append(key).append('=').append(value);
            named++;
        }
        if (named == 0)
        {
            return line.length() > 0 ? line.append("done").toString() : NOTHING_TO_SAY; //$NON-NLS-1$
        }
        return line.toString();
    }

    /**
     * Renders one field for the line.
     * <p>
     * A collection becomes its size, because the size is the fact worth having at a glance and the
     * contents are a click away in the structured block. A nested object is skipped: naming it would
     * cost a line and say nothing.
     * </p>
     *
     * @param value the field value
     * @return the rendering, or <code>null</code> when the field is not worth naming
     */
    private static String describeValue(JsonElement value)
    {
        if (value == null || value.isJsonNull())
        {
            return null;
        }
        if (value.isJsonArray())
        {
            return String.valueOf(value.getAsJsonArray().size());
        }
        if (value.isJsonObject())
        {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        String text = primitive.getAsString();
        if (text.isEmpty())
        {
            return null;
        }
        // A long string here is a message or a path, and either is more useful cut short than
        // dropped: the reader learns which one it is and can go to the payload for the rest.
        return text.length() > 80 ? text.substring(0, 77) + "..." : text; //$NON-NLS-1$
    }

    /**
     * Describes an answer that is not an object at all.
     *
     * @param payload the answer; may be <code>null</code>
     * @return the line
     */
    private static String describeNonObject(JsonElement payload)
    {
        if (payload == null || payload.isJsonNull())
        {
            return NOTHING_TO_SAY;
        }
        if (payload.isJsonArray())
        {
            JsonArray array = payload.getAsJsonArray();
            return array.size() + (array.size() == 1 ? " item" : " items"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return clip(payload.getAsString());
    }

    private static String clip(String line)
    {
        String flat = line.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.isEmpty())
        {
            return NOTHING_TO_SAY;
        }
        return flat.length() > MAX_LENGTH ? flat.substring(0, MAX_LENGTH - 3) + "..." : flat; //$NON-NLS-1$
    }
}
