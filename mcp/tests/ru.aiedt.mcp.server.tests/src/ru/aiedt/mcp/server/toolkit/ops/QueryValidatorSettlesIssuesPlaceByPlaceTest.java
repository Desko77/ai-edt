/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.QlValidator;

/**
 * The two settle steps of {@code validate_query}, as data: one issue per place, and the named
 * project's issues kept or dropped against the other model place by place.
 * <p>
 * What the answer counts is settled before it is counted, so a caller reading {@code errorCount}
 * reads the number of places to fix. The copy that survives a duplicate place is the one carrying
 * an offset - the one a caller can turn back into a position in the text.
 * </p>
 * <p>
 * Routing a live call through two workspace projects is a live check: no unit test holds an open
 * extension beside its parent. These tests pin the settling itself, over the shapes both models
 * return.
 * </p>
 */
public class QueryValidatorSettlesIssuesPlaceByPlaceTest
{
    private static QueryValidator.QueryIssue issue(String severity, String message, int line,
        int column, int offset)
    {
        return new QueryValidator.QueryIssue(severity, message, line, column, offset, null);
    }

    /**
     * A missing-field error, the one finding the settle rule may drop. The code is the platform's
     * check code, the same constant {@code QlValidator} gates the rule by.
     *
     * @param line the line
     * @param column the column
     * @param offset the offset
     * @return the issue
     */
    private static QueryValidator.QueryIssue fieldNotFound(int line, int column, int offset)
    {
        return new QueryValidator.QueryIssue("ERROR", "Поле не найдено", line, column, offset, //$NON-NLS-1$ //$NON-NLS-2$
            "Field not found"); //$NON-NLS-1$
    }

