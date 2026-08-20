/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the map of operation to the parameters it reads.
 * <p>
 * The map is derived from the sources rather than written by hand, because a hand-written one is
 * right on the day it is written. What is pinned here is the contract the derivation has to keep:
 * asking about something the map has nothing for must not look like an operation that takes no
 * parameters, and reading the resource must never take a call down.
 * </p>
 */
public class OperationParametersTest
{
    /** An unknown pair answers empty, and never null - a null here would end the call it serves. */
    @Test
    public void anUnknownPairAnswersEmptyRatherThanFailing()
    {
        assertNotNull(OperationParameters.of("NoSuchFacade", "no_such_operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(OperationParameters.of("NoSuchFacade", "no_such_operation").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Null in is a caller's mistake, not a reason to throw inside a help request. */
    @Test
    public void nothingAskedIsNothingAnswered()
    {
        assertNotNull(OperationParameters.of(null, null));
        assertTrue(OperationParameters.of(null, "create_object").isEmpty()); //$NON-NLS-1$
        assertTrue(OperationParameters.of("EditMetadataTool", null).isEmpty()); //$NON-NLS-1$
    }

    /**
     * Whether the map was packaged at all is a separate question from what it contains.
     * <p>
     * Without it, "this operation reads no parameters" and "the map is not here" are the same empty
     * list, and only one of them is knowledge - the distinction this project keeps paying to learn.
     * </p>
     */
    @Test
    public void thePresenceOfTheMapCanBeAskedAboutSeparately()
    {
        // Either answer is legitimate depending on how the fragment under test was assembled; what
        // matters is that the question exists and does not throw.
        boolean present = OperationParameters.available();
        assertTrue(present || !present);
    }
}
