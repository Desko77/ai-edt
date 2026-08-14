/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * Covers how a batch of positions is read off the wire.
 * <p>
 * Two spellings reach this: the JSON a program sends and the compact pairs a person types. Both are
 * accepted on purpose, so the tests hold both, and they hold the refusals too - a batch that is read
 * wrongly would answer about the wrong lines and look perfectly plausible doing it.
 * </p>
 */
public class SymbolPositionsTest
{
    @Test
    public void anAbsentArgumentMeansASinglePosition()
    {
        assertNull(SymbolInfoReader.parsePositions(null));
        assertNull(SymbolInfoReader.parsePositions("   ")); //$NON-NLS-1$
    }

    @Test
    public void objectsAreRead()
    {
        List<int[]> positions =
            SymbolInfoReader.parsePositions("[{\"line\":277,\"column\":4},{\"line\":9,\"column\":2}]"); //$NON-NLS-1$
        assertEquals(2, positions.size());
        assertArrayEquals(new int[] {277, 4}, positions.get(0));
        assertArrayEquals(new int[] {9, 2}, positions.get(1));
    }

    @Test
    public void compactPairsAreRead()
    {
        assertArrayEquals(new int[] {277, 4},
            SymbolInfoReader.parsePositions("[\"277:4\"]").get(0)); //$NON-NLS-1$
        assertArrayEquals(new int[] {12, 3},
            SymbolInfoReader.parsePositions("277:4, 12:3").get(1)); //$NON-NLS-1$
    }

    @Test
    public void twoNumberArraysAreRead()
    {
        assertArrayEquals(new int[] {5, 6}, SymbolInfoReader.parsePositions("[[5,6]]").get(0)); //$NON-NLS-1$
    }

    @Test
    public void theOrderGivenIsTheOrderKept()
    {
        List<int[]> positions = SymbolInfoReader.parsePositions("9:1, 3:1, 7:1"); //$NON-NLS-1$
        assertEquals(9, positions.get(0)[0]);
        assertEquals(3, positions.get(1)[0]);
        assertEquals(7, positions.get(2)[0]);
    }

    @Test
    public void aPositionMissingHalfOfItselfIsRefused()
    {
        refused("[{\"line\":10}]", "column"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void numbersBelowOneAreRefused()
    {
        refused("[{\"line\":0,\"column\":1}]", "at least 1"); //$NON-NLS-1$ //$NON-NLS-2$
        refused("4:0", "at least 1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void somethingThatIsNotAPairIsRefused()
    {
        refused("277", "pair"); //$NON-NLS-1$ //$NON-NLS-2$
        refused("[\"nowhere\"]", "pair"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void brokenJsonIsRefusedAsJson()
    {
        refused("[{\"line\":1,", "JSON"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEmptyBatchIsRefusedRatherThanAnsweredWithNothing()
    {
        refused("[]", "empty"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aBatchBeyondTheCeilingIsRefusedWithItsSize()
    {
        StringBuilder tokens = new StringBuilder();
        for (int i = 1; i <= SymbolInfoReader.POSITIONS_MAX + 1; i++)
        {
            tokens.append(i).append(":1"); //$NON-NLS-1$
            if (i <= SymbolInfoReader.POSITIONS_MAX)
            {
                tokens.append(',');
            }
        }
        refused(tokens.toString(), String.valueOf(SymbolInfoReader.POSITIONS_MAX));
    }

    @Test
    public void theCeilingItselfIsAccepted()
    {
        StringBuilder tokens = new StringBuilder();
        for (int i = 1; i <= SymbolInfoReader.POSITIONS_MAX; i++)
        {
            tokens.append(i).append(":1"); //$NON-NLS-1$
            if (i < SymbolInfoReader.POSITIONS_MAX)
            {
                tokens.append(',');
            }
        }
        assertEquals(SymbolInfoReader.POSITIONS_MAX,
            SymbolInfoReader.parsePositions(tokens.toString()).size());
    }

    private static void refused(String raw, String expectedInMessage)
    {
        try
        {
            SymbolInfoReader.parsePositions(raw);
            fail("a batch that cannot be read must be refused, not guessed at: " + raw); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue("the message should say what is wrong, got: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains(expectedInMessage));
        }
    }
}
