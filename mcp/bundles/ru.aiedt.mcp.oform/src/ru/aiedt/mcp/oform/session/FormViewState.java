/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure, per-editor navigation state. Never edits a snapshot or an editing session. */
public final class FormViewState
{
    private final Map<String, String> pages = new HashMap<>();
    private String selection;

    /** Parent-relative x/y/width/height, with safe nonzero drawing dimensions. */
    public static int[] bounds(int[] box)
    {
        return new int[] {box[0], box[1], Math.max(1, box[2] - box[0]), Math.max(1, box[3] - box[1])};
    }
    /** Screen bounds for reveal/hit-test clients; scale is applied once after ancestor offsets. */
    public static int[] screenBounds(int[] box, int parentX, int parentY, double scale)
    {
        int[] bounds = bounds(box);
        return new int[] {(int)Math.round((parentX + bounds[0]) * scale),
            (int)Math.round((parentY + bounds[1]) * scale),
            (int)Math.round(bounds[2] * scale), (int)Math.round(bounds[3] * scale)};
    }
    /** Whether a point in the parent's coordinate system is inside the box. */
    public static boolean contains(int[] box, int x, int y)
    {
        return x >= box[0] && y >= box[1] && x < box[2] && y < box[3];
    }

    /** A dirty removed session remains visible until its user explicitly closes it. */
    public static boolean closeOnRemoval(boolean dirty) { return !dirty; }
    /** Finds scaled bounds in canvas content coordinates, independent of viewport scroll. */
    public static int[] contentBounds(List<FormSnapshot.Element> elements, String id, double scale)
    {
        return contentBounds(elements, id, scale, 0, 0);
    }
    private static int[] contentBounds(List<FormSnapshot.Element> elements, String id, double scale,
        int parentX, int parentY)
    {
        for (FormSnapshot.Element element : elements)
        {
            if (element.box == null) { continue; }
            if (java.util.Objects.equals(id, element.id)) { return screenBounds(element.box, parentX, parentY, scale); }
            int[] nested = contentBounds(element.children, id, scale,
                parentX + element.box[0], parentY + element.box[1]);
            if (nested != null) { return nested; }
        }
        return null;
    }

    public String selection() { return selection; }
    public void page(String panel, String page) { pages.put(panel, page); }
    public String page(String panel, List<FormSnapshot.Page> available)
    {
        String chosen = pages.get(panel);
        for (FormSnapshot.Page candidate : available)
        {
            if (candidate.name.equals(chosen)) { return chosen; }
        }
        String first = available.isEmpty() ? null : available.get(0).name;
        pages.put(panel, first);
        return first;
    }
    public boolean visible(String panel, List<FormSnapshot.Page> available, String page)
    {
        return page == null || java.util.Objects.equals(page, page(panel, available));
    }
    public void reveal(List<FormSnapshot.Element> elements, String id)
    {
        selection = find(elements, id, "root") ? id : null;
    }
    public void reconcile(List<FormSnapshot.Element> elements)
    {
        if (selection != null) { reveal(elements, selection); }
    }
    private boolean find(List<FormSnapshot.Element> elements, String id, String parent)
    {
        for (FormSnapshot.Element element : elements)
        {
            if (java.util.Objects.equals(element.id, id) || find(element.children, id, element.id))
            {
                if (element.page != null) { pages.put(parent, element.page); }
                return true;
            }
        }
        return false;
    }
}
