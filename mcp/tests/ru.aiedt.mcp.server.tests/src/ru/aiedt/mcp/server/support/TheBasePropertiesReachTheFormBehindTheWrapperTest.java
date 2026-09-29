/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Where the base properties land when a metadata wrapper is handed in.
 * <p>
 * The properties belong to the {@code form.model.Form}, and a configuration holds that form under
 * an {@code mdclass} wrapper that exposes none of the setters. {@link FormBaseSetup#applyDefaults}
 * follows the wrapper to the form behind it and sets the properties there, and the count it answers
 * is the number set on that form.
 * </p>
 */
public class TheBasePropertiesReachTheFormBehindTheWrapperTest
{
    /** The form as the reflection sees it: scalar base properties, no containers. */
    public static final class FormDouble
    {
        private boolean enabled;
        private String group;
        private boolean showTitle;
        private boolean showCloseButton;

        public void setEnabled(boolean value)
        {
            enabled = value;
        }

        public void setGroup(String value)
        {
            group = value;
        }

        public void setShowTitle(boolean value)
        {
            showTitle = value;
        }

        public void setShowCloseButton(boolean value)
        {
            showCloseButton = value;
        }
    }

    /** The mdclass shape: it holds the form and carries the properties itself in no way. */
    public static final class WrapperDouble
    {
        private final FormDouble form = new FormDouble();

        public Object getFormAttachedForm()
        {
            return form;
        }
    }

    /**
     * A form root already: it answers {@code getItems()}, and the accessor probe must not follow it
     * anywhere - on a real form those names may answer an object that is not this form.
     */
    public static final class FormRootDouble
    {
        private final Object other = new Object();

        private boolean enabled;

        public Object getFormAttachedForm()
        {
            return other;
        }

        public List<String> getItems()
        {
            return List.of();
        }

        public void setEnabled(boolean value)
        {
            enabled = value;
        }
    }

    /** A wrapper whose accessors are named otherwise on this platform release - nothing to follow. */
    public static final class OpaqueWrapperDouble
    {
        private final FormDouble form = new FormDouble();

        public Object getSomethingNobodyProbes()
        {
            return form;
        }
    }

    /** The regression: the properties reach the form the wrapper holds. */
    @Test
    public void aWrapperIsFollowedToTheFormItHolds()
    {
        WrapperDouble wrapper = new WrapperDouble();

        assertEquals("all four setters of the form behind the wrapper have to be used", //$NON-NLS-1$
            4, FormBaseSetup.applyDefaults(wrapper));
        assertTrue(wrapper.form.enabled);
        assertEquals("VERTICAL", wrapper.form.group); //$NON-NLS-1$
        assertTrue(wrapper.form.showTitle);
        assertTrue(wrapper.form.showCloseButton);
    }

    /** A form root is left alone rather than followed through its own accessors. */
    @Test
    public void aFormRootIsNotLookedBehind()
    {
        FormRootDouble form = new FormRootDouble();

        assertEquals(1, FormBaseSetup.applyDefaults(form));
        assertTrue(form.enabled);
    }

    /** A wrapper whose form is not reachable still answers zero instead of failing. */
    @Test
    public void anUnreachableFormIsNotAFailure()
    {
        assertEquals(0, FormBaseSetup.applyDefaults(new OpaqueWrapperDouble()));
    }
}
