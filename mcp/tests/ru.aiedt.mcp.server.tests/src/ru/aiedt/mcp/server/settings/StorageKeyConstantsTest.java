/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterKeys;
import ru.aiedt.mcp.server.labels.MarkerKeys;

/**
 * Pinning tests for the three constant holders the preference/marker/cluster subsystems read from.
 * The string literals are a persisted wire format: a workspace file written by an older build is
 * read back through exactly these names, so each test nails the literal rather than checking only
 * that the field is non-empty. Renaming a key would silently lose user configuration, and these
 * assertions exist to make that rename fail the build.
 */
public class StorageKeyConstantsTest
{
    // ---- PrefKeys: preference-store keys (the wire format) ---------------------

    @Test
    public void portPreferenceKeyIsTheShippedLiteral()
    {
        assertEquals("mcpServerPort", PrefKeys.PREF_PORT); //$NON-NLS-1$
    }

    @Test
    public void autoStartPreferenceKeyIsTheShippedLiteral()
    {
        assertEquals("mcpServerAutoStart", PrefKeys.PREF_AUTO_START); //$NON-NLS-1$
    }

    @Test
    public void checksFolderPreferenceKeyIsTheShippedLiteral()
    {
        assertEquals("mcpChecksFolder", PrefKeys.PREF_CHECKS_FOLDER); //$NON-NLS-1$
    }

    @Test
    public void plainTextModePreferenceKeyIsTheShippedLiteral()
    {
        assertEquals("mcpPlainTextMode", PrefKeys.PREF_PLAIN_TEXT_MODE); //$NON-NLS-1$
    }

    @Test
    public void disabledToolsPreferenceKeyIsTheShippedLiteral()
    {
        assertEquals("mcpDisabledTools", PrefKeys.PREF_DISABLED_TOOLS); //$NON-NLS-1$
    }

    @Test
    public void markerPreferenceKeysUseTheDottedNamespace()
    {
        assertEquals("markers.showInNavigator", PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR);
        assertEquals("tags.showInNavigator", PrefKeys.LEGACY_MARKERS_SHOW_IN_NAVIGATOR); //$NON-NLS-1$
        assertEquals("markers.decorationStyle", PrefKeys.PREF_MARKERS_DECORATION_STYLE);
        assertEquals("tags.decorationStyle", PrefKeys.LEGACY_MARKERS_DECORATION_STYLE); //$NON-NLS-1$
    }

    @Test
    public void everyPreferenceKeyIsNonBlank()
    {
        for (String key : new String[] {
            PrefKeys.PREF_PORT, PrefKeys.PREF_AUTO_START,
            PrefKeys.PREF_CHECKS_FOLDER, PrefKeys.PREF_PLAIN_TEXT_MODE,
            PrefKeys.PREF_BSL_LS_JAR, PrefKeys.PREF_BSL_LS_JAVA,
            PrefKeys.PREF_VANESSA_EPF, PrefKeys.PREF_VANESSA_1C_EXE,
            PrefKeys.PREF_AUTH_ENABLED, PrefKeys.PREF_AUTH_TOKEN,
            PrefKeys.PREF_BIND_ALL_INTERFACES, PrefKeys.PREF_DISABLED_TOOLS,
            PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR,
            PrefKeys.PREF_MARKERS_DECORATION_STYLE,
            PrefKeys.PREF_UPKEEP_ENABLED, PrefKeys.PREF_UPKEEP_SITE_URL,
            PrefKeys.PREF_UPKEEP_INTERVAL_HOURS, PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES,
            PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS, PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE,
            PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE, PrefKeys.PREF_UPKEEP_NOTIFY_POPUP,
            PrefKeys.PREF_UPKEEP_NOTIFIED_VERSION})
        {
            assertNotNull(key);
            assertFalse("key must not be empty", key.isEmpty()); //$NON-NLS-1$
        }
    }

