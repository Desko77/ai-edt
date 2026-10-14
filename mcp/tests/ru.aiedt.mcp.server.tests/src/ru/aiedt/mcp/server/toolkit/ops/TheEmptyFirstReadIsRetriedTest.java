/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * An empty first read is retried, both where the error report reads and where a baseline record
 * reads.
 * <p>
 * Right after a validation or a build the markers are still on their way, and a single empty read
 * answers none of them. For the report that reads as a clean project; for a baseline record it
 * would write an empty "before" that names every standing finding new at the next comparison.
 * Both reads go through one helper, so the two cannot drift apart.
 * </p>
 */
public class TheEmptyFirstReadIsRetriedTest
{
    /**
     * A reader that answers empty twice and then the findings: the helper returns the findings.
     */
    @Test
    public void aReadThatFillsOnTheThirdCallAnswersTheFindings()
    {
        AtomicInteger calls = new AtomicInteger();
        List<String> answer = ProjectProblemsReader.retryEmptyRead(() -> {
            if (calls.incrementAndGet() < 3)
            {
                return Collections.emptyList();
            }
            return Arrays.asList("finding"); //$NON-NLS-1$
        });

        assertEquals(List.of("finding"), answer); //$NON-NLS-1$
        assertEquals(3, calls.get());
    }

    /**
     * A reader that stays empty is retried the full count and then answered empty - empty after
     * the retries is a claim about the project, not a read that gave up early.
     */
    @Test
    public void aReadThatStaysEmptyIsRetriedTheFullCount()
    {
        AtomicInteger calls = new AtomicInteger();

        List<String> answer = ProjectProblemsReader.retryEmptyRead(() -> {
            calls.incrementAndGet();
            return Collections.emptyList();
        });

        assertTrue(answer.isEmpty());
        assertEquals(4, calls.get());
    }

    /**
     * A reader that answers on the first call is not retried: a fresh project pays no wait.
     */
    @Test
    public void aReadThatAnswersAtOnceIsNotRetried()
    {
        AtomicInteger calls = new AtomicInteger();

        List<String> answer = ProjectProblemsReader.retryEmptyRead(() -> {
            calls.incrementAndGet();
            return Arrays.asList("finding"); //$NON-NLS-1$
        });

        assertEquals(1, answer.size());
        assertEquals(1, calls.get());
    }
}
