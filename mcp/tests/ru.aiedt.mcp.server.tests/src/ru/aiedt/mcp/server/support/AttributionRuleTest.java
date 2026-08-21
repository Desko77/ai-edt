/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Guards the rule that decides which side changed an object.
 * <p>
 * Every case here was produced on a stand and read back out of the files, not reasoned about. The
 * two that matter most were both wrong before this rule existed, and wrong in the reassuring
 * direction: a customisation the delivery had deleted was attributed to the vendor, with
 * GET_FROM_OTHER recommended - following which deletes the customisation.
 * </p>
 */
public class AttributionRuleTest
{
    private static final boolean MAIN = true;

    private static final boolean OTHER = false;

    private static final boolean HAD_ANCESTOR = true;

    private static final boolean NEW_OBJECT = false;

    private static final boolean SURVIVOR_CHANGED = true;

    private static final boolean SURVIVOR_UNTOUCHED = false;

    @Test
    public void weChangedItAndTheDeliveryDeletedIt()
    {
        // Measured: this came back VENDOR, and the environment recommended taking the delivery's
        // version - which is the deletion. The object was our own work.
        assertEquals("a customisation the delivery removed is a conflict, not a vendor change",
            AttributionRule.BOTH,
            AttributionRule.forOneSided(MAIN, HAD_ANCESTOR, SURVIVOR_CHANGED));
    }

    @Test
    public void weDeletedItAndTheDeliveryChangedIt()
    {
        // The mirror case, measured the same way: it came back OURS.
        assertEquals("an object we removed and the delivery reworked is a conflict too",
            AttributionRule.BOTH,
            AttributionRule.forOneSided(OTHER, HAD_ANCESTOR, SURVIVOR_CHANGED));
    }

    @Test
    public void aPlainDeletionByTheDeliveryIsTheDeliveryChange()
    {
        assertEquals("nothing of ours was touched, so nobody has to review it",
            AttributionRule.VENDOR,
            AttributionRule.forOneSided(MAIN, HAD_ANCESTOR, SURVIVOR_UNTOUCHED));
    }

    @Test
    public void aPlainDeletionByUsIsOurChange()
    {
        assertEquals(AttributionRule.OURS,
            AttributionRule.forOneSided(OTHER, HAD_ANCESTOR, SURVIVOR_UNTOUCHED));
    }

    @Test
    public void anObjectTheDeliveryBroughtIsTheirs()
    {
        // No ancestor: there is nothing it could conflict with.
        assertEquals(AttributionRule.VENDOR,
            AttributionRule.forOneSided(OTHER, NEW_OBJECT, SURVIVOR_UNTOUCHED));
    }

    @Test
    public void anObjectWeAddedIsOurs()
    {
        assertEquals(AttributionRule.OURS,
            AttributionRule.forOneSided(MAIN, NEW_OBJECT, SURVIVOR_UNTOUCHED));
    }

    @Test
    public void anAdditionIsNeverAConflictEvenWhenItLooksChanged()
    {
        // Without an ancestor the survivor is "changed" against nothing. Reading that as a
        // conflict would put every object either side added into the queue a person has to work
        // through by hand, which is the queue that has to stay short to be used at all.
        assertEquals(AttributionRule.OURS,
            AttributionRule.forOneSided(MAIN, NEW_OBJECT, SURVIVOR_CHANGED));
        assertEquals(AttributionRule.VENDOR,
            AttributionRule.forOneSided(OTHER, NEW_OBJECT, SURVIVOR_CHANGED));
    }

    @Test
    public void bothSidesChangingTheSameObjectIsAConflict()
    {
        assertEquals(AttributionRule.BOTH, AttributionRule.forTwoSided(true, false, false));
        assertEquals(AttributionRule.BOTH, AttributionRule.forTwoSided(false, true, true));
    }

    @Test
    public void oneSideChangingItAttributesToThatSide()
    {
        assertEquals(AttributionRule.OURS, AttributionRule.forTwoSided(false, true, false));
        assertEquals(AttributionRule.VENDOR, AttributionRule.forTwoSided(false, false, true));
    }

    @Test
    public void nothingDifferingIsNotAttributedToAnybody()
    {
        assertEquals("a node that differs on no side is not somebody's change, and naming a side "
            + "would put an object into a review queue for no reason",
            AttributionRule.UNKNOWN, AttributionRule.forTwoSided(false, false, false));
    }
}
