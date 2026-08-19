/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The one mark that says an answer is a Pending envelope rather than an answer that mentions one.
 * <p>
 * Eight places build this envelope, and the router has to recognise it in order to turn it into a
 * task. It recognised it by looking for the words {@code "Pending"} and {@code "runKey"} in the
 * text, and only in answers shorter than four thousand characters - two guesses, each of which
 * fails in its own direction. An envelope that grows past the limit stops being a task, silently,
 * because it got longer. An ordinary answer that happens to quote those words gets parsed as one.
 * </p>
 * <p>
 * A field nobody else writes settles it. Producers stamp it, the router looks for it, and neither
 * has to reason about length or about which words might appear in somebody's message text.
 * </p>
 */
public final class PendingEnvelope
{
    /** The member that marks the envelope. Written by producers, read by the router. */
    public static final String MARK = "pendingEnvelope"; //$NON-NLS-1$

    /** What the mark looks like in the serialised answer, for a cheap pre-check. */
    public static final String MARK_IN_JSON = "\"pendingEnvelope\":true"; //$NON-NLS-1$

    private PendingEnvelope()
    {
    }

    /**
     * Stamps a result as a Pending envelope.
     *
     * @param result the envelope being built.
     * @return the same result, for chaining
     */
    public static ToolResult mark(ToolResult result)
    {
        return result.put(MARK, true);
    }

    /**
     * Whether an answer could be a Pending envelope, judged before it is parsed.
     * <p>
     * Cheap and exact: the mark is a fixed string no other answer writes, so this rejects
     * everything else without parsing, and rejects nothing that is one - however long it has
     * grown.
     * </p>
     *
     * @param result the serialised answer; may be <code>null</code>.
     * @return true when it carries the mark
     */
    public static boolean isCandidate(String result)
    {
        return result != null && result.contains(MARK_IN_JSON);
    }
}
