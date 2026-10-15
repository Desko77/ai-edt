/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * An alias whose source the other model reported a diagnostic in settles nothing, even when that
 * model's table object makes the alias read as resolved: the extension's own table is "not found"
 * in the configuration, and a missing field of it stays an error.
 */
public class ASourceTheOtherModelRejectedSettlesNothingTest
{
    /**
     * The other model's layout: alias {@code сег} read as resolved, its source at 200-240.
     *
     * @return the layout
     */
    private static QlValidator.QueryLayout elsewhere()
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> resolves = new LinkedHashMap<>();
        resolves.put("сег", Boolean.TRUE); //$NON-NLS-1$
        layout.aliasResolvesByScope.put(Integer.valueOf(0), resolves);
        Map<String, List<int[]>> spans = new LinkedHashMap<>();
        List<int[]> ofSeg = new ArrayList<>();
        ofSeg.add(new int[] {200, 240});
        spans.put("сег", ofSeg); //$NON-NLS-1$
        layout.sourceSpansByScope.put(Integer.valueOf(0), spans);
        return layout;
    }

    /** A missing field of an alias whose source the other model rejected stays. */
    @Test
    public void aFieldOfARejectedSourceStays()
    {
        QlValidator.QlIssue issue = new QlValidator.QlIssue("ERROR", "Поле не найдено", 3, 6, 41, //$NON-NLS-1$ //$NON-NLS-2$
            QlValidator.FIELD_NOT_FOUND);
        List<QlValidator.At> other = List.of(QlValidator.At.of(223, 8, 13));
        List<QlValidator.FieldSite> sites = List.of(new QlValidator.FieldSite(39, 24, 3, "Сег", 0)); //$NON-NLS-1$

        assertTrue("the other model reported inside the source, so the alias settles nothing", //$NON-NLS-1$
            QlValidator.standsAfterMerging(issue, other, sites, elsewhere()));
    }

    /** With nothing reported inside the source, the resolved alias settles the missing field. */
    @Test
    public void aFieldOfAnAcceptedSourceIsSettled()
    {
        QlValidator.QlIssue issue = new QlValidator.QlIssue("ERROR", "Поле не найдено", 3, 6, 41, //$NON-NLS-1$ //$NON-NLS-2$
            QlValidator.FIELD_NOT_FOUND);
        List<QlValidator.At> other = List.of(QlValidator.At.of(300, 10, 2));
        List<QlValidator.FieldSite> sites = List.of(new QlValidator.FieldSite(39, 24, 3, "Сег", 0)); //$NON-NLS-1$

        assertFalse("nothing inside the source, the alias resolved, the field is settled", //$NON-NLS-1$
            QlValidator.standsAfterMerging(issue, other, sites, elsewhere()));
    }
}
