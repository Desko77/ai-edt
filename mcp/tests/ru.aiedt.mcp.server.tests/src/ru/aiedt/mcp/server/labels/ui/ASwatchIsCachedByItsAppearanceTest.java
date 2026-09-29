/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.eclipse.jface.resource.ImageDescriptor;
import org.junit.Test;

import ru.aiedt.mcp.server.labels.MarkerKeys;

/**
 * A swatch is the same image for the same color, size and shape, so a resource manager can keep one
 * image instead of allocating another on every repaint.
 */
public class ASwatchIsCachedByItsAppearanceTest
{
    @Test
    public void theSameColorIsTheSameSwatch()
    {
        ImageDescriptor first = MarkerIconFactory.getColorIcon("#C0FFEE"); //$NON-NLS-1$
        ImageDescriptor second = MarkerIconFactory.getColorIcon("#C0FFEE"); //$NON-NLS-1$

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void aDifferentColorSizeOrShapeIsADifferentSwatch()
    {
        ImageDescriptor square = MarkerIconFactory.getColorIcon("#C0FFEE"); //$NON-NLS-1$

        assertNotEquals(square, MarkerIconFactory.getColorIcon("#00FF00")); //$NON-NLS-1$
        assertNotEquals(square, MarkerIconFactory.getColorIcon("#C0FFEE", MarkerKeys.COLOR_ICON_SIZE_NORMAL + 8)); //$NON-NLS-1$
        assertNotEquals(MarkerIconFactory.getCircularColorIconWithCheck("#C0FFEE", 16, true), //$NON-NLS-1$
            MarkerIconFactory.getCircularColorIconWithCheck("#C0FFEE", 16, false)); //$NON-NLS-1$
        assertFalse(square.equals(null));
    }
}
