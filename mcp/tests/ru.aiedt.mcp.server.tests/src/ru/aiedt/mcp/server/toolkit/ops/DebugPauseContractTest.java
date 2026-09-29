/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What a pause answers, in the four states a caller has to tell apart.
 *
 * <p>The session behind these calls is a fake target, so the decision is exercised with no client at
 * all: whether a suspend request is placed, which thread receives it, and what the answer says when
 * nothing suspends. The loop itself - whether a real 1C client stops on the request - is measured on
 * the stand, not here.</p>
 *
 * <p>Each test uses its own application id: the session registry is a singleton for the whole test
 * JVM, and a snapshot left by another test would be answered as a stop of its own.</p>
 */
public class DebugPauseContractTest
{
    private static final String PAUSED_APP = "d19-pause-paused"; //$NON-NLS-1$
    private static final String NAMED_APP = "d19-pause-named-thread"; //$NON-NLS-1$
    private static final String ARMED_APP = "d19-pause-armed"; //$NON-NLS-1$
    private static final String BARE_APP = "d19-pause-bare-target"; //$NON-NLS-1$
    private static final String TERMINATED_APP = "d19-pause-terminated"; //$NON-NLS-1$
    private static final String TARGETLESS_APP = "d19-pause-targetless"; //$NON-NLS-1$
    private static final String REFUSED_APP = "d19-pause-refused"; //$NON-NLS-1$

    private static final List<String> TOUCHED = List.of(PAUSED_APP, NAMED_APP, ARMED_APP, BARE_APP,
        TERMINATED_APP, TARGETLESS_APP, REFUSED_APP);

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSessions()
    {
        for (String applicationId : TOUCHED)
        {
            registry.clearSnapshot(applicationId);
        }
    }

    @Test
    public void aRunningSessionThatStopsAnswersPausedWithItsFrame() throws Exception
    {
        FakeDebugFrames.Session session = runningSession(PAUSED_APP);
        session.moveTo(session.frame("ПроверитьЗаполнение", //$NON-NLS-1$
            FakeDebugFrames.var("Количество").asVariable())); //$NON-NLS-1$
        registry.clearSnapshot(PAUSED_APP);

        JsonObject answer = json(DebugPauser.pause(registry, PAUSED_APP, session.debugTarget(),
            null, 1));

        assertEquals(DebugPauser.OUTCOME_PAUSED, outcome(answer));
        assertEquals("the request went to the session's thread", 1, session.suspendRequests); //$NON-NLS-1$
        assertTrue("a paused answer names the thread the other tools address", //$NON-NLS-1$
            answer.get("threadId").getAsLong() > 0); //$NON-NLS-1$
        assertEquals(true, answer.get("hit").getAsBoolean()); //$NON-NLS-1$

        assertEquals(1, answer.getAsJsonArray("frames").size()); //$NON-NLS-1$
        JsonObject frame = answer.getAsJsonArray("frames").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("ПроверитьЗаполнение", frame.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the stack has to be reachable from the answer", //$NON-NLS-1$
            answer.get("topFrameRef").getAsLong() > 0); //$NON-NLS-1$
    }

    @Test
    public void aThreadNamedByTheCallerIsTheOnlyOneAsked() throws Exception
    {
        FakeDebugFrames.Session session = runningSession(NAMED_APP);
        session.moveTo(session.frame("ОбработкаПроведения")); //$NON-NLS-1$

        JsonObject answer = json(DebugPauser.pause(registry, NAMED_APP, session.debugTarget(),
            session.thread(), 1));

        assertEquals(DebugPauser.OUTCOME_PAUSED, outcome(answer));
        assertEquals(1, session.suspendRequests);
        assertEquals("the answer reports the thread that was named, not another one", //$NON-NLS-1$
            session.thread(), registry.getThread(answer.get("threadId").getAsLong())); //$NON-NLS-1$
    }

