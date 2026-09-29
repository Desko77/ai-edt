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
 * doubles here stand in for the form exactly as the reflection sees it: scalar setters, a command
 * bar container the form already holds, and nothing else.
 * </p>
 */
public class FormBaseSetupTest
{
    /** The command bar container a form root exposes, as the reflection sees it. */
    public static final class BarDouble
    {
        private String horizontalAlign;
        private boolean autoFill;

        public void setHorizontalAlign(String value)
        {
            horizontalAlign = value;
        }

        public void setAutoFill(boolean value)
        {
            autoFill = value;
        }
    }

    /** Exposes the property kinds the coercion has to handle: enum-ish text, plain boolean, a container. */
    public static final class FormDouble
    {
        private boolean enabled;
        private String group;
        private boolean saveWindowSettings;
        private final BarDouble bar = new BarDouble();

        public void setEnabled(boolean value)
        {
            enabled = value;
        }

        public void setGroup(String value)
        {
            group = value;
        }

        public void setSaveWindowSettings(boolean value)
        {
            saveWindowSettings = value;
        }

        public BarDouble getAutoCommandBar()
        {
            return bar;
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

        assertEquals("three scalar setters and the command bar container should have been used", //$NON-NLS-1$
            4, applied);
        assertTrue("a boolean property has to be coerced from its text form", form.enabled);
        assertEquals("VERTICAL", form.group); //$NON-NLS-1$
        assertTrue(form.saveWindowSettings);
        assertEquals("LEFT", form.bar.horizontalAlign); //$NON-NLS-1$
        assertTrue(form.bar.autoFill);
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
        assertEquals("VERTICAL", form.group); //$NON-NLS-1$
    }
}
