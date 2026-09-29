/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How long a miss at resolving EDT's form generator is remembered.
 * <p>
 * The generator lives in a bundle that starts later in the session than this server, so the first
 * {@code create_form} can ask before {@code FormPlugin.getDefault()} answers. The miss used to be
 * remembered as final, and every form created in that window fell back to the empty path for the
 * rest of the session - including forms made long after the bundle was up. A miss this runtime
 * cannot recover from (no bundle, no class) is still remembered, because asking again costs a
 * class lookup on every call.
 * </p>
 */
public class TheFormGeneratorMissIsKeptOnlyWhenItIsFinalTest
{
    private static final Object GENERATOR = new Object();

    /** The regression: the bundle not being started yet is not a final answer. */
    @Test
    public void aBundleThatHasNotStartedIsAskedAgain()
    {
        BmFormGeneratorHelper.ResolutionCache cache = new BmFormGeneratorHelper.ResolutionCache();

        cache.record(null, false);

        assertFalse("the next call has to ask again", cache.runtimeLacksIt()); //$NON-NLS-1$
        assertNull(cache.generator());
    }

    /** A runtime that does not carry the bundle at all answers the same forever. */
    @Test
    public void aRuntimeWithoutTheBundleIsAskedOnce()
    {
        BmFormGeneratorHelper.ResolutionCache cache = new BmFormGeneratorHelper.ResolutionCache();

        cache.record(null, true);

        assertTrue("nothing will change on this runtime", cache.runtimeLacksIt()); //$NON-NLS-1$
        assertNull(cache.generator());
    }

    /** A generator, once found, is kept. */
    @Test
    public void aFoundGeneratorIsKept()
    {
        BmFormGeneratorHelper.ResolutionCache cache = new BmFormGeneratorHelper.ResolutionCache();

        cache.record(GENERATOR, false);

        assertSame(GENERATOR, cache.generator());
        assertFalse(cache.runtimeLacksIt());
    }

    /** A later miss cannot undo a generator that was already found. */
    @Test
    public void aMissCannotUndoAFoundGenerator()
    {
        BmFormGeneratorHelper.ResolutionCache cache = new BmFormGeneratorHelper.ResolutionCache();
        cache.record(GENERATOR, false);

        cache.record(null, true);

        assertSame(GENERATOR, cache.generator());
        assertFalse("a bundle this runtime carries is not lacking", cache.runtimeLacksIt()); //$NON-NLS-1$
    }

    /** A fresh cache has neither answer. */
    @Test
    public void aFreshCacheHasNothingToSay()
    {
        BmFormGeneratorHelper.ResolutionCache cache = new BmFormGeneratorHelper.ResolutionCache();

        assertNull(cache.generator());
        assertFalse(cache.runtimeLacksIt());
    }
}
