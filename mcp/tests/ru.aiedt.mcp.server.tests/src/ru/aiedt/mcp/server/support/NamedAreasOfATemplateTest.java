/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * Naming areas of a spreadsheet template.
 * <p>
 * The mechanism that loads data from a file reads a template by its named areas: an area's name
 * becomes the name of the loaded column. mxl_workshop had no operation for them - not to create,
 * not to read, not to remove - so a load template built through the tool had to be finished by
 * editing the .mxlx by hand, which the tool exists to avoid.
 * </p>
 * <p>
 * Coordinates are 1-based on this class and 0-based in the moxel model, the same split every other
 * coordinate here lives with, so the conversion is what these mostly check.
 * </p>
 */
public class NamedAreasOfATemplateTest
{
    private static SpreadsheetDocument emptyDocument()
    {
        return MoxelFactory.eINSTANCE.createSpreadsheetDocument();
    }

    private static Map<String, Object> only(List<Map<String, Object>> areas)
    {
        assertEquals("exactly one area was named", 1, areas.size()); //$NON-NLS-1$
        return areas.get(0);
    }

    @Test
    public void aFreshTemplateHasNoNamedAreas()
    {
        assertTrue(BmTemplateHelper.listNamedAreas(emptyDocument()).isEmpty());
        assertTrue("a null document is not a crash", //$NON-NLS-1$
            BmTemplateHelper.listNamedAreas(null).isEmpty());
    }

    @Test
    public void aColumnAreaComesBackWithTheColumnsItWasGiven()
    {
        SpreadsheetDocument doc = emptyDocument();

        BmTemplateHelper.addNamedArea(doc, "Номенклатура", "columns", 0, 3, 0, 5); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> area = only(BmTemplateHelper.listNamedAreas(doc));
        assertEquals("Номенклатура", area.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("columns", area.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the column it was given, not the model's 0-based one", //$NON-NLS-1$
            Integer.valueOf(3), area.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), area.get("toCol")); //$NON-NLS-1$
    }

    @Test
    public void aRowAreaComesBackWithTheRowsItWasGiven()
    {
        SpreadsheetDocument doc = emptyDocument();

        BmTemplateHelper.addNamedArea(doc, "Шапка", "rows", 1, 0, 2, 0); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> area = only(BmTemplateHelper.listNamedAreas(doc));
        assertEquals("rows", area.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(1), area.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), area.get("toRow")); //$NON-NLS-1$
    }

    @Test
    public void aRectangleComesBackAsTheCornersItWasGiven()
    {
        SpreadsheetDocument doc = emptyDocument();

        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 2, 3, 4, 6); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> area = only(BmTemplateHelper.listNamedAreas(doc));
        assertEquals("rect", area.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        // The model holds a position and a span; the corners have to survive the round trip.
        assertEquals(Integer.valueOf(2), area.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), area.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), area.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), area.get("toCol")); //$NON-NLS-1$
    }

    @Test
    public void namingAnAreaTwiceMovesItRatherThanDoublingIt()
    {
        SpreadsheetDocument doc = emptyDocument();

        BmTemplateHelper.addNamedArea(doc, "Цена", "columns", 0, 1, 0, 1); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Цена", "columns", 0, 7, 0, 7); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> area = only(BmTemplateHelper.listNamedAreas(doc));
        assertEquals("two areas under one name would leave the loader a coin toss", //$NON-NLS-1$
            Integer.valueOf(7), area.get("fromCol")); //$NON-NLS-1$
    }

    @Test
    public void severalAreasAreAllKept()
    {
        SpreadsheetDocument doc = emptyDocument();
        for (int column = 1; column <= 7; column++)
        {
            BmTemplateHelper.addNamedArea(doc, "Колонка" + column, "columns", 0, column, 0, column); //$NON-NLS-1$ //$NON-NLS-2$
        }

        assertEquals("a load template names one area per column", //$NON-NLS-1$
            7, BmTemplateHelper.listNamedAreas(doc).size());
    }

    @Test
    public void removingAnAreaTakesItAndOnlyIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Первая", "columns", 0, 1, 0, 1); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Вторая", "columns", 0, 2, 0, 2); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(BmTemplateHelper.removeNamedArea(doc, "Первая")); //$NON-NLS-1$

        Map<String, Object> left = only(BmTemplateHelper.listNamedAreas(doc));
        assertEquals("Вторая", left.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void removingWhatIsNotThereSaysSo()
    {
        assertFalse("reporting a removal that did not happen is the silent success again", //$NON-NLS-1$
            BmTemplateHelper.removeNamedArea(emptyDocument(), "Нет такой")); //$NON-NLS-1$
        assertFalse(BmTemplateHelper.removeNamedArea(null, "Любая")); //$NON-NLS-1$
    }

    @Test
    public void anAreaWithoutANameIsRefused()
    {
        try
        {
            BmTemplateHelper.addNamedArea(emptyDocument(), "  ", "columns", 0, 1, 0, 1); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an area nobody can look up by name is of no use to the loader"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("name")); //$NON-NLS-1$
        }
    }

    @Test
    public void aRangeThatRunsBackwardsIsRefused()
    {
        try
        {
            BmTemplateHelper.addNamedArea(emptyDocument(), "Задом", "columns", 0, 5, 0, 2); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an end before the start is not a range"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("column")); //$NON-NLS-1$
        }
    }

    @Test
    public void anUnknownKindIsRefusedWithTheKindsThatWork()
    {
        try
        {
            BmTemplateHelper.addNamedArea(emptyDocument(), "Что-то", "диагональ", 1, 1, 1, 1); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a kind the model has no area for cannot be written"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("columns")); //$NON-NLS-1$
        }
    }

    @Test
    public void readingTheTemplateReportsTheAreasToo()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Товар", "columns", 0, 1, 0, 1); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> read = BmTemplateHelper.readSpreadsheet(doc, "ru"); //$NON-NLS-1$

        Object areas = read.get("namedAreas"); //$NON-NLS-1$
        assertTrue("a template read back without its areas cannot be repeated", //$NON-NLS-1$
            areas instanceof List && ((List<?>)areas).size() == 1);
    }
}
