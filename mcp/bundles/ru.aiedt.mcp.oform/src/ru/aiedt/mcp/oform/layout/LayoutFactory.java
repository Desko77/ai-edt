/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.function.Supplier;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * A new layout: the version-27 form the corpus holds with nothing on it - a titled body, a
 * root panel with one page and no element, the attribute table with the main attribute or
 * none, the main attribute's events block, no form handler.
 */
public final class LayoutFactory
{
    /** The version of the layout written. */
    public static final String VERSION = "27"; //$NON-NLS-1$

    private static final String FONT = "{3,0,{0},\"\",-1,-1,1,0}"; //$NON-NLS-1$

    private static final String ROOT_STYLE = "{10,1,{3,4,{0}},{3,4,{0}},{6,3,0,1},0,{3,3,{-22}},{3,4,{0}},{3,4,{0}}," //$NON-NLS-1$
        + "{3,3,{-7}},{3,3,{-21}},{3,0,{0},0,0,0,48312c09-257f-4b29-b280-284dd89efc1e},{1,0}}"; //$NON-NLS-1$

    private static final String ROOT_PANEL = "{09ccdc77-ea1a-4a6d-ab1c-3435eada2433,{1,{" + ROOT_STYLE //$NON-NLS-1$
        + ",21,0,0,0,0,0,0,{3,1," + FONT + "},0,1,{1,1,{3,@PAGETITLE,{3,0," + FONT + "},-1,1,1,\"Страница1\",1}}," //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        + "1,1,0,4,{2,0,1,1,1,0,0,0,0},{2,0,0,1,2,0,0,0,0},{2,0,1,1,3,0,0,0,0},{2,0,0,1,4,0,0,0,0},0,4294967295,5,64,0},{0}},{0}}"; //$NON-NLS-1$

    private static final String LAYOUT = "{" + VERSION + ",{16,{@TITLE,4,4294967295}," + ROOT_PANEL + ",@WIDTH,@HEIGHT,1,0,1,4,4,1}," //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        + "@ATTRIBUTES,@BLOCK,{0},1,4,1,0,0,0,{0},{0},{3,0," + FONT + "},1,2,0,0,1,1}"; //$NON-NLS-1$ //$NON-NLS-2$

    private LayoutFactory()
    {
        // static utility
    }

    /**
     * A layout with a main attribute.
     *
     * @param title the form's title
     * @param width the form's width
     * @param height the form's height
     * @param language the language the title is localized in
     * @param main the main attribute, or {@code null} for a form without one
     * @param producedTypeUuid the uuid of the produced type the main attribute is typed with;
     *        ignored when {@code main} is {@code null}
     * @return the layout tree
     */
    public static ListNode newLayout(String title, int width, int height, String language, MainAttributes.Spec main,
        String producedTypeUuid)
    {
        ListNode root = BraceTree.parse(LAYOUT);
        substitute(root, "@TITLE", () -> LayoutReader.localizedNode(language, title == null ? "" : title)); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(root, "@PAGETITLE", () -> LayoutReader.localizedNode(language, "Страница1")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(root, "@WIDTH", () -> new Token(Integer.toString(width))); //$NON-NLS-1$
        substitute(root, "@HEIGHT", () -> new Token(Integer.toString(height))); //$NON-NLS-1$
        substitute(root, "@ATTRIBUTES", () -> main == null ? BraceTree.parse("{{-1},0,{0},{0}}") //$NON-NLS-1$ //$NON-NLS-2$
            : BraceTree.parse("{{0},1,{1,{{0},0,0,1," + Token.string(main.name).text() + ",{\"Pattern\",{\"#\"," //$NON-NLS-1$ //$NON-NLS-2$
                + producedTypeUuid + "}}}},{0}}")); //$NON-NLS-1$
        substitute(root, "@BLOCK", () -> BraceTree.parse(main == null ? MainAttributes.NO_MAIN_ATTRIBUTE_BLOCK : main.block)); //$NON-NLS-1$
        return root;
    }

    private static void substitute(ListNode node, String placeholder, Supplier<Node> value)
    {
        for (int i = 0; i < node.size(); i++)
        {
            Node item = node.get(i);
            if (item instanceof ListNode)
            {
                substitute((ListNode)item, placeholder, value);
            }
            else if (placeholder.equals(LayoutReader.tokenText(item)))
            {
                node.items().set(i, value.get());
            }
        }
    }
}
