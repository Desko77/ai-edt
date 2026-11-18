/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * One unit of a pageable result sequence.
 * <p>
 * The window pages a result as an ordered sequence of units rather than as characters. A unit is
 * whatever the operation would otherwise have returned in one piece - a source line, a metadata
 * record, a coverage note, an outline block, a rendered block of an aggregated answer. Keeping the
 * boundary here, and not in the transport, is what makes "one more call shows one more thing"
 * provable: every continuation hands over whole units, and a unit is shortened only when it alone
 * is larger than the whole character ceiling.
 * </p>
 * <p>
 * The value is deep-copied through the shared Gson instance at construction, so a caller that keeps
 * mutating its own list or map cannot change a pinned selection. It is serialized once, and that
 * exact serialization is what the window measures: escaping and Unicode therefore count for what
 * the client actually receives, not for the original string.
 * </p>
 */
public final class ResultUnit
{
    /** What a unit is in the response: a result record, coverage, or an additional block. */
    public enum Kind
    {
        /** A record of the result itself. */
        RESULT,
        /** A coverage record: an unvisited area, a skipped phase, a shortened unit. */
        COVERAGE,
        /** An additional block of the answer: an outline, a form block, a rendered section. */
        BLOCK
    }

    private final Kind kind;

    private final JsonElement value;

    private final String json;

    private ResultUnit(Kind kind, Object value)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("a result unit needs a value"); //$NON-NLS-1$
        }
        this.kind = kind;
        // One serialization serves both the pinned content and the size measurement. Going through
        // Gson here also detaches the unit from any mutable collection the caller still holds.
        String serialized = GsonHolder.toJson(value);
        this.value = JsonParser.parseString(serialized);
        this.json = serialized;
    }

    /**
     * A unit of the result itself.
     *
     * @param value its JSON-ready content, never {@code null}
     * @return the unit
     */
    public static ResultUnit of(Object value)
    {
        return new ResultUnit(Kind.RESULT, value);
    }

    /**
     * A coverage unit.
     *
     * @param value its JSON-ready content, never {@code null}
     * @return the unit
     */
    public static ResultUnit ofCoverage(Object value)
    {
        return new ResultUnit(Kind.COVERAGE, value);
    }

    /**
     * An additional block of the answer.
     *
     * @param value its JSON-ready content, never {@code null}
     * @return the unit
     */
    public static ResultUnit ofBlock(Object value)
    {
        return new ResultUnit(Kind.BLOCK, value);
    }

    /**
     * What this unit is.
     *
     * @return the kind
     */
    public Kind kind()
    {
        return kind;
    }

    /**
     * The unit's content, as a detached copy.
     *
     * @return a copy of the value
     */
    public JsonElement value()
    {
        return value.deepCopy();
    }

    /**
     * The unit's content exactly as the window measures and emits it.
     *
     * @return the serialized JSON value
     */
    public String json()
    {
        return json;
    }

    /**
     * Whether the content is a JSON string.
     * <p>
     * A text unit is shortened by cutting its text; any other unit is shortened by cutting its
     * serialized form. This says which of the two a page will be cutting.
     * </p>
     *
     * @return {@code true} for a text unit
     */
    public boolean isText()
    {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    /**
     * The content of a text unit.
     *
     * @return the text, or {@code null} when the unit is not text
     */
    public String text()
    {
        return isText() ? value.getAsString() : null;
    }

    /**
     * The length of a text unit in characters, in the sense the response ceiling counts them.
     *
     * @return the length, or 0 when the unit is not text
     */
    public int textLength()
    {
        String text = text();
        return text == null ? 0 : text.length();
    }

    /**
     * Replaces a text unit's content, keeping its kind.
     * <p>
     * Used by the window for the one case where a unit cannot be handed over whole. Package-private
     * on purpose: a truncated unit is a fact of one page, not a new value the pinned selection
     * should be rewritten with.
     * </p>
     *
     * @param replacement the shortened text
     * @return a unit of the same kind carrying the replacement
     */
    ResultUnit withText(String replacement)
    {
        return new ResultUnit(kind, replacement);
    }
}
