/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * The error count of an object is unknown when the delegated call did not answer a count.
 * <p>
 * A get_project_errors answer that is neither "No Errors Found" nor "**Found:** N" is a call that
 * failed, not an object without problems. Counting table rows over a failure text produced zero -
 * indistinguishable from a clean object - so the answer is the same unknown signal (-1) the
 * reference count next to it already uses.
 * </p>
 */
public class ErrorCountIsUnknownWhenTheDelegateDidNotAnswerTest
{
    /** A get_project_errors stand-in that answers one fixed text. */
    private static final class FixedAnswer implements IMcpTool
    {
        private final String answer;

        FixedAnswer(String answer)
        {
            this.answer = answer;
        }

        @Override
        public String getName()
        {
            return "get_project_errors"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return getName();
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return answer;
        }
    }

    /** A failure text from the delegate reads as unknown, never as zero. */
    @Test
    public void aRefusalTextIsUnknownNotZero()
    {
        int count = ObjectSummaryTool.errorCountFrom(
            new FixedAnswer("Error: project model is not available"), "Demo", "Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a failed delegate answers unknown (-1)", -1, count); //$NON-NLS-1$
    }

    /** No answer at all is unknown too. */
    @Test
    public void aMissingAnswerIsUnknown()
    {
        assertEquals(-1, ObjectSummaryTool.errorCountFrom(new FixedAnswer(null), "Demo", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The two answers that carry a count still read as counts. */
    @Test
    public void aCleanObjectStaysZeroAndAFoundCountIsRead()
    {
        assertEquals(0, ObjectSummaryTool.errorCountFrom(
            new FixedAnswer("# Errors\n\nNo Errors Found\n"), "Demo", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(12, ObjectSummaryTool.errorCountFrom(
            new FixedAnswer("**Found:** 12 problems"), "Demo", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The empty result the problems reader really writes counts as zero, not as unknown. */
    @Test
    public void theReadersEmptyResultIsZero()
    {
        assertEquals(0, ObjectSummaryTool.errorCountFrom(
            new FixedAnswer("# Nothing Found\n\nScope: **object**\n"), "Demo", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
