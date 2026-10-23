/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.query.IQueryable;
import org.junit.Test;

/**
 * Covers the two checks that stand between a resolved plan and the running IDE: that the plan is
 * confined to this plugin, and that what it installs is what the caller approved.
 * <p>
 * Neither is a barrier against a hostile site - nothing here is - but both catch the failures that
 * would otherwise be silent. The list handed to an update operation is only a root; p2 works out the
 * rest, and a plan that quietly reached further would look exactly like a successful one.
 * </p>
 */
public class ReleaseAdoptionTest
{
    private static final URI SITE = URI.create("https://example.org/aiedt/"); //$NON-NLS-1$
    private static final Version VERSION = Version.create("3.2.0.202609011200"); //$NON-NLS-1$

    @Test
    public void anUpdateMayTouchTheFeatureItsJarAndBothBundles()
    {
        // Four, not one. Naming only the feature group would refuse the ordinary shape of our own
        // update, because the group requires its jar and both bring their bundle.
        assertTrue(ReleaseAdoption.ALLOWED_UNITS.contains("ru.aiedt.mcp.server.feature.feature.group")); //$NON-NLS-1$
        assertTrue(ReleaseAdoption.ALLOWED_UNITS.contains("ru.aiedt.mcp.server.feature.feature.jar")); //$NON-NLS-1$
        assertTrue(ReleaseAdoption.ALLOWED_UNITS.contains("ru.aiedt.mcp.server")); //$NON-NLS-1$
        assertTrue("the ordinary-forms bundle is listed by its exact id", //$NON-NLS-1$
            ReleaseAdoption.ALLOWED_UNITS.contains("ru.aiedt.mcp.oform")); //$NON-NLS-1$
        assertFalse("the allow-list must not grow silently", //$NON-NLS-1$
            ReleaseAdoption.ALLOWED_UNITS.size() != 4);
    }

    @Test
    public void aPlanConfinedToThisPluginRaisesNothing()
    {
        Set<String> strays = new LinkedHashSet<>();
        ReleaseAdoption.collectStrays(
            queryableOf(unit("ru.aiedt.mcp.server.feature.feature.group", "3.2.0"), //$NON-NLS-1$ //$NON-NLS-2$
                unit("ru.aiedt.mcp.server.feature.feature.jar", "3.2.0"), //$NON-NLS-1$ //$NON-NLS-2$
                unit("ru.aiedt.mcp.server", "3.2.0"), //$NON-NLS-1$ //$NON-NLS-2$
                unit("ru.aiedt.mcp.oform", "3.2.0")), //$NON-NLS-1$ //$NON-NLS-2$
            strays);
        assertTrue(strays.toString(), strays.isEmpty());
        assertNull("a plan of exactly the four allowed units is not a refusal", //$NON-NLS-1$
            ReleaseAdoption.refusalOf(strays));
    }

    @Test
    public void theLegacyPrivateFeatureIsRefusedWithItsRemovalNamed()
    {
        Set<String> strays = new LinkedHashSet<>();
        ReleaseAdoption.collectStrays(
            queryableOf(unit("ru.aiedt.mcp.server", "3.2.0"), //$NON-NLS-1$ //$NON-NLS-2$
                unit("ru.aiedt.mcp.oform.feature.feature.group", "0.1.0")), //$NON-NLS-1$ //$NON-NLS-2$
            strays);
        assertTrue(strays.toString(), strays.size() == 1);

        String refusal = ReleaseAdoption.refusalOf(strays);
        assertTrue("the legacy feature is named: " + refusal, //$NON-NLS-1$
            refusal.contains("ru.aiedt.mcp.oform.feature.feature.group")); //$NON-NLS-1$
        assertTrue("the refusal says what to do with it: " + refusal, //$NON-NLS-1$
            refusal.contains("uninstalled through p2")); //$NON-NLS-1$
        assertTrue("and that this update removes nothing itself: " + refusal, //$NON-NLS-1$
            refusal.contains("removes nothing itself")); //$NON-NLS-1$
    }

