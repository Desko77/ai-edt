/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.eclipse.equinox.p2.metadata.Version;
import org.junit.Test;

import ru.aiedt.mcp.server.upkeep.UpkeepPolicy.SiteVerdict;

/**
 * Exercises the rules that decide what gets contacted and when. Three groups carry real weight:
 * the version comparison encodes a trap that silently produces an endless update offer, the URL
 * rules are the feature's only enforced limit on where executable code may come from, and the
 * interval arithmetic decides how often someone else's server is contacted.
 */
public class UpkeepPolicyTest
{
    @Test
    public void aVersionIsAnnouncedOnceAndNotEveryDay()
    {
        ReleaseOffer offer = ReleaseOffer.available(URI.create("https://example.org/s/"), //$NON-NLS-1$
            Version.create("3.1.0"), Version.create("3.2.0"), 1L); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(UpkeepPolicy.shouldAnnounce(offer, true, null));
        assertTrue(UpkeepPolicy.shouldAnnounce(offer, true, "3.1.9")); //$NON-NLS-1$
        // The check runs daily. Without this the same notice would appear every day until the
        // update was installed, which teaches people to dismiss it unread.
        assertFalse(UpkeepPolicy.shouldAnnounce(offer, true, "3.2.0")); //$NON-NLS-1$
    }

