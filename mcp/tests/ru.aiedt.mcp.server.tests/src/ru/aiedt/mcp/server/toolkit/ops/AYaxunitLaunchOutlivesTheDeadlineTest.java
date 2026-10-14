/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/**
 * Holds the life of a YAXUnit launch the call's deadline does not bound: one client per
 * configuration while a launch is starting, a handed-over run tracked to its report under the
 * key the caller received, a client that appears after the polling budget still tracked, a
 * report written a moment after the client left still collected, and polls that take no heavy
 * permit.
 * <p>
 * Everything here runs on stand-in launches, a fake clock and the shared registry - no EDT, no
 * client, no infobase.
 * </p>
 */
public class AYaxunitLaunchOutlivesTheDeadlineTest
{
    /**
     * A second call of the same tests cannot start a second client while the first launch is
     * still under way, and joins that client once it is visible.
     */
    @Test
    public void aSecondCallWhileTheFirstLaunchIsStartingStartsNoSecondClient() throws Exception
    {
        String runKey = "inflight-" + System.nanoTime(); //$NON-NLS-1$
        AtomicBoolean terminated = new AtomicBoolean();
        ILaunch client = launchWithProcesses(
            new IProcess[] {process("1812", false)}, new AtomicBoolean(), terminated); //$NON-NLS-1$
        YaxunitTestRunner.publishLaunchUnderWay(runKey);
        try
        {
            assertFalse("a launch under way holds the slot of its runKey", //$NON-NLS-1$
                YaxunitTestRunner.launchSlotFree(runKey));

            AtomicLong now = new AtomicLong();
            boolean visible = YaxunitTestRunner.awaitLaunchVisible(runKey, now.get() + 10_000L,
                now::get, millis -> {
                    now.addAndGet(millis);
                    // The starter's client appeared and was tracked: the mark ends there.
                    YaxunitTestRunner.rememberActiveLaunch(runKey, client);
                });
            assertTrue("the wait ends when the first call's client becomes visible", visible); //$NON-NLS-1$
            assertSame("the second call joins the first call's client, not one of its own", //$NON-NLS-1$
                client, YaxunitTestRunner.activeLaunch(runKey));
            assertFalse("a tracked live client keeps holding the slot", //$NON-NLS-1$
                YaxunitTestRunner.launchSlotFree(runKey));
        }
        finally
        {
            YaxunitTestRunner.forgetFailedRun(runKey);
        }

        assertTrue("a failed run leaves the slot for the next call", //$NON-NLS-1$
            YaxunitTestRunner.launchSlotFree(runKey));
    }

