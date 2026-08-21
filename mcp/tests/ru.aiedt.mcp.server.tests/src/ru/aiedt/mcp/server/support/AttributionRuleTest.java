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

    /**
     * Unreadable is not unchanged, and answering VENDOR for it deletes customisations.
     * <p>
     * The path: an object present on our side, existing in the ancestor, whose comparison flags the
     * environment will not produce. Answering "unchanged" names it VENDOR, and VENDOR keeps it out
     * of the list an update is held back by AND out of what blocks an unchanged-configuration
     * update - so the object is removed and appears in neither the protection list nor the
     * refusals. BOTH costs a decision by hand; the alternative costs the object.
     * </p>
     */
    @Test
    public void anUnreadableSurvivorIsAConflictRatherThanTheVendors()
    {
        assertEquals(AttributionRule.BOTH, AttributionRule.forOneSided(true, true, null));
        assertEquals(AttributionRule.BOTH, AttributionRule.forOneSided(false, true, null));
    }

    @Test
    public void aReadableUnchangedSurvivorStillAnswersBySide()
    {
        // The fix must not swallow the ordinary answer: when the flags DO say "unchanged", a
        // one-sided object is exactly what its side makes it.
        assertEquals(AttributionRule.VENDOR,
            AttributionRule.forOneSided(true, true, Boolean.FALSE));
        assertEquals(AttributionRule.OURS,
            AttributionRule.forOneSided(false, true, Boolean.FALSE));
    }

    @Test
    public void withoutAnAncestorNothingIsAskedOfTheSurvivor()
    {
        // No ancestor means nothing to conflict with, so the third argument decides nothing - and
        // a null there must not reach the conflict branch and turn a plainly new object into one.
        assertEquals(AttributionRule.OURS, AttributionRule.forOneSided(true, false, null));
        assertEquals(AttributionRule.VENDOR, AttributionRule.forOneSided(false, false, null));
    }
}
