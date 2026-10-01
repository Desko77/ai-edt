/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A list value in the metrics report is printed as its elements, not as "[N items]".
 * <p>
 * The debt report's details are the elements - file, line, kind. A cell that counts them carries
 * none of that, and the caller asked for the list precisely to read it. Past twenty elements the
 * rest is a count of what is hidden, so the table stays a table.
 * </p>
 * <p>
 * The renderer is private and is reached by reflection, the way the pattern fields of the
 * collectors are: what is pinned is the text a caller reads, not the modifier on the method.
 * </p>
 */
public class AListInTheMetricsReportIsReadAsElementsTest
{
    /**
     * Renders one map through the report's table renderer.
     *
     * @param data the rows to render
     * @return the markdown table
     */
    private static String render(Map<String, Object> data) throws Exception
    {
        Method renderer = ProjectMetricsTool.class.getDeclaredMethod("appendMapAsTable", //$NON-NLS-1$
            StringBuilder.class, String.class, Map.class);
        renderer.setAccessible(true);
        StringBuilder sb = new StringBuilder();
        renderer.invoke(null, sb, "Debt", data); //$NON-NLS-1$
        return sb.toString();
    }

    /** A short list is printed element by element. */
    @Test
    public void aShortListIsPrintedElementByElement() throws Exception
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of("first debt", "second debt")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String table = render(data);

        assertTrue(table, table.contains("first debt")); //$NON-NLS-1$
        assertTrue(table, table.contains("second debt")); //$NON-NLS-1$
        assertFalse(table, table.contains("[2 items]")); //$NON-NLS-1$
    }

    /** A long list shows the first twenty and counts what is hidden. */
    @Test
    public void aLongListCountsWhatItHides() throws Exception
    {
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 25; i++)
        {
            items.add("debt-" + i); //$NON-NLS-1$
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items); //$NON-NLS-1$

        String table = render(data);

        assertTrue(table, table.contains("debt-1")); //$NON-NLS-1$
        assertTrue(table, table.contains("debt-20")); //$NON-NLS-1$
        assertFalse(table, table.contains("debt-21")); //$NON-NLS-1$
        assertTrue(table, table.contains("(+5 more)")); //$NON-NLS-1$
        assertFalse(table, table.contains("[25 items]")); //$NON-NLS-1$
    }
}
