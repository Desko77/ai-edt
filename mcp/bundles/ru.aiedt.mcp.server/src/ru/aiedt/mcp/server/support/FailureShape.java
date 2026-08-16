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
}
