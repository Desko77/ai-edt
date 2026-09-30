/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * A launch held open by a modal question answers a Pending envelope instead of parking the caller
 * inside the launch.
 *
 * <p>The wait itself reads the workbench's shells, which a headless test cannot show; the decision
 * - keep waiting, answer the launch, answer the dialog, answer the window - is taken from plain
 * values, and that decision plus the envelope and the {@code runKey} cycle is what these tests
 * hold. That a real dialog holds a real launch open is measured on the stand.</p>
 */
public class ALaunchHeldByAQuestionAnswersPendingTest
{
    /**
     * The answer a launch call hands to its continuation: the refusal shape both launch paths
     * answer with, so the tests below read what a collected key says the way a caller does.
     */
    private static final Function<DebugSessionStarter.LaunchOutcome, String> THE_CALLS_ANSWER =
        settled -> settled.started
            ? ToolResult.success()
                .put("launchConfiguration", "the-config") //$NON-NLS-1$ //$NON-NLS-2$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson()
            : ToolResult.error("Could not launch the debug session: " + settled.refusal) //$NON-NLS-1$
                .put("observed", settled.observed) //$NON-NLS-1$
                .toJson();

    @Test
    public void aLaunchThatReturnedIsAnsweredAsItAlwaysWas()
    {
        assertEquals(DebugSessionStarter.LaunchWaitChoice.ANSWER_THE_LAUNCH,
            DebugSessionStarter.decideLaunchWait(true, false, false));
        assertEquals("a returned launch is answered even with a dialog up and the window over",
            DebugSessionStarter.LaunchWaitChoice.ANSWER_THE_LAUNCH,
            DebugSessionStarter.decideLaunchWait(true, true, true));
    }

    @Test
    public void aQuestionThatOpenedSinceTheLaunchIsAnsweredAtOnce()
    {
        assertEquals(DebugSessionStarter.LaunchWaitChoice.ANSWER_BLOCKED_BY_DIALOG,
            DebugSessionStarter.decideLaunchWait(false, false, true));
        assertEquals("a question is answered even when the wait window is over",
            DebugSessionStarter.LaunchWaitChoice.ANSWER_BLOCKED_BY_DIALOG,
            DebugSessionStarter.decideLaunchWait(false, true, true));
    }

    @Test
    public void aQuietLaunchIsWaitedOnAndThenHandedOver()
    {
        assertEquals(DebugSessionStarter.LaunchWaitChoice.KEEP_WAITING,
            DebugSessionStarter.decideLaunchWait(false, false, false));
        assertEquals(DebugSessionStarter.LaunchWaitChoice.ANSWER_PENDING,
            DebugSessionStarter.decideLaunchWait(false, true, false));
    }

