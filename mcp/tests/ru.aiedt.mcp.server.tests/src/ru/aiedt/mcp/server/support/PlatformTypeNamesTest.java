/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.version.Version;

import ru.aiedt.mcp.server.support.PlatformTypeNames.Verdict;

/**
 * What the platform's type register is allowed to make us conclude.
 * <p>
 * The point of the class under test is a single distinction the register itself
 * does not draw: a name it does not know and a register that knows nothing both
 * come back as a null lookup. Believing the second would reject every type in
 * the language, so it has to surface as CANNOT_TELL - and that is what most of
 * these tests are here to hold.
 * </p>
 * <p>
 * The register is filled per platform version by contributions from the
 * {@code com._1c.g5.v8.dt.platform_v8.3.NN} bundles. This fragment's pom names
 * exactly one of them for the test launch, which is what gives the suite both a
 * populated register and an empty one to compare against.
 * </p>
 */
public class PlatformTypeNamesTest
{
    /** The version whose bundle the test launch pulls in; see this fragment's pom. */
    private static final Version PRESENT = Version.V8_3_22;

    /** A version whose bundle is NOT in the launch - the empty-register case. */
    private static final Version ABSENT = Version.V8_3_18;

    @Test
    public void aRealTypeNameIsKnown()
    {
        assertEquals(Verdict.KNOWN, PlatformTypeNames.checkForVersion("String", PRESENT)); //$NON-NLS-1$
        assertEquals(Verdict.KNOWN, PlatformTypeNames.checkForVersion("ValueTable", PRESENT)); //$NON-NLS-1$
    }

    @Test
    public void theNamesNoAllowlistWouldHaveHeldAreKnownToo()
    {
        // Legal bare type names, none of them a primitive. Enumerating names by hand
        // was tried and lost to these.
        for (String name : new String[] { "DynamicList", "SpreadsheetDocument", //$NON-NLS-1$ //$NON-NLS-2$
            "StandardPeriod", "TypeDescription", "Picture", "AnyRef" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertEquals(name, Verdict.KNOWN, PlatformTypeNames.checkForVersion(name, PRESENT));
        }
    }

    @Test
    public void aTypoIsUnknown()
    {
        // The whole reason the class exists: both of these used to be written onto an
        // attribute and reported as applied.
        assertEquals(Verdict.UNKNOWN, PlatformTypeNames.checkForVersion("Stirng", PRESENT)); //$NON-NLS-1$
        assertEquals(Verdict.UNKNOWN,
            PlatformTypeNames.checkForVersion("NoSuchTypeAtAll", PRESENT)); //$NON-NLS-1$
    }

    @Test
    public void anEmptyRegisterCannotTellUsAnything()
    {
        // NOT a curiosity - this is the failure mode the class is built around. The
        // provider is non-null and every lookup misses, so a version without its
        // bundle is indistinguishable from a misspelling. Answering UNKNOWN here
        // would refuse String, Number and everything else.
        assertEquals(Verdict.CANNOT_TELL, PlatformTypeNames.checkForVersion("String", ABSENT)); //$NON-NLS-1$
    }

    @Test
    public void anEmptyRegisterDoesNotEvenCondemnATypo()
    {
        // Same lookup, same null, and still no verdict: the register has to prove it
        // knows something before any of its misses count.
        assertEquals(Verdict.CANNOT_TELL, PlatformTypeNames.checkForVersion("Stirng", ABSENT)); //$NON-NLS-1$
    }

    @Test
    public void nothingToAskAboutMeansNoVerdict()
    {
        assertEquals(Verdict.CANNOT_TELL, PlatformTypeNames.checkForVersion(null, PRESENT));
        assertEquals(Verdict.CANNOT_TELL, PlatformTypeNames.checkForVersion("", PRESENT)); //$NON-NLS-1$
        assertEquals(Verdict.CANNOT_TELL, PlatformTypeNames.checkForVersion("String", null)); //$NON-NLS-1$
    }

    @Test
    public void withoutAProjectThereIsNoVersionToAskAbout()
    {
        assertEquals(Verdict.CANNOT_TELL, PlatformTypeNames.check("String", null)); //$NON-NLS-1$
    }
}