    @Test
    public void anAcceptedRequestThatDoesNotLandAnswersRequestArmed() throws Exception
    {
        FakeDebugFrames.Session session = runningSession(ARMED_APP);
        // A request that is accepted and still does not stop the client: what a session with no BSL
        // running answers. canSuspend answers false here on purpose - the answer is reported, and the
        // request is placed anyway, because whether a 1C thread answers it truthfully is not known.
        session.suspendsOnRequest = false;
        session.canSuspend = false;

        JsonObject answer = json(DebugPauser.pause(registry, ARMED_APP, session.debugTarget(), null, 1));

        assertEquals(DebugPauser.OUTCOME_REQUEST_ARMED, outcome(answer));
        assertEquals("the request has to have been placed", 1, session.suspendRequests); //$NON-NLS-1$
        assertEquals(1, answer.get("waitedSeconds").getAsInt()); //$NON-NLS-1$
        assertFalse("an armed request is a request, not a stop: nothing suspended", //$NON-NLS-1$
            answer.has("hit")); //$NON-NLS-1$

        JsonObject armed = answer.getAsJsonArray("armed").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Session thread", armed.get("thread").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(false, armed.get("canSuspend").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void aTargetThatExposesNoThreadIsAskedItself() throws Exception
    {
        FakeDebugFrames.Target bare = new FakeDebugFrames.Target(BARE_APP);

        JsonObject answer = json(DebugPauser.pause(registry, BARE_APP, bare.asDebugTarget(), null, 1));

        assertEquals(DebugPauser.OUTCOME_REQUEST_ARMED, outcome(answer));
        assertEquals("the target stands for the session when it names no thread", 1, //$NON-NLS-1$
            bare.suspendRequests);
        assertEquals("debug target", //$NON-NLS-1$ //$NON-NLS-2$
            answer.getAsJsonArray("armed").get(0).getAsJsonObject().get("thread").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSessionThatHasEndedAnswersTerminated() throws Exception
    {
        FakeDebugFrames.Session session = runningSession(TERMINATED_APP);
        session.target.terminated = true;

        JsonObject answer = json(DebugPauser.pause(registry, TERMINATED_APP, session.debugTarget(),
            null, 1));

        assertEquals(DebugPauser.OUTCOME_TERMINATED, outcome(answer));
        assertEquals("nothing is asked of a session that is gone", 0, session.suspendRequests); //$NON-NLS-1$
        assertTrue(answer.get("note").getAsString().contains("has terminated")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anAddressWithNoTargetAtAllAnswersTerminated() throws Exception
    {
        JsonObject answer = json(DebugPauser.pause(registry, TARGETLESS_APP, null, null, 1));

        assertEquals(DebugPauser.OUTCOME_TERMINATED, outcome(answer));
        assertEquals(TARGETLESS_APP, answer.get("applicationId").getAsString()); //$NON-NLS-1$
        assertTrue("a launch that never existed reads differently from one that ended", //$NON-NLS-1$
            answer.get("note").getAsString().contains("No live debug session")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRequestEveryThreadRefusesAnswersErrorNotArmed() throws Exception
    {
        FakeDebugFrames.Session session = runningSession(REFUSED_APP);
        session.suspendRefusal = new IllegalStateException("the thread is not suspended"); //$NON-NLS-1$

        JsonObject answer = json(DebugPauser.pause(registry, REFUSED_APP, session.debugTarget(), null, 1));

        assertEquals(DebugPauser.OUTCOME_ERROR, outcome(answer));
        assertTrue("the refusal names what refused it: " + answer, //$NON-NLS-1$
            answer.get("error").getAsString().contains("the thread is not suspended")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, answer.getAsJsonArray("refused").size()); //$NON-NLS-1$
        assertFalse(answer.has("armed")); //$NON-NLS-1$
    }

    /**
     * @param applicationId the application the session belongs to
     * @return a session that is alive, not suspended, says it can suspend, and reports its stop back
     *         when asked
     */
    private static FakeDebugFrames.Session runningSession(String applicationId)
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(applicationId);
        session.suspended = false;
        session.canSuspend = true;
        return session;
    }

    /**
     * @param result what the tool built
     * @return the document a client reads
     */
    private static JsonObject json(ToolResult result)
    {
        return FakeDebugToolCalls.json(result.toJson());
    }

    /**
     * @param answer the parsed document
     * @return the outcome it opens with
     */
    private static String outcome(JsonObject answer)
    {
        return answer.get(DebugPauser.KEY_OUTCOME).getAsString();
    }
}
