/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The pure geometry of a move and a bottom-right resize: the pixel delta is divided by the canvas
 * scale, a half unit rounds away from zero on both sides, the result saturates at the integer
 * bounds instead of wrapping, a move keeps the size, a resize keeps the left and top edges and
 * never goes below one unit, and the input box is never changed. A scale that is not finite and
 * positive is refused, as is a box that is not four coordinates.
 */
public class FormGeometryTest
{
    private static final int[] BOX = new int[] { 10, 20, 110, 70 };

    /** A half unit rounds away from zero on both sides, so the two directions are symmetric. */
    @Test
    public void unitsRoundHalfAwayFromZero()
    {
        assertEquals(1, FormGeometry.units(0.5, 1));
        assertEquals(-1, FormGeometry.units(-0.5, 1));
        assertEquals(2, FormGeometry.units(1.5, 1));
        assertEquals(-2, FormGeometry.units(-1.5, 1));
        assertEquals(2, FormGeometry.units(2.49, 1));
        assertEquals(-2, FormGeometry.units(-2.49, 1));
        assertEquals(0, FormGeometry.units(0, 1));
    }

    /** The scale divides the pixel distance before rounding. */
    @Test
    public void unitsDivideByTheScale()
    {
        assertEquals(5, FormGeometry.units(10, 2));
        assertEquals(-5, FormGeometry.units(-10, 2));
        assertEquals(5, FormGeometry.units(9, 2));
        assertEquals(-5, FormGeometry.units(-9, 2));
        assertEquals(3, FormGeometry.units(3, 1));
    }