    @Test
    public void upkeepKeysKeepTheirStoredNames()
    {
        assertEquals("mcpUpkeepEnabled", PrefKeys.PREF_UPKEEP_ENABLED); //$NON-NLS-1$
        assertEquals("mcpUpkeepSiteUrl", PrefKeys.PREF_UPKEEP_SITE_URL); //$NON-NLS-1$
        assertEquals("mcpUpkeepIntervalHours", PrefKeys.PREF_UPKEEP_INTERVAL_HOURS); //$NON-NLS-1$
        assertEquals("mcpUpkeepStartupDelayMinutes", //$NON-NLS-1$
            PrefKeys.PREF_UPKEEP_STARTUP_DELAY_MINUTES);
        assertEquals("mcpUpkeepLastCheckMillis", PrefKeys.PREF_UPKEEP_LAST_CHECK_MILLIS); //$NON-NLS-1$
        assertEquals("mcpUpkeepLastCheckSite", PrefKeys.PREF_UPKEEP_LAST_CHECK_SITE); //$NON-NLS-1$
        assertEquals("mcpUpkeepAllowLocalSite", PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE); //$NON-NLS-1$
        assertEquals("mcpUpkeepNotifyPopup", PrefKeys.PREF_UPKEEP_NOTIFY_POPUP); //$NON-NLS-1$
        assertEquals("mcpUpkeepNotifiedVersion", PrefKeys.PREF_UPKEEP_NOTIFIED_VERSION); //$NON-NLS-1$
    }

    @Test
    public void aFreshInstallLooksForNothing()
    {
        // The switch is the lock. ReleaseSweep gates every schedule, request and indicator on
        // "enabled && site.accepted()", so a shipped address starts nothing by itself. The address
        // used to be empty as a second lock; it now carries this plugin's own update site, because
        // the person turning the check on should not have to know that URL by heart. The check
        // itself stays off: reaching out on a fresh install is the user's decision, not ours.
        assertFalse("updates are not checked until asked for", PrefKeys.DEFAULT_UPKEEP_ENABLED); //$NON-NLS-1$
        assertEquals("the shipped address is this plugin's own update site", //$NON-NLS-1$
            "https://desko77.github.io/ai-edt/", PrefKeys.DEFAULT_UPKEEP_SITE_URL); //$NON-NLS-1$
        assertFalse("a local directory is a testing concession, not a default", //$NON-NLS-1$
            PrefKeys.DEFAULT_UPKEEP_ALLOW_LOCAL_SITE);
    }

    // ---- PrefKeys: shipped defaults --------------------------------------------

    @Test
    public void shippedPortIsAValidTcpPort()
    {
        assertTrue("port must be in range 1..65535", //$NON-NLS-1$
            PrefKeys.DEFAULT_PORT >= 1 && PrefKeys.DEFAULT_PORT <= 65535);
    }

    @Test
    public void shippedPortDoesNotCollideWithTheOtherEdtMcpPlugin()
    {
        // 8765 is another 1C EDT MCP plugin's shipped default. Installing both into one EDT would
        // leave whichever starts second unable to bind, and the UI says nothing about why. Nobody
        // would notice this in review either - it is a single innocuous-looking integer.
        assertNotEquals("the shipped port must stay clear of 8765", 8765, PrefKeys.DEFAULT_PORT); //$NON-NLS-1$
    }

    @Test
    public void shippedAutoStartIsOff()
    {
        // Installing the plugin must not open a socket on its own.
        assertFalse(PrefKeys.DEFAULT_AUTO_START);
    }

    @Test
    public void shippedDisabledToolsIsEmptySoEverythingStartsOn()
    {
        assertNotNull(PrefKeys.DEFAULT_DISABLED_TOOLS);
        assertTrue(PrefKeys.DEFAULT_DISABLED_TOOLS.isEmpty());
    }

    @Test
    public void shippedPlainTextModeIsOff()
    {
        assertFalse(PrefKeys.DEFAULT_PLAIN_TEXT_MODE);
    }

    @Test
    public void shippedAuthIsOffAndBindingIsLoopback()
    {
        // Safe by default only because the socket stays on loopback.
        assertFalse(PrefKeys.DEFAULT_AUTH_ENABLED);
        assertFalse(PrefKeys.DEFAULT_BIND_ALL_INTERFACES);
    }

    @Test
    public void shippedChecksFolderAndBsllsPathsAreBlank()
    {
        assertEquals("", PrefKeys.DEFAULT_CHECKS_FOLDER); //$NON-NLS-1$
        assertEquals("", PrefKeys.DEFAULT_BSL_LS_JAR); //$NON-NLS-1$
        assertEquals("", PrefKeys.DEFAULT_BSL_LS_JAVA); //$NON-NLS-1$
        assertEquals("", PrefKeys.DEFAULT_VANESSA_EPF); //$NON-NLS-1$
        assertEquals("", PrefKeys.DEFAULT_VANESSA_1C_EXE); //$NON-NLS-1$
        assertEquals("", PrefKeys.DEFAULT_AUTH_TOKEN); //$NON-NLS-1$
    }

    @Test
    public void shippedMarkerNavigatorDecorationIsOn()
    {
        assertTrue(PrefKeys.DEFAULT_MARKERS_SHOW_IN_NAVIGATOR);
    }

    // ---- PrefKeys: marker decoration styles ---------------------------------------

