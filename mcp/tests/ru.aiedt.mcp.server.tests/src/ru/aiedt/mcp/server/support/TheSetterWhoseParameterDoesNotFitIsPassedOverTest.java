/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

/**
 * What happens when a form carries one property name twice with different parameter types.
 * <p>
 * Every base property is written as text and the setter is found by name, so a form that exposes the
 * property as a model type beside the text one offers a setter the value cannot be coerced to - and
 * the model type does not accept the text: an enum constant that EDT does not have, a number where
 * the value is a word. Such a setter used to end the search, and the property came back as not
 * applied while the form had a setter for it. A form carries the same property more than once - as
 * the model type and as text - so the search continues to the next candidate.
 * </p>
 */
public class TheSetterWhoseParameterDoesNotFitIsPassedOverTest
{
    /** The model type of the form's alignment, as an enum the base value is not a constant of. */
    public enum AlignKind
    {
        /** Not the value {@link FormBaseSetup} applies - which is what makes this the hard case. */
        ItemsLeft,

        ItemsRight
    }

    /**
     * The same property as a model enum and as text, which is what a real form offers. The enum
     * setter is declared first, so it is the one a search landing on the model type meets.
     */
    public static final class OverloadedFormDouble
    {
        private boolean enumSetterCalled;

        private String byText;

        public void setChildrenAlign(AlignKind value)
        {
            enumSetterCalled = true;
        }

        public void setChildrenAlign(String value)
        {
            byText = value;
        }
    }

    /** The regression: the text reaches the setter that takes text. */
    @Test
    public void theCandidateWhoseTypeDoesNotFitIsPassedOver()
    {
        OverloadedFormDouble form = new OverloadedFormDouble();

        assertEquals("the form has a setter for the property, so it has to count as applied", //$NON-NLS-1$
            1, FormBaseSetup.applyDefaults(form));
        assertEquals("ItemsCenter", form.byText); //$NON-NLS-1$
        assertFalse("no enum constant carries the value", form.enumSetterCalled); //$NON-NLS-1$
    }

    /** The candidates are tried in the order given, and the first one that takes the value wins. */
    @Test
    public void theFirstCandidateThatTakesTheValueIsUsed() throws Exception
    {
        OverloadedFormDouble form = new OverloadedFormDouble();
        Method modelType = OverloadedFormDouble.class.getMethod("setChildrenAlign", AlignKind.class); //$NON-NLS-1$
        Method textual = OverloadedFormDouble.class.getMethod("setChildrenAlign", String.class); //$NON-NLS-1$

        assertTrue(FormBaseSetup.applyFirstThatTakes(form, List.of(modelType, textual), "ItemsCenter")); //$NON-NLS-1$
        assertEquals("ItemsCenter", form.byText); //$NON-NLS-1$
        assertFalse(form.enumSetterCalled);
    }

    /** A candidate that does take the value is not passed over for a later one. */
    @Test
    public void aCandidateThatTakesTheValueEndsTheSearch() throws Exception
    {
        OverloadedFormDouble form = new OverloadedFormDouble();
        Method modelType = OverloadedFormDouble.class.getMethod("setChildrenAlign", AlignKind.class); //$NON-NLS-1$
        Method textual = OverloadedFormDouble.class.getMethod("setChildrenAlign", String.class); //$NON-NLS-1$

        assertTrue(FormBaseSetup.applyFirstThatTakes(form, List.of(textual, modelType), "ItemsCenter")); //$NON-NLS-1$
        assertEquals("ItemsCenter", form.byText); //$NON-NLS-1$
    }

    /** A property whose setters all refuse the value is reported as not applied, not as applied. */
    @Test
    public void aPropertyNoSetterTakesIsNotCounted() throws Exception
    {
        OverloadedFormDouble form = new OverloadedFormDouble();
        Method modelType = OverloadedFormDouble.class.getMethod("setChildrenAlign", AlignKind.class); //$NON-NLS-1$

        assertFalse(FormBaseSetup.applyFirstThatTakes(form, List.of(modelType), "ItemsCenter")); //$NON-NLS-1$
    }

    /** Nothing to try is nothing applied. */
    @Test
    public void noCandidateIsNotApplied()
    {
        assertFalse(FormBaseSetup.applyFirstThatTakes(new OverloadedFormDouble(), List.of(), "ItemsCenter")); //$NON-NLS-1$
    }
}
