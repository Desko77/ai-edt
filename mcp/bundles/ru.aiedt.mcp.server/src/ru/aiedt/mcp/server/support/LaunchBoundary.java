/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The launch boundary of one platform call, as a single word every side claims through: the
 * worker under the per-infobase lock right before it calls the launcher, and the stop side -
 * the run's stopper at the moment the stop is signaled, or the abandonment before it declares
 * itself. Whichever side crosses first owns the launch, and the word keeps which side that was:
 * a worker that finds the boundary taken starts no platform process at all, and a stop side that
 * holds it - its own claim or the stopper's - reports the launch as prevented.
 */
final class LaunchBoundary
{
    /** No side has crossed yet. */
    private static final int FREE = 0;

    /** The worker crossed: the launcher call is committed. */
    private static final int LAUNCHED = 1;

    /** The stop side crossed first: no platform process of this call will start. */
    private static final int STOPPED = 2;

    /** Who holds the boundary. */
    private final AtomicInteger state = new AtomicInteger(FREE);

    /**
     * Claims the boundary for the launcher call.
     *
     * @return {@code true} when the call is this run's to make; {@code false} when the stop side
     *         crossed first and the launcher must not be called
     */
    boolean claimLaunch()
    {
        return state.compareAndSet(FREE, LAUNCHED);
    }

    /**
     * Claims the boundary for the stop side.
     *
     * @return {@code true} when no platform process of this call will start - this claim crossed
     *         first, or the stop side already holds the boundary; {@code false} when the worker
     *         crossed first and the launcher call is committed
     */
    boolean claimStop()
    {
        return state.compareAndSet(FREE, STOPPED) || state.get() == STOPPED;
    }

    /**
     * @return whether the stop side holds the boundary, so no platform process of this call will
     *         start
     */
    boolean heldByStop()
    {
        return state.get() == STOPPED;
    }
}