    /**
     * A call that waits past its deadline for another call's launch says so rather than
     * launching.
     */
    @Test
    public void aWaitForAnotherCallThatOutlivesTheDeadlineRefusesByName() throws Exception
    {
        String runKey = "waitout-" + System.nanoTime(); //$NON-NLS-1$
        AtomicLong now = new AtomicLong(1_000L);
        YaxunitTestRunner.publishLaunchUnderWay(runKey);
        try
        {
            assertFalse(YaxunitTestRunner.awaitLaunchVisible(runKey, 2_000L, now::get,
                now::addAndGet));
        }
        finally
        {
            YaxunitTestRunner.forgetFailedRun(runKey);
        }

        JsonObject refusal = JsonParser.parseString(
            YaxunitTestRunner.deadlineBeforeClient("another call starting these tests")) //$NON-NLS-1$
            .getAsJsonObject();
        assertTrue(refusal.get("error").getAsString(), //$NON-NLS-1$
            refusal.get("error").getAsString().contains("another call starting these tests")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Both tools of the run mode declare the starter their handed-over runs are polled by, and
     * the registry entries carry that name: a poll of a live key is the tool's own resumption
     * path, not a fresh heavy call.
     */
    @Test
    public void pollingAHandedOverRunKeyDeclaresTheStarterOfThatRun()
    {
        assertEquals("yaxunit_tests", new YaxunitTestsTool().resumes("yaxunit_tests", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("yaxunit_tests", new YaxunitTestRunner().resumes("yaxunit_tests", "run")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(new YaxunitTestsTool().resumes("debug_launch", "launch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(new YaxunitTestRunner().resumes("generic_tool", null)); //$NON-NLS-1$ //$NON-NLS-2$

        // The entry a handed-over launch files is resumable by exactly that declaration.
        CountDownLatch returned = new CountDownLatch(1);
        ILaunch noClient = launchWithProcesses(new IProcess[0], new AtomicBoolean(),
            new AtomicBoolean());
        JsonObject pending = JsonParser.parseString(YaxunitTestRunner.handOverLaunch(
            underWay(returned, noClient), List.of(), List.of(), Path.of("report-dir"), //$NON-NLS-1$
            "starter-" + System.nanoTime(), //$NON-NLS-1$
            new YaxunitTestRunner.RunContext("Proj", null, null, null, false))) //$NON-NLS-1$
            .getAsJsonObject();
        PendingWorkRegistry.PendingEntry entry =
            PendingWorkRegistry.YAXUNIT_LAUNCH.get(pending.get("runKey").getAsString()); //$NON-NLS-1$
        assertTrue(entry.resumableBy(new YaxunitTestsTool().resumes("yaxunit_tests", null))); //$NON-NLS-1$
        assertFalse(entry.resumableBy("debug_yaxunit_tests")); //$NON-NLS-1$
        // Let the parked continuation finish so the registry's worker thread is released.
        returned.countDown();
    }

    /**
     * The continuation of a launch handed over past the deadline of the call that started it
     * still waits out the dialog, runs the client to its end and answers its report under the
     * key the caller received - never a deadline refusal and never a second key.
     */
    @Test
    public void aHandedOverLaunchAnswersItsDialogPastTheDeadlineUnderOneKey() throws Exception
    {
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean terminated = new AtomicBoolean(true);
        ILaunch launch = launchWithProcesses(
            new IProcess[] {process("4242", false)}, new AtomicBoolean(), terminated); //$NON-NLS-1$
        String businessKey = "handed-" + System.nanoTime(); //$NON-NLS-1$
        Path reportDir = Files.createTempDirectory("yaxunit-handed"); //$NON-NLS-1$
        YaxunitTestRunner.noteUndelivered(businessKey);
        try
        {
            writePassingReport(reportDir);
            JsonObject pending = JsonParser.parseString(YaxunitTestRunner.handOverLaunch(
                underWay(returned, launch), List.of(), List.of(), reportDir, businessKey,
                new YaxunitTestRunner.RunContext("Proj", null, null, null, false))) //$NON-NLS-1$
                .getAsJsonObject();
            String pendingKey = pending.get("runKey").getAsString(); //$NON-NLS-1$

            returned.countDown();
            JsonObject collected = pollUntilAnswered(pendingKey);
            assertTrue("the run answers its report, not a deadline refusal: " + collected, //$NON-NLS-1$
                collected.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(1, collected.get("total").getAsInt()); //$NON-NLS-1$

            JsonObject again = JsonParser.parseString(new YaxunitTestRunner().dispatch(
                Map.of("runKey", pendingKey))).getAsJsonObject(); //$NON-NLS-1$
            assertFalse("a delivered key says so instead of a stale snapshot", //$NON-NLS-1$
                again.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(again.get("error").getAsString().contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            YaxunitTestRunner.forgetUndelivered(businessKey);
            deleteTree(reportDir);
        }
    }

    /**
     * A client that appears after the polling budget closed - the dialog was answered in the
     * last seconds of the call - is still tracked and answered Pending, not refused as a launch
     * that produced no client.
     */
    @Test
    public void aClientAppearingAfterThePollingDeadlineIsTrackedAndAnsweredPending()
        throws Exception
    {
        AtomicLong now = new AtomicLong();
        AtomicReference<IProcess[]> processes = new AtomicReference<>(new IProcess[0]);
        ILaunch launch = launchReadingProcesses(processes, new AtomicBoolean());
        String runKey = "lateclient-" + System.nanoTime(); //$NON-NLS-1$

        String answer = YaxunitTestRunner.awaitLaunchAndPoll(
            underWay(new CountDownLatch(0), launch), List.of(), List.of(),
            Path.of("report-dir"), 0L, runKey, //$NON-NLS-1$
            new YaxunitTestRunner.RunContext("Proj", null, null, null, false), //$NON-NLS-1$
            now::get, millis -> {
                now.addAndGet(millis);
                if (now.get() >= 500)
                {
                    processes.set(new IProcess[] {process("77", false)}); //$NON-NLS-1$
                }
            });

        JsonObject pending = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(pending.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(pending.get("clientStillRunning").getAsBoolean()); //$NON-NLS-1$
        assertSame("the late client is tracked under its runKey", launch, //$NON-NLS-1$
            YaxunitTestRunner.activeLaunch(runKey));
        YaxunitTestRunner.forgetFailedRun(runKey);
    }

    /**
     * A report written a moment after the client left is still collected by the key the Pending
     * answer named, because the pickup gives the file the same short window the client's start
     * gets.
     */
    @Test
    public void aReportWrittenJustAfterTheClientLeftIsStillCollected() throws Exception
    {
        AtomicBoolean terminated = new AtomicBoolean(true);
        ILaunch launch = launchWithProcesses(
            new IProcess[] {process("5", true)}, new AtomicBoolean(), terminated); //$NON-NLS-1$
        String runKey = "latereport-" + System.nanoTime(); //$NON-NLS-1$
        Path reportDir = Files.createTempDirectory("yaxunit-late"); //$NON-NLS-1$
        AtomicLong now = new AtomicLong();
        try
        {
            // The in-call poll answered Pending at its deadline with the launch already leaving:
            // the maps the pickup reads are in place and the report is not on disk yet.
            String pending = YaxunitTestRunner.pollLaunch(launch, reportDir, 1L, runKey,
                new YaxunitTestRunner.RunContext("Proj", null, null, null, false), //$NON-NLS-1$
                now::get, now::addAndGet);
            assertTrue(pending, pending.contains("No JUnit XML report was found")); //$NON-NLS-1$

            AtomicBoolean wrote = new AtomicBoolean();
            String answer = YaxunitTestRunner.resumeRun(runKey, 1L, now::get, millis -> {
                now.addAndGet(millis);
                if (!wrote.getAndSet(true))
                {
                    try
                    {
                        writePassingReport(reportDir);
                    }
                    catch (Exception writeFailed)
                    {
                        throw new IllegalStateException(writeFailed);
                    }
                }
            });

            JsonObject collected = JsonParser.parseString(answer).getAsJsonObject();
            assertTrue("the late report is collected, not refused: " + collected, //$NON-NLS-1$
                collected.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(1, collected.get("total").getAsInt()); //$NON-NLS-1$
        }
        finally
        {
            YaxunitTestRunner.forgetFailedRun(runKey);
            deleteTree(reportDir);
        }
    }

    /**
     * A deadline the engine install consumed answers an error naming that step and launches
     * nothing, rather than passing the remains of the call to the runner.
     */
    @Test
    public void aDeadlineSpentOnTheEngineInstallNamesTheStepAndLaunchesNothing()
    {
        Map<String, String> params = YaxunitTestRunner.withCallStart(Map.of(
            "projectName", "Proj", "applicationId", "app", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "installYaxunit", "true", "timeoutSeconds", "5"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            System.currentTimeMillis() - 60_000L);
        YaxunitTestsTool.InstallSteps steps = installedEngineSteps();

        JsonObject refusal = JsonParser.parseString(
            new YaxunitTestsTool().executeWithInstallSteps(params, steps)).getAsJsonObject();

        assertFalse(refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the refusal names the step the time went to: " + refusal, //$NON-NLS-1$
            refusal.get("error").getAsString().contains("engine install")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refusal.get("clientLaunched").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * A refusal that knows its step names it: the update that consumed the budget is told apart
     * from a bare deadline.
     */
    @Test
    public void aDeadlineRefusalNamesTheStepWhenItKnowsOne()
    {
        JsonObject named = JsonParser.parseString(
            YaxunitTestRunner.deadlineBeforeClient("the pre-launch infobase update")) //$NON-NLS-1$
            .getAsJsonObject();
        assertTrue(named.get("error").getAsString(), //$NON-NLS-1$
            named.get("error").getAsString().contains("pre-launch infobase update")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(named.get("deadlineExpired").getAsBoolean()); //$NON-NLS-1$

        JsonObject bare = JsonParser.parseString(YaxunitTestRunner.deadlineBeforeClient(null))
            .getAsJsonObject();
        assertTrue(bare.get("error").getAsString().endsWith("launched.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Install steps of an engine that is already in place with both flags already off. */
    private static YaxunitTestsTool.InstallSteps installedEngineSteps()
    {
        return new YaxunitTestsTool.InstallSteps()
        {
            @Override
            public ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ListResult listExtensions(
                String projectName, String applicationId)
            {
                ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ListResult listed =
                    new ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ListResult();
                listed.ok = true;
                listed.extensions = List.of("YAxUnit"); //$NON-NLS-1$
                return listed;
            }

            @Override
            public YaxunitTestsTool.InstallOutcome installEngine(String projectName,
                String applicationId, String repo)
            {
                return YaxunitTestsTool.InstallOutcome.done("nothing to install"); //$NON-NLS-1$
            }

            @Override
            public ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlagsResult lowerEngineFlags(
                String projectName, String applicationId)
            {
                ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlags flags =
                    new ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlags();
                flags.found = true;
                flags.safeMode = Boolean.FALSE;
                flags.unsafeActionProtection = Boolean.FALSE;
                ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlagsResult result =
                    new ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlagsResult();
                result.ok = true;
                result.flags = flags;
                return result;
            }
        };
    }

    /**
     * Polls a handed-over run until its continuation answers.
     *
     * @param runKey the key the Pending envelope named
     * @return the answered JSON, never a Pending one
     * @throws InterruptedException when the polling is interrupted
     */
    private static JsonObject pollUntilAnswered(String runKey) throws InterruptedException
    {
        YaxunitTestRunner runner = new YaxunitTestRunner();
        for (int attempt = 0; attempt < 40; attempt++)
        {
            JsonObject answer = JsonParser.parseString(runner.dispatch(Map.of("runKey", runKey))) //$NON-NLS-1$
                .getAsJsonObject();
            if (answer.get("status") == null || !"Pending".equals(answer.get("status").getAsString())) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return answer;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the handed-over run never answered under " + runKey); //$NON-NLS-1$
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

    /** Returns a fake launch with fixed processes and a termination recorder. */
    private static ILaunch launchWithProcesses(IProcess[] processes, AtomicBoolean terminateAsked,
        AtomicBoolean terminated)
    {
        return (ILaunch)Proxy.newProxyInstance(AYaxunitLaunchOutlivesTheDeadlineTest.class
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
                    return Boolean.valueOf(terminated.get());
                default:
                    return zeroOf(method.getReturnType());
                }
            });
    }

    /** Returns a fake launch whose processes appear when the holder puts them there. */
    private static ILaunch launchReadingProcesses(AtomicReference<IProcess[]> processes,
        AtomicBoolean terminated)
    {
        return (ILaunch)Proxy.newProxyInstance(AYaxunitLaunchOutlivesTheDeadlineTest.class
            .getClassLoader(), new Class<?>[] {ILaunch.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "getProcesses": //$NON-NLS-1$
                    return processes.get();
                case "canTerminate": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.valueOf(terminated.get());
                default:
                    return zeroOf(method.getReturnType());
                }
            });
    }

    /** Writes the report collected by the repeat call. */
    private static void writePassingReport(Path directory) throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //$NON-NLS-1$
            + "<testsuite name=\"Run\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">" //$NON-NLS-1$
            + "<testcase name=\"passes\" classname=\"Module\"/>" //$NON-NLS-1$
            + "</testsuite>"; //$NON-NLS-1$
        Files.write(directory.resolve("junit.xml"), xml.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
    }

    /** Returns a fake Eclipse process with a stable identifier. */
    private static IProcess process(String id, boolean terminated)
    {
        return (IProcess)Proxy.newProxyInstance(AYaxunitLaunchOutlivesTheDeadlineTest.class
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

    /** Deletes a temporary tree. */
    private static void deleteTree(Path root) throws Exception
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
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
