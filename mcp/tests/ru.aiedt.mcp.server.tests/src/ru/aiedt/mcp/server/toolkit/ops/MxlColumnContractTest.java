/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * What the column operations of {@code mxl_workshop} promise before they reach a workspace.
 * <p>
 * The bounds, the count and the {@code formatFrom} word are refused before the project is opened,
 * so a bad call cannot leave a half-shifted template behind. What the model does under a call that
 * passes these checks is covered by {@code ColumnOperationsOfATemplateTest}, on the model itself.
 * </p>
 */
public class MxlColumnContractTest
{
    private final MxlWorkshopTool tool = new MxlWorkshopTool();

    /** The arguments of the column operations are declared, or a strict client cannot pass them. */
    @Test
    public void theColumnArgumentsAreDeclared()
    {
        String schema = tool.getInputSchema();

        for (String argument : new String[] {"count", "formatFrom", "col", "fromCol", "toCol"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("undeclared argument " + argument, //$NON-NLS-1$
                schema.contains('"' + argument + '"'));
        }
        assertTrue("the format sources have to be named", schema.contains("left")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The three operations are named where operations are listed, or an agent will not find them. */
    @Test
    public void theOperationsAreNamedWhereOperationsAreListed()
    {
        assertTrue(tool.getDescription().contains("insert_columns")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("delete_columns")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("copy_columns")); //$NON-NLS-1$

        String help = tool.execute(helpCall());
        assertTrue("operation=help lists insert_columns: " + help, help.contains("insert_columns")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help.contains("delete_columns")); //$NON-NLS-1$
        assertTrue(help.contains("copy_columns")); //$NON-NLS-1$
    }

    /** A column below one is refused with the bound named, before anything is opened. */
    @Test
    public void aColBelowOneIsRefused()
    {
        Map<String, String> params = columnCall("insert_columns"); //$NON-NLS-1$
        params.put("col", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("col")); //$NON-NLS-1$
    }

    /** A count below one is refused on every column operation. */
    @Test
    public void aCountBelowOneIsRefused()
    {
        for (String operation : new String[] {"insert_columns", "delete_columns", "copy_columns"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Map<String, String> params = columnCall(operation);
            params.put("count", "0"); //$NON-NLS-1$ //$NON-NLS-2$
            if ("copy_columns".equals(operation)) //$NON-NLS-1$
            {
                params.put("fromCol", "1"); //$NON-NLS-1$ //$NON-NLS-2$
                params.put("toCol", "5"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            String answer = tool.execute(params);

            assertFalse(operation + " must refuse count=0: " + answer, //$NON-NLS-1$
                answer.contains("\"success\":true")); //$NON-NLS-1$
        }
    }

    /** A formatFrom nobody knows is refused with the words that work. */
    @Test
    public void anUnknownFormatFromIsRefused()
    {
        Map<String, String> params = columnCall("insert_columns"); //$NON-NLS-1$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formatFrom", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("left")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("right")); //$NON-NLS-1$
    }

    /** A formatFrom word of the row axis is refused on a column operation, naming this axis's words. */
    @Test
    public void aRowAxisFormatFromIsRefusedOnColumns()
    {
        Map<String, String> params = columnCall("insert_columns"); //$NON-NLS-1$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formatFrom", "above"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse("a row axis word must not pass as a column format source: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("left")); //$NON-NLS-1$
    }

    /** copy_columns without its two columns is refused, naming both. */
    @Test
    public void copyColumnsWithoutItsColumnsIsRefused()
    {
        Map<String, String> params = columnCall("copy_columns"); //$NON-NLS-1$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("fromCol")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("toCol")); //$NON-NLS-1$
    }

    /** The column outcome reaches the answer beside the call's own arguments. */
    @Test
    public void theOutcomeReachesTheAnswer()
    {
        Map<String, Object> tags = new LinkedHashMap<>();
        BmTemplateHelper.ColumnOutcome outcome = new BmTemplateHelper.ColumnOutcome();
        outcome.shiftedColumns = 3;
        outcome.resizedMerges = 2;
        outcome.removedMerges = 1;
        outcome.resizedNamedAreas.add("Шапка"); //$NON-NLS-1$
        outcome.removedNamedAreas.add("Право"); //$NON-NLS-1$
        outcome.removedDrawings.add(Integer.valueOf(7));
        outcome.lastColumn = 12;

        MxlWorkshopTool.applyColumnOutcome(tags, outcome, 4);

        assertTrue(tags.get("count").equals(Integer.valueOf(4))); //$NON-NLS-1$
        assertTrue(tags.get("shiftedColumns").equals(Integer.valueOf(3))); //$NON-NLS-1$
        assertTrue(tags.get("resizedMerges").equals(Integer.valueOf(2))); //$NON-NLS-1$
        assertTrue(tags.get("removedMerges").equals(Integer.valueOf(1))); //$NON-NLS-1$
        assertTrue(tags.get("lastColumn").equals(Integer.valueOf(12))); //$NON-NLS-1$
        assertTrue(((java.util.List<?>)tags.get("removedDrawings")).contains(Integer.valueOf(7))); //$NON-NLS-1$
        assertTrue(((java.util.List<?>)tags.get("removedNamedAreas")).contains("Право")); //$NON-NLS-1$
        assertTrue(((java.util.List<?>)tags.get("resizedNamedAreas")).contains("Шапка")); //$NON-NLS-1$
    }

    private static Map<String, String> columnCall(String operation)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", operation); //$NON-NLS-1$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private static Map<String, String> helpCall()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }
}
