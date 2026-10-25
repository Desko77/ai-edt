/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diff;

/**
 * What the baseline subtraction counts as new and as resolved.
 * <p>
 * The identity is the triple of object, check and message. Nothing positional takes part, so a
 * finding that moved is the same finding, and a finding that changed severity is the same finding
 * too - the severity feeds the saved answer's counts and nothing else. The counts are
 * multiplicities: two identical findings against one recorded is one new finding, not two, and one
 * recorded finding that stands no more is one resolved.
 * </p>
 */
public class TheBaselineComparesByTripleTest
{
    private static Diagnostic finding(String objectRef, String checkId, String message)
    {
        return new Diagnostic(objectRef, checkId, message, "ERRORS"); //$NON-NLS-1$
    }

    /**
     * A finding that was not in the baseline is named once.
     */
    @Test
    public void aNewFindingAfterTheBaselineIsNamedOnce()
    {
        Diagnostic kept = finding("CommonModule.Common", "bsl-unused-variable", "Variable x is never used"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic added = finding("CommonModule.Common", "bsl-empty-block", "Empty block"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Diff diff = DiagnosticsBaselineStore.compare(Collections.singletonList(kept),
            Arrays.asList(kept, added));

        assertEquals(1, diff.appeared.size());
        assertEquals(added.identity(), diff.appeared.get(0).identity());
        assertEquals(1, diff.appeared.get(0).count);
        assertEquals(0, diff.resolvedCount);
    }

    /**
     * The same finding on another line is not new: the identity carries no position, so the two
     * records a reader maps from markers on different lines are one record here.
     */
    @Test
    public void theSameFindingOnAnotherLineIsNotNew()
    {
        Diagnostic recorded = finding("Document.Sales", "module-structure-top-region", "Region is missing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The reader builds a record from the fields its rows carry; the line is not among them,
        // so the record of the finding that moved is indistinguishable from the recorded one.
        Diagnostic moved = finding("Document.Sales", "module-structure-top-region", "Region is missing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Diff diff = DiagnosticsBaselineStore.compare(Collections.singletonList(recorded),
            Collections.singletonList(moved));

        assertTrue(diff.appeared.isEmpty());
        assertEquals(0, diff.resolvedCount);
    }

    /**
     * A severity change is not a new finding either - severity is not part of the triple.
     */
    @Test
    public void theSameFindingUnderAnotherSeverityIsNotNew()
    {
        Diagnostic recorded = new Diagnostic("Catalog.Products", "bsl-string-length", "String is too long", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "MINOR"); //$NON-NLS-1$
        Diagnostic standing = new Diagnostic("Catalog.Products", "bsl-string-length", "String is too long", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "MAJOR"); //$NON-NLS-1$

        Diff diff = DiagnosticsBaselineStore.compare(Collections.singletonList(recorded),
            Collections.singletonList(standing));

        assertTrue(diff.appeared.isEmpty());
        assertEquals(0, diff.resolvedCount);
    }

    /**
     * Two identical findings in one object against one recorded: one new, not two.
     */
    @Test
    public void aSecondIdenticalFindingCountsAsOneNew()
    {
        Diagnostic one = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic two = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Diff diff = DiagnosticsBaselineStore.compare(Collections.singletonList(one),
            Arrays.asList(one, two));

        assertEquals(1, diff.appeared.size());
        assertEquals(1, diff.appeared.get(0).count);
        assertEquals(1, diff.appearedCount());
        assertEquals(0, diff.resolvedCount);
    }

    /**
     * A recorded finding that stands no more is counted as resolved.
     */
    @Test
    public void aFixedFindingCountsAsResolved()
    {
        Diagnostic fixed = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Diff diff = DiagnosticsBaselineStore.compare(Collections.singletonList(fixed),
            Collections.emptyList());

        assertTrue(diff.appeared.isEmpty());
        assertEquals(1, diff.resolvedCount);
    }

    /**
     * Two recorded findings of one triple with one standing: one resolved.
     */
    @Test
    public void oneOfTwoIdenticalRecordedCountsAsOneResolved()
    {
        Diagnostic one = new Diagnostic("Catalog.Products", "bsl-comment-length", "Comment is too long", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "MINOR", 2);
        Diagnostic standing = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Diff diff = DiagnosticsBaselineStore.compare(Collections.singletonList(one),
            Collections.singletonList(standing));

        assertTrue(diff.appeared.isEmpty());
        assertEquals(1, diff.resolvedCount);
    }

    /**
     * Collapse groups identical triples into one counted record.
     */
    @Test
    public void identicalTriplesCollapseIntoOneCountedRecord()
    {
        Diagnostic one = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic two = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic other = finding("Catalog.Products", "bsl-comment-length", "Another comment"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Diagnostic> collapsed = DiagnosticsBaselineStore.collapse(Arrays.asList(one, two, other));

        assertEquals(2, collapsed.size());
        assertEquals(2, collapsed.get(0).count);
        assertEquals(1, collapsed.get(1).count);
    }

    /**
     * A baseline scoped to named objects must not name the findings of other objects new: the
     * others were never compared against it.
     */
    @Test
    public void aScopedBaselineDoesNotNameOtherObjectsNew()
    {
        Diagnostic onWatched = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic elsewhere = finding("Catalog.ProductsExtra", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Set<String> watched = new HashSet<>();
        watched.add("catalog.products"); //$NON-NLS-1$

        List<Diagnostic> compared = DiagnosticsBaselineStore
            .withinObjects(Arrays.asList(onWatched, elsewhere), watched);
        Diff diff = DiagnosticsBaselineStore.compare(Collections.emptyList(), compared);

        assertEquals(1, compared.size());
        assertEquals(onWatched.identity(), compared.get(0).identity());
        assertEquals(1, diff.appeared.size());
        assertEquals(onWatched.identity(), diff.appeared.get(0).identity());
    }

    /**
     * The query frame keeps a baseline's records to the question the current findings answer: the
     * severity set, the check needle and the object set each drop what the query never asked
     * about.
     */
    @Test
    public void theQueryFrameNarrowsTheBaselineToTheQuestion()
    {
        Diagnostic error = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic minor = new Diagnostic("Catalog.Products", "bsl-comment-length", "Comment is too long", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "MINOR"); //$NON-NLS-1$
        Diagnostic otherCheck = finding("Catalog.Products", "bsl-string-length", "String is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic otherObject = finding("Document.Sales", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Set<String> errors = new HashSet<>();
        errors.add("ERRORS"); //$NON-NLS-1$
        Set<String> fqns = new HashSet<>();
        fqns.add("catalog.products"); //$NON-NLS-1$
        List<Diagnostic> framed = DiagnosticsBaselineStore.frame(
            Arrays.asList(error, minor, otherCheck, otherObject), errors, "bsl-comment", null, fqns); //$NON-NLS-1$

        // The MINOR record left with the severity filter, the other check with the needle, the
        // other object with the FQN set - each for the filter that drops it.
        assertEquals(1, framed.size());
        assertEquals(error.identity(), framed.get(0).identity());
    }

    /**
     * Every filter of the frame narrows on its own.
     */
    @Test
    public void eachFrameFilterNarrowsOnItsOwn()
    {
        Diagnostic a = finding("Catalog.Products", "bsl-comment-length", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Diagnostic b = finding("Document.Sales", "bsl-string-length", "String is too long"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Diagnostic> both = Arrays.asList(a, b);

        Set<String> empty = null;
        Set<String> fqns = new HashSet<>();
        fqns.add("catalog.products"); //$NON-NLS-1$

        assertEquals(2, DiagnosticsBaselineStore.frame(both, empty, null, null, null).size());
        assertEquals(1, DiagnosticsBaselineStore.frame(both, empty, "bsl-comment", null, null).size()); //$NON-NLS-1$
        assertEquals(1, DiagnosticsBaselineStore.frame(both, empty, null, "Document", null).size()); //$NON-NLS-1$
        assertEquals(1, DiagnosticsBaselineStore.frame(both, empty, null, null, fqns).size());
    }
}
