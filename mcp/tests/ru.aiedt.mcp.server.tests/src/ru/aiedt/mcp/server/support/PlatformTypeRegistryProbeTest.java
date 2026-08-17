/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * The platform's own register of type names, and what it takes to get an answer from it.
 * <p>
 * Type names are accepted here on their SHAPE alone - any capitalized ASCII word passes as
 * a primitive, any {@code <Something>Ref.<anything>} passes as a reference - so
 * {@code Stirng} and {@code CatalogRef.NoSuchCatalog} are written onto an attribute and
 * reported as applied. An allowlist cannot fix it: one demo configuration uses 41 distinct
 * legal bare type names on form attributes, among them DynamicList, SpreadsheetDocument,
 * StandardPeriod and TypeDescription. Only the platform knows the whole vocabulary.
 * </p>
 * <p>
 * These tests pin the route to it, because the route has a trap in it. The register is
 * keyed by 1C platform VERSION, and it is filled by IEObjectProviderExtension
 * contributions that live in the per-version bundles. Ask for a version whose bundle is
 * not installed and you get a provider that is not null and knows NOTHING - it answers
 * null to every name, exactly as it would for a genuine typo. A check written against
 * that would reject every type in the language. Hence the pom names one version bundle
 * for the test launch, and hence the emptiness itself is asserted here rather than left
 * for someone to discover.
 * </p>
 */
public class PlatformTypeRegistryProbeTest
{
    /** The version whose bundle the test launch pulls in; see this fragment's pom. */
    private static final Version PRESENT = Version.V8_3_22;

    /** A version whose bundle is NOT in the launch - the empty-register case. */
    private static final Version ABSENT = Version.V8_3_18;

    private static IEObjectProvider types(Version version)
    {
        return IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
    }

    @Test
    public void theRegisterIsReachableWithoutReflection()
    {
        // com._1c.g5.v8.dt.platform is already in our Import-Package, so this is a plain
        // call - no reflection, no new dependency.
        assertNotNull(types(PRESENT));
    }

    @Test
    public void realPlatformTypesAreFound()
    {
        assertNotNull(types(PRESENT).getEObjectDescription("String")); //$NON-NLS-1$
        assertNotNull(types(PRESENT).getEObjectDescription("ValueTable")); //$NON-NLS-1$
        assertNotNull(types(PRESENT).getEObjectDescription("ValueTree")); //$NON-NLS-1$
        assertNotNull(types(PRESENT).getEObjectDescription("ValueList")); //$NON-NLS-1$
    }

    @Test
    public void theNamesThatDefeatedAnAllowlistAreAllInThere()
    {
        // Every one of these is a legal form-attribute type and none is a primitive, which
        // is why enumerating names by hand was never going to work.
        for (String name : new String[] { "DynamicList", "SpreadsheetDocument", //$NON-NLS-1$ //$NON-NLS-2$
            "StandardPeriod", "TypeDescription", "Picture", "AnyRef" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertNotNull(name + " is missing from the platform register", //$NON-NLS-1$
                types(PRESENT).getEObjectDescription(name));
        }
    }

    @Test
    public void aTypoIsNotFound()
    {
        // The point of the whole exercise. Today both of these are accepted and reported
        // as applied.
        assertNull(types(PRESENT).getEObjectDescription("Stirng")); //$NON-NLS-1$
        assertNull(types(PRESENT).getEObjectDescription("NoSuchTypeAtAll")); //$NON-NLS-1$
    }

    @Test
    public void theRegisterHoldsAWholeVocabulary()
    {
        assertTrue(count(PRESENT) > 1000);
    }

    @Test
    public void aVersionWithoutItsBundleAnswersNothingAtAll()
    {
        // NOT a curiosity - this is the failure mode a caller has to guard against. The
        // provider is non-null and every lookup misses, so "unknown platform version"
        // looks precisely like "you spelled the type wrong". Anything built on this
        // register must check that it knows SOMETHING before trusting a miss.
        IEObjectProvider absent = types(ABSENT);
        assertNotNull(absent);
        assertNull(absent.getEObjectDescription("String")); //$NON-NLS-1$
        assertTrue(count(ABSENT) == 0);
    }

    private static int count(Version version)
    {
        IEObjectProvider provider = types(version);
        int found = 0;
        for (IEObjectDescription ignored : provider.getEObjectDescriptions(null))
        {
            found++;
        }
        List<URI> resources = new ArrayList<>();
        provider.collectResources(resources);
        return found;
    }
}
