/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/** Holds the YAXUnit deadline, dialog hand-over and client lifecycle without a live EDT. */
public class AYaxunitCallDeadlineAndDialogTest
{
    @Test
    public void theWholeCallOwnsOneDeadlineAndThirtySecondsBelongToStopping()
    {
        Map<String, String> stamped = YaxunitTestRunner.withCallStart(Map.of(), 1_000L);
        assertEquals(1_000L, YaxunitTestRunner.callStartedAt(stamped, () -> 99_000L));
        long pollingDeadline = YaxunitTestRunner.pollingDeadline(1_000L, 60);
        assertEquals(31_000L, pollingDeadline);
        assertEquals(YaxunitTestRunner.STOP_BUDGET_MS, 61_000L - pollingDeadline);

        JsonObject tooShort = JsonParser.parseString(new YaxunitTestRunner().execute(Map.of(
            "projectName", "Proj", "applicationId", "app", "timeoutSeconds", "59"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            .getAsJsonObject();
        assertFalse(tooShort.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(tooShort.get("error").getAsString().contains("at least 60")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aDialogHeldLaunchReturnsPendingAndTheKeyContinuesThatLaunch() throws Exception
    {
        CountDownLatch returned = new CountDownLatch(1);
        ILaunch noClient = launchWithProcesses(new IProcess[0], new AtomicBoolean());
        DebugSessionStarter.LaunchUnderWay underWay = underWay(returned, noClient);
        Map<String, Object> dialog = Map.of("title", "Update application", //$NON-NLS-1$ //$NON-NLS-2$
            "buttons", List.of("Update and launch", "Launch without update", "Cancel")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String businessKey = "dialog-" + System.nanoTime(); //$NON-NLS-1$
        long now = System.currentTimeMillis();

        JsonObject pending = JsonParser.parseString(YaxunitTestRunner.handOverLaunch(underWay,
            List.of(), List.of(dialog), Path.of("report-dir"), now, now + 1_000L, //$NON-NLS-1$
            businessKey, new YaxunitTestRunner.RunContext("Proj", null, null, null, false))) //$NON-NLS-1$
            .getAsJsonObject();
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$
        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(pending.get("blockedByDialog").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Update application", pending.getAsJsonArray("dialogs").get(0) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject().get("title").getAsString()); //$NON-NLS-1$

        returned.countDown();
        JsonObject collected = JsonParser.parseString(new YaxunitTestRunner().dispatch(
            Map.of("runKey", runKey))).getAsJsonObject(); //$NON-NLS-1$
        assertFalse(collected.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(collected.get("error").getAsString().contains("client was not launched")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Update application", collected.get("dialogTitle").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(PendingWorkRegistry.YAXUNIT_LAUNCH.get(runKey));
    }

    @Test
    public void aReturnedLaunchWithoutProcessesIsRefusedAndEvicted() throws Exception
    {
        String runKey = "empty-" + System.nanoTime(); //$NON-NLS-1$
        AtomicLong now = new AtomicLong();
        ILaunch noClient = launchWithProcesses(new IProcess[0], new AtomicBoolean());
        YaxunitTestRunner.rememberActiveLaunch(runKey, noClient);

        String answer = YaxunitTestRunner.awaitLaunchAndPoll(underWay(new CountDownLatch(0),
            noClient), List.of(), List.of(Map.of("title", "Update application")), //$NON-NLS-1$ //$NON-NLS-2$
            Path.of("report-dir"), 200L, 300L, runKey, //$NON-NLS-1$
            new YaxunitTestRunner.RunContext("Proj", null, null, null, false), //$NON-NLS-1$
            now::get, now::addAndGet);

        JsonObject refusal = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(refusal.get("clientLaunched").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Update application", refusal.get("dialogTitle").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(YaxunitTestRunner.activeLaunch(runKey));
    }

    @Test
    public void deadlineTerminatesTheLaunchAndNamesAClientThatSurvivesTheStopBudget()
        throws Exception
    {
        AtomicBoolean terminateAsked = new AtomicBoolean();
        AtomicLong now = new AtomicLong();
        IProcess process = process("4242", false); //$NON-NLS-1$
        ILaunch launch = launchWithProcesses(new IProcess[] {process}, terminateAsked);

        YaxunitTestRunner.StopResult stopped = YaxunitTestRunner.stopClient(launch, 300L,
            now::get, now::addAndGet);
        assertTrue(terminateAsked.get());
        assertTrue(stopped.clientStillRunning);
        assertEquals("4242", stopped.processId); //$NON-NLS-1$

        JsonObject refusal = JsonParser.parseString(YaxunitTestRunner.deadlineExpired(stopped))
            .getAsJsonObject();
        assertFalse(refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refusal.get("deadlineExpired").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refusal.get("clientStillRunning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("4242", refusal.get("processId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Returns a launch call controlled by a latch. */
    private static DebugSessionStarter.LaunchUnderWay underWay(CountDownLatch returned,
        ILaunch launch)
    {
        return new DebugSessionStarter.LaunchUnderWay()
        {
            @Override
            public boolean hasReturned()
            {
                return returned.getCount() == 0;
            }

            @Override
            public ILaunch launch()
            {
                return launch;
            }

            @Override
            public String error()
            {
                return null;
            }

            @Override
            public void awaitReturn() throws InterruptedException
            {
                returned.await();
            }
        };
    }

    /** Returns a fake launch with the supplied processes and termination recorder. */
    private static ILaunch launchWithProcesses(IProcess[] processes, AtomicBoolean terminateAsked)
    {
        return (ILaunch)Proxy.newProxyInstance(AYaxunitCallDeadlineAndDialogTest.class
            .getClassLoader(), new Class<?>[] {ILaunch.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "getProcesses": //$NON-NLS-1$
                    return processes;
                case "canTerminate": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "terminate": //$NON-NLS-1$
                    terminateAsked.set(true);
                    return null;
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.FALSE;
                default:
                    return zeroOf(method.getReturnType());
                }
            });
    }

    /** Returns a fake Eclipse process with a stable identifier. */
    private static IProcess process(String id, boolean terminated)
    {
        return (IProcess)Proxy.newProxyInstance(AYaxunitCallDeadlineAndDialogTest.class
            .getClassLoader(), new Class<?>[] {IProcess.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.valueOf(terminated);
                case "getAttribute": //$NON-NLS-1$
                    return IProcess.ATTR_PROCESS_ID.equals(args[0]) ? id : null;
                case "getLabel": //$NON-NLS-1$
                    return "client-" + id; //$NON-NLS-1$
                default:
                    return zeroOf(method.getReturnType());
                }
            });
    }

    /** Returns the neutral value of an unused proxy method. */
    private static Object zeroOf(Class<?> type)
    {
        if (!type.isPrimitive())
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        return null;
    }
}