    @Test
    public void aHandedOverLaunchAnswersPendingAndItsKeyCollectsTheOutcome()
    {
        CountDownLatch returned = new CountDownLatch(1);
        ILaunch[] launched = {aLaunchThatTerminatedAtOnce()};
        DebugSessionStarter.LaunchUnderWay underWay = new DebugSessionStarter.LaunchUnderWay()
        {
            @Override
            public boolean hasReturned()
            {
                return returned.getCount() == 0;
            }

            @Override
            public ILaunch launch()
            {
                return launched[0];
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

        JsonObject pending = FakeDebugToolCalls
            .json(DebugSessionStarter.pendingLaunchAnswer(underWay, false, List.of(), null,
                THE_CALLS_ANSWER));
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$

        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$
        assertEquals("the envelope is the one the router turns into a task", true, //$NON-NLS-1$
            pending.get("pendingEnvelope").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the answer says how to collect the outcome: " + pending, //$NON-NLS-1$
            pending.get("hint").getAsString().contains(runKey)); //$NON-NLS-1$

        // The launch comes back after the hand-over; the key collects what it came to.
        returned.countDown();
        JsonObject collected = FakeDebugToolCalls.json(new DebugSessionStarter()
            .execute(Map.of("runKey", runKey))); //$NON-NLS-1$

        assertEquals(false, collected.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the collected answer says what the launch came to: " + collected, //$NON-NLS-1$
            collected.get("error").getAsString().contains("terminated straight away")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a collected key is not held by the registry any more", null, //$NON-NLS-1$
            PendingWorkRegistry.DEBUG_LAUNCH.get(runKey));
    }

    @Test
    public void aHandedOverLaunchHoldsItsApplicationUntilItSettles() throws Exception
    {
        CountDownLatch returned = new CountDownLatch(1);
        ILaunch[] launched = {aLaunchThatTerminatedAtOnce()};
        DebugSessionStarter.LaunchUnderWay underWay = aLaunchUnderWay(launched, returned);

        JsonObject pending = FakeDebugToolCalls.json(
            DebugSessionStarter.pendingLaunchAnswer(underWay, false, List.of(), "app-under-test", //$NON-NLS-1$
                THE_CALLS_ANSWER));
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$

        assertEquals("the application is held while its launch is in flight", runKey, //$NON-NLS-1$
            DebugSessionStarter.inFlightLaunchRunKey("app-under-test")); //$NON-NLS-1$
        assertEquals("another application is not held by it", null, //$NON-NLS-1$
            DebugSessionStarter.inFlightLaunchRunKey("another-app")); //$NON-NLS-1$

        returned.countDown();
        JsonObject collected = FakeDebugToolCalls.json(new DebugSessionStarter()
            .execute(Map.of("runKey", runKey))); //$NON-NLS-1$
        assertEquals(false, collected.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the application is free again once the launch settled", null, //$NON-NLS-1$
            DebugSessionStarter.inFlightLaunchRunKey("app-under-test")); //$NON-NLS-1$
    }

    @Test
    public void aHandedOverLaunchAnswersWhatTheCallWouldHaveAnswered() throws Exception
    {
        CountDownLatch returned = new CountDownLatch(1);
        ILaunch[] launched = {aLaunchWhoseTargetIsLive()};
        DebugSessionStarter.LaunchUnderWay underWay = aLaunchUnderWay(launched, returned);
        AtomicBoolean answerRan = new AtomicBoolean(false);

        JsonObject pending = FakeDebugToolCalls.json(DebugSessionStarter.pendingLaunchAnswer(
            underWay, false, List.of(), "app-under-test", settled ->
            {
                answerRan.set(true);
                if (!settled.started)
                {
                    return ToolResult
                        .error("Could not launch the debug session: " + settled.refusal) //$NON-NLS-1$
                        .put("observed", settled.observed) //$NON-NLS-1$
                        .toJson();
                }
                // The shape the launch paths answer with, the endpoint wait included: a caller
                // that asked for waitForEndpoint reads its answer here, not a bare done.
                return ToolResult.success()
                    .put("launchConfiguration", "the-config") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("endpointReady", Boolean.TRUE) //$NON-NLS-1$
                    .put("endpointHttpStatus", Integer.valueOf(200)) //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }));
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$

        assertEquals("the application is held while its launch is in flight", runKey, //$NON-NLS-1$
            DebugSessionStarter.inFlightLaunchRunKey("app-under-test")); //$NON-NLS-1$

        returned.countDown();
        JsonObject collected = FakeDebugToolCalls.json(new DebugSessionStarter()
            .execute(Map.of("runKey", runKey))); //$NON-NLS-1$

        assertEquals("the collected answer is the one the call would have given", true, //$NON-NLS-1$
            collected.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the continuation ran the call's own answer, not a generic done", true, //$NON-NLS-1$
            answerRan.get());
        assertEquals("the endpoint wait the call asked for is part of the collected answer", true, //$NON-NLS-1$
            collected.get("endpointReady").getAsBoolean()); //$NON-NLS-1$
        assertEquals(200, collected.get("endpointHttpStatus").getAsInt()); //$NON-NLS-1$
        assertFalse("no bare done status is answered any more", collected.has("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the application is free again once the answer was built", null, //$NON-NLS-1$
            DebugSessionStarter.inFlightLaunchRunKey("app-under-test")); //$NON-NLS-1$
    }

    @Test
    public void theInFlightRefusalNamesTheKeyToPoll()
    {
        JsonObject answer = FakeDebugToolCalls.json(
            DebugSessionStarter.launchInFlightAnswer("app-under-test", "the-run-key")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(false, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(true, answer.get("launchInFlight").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the-run-key", answer.get("runKey").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the answer says how to collect the outcome: " + answer, //$NON-NLS-1$
            answer.get("hint").getAsString().contains("the-run-key")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(true, answer.get("nothingWasLaunchedOrUpdated").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * @param launched where the launch lands once it returns
     * @param returned the latch its return counts down
     * @return a launch that has not returned until the latch opens
     */
    private static DebugSessionStarter.LaunchUnderWay aLaunchUnderWay(ILaunch[] launched,
        CountDownLatch returned)
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
                return launched[0];
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

    /**
     * @return a launch whose reading settles the watch as started at once, so the collected
     *         answer runs the call's own success answer
     */
    private static ILaunch aLaunchWhoseTargetIsLive()
    {
        IDebugTarget target = (IDebugTarget)Proxy.newProxyInstance(
            ALaunchHeldByAQuestionAnswersPendingTest.class.getClassLoader(),
            new Class<?>[] { IDebugTarget.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.FALSE;
                case "toString": //$NON-NLS-1$
                    return "a live debug target"; //$NON-NLS-1$
                default:
                    return zeroOf(method.getReturnType());
                }
            });
        return (ILaunch)Proxy.newProxyInstance(ALaunchHeldByAQuestionAnswersPendingTest.class
            .getClassLoader(), new Class<?>[] { ILaunch.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.FALSE;
                case "getDebugTargets": //$NON-NLS-1$
                    return new IDebugTarget[] { target };
                case "toString": //$NON-NLS-1$
                    return "a launch with a live target"; //$NON-NLS-1$
                default:
                    return zeroOf(method.getReturnType());
                }
            });
    }

    /**
     * @return a launch whose reading settles the watch at once, so the collected answer needs no
     *         wait
     */
    private static ILaunch aLaunchThatTerminatedAtOnce()
    {
        return (ILaunch)Proxy.newProxyInstance(ALaunchHeldByAQuestionAnswersPendingTest.class
            .getClassLoader(), new Class<?>[] { ILaunch.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "isTerminated": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "getDebugTargets": //$NON-NLS-1$
                    return new org.eclipse.debug.core.model.IDebugTarget[0];
                case "toString": //$NON-NLS-1$
                    return "a launch that terminated at once"; //$NON-NLS-1$
                default:
                    return zeroOf(method.getReturnType());
                }
            });
    }

    /**
     * @param type a return type of a method the fake does not care about
     * @return nothing, of the right shape
     */
    private static Object zeroOf(Class<?> type)
    {
        if (type.isArray())
        {
            return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        }
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
