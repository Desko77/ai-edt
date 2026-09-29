/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormChildrenGroup;
import com._1c.g5.v8.dt.form.model.FormCommandInterface;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.ItemHorizontalAlignment;

/**
 * The base properties a form created without the generator receives, on the real form model.
 * <p>
 * The model factory builds a form without a project around it, and such a form carries the model
 * defaults of its root features: the booleans read {@code false}, so a client would see a disabled
 * form with no title and no close button. A form the EDT generator builds instead carries
 * {@code saveWindowSettings}, {@code autoTitle}, {@code autoUrl}, {@code group=Vertical},
 * {@code autoFillCheck}, {@code allowFormCustomize}, {@code enabled}, {@code showTitle},
 * {@code showCloseButton}, an {@code autoCommandBar} aligned left and auto-filling, and an empty
 * {@code commandInterface} with both panels. {@link FormBaseSetup#applyDefaults} has to bring the
 * factory form to that state - nine scalar properties and two containers, eleven in all.
 * </p>
 */
public class TheGeneratorBasePropertiesLandOnAFactoryFormTest
{
    /** Every property of the generator's root lands on the factory form, and the count names it. */
    @Test
    public void everyGeneratorBasePropertyLands()
    {
        Form form = FormFactory.eINSTANCE.createForm();

        int applied = FormBaseSetup.applyDefaults(form);

        assertEquals("nine scalar properties plus the two containers", 11, applied); //$NON-NLS-1$
        assertTrue("enabled stays false when the property is skipped - the client sees a disabled form", //$NON-NLS-1$
            form.isEnabled());
        assertTrue(form.isAutoTitle());
        assertTrue(form.isAutoUrl());
        assertTrue(form.isSaveWindowSettings());
        assertTrue(form.isAutoFillCheck());
        assertTrue(form.isAllowFormCustomize());
        assertTrue(form.isShowTitle());
        assertTrue(form.isShowCloseButton());
        assertEquals(FormChildrenGroup.VERTICAL, form.getGroup());
        AutoCommandBar bar = form.getAutoCommandBar();
        assertNotNull("the generator's root carries a command bar container", bar); //$NON-NLS-1$
        assertTrue(bar.isAutoFill());
        assertEquals(ItemHorizontalAlignment.LEFT, bar.getHorizontalAlign());
        FormCommandInterface commandInterface = form.getCommandInterface();
        assertNotNull("the generator's root carries a command interface", commandInterface); //$NON-NLS-1$
        assertNotNull(commandInterface.getNavigationPanel());
        assertNotNull(commandInterface.getCommandBar());
    }

    /** A second pass reports the same count and builds no second container. */
    @Test
    public void aSecondPassChangesNothing()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        int first = FormBaseSetup.applyDefaults(form);
        AutoCommandBar bar = form.getAutoCommandBar();
        FormCommandInterface commandInterface = form.getCommandInterface();

        int second = FormBaseSetup.applyDefaults(form);

        assertEquals(first, second);
        assertSame(bar, form.getAutoCommandBar());
        assertSame(commandInterface, form.getCommandInterface());
    }
}
