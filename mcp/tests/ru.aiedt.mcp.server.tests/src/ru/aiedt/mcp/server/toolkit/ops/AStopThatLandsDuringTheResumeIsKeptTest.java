/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.core.model.IBreakpoint;
import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * A stop that lands while the resume is being made survives it.
 *
 * <p>The platform reports a resume as an event of its own thread, and a thread that reaches its
 * next stop before the tool's answer is built records that stop first - a run-to-line that lands
 * at once is the everyday case. The snapshot drop that followed the resume used to be
 * unconditional: it took the fresh stop with it, and the wait the answer invites then ran out on
 * a session that was stopped the whole time. The drop now takes only the snapshot the call read
 * before it resumed; whatever stands there instead is newer than the resume and stays for the
 * caller's wait.</p>
 */
public class AStopThatLandsDuringTheResumeIsKeptTest
{
    private static final String APP = "d31-stop-during-resume"; //$NON-NLS-1$

    private static final String MODULE = "CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$
    private static final String PROJECT = "aiedt-stop-during-resume-test"; //$NON-NLS-1$
    private static final long MARKER_ID = 91L;

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSession()
    {
        registry.clearSnapshot(APP);
    }

    @Test
    public void aStopThatLandsDuringARunToLineIsWhatTheNextWaitReads() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long firstStop = FakeDebugToolCalls.register(APP, session);
        stopsAgainWhileResuming(session);

        JsonObject answer = json(RunToLineTool.execute(
            args(), file(), (f, line) -> breakpoint(MARKER_ID)));

        assertTrue("the run was accepted", answer.get("resumed").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the stop that landed during the resume is still readable", //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertNotEquals("it is the new stop, not the one the call left behind", firstStop, //$NON-NLS-1$
            registry.getSnapshot(APP).threadId);
    }

    @Test
    public void aStopThatLandsDuringAnExplicitResumeIsWhatTheNextWaitReads() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long firstStop = FakeDebugToolCalls.register(APP, session);
        stopsAgainWhileResuming(session);

        JsonObject answer = json(new DebugResumer().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(firstStop)))); //$NON-NLS-1$

        assertTrue("the resume was accepted", answer.get("resumed").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the stop that landed during the resume is still readable", //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertNotEquals("it is the new stop, not the one the call left behind", firstStop, //$NON-NLS-1$
            registry.getSnapshot(APP).threadId);
    }

    @Test
    public void aStopThatLandsDuringAnApplicationResumeIsWhatTheNextWaitReads() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long firstStop = FakeDebugToolCalls.register(APP, session);
        stopsAgainWhileResuming(session);
        FakeDebugFrames.Target target = new FakeDebugFrames.Target(APP);
        target.with(session.thread());

        JsonObject answer = json(DebugResumer.resumeApplication(registry, APP,
            target.asDebugTarget(), false).toJson());

        assertTrue("the resume was accepted", answer.get("resumed").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the stop that landed during the resume is still readable", //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertNotEquals("it is the new stop, not the one the call left behind", firstStop, //$NON-NLS-1$
            registry.getSnapshot(APP).threadId);
    }

    @Test
    public void aResumeWithoutANewStopDropsTheSnapshotSynchronously() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long threadId = FakeDebugToolCalls.register(APP, session);

        JsonObject answer = json(new DebugResumer().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId)))); //$NON-NLS-1$

        assertTrue(answer.get("resumed").getAsBoolean()); //$NON-NLS-1$
        assertFalse("nothing stopped again, so the stop that was left behind is gone", //$NON-NLS-1$
            registry.hasSnapshot(APP));
    }

    @Test
    public void aRefusedResumeLeavesTheSnapshotWhereItWas() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long threadId = FakeDebugToolCalls.register(APP, session);
        session.resumeRefusal = new IllegalStateException("the client is gone"); //$NON-NLS-1$

        JsonObject answer = json(new DebugResumer().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId)))); //$NON-NLS-1$

        assertTrue("the call failed, which is what its answer is about", answer.has("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a resume that never happened drops nothing: the stop stands", //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertNotEquals("and it still names the thread that stayed stopped", -1L, //$NON-NLS-1$
            registry.getSnapshot(APP).threadId);
    }

    @Test
    public void aRefusedRunToLineLeavesTheSnapshotWhereItWas() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        FakeDebugToolCalls.register(APP, session);
        session.resumeRefusal = new IllegalStateException("the client is gone"); //$NON-NLS-1$

        JsonObject answer = json(RunToLineTool.execute(
            args(), file(), (f, line) -> breakpoint(MARKER_ID)));

        assertTrue("the call failed, which is what its answer is about", answer.has("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a resume that never happened drops nothing: the stop stands", //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertNotEquals("and it still names the thread that stayed stopped", -1L, //$NON-NLS-1$
            registry.getSnapshot(APP).threadId);
    }

    /**
     * Makes the session deliver, inside {@code resume()}, what the platform delivers around one:
     * the RESUME event that forgets the application, then the stop the thread reaches at once.
     *
     * @param session the session whose resume delivers the events
     */
    private void stopsAgainWhileResuming(FakeDebugFrames.Session session)
    {
        session.onResumeRequest = () -> {
            FakeDebugFrames.resumeEvent(APP);
            session.suspended = true;
            registry.injectSuspend(APP, session.thread());
        };
    }

    /**
     * @return the arguments a run-to-line call sends
     */
    private static Map<String, String> args()
    {
        return FakeDebugToolCalls.args(
            "projectName", PROJECT, "module", MODULE, "lineNumber", "42", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "applicationId", APP); //$NON-NLS-1$
    }

    /**
     * @return the module file a run starts from
     */
    private static IFile file()
    {
        IPath path = (IPath)Proxy.newProxyInstance(AStopThatLandsDuringTheResumeIsKeptTest.class.getClassLoader(),
            new Class<?>[] { IPath.class }, (proxy, method, args) -> "toString".equals(method.getName()) //$NON-NLS-1$
                ? '/' + PROJECT + "/src/" + MODULE : null); //$NON-NLS-1$
        return (IFile)Proxy.newProxyInstance(AStopThatLandsDuringTheResumeIsKeptTest.class.getClassLoader(),
            new Class<?>[] { IFile.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "getFullPath": //$NON-NLS-1$
                    return path;
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString": //$NON-NLS-1$
                    return MODULE;
                default:
                    return null;
                }
            });
    }

    /**
     * @param markerId the marker id the breakpoint answers to
     * @return the one-shot breakpoint the run creates
     */
    private static IBreakpoint breakpoint(long markerId)
    {
        IResource resource = (IResource)Proxy.newProxyInstance(
            AStopThatLandsDuringTheResumeIsKeptTest.class.getClassLoader(),
            new Class<?>[] { IResource.class }, (proxy, method, args) -> null);
        IMarker marker = (IMarker)Proxy.newProxyInstance(
            AStopThatLandsDuringTheResumeIsKeptTest.class.getClassLoader(),
            new Class<?>[] { IMarker.class }, (proxy, method, args) ->
                "getId".equals(method.getName()) ? Long.valueOf(markerId) : resource); //$NON-NLS-1$
        return (IBreakpoint)Proxy.newProxyInstance(
            AStopThatLandsDuringTheResumeIsKeptTest.class.getClassLoader(),
            new Class<?>[] { IBreakpoint.class }, (proxy, method, args) ->
                "getMarker".equals(method.getName()) ? marker : null); //$NON-NLS-1$
    }

    /**
     * @param answer what the tool returned
     * @return the document a client reads
     */
    private static JsonObject json(String answer)
    {
        return FakeDebugToolCalls.json(answer);
    }
}
