/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.List;

import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * Moving an element to another panel or page. The box stays; the page the element leaves and the
 * page it lands on are both recomputed; anchors the move separates are released both ways and
 * listed on the answer.
 */
public final class MoveOperations
{
    private MoveOperations()
    {
        // static catalog
    }

    /** @return the specs of this catalog */
    public static List<OperationSpec> specs()
    {
        List<OperationSpec> out = new ArrayList<>();
        out.add(OperationSpec.layout("move_element", //$NON-NLS-1$
            "elementName to parent (a Panel, or form) and page; the box stays; anchors between the " //$NON-NLS-1$
                + "element and the neighbours the move separates are released both ways and listed", //$NON-NLS-1$
            List.of(ParamSpec.string("elementName", "the element to move."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the Panel to move the element into, or form for the root."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("page", "the page of that parent.")), //$NON-NLS-1$ //$NON-NLS-2$
            MoveOperations::moveElement));
        return out;
    }

    private static OperationResult moveElement(LayoutContext context)
    {
        String element = context.string("elementName"); //$NON-NLS-1$
        String parent = context.string("parent"); //$NON-NLS-1$
        String page = context.string("page"); //$NON-NLS-1$
        if (element == null || element.trim().isEmpty())
        {
            return OperationResult.refused("move_element needs elementName."); //$NON-NLS-1$
        }
        if (parent == null || parent.trim().isEmpty())
        {
            return OperationResult.refused("move_element needs parent: a panel, or form."); //$NON-NLS-1$
        }
        if (page == null || page.trim().isEmpty())
        {
            return OperationResult.refused("move_element needs page."); //$NON-NLS-1$
        }
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome outcome = context.editor.moveElement(element.trim(), parent.trim(), page.trim(), released);
        if (outcome.done)
        {
            context.answer.put("anchorsRemoved", released); //$NON-NLS-1$
        }
        return OperationResult.of(outcome);
    }
}
