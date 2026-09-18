/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * Whether a tool's answer reads as a failure, for the two callers that have to know.
 * <p>
 * A tool reports trouble in more than one way, because a tool answers in more than one shape: a
 * JSON body carries {@code success:false}, a text or markdown answer opens with {@code Error:},
 * {@code write_module_source} says it failed while writing, {@code edit_form} answers in yaml. None
 * of them throws - a refusal is a normal answer here - so anything that needs to tell success from
 * failure has to read the shape.
 * </p>
 * <p>
 * It lives in one place because it used to live in two. The idempotency store knew all four shapes;
 * the request router carried its own copy that knew only the JSON one, so a tool refusing in plain
 * text was recorded in the call history as having succeeded - and the history window's "failures
 * only" filter hid exactly the calls somebody opened it to find. Two copies of a rule are two
 * answers to the same question, and the one nobody is looking at is the one that goes stale.
 * </p>
 * <p>
 * Conservative on purpose: only well-known failure shapes count. For the idempotency store a false
 * positive is the expensive direction - a success read as a failure evicts the entry and lets a
 * retry mutate twice - while a false negative only costs a fresh operation id.
 * </p>
 */
public final class FailureShape
{
    /**
     * The {@code edit_form} yaml header's verdict, anchored to the start of a line.
     * <p>
     * Anchored, and not searched for anywhere, because this is asked of EVERY tool's answer now,
     * including the source of a module. A search that matched the phrase wherever it appeared would
     * read a module containing it as a failed call - and put a successful read into the history as
     * a failure, where the "failures only" filter would show it as a defect that never happened.
     * The two shapes above are anchored for the same reason: both are written at the very start of
     * the answer they belong to.
     * </p>
     */
    private static final java.util.regex.Pattern STATUS_ERROR_LINE =
        java.util.regex.Pattern.compile("^status:\\s*error\\s*$", java.util.regex.Pattern.MULTILINE);

    private FailureShape()
    {
        // Utility.
    }

    /**
     * Reads a tool's answer as success or failure.
     *
     * @param result what the tool returned; <code>null</code> counts as a failure, since a tool
     *            that returned nothing did not do the thing
     * @return whether the answer reads as a failure
     */
    public static boolean looksFailed(String result)
    {
        if (result == null)
        {
            return true;
        }
        Boolean own = topLevelSuccess(result);
        if (own != null)
        {
            return !own.booleanValue();
        }
        if (result.contains("\"success\":false") || result.contains("\"success\": false")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        String head = result.stripLeading();
        if (head.startsWith("Error:") || head.startsWith("Failed while writing")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        return STATUS_ERROR_LINE.matcher(result).find();
    }

    /**
     * The answer's own {@code success}, read as the key of the outermost object.
     * <p>
     * An answer carries other answers: a call history listing embeds the recorded result of every
     * call it lists, a batch its per-item results, a multi-target termination its per-launch rows.
     * A search for {@code "success":false} anywhere in the text reads one of those as the verdict
     * of the whole, so a listing that contains one failed call is handed to the client with
     * {@code isError} and recorded in the history as a failure itself - where the "failures only"
     * filter shows a defect that never happened. The idempotency store reads the same answer, and
     * a success read as a failure evicts the entry, which is what lets a retry mutate twice.
     * </p>
     * <p>
     * Nested text is skipped rather than searched: the scan walks strings whole, so an escaped
     * {@code \"success\":false} inside a recorded result is passed over, and only depth one is
     * read.
     * </p>
     *
     * @param result the answer.
     * @return the verdict the answer states about itself, or <code>null</code> when it is not a
     *         JSON object or states none
     */
    static Boolean topLevelSuccess(String result)
    {
        int length = result.length();
        int at = 0;
        while (at < length && Character.isWhitespace(result.charAt(at)))
        {
            at++;
        }
        if (at >= length || result.charAt(at) != '{')
        {
            return null;
        }
        at++;
        int depth = 1;
        while (at < length)
        {
            char c = result.charAt(at);
            if (c == '"')
            {
                int end = endOfString(result, at);
                if (end < 0)
                {
                    return null;
                }
                if (depth == 1)
                {
                    Boolean stated = successAfterKey(result, at + 1, end);
                    if (stated != null)
                    {
                        return stated;
                    }
                }
                at = end + 1;
                continue;
            }
            if (c == '{' || c == '[')
            {
                depth++;
            }
            else if (c == '}' || c == ']')
            {
                depth--;
                if (depth == 0)
                {
                    return null;
                }
            }
            at++;
        }
        return null;
    }

    /**
     * The value of the key that spans the given text, when that key is {@code success}.
     *
     * @param result the answer.
     * @param from the first character of the key, past its opening quote.
     * @param quote the closing quote of the key.
     * @return the stated value, or <code>null</code> when this is a different key, not a key at
     *         all, or not one of the two literals
     */
    private static Boolean successAfterKey(String result, int from, int quote)
    {
        if (!result.regionMatches(from, "success", 0, quote - from) //$NON-NLS-1$
            || quote - from != "success".length()) //$NON-NLS-1$
        {
            return null;
        }
        int at = skipSpace(result, quote + 1);
        if (at >= result.length() || result.charAt(at) != ':')
        {
            return null;
        }
        at = skipSpace(result, at + 1);
        if (result.startsWith("false", at)) //$NON-NLS-1$
        {
            return Boolean.FALSE;
        }
        if (result.startsWith("true", at)) //$NON-NLS-1$
        {
            return Boolean.TRUE;
        }
        return null;
    }

    private static int skipSpace(String text, int from)
    {
        int at = from;
        while (at < text.length() && Character.isWhitespace(text.charAt(at)))
        {
            at++;
        }
        return at;
    }

    /**
     * The closing quote of the string that opens at the given position.
     *
     * @param text the answer.
     * @param quote the opening quote.
     * @return the index of the closing quote, or -1 when the string never closes
     */
    private static int endOfString(String text, int quote)
    {
        for (int at = quote + 1; at < text.length(); at++)
        {
            char c = text.charAt(at);
            if (c == '\\')
            {
                at++;
                continue;
            }
            if (c == '"')
            {
                return at;
            }
        }
        return -1;
    }
}
