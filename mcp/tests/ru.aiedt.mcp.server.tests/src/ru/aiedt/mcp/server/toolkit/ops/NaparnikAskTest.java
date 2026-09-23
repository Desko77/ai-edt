/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.naparnik.BundleCopy;
import ru.aiedt.mcp.server.support.naparnik.NaparnikAccessException;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost.Question;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost.RunningQuestion;
import ru.aiedt.mcp.server.toolkit.ops.NaparnikTool.AskControls;
import ru.aiedt.mcp.server.toolkit.ops.NaparnikTool.ProjectDoor;

/**
 * {@code ask} against a fake that behaves the way 1.0.7 was measured.
 * <p>
 * The future completes on another thread. A non-positive {@code maxToolRounds} is no limit. An
 * unknown name in {@code allowedTools} fails with the measured text. The token is polled before
 * each scripted call. An empty allowed set does not lift the filter: nothing from the published
 * list stays defined. Cancelling the token completes the future normally, with the calls that
 * already started, and does not run the next one. The tool's own timeout test records the wait it
 * asked for and returns at once, so the suite does not sleep for the thirty-second floor.
 * </p>
 */
public class NaparnikAskTest
{
    private static final String VERSION = "1.0.7.v202608311234"; //$NON-NLS-1$

    private FakeHost host;

    private Door door;

    private AtomicBoolean bridge;

    private AtomicBoolean allTools;

    private AtomicBoolean callable;

    private NaparnikTool tool;

    @Before
    public void installed()
    {
        host = new FakeHost();
        install(VERSION, "RESOLVED"); //$NON-NLS-1$
        host.tools = new ArrayList<>(NaparnikTool.ALLOWED_TOOLS);
        host.tools.add("Execute"); //$NON-NLS-1$
        host.script = List.of("Read"); //$NON-NLS-1$
        door = new Door();
        bridge = new AtomicBoolean(true);
        allTools = new AtomicBoolean(false);
        callable = new AtomicBoolean(true);
        tool = wired(bridge::get, allTools::get, callable::get);
    }

    @After
    public void releaseQuestions()
        throws InterruptedException
    {
        if (host != null)
        {
            host.release = true;
        }
        List<String> keys = new ArrayList<>(PendingWorkRegistry.NAPARNIK.unfinishedKeys());
        for (String key : keys)
        {
            PendingWorkRegistry.NAPARNIK.cancel(key);
        }
        if (!keys.isEmpty())
        {
            Thread.sleep(300L);
        }
    }