    @Test
    public void nothingIsAnnouncedWithoutAnUpdateOrWithoutConsent()
    {
        ReleaseOffer offer = ReleaseOffer.available(URI.create("https://example.org/s/"), //$NON-NLS-1$
            Version.create("3.1.0"), Version.create("3.2.0"), 1L); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(UpkeepPolicy.shouldAnnounce(offer, false, null));
        assertFalse(UpkeepPolicy.shouldAnnounce(ReleaseOffer.dormant(), true, null));
        assertFalse(UpkeepPolicy.shouldAnnounce(
            ReleaseOffer.upToDate(URI.create("https://example.org/s/"), //$NON-NLS-1$
                Version.create("3.2.0"), 1L), true, null)); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.shouldAnnounce(null, true, null));
    }

    private static final long MINUTE = 60L * 1000L;
    private static final long HOUR = 60L * MINUTE;
    private static final long NOW = 1_800_000_000_000L;

    // -----------------------------------------------------------------------
    // Version comparison
    // -----------------------------------------------------------------------

    @Test
    public void aBuildQualifierMakesTheSameNumberNewer()
    {
        // The reason the plugin's own advertised version cannot be used here: it carries no
        // qualifier, an empty qualifier sorts first, and so every check would offer an update to
        // the build that is already running.
        Version advertised = Version.create("3.1.0"); //$NON-NLS-1$
        Version built = Version.create("3.1.0.202608011200"); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isNewer(advertised, built));
        assertFalse(UpkeepPolicy.isNewer(built, advertised));
    }

    @Test
    public void onlyAStrictlyHigherVersionCounts()
    {
        Version installed = Version.create("3.1.0.202608011200"); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isNewer(installed, Version.create("3.2.0.202609011200"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isNewer(installed, Version.create("3.1.0.202608011200"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isNewer(installed, Version.create("3.0.9.202607011200"))); //$NON-NLS-1$
    }

    @Test
    public void anUnknownVersionIsNeverAnUpdate()
    {
        Version known = Version.create("3.1.0.202608011200"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isNewer(null, known));
        assertFalse(UpkeepPolicy.isNewer(known, null));
        assertFalse(UpkeepPolicy.isNewer(null, null));
    }

    // -----------------------------------------------------------------------
    // Which sites may be consulted
    // -----------------------------------------------------------------------

    @Test
    public void anEmptySettingIsNotAnError()
    {
        for (String blank : new String[] {null, "", "   "}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            SiteVerdict verdict = UpkeepPolicy.examineSite(blank, false);
            assertFalse("blank must not be configured", verdict.configured()); //$NON-NLS-1$
            assertFalse(verdict.accepted());
            assertNull("a blank setting is the default, not a fault", verdict.reason()); //$NON-NLS-1$
        }
    }

    @Test
    public void httpsIsAccepted()
    {
        SiteVerdict verdict = UpkeepPolicy.examineSite("https://example.org/site/", false); //$NON-NLS-1$
        assertTrue(verdict.accepted());
        assertTrue(verdict.configured());
        assertEquals(URI.create("https://example.org/site/"), verdict.uri()); //$NON-NLS-1$
    }

    @Test
    public void plainHttpIsRefusedBecauseThePayloadIsExecutable()
    {
        SiteVerdict verdict = UpkeepPolicy.examineSite("http://example.org/site/", true); //$NON-NLS-1$
        assertFalse(verdict.accepted());
        assertNotNull(verdict.reason());
        assertTrue(verdict.reason().contains("https")); //$NON-NLS-1$
    }

    @Test
    public void aLocalSiteNeedsItsOwnPermission()
    {
        String local = "file:/C:/builds/aiedt-repository/"; //$NON-NLS-1$
        assertFalse(UpkeepPolicy.examineSite(local, false).accepted());
        assertTrue(UpkeepPolicy.examineSite(local, true).accepted());
    }

    @Test
    public void aFileUrlWithAHostIsANetworkPathAndStaysRefused()
    {
        // On Windows file://server/share is UNC. Accepting it would turn the local-testing
        // concession into a way to fetch executable code over the network without https.
        SiteVerdict verdict = UpkeepPolicy.examineSite("file://build-server/share/repo/", true); //$NON-NLS-1$
        assertFalse("UNC must be refused even when local sites are allowed", verdict.accepted()); //$NON-NLS-1$
        assertNotNull(verdict.reason());
    }

    @Test
    public void otherSchemesAreRefused()
    {
        assertFalse(UpkeepPolicy.examineSite("ftp://example.org/site/", true).accepted()); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.examineSite("jar:file:/x.zip!/", true).accepted()); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.examineSite("mailto:someone@example.org", true).accepted()); //$NON-NLS-1$
    }

    @Test
    public void anAddressWithoutASchemeIsRefused()
    {
        assertFalse(UpkeepPolicy.examineSite("example.org/site/", true).accepted()); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.examineSite("/var/repo", true).accepted()); //$NON-NLS-1$
    }

    @Test
    public void malformedInputIsRefusedRatherThanThrown()
    {
        SiteVerdict verdict = UpkeepPolicy.examineSite("https://exa mple.org/site", true); //$NON-NLS-1$
        assertFalse(verdict.accepted());
        assertNotNull(verdict.reason());
    }

    @Test
    public void credentialsInTheUrlAreRefused()
    {
        assertFalse(UpkeepPolicy.examineSite("https://user:secret@example.org/site/", true) //$NON-NLS-1$
            .accepted());
    }

    @Test
    public void aPathThatClimbsAboveTheRootIsRefused()
    {
        // Encoded on purpose: URI.normalize() works on the raw path and leaves %2e%2e untouched,
        // so this is the form that survives normalization and reaches the comparison.
        assertFalse(UpkeepPolicy.examineSite("https://example.org/site/%2e%2e/%2e%2e/", true) //$NON-NLS-1$
            .accepted());
    }

    @Test
    public void httpsWithoutAHostIsRefused()
    {
        assertFalse(UpkeepPolicy.examineSite("https:///site/", true).accepted()); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Which addresses belong to the configured source
    // -----------------------------------------------------------------------

    @Test
    public void aSubtreeOfTheConfiguredSiteIsInside()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isWithin(site, URI.create("https://example.org/site/"))); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isWithin(site,
            URI.create("https://example.org/site/plugins/ru.aiedt.mcp.server_3.2.0.jar"))); //$NON-NLS-1$
    }

    @Test
    public void aSiblingSharingATextPrefixIsOutside()
    {
        // The reason a string prefix test is not usable: "/site" is a prefix of "/site-evil".
        URI site = URI.create("https://example.org/site"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://example.org/site-evil/x.jar"))); //$NON-NLS-1$
    }

    @Test
    public void anotherHostOrSchemeIsOutside()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://example.com/site/x.jar"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("http://example.org/site/x.jar"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://evil.example.org/site/x.jar"))); //$NON-NLS-1$
    }

    @Test
    public void anotherPortIsOutsideAndTheDefaultPortIsTheSamePort()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://example.org:8443/site/x.jar"))); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isWithin(site, URI.create("https://example.org:443/site/x.jar"))); //$NON-NLS-1$
    }

    @Test
    public void theHostIsCaseInsensitiveAndThePathIsNot()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isWithin(site, URI.create("https://EXAMPLE.ORG/site/x.jar"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://example.org/SITE/x.jar"))); //$NON-NLS-1$
    }

    @Test
    public void aRelativeStepOutOfTheSubtreeIsOutsideEncodedOrNot()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://example.org/site/../evil/x.jar"))); //$NON-NLS-1$
        assertFalse(
            UpkeepPolicy.isWithin(site, URI.create("https://example.org/site/%2e%2e/evil/x.jar"))); //$NON-NLS-1$
    }

    @Test
    public void aShorterPathIsOutside()
    {
        URI site = URI.create("https://example.org/team/site/"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("https://example.org/team/"))); //$NON-NLS-1$
    }

    @Test
    public void localSitesFollowTheSameSubtreeRule()
    {
        URI site = URI.create("file:/C:/builds/repository/"); //$NON-NLS-1$
        assertTrue(UpkeepPolicy.isWithin(site, URI.create("file:/C:/builds/repository/plugins/a.jar"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("file:/C:/builds/other/a.jar"))); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("file://server/builds/repository/a.jar"))); //$NON-NLS-1$
    }

    @Test
    public void nothingIsInsideNothing()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertFalse(UpkeepPolicy.isWithin(null, site));
        assertFalse(UpkeepPolicy.isWithin(site, null));
        assertFalse(UpkeepPolicy.isWithin(site, URI.create("site/x.jar"))); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Schedule arithmetic
    // -----------------------------------------------------------------------

    @Test
    public void theIntervalIsClampedIntoASaneRange()
    {
        assertEquals(24 * HOUR, UpkeepPolicy.intervalMillis(24));
        assertEquals(HOUR, UpkeepPolicy.intervalMillis(0));
        assertEquals(HOUR, UpkeepPolicy.intervalMillis(-5));
        assertEquals(24L * 365L * HOUR, UpkeepPolicy.intervalMillis(Integer.MAX_VALUE));
    }

    @Test
    public void aFailedCheckIsRetriedSoonerThanTheFullInterval()
    {
        assertTrue(UpkeepPolicy.retryIntervalMillis(24) < UpkeepPolicy.intervalMillis(24));
        // Never longer than the configured interval, so a short interval is not lengthened.
        assertTrue(UpkeepPolicy.retryIntervalMillis(1) <= UpkeepPolicy.intervalMillis(1));
    }

    @Test
    public void theStartupDelayIsClamped()
    {
        assertEquals(5 * MINUTE, UpkeepPolicy.startupDelayMillis(5));
        assertEquals(MINUTE, UpkeepPolicy.startupDelayMillis(0));
        assertEquals(24L * 60L * MINUTE, UpkeepPolicy.startupDelayMillis(Integer.MAX_VALUE));
    }

    @Test
    public void theFirstCheckWaitsOutTheStartupDelay()
    {
        assertEquals(5 * MINUTE,
            UpkeepPolicy.delayUntilDueMillis(NOW, 0L, 24 * HOUR, 5 * MINUTE));
    }

    @Test
    public void aRecentCheckIsNotRepeatedByRestarting()
    {
        // Six restarts in a day must not produce six checks, so the wait is counted from the last
        // check rather than from startup.
        long lastCheck = NOW - HOUR;
        assertEquals(23 * HOUR,
            UpkeepPolicy.delayUntilDueMillis(NOW, lastCheck, 24 * HOUR, 5 * MINUTE));
    }

    @Test
    public void anOverdueCheckStillWaitsOutTheStartupDelay()
    {
        long lastCheck = NOW - 48 * HOUR;
        assertEquals(5 * MINUTE,
            UpkeepPolicy.delayUntilDueMillis(NOW, lastCheck, 24 * HOUR, 5 * MINUTE));
    }

    @Test
    public void aMarkInTheFutureDoesNotParkTheCheckForMonths()
    {
        long lastCheck = NOW + 300L * 24L * HOUR;
        assertEquals(24 * HOUR,
            UpkeepPolicy.delayUntilDueMillis(NOW, lastCheck, 24 * HOUR, 5 * MINUTE));
    }

    @Test
    public void dueness()
    {
        assertTrue("never checked", UpkeepPolicy.isDue(NOW, 0L, 24 * HOUR)); //$NON-NLS-1$
        assertFalse("checked an hour ago", UpkeepPolicy.isDue(NOW, NOW - HOUR, 24 * HOUR)); //$NON-NLS-1$
        assertTrue("checked a day ago", UpkeepPolicy.isDue(NOW, NOW - 24 * HOUR, 24 * HOUR)); //$NON-NLS-1$
        assertTrue("clock moved backwards", UpkeepPolicy.isDue(NOW, NOW + HOUR, 24 * HOUR)); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // The mark belongs to an address
    // -----------------------------------------------------------------------

    @Test
    public void theMarkCountsOnlyForTheSiteItWasMadeFor()
    {
        URI site = URI.create("https://example.org/site/"); //$NON-NLS-1$
        assertEquals(NOW, UpkeepPolicy.markFor(site, "https://example.org/site/", NOW)); //$NON-NLS-1$
        assertEquals(NOW, UpkeepPolicy.markFor(site, "  https://example.org/site/  ", NOW)); //$NON-NLS-1$
    }

    @Test
    public void correctingTheUrlDoesNotPostponeTheFirstCheckOfTheNewOne()
    {
        URI corrected = URI.create("https://example.org/correct/"); //$NON-NLS-1$
        assertEquals(0L, UpkeepPolicy.markFor(corrected, "https://example.org/typo/", NOW)); //$NON-NLS-1$
        assertEquals(0L, UpkeepPolicy.markFor(corrected, null, NOW));
        assertEquals(0L, UpkeepPolicy.markFor(null, "https://example.org/correct/", NOW)); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // When work may start
    // -----------------------------------------------------------------------

    @Test
    public void onlyAPendingRestartBlocksNewWork()
    {
        for (ReleaseOffer.State state : ReleaseOffer.State.values())
        {
            boolean expected = state != ReleaseOffer.State.RESTART_PENDING;
            assertEquals(state.name(), expected, UpkeepPolicy.mayStartWork(state));
        }
    }
}
