/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A query that mixes a borrowed object's non-adopted attribute with an object the extension owns
 * is settled place by place, not by discarding one model's answer whole.
 * <p>
 * Measured on a stand: such a query upsets BOTH models - the extension's view lacks the attribute,
 * the configuration's view lacks the extension's own table - and while only a clean answer from
 * either side could settle the query, neither side produces one. Picking the named project's
 * answer reported the attribute as a missing field, which is false: the platform runs extension
 * queries against the extended configuration, where the field exists.
 * </p>
 * <p>
 * The rule these tests pin works on places and on the query's own model, never on the words of a
 * diagnostic: an issue of the named model is dropped only when the other model said nothing at
 * that very place AND the source the issue's field names resolved in the other model. Positions
 * and alias resolution are data here; reading them off a live parse is a live check.
 * </p>
 */
public class AMixedQueryIsSettledPlaceByPlaceTest
{
    private static QlValidator.FieldSite site(int offset, int length, int line, String alias)
    {
        return new QlValidator.FieldSite(offset, length, line, alias);
    }

    private static Map<String, Boolean> resolves(String alias, Boolean value)
    {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put(alias, value);
        return map;
    }

    @Test
    public void aNonAdoptedAttributeOfABorrowedObjectIsNotAnError()
    {
        // The reproduced query: the extension's own table beside a borrowed one whose attribute
        // the extension did not adopt. The configuration resolves the borrowed table (the object
        // exists there) and says nothing at the attribute's place - its complaints are about the
        // extension's own table, which sits elsewhere in the text.
        boolean stands = QlValidator.standsAfterMerging(QlValidator.At.of(20, 1, 32),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 1, "Виды"), site(40, 16, 1, "Сег")), //$NON-NLS-1$ //$NON-NLS-2$
            resolves("виды", Boolean.TRUE)); //$NON-NLS-1$

        assertFalse("a field the other model holds is not an error in a mixed query", stands); //$NON-NLS-1$
    }

    @Test
    public void anInventedFieldOfABorrowedObjectStays()
    {
        // A field neither model finds: the other model reports the very place, so the query is
        // wrong wherever it is read. Silence was the condition for settling, and there is none.
        boolean stands = QlValidator.standsAfterMerging(QlValidator.At.of(20, 1, 32),
            List.of(QlValidator.At.of(20, 1, 32)),
            List.of(site(20, 18, 1, "Виды")), //$NON-NLS-1$
            resolves("виды", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue("an invented field is reported by both models and stays", stands); //$NON-NLS-1$
    }

    @Test
    public void anInventedFieldOfTheExtensionsOwnObjectStays()
    {
        // The other model is silent at this place - it never saw the table to look at its fields -
        // but silence alone is not agreement: the alias resolves nowhere in that model, so the
        // field is settled by the one model that holds the object.
        boolean stands = QlValidator.standsAfterMerging(QlValidator.At.of(40, 1, 8),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(40, 16, 1, "Сег")), //$NON-NLS-1$
            resolves("сег", Boolean.FALSE)); //$NON-NLS-1$

        assertTrue("an error about the named project's own object stands", stands); //$NON-NLS-1$
    }

    @Test
    public void anIssueNoAliasUnderwritesStays()
    {
        // A table that is not there, a syntax complaint - a diagnostic at a place no field
        // reference covers. Nothing the other model could have said bears on it, and an unknown
        // alias in the resolution map is the same situation read from the other end.
        boolean stands = QlValidator.standsAfterMerging(QlValidator.At.of(300, 5, 6),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 1, "Виды")), //$NON-NLS-1$
            resolves("виды", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue("a diagnostic tied to no source alias is never dropped", stands); //$NON-NLS-1$
    }

    @Test
    public void aPlaceWithoutOffsetsComparesByLineAndColumn()
    {
        QlValidator.FieldSite onTheLine = site(20, 18, 4, "Виды"); //$NON-NLS-1$
        Map<String, Boolean> borrowed = resolves("виды", Boolean.TRUE); //$NON-NLS-1$

        // Same line and column: the other model spoke at this place, so the issue stands even
        // though the alias resolves.
        assertTrue(QlValidator.standsAfterMerging(QlValidator.At.of(-1, 4, 10),
            List.of(QlValidator.At.of(-1, 4, 10)), List.of(onTheLine), borrowed));

        // A different column is a different place: silence there, the alias resolves, and the
        // issue is settled away.
        assertFalse(QlValidator.standsAfterMerging(QlValidator.At.of(-1, 4, 10),
            List.of(QlValidator.At.of(-1, 4, 11)), List.of(onTheLine), borrowed));

        // A place that names neither an offset nor a line is not the same place as anything.
        assertTrue(QlValidator.standsAfterMerging(QlValidator.At.of(-1, -1, -1),
            List.of(QlValidator.At.of(-1, -1, -1)), List.of(onTheLine), borrowed));
    }

    @Test
    public void aSiteLineBacksAnIssueThatCarriesNoOffset()
    {
        // The resource's copy of a finding carries no offset; its reference is found by the line
        // the site starts on.
        boolean stands = QlValidator.standsAfterMerging(QlValidator.At.of(-1, 4, 10),
            List.of(), List.of(site(20, 18, 4, "Виды")), resolves("виды", Boolean.TRUE)); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a line is enough to tie an issue to its alias", stands); //$NON-NLS-1$
    }
}
