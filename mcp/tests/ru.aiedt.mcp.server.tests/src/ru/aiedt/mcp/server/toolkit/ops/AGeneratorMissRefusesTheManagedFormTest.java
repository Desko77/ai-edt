/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What {@code create_form} does when EDT's form generator does not deliver a layout.
 * <p>
 * The generator runs for a managed form that did not explicitly ask for {@code layout=empty};
 * when it then misses (no generator on the runtime, no field tree, an exception, a form that
 * will not attach), the operation is refused and the BM transaction rolls back - no form is
 * left behind. An ORDINARY form and an explicit {@code layout=empty} never ask the generator,
 * so they cannot be refused over its miss and keep the empty-creation path.
 * </p>
 */
public class AGeneratorMissRefusesTheManagedFormTest
{
    /** The gate: a managed form without an explicit empty-layout request asks the generator. */
    @Test
    public void aManagedFormWithoutAnEmptyLayoutAsksTheGenerator()
    {
        assertTrue(FormCreateOps.generatorRunsFor("MANAGED", null)); //$NON-NLS-1$
        assertTrue(FormCreateOps.generatorRunsFor("MANAGED", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(FormCreateOps.generatorRunsFor("MANAGED", "auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(FormCreateOps.generatorRunsFor("managed", null)); //$NON-NLS-1$
    }

    /** An ORDINARY form carries its own layout; asking the generator is not its path. */
    @Test
    public void anOrdinaryFormDoesNotAskTheGenerator()
    {
        assertFalse(FormCreateOps.generatorRunsFor("ORDINARY", null)); //$NON-NLS-1$
        assertFalse(FormCreateOps.generatorRunsFor("Ordinary", "auto")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An explicit {@code layout=empty} names the empty path; the generator is not called. */
    @Test
    public void anEmptyLayoutRequestSkipsTheGenerator()
    {
        assertFalse(FormCreateOps.generatorRunsFor("MANAGED", "empty")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(FormCreateOps.generatorRunsFor("MANAGED", "EMPTY")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(FormCreateOps.generatorRunsFor("ORDINARY", "empty")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The refusal names the miss and the retry: a caller who reads only the message learns why
     * nothing was created and how to create the form anyway.
     */
    @Test
    public void theRefusalNamesTheMissAndTheEmptyLayoutRetry()
    {
        String refusal = FormCreateOps.generatorRefusalText("failed: no field tree"); //$NON-NLS-1$

        assertTrue("the miss reason is named: " + refusal, //$NON-NLS-1$
            refusal.contains("no field tree")); //$NON-NLS-1$
        assertTrue("nothing-created is stated explicitly: " + refusal, //$NON-NLS-1$
            refusal.contains("No form was created")); //$NON-NLS-1$
        assertTrue("the empty-layout retry is offered: " + refusal, //$NON-NLS-1$
            refusal.contains("layout=empty")); //$NON-NLS-1$
    }

    /** The not-found miss reads as a missing generator, not as a bare token. */
    @Test
    public void theNotFoundMissNamesTheMissingGenerator()
    {
        String refusal = FormCreateOps.generatorRefusalText("not-found"); //$NON-NLS-1$

        assertTrue(refusal.contains("not available on this runtime")); //$NON-NLS-1$
        assertFalse("the bare token does not leak into the message", //$NON-NLS-1$
            refusal.contains("(not-found)")); //$NON-NLS-1$
    }

    /** A miss without a captured reason still refuses with a complete sentence. */
    @Test
    public void aMissingReasonStillRefuses()
    {
        String refusal = FormCreateOps.generatorRefusalText(null);

        assertNotNull(refusal);
        assertFalse(refusal.isEmpty());
        assertTrue(refusal.contains("layout=empty")); //$NON-NLS-1$
    }
}
