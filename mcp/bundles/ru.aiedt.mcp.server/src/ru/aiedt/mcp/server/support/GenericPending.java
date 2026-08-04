/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides which slow tools the router wraps in the generic soft-timeout /
 * {@code runKey} Pending flow (through {@link PendingExecutor} on
 * {@link PendingWorkRegistry#GENERIC}), and builds the stable key seed from a
 * call's arguments.
 * <p>
 * <b>Membership is read-only by contract.</b> Only idempotent, side-effect-free
 * tools may be listed, because the generic flow cache-replays a completed result
 * and coalesces identical concurrent calls onto one future - safe for a query,
 * unsafe for a mutator (which would replay {@code success} without re-running, or
 * merge two mutation intents into one). Mutators, file writers and revalidation
 * commands stay inline under the B2 heavy-tool limiter; making <em>those</em>
 * resumable is the job of the idempotency key (B5), not this list.
 * <p>
 * Every listed tool is also in {@link HeavyTools}, so the B2 limiter already
 * bounds how many run at once; this only changes how a single slow call reports
 * back (a resumable {@code Pending} instead of a pinned request thread).
 */
public final class GenericPending
{
    /**
     * The one argument that steers the Pending flow rather than the work's
     * identity: {@code runKey} is a resume handle, so it is dropped from the key.
     * <p>
     * Note {@code timeoutSeconds} is deliberately <b>not</b> here: several listed
     * tools ({@code code_review}, {@code project_metrics}) already use it as their
     * own work budget, so it is part of the work's identity (two different
     * budgets are different runs and must not coalesce). The generic soft wait is
     * a fixed server-side value, never read from the arguments, so it can never
     * collide with a tool's own {@code timeoutSeconds}.
     */
    private static final Set<String> CONTROL_ARGS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList("runKey"))); //$NON-NLS-1$

    /**
     * Read-only, idempotent, side-effect-free slow tools. See class contract.
     * <p>
     * {@code impact_analysis} is deliberately excluded even though it is a
     * read-only analysis: it delegates to {@code find_references}, which runs its
     * own soft-timeout/Pending flow, so wrapping it again would nest two Pending
     * layers and the inner Pending JSON would be mis-parsed as an empty result.
     * It stays inline under the B2 limiter instead.
     */
    private static final Set<String> PENDING =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "audit_role_rights", //$NON-NLS-1$
            "code_review", //$NON-NLS-1$
            "compare_configurations", //$NON-NLS-1$
            "dcs_search", //$NON-NLS-1$
            "dependency_graph", //$NON-NLS-1$
            "detect_query_anti_patterns", //$NON-NLS-1$
            "find_dead_code", //$NON-NLS-1$
            "find_rls_violations", //$NON-NLS-1$
            "generate_health_snapshot", //$NON-NLS-1$
            "list_interceptors", //$NON-NLS-1$
            "project_metrics", //$NON-NLS-1$
            "semantic_metadata_search", //$NON-NLS-1$
            "sensitive_data_scan", //$NON-NLS-1$
            "validate_for_export", //$NON-NLS-1$
            "list_extension"))); //$NON-NLS-1$

    private GenericPending()
    {
    }

    /**
     * Whether the named tool runs through the generic Pending flow.
     *
     * @param toolName the wire tool name, or {@code null}
     * @return {@code true} for a listed read-only slow tool
     */
    public static boolean applies(String toolName)
    {
        return toolName != null && PENDING.contains(toolName);
    }

    /** @return the number of generic-Pending tools, for tests and diagnostics */
    public static int count()
    {
        return PENDING.size();
    }

    /**
     * Builds a stable identity string from the call's arguments: entries sorted
     * by key, with the control args removed. The caller combines this with the
     * tool name via {@link PendingWorkRegistry#computeRunKey}, so two different
     * tools never share a key even though they share the one {@code GENERIC}
     * registry.
     * <p>
     * Each key and value is written length-prefixed ({@code <len>:<text>}) so the
     * encoding is injective: two distinct argument maps can never produce the same
     * string, even when a value itself contains the delimiter characters. A naive
     * {@code k=v&k=v} join would let {@code {a:"x", b:"y"}} and {@code {a:"x&b=y"}}
     * collide onto one runKey and wrongly coalesce two different calls.
     *
     * @param arguments the flattened tool arguments (may be {@code null})
     * @return the canonical parameter string ({@code ""} when there are none)
     */
    public static String canonicalParams(Map<String, String> arguments)
    {
        if (arguments == null || arguments.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : arguments.entrySet())
        {
            if (e.getKey() != null && !CONTROL_ARGS.contains(e.getKey()))
            {
                sorted.put(e.getKey(), e.getValue() == null ? "" : e.getValue()); //$NON-NLS-1$
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet())
        {
            String k = e.getKey();
            String v = e.getValue();
            sb.append(k.length()).append(':').append(k) //$NON-NLS-1$
                .append('=').append(v.length()).append(':').append(v).append(';'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }
}
