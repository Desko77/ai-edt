/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What a property that cannot be set from text answers.
 * <p>
 * Colour, font and border are composite values in the model: the setter takes a built object, and
 * text cannot become one. The failure used to reach the caller as the JDK's own
 * "argument type mismatch", which names neither the property nor what it wanted and reads as a
 * defect in the plugin. Measured on the stand 30.08: setting a style item's value and a form
 * item's text colour both answered exactly that.
 * </p>
 */
public class ACompositePropertySaysSoTest
{
    /** Stands in for a composite model value - only its simple name is used. */
    public static final class ColorDef
    {
        private ColorDef()
        {
        }
    }

    @Test
    public void theRefusalNamesTheProperty()
    {
        String refusal = BmObjectHelper.compositeRefusal("textColor", ColorDef.class); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("textColor")); //$NON-NLS-1$
    }

    @Test
    public void theRefusalNamesTheTypeItWants()
    {
        String refusal = BmObjectHelper.compositeRefusal("textColor", ColorDef.class); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ColorDef")); //$NON-NLS-1$
    }

    @Test
    public void theRefusalSaysNothingWasChanged()
    {
        String refusal = BmObjectHelper.compositeRefusal("value", ColorDef.class); //$NON-NLS-1$
        assertTrue(refusal, refusal.toLowerCase().contains("nothing was changed")); //$NON-NLS-1$
    }

    @Test
    public void theJdkWordingIsGone()
    {
        String refusal = BmObjectHelper.compositeRefusal("value", ColorDef.class); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("argument type mismatch")); //$NON-NLS-1$
    }
}
