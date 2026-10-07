/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
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
 * {@code run_to_line} continues the stopped thread of the session it addressed.
 *
 * <p>A debug target of this platform is not resumable - only its threads are - so a call that named
 * no thread used to resolve the target and then refuse, because the target answered
 * {@code canResume() == false}. The session was stopped, the caller had said which one, and the tool
 * still answered that it could not run. It now resolves the stopped thread the same way
 * {@code resume} does: the application's suspend snapshot first, then any stopped thread of its
 * target.</p>
 *
 * <p>The breakpoint this tool creates is made through a seam and the module file is handed in, so the
 * decision about what gets resumed is exercised with no client and no workspace behind it.</p>
 */
public class ARunToLineResumesTheStoppedThreadTest
{
    private static final String STOPPED_APP = "d31-run-to-line-stopped"; //$NON-NLS-1$
    private static final String REFUSED_APP = "d31-run-to-line-refused"; //$NON-NLS-1$
    private static final String FOREIGN_APP = "d31-run-to-line-foreign"; //$NON-NLS-1$
    private static final String NAMED_APP = "d31-run-to-line-named"; //$NON-NLS-1$

    private static final String MODULE = "CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$
    private static final String PROJECT = "aiedt-run-to-line-test"; //$NON-NLS-1$
    private static final long MARKER_ID = 77L;

    private static final List<String> TOUCHED = List.of(STOPPED_APP, REFUSED_APP, FOREIGN_APP, NAMED_APP);

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
    public void aSessionNamedByItsApplicationIsResumedThroughItsStoppedThread() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(STOPPED_APP);
        long threadId = FakeDebugToolCalls.register(STOPPED_APP, session);
        assertTrue("the session has to be stopped for this to mean anything", threadId > 0); //$NON-NLS-1$

        JsonObject answer = json(RunToLineTool.execute(
            args(STOPPED_APP, null), file(), (f, line) -> breakpoint(MARKER_ID)));

        assertTrue("the run was accepted", answer.get("resumed").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the thread is what resumes, one of them", "thread", //$NON-NLS-1$ //$NON-NLS-2$
            answer.get("scope").getAsString()); //$NON-NLS-1$
        assertEquals(MARKER_ID, answer.get("breakpointId").getAsLong()); //$NON-NLS-1$
        assertEquals("the stopped thread was the one resumed", 1, session.resumeRequests); //$NON-NLS-1$
        assertEquals("the target is not resumable and was not asked to resume", 0, //$NON-NLS-1$
            session.target.resumeRequests);
        assertFalse("the stale snapshot goes before the caller is told to wait", //$NON-NLS-1$
            registry.hasSnapshot(STOPPED_APP));
    }

    @Test
    public void theStoppedThreadIsTheSnapshotThreadOfThatApplication() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(NAMED_APP);
        FakeDebugToolCalls.register(NAMED_APP, session);

        assertSame("the session's own stopped thread is what the run continues", session.thread(), //$NON-NLS-1$
            RunToLineTool.suspendedThread(registry, NAMED_APP));
    }

    @Test
    public void aRunningSessionHasNoStoppedThreadToRunFrom() throws Exception
    {
        FakeDebugFrames.Session running = FakeDebugFrames.session(NAMED_APP);
        running.suspended = false;

        assertNull("a running session must not be answered as if it were stopped", //$NON-NLS-1$
            RunToLineTool.suspendedThread(registry, NAMED_APP));
    }

    @Test
    public void aResumeThatFailedNamesTheBreakpointItAlreadyCreated() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(REFUSED_APP);
        long threadId = FakeDebugToolCalls.register(REFUSED_APP, session);
        session.resumeRefusal = new IllegalStateException("the client is gone"); //$NON-NLS-1$

        JsonObject answer = json(RunToLineTool.execute(
            args(REFUSED_APP, Long.valueOf(threadId)), file(), (f, line) -> breakpoint(MARKER_ID)));

        assertTrue("the call failed, which is what the answer is about", answer.has("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the caller needs the id to remove the breakpoint left behind", //$NON-NLS-1$
            MARKER_ID, answer.get("breakpointId").getAsLong()); //$NON-NLS-1$
        assertFalse("nothing was resumed", answer.get("resumed").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refused resume left the thread where it was", session.suspended); //$NON-NLS-1$
    }

    @Test
    public void aThreadOfAnotherSessionIsRefusedRatherThanResumed() throws Exception
    {
        FakeDebugFrames.Session foreign = FakeDebugFrames.session(FOREIGN_APP);
        long threadId = FakeDebugToolCalls.register(FOREIGN_APP, foreign);

        Map<String, String> params = FakeDebugToolCalls.args(
            "projectName", PROJECT, "module", MODULE, "lineNumber", "42", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "threadId", String.valueOf(threadId), "applicationId", NAMED_APP); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = json(RunToLineTool.execute(params, file(),
            (f, line) -> breakpoint(MARKER_ID)));

        assertEquals(DebugThreadOwnership.REASON, answer.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals("the session that issued the id is named", FOREIGN_APP, //$NON-NLS-1$
            answer.get("applicationId").getAsString()); //$NON-NLS-1$
        assertEquals("a foreign thread is not resumed", 0, foreign.resumeRequests); //$NON-NLS-1$
    }

    /**
     * @param applicationId the application to address
     * @param threadId the thread to name, or <code>null</code> to name none
     * @return the arguments a caller sends
     */
    private static Map<String, String> args(String applicationId, Long threadId)
    {
        Map<String, String> params = FakeDebugToolCalls.args(
            "projectName", PROJECT, "module", MODULE, "lineNumber", "42", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "applicationId", applicationId); //$NON-NLS-1$
        if (threadId != null)
        {
            params.put("threadId", threadId.toString()); //$NON-NLS-1$
        }
        return params;
    }

    /**
     * @return the module file a run starts from
     */
    private static IFile file()
    {
        IPath path = (IPath)Proxy.newProxyInstance(ARunToLineResumesTheStoppedThreadTest.class.getClassLoader(),
            new Class<?>[] { IPath.class }, (proxy, method, args) -> "toString".equals(method.getName()) //$NON-NLS-1$
                ? '/' + PROJECT + "/src/" + MODULE : null); //$NON-NLS-1$
        return (IFile)Proxy.newProxyInstance(ARunToLineResumesTheStoppedThreadTest.class.getClassLoader(),
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
            ARunToLineResumesTheStoppedThreadTest.class.getClassLoader(),
            new Class<?>[] { IResource.class }, (proxy, method, args) -> null);
        IMarker marker = (IMarker)Proxy.newProxyInstance(
            ARunToLineResumesTheStoppedThreadTest.class.getClassLoader(),
            new Class<?>[] { IMarker.class }, (proxy, method, args) ->
                "getId".equals(method.getName()) ? Long.valueOf(markerId) : resource); //$NON-NLS-1$
        return (IBreakpoint)Proxy.newProxyInstance(
            ARunToLineResumesTheStoppedThreadTest.class.getClassLoader(),
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
