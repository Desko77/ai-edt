/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Covers the compatibility mode a freshly created configuration project declares.
 * <p>
 * A project whose compatibility mode outranks its runtime version is not merely
 * mislabelled - it resolves no platform type at all, so every attribute in it reads
 * as "unknown type" while the .mdo files on disk are perfectly correct. The symptom
 * points at the wrong culprit, which is why this is pinned by a test rather than
 * left to be rediscovered.
 * </p>
 */
public class ConfigurationProjectShapeTest
{
    @Test
    public void aRuntimeVersionWithAModeOfItsOwnGetsThatExactMode()
    {
        assertEquals(CompatibilityMode.get("8.3.21"), //$NON-NLS-1$
            BmConfigurationProjectHelper.compatibilityModeFor(Version.create("8.3.21"))); //$NON-NLS-1$
    }

    @Test
    public void theModeNeverOutranksTheRuntimeItWasResolvedFor()
    {
        for (Version version : new Version[] {Version.create("8.3.14"), //$NON-NLS-1$
            Version.create("8.3.21"), Version.create("8.3.27")}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            CompatibilityMode mode = BmConfigurationProjectHelper.compatibilityModeFor(version);
            assertNotNull("every supported runtime deserves a mode", mode); //$NON-NLS-1$
            assertTrue("mode " + mode.getLiteral() + " outranks runtime " + version, //$NON-NLS-1$ //$NON-NLS-2$
                compare(mode.getLiteral(), version.toString()) <= 0);
        }
    }

    @Test
    public void aRuntimeBetweenTwoKnownModesTakesTheLowerOne()
    {
        // 8.4.x has no mode of its own - the nearest one that a 8.4 runtime can
        // still honour is 8.3.27, and reaching up to 8.5.1 is the failure this
        // whole helper exists to prevent.
        CompatibilityMode mode =
            BmConfigurationProjectHelper.compatibilityModeFor(Version.create("8.4.5")); //$NON-NLS-1$
        assertNotNull(mode);
        assertEquals("8.3.27", mode.getLiteral()); //$NON-NLS-1$
    }

    @Test
    public void aRuntimeAboveEveryKnownModeTakesTheHighestOne()
    {
        CompatibilityMode mode =
            BmConfigurationProjectHelper.compatibilityModeFor(Version.create("9.1.0")); //$NON-NLS-1$
        assertNotNull(mode);
        assertEquals("8.5.1", mode.getLiteral()); //$NON-NLS-1$
    }

    @Test
    public void anAbsentVersionLeavesTheModelDefaultAlone()
    {
        assertNull("with nothing to match, imposing a mode would be a guess", //$NON-NLS-1$
            BmConfigurationProjectHelper.compatibilityModeFor(null));
    }

    private static int compare(String left, String right)
    {
        String[] leftParts = left.split("\\."); //$NON-NLS-1$
        String[] rightParts = right.split("\\."); //$NON-NLS-1$
        for (int i = 0; i < 3; i++)
        {
            int a = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int b = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            if (a != b)
            {
                return a < b ? -1 : 1;
            }
        }
        return 0;
    }
}