    @Test
    public void theThreeDecorationStylesAreShippedLiterals()
    {
        assertEquals("suffix", PrefKeys.MARKERS_STYLE_SUFFIX); //$NON-NLS-1$
        assertEquals("firstMarker", PrefKeys.MARKERS_STYLE_FIRST_MARKER);
        assertEquals("firstTag", PrefKeys.LEGACY_STYLE_FIRST_MARKER); //$NON-NLS-1$
        assertEquals("count", PrefKeys.MARKERS_STYLE_COUNT); //$NON-NLS-1$
    }

    @Test
    public void theThreeDecorationStylesAreMutuallyDistinct()
    {
        assertNotEquals(PrefKeys.MARKERS_STYLE_SUFFIX, PrefKeys.MARKERS_STYLE_FIRST_MARKER);
        assertNotEquals(PrefKeys.MARKERS_STYLE_SUFFIX, PrefKeys.MARKERS_STYLE_COUNT);
        assertNotEquals(PrefKeys.MARKERS_STYLE_FIRST_MARKER, PrefKeys.MARKERS_STYLE_COUNT);
    }

    @Test
    public void shippedDecorationStyleIsSuffix()
    {
        assertEquals(PrefKeys.MARKERS_STYLE_SUFFIX, PrefKeys.DEFAULT_MARKERS_DECORATION_STYLE);
    }

    // ---- PrefKeys: no-instance contract -----------------------------------------

    @Test
    public void constantHolderDeclaresNoPublicConstructors()
    {
        // A utility class of compile-time literals must not be instantiable.
        Constructor<?>[] declared = PrefKeys.class.getDeclaredConstructors();
        assertEquals("expected exactly one (implicit private) constructor", 1, declared.length); //$NON-NLS-1$
        assertFalse("the sole constructor must be private", //$NON-NLS-1$
            Modifier.isPublic(declared[0].getModifiers()));
    }

    // ---- ClusterKeys --------------------------------------------------------------------

    @Test
    public void clustersLiveUnderSettingsFolder()
    {
        assertEquals(".settings", ClusterKeys.SETTINGS_FOLDER); //$NON-NLS-1$
    }

    @Test
    public void clustersFileName()
    {
        assertEquals("aiedt-clusters.yaml", ClusterKeys.CLUSTERS_FILE);
        assertEquals("groups.yaml", ClusterKeys.LEGACY_CLUSTERS_FILE); //$NON-NLS-1$
    }

    @Test
    public void clustersPathIsFolderSlashFile()
    {
        assertEquals(ClusterKeys.SETTINGS_FOLDER + "/" + ClusterKeys.CLUSTERS_FILE, //$NON-NLS-1$
            ClusterKeys.CLUSTERS_PATH);
    }

    // ---- MarkerKeys ----------------------------------------------------------------------

    @Test
    public void markersLiveUnderSettingsFolder()
    {
        assertEquals(".settings", MarkerKeys.SETTINGS_FOLDER); //$NON-NLS-1$
    }

    @Test
    public void markersFileName()
    {
        assertEquals("aiedt-markers.yaml", MarkerKeys.MARKERS_FILE);
        assertEquals("metadata-tags.yaml", MarkerKeys.LEGACY_MARKERS_FILE); //$NON-NLS-1$
    }

    @Test
    public void markerFallbackColorIsMidGray()
    {
        assertEquals("#808080", MarkerKeys.DEFAULT_TAG_COLOR); //$NON-NLS-1$
    }

    @Test
    public void bmUriSchemePrefix()
    {
        assertEquals("bm://", MarkerKeys.BM_URI_SCHEME); //$NON-NLS-1$
    }

    @Test
    public void navigatorViewIdTargetsTheEdtMetadataTree()
    {
        assertNotNull(MarkerKeys.NAVIGATOR_VIEW_ID);
        assertFalse(MarkerKeys.NAVIGATOR_VIEW_ID.isEmpty());
        assertEquals("com._1c.g5.v8.dt.ui2.navigator", MarkerKeys.NAVIGATOR_VIEW_ID); //$NON-NLS-1$
    }

    @Test
    public void colorSwatchEdgeIsSixteenPixels()
    {
        assertEquals(16, MarkerKeys.COLOR_ICON_SIZE_NORMAL);
    }

    @Test
    public void markerConstantHolderDeclaresNoPublicConstructors()
    {
        Constructor<?>[] declared = MarkerKeys.class.getDeclaredConstructors();
        assertEquals(1, declared.length);
        assertFalse(Modifier.isPublic(declared[0].getModifiers()));
    }
}
