/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the query validator to refusing while the model it would answer from is still being built.
 * <p>
 * <b>It answered instead.</b> For the first seconds after EDT restarts, a query naming a catalogue
 * that certainly exists comes back "table not found", which is indistinguishable from a real
 * mistake in the query.
 * </p>
 * <p>
 * <b>Nothing a caller can reach says otherwise.</b> The health endpoint reports ready within
 * seconds of a restart, and the project listing reports ready over every project while a query in
 * one of them cannot find a table from another. Neither is a readiness signal for the query model.
 * </p>
 * <p>
 * The signal that is one is the derived-data manager, asked through ProjectStateGuard.
 * </p>
 * <p>
 * <b>What this file can and cannot hold.</b> The states come from a live EDT, so what is pinned
 * here is the vocabulary the guard answers in and the contract that anything short of ready becomes
 * a message rather than an answer. The behaviour itself is a live check, recorded in the commit.
 * </p>
 */
public class ReadinessIsAskedBeforeAnsweringTest
{
    @Test
    public void everyStateShortOfReadyHasAName()
    {
        // Two of these are not "building": a project can be absent, and the build state can be
        // unreadable. A guard that knew only ready and building would answer for both.
        assertNotNull(ProjectStateGuard.ProjectState.READY);
        assertNotNull(ProjectStateGuard.ProjectState.BUILDING);
        assertNotNull(ProjectStateGuard.ProjectState.NOT_AVAILABLE);
        assertNotNull(ProjectStateGuard.ProjectState.UNKNOWN);
        assertEquals(4, ProjectStateGuard.ProjectState.values().length);
    }

    @Test
    public void aMissingProjectIsRefusedRatherThanAnswered()
    {
        // The null case reaches this on every call, and it must not become a null dereference on
        // the way to finding out there is nothing to check.
        //
        // Typed, because the guard offers the same name for a project and for its name, and a
        // bare null names neither.
        String refusal = ProjectStateGuard.checkReadyOrError((org.eclipse.core.resources.IProject)null);
        assertNotNull("a project that is not there cannot be ready", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.length() > 0);
    }

    @Test
    public void theDefaultIdentityFormIsRecognisedExactly()
    {
        // The refusal for a building project quotes the build status, and that class does not
        // override toString - so what reached callers was a class name and a hash, which names
        // no reason at all. The guard now swaps that for words, and it tells the two apart by
        // rebuilding the identity form and comparing.
        //
        // Which puts the whole fix on this one string matching what Object.toString() produces,
        // character for character. Get the radix or the separator wrong and nothing ever matches,
        // the hashes come back, and every test above still passes.
        Object plain = new Object();
        assertEquals(plain.toString(), ProjectStateGuard.defaultToString(plain));
    }

    @Test
    public void somethingThatSaysMoreThanItsHashIsLeftAlone()
    {
        // A status class that does describe itself must keep its own words.
        Object speaks = new Object()
        {
            @Override
            public String toString()
            {
                return "indexing modules, 40 of 200"; //$NON-NLS-1$
            }
        };
        assertNotEquals(speaks.toString(), ProjectStateGuard.defaultToString(speaks));
    }

    @Test
    public void nothingToWatchIsSaidByReturningNothing()
    {
        // A watch reports whether the model moved while an answer was being computed. When one
        // cannot be opened, the honest report is that there is no watch - not a watch that will
        // cheerfully answer "nothing moved" to every question it is asked.
        assertNull(ProjectStateGuard.watchModel(null));
    }
}
