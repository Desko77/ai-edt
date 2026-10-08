/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A selection that spans projects is split by project before anything is written.
 * <p>
 * The command used to take the project of the first selected object and write every selected
 * object into that project's clusters - so objects of the second project landed in the first
 * project's clusters file. The split keeps each project's objects with their own project.
 * </p>
 */
public class ASelectionIsSplitByProjectTest
{
    private static String firstLetter(String item)
    {
        return item.substring(0, 1);
    }

    /** Objects of two projects interleave and still land with their own project, in order. */
    @Test
    public void interleavedObjectsLandWithTheirOwnProject()
    {
        Map<String, List<String>> grouped = AddToClusterCommand.groupedBy(
            List.of("aOne", "bOne", "aTwo", "cOne"), ASelectionIsSplitByProjectTest::firstLetter);

        assertEquals(List.of("aOne", "aTwo"), grouped.get("a")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("bOne"), grouped.get("b")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("cOne"), grouped.get("c")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("groups appear in first-seen order", //$NON-NLS-1$
            List.of("a", "b", "c"), List.copyOf(grouped.keySet())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** An object with no project belongs to nothing the command can write to and is dropped. */
    @Test
    public void anObjectWithNoProjectIsDropped()
    {
        Map<String, List<String>> grouped = AddToClusterCommand.groupedBy(
            List.of("aOne", "xNone"), //$NON-NLS-1$ //$NON-NLS-2$
            item -> "x".equals(firstLetter(item)) ? null : firstLetter(item)); //$NON-NLS-1$

        assertEquals(List.of("aOne"), grouped.get("a")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, grouped.size());
    }
}
