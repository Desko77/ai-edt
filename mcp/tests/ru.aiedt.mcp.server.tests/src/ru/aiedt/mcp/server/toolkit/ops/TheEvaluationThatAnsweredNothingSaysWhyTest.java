/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.debug.core.model.IStackFrame;
import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * An evaluation that answered nothing says why, and a wait that ran out says the expression is still
 * running.
 *
 * <p>Reading a name the variables API does not carry falls back to evaluating it, and that fallback
 * can answer nothing for reasons that are not the caller's fault - no delegate serves this model, the
 * manager is not reachable, the delegate raised. The refusal was reported as "answered nothing
 * either", so a name that cannot be evaluated at all and a name that is simply not there read the
 * same, and the caller repeats a call that cannot work.</p>
 *
 * <p>The timeout is the other half: the wait runs out, the evaluation does not stop, and the code
 * goes on running inside the client. A caller that reads the timeout as a failure evaluates the next
 * expression beside a running one.</p>
 */
public class TheEvaluationThatAnsweredNothingSaysWhyTest
{
    private static final String APP = "b61-eval-app"; //$NON-NLS-1$

    private static final String ANSWERED_NOTHING = "answered nothing: "; //$NON-NLS-1$

    @After
    public void clearTheFakeSnapshot()
    {
        DebugSessionBook.get().clearSnapshot(APP);
    }

    @Test
    public void theReadThatFellBackToEvaluationCarriesTheReason()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo(session.frame("ОбщийМодуль.Метод")); //$NON-NLS-1$
        long threadId = FakeDebugToolCalls.register(APP, session);

        String error = FakeDebugToolCalls.error(new DebugVariablesReader().execute(FakeDebugToolCalls.args(
            "threadId", String.valueOf(threadId), "expandPath", "НеизвестноеИмя"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        int at = error.indexOf(ANSWERED_NOTHING);
        assertTrue(error, at >= 0);
        assertTrue("the refusal has to say why, not only that nothing came back: " + error, //$NON-NLS-1$
            error.length() > at + ANSWERED_NOTHING.length());
    }

    @Test
    public void theEvaluationItselfNamesTheReasonItAnsweredNothing()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame frame = session.frame("ОбщийМодуль.Метод"); //$NON-NLS-1$

        ExpressionEvaluator.Evaluation evaluated =
            ExpressionEvaluator.evaluateValue(frame, "НеизвестноеИмя"); //$NON-NLS-1$

        assertNull(evaluated.value);
        assertNotNull(evaluated.refusal);
        assertTrue("a refusal that says nothing is the answer this replaces: " + evaluated.refusal, //$NON-NLS-1$
            evaluated.refusal.trim().length() > 0);
    }

    @Test
    public void nothingToEvaluateIsItsOwnRefusal()
    {
        ExpressionEvaluator.Evaluation evaluated = ExpressionEvaluator.evaluateValue(null, "Сумма"); //$NON-NLS-1$

        assertNull(evaluated.value);
        assertNotNull(evaluated.refusal);
    }

    @Test
    public void aWaitThatRanOutSaysTheExpressionIsStillRunning()
    {
        String refusal = ExpressionEvaluator.timeoutRefusal(15_000L);

        assertTrue(refusal, refusal.contains("did not finish within 15000ms")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("not cancelled")); //$NON-NLS-1$
        assertTrue("the caller has to know the code runs on in the client", //$NON-NLS-1$
            refusal.contains("still running in the debugged client")); //$NON-NLS-1$
    }
}