    @Test
    public void theBridgeOffIsRefusedBeforeAnyRequest()
    {
        bridge.set(false);

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("mcpNaparnikBridgeEnabled")); //$NON-NLS-1$
        assertTrue(error, error.contains("off")); //$NON-NLS-1$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aReadOnlyPresetRefusesAskInReadMode()
    {
        callable.set(false);

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("disabled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aReadOnlyPresetRefusesAskWhenTheFullSetIsOn()
    {
        callable.set(false);
        allTools.set(true);

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("disabled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void readOnlyPresetsStillNameNaparnik()
    {
        assertTrue(ToolProfile.READ_ONLY.getDisabledTools().contains("naparnik")); //$NON-NLS-1$
        assertTrue(ToolProfile.DEBUG_AND_TEST.getDisabledTools().contains("naparnik")); //$NON-NLS-1$
        assertTrue(ToolProfile.CODE_REVIEW.getDisabledTools().contains("naparnik")); //$NON-NLS-1$
    }

    @Test
    public void anAbsentInstallationIsRefused()
    {
        host.copies.clear();

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("not installed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void anUnresolvedInstallationIsRefused()
    {
        install(VERSION, "INSTALLED"); //$NON-NLS-1$

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("not resolved")); //$NON-NLS-1$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aVersionOutsideThePolicyIsRefused()
    {
        install("1.0.5", "RESOLVED"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("1.0.5")); //$NON-NLS-1$
        assertTrue(error, error.contains("1.0.7")); //$NON-NLS-1$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aMissingProjectIsRefused()
    {
        JsonObject doc = ask("projectName", "Missing"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("Project not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aClosedProjectIsRefused()
    {
        JsonObject doc = ask("projectName", "Closed"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("exists but is closed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aMissingProjectNameIsRefused()
    {
        JsonObject doc = ask("projectName", null); //$NON-NLS-1$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("projectName is required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEmptyQuestionIsRefused()
    {
        JsonObject doc = ask("question", "   "); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("question is empty")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aQuestionPastTheLimitIsRefused()
    {
        JsonObject doc = ask("question", "x".repeat(20001)); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("longer than 20000")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void maxToolRoundsOutsideTheRangeIsRefused()
    {
        JsonObject low = ask("maxToolRounds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject high = ask("maxToolRounds", "31"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(low.get("error").getAsString().contains("maxToolRounds must be from 1 to 30")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(high.get("error").getAsString().contains("maxToolRounds must be from 1 to 30")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void timeoutSecondsOutsideTheRangeIsRefused()
    {
        JsonObject low = ask("timeoutSeconds", "29"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject high = ask("timeoutSeconds", "1801"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(low.get("error").getAsString().contains("timeoutSeconds must be from 30 to 1800")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(high.get("error").getAsString().contains("timeoutSeconds must be from 30 to 1800")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void waitSecondsOutsideTheRangeIsRefused()
    {
        JsonObject low = ask("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject high = ask("waitSeconds", "121"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(low.get("error").getAsString().contains("waitSeconds must be from 1 to 120")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(high.get("error").getAsString().contains("waitSeconds must be from 1 to 120")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void replyToWithoutAConversationIsRefused()
    {
        JsonObject doc = ask("replyTo", "msg-1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("replyTo requires conversationId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void cancelWithoutARunKeyIsRefused()
    {
        JsonObject doc = ask("cancel", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("cancel requires runKey")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void anUnknownRunKeyIsRefused()
    {
        JsonObject doc = ask("runKey", "naparnik-missing"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("runKey not found: naparnik-missing")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSecondQuestionNamesTheOneAlreadyRunning()
    {
        host.stall = true;

        JsonObject first = ask("waitSeconds", "1", "timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String runKey = first.get("runKey").getAsString(); //$NON-NLS-1$
        JsonObject second = ask();

        assertEquals("Pending", first.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(second.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = second.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("already in progress")); //$NON-NLS-1$
        assertTrue(error, error.contains(runKey));
        assertEquals(1, host.sent.size());
    }

    @Test
    public void theRequestAllowsTheIntersectionAndNothingElse()
    {
        JsonObject doc = ask();

        assertTrue(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(NaparnikTool.ALLOWED_TOOLS, strings(doc, "allowedTools")); //$NON-NLS-1$
        assertEquals("read", doc.get("toolPolicy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> sent = new ArrayList<>(host.sent.get(0).allowedTools());
        assertEquals(NaparnikTool.ALLOWED_TOOLS, sent);
        assertFalse(sent.contains("Execute")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyIntersectionIsRefusedAndNotSent()
    {
        host.tools = List.of("Execute", "Write"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("empty")); //$NON-NLS-1$
        assertTrue(error, error.contains("not sent")); //$NON-NLS-1$
        assertTrue(error, error.contains("mcpNaparnikAllToolsEnabled")); //$NON-NLS-1$
        assertTrue(host.sent.isEmpty());
    }

    @Test
    public void aToolOutsideTheReadSetCancelsAndNamesIt()
    {
        host.script = List.of("Read", "Execute", "Write"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("Execute")); //$NON-NLS-1$
        assertTrue(error, error.contains("outside the read set")); //$NON-NLS-1$
        assertEquals(List.of("Read", "Execute"), strings(doc, "toolsCalled")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(host.cancels >= 1);
    }

    @Test
    public void maxToolRoundsInTheRequestIsAlwaysPositive()
    {
        ask();
        ask("maxToolRounds", "30"); //$NON-NLS-1$ //$NON-NLS-2$
        ask("maxToolRounds", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(10, host.sent.get(0).maxToolRounds());
        assertEquals(30, host.sent.get(1).maxToolRounds());
        assertEquals(1, host.sent.get(2).maxToolRounds());
        for (Question question : host.sent)
        {
            assertTrue(question.maxToolRounds() >= 1);
            assertTrue(question.maxToolRounds() <= 30);
        }
    }

    @Test
    public void theRoundLimitNamesTheCeiling()
    {
        host.script = List.of("Read", "Glob", "List"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject doc = ask("maxToolRounds", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("tool round limit exhausted, maxToolRounds=1")); //$NON-NLS-1$
        assertTrue(error, error.contains("Too many tool rounds")); //$NON-NLS-1$
        assertEquals(List.of("Read"), strings(doc, "toolsCalled")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theOverallTimeoutCancelsAndNamesTheTools()
    {
        host.stall = true;
        host.elapseImmediately = true;
        host.script = List.of("Read", "Glob"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject doc = ask("timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("timed out after 30s")); //$NON-NLS-1$
        assertTrue(error, error.contains("ms")); //$NON-NLS-1$
        assertTrue(error, error.contains("Read")); //$NON-NLS-1$
        assertTrue(error, error.contains("Glob")); //$NON-NLS-1$
        assertEquals(List.of("Read", "Glob"), strings(doc, "toolsCalled")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(host.waits.contains(Long.valueOf(30000L)));
        assertTrue(host.waits.contains(Long.valueOf(2000L)));
        assertTrue(host.cancels >= 1);
    }

    @Test
    public void cancelStopsTheQuestion()
    {
        host.stall = true;

        JsonObject pending = ask("waitSeconds", "1", "timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$
        JsonObject stopped = ask("runKey", runKey, "cancel", "true", "waitSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(stopped.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(stopped.get("error").getAsString().contains("cancelled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(host.cancels >= 1);
    }

    /**
     * {@code tasks/cancel} goes through the registry, which only stops work the domain has named.
     * Raising nothing leaves the question, and any tool it is running, going.
     */
    @Test
    public void aRegistryCancelReachesTheRunningQuestion()
        throws InterruptedException
    {
        host.stall = true;

        JsonObject pending = ask("waitSeconds", "1", "timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$
        assertEquals(0, host.cancels);

        PendingWorkRegistry.StopOutcome outcome = PendingWorkRegistry.NAPARNIK.cancelAndStop(runKey);

        assertTrue("the registry has to ask the question to stop, not only drop the entry", //$NON-NLS-1$
            outcome != PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP);
        long deadline = System.currentTimeMillis() + 3000L;
        while (host.cancels < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
        assertTrue("cancel through the registry must reach the question", host.cancels >= 1); //$NON-NLS-1$
    }

    /**
     * {@code notifications/cancelled} only raises the flag the entry captured. The wait has to
     * notice it and cancel the question; the question does not see that flag on its own.
     */
    @Test
    public void aRaisedCancellationFlagReachesTheRunningQuestion()
        throws InterruptedException
    {
        host.stall = true;
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        JsonObject pending;
        try
        {
            pending = ask("waitSeconds", "1", "timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        finally
        {
            ToolCallScope.exit();
        }
        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, host.cancels);

        flag.cancel("notifications/cancelled"); //$NON-NLS-1$
        long deadline = System.currentTimeMillis() + 3000L;
        while (host.cancels < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
        assertTrue("the flag raised on the registry entry must reach the question", //$NON-NLS-1$
            host.cancels >= 1);

        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$
        JsonObject stopped = ask("runKey", runKey, "waitSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(stopped.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(stopped.get("error").getAsString(), //$NON-NLS-1$
            stopped.get("error").getAsString().contains("cancelled")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A timeout that asks the question to stop, and a future that keeps running anyway, must keep
     * the slot until that future actually finishes. The next question is refused until then.
     */
    @Test
    public void aFutureThatIgnoresCancelHoldsTheSlotUntilItStops()
        throws InterruptedException
    {
        host.stall = true;
        host.elapseImmediately = true;
        host.surviveCancel = true;

        JsonObject first = ask("waitSeconds", "1", "timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("cancel was asked and the future kept going", host.cancels >= 1); //$NON-NLS-1$

        JsonObject second = ask();

        assertFalse(second.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = second.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("already in progress")); //$NON-NLS-1$
        assertTrue(error, error.contains(first.get("runKey").getAsString())); //$NON-NLS-1$
        assertEquals(1, host.sent.size());

        String runKey = first.get("runKey").getAsString(); //$NON-NLS-1$
        host.release = true;
        long deadline = System.currentTimeMillis() + 5000L;
        while (PendingWorkRegistry.NAPARNIK.unfinishedKeys().contains(runKey)
            && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
        assertFalse(PendingWorkRegistry.NAPARNIK.unfinishedKeys().contains(runKey));
        host.stall = false;
        host.elapseImmediately = false;
        host.surviveCancel = false;
        host.release = false;

        JsonObject third = ask();

        assertTrue(third.toString(), third.get("success").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void aPresetSwitchedDuringTheQuestionCancelsTheNextCall()
    {
        host.script = List.of("Read", "SearchText"); //$NON-NLS-1$ //$NON-NLS-2$
        host.beforeTool = name -> {
            if ("SearchText".equals(name)) //$NON-NLS-1$
            {
                callable.set(false);
            }
        };

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("disabled")); //$NON-NLS-1$
        assertTrue(error, error.contains("cancelled")); //$NON-NLS-1$
        assertEquals(List.of("Read", "SearchText"), strings(doc, "toolsCalled")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theBridgeSwitchedOffDuringTheQuestionCancels()
    {
        host.script = List.of("Read", "Glob"); //$NON-NLS-1$ //$NON-NLS-2$
        host.beforeTool = name -> {
            if ("Glob".equals(name)) //$NON-NLS-1$
            {
                bridge.set(false);
            }
        };

        JsonObject doc = ask();

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = doc.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("mcpNaparnikBridgeEnabled")); //$NON-NLS-1$
        assertTrue(error, error.contains("cancelled")); //$NON-NLS-1$
    }

    @Test
    public void twoIdenticalQuestionsGetTwoRunKeys()
    {
        JsonObject first = ask("question", "same question"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject second = ask("question", "same question"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(first.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(second.get("success").getAsBoolean()); //$NON-NLS-1$
        assertNotEquals(first.get("runKey").getAsString(), second.get("runKey").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(host.sent.get(0).text(), host.sent.get(1).text());
    }

    @Test
    public void aNewQuestionStartsAConversation()
    {
        JsonObject doc = ask();
        Question question = host.sent.get(0);

        assertTrue(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(question.forceNew());
        assertNull(question.conversationId());
        assertNull(question.replyTo());
        assertEquals("custom", question.skillName()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, question.chat());
        assertEquals("conv-created", doc.get("conversationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, doc.get("assistantMessages").getAsInt()); //$NON-NLS-1$
        assertEquals("the answer", doc.get("answer").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(doc.has("reasoning")); //$NON-NLS-1$
        assertFalse(doc.toString().contains("secret-reasoning")); //$NON-NLS-1$
    }

    @Test
    public void aContinuationCarriesTheConversationAndTheMessage()
    {
        JsonObject doc = ask("conversationId", "conv-1", "replyTo", "msg-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Question question = host.sent.get(0);

        assertFalse(question.forceNew());
        assertEquals("conv-1", question.conversationId()); //$NON-NLS-1$
        assertEquals("msg-1", question.replyTo()); //$NON-NLS-1$
        assertEquals("custom", question.skillName()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, question.chat());
        assertEquals("conv-1", doc.get("conversationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("msg-1", doc.get("replyTo").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theFullSetIsOffByDefault()
    {
        assertFalse(PrefKeys.DEFAULT_NAPARNIK_ALL_TOOLS_ENABLED);
        assertEquals("mcpNaparnikAllToolsEnabled", PrefKeys.PREF_NAPARNIK_ALL_TOOLS_ENABLED); //$NON-NLS-1$
        NaparnikTool fromTheStore = wired(bridge::get, null, callable::get);

        JsonObject doc = parse(fromTheStore.execute(ready()));

        assertEquals("read", doc.get("toolPolicy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(host.sent.get(0).allowedTools());
        assertTrue(doc.has("allowedTools")); //$NON-NLS-1$
    }

    @Test
    public void theFullSetSendsNoFilterAndDoesNotCancelAnOutsideTool()
    {
        allTools.set(true);
        host.script = List.of("Execute"); //$NON-NLS-1$

        JsonObject doc = ask();

        assertTrue(doc.toString(), doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("all", doc.get("toolPolicy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(doc.has("allowedTools")); //$NON-NLS-1$
        assertNull(host.sent.get(0).allowedTools());
        assertEquals(List.of("Execute"), strings(doc, "toolsCalled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, host.cancels);
    }

    @Test
    public void aNonPositiveRoundLimitDoesNotStopTheFake()
        throws Exception
    {
        host.script = List.of("Read", "Glob", "List", "SearchText", "GetMarkers"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        RunningQuestion running = host.ask(new Object(), host.ai, question(0, null));

        assertTrue(running.await(2000L));
        assertNull(running.failure());
        assertEquals(5, running.toolsCalled().size());
        assertTrue(host.sawNonPositiveLimit);
    }

    @Test
    public void anUnknownAllowedNameFailsWithTheMeasuredText()
    {
        try
        {
            host.ask(new Object(), host.ai, question(10, Set.of("NotATool"))); //$NON-NLS-1$
            fail("an unknown name must fail"); //$NON-NLS-1$
        }
        catch (NaparnikAccessException failure)
        {
            assertTrue(failure.getMessage(), failure.getMessage().contains("Unknown tools in allowed-tools:")); //$NON-NLS-1$
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertTrue(failure.getCause().getMessage().contains("Unknown tools in allowed-tools:")); //$NON-NLS-1$
        }
    }

    @Test
    public void anEmptyAllowedSetDoesNotLiftTheFilter()
        throws Exception
    {
        List<String> published = List.of("Read", "Execute"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(FakeHost.visible(Set.of(), published).isEmpty());
        assertEquals(published, FakeHost.visible(null, published));
        host.script = List.of("Read"); //$NON-NLS-1$
        RunningQuestion running = host.ask(new Object(), host.ai, question(10, Set.of()));
        assertTrue(running.await(2000L));
        assertEquals(List.of("Read"), running.toolsCalled()); //$NON-NLS-1$
    }

    @Test
    public void theFutureCompletesOnAnotherThreadAndTheTokenIsPolled()
        throws Exception
    {
        host.script = List.of("Read", "Glob", "List"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Thread caller = Thread.currentThread();

        RunningQuestion running = host.ask(new Object(), host.ai, question(10, null));

        assertTrue(running.await(2000L));
        assertNotNull(host.completedOn);
        assertNotEquals(caller, host.completedOn);
        assertTrue(host.polls >= 3);
    }

    @Test
    public void helpNamesTheQuestionTheServiceAndBothSettings()
    {
        JsonObject doc = parse(tool.execute(Map.of("operation", "help"))); //$NON-NLS-1$ //$NON-NLS-2$
        String text = doc.get("text").getAsString(); //$NON-NLS-1$
        String description = tool.getDescription();

        assertTrue(text, text.contains("ask")); //$NON-NLS-1$
        assertTrue(text, text.contains("1C:Naparnik service")); //$NON-NLS-1$
        assertTrue(text, text.contains("mcpNaparnikBridgeEnabled")); //$NON-NLS-1$
        assertTrue(text, text.contains("mcpNaparnikAllToolsEnabled")); //$NON-NLS-1$
        assertTrue(description, description.contains("1C:Naparnik service")); //$NON-NLS-1$
        assertTrue(description, description.contains("mcpNaparnikAllToolsEnabled")); //$NON-NLS-1$
    }

    @Test
    public void theCheckboxNamesWhatTheFullSetAllows()
        throws Exception
    {
        String source = Files.readString(sourceFile("GeneralPrefTab.java")); //$NON-NLS-1$

        assertTrue(source.contains("change metadata")); //$NON-NLS-1$
        assertTrue(source.contains("write files")); //$NON-NLS-1$
        assertTrue(source.contains("execute code")); //$NON-NLS-1$
        assertTrue(source.contains("mcpNaparnikAllToolsEnabled")); //$NON-NLS-1$
    }

    private NaparnikTool wired(java.util.function.BooleanSupplier bridgeOn,
        java.util.function.BooleanSupplier allOn, java.util.function.BooleanSupplier allowed)
    {
        return new NaparnikTool(host, new AskControls(bridgeOn, allOn, allowed, door));
    }

    private void install(String version, String state)
    {
        host.copies.clear();
        host.ai = host.add("com.e1c.edt.ai", version, state); //$NON-NLS-1$
        host.add("com.e1c.edt.ai.context", version, state); //$NON-NLS-1$
        host.ui = host.add("com.e1c.edt.ai.ui", version, state); //$NON-NLS-1$
        host.add("com.e1c.edt.ai.ui.common", version, state); //$NON-NLS-1$
        host.facadeOwner = host.ai;
        host.activatorOwner = host.ui;
    }

    private JsonObject ask(String... pairs)
    {
        Map<String, String> params = ready();
        for (int i = 0; i < pairs.length; i += 2)
        {
            if (pairs[i + 1] == null)
            {
                params.remove(pairs[i]);
            }
            else
            {
                params.put(pairs[i], pairs[i + 1]);
            }
        }
        return parse(tool.execute(params));
    }

    private static Map<String, String> ready()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "ask"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("question", "What does this module do?"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private static Question question(int rounds, Set<String> allowed)
    {
        return new Question(new Object(), "direct", null, null, true, "custom", Boolean.TRUE, rounds, //$NON-NLS-1$ //$NON-NLS-2$
            allowed, null);
    }

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> strings(JsonObject doc, String key)
    {
        List<String> names = new ArrayList<>();
        if (!doc.has(key) || doc.get(key).isJsonNull())
        {
            return names;
        }
        for (JsonElement element : doc.getAsJsonArray(key))
        {
            names.add(element.getAsString());
        }
        return names;
    }

    private static Path sourceFile(String name)
    {
        Path dir = Path.of("").toAbsolutePath();
        for (int hop = 0; hop < 8 && dir != null; hop++)
        {
            Path nested = dir.resolve("src/ru/aiedt/mcp/server/settings").resolve(name); //$NON-NLS-1$
            if (Files.isRegularFile(nested))
            {
                return nested;
            }
            Path fromRoot = dir.resolve(
                "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/settings").resolve(name); //$NON-NLS-1$
            if (Files.isRegularFile(fromRoot))
            {
                return fromRoot;
            }
            dir = dir.getParent();
        }
        fail("source not found: " + name); //$NON-NLS-1$
        return null;
    }

    /**
     * Where the question's project comes from. Missing and closed are refused in the words the
     * workspace uses.
     */
    private static final class Door
        implements ProjectDoor
    {
        private final Object project = new Object();

        @Override
        public Object open(String name)
        {
            if ("Missing".equals(name) || "Closed".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return null;
            }
            return project;
        }

        @Override
        public String refusal(String name)
        {
            if ("Closed".equals(name)) //$NON-NLS-1$
            {
                return "Project '" + name + "' exists but is closed. Open it in the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Navigator (right-click -> Open Project) and retry."; //$NON-NLS-1$
            }
            return "Project not found: '" + name + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A 1.0.7 stand-in. {@code allowedTools} filters definitions only. The scripted calls still
     * run, and a cancel from the start notice stops the next call, not the one that just started.
     * A non-positive round limit is unlimited. The future is completed on the stand-in's thread.
     * {@code elapseImmediately} records the requested wait and returns as if it had elapsed, so a
     * timeout test does not sleep. {@code surviveCancel} counts a cancel and leaves the future
     * running until {@code release}.
     */
    private static final class FakeHost
        implements NaparnikHost
    {
        private final Map<String, List<BundleCopy>> copies = new LinkedHashMap<>();

        private final List<Question> sent = Collections.synchronizedList(new ArrayList<>());

        private final List<Long> waits = Collections.synchronizedList(new ArrayList<>());

        private BundleCopy ai;

        private BundleCopy ui;

        private BundleCopy facadeOwner;

        private BundleCopy activatorOwner;

        private List<String> tools = new ArrayList<>();

        private List<String> script = List.of();

        private Consumer<String> beforeTool;

        private volatile boolean stall;

        private volatile boolean elapseImmediately;

        private volatile boolean surviveCancel;

        private volatile boolean release;

        private volatile boolean sawNonPositiveLimit;

        private volatile int cancels;

        private volatile int polls;

        private volatile Thread completedOn;

        private BundleCopy add(String name, String version, String state)
        {
            BundleCopy copy = new BundleCopy(name, version, state, new Object());
            copies.computeIfAbsent(copy.name(), key -> new ArrayList<>()).add(copy);
            return copy;
        }

        /**
         * What an allowed set leaves defined. Null is no filter. An empty set leaves nothing: it
         * does not mean "no filter".
         *
         * @param allowed the set on the request, or {@code null}
         * @param published the names the installation published
         * @return the names that stay defined
         */
        private static List<String> visible(Set<String> allowed, List<String> published)
        {
            if (allowed == null)
            {
                return List.copyOf(published);
            }
            List<String> names = new ArrayList<>();
            for (String name : published)
            {
                if (allowed.contains(name))
                {
                    names.add(name);
                }
            }
            return names;
        }

        @Override
        public List<BundleCopy> copiesOf(String symbolicName)
        {
            List<BundleCopy> found = copies.get(symbolicName);
            return found == null ? List.of() : found;
        }

        @Override
        public Class<?> loadClass(BundleCopy bundle, String className)
        {
            return String.class;
        }

        @Override
        public InjectorDoor openInjector(BundleCopy uiBundle, Class<?> baseActivator)
        {
            return new InjectorDoor(new Object(), activatorOwner);
        }

        @Override
        public FacadeDoor openFacade(Object injector, Class<?> facadeType)
        {
            return new FacadeDoor(new Object(), facadeOwner);
        }

        @Override
        public List<String> toolNames(Object injector, Class<?> mcpToolsType)
        {
            return tools;
        }

        @Override
        public RunningQuestion ask(Object facade, BundleCopy source, Question question)
            throws NaparnikAccessException
        {
            sent.add(question);
            if (question.allowedTools() != null)
            {
                List<String> unknown = new ArrayList<>();
                for (String name : question.allowedTools())
                {
                    if (!tools.contains(name))
                    {
                        unknown.add(name);
                    }
                }
                if (!unknown.isEmpty())
                {
                    String text = "Unknown tools in allowed-tools: " + String.join(", ", unknown); //$NON-NLS-1$ //$NON-NLS-2$
                    throw new NaparnikAccessException(NaparnikHost.LINK_ASK, text,
                        new IllegalArgumentException(text));
                }
            }
            int requested = question.maxToolRounds();
            int limit = requested;
            if (requested <= 0)
            {
                sawNonPositiveLimit = true;
                limit = Integer.MAX_VALUE;
            }
            FakeRun run = new FakeRun(question);
            CountDownLatch offered = new CountDownLatch(1);
            int roundLimit = limit;
            Thread worker = new Thread(() -> {
                completedOn = Thread.currentThread();
                try
                {
                    int executed = 0;
                    for (String name : script)
                    {
                        polls++;
                        if (run.canceled)
                        {
                            break;
                        }
                        executed++;
                        if (executed > roundLimit)
                        {
                            run.future.completeExceptionally(
                                new IllegalStateException("Too many tool rounds")); //$NON-NLS-1$
                            return;
                        }
                        if (beforeTool != null)
                        {
                            beforeTool.accept(name);
                        }
                        String veto = null;
                        if (question.onToolStart() != null)
                        {
                            veto = question.onToolStart().onStart(List.of(name));
                        }
                        run.tools.add(name);
                        if (veto != null)
                        {
                            // The listener cancels Naparnik's own token. That does not drop the
                            // call that already started; the next scripted call sees the token.
                            run.cancel();
                        }
                    }
                }
                finally
                {
                    offered.countDown();
                }
                if (stall && !run.future.isDone())
                {
                    while (!run.canceled && !release)
                    {
                        try
                        {
                            Thread.sleep(20L);
                        }
                        catch (InterruptedException interrupted)
                        {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!run.future.isDone())
                {
                    run.future.complete(Boolean.TRUE);
                }
            });
            worker.setDaemon(true);
            worker.start();
            try
            {
                if (!offered.await(5L, TimeUnit.SECONDS))
                {
                    throw new NaparnikAccessException(NaparnikHost.LINK_ASK, "the fake did not start", //$NON-NLS-1$
                        null);
                }
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new NaparnikAccessException(NaparnikHost.LINK_ASK, "interrupted", interrupted); //$NON-NLS-1$
            }
            return run;
        }

        /**
         * One question. {@code reasoning} is held and never handed to the tool: the tool has no
         * way to ask for it.
         */
        private final class FakeRun
            implements RunningQuestion
        {
            private final Question question;

            private final CompletableFuture<Boolean> future = new CompletableFuture<>();

            private final List<String> tools = Collections.synchronizedList(new ArrayList<>());

            private volatile boolean canceled;

            private Throwable failure;

            private boolean captured;

            private FakeRun(Question question)
            {
                this.question = question;
            }

            @Override
            public boolean await(long timeoutMs)
            {
                waits.add(Long.valueOf(timeoutMs));
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!future.isDone())
                {
                    if (elapseImmediately || release || System.currentTimeMillis() >= deadline)
                    {
                        return false;
                    }
                    try
                    {
                        Thread.sleep(20L);
                    }
                    catch (InterruptedException interrupted)
                    {
                        Thread.currentThread().interrupt();
                        return future.isDone() && capture();
                    }
                }
                return capture();
            }

            @Override
            public void cancel()
            {
                cancels++;
                if (!surviveCancel)
                {
                    canceled = true;
                }
            }

            @Override
            public Throwable failure()
            {
                return failure;
            }

            @Override
            public String text()
            {
                return "the answer"; //$NON-NLS-1$
            }

            @Override
            public String conversationId()
            {
                return question.conversationId() == null ? "conv-created" : question.conversationId(); //$NON-NLS-1$
            }

            @Override
            public String replyTo()
            {
                return question.replyTo();
            }

            @Override
            public int assistantMessages()
            {
                return 2;
            }

            @Override
            public List<String> toolsCalled()
            {
                synchronized (tools)
                {
                    return List.copyOf(tools);
                }
            }

            private boolean capture()
            {
                if (captured)
                {
                    return true;
                }
                captured = true;
                if (future.isCompletedExceptionally())
                {
                    try
                    {
                        future.join();
                    }
                    catch (CompletionException failed)
                    {
                        failure = failed.getCause() == null ? failed : failed.getCause();
                    }
                }
                return true;
            }
        }
    }
}
