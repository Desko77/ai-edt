/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

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

    /**
     * What the extension keeps is a list of entries, and an entry is not a type.
     * <p>
     * An ordinary composition holds the types themselves, so naming each element names the type.
     * The extension holds one entry per type - a state beside a type - and an entry has no name of
     * its own. Read as an ordinary composition it yields nothing, the two sides never get compared,
     * and a delivery that retyped a borrowed field passes as though it had left it alone. That is
     * what a stand showed: the extension had Number against a delivery holding String, and the
     * check reported no finding at all.
     * </p>
     */
    @Test
    public void aBorrowedTypeIsNamedThroughItsEntry()
    {
        assertEquals("Number",
            ExtensionFitness.MdChildren.renderBorrowed(new Composition(new Entry(new Named("Number")))));
    }

    @Test
    public void severalBorrowedTypesRenderInAStableOrder()
    {
        // Sorted, so the same composition renders the same way whichever order it is held in -
        // otherwise two identical types compare unequal and every borrowed field cries wolf.
        String forwards = ExtensionFitness.MdChildren.renderBorrowed(
            new Composition(new Entry(new Named("String")), new Entry(new Named("Number"))));
        String backwards = ExtensionFitness.MdChildren.renderBorrowed(
            new Composition(new Entry(new Named("Number")), new Entry(new Named("String"))));
        assertEquals(forwards, backwards);
        assertEquals("Number, String", forwards);
    }

    @Test
    public void anEmptyCompositionIsNothingRatherThanAnEmptyName()
    {
        // An empty name would compare equal to another empty name, which is how two sides that
        // were never read come to look like two sides that agree.
        assertNull(ExtensionFitness.MdChildren.renderBorrowed(new Composition()));
        assertNull(ExtensionFitness.MdChildren.renderBorrowed(null));
        assertNull(ExtensionFitness.MdChildren.renderBorrowed("not a composition at all"));
    }

    /** Stands in for the extension composition block: it answers getTypes with its entries. */
    public static final class Composition
    {
        private final List<Entry> entries;

        Composition(Entry... entries)
        {
            this.entries = Arrays.asList(entries);
        }

        public List<Entry> getTypes()
        {
            return entries;
        }
    }

    /** Stands in for one entry: a state beside a type, and no name of its own. */
    public static final class Entry
    {
        private final Named type;

        Entry(Named type)
        {
            this.type = type;
        }

        public String getState()
        {
            return "Checked";
        }

        public Named getType()
        {
            return type;
        }
    }

    /** Stands in for the type an entry points at. */
    public static final class Named
    {
        private final String name;

        Named(String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }
    }
}
