/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.settings.HistorySettings;
import ru.aiedt.mcp.server.support.HistoryJournal;

/**
 * Bounded in-memory ring buffer of recent MCP tool calls, for observability: which tools ran, with
 * what arguments, how long, success or failure. Two readers exist - the {@code get_mcp_history} tool,
 * for an agent, and the call-history dialog, for a person. Thread-safe.
 * <p>
 * How much is kept - whether at all, how many calls, how many characters of the arguments and of the
 * response - comes from {@link HistorySettings}, read afresh on each call so a change takes effect on
 * the next one without a restart. The shipped values are the ones this buffer had before any of it
 * was settable, so a workspace that never touches the settings behaves exactly as before.
 * </p>
 * <p>
 * The buffer itself is memory only and goes with the session. Surviving a restart is what the
 * optional {@link HistoryJournal} file is for.
 * </p>
 */
public final class McpHistory
{
    private static final Deque<Record> RING = new ArrayDeque<>();

    private McpHistory()
    {
    }

    /**
     * Truncates a value to {@code limit} chars, appending an ellipsis when cut.
     * <p>
     * A limit of zero keeps nothing, and keeping nothing means an empty string. Appending the
     * ellipsis there would store three characters for a setting that asked for none, and the reader
     * would be shown a value where the answer is that they chose not to keep one.
     * </p>
     *
     * @param value the text, or <code>null</code>
     * @param limit how many characters to keep
     * @return the kept text
     */
    public static String truncate(String value, int limit)
    {
        if (value == null)
        {
            return null;
        }
        if (limit <= 0)
        {
            return ""; //$NON-NLS-1$
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "..."; //$NON-NLS-1$
    }

    /**
     * Records one tool call whose arguments arrived whole.
     *
     * @param toolName the tool that ran
     * @param argSummary its arguments, already flattened and with credentials masked
     * @param resultSummary what it answered, in full - this is where the response is cut to size
     * @param durationMs how long it took
     * @param success whether it worked
     */
    public static void record(String toolName, String argSummary, String resultSummary, long durationMs,
        boolean success)
    {
        record(toolName, argSummary, false, resultSummary, durationMs, success);
    }

    /**
     * Records one tool call (called from the dispatch path after the tool ran).
     *
     * @param toolName the tool that ran
     * @param argSummary its arguments, already flattened and with credentials masked
     * @param argsAlreadyCut whether flattening the arguments shortened any of them - the caller has
     *            to say so, because a summary that lost a long value inside it can still come out
     *            shorter than the extent, and comparing lengths here would then call it whole
     * @param resultSummary what it answered, in full - this is where the response is cut to size
     * @param durationMs how long it took
     * @param success whether it worked
     */
    public static void record(String toolName, String argSummary, boolean argsAlreadyCut, String resultSummary,
        long durationMs, boolean success)
    {
        HistorySettings settings = HistorySettings.current();
        if (!settings.isEnabled())
        {
            return;
        }
        Record entry = new Record(toolName, truncate(argSummary, settings.argChars()),
            truncate(resultSummary, settings.resultChars()), System.currentTimeMillis(), durationMs, success,
            argsAlreadyCut || (argSummary != null && argSummary.length() > settings.argChars()),
            resultSummary == null ? 0 : resultSummary.length());
        add(entry, settings.depth());
        if (settings.isFileEnabled())
        {
            // Outside the lock: the journal touches the disk, and holding the buffer while it does
            // would stall every other tool call behind one slow write.
            HistoryJournal.append(entry.toMap(), settings.isFileRedacted());
        }
    }

    /**
     * Adds an entry and brings the buffer down to the depth in force.
     * <p>
     * Trimming here rather than at the moment the setting changes is what makes a reduced depth take
     * effect without anything having to notice the change: the buffer shrinks as calls arrive.
     * </p>
     *
     * @param entry the call to keep
     * @param depth how many calls may be kept
     */
    private static synchronized void add(Record entry, int depth)
    {
        RING.addLast(entry);
        while (RING.size() > depth)
        {
            RING.removeFirst();
        }
    }

    /**
     * How many calls the buffer is currently allowed to hold.
     *
     * @return the depth in force
     */
    public static int capacity()
    {
        return HistorySettings.current().depth();
    }

    /**
     * What the trim bar says when no call is running: which tool ran last and when.
     */
    public static final class LastCall
    {
        /** The tool that ran. */
        public final String toolName;

        /** When it was recorded, in epoch milliseconds. */
        public final long timestamp;

        LastCall(String toolName, long timestamp)
        {
            this.toolName = toolName;
            this.timestamp = timestamp;
        }
    }

    /**
     * Returns the most recently served call.
     * <p>
     * Reading it through {@link #recent(int)} would mean building a map to take two values out of
     * it, on a timer, for a label - so the buffer answers this one directly.
     * </p>
     *
     * @return the last call, or <code>null</code> when nothing has been called yet
     */
    public static synchronized LastCall lastCall()
    {
        Record last = RING.peekLast();
        return last == null ? null : new LastCall(last.toolName, last.timestamp);
    }

    /** Returns the most recent calls, newest first (up to {@code limit}). */
    public static synchronized List<Map<String, Object>> recent(int limit)
    {
        int n = (limit > 0 ? Math.min(limit, RING.size()) : RING.size());
        List<Record> snap = new ArrayList<>(RING);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = snap.size() - 1; i >= 0 && out.size() < n; i--)
        {
            out.add(snap.get(i).toMap());
        }
        return out;
    }

