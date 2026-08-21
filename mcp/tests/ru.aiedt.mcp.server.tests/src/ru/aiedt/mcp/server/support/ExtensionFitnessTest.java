/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the check that asks what a release breaks in an extension.
 * <p>
 * The walk itself needs two loaded configurations and is measured on a stand. What is pinned here
 * is the boundary: a project that is not there is refused by name rather than answered with an
 * empty verdict, because "nothing was found" and "nothing was looked at" read the same in an answer
 * and mean opposite things - and the second one, read as the first, is how an extension ships
 * against a release it does not fit.
 * </p>
 */
public class ExtensionFitnessTest
{
    @Test
    public void anAbsentExtensionIsRefusedRatherThanAnsweredEmpty()
    {
        ExtensionFitness.Verdict verdict =
            ExtensionFitness.check("no such extension", "no such base");
        assertNotNull("an empty finding list from a project nobody found would be read as a clean "
            + "bill of health", verdict.cannotTell);
        assertTrue(verdict.findings.isEmpty());
    }

    @Test
    public void aRefusalNamesWhichSideIsMissing()
    {
        ExtensionFitness.Verdict verdict = ExtensionFitness.check("no such extension", "nor this");
        assertTrue(verdict.cannotTell, verdict.cannotTell.contains("no such extension"));
    }

    @Test
    public void aFindingCarriesWhatItIsAboutAndWhatHappened()
    {
        // Three fields and all three are load bearing: which coupling, what kind of coupling, and
        // what the release did to it. A finding missing any of them cannot be acted on.
        ExtensionFitness.Finding finding =
            new ExtensionFitness.Finding("Catalog.Products.Price", "attribute type",
                "the type changed: Number -> String");
        assertTrue(finding.object.contains("Price"));
        assertTrue(finding.kind.contains("type"));
        assertTrue(finding.what.contains("Number -> String"));
    }
}
