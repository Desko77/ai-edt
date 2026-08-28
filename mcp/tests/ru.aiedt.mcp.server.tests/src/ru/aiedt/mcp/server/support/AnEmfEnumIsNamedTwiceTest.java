/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.ItemHorizontalAlignment;
import com._1c.g5.v8.dt.form.model.ManagedFormDecorationType;

/**
 * An EMF enum answers to two names, and only one of them is the Java constant.
 * <p>
 * The generated enum overrides {@code toString()} to return its literal - the name the platform and
 * the form file use - while the Java constant stays SCREAMING_SNAKE. Code that compared a hardcoded
 * "LABEL" against {@code toString()} matched nothing, so {@code setType} never ran and the
 * decoration kept the model default.
 * </p>
 * <p>
 * Measured on the stand 2026-08-28: a decoration created as a Label came out with
 * {@code LabelDecorationExtInfo} and no type element written at all, and EDT reported MAJOR
 * "Illegal extension type for decoration type 'Picture'" - it read the type as Picture, which is
 * what the model defaults to. The Picture branch carried the same flaw and never showed it, because
 * Picture is the default it was failing into.
 * </p>
 */
public class AnEmfEnumIsNamedTwiceTest
{
    @Test
    public void theLiteralAndTheJavaConstantAreNotTheSameString()
    {
        // This is the whole defect in one line. If these were equal there would have been no bug.
        assertEquals("Label", String.valueOf(ManagedFormDecorationType.LABEL)); //$NON-NLS-1$
        assertEquals("LABEL", ManagedFormDecorationType.LABEL.name()); //$NON-NLS-1$
    }

    @Test
    public void theJavaSpellingFindsTheConstant()
    {
        assertEquals(ManagedFormDecorationType.LABEL,
            BmFormHelper.enumConstantNamedForTest(ManagedFormDecorationType.class, "LABEL")); //$NON-NLS-1$
        assertEquals(ManagedFormDecorationType.PICTURE,
            BmFormHelper.enumConstantNamedForTest(ManagedFormDecorationType.class, "PICTURE")); //$NON-NLS-1$
    }

    @Test
    public void theLiteralFindsItToo()
    {
        assertEquals(ManagedFormDecorationType.LABEL,
            BmFormHelper.enumConstantNamedForTest(ManagedFormDecorationType.class, "Label")); //$NON-NLS-1$
        assertEquals(ManagedFormDecorationType.PICTURE,
            BmFormHelper.enumConstantNamedForTest(ManagedFormDecorationType.class, "picture")); //$NON-NLS-1$
    }

    @Test
    public void theAlignmentEnumBehavesTheSameWay()
    {
        assertNotNull(BmFormHelper.enumConstantNamedForTest(ItemHorizontalAlignment.class, "LEFT")); //$NON-NLS-1$
        assertEquals(BmFormHelper.enumConstantNamedForTest(ItemHorizontalAlignment.class, "LEFT"), //$NON-NLS-1$
            BmFormHelper.enumConstantNamedForTest(ItemHorizontalAlignment.class, "Left")); //$NON-NLS-1$
    }

    @Test
    public void aNameTheEnumDoesNotHaveComesBackEmptyRatherThanWrong()
    {
        assertNull(BmFormHelper.enumConstantNamedForTest(ManagedFormDecorationType.class, //$NON-NLS-1$
            "Table")); //$NON-NLS-1$
        assertNull(BmFormHelper.enumConstantNamedForTest(ManagedFormDecorationType.class, null));
    }
}
