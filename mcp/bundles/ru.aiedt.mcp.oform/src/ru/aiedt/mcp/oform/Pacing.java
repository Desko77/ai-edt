/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

/**
 * The stop points between the steps of a write or an edit: the window between the registry
 * lock's lookup and its taking, the span between an edit's baseline check and its fixation, and
 * the span between a write's check and its file write. Nothing pauses unless a test installs a
 * pause; with no pause installed, which is every run outside the suite, each point returns at
 * once and costs one volatile read.
 */
public final class Pacing
{
    /** After the registry entry is looked up, before its lock is taken. */
    public static final String BEFORE_REGISTRY_LOCK = "beforeRegistryLock"; //$NON-NLS-1$

    /** After an edit's baseline check and its operation ran, before the edit is fixed into the session. */
    public static final String BEFORE_EDIT_FIXATION = "beforeEditFixation"; //$NON-NLS-1$

    /** After a write's check passed, before the container's bytes land in the file. */
    public static final String BEFORE_CONTAINER_WRITE = "beforeContainerWrite"; //$NON-NLS-1$

    /** The pause a test puts on a stop point. */
    @FunctionalInterface
    public interface Pause
    {
        /**
         * Called at a stop point.
         *
         * @param point the point's name, one of this class's constants
         */
        void at(String point);
    }

    private static volatile Pause pause;

    private Pacing()
    {
        // static points
    }

    /**
     * Installs the pause every stop point answers to, replacing the one before it.
     *
     * @param installed the pause; {@code null} clears
     */
    public static void install(Pause installed)
    {
        pause = installed;
    }

    /** Clears the pause: every stop point returns at once again. */
    public static void clear()
    {
        pause = null;
    }

    /**
     * The stop point itself: returns at once unless a pause is installed.
     *
     * @param point the point's name, one of this class's constants
     */
    public static void pauseAt(String point)
    {
        Pause installed = pause;
        if (installed != null)
        {
            installed.at(point);
        }
    }
}
