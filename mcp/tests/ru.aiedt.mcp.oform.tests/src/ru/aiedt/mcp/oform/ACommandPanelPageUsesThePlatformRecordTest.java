/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * A command panel added off the first page lands on the slot the platform reads the page from.
 * The platform parses the common list of an element record positionally: a header, a flag and
 * six anchor slots, then six counted sections of the tab chain - each a count followed by that
 * many entries - then the page ordinal, then four tail items. The test walks the serialized
 * record the same way, so what is asserted is the platform's grammar and not the reader's
 * {@code size - 5} shortcut: the walk and the shortcut agree on every correct record and
 * disagree on a record one item longer. The panel is created through the editor's template
 * route on a fixture of each measured version (25, 26 and 27), on a page off the first one,
 * and its record is found by the id the edit returned. The same walk over the command panels
 * the real fixtures hold on pages 1 and 2 establishes the grammar the assertion assumes.
 */
public class ACommandPanelPageUsesThePlatformRecordTest
{
    /** The item of a common list the counted sections begin at, past the header, the flag and the six anchors. */
    private static final int SECTIONS_START = 12;

    /** The counted sections of the tab chain every measured common list holds. */
    private static final int SECTIONS = 6;

    /** The tail items of a common list that follow the page ordinal on every measured record. */
    private static final int TAIL = 4;

