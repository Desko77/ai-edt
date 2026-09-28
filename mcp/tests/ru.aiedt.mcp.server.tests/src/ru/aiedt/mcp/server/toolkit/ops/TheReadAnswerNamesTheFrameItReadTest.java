/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.debug.core.model.IStackFrame;
import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * A caller that named a thread and an index is told which frame the values came from.
 *
 * <p>The answer used to carry the variables alone, so a call that landed on another frame - a stack
 * that had moved between the two calls, an index counted from the wrong end - read exactly like the
 * call that landed where it meant to. The frame's address now comes back with the values, and an
 * index the stack does not reach is refused rather than folded into the nearest frame: reading
 * somebody else's frame is work nobody asked for, and the caller cannot tell it happened.</p>
 */
public class TheReadAnswerNamesTheFrameItReadTest
{
    private static final String APP = "b61-read-app"; //$NON-NLS-1$

    private static final String OUT_OF_RANGE = "999999999"; //$NON-NLS-1$

    @After
    public void clearTheFakeSnapshot()
    {
        DebugSessionBook.get().clearSnapshot(APP);
    }

    @Test
    public void theAnswerCarriesTheAddressOfTheFrameItRead()
    {
        FakeDebugFrames.Var total = FakeDebugFrames.var("Сумма").type("Число").value("42"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo(session.frame("ОбщийМодуль.Метод", total.asVariable()), //$NON-NLS-1$
            session.frame("ВВызове")); //$NON-NLS-1$
        long threadId = FakeDebugToolCalls.register(APP, session);

        JsonObject answer = FakeDebugToolCalls.json(new DebugVariablesReader().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId), "frameIndex", "0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(true, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.has("threadId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.has("frameIndex")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.has("frameName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(threadId, answer.get("threadId").getAsLong()); //$NON-NLS-1$
        assertEquals(0, answer.get("frameIndex").getAsInt()); //$NON-NLS-1$
        assertEquals("ОбщийМодуль.Метод", answer.get("frameName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, answer.get("count").getAsInt()); //$NON-NLS-1$
        assertEquals("Сумма", answer.getAsJsonArray("variables").get(0).getAsJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
            .get("name").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void anIndexTheStackDoesNotReachIsRefusedRatherThanFolded() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame caller = session.frame("ПередВызовом"); //$NON-NLS-1$
        IStackFrame callee = session.frame("ВВызове"); //$NON-NLS-1$
        session.moveTo(caller, callee);
        FakeDebugToolCalls.register(APP, session);

        DebugFrameResolution.Resolution beyond =
            DebugFrameResolution.against(DebugSessionBook.get(), APP, 7);
        assertNull("an index past the stack is not a frame", beyond.frame); //$NON-NLS-1$
        assertTrue(beyond.refusal, beyond.refusal.contains("frameIndex is out of range (0..1)")); //$NON-NLS-1$ //$NON-NLS-2$

        DebugFrameResolution.Resolution inside =
            DebugFrameResolution.against(DebugSessionBook.get(), APP, 1);
        assertSame(callee, inside.frame);
        assertEquals(1, inside.index);
    }

    @Test
    public void aThreadThatHoldsNoFramesSaysSo() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo();
        FakeDebugToolCalls.register(APP, session);

        DebugFrameResolution.Resolution resolved =
            DebugFrameResolution.against(DebugSessionBook.get(), APP, 0);

        assertNull(resolved.frame);
        assertEquals("the suspended thread has no stack frames", resolved.refusal); //$NON-NLS-1$
    }

    @Test
    public void theReaderRefusesAnIndexItsThreadDoesNotReach()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo(session.frame("ВВызове")); //$NON-NLS-1$
        long threadId = FakeDebugToolCalls.register(APP, session);

        String error = FakeDebugToolCalls.error(new DebugVariablesReader().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId), "frameIndex", "7"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(error, error.contains("frameIndex is out of range (0..0)")); //$NON-NLS-1$
    }

    @Test
    public void aStaleReferenceIsNotAnsweredWithAThreadThatIsGone()
    {
        String error = FakeDebugToolCalls.error(new DebugVariablesReader().execute(
            FakeDebugToolCalls.args("frameRef", OUT_OF_RANGE))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("frameRef is no longer valid - call wait_for_break again", error); //$NON-NLS-1$
    }
}
