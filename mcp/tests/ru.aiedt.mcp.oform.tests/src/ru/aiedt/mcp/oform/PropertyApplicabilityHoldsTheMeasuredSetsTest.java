/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.oform.layout.PropertyApplicability;

/**
 * The applicability sets the editor and the reader work from live in one place, and the classes
 * that once held copies of their own - the events list of the editor, the styles of
 * {@code ElementStyle}, the caption paths of the reader - read them here. The queries answer
 * fresh sets, so no caller can add to the measured corpus through them, and the numbers are the
 * ones the corpus was measured on.
 */
public class PropertyApplicabilityHoldsTheMeasuredSetsTest
{
    /** The events a kind was measured on are where the kind's own record keeps them. */
    @Test
    public void theEventsSitWhereTheKindKeepsThem()
    {
        assertEquals(Integer.valueOf(4), PropertyApplicability.eventsAt("Table")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(17), PropertyApplicability.eventsAt("TableField")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), PropertyApplicability.eventsAt("Button")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), PropertyApplicability.eventsAt("Image")); //$NON-NLS-1$
        assertNull(PropertyApplicability.eventsAt("Separator")); //$NON-NLS-1$
        assertTrue(PropertyApplicability.eventKinds().contains("Table")); //$NON-NLS-1$
        assertFalse(PropertyApplicability.eventKinds().contains("Separator")); //$NON-NLS-1$
    }

    /** The caption of a kind sits at the path the corpus measured, and only there. */
    @Test
    public void theCaptionPathsAreTheMeasuredOnes()
    {
        assertNotNull(PropertyApplicability.captionPath("Label")); //$NON-NLS-1$
        assertNull(PropertyApplicability.captionPath("Table")); //$NON-NLS-1$
        assertEquals(Set.of("Label", "Button", "Group", "CheckBox", "RadioBtn"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            PropertyApplicability.captionKinds());
    }

    /** The style, read-only, alignment and format sets answer as sets, fresh on every query. */
    @Test
    public void thePropertySetsAnswerFresh()
    {
        assertTrue(PropertyApplicability.styleKinds().contains("Separator")); //$NON-NLS-1$
        assertTrue(PropertyApplicability.readOnlyKinds().contains("Field")); //$NON-NLS-1$
        assertTrue(PropertyApplicability.alignmentKinds().contains("Label")); //$NON-NLS-1$
        assertTrue(PropertyApplicability.formatKinds().contains("Field")); //$NON-NLS-1$

        Set<String> styles = PropertyApplicability.styleKinds();
        int before = styles.size();
        styles.clear();
        assertEquals("a query answers a fresh set", before, PropertyApplicability.styleKinds().size()); //$NON-NLS-1$
    }
}
