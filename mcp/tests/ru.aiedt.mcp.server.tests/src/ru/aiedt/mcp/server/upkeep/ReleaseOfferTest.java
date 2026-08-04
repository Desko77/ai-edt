/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.eclipse.equinox.p2.metadata.Version;
import org.junit.Test;

import ru.aiedt.mcp.server.upkeep.ReleaseOffer.State;

/**
 * Pins what each snapshot claims, and the two properties the rest of the feature leans on: that a
 * snapshot never changes underneath a reader, and that starting a piece of work carries the present
 * knowledge forward instead of blanking it.
 */
public class ReleaseOfferTest
{
    private static final URI SITE = URI.create("https://example.org/site/"); //$NON-NLS-1$
    private static final Version INSTALLED = Version.create("3.1.0.202608011200"); //$NON-NLS-1$
    private static final Version OFFERED = Version.create("3.2.0.202609011200"); //$NON-NLS-1$
    private static final long CHECKED_AT = 1_800_000_000_000L;

    @Test
    public void aFreshInstallationIsAsleep()
    {
        ReleaseOffer offer = ReleaseOffer.dormant();
        assertEquals(State.DORMANT, offer.state());
        assertNull("nothing is configured yet", offer.site()); //$NON-NLS-1$
        assertFalse(offer.hasUpdate());
        assertEquals(0L, offer.checkedAtMillis());
    }

    @Test
    public void aConfiguredSiteStartsWithoutAnAnswer()
    {
        ReleaseOffer offer = ReleaseOffer.noData(SITE);
        assertEquals(State.NO_DATA, offer.state());
        assertEquals(SITE, offer.site());
        assertTrue("management is assumed until a lookup says otherwise", offer.managed()); //$NON-NLS-1$
        assertFalse(offer.hasUpdate());
    }

    @Test
    public void anInstallationOutsideP2IsReportedAsUnmanagedRatherThanBroken()
    {
        ReleaseOffer offer = ReleaseOffer.unmanaged(SITE, "installed from dropins"); //$NON-NLS-1$
        assertFalse(offer.managed());
        assertNotNull(offer.note());
        assertFalse("nothing may be offered for an installation p2 does not own", //$NON-NLS-1$
            offer.hasUpdate());
    }

    @Test
    public void anOfferCarriesBothVersionsAndItsSource()
    {
        ReleaseOffer offer = ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT);
        assertEquals(State.UPDATE_AVAILABLE, offer.state());
        assertTrue(offer.hasUpdate());
        assertEquals(SITE, offer.site());
        assertEquals(INSTALLED, offer.installed());
        assertEquals(OFFERED, offer.offered());
        assertEquals(CHECKED_AT, offer.checkedAtMillis());
    }

    @Test
    public void anUpToDateAnswerOffersNothing()
    {
        ReleaseOffer offer = ReleaseOffer.upToDate(SITE, INSTALLED, CHECKED_AT);
        assertEquals(State.UP_TO_DATE, offer.state());
        assertFalse(offer.hasUpdate());
        assertNull(offer.offered());
    }

    @Test
    public void aFailedCheckKeepsWhatTheProfileAlreadyTold()
    {
        // The installed version comes from the profile and needs no network, so a failed check has
        // no reason to forget it.
        ReleaseOffer offer = ReleaseOffer.failed(SITE, INSTALLED, "host unreachable", CHECKED_AT); //$NON-NLS-1$
        assertEquals(State.CHECK_FAILED, offer.state());
        assertEquals(INSTALLED, offer.installed());
        assertEquals("host unreachable", offer.note()); //$NON-NLS-1$
        assertFalse(offer.hasUpdate());
    }

    @Test
    public void aPendingRestartAsksForARestartAndNotForAnotherInstall()
    {
        ReleaseOffer offer = ReleaseOffer.restartPending(SITE, INSTALLED, OFFERED, CHECKED_AT);
        assertEquals(State.RESTART_PENDING, offer.state());
        assertEquals("the version running right now", INSTALLED, offer.installed()); //$NON-NLS-1$
        assertEquals("the version a restart will activate", OFFERED, offer.offered()); //$NON-NLS-1$
        assertFalse("what it needs is a restart, not an install", offer.hasUpdate()); //$NON-NLS-1$
    }

    @Test
    public void startingWorkCarriesKnowledgeForwardInsteadOfBlankingIt()
    {
        // Otherwise the status bar would drop its marker for the duration of every check, and a
        // check that fails would erase a perfectly good offer.
        ReleaseOffer known = ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT);

        ReleaseOffer checking = known.checking();
        assertEquals(State.CHECKING, checking.state());
        assertEquals(SITE, checking.site());
        assertEquals(INSTALLED, checking.installed());
        assertEquals(OFFERED, checking.offered());
        assertEquals(CHECKED_AT, checking.checkedAtMillis());

        ReleaseOffer installing = known.installing();
        assertEquals(State.INSTALLING, installing.state());
        assertEquals(OFFERED, installing.offered());
    }

    @Test
    public void aTransitionProducesANewSnapshotAndLeavesTheOldOneAlone()
    {
        ReleaseOffer known = ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT);
        ReleaseOffer checking = known.checking();
        assertNotSame(known, checking);
        assertEquals("the snapshot a reader already holds must not change", //$NON-NLS-1$
            State.UPDATE_AVAILABLE, known.state());
        assertSame(known.site(), checking.site());
    }

    @Test
    public void theDescriptionNamesTheStateAndBothVersions()
    {
        String text = ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT).toString();
        assertTrue(text, text.contains("UPDATE_AVAILABLE")); //$NON-NLS-1$
        assertTrue(text, text.contains(INSTALLED.toString()));
        assertTrue(text, text.contains(OFFERED.toString()));
    }
}