    private static QlValidator.QueryLayout layout(int scope, String alias,
        Boolean resolvesElsewhere, QlValidator.FieldSite... sites)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> ofScope = new java.util.LinkedHashMap<>();
        ofScope.put(alias.toLowerCase(java.util.Locale.ROOT), resolvesElsewhere);
        layout.aliasResolvesByScope.put(Integer.valueOf(scope), ofScope);
        for (QlValidator.FieldSite site : sites)
        {
            layout.sites.add(site);
        }
        return layout;
    }

    /**
     * A layout of one scope holding several alias resolutions at once.
     *
     * @param scope the scope's id
     * @param resolutions lower-case alias to whether it resolved in that model
     * @param sites the scope's field references
     * @return the layout
     */
    private static QlValidator.QueryLayout layoutOfResolutions(int scope,
        Map<String, Boolean> resolutions, QlValidator.FieldSite... sites)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        Map<String, Boolean> ofScope = new java.util.LinkedHashMap<>();
        ofScope.putAll(resolutions);
        layout.aliasResolvesByScope.put(Integer.valueOf(scope), ofScope);
        for (QlValidator.FieldSite site : sites)
        {
            layout.sites.add(site);
        }
        return layout;
    }

    @Test
    public void theCopyCarryingAnOffsetWinsThePlace()
    {
        List<QueryValidator.QueryIssue> raw = new ArrayList<>();
        raw.add(issue("ERROR", "mismatched input 'В'", 2, 7, -1)); //$NON-NLS-1$ //$NON-NLS-2$
        raw.add(issue("ERROR", "mismatched input 'В'", 2, 7, 14)); //$NON-NLS-1$ //$NON-NLS-2$

        List<QueryValidator.QueryIssue> settled = QueryValidator.oneIssuePerPlace(raw);

        assertEquals("one place is one issue", 1, settled.size()); //$NON-NLS-1$
        assertEquals("the record with the offset is the one kept", 14, settled.get(0).offset); //$NON-NLS-1$
    }

    @Test
    public void errorCountAfterSettlingCountsEachPlaceOnce()
    {
        List<QueryValidator.QueryIssue> raw = new ArrayList<>();
        for (int i = 0; i < 4; i++)
        {
            int line = 1 + i;
            raw.add(issue("ERROR", "mismatched input 'В'", line, 7, -1)); //$NON-NLS-1$ //$NON-NLS-2$
            raw.add(issue("ERROR", "mismatched input 'В'", line, 7, 10 * i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (int i = 0; i < 3; i++)
        {
            raw.add(issue("ERROR", "Поле 'Виды.Поле" + i + "' не найдено", 9, 4 + i, 300 + i)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        long errors = QueryValidator.oneIssuePerPlace(raw).stream()
            .filter(i -> "ERROR".equals(i.severity)).count(); //$NON-NLS-1$

        assertEquals("four syntax places and three field places, not eleven", 7, errors); //$NON-NLS-1$
    }

    @Test
    public void aNonAdoptedAttributeLeavesTheSettledAnswer()
    {
        List<QueryValidator.QueryIssue> asked = new ArrayList<>();
        asked.add(fieldNotFound(1, 32, 20));
        asked.add(fieldNotFound(1, 8, 40));
        List<QueryValidator.QueryIssue> other = new ArrayList<>();
        other.add(issue("ERROR", "Таблица 'Справочник._Расшир' не найдена", 3, 5, 90)); //$NON-NLS-1$

        QlValidator.QueryLayout named = layout(0, "виды", Boolean.TRUE, //$NON-NLS-1$
            new QlValidator.FieldSite(20, 18, 1, "Виды", 0), //$NON-NLS-1$
            new QlValidator.FieldSite(40, 16, 1, "Сег", 0)); //$NON-NLS-1$
        // The configuration holds BOTH resolutions: the borrowed table resolves, the extension's
        // own does not. A layout that recorded only the resolving one would never meet a source
        // the other model cannot see, and that meeting is what these tests exist to pin.
        Map<String, Boolean> resolutions = new java.util.LinkedHashMap<>();
        resolutions.put("виды", Boolean.TRUE); //$NON-NLS-1$
        resolutions.put("сег", Boolean.FALSE); //$NON-NLS-1$
        QlValidator.QueryLayout configuration = layoutOfResolutions(0, resolutions);

        List<QueryValidator.QueryIssue> settled =
            QueryValidator.settledIssues(asked, other, named, configuration);

        assertEquals("the borrowed attribute is settled away, the own object's error stays", 1, //$NON-NLS-1$
            settled.size());
        assertEquals(40, settled.get(0).offset);
    }

    @Test
    public void aTypeMismatchOfABorrowedObjectStays()
    {
        // The extension compared a borrowed object's reference with a string and found them
        // incompatible. The configuration holds the table, which settles where a FIELD is and
        // says nothing about what may be compared with what.
        List<QueryValidator.QueryIssue> asked = new ArrayList<>();
        asked.add(new QueryValidator.QueryIssue("ERROR", "Несовместимые типы", 1, 32, 20, //$NON-NLS-1$ //$NON-NLS-2$
            "Type mismatch")); //$NON-NLS-1$
        List<QueryValidator.QueryIssue> other = new ArrayList<>();
        other.add(issue("ERROR", "Таблица 'Справочник._Расшир' не найдена", 3, 5, 90)); //$NON-NLS-1$

        List<QueryValidator.QueryIssue> settled = QueryValidator.settledIssues(asked, other,
            layout(0, "виды", Boolean.TRUE, new QlValidator.FieldSite(20, 18, 1, "Виды", 0)), //$NON-NLS-1$ //$NON-NLS-2$
            layout(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertEquals("a mismatch the other model never voiced stays", 1, settled.size()); //$NON-NLS-1$
    }

    @Test
    public void aFieldBothModelsRejectStays()
    {
        List<QueryValidator.QueryIssue> asked = new ArrayList<>();
        asked.add(fieldNotFound(1, 32, 20));
        List<QueryValidator.QueryIssue> other = new ArrayList<>();
        other.add(fieldNotFound(1, 32, 20));

        List<QueryValidator.QueryIssue> settled = QueryValidator.settledIssues(asked, other,
            layout(0, "виды", Boolean.TRUE, new QlValidator.FieldSite(20, 18, 1, "Виды", 0)), //$NON-NLS-1$ //$NON-NLS-2$
            layout(0, "виды", Boolean.TRUE)); //$NON-NLS-1$

        assertEquals("the other model spoke at this very place", 1, settled.size()); //$NON-NLS-1$
    }

    @Test
    public void missingLayoutsKeepEverything()
    {
        // A query whose layout could not be read settles nothing: layouts are absent exactly when
        // the walk over the model failed, and a dropped diagnostic cannot be brought back.
        List<QueryValidator.QueryIssue> asked = new ArrayList<>();
        asked.add(issue("ERROR", "Поле 'Виды.НаборСвойств' не найдено", 1, 32, 20)); //$NON-NLS-1$

        List<QueryValidator.QueryIssue> settled =
            QueryValidator.settledIssues(asked, new ArrayList<>(), null, null);

        assertEquals(1, settled.size());
        assertTrue(settled.get(0).severity.equals("ERROR")); //$NON-NLS-1$
    }
}
