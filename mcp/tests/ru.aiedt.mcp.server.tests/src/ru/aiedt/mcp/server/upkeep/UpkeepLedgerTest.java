/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.equinox.p2.metadata.Version;
import org.junit.Test;

import ru.aiedt.mcp.server.upkeep.ReleaseOffer.State;
import ru.aiedt.mcp.server.upkeep.UpkeepLedger.Lease;
import ru.aiedt.mcp.server.upkeep.UpkeepLedger.Work;

/**
 * Covers the two guarantees the rest of the feature is built on: only one piece of work runs at a
 * time, and a setting changed mid-operation invalidates an answer about a site without ever hiding
 * a change already made to the profile on disk.
 * <p>
 * Those two rules pull in opposite directions, and the tests below are written around the cases
 * where getting them backwards produces a plausible-looking bug: a second installer started over a
 * live one, or a machine left running code its profile no longer describes with nothing on screen
 * to say so.
 * </p>
 */
public class UpkeepLedgerTest
{
    private static final URI SITE = URI.create("https://example.org/site/"); //$NON-NLS-1$
    private static final URI OTHER_SITE = URI.create("https://mirror.example.org/site/"); //$NON-NLS-1$
    private static final Version INSTALLED = Version.create("3.1.0.202608011200"); //$NON-NLS-1$
    private static final Version OFFERED = Version.create("3.2.0.202609011200"); //$NON-NLS-1$
    private static final long CHECKED_AT = 1_800_000_000_000L;