    /** Aggregate counts over the buffered calls (total, success/failure, by tool, total ms). */
    public static synchronized Map<String, Object> stats()
    {
        Map<String, Integer> byTool = new LinkedHashMap<>();
        int ok = 0;
        int fail = 0;
        long totalMs = 0;
        for (Record r : RING)
        {
            byTool.merge(r.toolName, 1, Integer::sum);
            if (r.success)
            {
                ok++;
            }
            else
            {
                fail++;
            }
            totalMs += r.durationMs;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("buffered", RING.size()); //$NON-NLS-1$
        s.put("capacity", capacity()); //$NON-NLS-1$
        s.put("success", ok); //$NON-NLS-1$
        s.put("failure", fail); //$NON-NLS-1$
        s.put("totalDurationMs", totalMs); //$NON-NLS-1$
        s.put("byTool", byTool); //$NON-NLS-1$
        return s;
    }

    /** Number of calls currently buffered. */
    public static synchronized int size()
    {
        return RING.size();
    }

    /** Clears the buffer. */
    public static synchronized void clear()
    {
        RING.clear();
    }

    /**
     * Per-tool timing and outcome over the buffered calls: for each tool name that
     * ran, a map with {@code count}, {@code p50Ms} / {@code p95Ms} / {@code p99Ms}
     * (nearest-rank percentiles of its call durations), {@code maxMs},
     * {@code successCount} and {@code failCount}. Used by the {@code self_status}
     * diagnostic tool. The window is whatever the ring currently holds.
     *
     * @return tool name -> its stat map, iteration order matching first appearance
     */
    public static synchronized Map<String, Map<String, Object>> perToolStats()
    {
        Map<String, List<Long>> durations = new LinkedHashMap<>();
        Map<String, int[]> outcomes = new LinkedHashMap<>(); // [successCount, failCount]
        for (Record r : RING)
        {
            durations.computeIfAbsent(r.toolName, k -> new ArrayList<>()).add(r.durationMs);
            int[] c = outcomes.computeIfAbsent(r.toolName, k -> new int[2]);
            if (r.success)
            {
                c[0]++;
            }
            else
            {
                c[1]++;
            }
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : durations.entrySet())
        {
            long[] sorted = e.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            int[] c = outcomes.get(e.getKey());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("count", sorted.length); //$NON-NLS-1$
            s.put("p50Ms", percentile(sorted, 50)); //$NON-NLS-1$
            s.put("p95Ms", percentile(sorted, 95)); //$NON-NLS-1$
            s.put("p99Ms", percentile(sorted, 99)); //$NON-NLS-1$
            s.put("maxMs", sorted[sorted.length - 1]); //$NON-NLS-1$
            s.put("successCount", c[0]); //$NON-NLS-1$
            s.put("failCount", c[1]); //$NON-NLS-1$
            out.put(e.getKey(), s);
        }
        return out;
    }

    /**
     * Nearest-rank percentile of an ascending-sorted array (the rank is
     * {@code ceil(p/100 * n)}, clamped into range). Returns 0 for an empty array.
     *
     * @param sortedAsc durations sorted ascending
     * @param p the percentile in [0, 100]
     * @return the value at that rank, or 0 when there is no data
     */
    static long percentile(long[] sortedAsc, int p)
    {
        if (sortedAsc.length == 0)
        {
            return 0;
        }
        int idx = (int)Math.ceil(p / 100.0 * sortedAsc.length) - 1;
        if (idx < 0)
        {
            idx = 0;
        }
        if (idx >= sortedAsc.length)
        {
            idx = sortedAsc.length - 1;
        }
        return sortedAsc[idx];
    }

    private static final class Record
    {
        final String toolName;
        final String argSummary;
        final String resultSummary;
        final long timestamp;
        final long durationMs;
        final boolean success;
        final boolean argsCut;
        final int resultFullChars;

        Record(String toolName, String argSummary, String resultSummary, long timestamp, long durationMs,
            boolean success, boolean argsCut, int resultFullChars)
        {
            this.toolName = toolName;
            this.argSummary = argSummary;
            this.resultSummary = resultSummary;
            this.timestamp = timestamp;
            this.durationMs = durationMs;
            this.success = success;
            this.argsCut = argsCut;
            this.resultFullChars = resultFullChars;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", toolName); //$NON-NLS-1$
            m.put("args", argSummary); //$NON-NLS-1$
            m.put("result", resultSummary); //$NON-NLS-1$
            m.put("timestamp", timestamp); //$NON-NLS-1$
            m.put("durationMs", durationMs); //$NON-NLS-1$
            m.put("success", success); //$NON-NLS-1$
            // Whether what is stored is the whole thing. Without this a reader cannot tell a short
            // answer from a long one cut down to the same size, and would read the settings as
            // having no effect while they were quietly deciding what they see.
            m.put("argsCut", argsCut); //$NON-NLS-1$
            m.put("resultChars", resultFullChars); //$NON-NLS-1$
            return m;
        }
    }
}
