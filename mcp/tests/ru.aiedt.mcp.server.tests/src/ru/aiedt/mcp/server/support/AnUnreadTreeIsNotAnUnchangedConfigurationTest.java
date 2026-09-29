/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the line between "the counts are zero" and "nothing moved".
 * <p>
 * A comparison that finished but could not be read - no session, or a session with no tree - leaves
 * every count at zero. Zero is what an untouched configuration looks like too, and that is the
 * whole hazard: UPDATE_UNCHANGED used to read the zeros as "nothing was reworked here" and take the
 * delivery whole, and the protection modes ran on lists that were empty because nothing was read,
 * not because nothing needed protecting.
 * </p>
 */
public class AnUnreadTreeIsNotAnUnchangedConfigurationTest
{
    private static BmComparisonHelper.Outcome finishedButUnread()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.completed = true;
        outcome.cannotTell = "the comparison finished but produced no session to read"; //$NON-NLS-1$
        return outcome;
    }

    @Test
    public void anUnreadAnswerIsNotAnUnchangedConfiguration()
    {
        // Three sides, finished, every count zero - the exact shape of a configuration with no
        // customisations, and also the exact shape of an answer nobody read. The reason has to
        // separate them, because only one of them may take the delivery whole.
        String reason = BmComparisonHelper.whyNotUnchanged(finishedButUnread());
        assertNotNull("zeros from an unread tree are not evidence of anything", reason); //$NON-NLS-1$
        assertTrue(reason, reason.contains("could not be read")); //$NON-NLS-1$
    }

    @Test
    public void aReadAnswerIsStillJudgedOnItsCounts()
    {
        // The new check must not refuse what the old one allowed: an ordinary read answer with
        // zero counts is the legitimate fast path, and stays one.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.completed = true;
        assertTrue(BmComparisonHelper.whyNotUnchanged(outcome) == null);
    }

    @Test
    public void aSessionWithNoTreeRefusesInsteadOfAnsweringFromNothing()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.readTree(FakeComparison.over(null).session(), outcome,
            new BmComparisonHelper.Page());

        assertNotNull(outcome.cannotTell);
        assertTrue(String.valueOf(outcome.cannotTell),
            outcome.cannotTell.contains("no tree to read")); //$NON-NLS-1$
        assertEquals("nothing was walked, so nothing was counted", 0, outcome.nodes); //$NON-NLS-1$
    }

    @Test
    public void protectionWithoutASessionIsARefusalNotASilentSkip()
    {
        // The mode's gate refuses on protectionRefused. A protection that never ran used to leave
        // that list empty - which the gate reads as protection that succeeded - and said so only
        // in a note nobody's decision reads.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.protectOurs(null, outcome);

        assertEquals(1, outcome.protectionRefused.size());
        assertTrue(outcome.protectionRefused.get(0),
            outcome.protectionRefused.get(0).contains("no session")); //$NON-NLS-1$
    }

    @Test
    public void aTreeThatReadsStillCounts()
    {
        // The refusal is about not reading, not about reading: an ordinary tree walks and counts.
        FakeComparison fake = FakeComparison.over(FakeComparison.plain(1L, null).child(
            FakeComparison.top(2L, "Catalog.Alpha", FakeComparison.changedOnBothSides())));
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();

        BmComparisonHelper.readTree(fake.session(), outcome, page);

        assertTrue(outcome.cannotTell == null);
        assertTrue(outcome.nodes > 0);
        assertEquals(1, outcome.objectsChangedByBoth);
    }
}
