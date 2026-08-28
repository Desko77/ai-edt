/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDefinedTypeHelper.QualifierOptions;

/**
 * A qualifier is part of what the caller asked for, not a decoration on the type name.
 * <p>
 * {@code set_object_type} skipped its work when the type names it found matched the ones it was
 * given. Changing only a qualifier - the precision of a number, the length of a string, which parts
 * of a date - leaves those names identical, so the skip fired and the qualifier was never written.
 * The answer still said the type had been applied.
 * </p>
 * <p>
 * Measured on the stand 2026-08-28: an attribute of Number(2,0) asked to become Number(5,2) came
 * back with {@code applied: true} and kept {@code precision 2} in its .mdo. Two cases of the same
 * thing were reported from a configuration of ERP size before that.
 * </p>
 */
public class AQualifierIsPartOfTheRequestTest
{
    @Test
    public void anEmptyRequestAsksForNoQualifier()
    {
        assertFalse("nothing named means the skip is still safe", //$NON-NLS-1$
            new QualifierOptions().anyRequested());
    }

    @Test
    public void aPrecisionCounts()
    {
        QualifierOptions options = new QualifierOptions();
        options.precision = Integer.valueOf(5);

        assertTrue(options.anyRequested());
    }

    @Test
    public void aFractionCountOfZeroIsStillAnAnswer()
    {
        // Zero digits after the point is a value somebody chose, not an absence.
        QualifierOptions options = new QualifierOptions();
        options.fractionDigits = Integer.valueOf(0);

        assertTrue(options.anyRequested());
    }

    @Test
    public void aSignRestrictionOfFalseIsStillAnAnswer()
    {
        QualifierOptions options = new QualifierOptions();
        options.nonNegative = Boolean.FALSE;

        assertTrue(options.anyRequested());
    }

    @Test
    public void everyQualifierTheOptionsCarryIsCounted()
    {
        QualifierOptions byLength = new QualifierOptions();
        byLength.length = Integer.valueOf(50);
        assertTrue(byLength.anyRequested());

        QualifierOptions byDate = new QualifierOptions();
        byDate.dateFractions = "DateTime"; //$NON-NLS-1$
        assertTrue(byDate.anyRequested());

        QualifierOptions byAllowedLength = new QualifierOptions();
        byAllowedLength.allowedLength = "Fixed"; //$NON-NLS-1$
        assertTrue(byAllowedLength.anyRequested());
    }
}