    @Test
    public void theLegacyFeatureJarIsTheSameLegacyAndALookAlikeIsOnlyAStray()
    {
        Set<String> strays = new LinkedHashSet<>();
        ReleaseAdoption.collectStrays(
            queryableOf(unit("ru.aiedt.mcp.oform.feature.feature.jar", "0.1.0")), //$NON-NLS-1$ //$NON-NLS-2$
            strays);
        assertTrue(ReleaseAdoption.refusalOf(strays).contains("uninstalled through p2")); //$NON-NLS-1$

        Set<String> lookAlike = new LinkedHashSet<>();
        ReleaseAdoption.collectStrays(
            queryableOf(unit("ru.aiedt.mcp.oform.reporting", "9.9.9")), lookAlike); //$NON-NLS-1$ //$NON-NLS-2$
        String plain = ReleaseAdoption.refusalOf(lookAlike);
        assertTrue("an id merely starting with ours must not pass", plain.contains("ru.aiedt.mcp.oform.reporting")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and it is not read as the legacy feature", //$NON-NLS-1$
            plain.contains("uninstalled through p2")); //$NON-NLS-1$
    }

    @Test
    public void anythingElseInThePlanIsNamedAndRefused()
    {
        Set<String> strays = new LinkedHashSet<>();
        ReleaseAdoption.collectStrays(
            queryableOf(unit("ru.aiedt.mcp.server", "3.2.0"), //$NON-NLS-1$ //$NON-NLS-2$
                unit("com._1c.g5.v8.dt.feature.feature.group", "2026.1.0")), //$NON-NLS-1$ //$NON-NLS-2$
            strays);

        assertTrue(strays.toString(), strays.size() == 1);
        // Named, not just counted: a refusal that does not say what the plan wanted to change leaves
        // the user with nothing to judge.
        assertTrue(strays.toString(),
            strays.iterator().next().contains("com._1c.g5.v8.dt.feature.feature.group")); //$NON-NLS-1$
    }

    @Test
    public void aLookAlikeIdIsNotMistakenForOurs()
    {
        Set<String> strays = new LinkedHashSet<>();
        ReleaseAdoption.collectStrays(
            queryableOf(unit("ru.aiedt.mcp.server.evil", "9.9.9")), strays); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an id merely starting with ours must not pass", strays.size() == 1); //$NON-NLS-1$
    }

    @Test
    public void nothingToInstallIsRefusedBeforeAnythingIsTouched()
    {
        assertRefused(ReleaseAdoption.install(null, VERSION, null));
        assertRefused(ReleaseAdoption.install(SITE, null, null));
    }

    @Test
    public void anInstallationP2DoesNotManageIsRefusedWithoutReachingTheSite()
    {
        // In this runtime p2 holds no profile for the code under test, so the refusal must come from
        // the local check. If it ever did reach for the site, this test would be the one that hangs.
        ReleaseAdoption.Outcome outcome = ReleaseAdoption.install(SITE, VERSION, null);
        assertRefused(outcome);
        assertTrue(outcome.problem(), outcome.problem().contains("p2")); //$NON-NLS-1$
    }

    private static void assertRefused(ReleaseAdoption.Outcome outcome)
    {
        assertNotNull(outcome);
        assertFalse(outcome.applied());
        assertNotNull(outcome.problem());
        assertNull(outcome.version());
    }

    private static IInstallableUnit unit(String id, String version)
    {
        InstallableUnitDescription description = new InstallableUnitDescription();
        description.setId(id);
        description.setVersion(Version.create(version));
        return MetadataFactory.createInstallableUnit(description);
    }

    private static IQueryable<IInstallableUnit> queryableOf(IInstallableUnit... units)
    {
        List<IInstallableUnit> all = new ArrayList<>(List.of(units));
        return (query, monitor) -> query.perform(all.iterator());
    }
}
