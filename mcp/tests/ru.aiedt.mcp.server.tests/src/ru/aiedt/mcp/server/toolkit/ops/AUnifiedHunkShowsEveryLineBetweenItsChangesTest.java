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

import ru.aiedt.mcp.server.toolkit.ops.DiffModuleTool.DiffLine;

/**
 * A unified hunk is a contiguous run of the diff: two changes close enough to share a hunk are
 * shown with every equal line between them, and changes further apart get hunks of their own.
 */
public class AUnifiedHunkShowsEveryLineBetweenItsChangesTest
{
    private static final int CONTEXT = 3;

    /**
     * Two changed pairs of lines with {@code gap} equal lines between them, inside four equal lines
     * on each side.
     *
     * @param gap how many equal lines stand between the two changes
     * @param changed whether to answer the changed side
     * @return the lines of that side
     */
    private static String[] module(int gap, boolean changed)
    {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 4; i++)
        {
            lines.add("head" + i); //$NON-NLS-1$
        }
        lines.add(changed ? "A1" : "a1"); //$NON-NLS-1$ //$NON-NLS-2$
        lines.add(changed ? "A2" : "a2"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 1; i <= gap; i++)
        {
            lines.add("gap" + i); //$NON-NLS-1$
        }
        lines.add(changed ? "B1" : "b1"); //$NON-NLS-1$ //$NON-NLS-2$
        lines.add(changed ? "B2" : "b2"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 1; i <= 4; i++)
        {
            lines.add("tail" + i); //$NON-NLS-1$
        }
        return lines.toArray(new String[0]);
    }

    /**
     * Asserts a hunk skips no line of either side.
     *
     * @param hunk the hunk
     * @param what the case, for the message
     */
    private static void assertContiguous(List<DiffLine> hunk, String what)
    {
        int lastOld = -1;
        int lastNew = -1;
        for (DiffLine line : hunk)
        {
            if (line.oldLineNum > 0)
            {
                assertTrue(what + ": old line " + line.oldLineNum + " after " + lastOld, //$NON-NLS-1$ //$NON-NLS-2$
                    lastOld < 0 || line.oldLineNum == lastOld + 1);
                lastOld = line.oldLineNum;
            }
            if (line.newLineNum > 0)
            {
                assertTrue(what + ": new line " + line.newLineNum + " after " + lastNew, //$NON-NLS-1$ //$NON-NLS-2$
                    lastNew < 0 || line.newLineNum == lastNew + 1);
                lastNew = line.newLineNum;
            }
        }
    }

    /**
     * @param hunk a hunk
     * @return how many equal lines it shows
     */
    private static int equalLines(List<DiffLine> hunk)
    {
        int count = 0;
        for (DiffLine line : hunk)
        {
            if (line.type == DiffLine.Type.EQUAL)
            {
                count++;
            }
        }
        return count;
    }

    /** Up to twice the context, the gap is shown whole in one hunk; past it, two hunks. */
    @Test
    public void everyGapIsShownWholeOrSplit()
    {
        for (int gap = 1; gap <= 7; gap++)
        {
            String what = "gap " + gap; //$NON-NLS-1$
            List<List<DiffLine>> hunks = DiffModuleTool.groupHunks(
                DiffModuleTool.computeDiff(module(gap, false), module(gap, true)), CONTEXT);
            for (List<DiffLine> hunk : hunks)
            {
                assertContiguous(hunk, what);
            }
            if (gap <= CONTEXT * 2)
            {
                assertEquals(what, 1, hunks.size());
                assertEquals(what, CONTEXT + gap + CONTEXT, equalLines(hunks.get(0)));
            }
            else
            {
                assertEquals(what, 2, hunks.size());
                assertEquals(what, CONTEXT * 2, equalLines(hunks.get(0)));
                assertEquals(what, CONTEXT * 2, equalLines(hunks.get(1)));
            }
        }
    }

    /** Context stops at the edges of the module. */
    @Test
    public void contextStopsAtTheEdges()
    {
        String[] before = {"a", "same"}; //$NON-NLS-1$ //$NON-NLS-2$
        String[] after = {"A", "same"}; //$NON-NLS-1$ //$NON-NLS-2$
        List<List<DiffLine>> hunks = DiffModuleTool.groupHunks(
            DiffModuleTool.computeDiff(before, after), CONTEXT);
        assertEquals(1, hunks.size());
        assertEquals(1, equalLines(hunks.get(0)));
        assertContiguous(hunks.get(0), "edges"); //$NON-NLS-1$
    }

    /** A context wider than the module shows the whole module in one hunk. */
    @Test
    public void aContextPastTheModuleShowsTheWholeModule()
    {
        List<List<DiffLine>> hunks = DiffModuleTool.groupHunks(
            DiffModuleTool.computeDiff(module(7, false), module(7, true)), Integer.MAX_VALUE);
        assertEquals(1, hunks.size());
        assertEquals(4 + 7 + 4, equalLines(hunks.get(0)));
        assertContiguous(hunks.get(0), "whole module"); //$NON-NLS-1$
    }

    /** Equal texts give no hunk. */
    @Test
    public void equalTextsGiveNoHunk()
    {
        String[] same = {"x", "y"}; //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DiffModuleTool.groupHunks(DiffModuleTool.computeDiff(same, same), CONTEXT).isEmpty());
    }
}
