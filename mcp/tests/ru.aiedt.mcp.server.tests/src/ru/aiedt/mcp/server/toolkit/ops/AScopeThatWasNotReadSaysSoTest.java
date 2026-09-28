/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.debug.core.model.IStackFrame;
import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * A module scope the frame would not hand over is reported, not answered as an empty scope.
 *
 * <p>Asking for {@code scope=module} reads the module variables through a getter only a BSL frame
 * has, and that getter can throw - the client is gone, the frame is no longer readable. The failure
 * was swallowed: the answer came back with the locals alone and no word about the scope that was
 * asked for, which reads exactly like a module without variables. A caller that then reads the
 * listing as complete is missing names that exist, and nothing it was told says so.</p>
 *
 * <p>A frame that does not declare the getter is a different case: module scope is not available
 * there, nothing failed, and the answer stays silent about it.</p>
 */
public class AScopeThatWasNotReadSaysSoTest
{
    private static final String APP = "b61-scope-app"; //$NON-NLS-1$

    @After
    public void clearTheFakeSnapshot()
    {
        DebugSessionBook.get().clearSnapshot(APP);
    }

    @Test
    public void aModuleScopeThatThrewIsNamedInTheAnswer()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame frame = session.moduleFrame("ОбщийМодуль.Метод", //$NON-NLS-1$
            FakeDebugFrames.var("Локальная").value("1").asVariable()); //$NON-NLS-1$ //$NON-NLS-2$
        session.moveTo(frame);
        session.moduleFailure = new IllegalStateException("клиент не отвечает"); //$NON-NLS-1$
        long threadId = FakeDebugToolCalls.register(APP, session);

        JsonObject answer = FakeDebugToolCalls.json(new DebugVariablesReader().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId), "scope", "module"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(true, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the locals were not asked for", 0, answer.get("count").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.has("scopeFailures")); //$NON-NLS-1$ //$NON-NLS-2$
        String reason = answer.getAsJsonObject("scopeFailures").get("module").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(reason, reason.contains("не отвечает")); //$NON-NLS-1$ //$NON-NLS-2$
        String note = answer.get("scopeFailureNote").getAsString(); //$NON-NLS-1$
        assertTrue(note, note.contains("module")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note, note.contains("incomplete")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aScopeThatAnsweredIsNotReportedAsAFailure()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame frame = session.moduleFrame("ОбщийМодуль.Метод"); //$NON-NLS-1$
        session.moveTo(frame);
        session.moduleVariables = new org.eclipse.debug.core.model.IVariable[] {
            FakeDebugFrames.var("МодульнаяПеременная").value("7").asVariable() }; //$NON-NLS-1$ //$NON-NLS-2$
        long threadId = FakeDebugToolCalls.register(APP, session);

        JsonObject answer = FakeDebugToolCalls.json(new DebugVariablesReader().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId), "scope", "module"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(1, answer.get("count").getAsInt()); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.has("scopeFailures")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFrameWithoutTheScopeIsNotAFailure()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo(session.frame("ВВызове")); //$NON-NLS-1$
        long threadId = FakeDebugToolCalls.register(APP, session);

        JsonObject answer = FakeDebugToolCalls.json(new DebugVariablesReader().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId), "scope", "all"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(0, answer.get("count").getAsInt()); //$NON-NLS-1$
        assertFalse("a frame that has no module scope did not fail to answer", //$NON-NLS-1$
            answer.has("scopeFailures")); //$NON-NLS-1$
    }
}
