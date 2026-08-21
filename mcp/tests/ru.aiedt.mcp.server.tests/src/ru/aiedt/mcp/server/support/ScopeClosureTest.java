/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Guards what a move has to carry along, and what it cannot know it is missing.
 * <p>
 * The walk itself needs a loaded configuration and is measured on a stand. What is pinned here is
 * the contract around it: the bounds are real numbers, a request with nothing in it is refused
 * rather than answered with an empty closure, and the list of dependencies a reference walk cannot
 * see is never empty - because a caller reading additions and no blind spots takes the first for
 * the whole answer.
 * </p>
 */
public class ScopeClosureTest
{
    @Test
    public void whatAReferenceWalkCannotSeeIsAlwaysStated()
    {
        List<String> blind = ScopeClosure.whatReferencesCannotExpress();
        assertFalse("a closure with no stated blind spots claims a completeness it cannot have",
            blind.isEmpty());
    }

    @Test
    public void theBlindSpotsNameTheWaysADependencyHidesFromTheModel()
    {
        String all = String.join(" | ", ScopeClosure.whatReferencesCannotExpress());
        assertTrue("metadata addressed by a name built at run time: " + all, all.contains("string"));
        assertTrue("names inside a query, which is text to the model: " + all, all.contains("query"));
        assertTrue("names inside a template: " + all, all.contains("template"));
    }

    @Test
    public void askingForNothingIsRefusedRatherThanAnsweredEmpty()
    {
        // An empty closure and "there was nothing to close over" read the same in an answer and
        // mean opposite things: one says the move is self-contained, the other that nobody asked.
        ScopeClosure.Closure none = ScopeClosure.of("P", Collections.emptyList(), 0);
        assertNotNull(none.cannotTell);
        ScopeClosure.Closure nulls = ScopeClosure.of("P", null, 0);
        assertNotNull(nulls.cannotTell);
    }

    @Test
    public void theBoundsAreRealNumbersAndNotUnlimited()
    {
        // A closure that ran to the whole configuration would answer "everything depends on
        // everything", which is true and useless. The bounds are what keep it an answer, and
        // reaching one is reported rather than trimmed away.
        assertTrue(ScopeClosure.MAX_DEPTH > 1);
        assertTrue(ScopeClosure.MAX_NODES > 100);
    }
}