    /**
     * A fixture per measured version whose panel holds a page off the first one: the fixture,
     * the version, the panel, the page and the page's ordinal.
     */
    private static final String[][] VERSIONS = {
        { "fixtures/settings-form-v25.form", "25", "Text10", "Text9", "1" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        { "fixtures/settings-form-v26.form", "26", "Text11", "Text10", "2" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        { "fixtures/processing-form-v27.form", "27", "Text14", "Text13", "1" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    /**
     * The command panels the real fixtures hold off the first page: the fixture, the panel's
     * name and the page ordinal the platform wrote.
     */
    private static final String[][] MEASURED = {
        { "fixtures/settings-form-v25.form", "Text22", "1" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "fixtures/settings-form-v26.form", "Text55", "1" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "fixtures/settings-form-v26.form", "Text36", "2" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "fixtures/processing-form-v27.form", "Text57", "1" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    /** The command panels of the real fixtures follow the grammar the added record is asserted against. */
    @Test
    public void theMeasuredPanelsFollowTheWalkedGrammar() throws IOException
    {
        for (String[] measured : MEASURED)
        {
            String label = measured[0] + " " + measured[1]; //$NON-NLS-1$
            ListNode tree = BraceTree.parse(ALayoutIsReadOffTheContainerTest.fixture(measured[0]));
            ListNode record = recordWhere(tree, null, measured[1]);
            assertNotNull(label, record);
            ListNode common = LayoutReader.commonOf(record);
            assertNotNull(label, common);
            int slot = pageSlot(common, label);
            assertEquals(label, Integer.valueOf(measured[2]), number(common.get(slot)));
            assertEquals(label, TAIL, common.size() - slot - 1);
        }
    }

    /**
     * On a layout of every measured version the panel added through the template route carries
     * the requested page in the slot the platform reads, with the four tail items behind it.
     */
    @Test
    public void anAddedPanelCarriesItsPageInThePlatformSlot() throws IOException
    {
        for (String[] probe : VERSIONS)
        {
            String label = probe[0] + " v" + probe[1]; //$NON-NLS-1$
            ListNode tree = BraceTree.parse(ALayoutIsReadOffTheContainerTest.fixture(probe[0]));
            LayoutEditor editor = LayoutEditor.over(tree, "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome added = editor.addElement("CommandPanel", "ProbeCommands", probe[2], probe[3], //$NON-NLS-1$ //$NON-NLS-2$
                new int[] { 10, 10, 200, 30 }, null, null, null);
            assertTrue(label + ": " + added.message, added.done); //$NON-NLS-1$
            assertNotNull(label, added.elementId);
            ListNode record = recordWhere(editor.root(), added.elementId, null);
            assertNotNull(label + " id " + added.elementId, record); //$NON-NLS-1$
            assertEquals(label, "ProbeCommands", LayoutReader.elementName(LayoutReader.identityOf(record))); //$NON-NLS-1$
            ListNode common = LayoutReader.commonOf(record);
            assertNotNull(label, common);
            int slot = pageSlot(common, label);
            // The platform reads the page positionally: the requested page sits at the walked
            // slot and the four tail items end the record - a record one item longer fails both.
            assertEquals(label, Integer.valueOf(probe[4]), number(common.get(slot)));
            assertEquals(label, TAIL, common.size() - slot - 1);
            String text = editor.text();
            assertEquals(label, text, BraceTree.serialize(BraceTree.parse(text)));
        }
    }

    /**
     * The slot the page ordinal is read from: past the six counted sections that begin at
     * {@link #SECTIONS_START}, each a count followed by that many entries.
     *
     * @param common the common list of an element record
     * @param label the assertion label
     * @return the index of the page ordinal in the common list
     */
    private static int pageSlot(ListNode common, String label)
    {
        int at = SECTIONS_START;
        for (int section = 0; section < SECTIONS; section++)
        {
            assertTrue(label + " section " + section, at < common.size()); //$NON-NLS-1$ //$NON-NLS-2$
            Integer count = number(common.get(at));
            assertNotNull(label + " section " + section, count); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(label + " section " + section, at + count.intValue() < common.size()); //$NON-NLS-1$ //$NON-NLS-2$
            for (int entry = 0; entry < count.intValue(); entry++)
            {
                assertTrue(label + " section " + section + " entry " + entry, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    common.get(at + 1 + entry) instanceof ListNode);
            }
            at += 1 + count.intValue();
        }
        return at;
    }

    /**
     * The element record of the tree with the id or the name given.
     *
     * @param root the brace tree of a layout
     * @param id the element id, or {@code null}
     * @param name the element name, or {@code null}
     * @return the record, or {@code null}
     */
    private static ListNode recordWhere(ListNode root, String id, String name)
    {
        List<ListNode> records = new ArrayList<>();
        ListNode body = root.size() > 1 ? asList(root.get(1)) : null;
        ListNode panel = body != null && body.size() > 2 ? asList(body.get(2)) : null;
        ListNode children = panel != null && panel.size() > 2 ? asList(panel.get(2)) : null;
        if (children != null)
        {
            collect(children, records);
        }
        for (ListNode record : records)
        {
            if (id != null && id.equals(text(record.get(1))))
            {
                return record;
            }
            if (name != null && name.equals(LayoutReader.elementName(LayoutReader.identityOf(record))))
            {
                return record;
            }
        }
        return null;
    }

    /**
     * Every element record below the holder, depth first.
     *
     * @param holder the list that holds records
     * @param out the records collected
     */
    private static void collect(ListNode holder, List<ListNode> out)
    {
        for (Node item : holder.items())
        {
            ListNode record = asList(item);
            if (record == null)
            {
                continue;
            }
            if (LayoutReader.isElementRecord(record))
            {
                out.add(record);
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    collect(nested, out);
                }
            }
            else
            {
                collect(record, out);
            }
        }
    }

    /**
     * The list a node is, or {@code null}.
     *
     * @param node the node
     * @return the list, or {@code null}
     */
    private static ListNode asList(Node node)
    {
        return node instanceof ListNode list ? list : null;
    }

    /**
     * The text of a token, or {@code null} for a list.
     *
     * @param node the node
     * @return the text, or {@code null}
     */
    private static String text(Node node)
    {
        return node instanceof Token token ? token.text() : null;
    }

    /**
     * The integer a plain token holds, or {@code null}.
     *
     * @param node the node
     * @return the integer, or {@code null}
     */
    private static Integer number(Node node)
    {
        if (node instanceof Token token && !token.isString())
        {
            try
            {
                return Integer.valueOf(token.text());
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
        return null;
    }
}
