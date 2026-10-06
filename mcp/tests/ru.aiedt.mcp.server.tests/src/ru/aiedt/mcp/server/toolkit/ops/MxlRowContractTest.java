/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * What the row operations of {@code mxl_workshop} promise before they reach a workspace.
 * <p>
 * The bounds, the count and the {@code formatFrom} word are refused before the project is opened,
 * so a bad call cannot leave a half-shifted template behind. What the model does under a call that
 * passes these checks is covered by {@code RowOperationsOfATemplateTest}, on the model itself.
 * </p>
 */
public class MxlRowContractTest
{
    private final MxlWorkshopTool tool = new MxlWorkshopTool();

    /** The arguments of the row operations are declared, or a strict client cannot pass them. */
    @Test
    public void theRowArgumentsAreDeclared()
    {
        String schema = tool.getInputSchema();

        for (String argument : new String[] {"count", "formatFrom", "row", "fromRow", "toRow"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("undeclared argument " + argument, //$NON-NLS-1$
                schema.contains('"' + argument + '"'));
        }
        assertTrue("the format sources have to be named", schema.contains("above")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The three operations are named where operations are listed, or an agent will not find them. */
    @Test
    public void theOperationsAreNamedWhereOperationsAreListed()
    {
        assertTrue(tool.getDescription().contains("insert_rows")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("delete_rows")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("copy_rows")); //$NON-NLS-1$

        String help = tool.execute(helpCall());
        assertTrue("operation=help lists insert_rows: " + help, help.contains("insert_rows")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help.contains("delete_rows")); //$NON-NLS-1$
        assertTrue(help.contains("copy_rows")); //$NON-NLS-1$
    }

    /** A row below one is refused with the bound named, before anything is opened. */
    @Test
    public void aRowBelowOneIsRefused()
    {
        Map<String, String> params = rowCall("insert_rows"); //$NON-NLS-1$
        params.put("row", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("row")); //$NON-NLS-1$
    }

    /** A count below one is refused on every row operation. */
    @Test
    public void aCountBelowOneIsRefused()
    {
        for (String operation : new String[] {"insert_rows", "delete_rows", "copy_rows"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Map<String, String> params = rowCall(operation);
            params.put("count", "0"); //$NON-NLS-1$ //$NON-NLS-2$
            if ("copy_rows".equals(operation)) //$NON-NLS-1$
            {
                params.put("fromRow", "1"); //$NON-NLS-1$ //$NON-NLS-2$
                params.put("toRow", "5"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
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
        Map<String, String> params = rowCall("insert_rows"); //$NON-NLS-1$
        params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formatFrom", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("above")); //$NON-NLS-1$
        assertTrue(answer.contains("below")); //$NON-NLS-1$
    }

    /** copy_rows without its two rows is refused, naming both. */
    @Test
    public void copyRowsWithoutItsRowsIsRefused()
    {
        Map<String, String> params = rowCall("copy_rows"); //$NON-NLS-1$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("fromRow")); //$NON-NLS-1$
        assertTrue(answer.contains("toRow")); //$NON-NLS-1$
    }

    /** The row outcome reaches the answer beside the call's own arguments. */
    @Test
    public void theOutcomeReachesTheAnswer()
    {
        Map<String, Object> tags = new LinkedHashMap<>();
        BmTemplateHelper.RowOutcome outcome = new BmTemplateHelper.RowOutcome();
        outcome.shiftedRows = 3;
        outcome.resizedMerges = 2;
        outcome.removedMerges = 1;
        outcome.resizedNamedAreas.add("Шапка"); //$NON-NLS-1$
        outcome.removedNamedAreas.add("Низ"); //$NON-NLS-1$
        outcome.removedDrawings.add(Integer.valueOf(7));
        outcome.lastRow = 12;

        MxlWorkshopTool.applyRowOutcome(tags, outcome, 4);

        assertEquals(Integer.valueOf(4), tags.get("count")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), tags.get("shiftedRows")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), tags.get("resizedMerges")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), tags.get("removedMerges")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(12), tags.get("lastRow")); //$NON-NLS-1$
        assertTrue(((java.util.List<?>)tags.get("removedDrawings")).contains(Integer.valueOf(7))); //$NON-NLS-1$
        assertTrue(((java.util.List<?>)tags.get("removedNamedAreas")).contains("Низ")); //$NON-NLS-1$
        assertTrue(((java.util.List<?>)tags.get("resizedNamedAreas")).contains("Шапка")); //$NON-NLS-1$
    }

    /**
     * A refusal leaves the write by exception, so the transaction rolls back with it.
     * <p>
     * The six row and column handlers call this from inside the write callback: returning the
     * refusal text instead would let the transaction commit a model the call opened on the way in.
     * What a workspace run then keeps untouched is read off the code; this is the part a plain
     * test reaches.
     * </p>
     */
    @Test
    public void aRefusalAbortsTheWrite()
    {
        try
        {
            MxlWorkshopTool.abortOnRefusal("row 9 is past the end"); //$NON-NLS-1$
            fail("a refusal has to leave the write by exception, not by return"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException refusal)
        {
            assertEquals("the refusal text travels with the exception", "row 9 is past the end", //$NON-NLS-1$ //$NON-NLS-2$
                refusal.getMessage());
        }
        // An applied change passes through: the write runs on and commits.
        MxlWorkshopTool.abortOnRefusal(null);
    }

    private static Map<String, String> rowCall(String operation)
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
