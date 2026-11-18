/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

/** Page and selection state survives replacement snapshots without touching the session. */
public class FormViewStateTest
{
    private static FormSnapshot.Element element(String id, String page, List<FormSnapshot.Page> pages,
        List<FormSnapshot.Element> children)
    {
        return new FormSnapshot.Element("Panel", id, id, null, null, new int[] {10, 20, 90, 100}, page,
            null, null, List.of(), List.of(), pages, List.of(), children);
    }

    @Test
    public void pagesDefaultToFirstAndUnknownPagesAreNotSilentlyShown()
    {
        FormViewState state = new FormViewState();
        List<FormSnapshot.Page> pages = List.of(new FormSnapshot.Page("a", null), new FormSnapshot.Page("b", null));
        assertTrue(state.visible("root", pages, "a"));
        assertFalse(state.visible("root", pages, "b"));
        assertTrue(state.visible("root", pages, null));
        assertFalse(state.visible("root", pages, "missing"));
        state.page("root", "b");
        assertTrue(state.visible("root", pages, "b"));
        assertFalse(state.visible("root", pages, "a"));
        assertEquals("a", state.page("root", List.of(new FormSnapshot.Page("a", null))));
    }

    @Test
    public void nestedBoxesStayParentRelativeAndZoomIsAppliedOnce()
    {
        int[] panel = {100, 80, 400, 280};
        int[] child = {10, 20, 90, 60};
        assertArrayEquals(new int[] {10, 20, 80, 40}, FormViewState.bounds(child));
        assertArrayEquals(new int[] {165, 150, 120, 60}, FormViewState.screenBounds(child, panel[0], panel[1], 1.5));
        assertTrue(FormViewState.contains(child, 15, 25));
        assertFalse(FormViewState.contains(child, 100, 100));
        assertArrayEquals(new int[] {0, 0, 1, 1}, FormViewState.bounds(new int[] {0, 0, -1, -1}));
    }

    @Test
    public void revealUsesContentCoordinatesNotTheScrolledViewport()
    {
        FormSnapshot.Element child = element("child", null, List.of(), List.of());
        FormSnapshot.Element panel = element("panel", null, List.of(), List.of(child));
        assertArrayEquals(new int[] {30, 60, 120, 120},
            FormViewState.contentBounds(List.of(panel), "child", 1.5));
        assertNull(FormViewState.contentBounds(List.of(panel), "removed", 2));
    }

    @Test
    public void dirtyRemovalKeepsTheTerminalViewerInsteadOfSilentlyClosing()
    {
        assertFalse(FormViewState.closeOnRemoval(true));
        assertTrue(FormViewState.closeOnRemoval(false));
    }

    @Test
    public void revealingANestedSelectionSwitchesEachAncestorPage()
    {
        List<FormSnapshot.Page> pages = List.of(new FormSnapshot.Page("a", null), new FormSnapshot.Page("b", null));
        FormSnapshot.Element child = element("child", "b", List.of(), List.of());
        FormSnapshot.Element panel = element("panel", "b", pages, List.of(child));
        FormViewState state = new FormViewState();
        state.reveal(List.of(panel), "child");
        assertEquals("child", state.selection());
        assertEquals("b", state.page("root", pages));
        assertEquals("b", state.page("panel", pages));
        state.reconcile(List.of(element("child", "a", List.of(), List.of())));
        assertEquals("child", state.selection());
        state.reconcile(List.of());
        assertNull(state.selection());
    }
}
