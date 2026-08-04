/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;

import ru.aiedt.mcp.server.labels.MarkerKeys;

/**
 * Draws the small color swatches that stand in for a marker in menus, tables and trees.
 * <p>
 * Every swatch is produced as an {@link ImageDescriptor}, so the caller decides how to realize and
 * dispose it - through a {@link ResourceManager} that owns it for the lifetime of a control, or with a
 * one-off {@code createImage()} it disposes itself. The swatches are painted pixel by pixel, which
 * needs no display and leaves no transient image to leak, and scales cleanly for high-density screens.
 * </p>
 */
public final class MarkerIconFactory
{
    private static final int HEX_BASE = 16;

    private static final int SHORT_HEX = 3;

    private static final int LONG_HEX = 6;

    private MarkerIconFactory()
    {
        // Static factory.
    }

    /**
     * Returns the shared JFace resource manager, a convenient owner for short-lived swatches.
     *
     * @return the shared resource manager
     */
    public static ResourceManager getJFaceResources()
    {
        return JFaceResources.getResources();
    }

    /**
     * Returns a square swatch of the given color at the normal size.
     *
     * @param hex a {@code #RRGGBB} (or {@code #RGB}) color
     * @return an image descriptor for the swatch
     */
    public static ImageDescriptor getColorIcon(String hex)
    {
        return getColorIcon(hex, MarkerKeys.COLOR_ICON_SIZE_NORMAL);
    }

    /**
     * Returns a square swatch of the given color and size, framed by a thin gray border.
     *
     * @param hex a {@code #RRGGBB} (or {@code #RGB}) color
     * @param size the edge length in pixels
     * @return an image descriptor for the swatch
     */
    public static ImageDescriptor getColorIcon(String hex, int size)
    {
        return new SwatchImageDescriptor(hexToRgb(hex), size, false, false);
    }

    /**
     * Returns a round swatch of the given color, optionally overlaid with a checkmark.
     *
     * @param hex a {@code #RRGGBB} (or {@code #RGB}) color
     * @param size the edge length in pixels
     * @param checked overlays a tick, marking the entry as assigned
     * @return an image descriptor for the swatch
     */
    public static ImageDescriptor getCircularColorIconWithCheck(String hex, int size, boolean checked)
    {
        return new SwatchImageDescriptor(hexToRgb(hex), size, true, checked);
    }

    /**
     * Parses a hex color string. A missing {@code #}, the short {@code #RGB} form, and stray
     * whitespace are all accepted; anything unparseable yields a neutral gray.
     *
     * @param hex the color string
     * @return the parsed color, or gray when it cannot be parsed
     */
    public static RGB hexToRgb(String hex)
    {
        if (hex != null)
        {
            String value = hex.trim();
            if (value.startsWith("#")) //$NON-NLS-1$
            {
                value = value.substring(1);
            }
            try
            {
                if (value.length() == SHORT_HEX)
                {
                    int r = Integer.parseInt(value.substring(0, 1), HEX_BASE) * 17;
                    int g = Integer.parseInt(value.substring(1, 2), HEX_BASE) * 17;
                    int b = Integer.parseInt(value.substring(2, 3), HEX_BASE) * 17;
                    return new RGB(r, g, b);
                }
                if (value.length() == LONG_HEX)
                {
                    int r = Integer.parseInt(value.substring(0, 2), HEX_BASE);
                    int g = Integer.parseInt(value.substring(2, 4), HEX_BASE);
                    int b = Integer.parseInt(value.substring(4, 6), HEX_BASE);
                    return new RGB(r, g, b);
                }
            }
            catch (NumberFormatException e)
            {
                // Fall through to the default.
            }
        }
        return new RGB(128, 128, 128);
    }

    /**
     * Formats a color as an uppercase {@code #RRGGBB} string.
     *
     * @param rgb the color
     * @return the hex string
     */
    public static String rgbToHex(RGB rgb)
    {
        return String.format("#%02X%02X%02X", rgb.red, rgb.green, rgb.blue); //$NON-NLS-1$
    }

