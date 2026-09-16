/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * A merge writes the cells AFTER the first, and reads them back the same way.
 * <p>
 * The span used to go in as a count of cells, so a merge of three columns was written as three and
 * opened in the environment one column too wide. The file says which it is: a merge of a single
 * cell carries no span at all, and an absent span reads back as zero - a count would have been one.
 * </p>
 * <p>
 * Both directions are pinned here because the tool read its own writing and agreed with itself: the
 * round trip was consistent and wrong, and only the platform's own reading showed it.
 * </p>
 */
public class AMergeIsWrittenTheWayThePlatformReadsItTest
{
    private static int span(int from, int to) throws Exception
    {
        Method method = BmTemplateHelper.class.getDeclaredMethod("mergeSpan", int.class, int.class); //$NON-NLS-1$
        method.setAccessible(true);
        return ((Integer)method.invoke(null, Integer.valueOf(from), Integer.valueOf(to))).intValue();
    }

    private static int farCorner(int from, int span) throws Exception
    {
        Method method = BmTemplateHelper.class.getDeclaredMethod("mergeFarCorner", int.class, //$NON-NLS-1$
            int.class);
        method.setAccessible(true);
        return ((Integer)method.invoke(null, Integer.valueOf(from), Integer.valueOf(span)))
            .intValue();
    }

    /** Three columns are two cells after the first, not three. */
    @Test
    public void theSpanCountsTheCellsAfterTheFirst() throws Exception
    {
        assertEquals("columns 2 to 4 are three cells", 2, span(2, 4)); //$NON-NLS-1$
        assertEquals("one cell has no span at all", 0, span(5, 5)); //$NON-NLS-1$
        assertEquals("rows 1 to 10", 9, span(1, 10)); //$NON-NLS-1$
    }

    /** And the far corner is read back from that span. */
    @Test
    public void theFarCornerComesBackFromTheSpan() throws Exception
    {
        assertEquals(4, farCorner(2, 2));
        assertEquals("an absent span is one cell", 5, farCorner(5, 0)); //$NON-NLS-1$
        assertEquals("and a negative one is read as absent", 5, farCorner(5, -1)); //$NON-NLS-1$
    }

    /** What was written comes back as what was asked for. */
    @Test
    public void whatIsWrittenIsWhatComesBack() throws Exception
    {
        for (int from = 1; from <= 5; from++)
        {
            for (int to = from; to <= from + 7; to++)
            {
                assertEquals("merge " + from + ".." + to, to, //$NON-NLS-1$ //$NON-NLS-2$
                    farCorner(from, span(from, to)));
            }
        }
    }
}
