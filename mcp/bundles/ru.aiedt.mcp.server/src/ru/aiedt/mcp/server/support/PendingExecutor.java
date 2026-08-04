/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Map;
import java.util.function.Supplier;

import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Canonical soft-timeout / {@code runKey} "Pending" wrapper shared by
 * long-running MCP tools.
 * <p>
 * This consolidates the boilerplate that {@code update_database},
 * {@code export_object}, {@code find_references} and the {@code edit_metadata}
 * batch each hand-rolled around {@link PendingWorkRegistry}: prune stale entries,
 * dispatch (or coalesce onto) the async future, wait up to a soft timeout, then
 * either return the finished result (and drop the entry) or a {@code Pending}
 * response carrying the {@code runKey} the caller can poll. A separate
 * {@link #resume} path serves an explicit {@code runKey} re-issue.
 * <p>
 * The emitted {@code Pending} JSON is a strict superset of what each caller
 * produced by hand - {@code operation}, {@code status="Pending"}, {@code runKey},
 * {@code elapsedMs}, {@code waitedMs}, {@code hint} - so migrating a caller to
 * this helper preserves its wire contract; domain-specific fields (a project
 * name, an output path) are appended through the {@link PendingFields} hook.
 * <p>
 * <b>Terminal state.</b> The registry itself guarantees every dispatched future
 * reaches a terminal cached result (success text, or an {@code "Error: ..."}
 * string when the work throws) and stamps {@code completedAt}; this helper never
 * swallows that guarantee, so an entry cannot leak in a forever-pending state.
 * Oversized results are evicted on a short TTL by the registry so a large export
 * cannot pin the heap for the full completed-entry lifetime.
 */
public final class PendingExecutor
{
    /**
     * Default soft wait before handing back a {@code Pending} response. Matches
     * the 30s that {@code update_database} / {@code export_object} /
     * {@code find_references} already default to, so a migrated caller keeps its
     * timing; callers that want a different budget pass an explicit value.
     */
    public static final long DEFAULT_SOFT_TIMEOUT_MS = 30_000L;

    /** Lower clamp for a caller-supplied {@code timeoutSeconds}. */
    public static final int MIN_TIMEOUT_SECONDS = 5;

    /** Upper clamp for a caller-supplied {@code timeoutSeconds}. */
    public static final int MAX_TIMEOUT_SECONDS = 120;

    private PendingExecutor()
    {
        // static helper
    }

    /**
     * Appends domain-specific fields (project name, output path, ...) to a
     * {@code Pending} response so a migrated caller keeps every field it used to
     * emit.
     */
    @FunctionalInterface
    public interface PendingFields
    {
        void apply(ToolResult pending);
    }

    /**
     * Reads a {@code runKey} argument from {@code params}: when present, polls
     * that existing run ({@link #resume}); otherwise starts (or coalesces onto)
     * a run under {@code startRunKey} ({@link #start}). Uses cache-replay start
     * semantics (a completed prior run under the same key replays without
     * re-running) - correct for idempotent reads. Mutators that must re-run on
     * every fresh call use {@link #execute(PendingWorkRegistry, String, Map,
     * String, long, Supplier, PendingFields, boolean)} with {@code true}.
     *
     * @param registry the domain registry backing this tool
     * @param operationName tool/operation name echoed in the response
     * @param params the raw tool arguments (read for {@code runKey})
     * @param startRunKey the canonical key for a fresh run (from the caller's
     *            stable parameter set)
     * @param softTimeoutMs how long to wait before returning {@code Pending}
     * @param work the synchronous heavy work (runs on the registry executor)
     * @param pendingFields optional domain fields for the {@code Pending} JSON
     * @return the finished result JSON, or a {@code Pending} JSON with a runKey
     */
    public static String execute(PendingWorkRegistry registry, String operationName,
        Map<String, String> params, String startRunKey, long softTimeoutMs,
        Supplier<String> work, PendingFields pendingFields)
    {
        return execute(registry, operationName, params, startRunKey, softTimeoutMs, work,
            pendingFields, false);
    }

    /**
     * As {@link #execute(PendingWorkRegistry, String, Map, String, long,
     * Supplier, PendingFields)} but lets a mutating caller force a fresh run:
     * when {@code evictCompletedOnStart} is {@code true} a completed prior entry
     * under the same key is dropped before dispatch, so the work actually re-runs
     * instead of replaying a stale cached result.
     */
    public static String execute(PendingWorkRegistry registry, String operationName,
        Map<String, String> params, String startRunKey, long softTimeoutMs,
        Supplier<String> work, PendingFields pendingFields, boolean evictCompletedOnStart)
    {
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return resume(registry, operationName, runKeyParam, softTimeoutMs, pendingFields);
        }
        return start(registry, operationName, startRunKey, softTimeoutMs, work, pendingFields,
            evictCompletedOnStart);
    }

    /**
     * Starts (or coalesces onto) an async run with cache-replay semantics (see
     * {@link #execute}) and waits up to the soft timeout. Returns the finished
     * result (dropping the entry) or a {@code Pending} JSON.
     */
    public static String start(PendingWorkRegistry registry, String operationName,
        String runKey, long softTimeoutMs, Supplier<String> work, PendingFields pendingFields)
    {
        return start(registry, operationName, runKey, softTimeoutMs, work, pendingFields, false);
    }

    /**
     * As {@link #start(PendingWorkRegistry, String, String, long, Supplier,
     * PendingFields)} but drops a completed prior entry first when
     * {@code evictCompletedOnStart} is {@code true} (fresh-run semantics for
     * mutators). Best-effort: the evict/dispatch pair is not atomic against a
     * concurrent caller on the same key - full re-run idempotency is B5's job.
     */
    public static String start(PendingWorkRegistry registry, String operationName,
        String runKey, long softTimeoutMs, Supplier<String> work, PendingFields pendingFields,
        boolean evictCompletedOnStart)
    {
        long waitMs = Math.max(1L, softTimeoutMs);
        registry.pruneExpired();
        if (evictCompletedOnStart)
        {
            PendingWorkRegistry.PendingEntry existing = registry.get(runKey);
            if (existing != null && existing.isDone())
            {
                registry.remove(runKey);
            }
        }
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey, work);
        String result = entry.await(waitMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(operationName, runKey, entry, waitMs, pendingFields);
    }

    /**
     * Polls a previously issued {@code runKey}. Returns the cached result (and
     * drops the entry), a fresh {@code Pending} JSON when still running, or an
     * error when the key is unknown (completed-and-retrieved, or TTL-evicted).
     */
    public static String resume(PendingWorkRegistry registry, String operationName,
        String runKey, long softTimeoutMs, PendingFields pendingFields)
    {
        long waitMs = Math.max(1L, softTimeoutMs);
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the operation either completed and was " //$NON-NLS-1$
                + "already retrieved, or was abandoned and evicted by TTL. Issue a new request " //$NON-NLS-1$
                + "without runKey to start over.") //$NON-NLS-1$
                .put("operation", operationName) //$NON-NLS-1$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        String result = entry.await(waitMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(operationName, runKey, entry, waitMs, pendingFields);
    }

    /**
     * Builds the canonical {@code Pending} response: the fixed field set every
     * long-running tool shares, plus any domain fields from {@code pendingFields}.
     * The {@code hint} is intentionally generic; a caller that wants its own
     * wording re-puts {@code "hint"} from {@code pendingFields} (a later
     * {@link ToolResult#put} wins), since fields are applied after the defaults.
     */
    public static String buildPendingJson(String operationName, String runKey,
        PendingWorkRegistry.PendingEntry entry, long softTimeoutMs, PendingFields pendingFields)
    {
        ToolResult tr = ToolResult.success()
            .put("operation", operationName) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", softTimeoutMs) //$NON-NLS-1$
            .put("hint", "Still running. Call this tool again with runKey=\"" + runKey //$NON-NLS-1$
                + "\" to resume waiting (or with the same params - they produce the same runKey)."); //$NON-NLS-1$
        if (pendingFields != null)
        {
            pendingFields.apply(tr);
        }
        return tr.toJson();
    }

    /**
     * Parses a {@code timeoutSeconds} argument, clamped to
     * [{@value #MIN_TIMEOUT_SECONDS}, {@value #MAX_TIMEOUT_SECONDS}] seconds, or
     * {@code defaultMs} when absent or unparseable.
     */
    public static long parseTimeoutMs(Map<String, String> params, long defaultMs)
    {
        String t = JsonUtils.extractStringArgument(params, "timeoutSeconds"); //$NON-NLS-1$
        if (t == null || t.isEmpty())
        {
            return defaultMs;
        }
        try
        {
            int seconds = (int) Double.parseDouble(t);
            seconds = Math.max(MIN_TIMEOUT_SECONDS, Math.min(seconds, MAX_TIMEOUT_SECONDS));
            return seconds * 1000L;
        }
        catch (NumberFormatException e)
        {
            return defaultMs;
        }
    }
}
