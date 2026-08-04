/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Map;

import ru.aiedt.mcp.server.wire.JsonUtils;

/**
 * Reads a timeout in seconds under one canonical argument name, {@code timeoutSeconds},
 * while still accepting the legacy names some tools shipped with so no existing caller
 * breaks.
 * <p>
 * Precedence when more than one is present: {@code timeoutSeconds} (the canonical name)
 * wins, then {@code timeoutMs} (milliseconds, rounded to the nearest second), then
 * {@code timeout} (seconds). A name that is present but unparseable is skipped and the
 * next name is tried; when none yields a value the caller's default is used. The result
 * is clamped to the caller's bounds.
 */
public final class TimeoutArgs
{
    /** The one name every tool now accepts for a timeout. */
    public static final String CANONICAL = "timeoutSeconds"; //$NON-NLS-1$

    private TimeoutArgs()
    {
    }

    /**
     * Reads a timeout in seconds.
     *
     * @param params the tool arguments
     * @param defaultSeconds the value when no timeout argument is given
     * @param minSeconds the lower bound; a smaller value is raised to it
     * @param maxSeconds the upper bound, or {@code 0} (or negative) for no upper bound
     * @return the timeout in seconds, within {@code [minSeconds, maxSeconds]}
     */
    public static int readSeconds(Map<String, String> params, int defaultSeconds, int minSeconds,
        int maxSeconds)
    {
        Integer raw = rawSeconds(params);
        int seconds = raw != null ? raw.intValue() : defaultSeconds;
        if (seconds < minSeconds)
        {
            seconds = minSeconds;
        }
        if (maxSeconds > 0 && seconds > maxSeconds)
        {
            seconds = maxSeconds;
        }
        return seconds;
    }

    /**
     * The requested seconds from whichever accepted name is present, or {@code null}
     * when none is given (or none parses as an integer).
     */
    private static Integer rawSeconds(Map<String, String> params)
    {
        Integer seconds = JsonUtils.extractIntegerArgument(params, CANONICAL);
        if (seconds != null)
        {
            return seconds;
        }
        Integer millis = JsonUtils.extractIntegerArgument(params, "timeoutMs"); //$NON-NLS-1$
        if (millis != null)
        {
            return Integer.valueOf((int) Math.round(millis.intValue() / 1000.0));
        }
        return JsonUtils.extractIntegerArgument(params, "timeout"); //$NON-NLS-1$
    }
}