    /** A huge distance saturates at the integer bounds instead of wrapping. */
    @Test
    public void unitsSaturateAtTheIntegerBounds()
    {
        assertEquals(Integer.MAX_VALUE, FormGeometry.units(Double.MAX_VALUE, 1));
        assertEquals(Integer.MIN_VALUE, FormGeometry.units(-Double.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, FormGeometry.units(1e12, 0.5));
    }

    /** A scale must be finite and positive. */
    @Test
    public void unitsRefuseANonPositiveScale()
    {
        assertRefused(0, 0.0);
        assertRefused(0, -1.0);
    }

    /** A scale must be finite. */
    @Test
    public void unitsRefuseANonFiniteScale()
    {
        assertRefused(0, Double.NaN);
        assertRefused(0, Double.POSITIVE_INFINITY);
        assertRefused(0, Double.NEGATIVE_INFINITY);
        assertRefused(Double.NaN, 1);
        assertRefused(Double.POSITIVE_INFINITY, 1);
    }

    /** A move keeps the width and the height exactly. */
    @Test
    public void movedKeepsTheSize()
    {
        assertArrayEquals(new int[] { 30, 30, 130, 80 }, FormGeometry.moved(BOX, 20, 10, 1));
        assertArrayEquals(new int[] { -10, 10, 90, 60 }, FormGeometry.moved(BOX, -20, -10, 1));
        assertArrayEquals(new int[] { 10, 20, 110, 70 }, FormGeometry.moved(BOX, 0, 0, 1));
    }

    /** A move divides the pixel delta by the scale. */
    @Test
    public void movedDividesByTheScale()
    {
        assertArrayEquals(new int[] { 15, 23, 115, 73 }, FormGeometry.moved(BOX, 10, 6, 2));
        assertArrayEquals(new int[] { 5, 17, 105, 67 }, FormGeometry.moved(BOX, -10, -6, 2));
    }

    /** A move rounds a negative half away from zero, like the positive half. */
    @Test
    public void movedRoundsNegativeHalvesSymmetrically()
    {
        assertArrayEquals(new int[] { 9, 19, 109, 69 }, FormGeometry.moved(BOX, -1, -1, 2));
        assertArrayEquals(new int[] { 11, 21, 111, 71 }, FormGeometry.moved(BOX, 1, 1, 2));
    }

    /** A move does not write into the box it was given. */
    @Test
    public void movedDoesNotTouchTheInput()
    {
        int[] copy = BOX.clone();
        FormGeometry.moved(BOX, 20, 10, 1);
        assertArrayEquals(copy, BOX);
    }

    /** A move clamps the shift as a whole, so both edges stop together and the size is kept. */
    @Test
    public void movedClampsTheShiftAsAWhole()
    {
        int[] high = FormGeometry.moved(new int[] { Integer.MAX_VALUE - 5, 0, Integer.MAX_VALUE, 10 },
            100, 0, 1);
        assertArrayEquals(new int[] { Integer.MAX_VALUE - 5, 0, Integer.MAX_VALUE, 10 }, high);
        int[] low = FormGeometry.moved(new int[] { Integer.MIN_VALUE, 0, Integer.MIN_VALUE + 5, 10 },
            -100, 0, 1);
        assertArrayEquals(new int[] { Integer.MIN_VALUE, 0, Integer.MIN_VALUE + 5, 10 }, low);
    }

    /** A move that only partly fits still moves both edges by the same amount. */
    @Test
    public void movedKeepsTheSizeAtTheIntegerBounds()
    {
        int[] high = FormGeometry.moved(new int[] { Integer.MAX_VALUE - 105, 0, Integer.MAX_VALUE - 5, 10 },
            100, 0, 1);
        assertArrayEquals(new int[] { Integer.MAX_VALUE - 100, 0, Integer.MAX_VALUE, 10 }, high);
        assertEquals(100, high[2] - high[0]);
        int[] low = FormGeometry.moved(new int[] { Integer.MIN_VALUE + 5, 0, Integer.MIN_VALUE + 105, 10 },
            -100, 0, 1);
        assertArrayEquals(new int[] { Integer.MIN_VALUE, 0, Integer.MIN_VALUE + 100, 10 }, low);
        assertEquals(100, low[2] - low[0]);
    }

    /** A bottom-right resize keeps the left and top edges and moves the right and bottom ones. */
    @Test
    public void resizedKeepsTheLeftAndTopEdges()
    {
        assertArrayEquals(new int[] { 10, 20, 130, 80 }, FormGeometry.resized(BOX, 20, 10, 1));
        assertArrayEquals(new int[] { 10, 20, 90, 60 }, FormGeometry.resized(BOX, -20, -10, 1));
        assertArrayEquals(new int[] { 10, 20, 110, 70 }, FormGeometry.resized(BOX, 0, 0, 1));
    }

    /** A bottom-right resize divides the pixel delta by the scale. */
    @Test
    public void resizedDividesByTheScale()
    {
        assertArrayEquals(new int[] { 10, 20, 115, 73 }, FormGeometry.resized(BOX, 10, 6, 2));
        assertArrayEquals(new int[] { 10, 20, 105, 67 }, FormGeometry.resized(BOX, -10, -6, 2));
    }

    /** A bottom-right resize never makes the box narrower or shorter than one unit. */
    @Test
    public void resizedNeverGoesBelowOneUnit()
    {
        assertArrayEquals(new int[] { 10, 20, 11, 21 }, FormGeometry.resized(BOX, -1000, -1000, 1));
        assertArrayEquals(new int[] { 10, 20, 11, 70 }, FormGeometry.resized(BOX, -1000, 0, 1));
    }

    /** A bottom-right resize does not write into the box it was given. */
    @Test
    public void resizedDoesNotTouchTheInput()
    {
        int[] copy = BOX.clone();
        FormGeometry.resized(BOX, 20, 10, 1);
        assertArrayEquals(copy, BOX);
    }

    /** A box is four coordinates; anything else is refused by name. */
    @Test
    public void movedAndResizedRefuseABoxThatIsNotFourCoordinates()
    {
        assertBoxRefused(null);
        assertBoxRefused(new int[] { 1, 2, 3 });
    }

    private static void assertRefused(double pixels, double scale)
    {
        try
        {
            FormGeometry.units(pixels, scale);
            throw new AssertionError("a scale " + scale + " must be refused"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IllegalArgumentException expected)
        {
            // the only accepted answer
        }
    }

    private static void assertBoxRefused(int[] box)
    {
        try
        {
            FormGeometry.moved(box, 0, 0, 1);
            throw new AssertionError("a box of the wrong shape must be refused"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            // the only accepted answer
        }
        try
        {
            FormGeometry.resized(box, 0, 0, 1);
            throw new AssertionError("a box of the wrong shape must be refused"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            // the only accepted answer
        }
    }
}
