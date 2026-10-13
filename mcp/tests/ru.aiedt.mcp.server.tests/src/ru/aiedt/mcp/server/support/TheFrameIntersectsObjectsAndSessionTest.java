/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * A session-scoped query frame intersects its object set with its session set, the way the
 * marker filter intersects them.
 * <p>
 * A marker the reader collects has to match both sets at once, so a baseline record that fits
 * only one of the two was never in the question. Framing the baseline with the union instead
 * counted such records resolved - a narrowing read as a cure.
 * </p>
 */
public class TheFrameIntersectsObjectsAndSessionTest
{
    private static Diagnostic finding(String objectRef)
    {
        return new Diagnostic(objectRef, "bsl-comment-length", "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Only a record under both sets stays: the record under the object set alone and the record
     * under the session set alone leave, even though each fits one of the two.
     */
    @Test
    public void onlyARecordUnderBothSetsStays()
    {
        Diagnostic objectOnly = finding("Catalog.Products"); //$NON-NLS-1$
        Diagnostic sessionOnly = finding("Document.Sales"); //$NON-NLS-1$
        Diagnostic both = finding("Catalog.Prices"); //$NON-NLS-1$
        Set<String> objects = new HashSet<>();
        objects.add("catalog.products"); //$NON-NLS-1$
        objects.add("catalog.prices"); //$NON-NLS-1$
        Set<String> session = new HashSet<>();
        session.add("document.sales"); //$NON-NLS-1$
        session.add("catalog.prices"); //$NON-NLS-1$

        List<Diagnostic> framed = DiagnosticsBaselineStore.frame(
            Arrays.asList(objectOnly, sessionOnly, both), null, null, null, objects, true, session);

        assertEquals(1, framed.size());
        assertEquals(both.identity(), framed.get(0).identity());
    }

    /**
     * Under session scope an empty session set keeps nothing - the reader's own session filter
     * keeps no marker then either, so the two sides stay the same question.
     */
    @Test
    public void anEmptySessionSetUnderSessionScopeKeepsNothing()
    {
        Diagnostic standing = finding("Catalog.Products"); //$NON-NLS-1$
        Set<String> objects = new HashSet<>();
        objects.add("catalog.products"); //$NON-NLS-1$

        List<Diagnostic> framed = DiagnosticsBaselineStore.frame(
            Arrays.asList(standing), null, null, null, objects, true, new HashSet<>());

        assertEquals(0, framed.size());
    }

    /**
     * Without session scope the session set takes no part and the object set narrows alone.
     */
    @Test
    public void withoutSessionScopeTheObjectSetNarrowsAlone()
    {
        Diagnostic watched = finding("Catalog.Products"); //$NON-NLS-1$
        Diagnostic elsewhere = finding("Document.Sales"); //$NON-NLS-1$
        Set<String> objects = new HashSet<>();
        objects.add("catalog.products"); //$NON-NLS-1$
        Set<String> session = new HashSet<>();
        session.add("document.sales"); //$NON-NLS-1$

        List<Diagnostic> framed = DiagnosticsBaselineStore.frame(
            Arrays.asList(watched, elsewhere), null, null, null, objects, false, session);

        assertEquals(1, framed.size());
        assertEquals(watched.identity(), framed.get(0).identity());
    }
}
