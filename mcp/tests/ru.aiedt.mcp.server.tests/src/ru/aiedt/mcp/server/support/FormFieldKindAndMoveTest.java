/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Two pieces of form editing that can be reasoned about without a workspace: how a field kind
 * spelled by a caller is matched against the model enum, and which moves are refused outright.
 * <p>
 * The field-kind part exists because the plugin used to answer two different questions with two
 * different matchers. The ext-info was chosen on a substring, so {@code Label} produced a label's
 * ext-info; the type was chosen on the enum literal, so the same {@code Label} matched nothing and
 * no type was written at all. The result serialized as a field the model reads as an input field
 * and the platform reads as a label - the kind of disagreement that only shows up on screen.
 * </p>
 */
public class FormFieldKindAndMoveTest
{
    /** Stands in for a form item exactly as the reflective container walk sees it. */
    public static final class ItemDouble
    {
        private final Object container;

        public ItemDouble(Object container)
        {
            this.container = container;
        }

        public Object eContainer()
        {
            return container;
        }
    }

    /** A model object that answers no container question at all. */
    public static final class OpaqueDouble
    {
        // deliberately without eContainer()
    }

    @Test
    public void theShortSpellingOfAFieldKindMeansTheSameAsTheLiteral()
    {
        assertEquals(BmFormHelper.normalizeFieldKind("LabelField"), //$NON-NLS-1$
            BmFormHelper.normalizeFieldKind("Label")); //$NON-NLS-1$
        assertEquals(BmFormHelper.normalizeFieldKind("LabelField"), //$NON-NLS-1$
            BmFormHelper.normalizeFieldKind("LABEL_FIELD")); //$NON-NLS-1$
        assertEquals(BmFormHelper.normalizeFieldKind("InputField"), //$NON-NLS-1$
            BmFormHelper.normalizeFieldKind("input")); //$NON-NLS-1$
    }

    @Test
    public void differentFieldKindsStayDifferent()
    {
        assertFalse(BmFormHelper.normalizeFieldKind("LabelField") //$NON-NLS-1$
            .equals(BmFormHelper.normalizeFieldKind("InputField"))); //$NON-NLS-1$
        assertFalse(BmFormHelper.normalizeFieldKind("CheckBoxField") //$NON-NLS-1$
            .equals(BmFormHelper.normalizeFieldKind("RadioButtonField"))); //$NON-NLS-1$
    }

    @Test
    public void theWordFieldOnItsOwnIsNotStrippedIntoNothing()
    {
        // Stripping the suffix off "Field" would leave an empty string that matches everything.
        assertFalse(BmFormHelper.normalizeFieldKind("Field").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anItemIsInsideItsOwnContainer()
    {
        Object root = new OpaqueDouble();
        ItemDouble group = new ItemDouble(root);
        ItemDouble field = new ItemDouble(group);

        assertTrue(BmFormHelper.isInsideOf(field, group));
        assertTrue("containment is transitive, so the root counts too", //$NON-NLS-1$
            BmFormHelper.isInsideOf(field, root));
        assertTrue("an item is trivially inside itself - that move is refused as well", //$NON-NLS-1$
            BmFormHelper.isInsideOf(field, field));
    }

    @Test
    public void anItemIsNotInsideAnUnrelatedContainer()
    {
        Object root = new OpaqueDouble();
        ItemDouble left = new ItemDouble(root);
        ItemDouble right = new ItemDouble(root);

        assertFalse("siblings do not contain each other, so this move is allowed", //$NON-NLS-1$
            BmFormHelper.isInsideOf(left, right));
        assertFalse("a container is not inside the item it holds", //$NON-NLS-1$
            BmFormHelper.isInsideOf(root, left));
    }

    @Test
    public void anItemTravellingForwardsLandsOnePlaceEarlierThanTheSlotItAimedAt()
    {
        // A list move does not shorten the list first, so everything between the two positions has
        // already shifted back by one by the time the item arrives. Getting this wrong puts the
        // item one place past where the caller asked for.
        assertEquals(2, BmFormHelper.reorderTargetIndex(0, 3));
        assertEquals(0, BmFormHelper.reorderTargetIndex(0, 1));
    }

    @Test
    public void anItemTravellingBackwardsLandsExactlyOnTheSlotItAimedAt()
    {
        // Nothing between the two positions moves until the item is placed, so the index stands.
        assertEquals(1, BmFormHelper.reorderTargetIndex(4, 1));
        assertEquals(0, BmFormHelper.reorderTargetIndex(1, 0));
    }

    @Test
    public void anItemAlreadyInPlaceIsNotMovedAtAll()
    {
        assertEquals(3, BmFormHelper.reorderTargetIndex(3, 3));
    }

    @Test
    public void anObjectThatAnswersNoContainerQuestionEndsTheWalk()
    {
        // The walk must terminate on anything, including a model object from a newer EDT that
        // renamed the accessor - otherwise a refusal check turns into a hang.
        assertFalse(BmFormHelper.isInsideOf(new OpaqueDouble(), new OpaqueDouble()));
        assertFalse(BmFormHelper.isInsideOf(new ItemDouble(null), null));
    }
}
