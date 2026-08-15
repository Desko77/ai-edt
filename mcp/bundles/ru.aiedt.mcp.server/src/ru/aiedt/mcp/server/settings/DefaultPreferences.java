/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;

/**
 * Seeds the shipped values into the preference store.
 * <p>
 * Eclipse runs this the first time anything asks the store for a default, and the store needs to be
 * told what its defaults are before it can answer. Skip it and every unset preference reads as the
 * type's zero: the port becomes 0 and the socket binds somewhere random, marker decoration reads as off
 * and quietly vanishes, and the parameter service can no longer tell a value the user chose from one
 * they never touched.
 * </p>
 */
public class DefaultPreferences
    extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        store.setDefault(PrefKeys.PREF_PORT, PrefKeys.DEFAULT_PORT);
        store.setDefault(PrefKeys.PREF_HEAVY_TOOL_LIMIT, PrefKeys.DEFAULT_HEAVY_TOOL_LIMIT);
        store.setDefault(PrefKeys.PREF_HEAP_REFUSAL_PERCENT, PrefKeys.DEFAULT_HEAP_REFUSAL_PERCENT);
        store.setDefault(PrefKeys.PREF_DEBUG_LOG_ENABLED, PrefKeys.DEFAULT_DEBUG_LOG_ENABLED);
        store.setDefault(PrefKeys.PREF_MAX_RESPONSE_BYTES, PrefKeys.DEFAULT_MAX_RESPONSE_BYTES);
        store.setDefault(PrefKeys.PREF_AUTO_START, PrefKeys.DEFAULT_AUTO_START);
        store.setDefault(PrefKeys.PREF_CHECKS_FOLDER, PrefKeys.DEFAULT_CHECKS_FOLDER);
        store.setDefault(PrefKeys.PREF_PLAIN_TEXT_MODE,
            PrefKeys.DEFAULT_PLAIN_TEXT_MODE);
        store.setDefault(PrefKeys.PREF_BSL_LS_JAR, PrefKeys.DEFAULT_BSL_LS_JAR);
        store.setDefault(PrefKeys.PREF_BSL_LS_JAVA, PrefKeys.DEFAULT_BSL_LS_JAVA);
        store.setDefault(PrefKeys.PREF_VANESSA_EPF, PrefKeys.DEFAULT_VANESSA_EPF);
        store.setDefault(PrefKeys.PREF_VANESSA_1C_EXE,
            PrefKeys.DEFAULT_VANESSA_1C_EXE);
        store.setDefault(PrefKeys.PREF_AUTH_ENABLED, PrefKeys.DEFAULT_AUTH_ENABLED);
        store.setDefault(PrefKeys.PREF_AUTH_TOKEN, PrefKeys.DEFAULT_AUTH_TOKEN);
        store.setDefault(PrefKeys.PREF_BIND_ALL_INTERFACES,
            PrefKeys.DEFAULT_BIND_ALL_INTERFACES);
        store.setDefault(PrefKeys.PREF_PII_REDACT_ENABLED,
            PrefKeys.DEFAULT_PII_REDACT_ENABLED);
        store.setDefault(PrefKeys.PREF_DISABLED_TOOLS,
            PrefKeys.DEFAULT_DISABLED_TOOLS);
        // F4: a fresh install shows the canonical compact surface - the facades plus the high-frequency
        // primitives, with the legacy standalone names hidden from tools/list but still callable. The
        // set is read from ToolProfile.CANONICAL so it tracks the enum, not a second copy here. A
        // workspace that has already chosen (the preference was written) keeps its choice - setDefault
        // only supplies the value for a key that was never stored.
        store.setDefault(PrefKeys.PREF_UNLISTED_TOOLS,
            ToolSettingsStore.serializeDisabledTools(ToolProfile.CANONICAL.getUnlistedTools()));
        store.setDefault(PrefKeys.PREF_HISTORY_ENABLED, PrefKeys.DEFAULT_HISTORY_ENABLED);
        store.setDefault(PrefKeys.PREF_HISTORY_DEPTH, PrefKeys.DEFAULT_HISTORY_DEPTH);
        store.setDefault(PrefKeys.PREF_HISTORY_ARG_CHARS, PrefKeys.DEFAULT_HISTORY_ARG_CHARS);
        store.setDefault(PrefKeys.PREF_HISTORY_RESULT_CHARS,
            PrefKeys.DEFAULT_HISTORY_RESULT_CHARS);
        store.setDefault(PrefKeys.PREF_HISTORY_FILE_ENABLED,
            PrefKeys.DEFAULT_HISTORY_FILE_ENABLED);
        store.setDefault(PrefKeys.PREF_HISTORY_FILE_REDACT,
            PrefKeys.DEFAULT_HISTORY_FILE_REDACT);
        store.setDefault(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR,
            PrefKeys.DEFAULT_MARKERS_SHOW_IN_NAVIGATOR);
        store.setDefault(PrefKeys.PREF_MARKERS_DECORATION_STYLE,
            PrefKeys.DEFAULT_MARKERS_DECORATION_STYLE);
        store.setDefault(PrefKeys.PREF_UPKEEP_ENABLED, PrefKeys.DEFAULT_UPKEEP_ENABLED);
        store.setDefault(PrefKeys.PREF_UPKEEP_SITE_URL, PrefKeys.DEFAULT_UPKEEP_SITE_URL);
        store.setDefault(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS,
            PrefKeys.DEFAULT_UPKEEP_INTERVAL_HOURS);
        store.setDefault(PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES,
            PrefKeys.DEFAULT_UPKEEP_STARTUP_DELAY_MINUTES);
        store.setDefault(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE,
            PrefKeys.DEFAULT_UPKEEP_ALLOW_LOCAL_SITE);
        store.setDefault(PrefKeys.PREF_UPKEEP_NOTIFY_POPUP,
            PrefKeys.DEFAULT_UPKEEP_NOTIFY_POPUP);

        ToolParamSettings.getInstance().initializeDefaults(store);
    }
}
