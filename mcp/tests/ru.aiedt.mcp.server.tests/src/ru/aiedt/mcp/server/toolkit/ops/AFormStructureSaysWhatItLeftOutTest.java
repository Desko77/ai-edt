/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;

/**
 * The form structure says what it left out: a cap that dropped a node, a subtree name written in
 * another case, a dynamic list that could not be read.
 */
public class AFormStructureSaysWhatItLeftOutTest
{
    /**
     * @param names the buttons' names
     * @return a form holding one button per name
     */
    private static Form formWithButtons(String... names)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        for (String name : names)
        {
            Button button = FormFactory.eINSTANCE.createButton();
            button.setName(name);
            form.getItems().add(button);
        }
        return form;
    }

    /** A form whose nodes number exactly the cap is emitted whole and not called truncated. */
    @Test
    public void aFormThatFitsTheCapExactlyIsNotTruncated()
    {
        AtomicBoolean dropped = new AtomicBoolean();
        AtomicInteger counter = new AtomicInteger();

        new GetFormStructureTool().walk(formWithButtons("One", "Two"), 0, 0, 3, counter, dropped); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(3, counter.get());
        assertFalse("nothing was left out", dropped.get()); //$NON-NLS-1$
    }

    /** A form with more nodes than the cap is called truncated. */
    @Test
    public void aFormOverTheCapIsTruncated()
    {
        AtomicBoolean dropped = new AtomicBoolean();

        new GetFormStructureTool().walk(formWithButtons("One", "Two"), 0, 0, 2, new AtomicInteger(), dropped); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a node was left out", dropped.get()); //$NON-NLS-1$
    }

    /** A subtree name written in another case finds the item. */
    @Test
    public void aSubtreeNameInAnotherCaseFindsTheItem()
    {
        Form form = formWithButtons("Печать"); //$NON-NLS-1$

        assertNotNull(new GetFormStructureTool().findItemByName(form, "печать")); //$NON-NLS-1$
    }

    /** A dynamic list attribute whose reading fails is named, not passed over. */
    @Test
    public void aListThatCouldNotBeReadIsNamed()
    {
        List<String> unread = new ArrayList<>();

        GetFormStructureTool.collectDynamicLists(new FakeForm(), unread);

        assertEquals(unread.toString(), 1, unread.size());
        assertTrue(unread.get(0), unread.get(0).startsWith("Broken")); //$NON-NLS-1$
        assertTrue(unread.get(0), unread.get(0).contains("model unloaded")); //$NON-NLS-1$
    }

    /** A form whose attributes carry no extension info has no lists and nothing unread. */
    @Test
    public void attributesWithoutExtensionInfoAreNotFailures()
    {
        List<String> unread = new ArrayList<>();

        GetFormStructureTool.collectDynamicLists(new PlainForm(), unread);

        assertTrue(unread.toString(), unread.isEmpty());
    }

    /** A form whose one attribute cannot be read. */
    public static final class FakeForm
    {
        /**
         * @return the attributes
         */
        public List<Object> getAttributes()
        {
            return Arrays.asList(new BrokenAttribute());
        }
    }

    /** A form whose attribute has no extension info getter. */
    public static final class PlainForm
    {
        /**
         * @return the attributes
         */
        public List<Object> getAttributes()
        {
            return Arrays.asList(new PlainAttribute());
        }
    }

    /** An attribute whose extension info cannot be read. */
    public static final class BrokenAttribute
    {
        /**
         * @return the name
         */
        public String getName()
        {
            return "Broken"; //$NON-NLS-1$
        }

        /**
         * @return never
         */
        public Object getExtInfo()
        {
            throw new IllegalStateException("model unloaded"); //$NON-NLS-1$
        }
    }

    /** An attribute with no extension info getter. */
    public static final class PlainAttribute
    {
        /**
         * @return the name
         */
        public String getName()
        {
            return "Plain"; //$NON-NLS-1$
        }
    }
}
