/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.eclipse.debug.core.model.IStackFrame;
import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * A write that did not happen says which input was wrong, and writes nowhere else.
 *
 * <p>Three different things went wrong through one sentence: a reference the session had left
 * behind, a thread id it had left behind, and a call that named no frame at all. All three were
 * answered with the sentence about naming no frame, so a caller that had named one and got the
 * address wrong was told to name one - and the next call repeated the same mistake.</p>
 *
 * <p>An index the stack does not reach is refused before anything is written, and the frame the
 * index does name is the one written: a write that lands on the neighbouring frame is the one
 * failure a debugger cannot take back.</p>
 */
public class AWriteRefusalIsAboutTheInputThatWasWrongTest
{
    private static final String APP = "b61-write-app"; //$NON-NLS-1$

    private static final String STALE = "999999999"; //$NON-NLS-1$

    @After
    public void clearTheFakeSnapshot()
    {
        DebugSessionBook.get().clearSnapshot(APP);
    }

    @Test
    public void eachWrongInputIsAnsweredWithItsOwnText()
    {
        String staleRef = FakeDebugToolCalls.error(new DebugVariableWriter().execute(
            FakeDebugToolCalls.args("frameRef", STALE, "path", "Пароль", "value", "\"новый\""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        String staleThread = FakeDebugToolCalls.error(new DebugVariableWriter().execute(
            FakeDebugToolCalls.args("threadId", STALE, "path", "Пароль", "value", "\"новый\""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        String nothingNamed = FakeDebugToolCalls.error(new DebugVariableWriter().execute(
            FakeDebugToolCalls.args("path", "Пароль", "value", "\"новый\""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("frameRef is no longer valid - call wait_for_break again", staleRef); //$NON-NLS-1$
        assertEquals("threadId is no longer valid - call wait_for_break again", staleThread); //$NON-NLS-1$
        assertTrue(nothingNamed, nothingNamed.contains("no single suspended debug launch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a stale reference is not a call that named nothing", //$NON-NLS-1$
            staleRef.equals(nothingNamed));
        assertFalse("a stale thread id is not a call that named nothing", //$NON-NLS-1$
            staleThread.equals(nothingNamed));
    }

    @Test
    public void anIndexTheStackDoesNotReachIsRefusedAndNothingIsWritten()
    {
        FakeDebugFrames.Var password = FakeDebugFrames.var("Пароль").type("Строка").value("\"старый\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo(session.frame("Внешний", password.asVariable()), session.frame("ВВызове")); //$NON-NLS-1$ //$NON-NLS-2$
        long threadId = FakeDebugToolCalls.register(APP, session);

        String error = FakeDebugToolCalls.error(new DebugVariableWriter().execute(FakeDebugToolCalls.args(
            "threadId", String.valueOf(threadId), "frameIndex", "7", "path", "Пароль", "value", "\"новый\""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$

        assertTrue(error, error.contains("frameIndex is out of range (0..1)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("no frame was written, least of all the one the index nearly named", //$NON-NLS-1$
            Collections.emptyList(), password.written);
    }

    @Test
    public void theFrameTheIndexNamesIsTheOneWritten()
    {
        FakeDebugFrames.Var outer = FakeDebugFrames.var("Пароль").type("Строка").value("\"старший\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FakeDebugFrames.Var inner = FakeDebugFrames.var("Пароль").type("Строка").value("\"младший\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame caller = session.frame("Внешний", outer.asVariable()); //$NON-NLS-1$
        IStackFrame callee = session.frame("ВВызове", inner.asVariable()); //$NON-NLS-1$
        session.moveTo(caller, callee);
        long threadId = FakeDebugToolCalls.register(APP, session);

        JsonObject answer = FakeDebugToolCalls.json(new DebugVariableWriter().execute(FakeDebugToolCalls.args(
            "threadId", String.valueOf(threadId), "frameIndex", "1", "path", "Пароль", "value", "\"младший-новый\""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$

        assertEquals(answer.toString(), true, answer.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.singletonList("\"младший-новый\""), inner.written); //$NON-NLS-1$
        assertEquals(Collections.emptyList(), outer.written);
    }
}
