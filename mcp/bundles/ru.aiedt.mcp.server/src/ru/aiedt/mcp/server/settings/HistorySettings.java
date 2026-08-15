/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;

/**
 * How much of each tool call is remembered, and where.
 * <p>
 * One reading of the four numbers behind the call history, taken together so that a call is recorded
 * under a single consistent set rather than under whatever each value happened to be at the moment it
 * was read. Every accessor answers from the shipped defaults when there is no preference store to
 * ask, which is the case in the headless test runtime and during shutdown - the history must keep
 * working there, not throw.
 * </p>
 */
public final class HistorySettings
{
    /** Fixed-point scale for the affordable share, so the fit is computed without floating point. */
    private static final long SHARE_SCALE = 1_000_000L;

    private final boolean enabled;

    private final int depth;

    private final int argChars;

    private final int resultChars;

    private final boolean fileEnabled;

    private final boolean fileRedact;

    private HistorySettings(boolean enabled, int depth, int argChars, int resultChars, boolean fileEnabled,
        boolean fileRedact)
    {
        this.enabled = enabled;
        this.depth = depth;
        this.argChars = argChars;
        this.resultChars = resultChars;
        this.fileEnabled = fileEnabled;
        this.fileRedact = fileRedact;
    }

    /**
     * Reads the settings from the workspace.
     *
     * @return the settings in force, never <code>null</code>
     */
    public static HistorySettings current()
    {
        Activator activator = Activator.getDefault();
        return read(activator == null ? null : activator.getPreferenceStore());
    }

    /**
     * Reads the settings from a given store, which is what makes them checkable without a workbench.
     * <p>
     * A key the store has never heard of takes the shipped value rather than the type's zero. That
     * distinction is the whole reason this asks {@code contains} first: an absent boolean reads as
     * <code>false</code>, and reading it that way would turn recording off in any runtime where the
     * default initializer has not run - silently, since an empty history looks the same as a quiet
     * session.
     * </p>
     *
     * @param store the store to read, or <code>null</code> to take the shipped values whole
     * @return the settings, never <code>null</code>
     */
    public static HistorySettings read(IPreferenceStore store)
    {
        if (store == null)
        {
            return new HistorySettings(PrefKeys.DEFAULT_HISTORY_ENABLED, PrefKeys.DEFAULT_HISTORY_DEPTH,
                PrefKeys.DEFAULT_HISTORY_ARG_CHARS, PrefKeys.DEFAULT_HISTORY_RESULT_CHARS,
                PrefKeys.DEFAULT_HISTORY_FILE_ENABLED, PrefKeys.DEFAULT_HISTORY_FILE_REDACT);
        }
        int depth = count(store, PrefKeys.PREF_HISTORY_DEPTH, PrefKeys.DEFAULT_HISTORY_DEPTH, 1,
            PrefKeys.MAX_HISTORY_DEPTH);
        int argChars = count(store, PrefKeys.PREF_HISTORY_ARG_CHARS, PrefKeys.DEFAULT_HISTORY_ARG_CHARS, 0,
            PrefKeys.MAX_HISTORY_CHARS);
        int resultChars = count(store, PrefKeys.PREF_HISTORY_RESULT_CHARS,
            PrefKeys.DEFAULT_HISTORY_RESULT_CHARS, 0, PrefKeys.MAX_HISTORY_CHARS);
        long share = share(depth, argChars, resultChars);
        return new HistorySettings(
            flag(store, PrefKeys.PREF_HISTORY_ENABLED, PrefKeys.DEFAULT_HISTORY_ENABLED), depth,
            fit(argChars, share), fit(resultChars, share),
            flag(store, PrefKeys.PREF_HISTORY_FILE_ENABLED, PrefKeys.DEFAULT_HISTORY_FILE_ENABLED),
            flag(store, PrefKeys.PREF_HISTORY_FILE_REDACT, PrefKeys.DEFAULT_HISTORY_FILE_REDACT));
    }

    /**
     * How much of each asked-for extent the whole buffer can afford, as a fraction scaled by
     * {@link #SHARE_SCALE}.
     * <p>
     * Each setting is sensible alone and their product is not: the greatest depth with both extents
     * at their maximum asks for gigabytes of {@code char}, which does not present as a rejected
     * setting but as the IDE dying. Scaling both extents by the same fraction keeps the balance the
     * user chose between request and response while bringing the total inside
     * {@link PrefKeys#MAX_HISTORY_TOTAL_CHARS}.
     * </p>
     *
     * @param depth how many calls are kept
     * @param argChars characters of the request asked for
     * @param resultChars characters of the response asked for
     * @return {@link #SHARE_SCALE} when everything fits, less when it does not
     */
    private static long share(int depth, int argChars, int resultChars)
    {
        long wanted = (long)depth * ((long)argChars + resultChars);
        if (wanted <= PrefKeys.MAX_HISTORY_TOTAL_CHARS)
        {
            return SHARE_SCALE;
        }
        return SHARE_SCALE * PrefKeys.MAX_HISTORY_TOTAL_CHARS / wanted;
    }

    /**
     * Brings one extent down by the affordable share.
     * <p>
     * An extent the user asked for never becomes zero here: zero means "keep no text at all", which
     * is a choice they can make deliberately and must not be arrived at by arithmetic.
     * </p>
     *
     * @param asked the extent from the settings
     * @param share the affordable fraction, scaled by {@link #SHARE_SCALE}
     * @return the extent to work with
     */
    private static int fit(int asked, long share)
    {
        if (share >= SHARE_SCALE || asked == 0)
        {
            return asked;
        }
        return Math.max(1, (int)(asked * share / SHARE_SCALE));
    }

    private static boolean flag(IPreferenceStore store, String key, boolean shipped)
    {
        return store.contains(key) ? store.getBoolean(key) : shipped;
    }

    /**
     * Reads a number and holds it between the bounds.
     * <p>
     * Clamping rather than rejecting, because these can be reached by hand-editing the preferences
     * file, where a stray zero would ask for a buffer that cannot hold a call and a stray large
     * number for one that eats the heap.
     * </p>
     *
     * @param store the store to read
     * @param key the preference
     * @param shipped the value to use when nothing was stored
     * @param min lowest accepted
     * @param max highest accepted
     * @return the number to work with
     */
    private static int count(IPreferenceStore store, String key, int shipped, int min, int max)
    {
        if (!store.contains(key))
        {
            return shipped;
        }
        return Math.max(min, Math.min(max, store.getInt(key)));
    }

    /**
     * @return whether calls are recorded at all
     */
    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * @return how many calls are kept
     */
    public int depth()
    {
        return depth;
    }

    /**
     * @return how many characters of the arguments are kept
     */
    public int argChars()
    {
        return argChars;
    }

    /**
     * @return how many characters of the response are kept
     */
    public int resultChars()
    {
        return resultChars;
    }

    /**
     * @return whether each call is also appended to the journal file
     */
    public boolean isFileEnabled()
    {
        return fileEnabled;
    }

    /**
     * @return whether the journal file is masked for personal data
     */
    public boolean isFileRedacted()
    {
        return fileRedact;
    }
}
