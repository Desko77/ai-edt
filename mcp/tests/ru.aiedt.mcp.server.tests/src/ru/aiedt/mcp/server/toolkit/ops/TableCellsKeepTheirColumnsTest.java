/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * A metadata table row survives its own content.
 * <p>
 * A comment written across several lines or carrying a vertical bar used to leave its row: the
 * bar split the row across more columns than the header names, and a line feed moved the rest of
 * the comment onto a line of its own that no longer parsed as a row at all. The free-text cells
 * are escaped for a single cell now, and a walk that did not filter by type no longer claims it
 * applied one.
 * </p>
 */
public class TableCellsKeepTheirColumnsTest
{
    private static List<MetadataObjectsReader.MetadataInfo> oneRowWithWildComment()
    {
        List<MetadataObjectsReader.MetadataInfo> rows = new ArrayList<>();
        rows.add(new MetadataObjectsReader.MetadataInfo("Warehouses", "Склады", //$NON-NLS-1$ //$NON-NLS-2$
            "first line\nstill the comment | with a bar", "Catalog", false, false)); //$NON-NLS-1$ //$NON-NLS-2$
        return rows;
    }

    /**
     * Counts the bars a line shows as column separators, ignoring the escaped ones.
     *
     * @param line one rendered line
     * @return the number of unescaped vertical bars in it
     */
    private static int unescapedBars(String line)
    {
        int bars = 0;
        for (int i = 0; i < line.length(); i++)
        {
            if (line.charAt(i) == '|' && (i == 0 || line.charAt(i - 1) != '\\'))
            {
                bars++;
            }
        }
        return bars;
    }

    /** Every data row keeps the seven separators of a six-column table, on a single line. */
    @Test
    public void aMultiLineCommentWithABarStaysOneRowOfSixColumns()
    {
        String out = MetadataObjectsReader.formatOutput(oneRowWithWildComment(), "Demo", "all", 100, true); //$NON-NLS-1$ //$NON-NLS-2$
        String[] lines = out.split("\n"); //$NON-NLS-1$
        int dataRows = 0;
        for (String line : lines)
        {
            if (line.startsWith("| ") && !line.startsWith("| Object Name")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                dataRows++;
                assertEquals("a data row keeps its six columns", 7, unescapedBars(line)); //$NON-NLS-1$
            }
        }
        assertEquals("one object, one row - nothing spilled onto extra lines", 1, dataRows); //$NON-NLS-1$
        assertTrue("the bar of the comment is escaped, not a separator", //$NON-NLS-1$
            out.contains("still the comment \\| with a bar")); //$NON-NLS-1$
    }

    /** The applied-filter line is printed only by a walk that really narrowed by type. */
    @Test
    public void anUnfilteredWalkDoesNotClaimAFilter()
    {
        String out = MetadataObjectsReader.formatOutput(oneRowWithWildComment(), "Demo", "documents", 100, //$NON-NLS-1$ //$NON-NLS-2$
            false);
        assertFalse("an external walk ignores metadataType, so no filter may be claimed", //$NON-NLS-1$
            out.contains("Applied filter")); //$NON-NLS-1$

        String filtered = MetadataObjectsReader.formatOutput(oneRowWithWildComment(), "Demo", "documents", //$NON-NLS-1$ //$NON-NLS-2$
            100, true);
        assertTrue("a real collection walk names the filter it applied", //$NON-NLS-1$
            filtered.contains("**Applied filter:** documents")); //$NON-NLS-1$
    }
}
