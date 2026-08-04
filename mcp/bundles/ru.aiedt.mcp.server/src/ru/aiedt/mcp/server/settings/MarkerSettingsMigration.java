/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import ru.aiedt.mcp.server.Activator;

/**
 * Carries the marker decoration settings from the names they were stored under before the feature
 * was renamed.
 * <p>
 * Two things make this trickier than copying a string. A preference store cannot be asked "did the
 * user set this?" - {@code getBoolean} answers {@code false} for both an explicit false and an
 * untouched key, and {@code getString} hands back the shipped default once one is registered. So
 * the check goes to the instance-scope node, where an absent key really is absent. And the style
 * preference is not just renamed but re-spelled: the value {@code firstTag} has to become
 * {@code firstMarker} on the way across, or the decorator stops recognising it and silently falls
 * back to another style.
 * </p>
 * <p>
 * Both names keep being written afterwards. Dropping the old one would look tidy, but a workspace
 * that is rolled back to an earlier build would then read a stale value instead of the one the user
 * last chose.
 * </p>
 */
public final class MarkerSettingsMigration
{
    private MarkerSettingsMigration()
    {
        // utility
    }

    /**
     * Copies any pre-rename marker settings onto the current keys. Safe to call repeatedly and safe
     * to interrupt: the completion flag is only written after the values are, so an interrupted run
     * simply repeats, and a repeat writes the same values again.
     */
    public static void run()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        if (store.getBoolean(PrefKeys.PREF_MARKER_KEYS_MIGRATED))
        {
            return;
        }

        Preferences node = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
        boolean carried = false;

        String legacyVisibility = node.get(PrefKeys.LEGACY_MARKERS_SHOW_IN_NAVIGATOR, null);
        if (legacyVisibility != null && node.get(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR, null) == null)
        {
            store.setValue(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR, Boolean.parseBoolean(legacyVisibility));
            carried = true;
        }

        String legacyStyle = node.get(PrefKeys.LEGACY_MARKERS_DECORATION_STYLE, null);
        if (legacyStyle != null && node.get(PrefKeys.PREF_MARKERS_DECORATION_STYLE, null) == null)
        {
            store.setValue(PrefKeys.PREF_MARKERS_DECORATION_STYLE, translateStyle(legacyStyle));
            carried = true;
        }

        store.setValue(PrefKeys.PREF_MARKER_KEYS_MIGRATED, true);
        flush(node);

        if (carried)
        {
            Activator.logInfo("Marker decoration settings carried over from their pre-rename keys"); //$NON-NLS-1$
        }
    }

    /**
     * Mirrors a marker setting onto its pre-rename key, so a downgrade still sees the current
     * choice. Call it wherever the current key is written.
     *
     * @param key the current preference key; anything without a pre-rename twin is ignored
     * @param value the value just written under {@code key}
     */
    public static void mirrorToLegacyKey(String key, String value)
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        if (PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR.equals(key))
        {
            store.setValue(PrefKeys.LEGACY_MARKERS_SHOW_IN_NAVIGATOR, Boolean.parseBoolean(value));
        }
        else if (PrefKeys.PREF_MARKERS_DECORATION_STYLE.equals(key))
        {
            store.setValue(PrefKeys.LEGACY_MARKERS_DECORATION_STYLE,
                PrefKeys.MARKERS_STYLE_FIRST_MARKER.equals(value) ? PrefKeys.LEGACY_STYLE_FIRST_MARKER : value);
        }
    }

    /**
     * @param stored the style as an older build wrote it
     * @return the same style under the name this build recognises
     */
    private static String translateStyle(String stored)
    {
        return PrefKeys.LEGACY_STYLE_FIRST_MARKER.equals(stored) ? PrefKeys.MARKERS_STYLE_FIRST_MARKER : stored;
    }

    private static void flush(Preferences node)
    {
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            // The values are in the store either way; only their trip to disk failed, and the next
            // successful write takes them along. Repeating the migration after a restart is harmless.
            Activator.logError("Marker settings migrated but could not be flushed to disk", e); //$NON-NLS-1$
        }
    }
}
