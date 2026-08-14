/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.TreeSet;

import org.junit.Test;

/**
 * Covers the survey's contract to a caller, in the parts that hold without a configuration behind
 * them.
 * <p>
 * Walking real tables needs the environment's derived model over a loaded configuration, so the
 * shape of an actual answer is verified against a live workspace and not here. What is pinned here
 * is what a caller acts on: that an unknown type is absent rather than invented, that a table which
 * is hidden says so, and that asking about nothing is answered rather than thrown.
 * </p>
 */
public class DbViewSurveyTest
{
    @Test
    public void surveyingNothingIsAnAnswerNotAFailure()
    {
        DbViewSurvey.Survey survey = DbViewSurvey.of(null);
        assertNotNull("a missing object must be reported, not thrown", survey); //$NON-NLS-1$
        assertNotNull("the reason has to be sayable to the caller", survey.error); //$NON-NLS-1$
        assertTrue("nothing can be surveyed without an object", survey.tables.isEmpty()); //$NON-NLS-1$
        assertEquals(0, survey.fieldCount());
    }

    @Test
    public void aFieldWithoutATypeCarriesNoTypeKeyAtAll()
    {
        // The same confidence rule the query schema keeps: a caller acts on what it is told, so an
        // unknown type is omitted rather than filled with a plausible-looking default.
        Map<String, Object> asMap =
            new DbViewSurvey.Field("Ref", "Ссылка", new TreeSet<>(), null).toMap(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Ref", asMap.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Ссылка", asMap.get("nameRu")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unknown type must be omitted, never guessed", //$NON-NLS-1$
            asMap.containsKey("types")); //$NON-NLS-1$
        assertFalse("a field that is not a table must not claim to be one", //$NON-NLS-1$
            asMap.containsKey("nestedTable")); //$NON-NLS-1$
    }

    @Test
    public void aFieldThatIsATableSaysWhichOne()
    {
        // A tabular section is reported twice - as a field of its owner and as a table of its own -
        // and this key is what joins the two halves for the caller.
        Map<String, Object> asMap = new DbViewSurvey.Field("Contacts", "Контакты", //$NON-NLS-1$ //$NON-NLS-2$
            new TreeSet<>(), "Catalog.Partners.Contacts").toMap(); //$NON-NLS-1$
        assertEquals("Catalog.Partners.Contacts", asMap.get("nestedTable")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void bothNamesAreAlwaysReported()
    {
        // A query is written in one language or the other. Giving only the English name leaves a
        // caller translating, which is the guesswork this whole thing exists to remove.
        DbViewSurvey.DbTable table = new DbViewSurvey.DbTable("AccumulationRegister.Sales.Turnovers", //$NON-NLS-1$
            "РегистрНакопления.Продажи.Обороты", "virtual", false); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> asMap = table.toMap(true);
        assertEquals("AccumulationRegister.Sales.Turnovers", asMap.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("РегистрНакопления.Продажи.Обороты", asMap.get("nameRu")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("virtual", asMap.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void hiddenIsSaidOnlyWhenItIsTrue()
    {
        // Hidden means the query builder does not offer it, not that it cannot be selected from -
        // so it is reported, and its absence must not read as "hidden: false was forgotten".
        assertFalse(new DbViewSurvey.DbTable("Catalog.Currencies", "Справочник.Валюты", //$NON-NLS-1$ //$NON-NLS-2$
            "main", false).toMap(false).containsKey("hidden")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, new DbViewSurvey.DbTable("Catalog.Currencies.Changes", //$NON-NLS-1$
            "Справочник.Валюты.Изменения", "change", true).toMap(false).get("hidden")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void countingIsPossibleWithoutAskingForTheFields()
    {
        // The cheap answer has to stay useful: a caller reconciling its own table list wants the
        // count even when it does not want a megabyte of fields.
        DbViewSurvey.DbTable table =
            new DbViewSurvey.DbTable("Catalog.Currencies", "Справочник.Валюты", "main", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        table.fields.add(new DbViewSurvey.Field("Ref", "Ссылка", new TreeSet<>(), null)); //$NON-NLS-1$ //$NON-NLS-2$
        table.fields.add(new DbViewSurvey.Field("Code", "Код", new TreeSet<>(), null)); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> lean = table.toMap(false);
        assertEquals(2, lean.get("fieldCount")); //$NON-NLS-1$
        assertFalse("includeFields=false must actually drop the fields", //$NON-NLS-1$
            lean.containsKey("fields")); //$NON-NLS-1$
        assertTrue(table.toMap(true).containsKey("fields")); //$NON-NLS-1$
    }

    @Test
    public void aVirtualTablesParametersTravelWithIt()
    {
        // Turnovers without its period and condition parameters is half an answer: the caller can
        // name the table and still not know what it may be called with.
        DbViewSurvey.DbTable table = new DbViewSurvey.DbTable("AccumulationRegister.Sales.Turnovers", //$NON-NLS-1$
            "РегистрНакопления.Продажи.Обороты", "virtual", false); //$NON-NLS-1$ //$NON-NLS-2$
        TreeSet<String> dateTypes = new TreeSet<>();
        dateTypes.add("Дата"); //$NON-NLS-1$
        table.parameters.add(new DbViewSurvey.Parameter("BeginOfPeriod", "НачалоПериода", dateTypes)); //$NON-NLS-1$ //$NON-NLS-2$
        Object parameters = table.toMap(true).get("parameters"); //$NON-NLS-1$
        assertNotNull("a virtual table must carry its parameters", parameters); //$NON-NLS-1$
        assertTrue(parameters.toString().contains("НачалоПериода")); //$NON-NLS-1$
        assertFalse("a table with no parameters must not carry an empty list", //$NON-NLS-1$
            new DbViewSurvey.DbTable("Catalog.Currencies", "Справочник.Валюты", "main", false) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toMap(true).containsKey("parameters")); //$NON-NLS-1$
    }

    @Test
    public void fieldsAreCountedAcrossEveryTable()
    {
        DbViewSurvey.Survey survey = new DbViewSurvey.Survey();
        DbViewSurvey.DbTable main =
            new DbViewSurvey.DbTable("Catalog.Partners", "Справочник.Партнеры", "main", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        main.fields.add(new DbViewSurvey.Field("Ref", "Ссылка", new TreeSet<>(), null)); //$NON-NLS-1$ //$NON-NLS-2$
        DbViewSurvey.DbTable nested = new DbViewSurvey.DbTable("Catalog.Partners.Contacts", //$NON-NLS-1$
            "Справочник.Партнеры.Контакты", "nested", false); //$NON-NLS-1$ //$NON-NLS-2$
        nested.fields.add(new DbViewSurvey.Field("LineNumber", "НомерСтроки", new TreeSet<>(), null)); //$NON-NLS-1$ //$NON-NLS-2$
        nested.fields.add(new DbViewSurvey.Field("Phone", "Телефон", new TreeSet<>(), null)); //$NON-NLS-1$ //$NON-NLS-2$
        survey.tables.add(main);
        survey.tables.add(nested);
        assertEquals(3, survey.fieldCount());
    }
}
