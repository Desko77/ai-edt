/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.model.MergeRule;

/**
 * Holds what happens to the decisions a comparison could not record.
 * <p>
 * The defect this guards had two halves. One unusable decision - a name the comparison does not
 * hold, a word that is not a rule, CUSTOM_MERGE with no editor - stopped the marking loop where it
 * stood, so every decision after it went unmarked. And the merge gate asked only whether SOMETHING
 * had been decided, so the merge ran on whatever had been recorded before the loop stopped: the
 * caller read {@code merged=true} as everything they asked for having been done, while the tail of
 * their list was never even attempted.
 * </p>
 * <p>
 * What is asserted: the marking continues past the refusal, the refusal is counted and named, the
 * merge is refused on the count, and a settings file is not written short. What is NOT asserted:
 * that the refused decision gets applied - it cannot be, and the point is that nothing pretends it
 * was.
 * </p>
 */
public class DecisionsThatDoNotApplyStopTheMergeTest
{
    private static FakeComparison twoCatalogues()
    {
        return FakeComparison.over(FakeComparison.plain(1L, null).child(
            FakeComparison.top(2L, "Catalog.Alpha", FakeComparison.changedOnBothSides())).child(
                FakeComparison.top(3L, "Catalog.Beta", FakeComparison.changedOnBothSides())));
    }

    @Test
    public void aDecisionNamingAnAbsentObjectIsCountedAndTheRestAreStillMarked()
    {
        FakeComparison fake = twoCatalogues();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();

        BmComparisonHelper.decide(fake.session(), List.of(
            new BmComparisonHelper.Decision("Catalog.Alpha", "GET_FROM_OTHER"), //$NON-NLS-1$ //$NON-NLS-2$
            new BmComparisonHelper.Decision("Catalog.NoSuch", "DO_NOT_MERGE"), //$NON-NLS-1$ //$NON-NLS-2$
            new BmComparisonHelper.Decision("Catalog.Beta", "DO_NOT_MERGE")), //$NON-NLS-1$ //$NON-NLS-2$
            null, outcome, () -> {
            });

        assertEquals("the two usable decisions are still recorded", 2, outcome.decided); //$NON-NLS-1$
        assertEquals("the unusable one is counted, not dropped on the floor", 1, //$NON-NLS-1$
            outcome.decisionsRefused);
        assertTrue("and named, because a count alone does not say which decision to fix: " //$NON-NLS-1$
            + outcome.decisionsNote,
            outcome.decisionsNote.contains("no object named Catalog.NoSuch")); //$NON-NLS-1$
        assertEquals("the decision after the refusal is marked, not skipped by an early return", //$NON-NLS-1$
            MergeRule.GET_FROM_OTHER, fake.ruleOn(2L));
        assertEquals(MergeRule.DO_NOT_MERGE, fake.ruleOn(3L));
    }

