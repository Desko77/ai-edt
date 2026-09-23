/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import java.util.Map;

/**
 * The road every tool call takes, published as an OSGi service so another bundle can call a tool
 * the way the wire does.
 * <p>
 * An external call over HTTP and an internal call from a bundle pass the same decisions: the tool
 * is looked up in the catalogue, the preset gate is asked, a heavy call clears the heap gate and
 * takes one of the heavy-tool permits, and the permit is held for as long as the work runs - past
 * a {@code Pending} answer, until the background work itself has left. A run whose tracking was
 * detached or stopped keeps the permit until its body returns. Calling through this service
 * therefore costs the same and is bounded by the same limits as a call from an agent.
 * </p>
 * <p>
 * Methods added to this interface after release are declared {@code default} only: a bundle
 * compiled against an earlier version keeps resolving.
 * </p>
 */
public interface IToolRoad
{
    /**
     * Calls a tool by any name the wire accepts.
     * <p>
     * Runs on the calling thread. The answer is the tool's own text, without the truncation and
     * masking the wire applies to what it sends a client - the caller owns that of its own answer.
     * A nested call (the calling thread already runs inside a tool call) shares that call's
     * cancellation flag and its heavy permit; a call with no parent runs under a scope of its own.
     * </p>
     *
     * @param tool the tool name or alias
     * @param arguments the arguments; structure is flattened the way the wire flattens it
     * @param origin the symbolic name of the calling bundle, recorded with the call's history
     *            entry; may be {@code null}
     * @return the outcome, never {@code null}
     */
    ToolRoadOutcome call(String tool, Map<String, Object> arguments, String origin);

    /**
     * Waits for a run a call started, by the {@code runKey} its {@code Pending} answer carried.
     *
     * @param runKey the key of the run
     * @param waitMillis how long to wait before answering {@code finished=false}
     * @return the outcome; a refusal when no domain holds the key
     */
    ToolRoadOutcome resume(String runKey, long waitMillis);

    /**
     * Stops a run: raises the cancellation flag of the scope the work runs under and asks the
     * domain to stop it.
     *
     * @param runKey the key of the run
     * @return whether a domain held the key
     */
    boolean cancel(String runKey);
}
