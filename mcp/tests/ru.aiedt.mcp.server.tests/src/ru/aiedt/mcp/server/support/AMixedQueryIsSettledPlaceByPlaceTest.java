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
 * that very place AND the source the issue's field names resolved in the other model's parse of
 * the query scope that declares the alias. Positions, scopes and alias resolution are data here;
 * reading them off a live parse is a live check.
 * </p>
 */
public class AMixedQueryIsSettledPlaceByPlaceTest
{
    private static QlValidator.QlIssue errorAt(int offset, int line, int column)
    {
        return new QlValidator.QlIssue("ERROR", "Поле не найдено", line, column, offset, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static QlValidator.FieldSite site(int offset, int length, int line, String alias,
        int scope)
    {
        return new QlValidator.FieldSite(offset, length, line, alias, scope);
    }

    private static QlValidator.FieldSite site(int offset, int length, int line, String alias,
        int scope, int column, int endColumn)
    {
        return new QlValidator.FieldSite(offset, length, line, alias, scope, column, endColumn);
    }

    private static QlValidator.QueryLayout elsewhere(int scope, String alias, Boolean value)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> ofScope = new LinkedHashMap<>();
        ofScope.put(alias.toLowerCase(java.util.Locale.ROOT), value);
        layout.aliasResolvesByScope.put(Integer.valueOf(scope), ofScope);
        return layout;
    }

    @Test
    public void aNonAdoptedAttributeOfABorrowedObjectIsNotAnError()
    {
        // The reproduced query: the extension's own table beside a borrowed one whose attribute
        // the extension did not adopt. The configuration resolves the borrowed table (the object
        // exists there) and says nothing at the attribute's place - its complaints are about the
        // extension's own table, which sits elsewhere in the text.
        boolean stands = QlValidator.standsAfterMerging(errorAt(20, 1, 32),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 1, "Виды", 0), site(40, 16, 1, "Сег", 0)), //$NON-NLS-1$ //$NON-NLS-2$
            elsewhere(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertFalse("a field the other model holds is not an error in a mixed query", stands); //$NON-NLS-1$
    }

    @Test
    public void anInventedFieldOfABorrowedObjectStays()
    {
        // A field neither model finds: the other model reports the very place, so the query is
        // wrong wherever it is read. Silence was the condition for settling, and there is none.
        boolean stands = QlValidator.standsAfterMerging(errorAt(20, 1, 32),
            List.of(QlValidator.At.of(20, 1, 32)),
            List.of(site(20, 18, 1, "Виды", 0)), //$NON-NLS-1$
            elsewhere(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue("an invented field is reported by both models and stays", stands); //$NON-NLS-1$
    }

    @Test
    public void anInventedFieldOfTheExtensionsOwnObjectStays()
    {
        // The other model is silent at this place - it never saw the table to look at its fields -
        // but silence alone is not agreement: the alias resolves nowhere in that model, so the
        // field is settled by the one model that holds the object.
        boolean stands = QlValidator.standsAfterMerging(errorAt(40, 1, 8),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(40, 16, 1, "Сег", 0)), //$NON-NLS-1$
            elsewhere(0, "сег", Boolean.FALSE)); //$NON-NLS-1$

        assertTrue("an error about the named project's own object stands", stands); //$NON-NLS-1$
    }

    @Test
    public void anAliasRedeclaredByAnotherQuerySettlesNothingOfItsOwn()
    {
        // Two queries of one package, the same alias in both: the extension's object in the first
        // query, a configuration object in the second. One map for the whole batch would hold the
        // second declaration alone, and the second query's table would settle an error of the
        // first - a table the other model was never asked about at that place.
        boolean stands = QlValidator.standsAfterMerging(errorAt(7, 1, 8),
            List.of(QlValidator.At.of(300, 9, 5)),
            List.of(site(7, 20, 1, "Расш", 0), site(118, 10, 1, "Расш", 1)), //$NON-NLS-1$ //$NON-NLS-2$
            layoutWithTwoScopes(0, "расш", Boolean.FALSE, 1, "расш", Boolean.TRUE)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an alias of another query does not settle this query's error", stands); //$NON-NLS-1$
    }

    @Test
    public void aFieldOfAnAliasItsOwnQueryResolvedIsSettledAway()
    {
        // The same shape the other way round: the FIRST query's table resolves in the other model,
        // the second query's does not. A field of the first query is then settled by it, and the
        // map of the second query must not be allowed to speak for the first.
        boolean stands = QlValidator.standsAfterMerging(errorAt(7, 1, 8),
            List.of(QlValidator.At.of(300, 9, 5)),
            List.of(site(7, 20, 1, "Расш", 0), site(118, 10, 1, "Расш", 1)), //$NON-NLS-1$ //$NON-NLS-2$
            layoutWithTwoScopes(0, "расш", Boolean.TRUE, 1, "расш", Boolean.FALSE)); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("the query that declared the alias settles its own fields", stands); //$NON-NLS-1$
    }

    @Test
    public void anAliasDeclaredTwiceInOneScopeWithDifferentResolvabilitySettlesNothing()
    {
        // Two sources of one query share an alias; one resolves in the other model, the other does
        // not. Which source a field of that name reads through is not something the layout can
        // say, so the error stays rather than being settled by whichever declaration won the map.
        boolean stands = QlValidator.standsAfterMerging(errorAt(7, 1, 8),
            List.of(QlValidator.At.of(300, 9, 5)),
            List.of(site(7, 20, 1, "Расш", 0)), //$NON-NLS-1$
            disputedElsewhere(0, "расш")); //$NON-NLS-1$

        assertTrue("a twice-declared alias does not settle anything", stands); //$NON-NLS-1$
    }

    @Test
    public void anIssueNoAliasUnderwritesStays()
    {
        // A table that is not there, a syntax complaint - a diagnostic at a place no field
        // reference covers. Nothing the other model could have said bears on it, and an alias
        // resolving in a scope of its own is the same situation read from the other end.
        boolean stands = QlValidator.standsAfterMerging(errorAt(300, 5, 6),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 1, "Виды", 0)), //$NON-NLS-1$
            elsewhere(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue("a diagnostic tied to no source alias is never dropped", stands); //$NON-NLS-1$
    }

    @Test
    public void twoReferencesOnOneLineWithoutOffsetSettleByTheirOwnColumns()
    {
        // One line, two fields: the borrowed object's reference first, the extension's own second.
        // An issue without an offset is tied by its COLUMN, not by which reference happens to
        // start first on the line - the first reference's alias resolving settles nothing about
        // the second.
        boolean stands = QlValidator.standsAfterMerging(errorAt(-1, 1, 44),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 1, "Виды", 0, 21, 38),
                site(58, 24, 1, "Расш", 0, 59, 81)), //$NON-NLS-1$ //$NON-NLS-2$
            resolution(0, "виды", Boolean.TRUE, "расш", Boolean.FALSE)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the first reference on a line does not settle the second's error", stands); //$NON-NLS-1$
    }

    @Test
    public void anIssueWithoutAColumnNamesNoReferenceOnItsLine()
    {
        // A diagnostic that carries a line and no column cannot be told from its neighbour on the
        // line, so it names no alias and stands - a line is a neighbourhood, not a place.
        boolean stands = QlValidator.standsAfterMerging(errorAt(-1, 1, -1),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 1, "Виды", 0, 21, 38)), //$NON-NLS-1$
            elsewhere(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue("a place without a column ties an issue to no alias", stands); //$NON-NLS-1$
    }

    @Test
    public void aReferenceWithoutColumnsDoesNotSettleAnIssueWithoutAnOffset()
    {
        // A layout whose columns could not be read settles nothing by line alone: the column is
        // what tells two references of one line apart, and without it the conservative answer is
        // the one the named model gave.
        boolean stands = QlValidator.standsAfterMerging(errorAt(-1, 1, 21),
            List.of(),
            List.of(site(20, 18, 1, "Виды", 0)), //$NON-NLS-1$
            elsewhere(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue("a site naming no columns settles no issue without an offset", stands); //$NON-NLS-1$
    }

    @Test
    public void anIssueInsideAReferencesColumnsIsSettledByThem()
    {
        // The positive shape: the issue carries no offset, its column lies inside one reference,
        // and that reference's alias resolves - the error is settled away exactly as with offsets.
        boolean stands = QlValidator.standsAfterMerging(errorAt(-1, 4, 10),
            List.of(QlValidator.At.of(90, 3, 5)),
            List.of(site(20, 18, 4, "Виды", 0, 9, 26)), //$NON-NLS-1$
            elsewhere(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertFalse("a column inside the reference settles the issue", stands); //$NON-NLS-1$
    }

    @Test
    public void aPlaceWithoutOffsetsComparesByLineAndColumn()
    {
        QlValidator.FieldSite onTheLine = site(20, 18, 4, "Виды", 0); //$NON-NLS-1$
        QlValidator.QueryLayout borrowed = elsewhere(0, "виды", Boolean.TRUE); //$NON-NLS-1$

        // Same line and column: the other model spoke at this place, so the issue stands even
        // though the alias resolves.
        assertTrue(QlValidator.standsAfterMerging(errorAt(-1, 4, 10),
            List.of(QlValidator.At.of(-1, 4, 10)), List.of(onTheLine), borrowed));

        // A different column is a different place: silence there, and the issue is settled away by
        // the reference that covers the column.
        assertFalse(QlValidator.standsAfterMerging(errorAt(-1, 4, 10),
            List.of(QlValidator.At.of(-1, 4, 11)),
            List.of(site(20, 18, 4, "Виды", 0, 9, 26)), borrowed)); //$NON-NLS-1$

        // A place that names neither an offset nor a line is not the same place as anything.
        assertTrue(QlValidator.standsAfterMerging(errorAt(-1, -1, -1),
            List.of(QlValidator.At.of(-1, -1, -1)), List.of(onTheLine), borrowed));
    }

    /**
     * A layout of two scopes, one alias each.
     *
     * @param firstScope the first scope's id
     * @param firstAlias the first scope's alias
     * @param firstValue whether it resolved in that model
     * @param secondScope the second scope's id
     * @param secondAlias the second scope's alias
     * @param secondValue whether it resolved in that model
     * @return the layout
     */
    private static QlValidator.QueryLayout layoutWithTwoScopes(int firstScope, String firstAlias,
        Boolean firstValue, int secondScope, String secondAlias, Boolean secondValue)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> first = new LinkedHashMap<>();
        first.put(firstAlias.toLowerCase(java.util.Locale.ROOT), firstValue);
        layout.aliasResolvesByScope.put(Integer.valueOf(firstScope), first);
        Map<String, Boolean> second = new LinkedHashMap<>();
        second.put(secondAlias.toLowerCase(java.util.Locale.ROOT), secondValue);
        layout.aliasResolvesByScope.put(Integer.valueOf(secondScope), second);
        return layout;
    }

    /**
     * A layout of one scope holding two alias resolutions.
     *
     * @param scope the scope's id
     * @param firstAlias the first alias, as written
     * @param firstValue whether it resolved in that model
     * @param secondAlias the second alias, as written
     * @param secondValue whether it resolved in that model
     * @return the layout
     */
    private static QlValidator.QueryLayout resolution(int scope, String firstAlias,
        Boolean firstValue, String secondAlias, Boolean secondValue)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> ofScope = new LinkedHashMap<>();
        ofScope.put(firstAlias.toLowerCase(java.util.Locale.ROOT), firstValue);
        ofScope.put(secondAlias.toLowerCase(java.util.Locale.ROOT), secondValue);
        layout.aliasResolvesByScope.put(Integer.valueOf(scope), ofScope);
        return layout;
    }

    /**
     * A layout whose scope declared one alias twice with different resolvability, which the
     * layout records by holding no entry for the alias at all.
     *
     * @param scope the scope's id
     * @param alias the lower-case alias
     * @return the layout
     */
    private static QlValidator.QueryLayout disputedElsewhere(int scope, String alias)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> ofScope = new LinkedHashMap<>();
        ofScope.put(alias.toLowerCase(java.util.Locale.ROOT) + ".other", Boolean.TRUE); //$NON-NLS-1$
        layout.aliasResolvesByScope.put(Integer.valueOf(scope), ofScope);
        return layout;
    }
}
