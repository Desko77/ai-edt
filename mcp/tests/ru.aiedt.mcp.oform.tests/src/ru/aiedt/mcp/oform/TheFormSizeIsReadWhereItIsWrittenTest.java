/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * The size of the form is read from the two numbers after the root panel, the place
 * {@link LayoutEditor#setSize(Integer, Integer)} writes: on a form of every measured layout version
 * the reader answers the fixture's own size, and after a resize it answers the new one. A layout
 * with nothing there answers no size rather than a guess.
 */
public class TheFormSizeIsReadWhereItIsWrittenTest
{
    /** The fixtures of versions 25, 26 and 27 and the size each records. */
    private static final Object[][] FIXTURES = {
        { "fixtures/settings-form-v25.form", "25", 490, 311 }, //$NON-NLS-1$ //$NON-NLS-2$
        { "fixtures/settings-form-v26.form", "26", 370, 311 }, //$NON-NLS-1$ //$NON-NLS-2$
        { "fixtures/panel-object-form-v26.form", "26", 333, 84 }, //$NON-NLS-1$ //$NON-NLS-2$
        { "fixtures/item-form-v27.form", "27", 750, 480 }, //$NON-NLS-1$ //$NON-NLS-2$
        { "fixtures/processing-form-v27.form", "27", 573, 414 } }; //$NON-NLS-1$ //$NON-NLS-2$

    /** Each fixture answers the size it records. */
    @Test
    public void everyMeasuredVersionAnswersItsSize() throws Exception
    {
        for (Object[] fixture : FIXTURES)
        {
            String name = (String)fixture[0];
            FormLayout layout = LayoutReader.read(BraceTree.parse(ALayoutIsReadOffTheContainerTest.fixture(name)), "Catalog"); //$NON-NLS-1$
            assertEquals(name, fixture[1], layout.version);
            assertEquals(name, fixture[2], layout.width);
            assertEquals(name, fixture[3], layout.height);
        }
    }

    /** A resize is what the reader answers next, on every measured version. */
    @Test
    public void aResizeIsReadBack() throws Exception
    {
        for (Object[] fixture : FIXTURES)
        {
            String name = (String)fixture[0];
            ListNode tree = BraceTree.parse(ALayoutIsReadOffTheContainerTest.fixture(name));
            LayoutEditor editor = LayoutEditor.over(tree, "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome resized = editor.setSize(Integer.valueOf(640), Integer.valueOf(400));
            assertTrue(name + ": " + resized.message, resized.done); //$NON-NLS-1$
            FormLayout layout = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
            assertEquals(name, Integer.valueOf(640), layout.width);
            assertEquals(name, Integer.valueOf(400), layout.height);

            LayoutEditor.Outcome heightOnly = editor.setSize(null, Integer.valueOf(250));
            assertTrue(name + ": " + heightOnly.message, heightOnly.done); //$NON-NLS-1$
            FormLayout again = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
            assertEquals(name, Integer.valueOf(640), again.width);
            assertEquals(name, Integer.valueOf(250), again.height);
        }
    }

    /** A layout cut short after the root panel answers no size, and the writer refuses it too. */
    @Test
    public void aLayoutWithoutTheNumbersAnswersNoSize() throws Exception
    {
        ListNode tree = BraceTree.parse(ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form")); //$NON-NLS-1$
        ListNode body = (ListNode)tree.get(1);
        while (body.size() > 3)
        {
            body.items().remove(body.size() - 1);
        }
        FormLayout layout = LayoutReader.read(tree, "Catalog"); //$NON-NLS-1$
        assertNull(layout.width);
        assertNull(layout.height);
        LayoutEditor editor = LayoutEditor.over(tree, "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(!editor.setSize(Integer.valueOf(100), Integer.valueOf(100)).done);
    }
}