    @Test
    public void aFreshLedgerIsAsleepAndIdle()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        assertEquals(State.DORMANT, ledger.current().state());
        assertFalse(ledger.busy());
    }

    @Test
    public void claimingTheSlotShowsTheWorkAndCarriesKnowledgeForward()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT));

        Lease lease = claim(ledger, Work.CHECK);

        assertEquals(State.CHECKING, ledger.current().state());
        assertEquals("a check must not blank a known offer", OFFERED, ledger.current().offered()); //$NON-NLS-1$
        assertTrue(ledger.busy());
        assertEquals(Work.CHECK, lease.work());
    }

    @Test
    public void aSecondClaimIsRefusedWhileWorkIsRunning()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        claim(ledger, Work.CHECK);

        assertFalse("a second check must not start", ledger.begin(Work.CHECK).isPresent()); //$NON-NLS-1$
        assertFalse("an install must not start on top of a check", //$NON-NLS-1$
            ledger.begin(Work.INSTALL).isPresent());
    }

    @Test
    public void completingPublishesTheResultAndFreesTheSlot()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        Lease lease = claim(ledger, Work.CHECK);

        assertTrue(ledger.complete(lease, ReleaseOffer.available(SITE, INSTALLED, OFFERED,
            CHECKED_AT)));

        assertEquals(State.UPDATE_AVAILABLE, ledger.current().state());
        assertFalse(ledger.busy());
        assertTrue(ledger.begin(Work.INSTALL).isPresent());
    }

    @Test
    public void anAnswerAboutASiteNobodyAsksAboutAnyMoreIsDropped()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        Lease lease = claim(ledger, Work.CHECK);

        ledger.reconfigured(ReleaseOffer.noData(OTHER_SITE));
        boolean published =
            ledger.complete(lease, ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT));

        assertFalse("the answer describes the old site", published); //$NON-NLS-1$
        assertEquals(State.NO_DATA, ledger.current().state());
        assertEquals(OTHER_SITE, ledger.current().site());
        assertFalse("the slot is still given back", ledger.busy()); //$NON-NLS-1$
    }

    @Test
    public void aFinishedInstallIsPublishedEvenWhenTheSettingChangedMeanwhile()
    {
        // The profile on disk has already changed. Dropping this result because the URL was edited
        // would hide the need to restart and then permit another install over a runtime and a
        // profile that disagree.
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT));
        Lease lease = claim(ledger, Work.INSTALL);

        ledger.reconfigured(ReleaseOffer.noData(OTHER_SITE));
        boolean published = ledger.complete(lease,
            ReleaseOffer.restartPending(SITE, INSTALLED, OFFERED, CHECKED_AT));

        assertTrue("an install that changed the profile is a fact about this machine", published); //$NON-NLS-1$
        assertEquals(State.RESTART_PENDING, ledger.current().state());
    }

    @Test
    public void anInstallThatChangedNothingFollowsTheOrdinaryRule()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT));
        Lease lease = claim(ledger, Work.INSTALL);

        ledger.reconfigured(ReleaseOffer.noData(OTHER_SITE));
        boolean published =
            ledger.complete(lease, ReleaseOffer.failed(SITE, INSTALLED, "resolution failed", //$NON-NLS-1$
                CHECKED_AT));

        assertFalse("nothing was installed, so nothing outranks the setting change", published); //$NON-NLS-1$
        assertEquals(State.NO_DATA, ledger.current().state());
    }

    @Test
    public void changingASettingDoesNotFreeTheSlot()
    {
        // If it did, a second install would start on top of a live first one.
        UpkeepLedger ledger = new UpkeepLedger();
        claim(ledger, Work.INSTALL);

        ledger.reconfigured(ReleaseOffer.noData(OTHER_SITE));

        assertTrue("work is still running", ledger.busy()); //$NON-NLS-1$
        assertFalse("and nothing else may start", ledger.begin(Work.INSTALL).isPresent()); //$NON-NLS-1$
    }

    @Test
    public void aPendingRestartSurvivesASettingChange()
    {
        // It states something about the running process, not about any site.
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.restartPending(SITE, INSTALLED, OFFERED, CHECKED_AT));

        ledger.reconfigured(ReleaseOffer.dormant());

        assertEquals(State.RESTART_PENDING, ledger.current().state());
        assertEquals(OFFERED, ledger.current().offered());
    }

    @Test
    public void noWorkStartsWhileARestartIsPending()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.restartPending(SITE, INSTALLED, OFFERED, CHECKED_AT));

        assertFalse(ledger.begin(Work.CHECK).isPresent());
        assertFalse(ledger.begin(Work.INSTALL).isPresent());
        assertFalse(ledger.busy());
    }

    @Test
    public void aLeaseIsSpentOnceAndBelongsToItsOwnLedger()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        UpkeepLedger other = new UpkeepLedger();
        Lease lease = claim(ledger, Work.CHECK);
        Lease foreign = claim(other, Work.CHECK);

        assertFalse("a lease from another ledger publishes nothing", //$NON-NLS-1$
            ledger.complete(foreign, ReleaseOffer.upToDate(SITE, INSTALLED, CHECKED_AT)));
        assertTrue("and does not release someone else's slot", ledger.busy()); //$NON-NLS-1$

        assertTrue(ledger.complete(lease, ReleaseOffer.upToDate(SITE, INSTALLED, CHECKED_AT)));
        assertFalse("completing twice changes nothing", //$NON-NLS-1$
            ledger.complete(lease, ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT)));
        assertEquals(State.UP_TO_DATE, ledger.current().state());
    }

    @Test
    public void anAbandonedOperationGivesTheSlotBackWithoutPublishing()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT));
        Lease lease = claim(ledger, Work.CHECK);

        ledger.release(lease);

        assertFalse(ledger.busy());
        assertEquals("the state is left as the abandoning caller found it", State.CHECKING, //$NON-NLS-1$
            ledger.current().state());
        assertTrue(ledger.begin(Work.CHECK).isPresent());
    }

    @Test
    public void anAbandonedTaskThatFinishesAnywayPublishesNothing()
    {
        // The dangerous interleaving: a cancellation or a stopping bundle gives the slot back while
        // the task is still running, another task takes the slot, and only then does the abandoned
        // one finish. Its answer must not land on top of the task that started meanwhile.
        UpkeepLedger ledger = new UpkeepLedger();
        ledger.publish(ReleaseOffer.available(SITE, INSTALLED, OFFERED, CHECKED_AT));
        Lease abandoned = claim(ledger, Work.CHECK);
        ledger.release(abandoned);
        Lease successor = claim(ledger, Work.CHECK);

        assertFalse("an abandoned lease is spent", //$NON-NLS-1$
            ledger.complete(abandoned, ReleaseOffer.upToDate(SITE, INSTALLED, CHECKED_AT)));

        assertEquals("the successor's state stands", State.CHECKING, ledger.current().state()); //$NON-NLS-1$
        assertTrue("and its slot is untouched", ledger.busy()); //$NON-NLS-1$
        assertTrue(ledger.complete(successor, ReleaseOffer.upToDate(SITE, INSTALLED, CHECKED_AT)));
        assertEquals(State.UP_TO_DATE, ledger.current().state());
    }

    @Test
    public void aSettingChangeAdvancesTheGeneration()
    {
        UpkeepLedger ledger = new UpkeepLedger();
        int before = ledger.generation();

        ledger.reconfigured(ReleaseOffer.noData(OTHER_SITE));

        assertTrue(ledger.generation() > before);
    }

    @Test
    public void exactlyOneOfManyThreadsGetsTheSlot()
    {
        final UpkeepLedger ledger = new UpkeepLedger();
        final int threads = 8;
        final CyclicBarrier startTogether = new CyclicBarrier(threads);
        final AtomicInteger claimed = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++)
        {
            Thread worker = new Thread(() -> {
                try
                {
                    startTogether.await();
                    Optional<Lease> lease = ledger.begin(Work.CHECK);
                    if (lease.isPresent())
                    {
                        claimed.incrementAndGet();
                    }
                }
                catch (Exception e)
                {
                    failures.incrementAndGet();
                }
            }, "upkeep-ledger-race-" + i); //$NON-NLS-1$
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers)
        {
            try
            {
                worker.join(30_000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        assertEquals("no worker may fail", 0, failures.get()); //$NON-NLS-1$
        assertEquals("the slot is single", 1, claimed.get()); //$NON-NLS-1$
        assertTrue(ledger.busy());
    }

    private static Lease claim(UpkeepLedger ledger, Work work)
    {
        Optional<Lease> lease = ledger.begin(work);
        assertTrue("the slot was expected to be free", lease.isPresent()); //$NON-NLS-1$
        return lease.get();
    }
}
