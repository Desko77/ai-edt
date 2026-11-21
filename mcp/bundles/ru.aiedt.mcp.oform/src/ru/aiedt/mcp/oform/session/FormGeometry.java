/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

/**
 * The pixel-to-form-units geometry a drag or a bottom-right resize uses. Pure and independent of
 * SWT and of the layout: the editor feeds it the pixels a pointer moved and the canvas scale, and
 * it answers the box to hand to {@code set_property box}. A move keeps the size, a resize keeps
 * the left and top edges and never goes below one unit; neither changes the parent or the page.
 */
public final class FormGeometry
{
    private FormGeometry()
    {
        // static helpers
    }

    /**
     * How many form units a pixel distance covers at a scale, rounded half away from zero, so a
     * half unit left and a half unit right cover the same distance. Saturates at the integer
     * bounds instead of wrapping.
     *
     * @param pixels the pointer distance in pixels
     * @param scale the canvas scale, finite and positive
     * @return the distance in form units
     * @throws IllegalArgumentException when the scale is not finite and positive, or the distance is not finite
     */
    public static int units(double pixels, double scale)
    {
        requireScale(scale);
        if (!Double.isFinite(pixels))
        {
            throw new IllegalArgumentException("the pixel distance must be finite"); //$NON-NLS-1$
        }
        return rounded(pixels / scale);
    }

    /**
     * The box a move produces: the same width and height, the left and top moved by the pixel
     * delta converted at the scale. The shift is clamped as a whole at the integer bounds, so the
     * size is kept even when one edge would leave the range. The original array is not changed.
     *
     * @param box left, top, right, bottom
     * @param dxPixels the horizontal pointer distance
     * @param dyPixels the vertical pointer distance
     * @param scale the canvas scale, finite and positive
     * @return the moved box
     * @throws IllegalArgumentException when the box is not four coordinates, or the scale or a distance is not finite
     */
    public static int[] moved(int[] box, int dxPixels, int dyPixels, double scale)
    {
        requireBox(box);
        requireScale(scale);
        int shiftX = clamp(box[0], box[2], units(dxPixels, scale));
        int shiftY = clamp(box[1], box[3], units(dyPixels, scale));
        return new int[] { box[0] + shiftX, box[1] + shiftY, box[2] + shiftX, box[3] + shiftY };
    }

    /**
     * The box a bottom-right resize produces: the left and top stay, the right and bottom move by
     * the pixel delta converted at the scale, and the box stays at least one unit wide and high.
     * The original array is not changed.
     *
     * @param box left, top, right, bottom
     * @param dxPixels the horizontal pointer distance
     * @param dyPixels the vertical pointer distance
     * @param scale the canvas scale, finite and positive
     * @return the resized box
     * @throws IllegalArgumentException when the box is not four coordinates, or the scale or a distance is not finite
     */
    public static int[] resized(int[] box, int dxPixels, int dyPixels, double scale)
    {
        requireBox(box);
        requireScale(scale);
        int right = Math.max(add(box[0], 1), add(box[2], units(dxPixels, scale)));
        int bottom = Math.max(add(box[1], 1), add(box[3], units(dyPixels, scale)));
        return new int[] { box[0], box[1], right, bottom };
    }

    /** Rounds half away from zero and saturates at the integer bounds. */
    private static int rounded(double value)
    {
        if (value >= Integer.MAX_VALUE)
        {
            return Integer.MAX_VALUE;
        }
        if (value <= Integer.MIN_VALUE)
        {
            return Integer.MIN_VALUE;
        }
        long magnitude = (long)Math.floor(Math.abs(value) + 0.5);
        if (value < 0)
        {
            return magnitude >= 1L + Integer.MAX_VALUE ? Integer.MIN_VALUE : (int)-magnitude;
        }
        return magnitude > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)magnitude;
    }

    /** Adds an int without wrapping. */
    private static int add(int value, int delta)
    {
        long sum = (long)value + delta;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int)sum;
    }

    /**
     * The largest part of {@code delta} both edges can take without leaving the integer range,
     * so the two edges move together and the distance between them stays the same.
     */
    private static int clamp(int start, int end, int delta)
    {
        long lowest = Math.max((long)Integer.MIN_VALUE - start, (long)Integer.MIN_VALUE - end);
        long highest = Math.min((long)Integer.MAX_VALUE - start, (long)Integer.MAX_VALUE - end);
        long wanted = Math.max(lowest, Math.min(highest, delta));
        return (int)wanted;
    }

    private static void requireScale(double scale)
    {
        if (!Double.isFinite(scale) || scale <= 0)
        {
            throw new IllegalArgumentException("the scale must be finite and positive"); //$NON-NLS-1$
        }
    }

    private static void requireBox(int[] box)
    {
        if (box == null || box.length < 4)
        {
            throw new IllegalArgumentException("a box is left, top, right and bottom"); //$NON-NLS-1$
        }
    }
}
