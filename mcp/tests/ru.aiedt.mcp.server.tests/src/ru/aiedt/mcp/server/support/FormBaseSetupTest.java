/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers how a newly created form is given its base properties.
 * <p>
 * The setters live on an EMF form class the plugin never compiles against, so they are found by
 * name at runtime - which means the interesting behaviour is what happens when a name is not there.
 * Skipping quietly is the deliberate choice: EDT renames and drops form features between releases,
 * and a form that comes out with ten of eleven defaults is far better than a create that fails. The
 * doubles here stand in for the form exactly as the reflection sees it.
 * </p>
 */
public class FormBaseSetupTest
{
    /** Exposes the property kinds the coercion has to handle: enum-ish text, plain text, boolean. */
    public static final class FormDouble
    {
        private String childrenAlign;
        private String itemsGroup;
        private boolean enableContentChange;
        private Boolean autoCommandBar;

        public void setChildrenAlign(String value)
        {
            childrenAlign = value;
        }

        public void setItemsGroup(String value)
        {
            itemsGroup = value;
        }

        public void setEnableContentChange(boolean value)
        {
            enableContentChange = value;
        }

        public void setAutoCommandBar(Boolean value)
        {
            autoCommandBar = value;
        }
    }

    /** A form class from a platform release that renamed everything this helper knows. */
    public static final class ForeignFormDouble
    {
        public void setSomethingElse(String value)
        {
            // deliberately not one of the base properties
        }
    }

    @Test
    public void thePropertiesTheFormOffersAreApplied()
    {
        FormDouble form = new FormDouble();

        int applied = FormBaseSetup.applyDefaults(form);

        assertEquals("all four setters this double exposes should have been used", 4, applied); //$NON-NLS-1$
        assertEquals("ItemsCenter", form.childrenAlign); //$NON-NLS-1$
        assertEquals("Vertical", form.itemsGroup); //$NON-NLS-1$
        assertTrue("a boolean property has to be coerced from its text form", //$NON-NLS-1$
            form.enableContentChange);
        assertEquals(Boolean.TRUE, form.autoCommandBar);
    }

    @Test
    public void aFormThatOffersNothingIsLeftAloneRatherThanFailing()
    {
        // The forward-compatibility case: a newer EDT with different feature names must not turn
        // form creation into an error.
        assertEquals(0, FormBaseSetup.applyDefaults(new ForeignFormDouble()));
        assertEquals(0, FormBaseSetup.applyDefaults(new Object()));
    }

    @Test
    public void thereIsNothingToApplyToNothing()
    {
        assertEquals(0, FormBaseSetup.applyDefaults(null));
    }

    @Test
    public void applyingTwiceIsHarmless()
    {
        // Values are constants, so a second pass has to reach the same state - the helper is called
        // again whenever a form is regenerated.
        FormDouble form = new FormDouble();

        assertEquals(FormBaseSetup.applyDefaults(form), FormBaseSetup.applyDefaults(form));
        assertEquals("ItemsCenter", form.childrenAlign); //$NON-NLS-1$
    }
}
