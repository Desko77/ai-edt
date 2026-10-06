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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Telling a question from a hang.
 * <p>
 * A modal dialog stops the workbench, and from outside it is indistinguishable from a
 * wedged process: the call that raised it never comes back. The one met in practice is
 * Eclipse's own secure-storage prompt - "the secure storage file has been modified by
 * another program" - which several EDT instances on one machine raise at each other,
 * because they share a single keyring and each holds its own idea of when it was last
 * written. On a machine where an agent is the only one watching, it waits forever.
 * </p>
 * <p>
 * The reading itself needs a workbench with a dialog up, which no headless test has. What
 * is held here is the contract for the case this suite CAN reach - no UI at all - because
 * that is the one that would otherwise report a phantom dialog or, worse, bring the native
 * toolkit up to find out there is none.
 * </p>
 */
public class ModalDialogWatchTest
{
    @Test
    public void withoutAUiThereIsNoDialogToReport()
    {
        ModalDialogWatch.Reading reading = ModalDialogWatch.current();

        assertNotNull(reading);
        assertNotNull("never null - callers put this straight into a status payload", //$NON-NLS-1$
            reading.getDialogs());
        // A headless runtime cannot be showing anything, and saying so is an answer rather
        // than a failure: the caller asked why something is slow, and "no dialog" is a
        // useful half of that.
        assertFalse(reading.isBlocked());
    }

    @Test
    public void anUnblockedReadingHasNothingToSay()
    {
        ModalDialogWatch.Reading reading = ModalDialogWatch.current();

        // The line exists to be shown to somebody. When there is no dialog and the UI is
        // fine, there is nothing worth showing, and a status payload should not carry an
        // empty sentence around.
        if (reading.isUiResponding() && !reading.isBlocked())
        {
            assertNull(reading.describe());
        }
    }

    @Test
    public void askingCostsLittleEnoughToPutInAHealthCheck()
    {
        // /health is polled. A probe that waits on the UI thread without a budget would
        // turn every poll into the very hang it is meant to detect.
        long start = System.nanoTime();
        ModalDialogWatch.current();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue("the probe took " + elapsedMs + "ms - it is bounded, and must stay so", //$NON-NLS-1$ //$NON-NLS-2$
            elapsedMs < 3000);
    }

    @Test
    public void aPressWhoseBudgetRanOutIsNotPerformedWhenTheUiThreadWakesUpLate()
    {
        // The wedged UI thread keeps the posted task in its queue. Once the caller has been told
        // "nothing was pressed", that task running later would make the answer a lie: a button
        // would fire with nobody having asked for it anymore.
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean pressed = new AtomicBoolean();

        ModalDialogWatch.Press result = ModalDialogWatch.pressWithBudget(queued::set,
            () -> {
                pressed.set(true);
                return new ModalDialogWatch.Press(true, "OK", null); //$NON-NLS-1$
            }, 50);

        assertFalse(result.isPressed());
        assertNotNull(result.getRefusal());
        Runnable late = queued.get();
        assertNotNull("the task was posted to the UI thread", late); //$NON-NLS-1$
        late.run();
        assertFalse("a press answered as not pressed must not happen late", pressed.get()); //$NON-NLS-1$
    }

    @Test
    public void aPressWithinTheBudgetHappensAsBefore()
    {
        AtomicBoolean pressed = new AtomicBoolean();

        ModalDialogWatch.Press result = ModalDialogWatch.pressWithBudget(Runnable::run,
            () -> {
                pressed.set(true);
                return new ModalDialogWatch.Press(true, "OK", null); //$NON-NLS-1$
            }, 5000);

        assertTrue(result.isPressed());
        assertTrue(pressed.get());
        assertEquals("OK", result.getLabel()); //$NON-NLS-1$
        assertNull(result.getRefusal());
    }
}