    /**
     * An image descriptor that paints a color swatch on demand, square or round, with an optional
     * checkmark, at whatever pixel size the requested zoom asks for.
     */
    private static final class SwatchImageDescriptor
        extends ImageDescriptor
    {
        private final RGB rgb;

        private final int size;

        private final boolean circular;

        private final boolean checked;

        SwatchImageDescriptor(RGB rgb, int size, boolean circular, boolean checked)
        {
            this.rgb = rgb;
            this.size = Math.max(1, size);
            this.circular = circular;
            this.checked = checked;
        }

        @Override
        public ImageData getImageData(int zoom)
        {
            int pixels = Math.max(1, Math.round(size * (zoom / 100.0f)));
            return circular ? paintCircular(pixels) : paintSquare(pixels);
        }

        /**
         * Paints an opaque square with a one-pixel gray border.
         *
         * @param px the edge length in pixels
         * @return the painted image data
         */
        private ImageData paintSquare(int px)
        {
            PaletteData palette = new PaletteData(0xFF0000, 0xFF00, 0xFF);
            ImageData data = new ImageData(px, px, 24, palette);
            int fill = palette.getPixel(rgb);
            int border = palette.getPixel(new RGB(128, 128, 128));
            for (int y = 0; y < px; y++)
            {
                for (int x = 0; x < px; x++)
                {
                    boolean edge = x == 0 || y == 0 || x == px - 1 || y == px - 1;
                    data.setPixel(x, y, edge ? border : fill);
                }
            }
            return data;
        }

        /**
         * Paints a filled circle on a transparent field, with a contrasting checkmark when asked.
         *
         * @param px the edge length in pixels
         * @return the painted image data, with per-pixel transparency
         */
        private ImageData paintCircular(int px)
        {
            PaletteData palette = new PaletteData(0xFF0000, 0xFF00, 0xFF);
            ImageData data = new ImageData(px, px, 24, palette);
            data.alphaData = new byte[px * px];
            int fill = palette.getPixel(rgb);
            double center = (px - 1) / 2.0;
            double radius = px / 2.0 - 0.5;
            double radiusSquared = radius * radius;
            for (int y = 0; y < px; y++)
            {
                for (int x = 0; x < px; x++)
                {
                    double dx = x - center;
                    double dy = y - center;
                    if (dx * dx + dy * dy <= radiusSquared)
                    {
                        data.setPixel(x, y, fill);
                        data.alphaData[y * px + x] = (byte)255;
                    }
                }
            }
            if (checked)
            {
                drawCheck(data, px, palette);
            }
            return data;
        }

        /**
         * Overlays a simple two-stroke checkmark, in whichever of black or white reads better against
         * the swatch color.
         *
         * @param data the image being painted
         * @param px the edge length in pixels
         * @param palette the image palette
         */
        private void drawCheck(ImageData data, int px, PaletteData palette)
        {
            int luminance = (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
            int markPixel = palette.getPixel(luminance < 128 ? new RGB(255, 255, 255) : new RGB(0, 0, 0));
            plot(data, px, markPixel, 0.28, 0.52, 0.44, 0.68);
            plot(data, px, markPixel, 0.44, 0.68, 0.74, 0.34);
        }

        /**
         * Draws a short opaque line between two points given in fractions of the icon size.
         *
         * @param data the image being painted
         * @param px the edge length in pixels
         * @param markPixel the palette pixel to draw with
         * @param fromX start x as a fraction 0..1
         * @param fromY start y as a fraction 0..1
         * @param toX end x as a fraction 0..1
         * @param toY end y as a fraction 0..1
         */
        private void plot(ImageData data, int px, int markPixel, double fromX, double fromY, double toX,
            double toY)
        {
            int x0 = (int)Math.round(fromX * (px - 1));
            int y0 = (int)Math.round(fromY * (px - 1));
            int x1 = (int)Math.round(toX * (px - 1));
            int y1 = (int)Math.round(toY * (px - 1));
            int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            if (steps == 0)
            {
                setMark(data, px, x0, y0, markPixel);
                return;
            }
            for (int i = 0; i <= steps; i++)
            {
                int x = x0 + (x1 - x0) * i / steps;
                int y = y0 + (y1 - y0) * i / steps;
                setMark(data, px, x, y, markPixel);
            }
        }

        /**
         * Sets one opaque pixel, if it lies inside the image.
         *
         * @param data the image being painted
         * @param px the edge length in pixels
         * @param x the x coordinate
         * @param y the y coordinate
         * @param markPixel the palette pixel to set
         */
        private void setMark(ImageData data, int px, int x, int y, int markPixel)
        {
            if (x >= 0 && x < px && y >= 0 && y < px)
            {
                data.setPixel(x, y, markPixel);
                data.alphaData[y * px + x] = (byte)255;
            }
        }
    }
}
