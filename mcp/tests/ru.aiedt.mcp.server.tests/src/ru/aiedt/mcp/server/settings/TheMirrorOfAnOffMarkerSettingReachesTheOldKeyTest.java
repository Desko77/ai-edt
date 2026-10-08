/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;
import org.osgi.service.prefs.Preferences;

import ru.aiedt.mcp.server.Activator;

/**
 * What a downgrade finds under the pre-rename name of the marker visibility setting.
 * <p>
 * Both names are written on every save so a workspace rolled back to an earlier build still reads
 * what the user last chose. The pre-rename name has no registered default, and a preference store
 * writes such a key by removing it when the value it is given is the one it assumes is the default -
 * which for a boolean is <code>false</code>. The mirror would then be missing exactly when the user
 * switched markers off, and the older build, finding nothing, would paint them.
 * </p>
 * <p>
 * The check is the key as it stands in the node the store writes into, because "was it written" is
 * what is in question - a read through the preference store answers the shipped default either way.
 * </p>
 */
public class TheMirrorOfAnOffMarkerSettingReachesTheOldKeyTest
{
    @Test
    public void switchingMarkersOffLeavesTheOldKeyHoldingOff()
    {
        Preferences node = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
        String legacy = PrefKeys.LEGACY_MARKERS_SHOW_IN_NAVIGATOR;
        String before = node.get(legacy, null);
        try
        {
            MarkerSettingsMigration.mirrorToLegacyKey(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR, "false");

            assertEquals("the old key has to say off, not be absent", "false", node.get(legacy, null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            if (before == null)
            {
                node.remove(legacy);
            }
            else
            {
                node.put(legacy, before);
            }
        }
    }
}
