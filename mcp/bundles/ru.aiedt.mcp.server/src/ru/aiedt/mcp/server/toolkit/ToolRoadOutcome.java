/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

/**
 * What a call through {@link IToolRoad} came back with.
 * <p>
 * Three shapes, one object: a finished call carries its text; a refused call carries why it never
 * ran; a call whose work outlived its wait carries the {@code Pending} envelope text together with
 * the {@code runKey} and the domain that hold the run, and says it is not finished.
 * </p>
 */
public final class ToolRoadOutcome
{
    private final String text;

    private final String refusal;

    private final String runKey;

    private final String domain;

    private final boolean finished;

    private ToolRoadOutcome(String text, String refusal, String runKey, String domain, boolean finished)
    {
        this.text = text;
        this.refusal = refusal;
        this.runKey = runKey;
        this.domain = domain;
        this.finished = finished;
    }

    /**
     * @param text what the tool produced
     * @return an outcome for a call that ran to the end
     */
    public static ToolRoadOutcome done(String text)
    {
        return new ToolRoadOutcome(text, null, null, null, true);
    }

    /**
     * @param message why the call did not run
     * @return an outcome for a call the road turned away
     */
    public static ToolRoadOutcome refused(String message)
    {
        return new ToolRoadOutcome(null, message, null, null, true);
    }

    /**
     * @param envelope the {@code Pending} envelope the tool produced
     * @param runKey the key the caller polls with
     * @param domain the domain holding the run
     * @return an outcome for a call whose work is still running
     */
    public static ToolRoadOutcome pending(String envelope, String runKey, String domain)
    {
        return new ToolRoadOutcome(envelope, null, runKey, domain, false);
    }

    /**
     * @return the tool's own text, or the {@code Pending} envelope for a call still running;
     *         {@code null} for a refused call
     */
    public String text()
    {
        return text;
    }

    /**
     * @return why the road did not run the call, or {@code null} when it did
     */
    public String refusal()
    {
        return refusal;
    }

    /**
     * @return whether the road refused the call
     */
    public boolean refused()
    {
        return refusal != null;
    }

    /**
     * @return the key of the still-running work, or {@code null} for a finished or refused call
     */
    public String runKey()
    {
        return runKey;
    }

    /**
     * @return the domain holding the still-running work, or {@code null}
     */
    public String domain()
    {
        return domain;
    }

    /**
     * @return whether the work is done; {@code false} while it runs under a {@code runKey}
     */
    public boolean finished()
    {
        return finished;
    }
}
