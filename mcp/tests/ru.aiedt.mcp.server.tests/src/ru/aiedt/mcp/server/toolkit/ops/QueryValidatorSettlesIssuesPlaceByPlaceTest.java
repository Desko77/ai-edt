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
        return new QueryValidator.QueryIssue(severity, message, line, column, offset);
    }

    private static QlValidator.QueryLayout layout(String alias, Boolean resolvesElsewhere,
        QlValidator.FieldSite... sites)
    {
        QlValidator.QueryLayout layout = new QlValidator.QueryLayout();
        layout.aliasResolves.put(alias.toLowerCase(java.util.Locale.ROOT), resolvesElsewhere);
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
        asked.add(issue("ERROR", "Поле 'Виды.НаборСвойств' не найдено", 1, 32, 20)); //$NON-NLS-1$
        asked.add(issue("ERROR", "Поле 'Сег.Ссылка' не найдено", 1, 8, 40)); //$NON-NLS-1$
        List<QueryValidator.QueryIssue> other = new ArrayList<>();
        other.add(issue("ERROR", "Таблица 'Справочник._Расшир' не найдена", 3, 5, 90)); //$NON-NLS-1$

        QlValidator.QueryLayout named = layout("сег", Boolean.TRUE, //$NON-NLS-1$
            new QlValidator.FieldSite(20, 18, 1, "Виды"), //$NON-NLS-1$
            new QlValidator.FieldSite(40, 16, 1, "Сег")); //$NON-NLS-1$
        QlValidator.QueryLayout configuration = layout("виды", Boolean.TRUE); //$NON-NLS-1$

        List<QueryValidator.QueryIssue> settled =
            QueryValidator.settledIssues(asked, other, named, configuration);

        assertEquals("the borrowed attribute is settled away, the own object's error stays", 1, //$NON-NLS-1$
            settled.size());
        assertEquals(40, settled.get(0).offset);
    }

    @Test
    public void aFieldBothModelsRejectStays()
    {
        List<QueryValidator.QueryIssue> asked = new ArrayList<>();
        asked.add(issue("ERROR", "Поле 'Виды.Выдумка' не найдено", 1, 32, 20)); //$NON-NLS-1$
        List<QueryValidator.QueryIssue> other = new ArrayList<>();
        other.add(issue("ERROR", "Поле 'Виды.Выдумка' не найдено", 1, 32, 20)); //$NON-NLS-1$

        List<QueryValidator.QueryIssue> settled = QueryValidator.settledIssues(asked, other,
            layout("виды", Boolean.TRUE, new QlValidator.FieldSite(20, 18, 1, "Виды")), //$NON-NLS-1$ //$NON-NLS-2$
            layout("виды", Boolean.TRUE)); //$NON-NLS-1$

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