    @Test
    public void theMergeIsRefusedOnTheCount()
    {
        FakeComparison fake = twoCatalogues();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.decide(fake.session(), List.of(
            new BmComparisonHelper.Decision("Catalog.Alpha", "GET_FROM_OTHER"), //$NON-NLS-1$ //$NON-NLS-2$
            new BmComparisonHelper.Decision("Catalog.NoSuch", "DO_NOT_MERGE")), //$NON-NLS-1$ //$NON-NLS-2$
            null, outcome, () -> {
            });

        String refused = BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.MERGE, outcome);
        assertNotNull("a merge that would apply half of what was asked has to say no", refused); //$NON-NLS-1$
        assertTrue(refused, refused.contains("1 decision(s)")); //$NON-NLS-1$
        assertFalse("refused, not silently partial", outcome.merged); //$NON-NLS-1$
    }

    @Test
    public void aMergeWhoseEveryDecisionRecordedIsNotRefusedForLackingOnes()
    {
        // The gate's other half: an ordinary clean decision list must not be refused by the
        // refused-count branch, or every merge would stop.
        FakeComparison fake = twoCatalogues();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.decide(fake.session(),
            List.of(new BmComparisonHelper.Decision("Catalog.Alpha", "GET_FROM_OTHER")), //$NON-NLS-1$ //$NON-NLS-2$
            null, outcome, () -> {
            });

        assertNull(BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.MERGE, outcome));
    }

    @Test
    public void aRuleThatIsNotOneAndAMergeNoEditorCanRunAreCountedNotFatal()
    {
        FakeComparison fake = twoCatalogues();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();

        BmComparisonHelper.decide(fake.session(), List.of(
            new BmComparisonHelper.Decision("Catalog.Alpha", "NOT_A_RULE"), //$NON-NLS-1$ //$NON-NLS-2$
            new BmComparisonHelper.Decision("Catalog.Beta", "CUSTOM_MERGE")), //$NON-NLS-1$ //$NON-NLS-2$
            null, outcome, () -> {
            });

        assertEquals(0, outcome.decided);
        assertEquals("both kinds of unusable decision are refused decisions", 2, //$NON-NLS-1$
            outcome.decisionsRefused);
        assertTrue(String.valueOf(outcome.decisionsNote),
            outcome.decisionsNote.contains("is not a merge rule")); //$NON-NLS-1$
        assertNotNull(BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.MERGE, outcome));
    }

    @Test
    public void theSettingsFileIsNotWrittenShort()
    {
        // A file that quietly lacks half the decisions somebody wrote is worse than no file - the
        // whole reason a refused decision is refused rather than skipped.
        FakeComparison fake = twoCatalogues();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        AtomicBoolean written = new AtomicBoolean();

        BmComparisonHelper.decide(fake.session(), List.of(
            new BmComparisonHelper.Decision("Catalog.Alpha", "GET_FROM_OTHER"), //$NON-NLS-1$ //$NON-NLS-2$
            new BmComparisonHelper.Decision("Catalog.NoSuch", "DO_NOT_MERGE")), //$NON-NLS-1$ //$NON-NLS-2$
            "decisions.zip", outcome, () -> written.set(true)); //$NON-NLS-1$

        assertFalse("the file is not written while a decision is missing from it", written.get()); //$NON-NLS-1$
        assertNull(outcome.decisionsWrittenTo);
        assertTrue(String.valueOf(outcome.decisionsNote),
            outcome.decisionsNote.contains("not written")); //$NON-NLS-1$
    }

    @Test
    public void theSettingsFileIsWrittenWhenEveryDecisionRecorded()
    {
        FakeComparison fake = twoCatalogues();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        AtomicBoolean written = new AtomicBoolean();

        BmComparisonHelper.decide(fake.session(),
            List.of(new BmComparisonHelper.Decision("Catalog.Alpha", "GET_FROM_OTHER")), //$NON-NLS-1$ //$NON-NLS-2$
            "decisions.zip", outcome, () -> written.set(true)); //$NON-NLS-1$

        assertTrue(written.get());
        assertEquals("decisions.zip", outcome.decisionsWrittenTo); //$NON-NLS-1$
    }

    @Test
    public void aSessionThatWasNeverThereRefusesEveryDecision()
    {
        // Counted as refused rather than said and forgotten: an update mode carrying explicit
        // decisions used to run its merge with none of them attempted, because an empty note is
        // not a reason anything downstream reads.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        List<BmComparisonHelper.Decision> decisions =
            List.of(new BmComparisonHelper.Decision("Catalog.Alpha", "GET_FROM_OTHER"), //$NON-NLS-1$ //$NON-NLS-2$
                new BmComparisonHelper.Decision("Catalog.Beta", "DO_NOT_MERGE")); //$NON-NLS-1$ //$NON-NLS-2$

        BmComparisonHelper.decide(null, decisions, null, outcome, () -> {
        });

        assertEquals(decisions.size(), outcome.decisionsRefused);
        assertEquals(0, outcome.decided);
        assertNotNull(BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.MERGE, outcome));
    }

    @Test
    public void anUpdateModeNeedsNoDecisionsAndIsNotRefusedForHavingNone()
    {
        // whyNotDecided's nothing-to-apply half is off for the two update intents: they carry
        // their own decisions, and decided is what the protection itself counted.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.decided = 4;
        assertNull(BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.UPDATE_KEEPING_OURS,
            outcome));
        assertNull(BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.UPDATE_UNCHANGED,
            outcome));

        outcome.decisionsRefused = 1;
        assertNotNull("a refused decision stops an update mode the same as a plain merge", //$NON-NLS-1$
            BmComparisonHelper.whyNotDecided(BmComparisonHelper.Intent.UPDATE_KEEPING_OURS,
                outcome));
    }
}
