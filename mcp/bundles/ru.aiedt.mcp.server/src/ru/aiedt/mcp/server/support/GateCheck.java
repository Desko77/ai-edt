/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.List;
import java.util.Objects;

/**
 * One preset check a routed facade operation passes before it runs, and the runner that applies a
 * facade's checks in order.
 * <p>
 * A facade that folds standalone tools into operations answers the same preset question as the
 * router would for a direct call, but it asks it in Java. The checks differ by operation and by
 * facade: a preset-gated capability is decided by its disabled-set membership alone, a facade's
 * {@code <facade>_writes} door has its own refusal text, and a delegated standalone is enabled only
 * when it is both registered and not disabled. Each kind therefore goes through its own
 * {@link ToolGate} method, so the wording a caller sees through the facade is the wording the
 * standalone or the router would have produced.
 * </p>
 * <p>
 * This is also the list an agent-facing description of the route reads: the facade's public
 * {@code gateChecksOf(operation)} returns these records, and {@link #firstRefusal(List)} executes
 * them in the same order {@code execute} does. One source, so a refusal shown by the call and a
 * refusal described before it cannot drift apart.
 * </p>
 */
public final class GateCheck
{
    /**
     * How a check decides, and therefore which {@link ToolGate} method and refusal text it uses.
     */
    public enum Kind
    {
        /**
         * Disabled-set membership of a capability name, whether or not a tool of that name exists -
         * {@link ToolGate#gateIfPresetDisabled}.
         */
        PRESET,

        /**
         * A facade's write door, {@code <facade>_writes}, with the door's own wording -
         * {@link ToolGate#gateWriteDoor}.
         */
        WRITE_DOOR,

        /**
         * A registered standalone tool that is also enabled - {@link ToolGate#gateOrNull}.
         */
        ENABLED
    }

    private final Kind kind;

    private final String name;

    private GateCheck(Kind kind, String name)
    {
        this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        this.name = Objects.requireNonNull(name, "name"); //$NON-NLS-1$
    }

    /**
     * A check on disabled-set membership of a capability name.
     *
     * @param capabilityName the ToolCategory name a preset can switch off
     * @return the check, never {@code null}
     */
    public static GateCheck preset(String capabilityName)
    {
        return new GateCheck(Kind.PRESET, capabilityName);
    }

    /**
     * A check on a facade's write door.
     *
     * @param writeDoor the capability name, {@code <facade>_writes}
     * @return the check, never {@code null}
     */
    public static GateCheck writeDoor(String writeDoor)
    {
        return new GateCheck(Kind.WRITE_DOOR, writeDoor);
    }

    /**
     * A check on a registered, enabled standalone tool.
     *
     * @param toolName the standalone tool's registered name
     * @return the check, never {@code null}
     */
    public static GateCheck enabled(String toolName)
    {
        return new GateCheck(Kind.ENABLED, toolName);
    }

    /**
     * The way this check decides.
     *
     * @return the kind, never {@code null}
     */
    public Kind kind()
    {
        return kind;
    }

    /**
     * The name this check asks about.
     *
     * @return the capability or tool name, never {@code null}
     */
    public String name()
    {
        return name;
    }

    /**
     * Applies this check to the preset and registry as they stand now.
     *
     * @return {@code null} when the check passes and the operation may run; otherwise the exact
     *         refusal text the facade has to return in place of the operation
     */
    public String refusal()
    {
        switch (kind)
        {
            case PRESET:
                return ToolGate.gateIfPresetDisabled(name);
            case WRITE_DOOR:
                return ToolGate.gateWriteDoor(name);
            case ENABLED:
                return ToolGate.gateOrNull(name);
            default:
                throw new IllegalStateException("Unknown gate check kind: " + kind); //$NON-NLS-1$
        }
    }

    /**
     * Applies every check in order and answers the first refusal.
     * <p>
     * The order is the facade's own order: the first check that refuses is the one whose text the
     * caller sees, exactly as the checks stood inline before they were collected here.
     * </p>
     *
     * @param checks the checks to run, in the facade's order
     * @return {@code null} when every check passes; otherwise the first check's refusal text
     */
    public static String firstRefusal(List<GateCheck> checks)
    {
        Objects.requireNonNull(checks, "checks"); //$NON-NLS-1$
        for (GateCheck check : checks)
        {
            String refusal = check.refusal();
            if (refusal != null)
            {
                return refusal;
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        return kind + "(" + name + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
