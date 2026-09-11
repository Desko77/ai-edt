/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.workbench.McpHistoryDialog;
import ru.aiedt.mcp.server.workbench.McpStatusBarItem;

/**
 * A call the operator answered from the status bar is recorded as that: the tool's own outcome,
 * plus who answered the agent and whether the answer arrived.
 * <p>
 * The record used to be written by the tool thread alone, with the tool's outcome, and the signal
 * went past it: a call the agent never got the result of looked exactly like one it did.
 * </p>
 */
public class AnInterruptedCallLeavesATraceTest
{
    private static final String TOOL = "trace_probe"; //$NON-NLS-1$

    @Before
    public void emptyTheBuffer()
    {
        McpHistory.clear();
    }

    // ---- 1, 2: the signal is in the record, the tool's outcome stays ------------------------

    @Test
    public void aSignalThatAnsweredTheAgentIsInTheRecordWithItsNote()
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.open(), TOOL, 7);
        OperatorSignal signal = new OperatorSignal(OperatorSignal.SignalType.CUSTOM, "look at the form first"); //$NON-NLS-1$

        assertEquals(RunningToolCall.Delivery.DELIVERED, call.sendSignalResponse(signal));
        call.toolFinished(completion(true));

        Map<String, Object> record = newest();
        assertEquals(McpHistory.ARBITRATED_BY_SIGNAL, record.get("arbitratedBy")); //$NON-NLS-1$
        assertEquals(McpHistory.DELIVERY_DELIVERED, record.get("deliveryStatus")); //$NON-NLS-1$
        assertEquals("CUSTOM", record.get("signalType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("look at the form first", record.get("signalNote")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the tool's own outcome is kept", Boolean.TRUE, record.get("success")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ok-result", record.get("result")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSignalWithoutANoteCarriesItsTypeOnly()
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.open(), TOOL, 7);
        call.sendSignalResponse(new OperatorSignal(OperatorSignal.SignalType.CANCEL, null));
        call.toolFinished(completion(false));

        Map<String, Object> record = newest();
        assertEquals("CANCEL", record.get("signalType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(record.containsKey("signalNote")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, record.get("success")); //$NON-NLS-1$
    }

    // ---- 3: an ordinary call is recorded as before ------------------------------------------

    @Test
    public void anOrdinaryCallIsRecordedAsBefore()
    {
        FakeExchange exchange = FakeExchange.open();
        RunningToolCall call = new RunningToolCall(exchange, TOOL, 7);
        call.toolFinished(completion(true));
        assertEquals(RunningToolCall.Delivery.DELIVERED, call.answerWithResult(() -> exchange.getResponseBody()
            .write("{\"result\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))); //$NON-NLS-1$

        Map<String, Object> record = newest();
        assertEquals(TOOL, record.get("tool")); //$NON-NLS-1$
        assertEquals("a=1", record.get("args")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ok-result", record.get("result")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, record.get("success")); //$NON-NLS-1$
        assertEquals(12L, ((Number)record.get("durationMs")).longValue()); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, record.get("argsCut")); //$NON-NLS-1$
        assertEquals("ok-result".length(), ((Number)record.get("resultChars")).intValue()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(record.containsKey("timestamp")); //$NON-NLS-1$
        assertEquals(McpHistory.ARBITRATED_BY_TOOL, record.get("arbitratedBy")); //$NON-NLS-1$
        assertEquals(McpHistory.DELIVERY_DELIVERED, record.get("deliveryStatus")); //$NON-NLS-1$
        assertFalse(record.containsKey("signalType")); //$NON-NLS-1$
    }

    // ---- 4, 10: the counters sit on top of the old ones ------------------------------------

    @Test
    public void theCountersAreAddedOnTopAndTheOldSumsHold()
    {
        // one ordinary success, one ordinary failure, one interrupted success, one interrupted and
        // undelivered failure, one tool answer that could not be sent
        ordinary(true);
        ordinary(false);
        interrupted(true, FakeExchange.open());
        interrupted(false, FakeExchange.hungUp());
        undeliveredResult(true);

        Map<String, Object> stats = McpHistory.stats();
        assertEquals(5, stats.get("buffered")); //$NON-NLS-1$
        assertEquals(3, stats.get("success")); //$NON-NLS-1$
        assertEquals(2, stats.get("failure")); //$NON-NLS-1$
        assertEquals(2, stats.get("interrupted")); //$NON-NLS-1$
        assertEquals(2, stats.get("undelivered")); //$NON-NLS-1$

        Map<String, Object> perTool = McpHistory.perToolStats().get(TOOL);
        assertEquals(5, perTool.get("count")); //$NON-NLS-1$
        assertEquals(3, perTool.get("successCount")); //$NON-NLS-1$
        assertEquals(2, perTool.get("failCount")); //$NON-NLS-1$
        assertEquals(2, perTool.get("interruptedCount")); //$NON-NLS-1$
        assertEquals(2, perTool.get("undeliveredCount")); //$NON-NLS-1$
    }

    // ---- 5, 6: exactly one record, whichever half comes second, driven by latches ------------

    @Test
    public void oneRecordWhenTheToolFinishesFirst() throws Exception
    {
        assertEquals(1, recordsWhenOrdered(true));
    }

    @Test
    public void oneRecordWhenTheSignalComesFirst() throws Exception
    {
        assertEquals(1, recordsWhenOrdered(false));
    }

    @Test
    public void aResultArrivingAfterTheSignalIsNotArbitratedAndLeavesNoSecondRecord()
    {
        FakeExchange exchange = FakeExchange.open();
        RunningToolCall call = new RunningToolCall(exchange, TOOL, 7);
        call.sendSignalResponse(new OperatorSignal(OperatorSignal.SignalType.BACKGROUND, null));
        call.toolFinished(completion(true));
        assertEquals(RunningToolCall.Delivery.NOT_ARBITRATED, call.answerWithResult(() -> {
            throw new AssertionError("the result must not be written over the signal"); //$NON-NLS-1$
        }));
        assertEquals(1, McpHistory.size());
        assertEquals(McpHistory.ARBITRATED_BY_SIGNAL, newest().get("arbitratedBy")); //$NON-NLS-1$
    }

    // ---- 7: the signal won and was not delivered ---------------------------------------------

    @Test
    public void aSignalTheAgentNeverGotIsRecordedAsUndeliveredNotAsDelivered()
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.hungUp(), TOOL, 7);
        assertEquals(RunningToolCall.Delivery.DELIVERY_FAILED,
            call.sendSignalResponse(new OperatorSignal(OperatorSignal.SignalType.RETRY, null)));
        call.toolFinished(completion(true));

        Map<String, Object> record = newest();
        assertEquals(McpHistory.ARBITRATED_BY_SIGNAL, record.get("arbitratedBy")); //$NON-NLS-1$
        assertEquals(McpHistory.DELIVERY_FAILED, record.get("deliveryStatus")); //$NON-NLS-1$
        assertEquals("RETRY", record.get("signalType")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- 8: a result that could not be sent --------------------------------------------------

    @Test
    public void aResultTheAgentNeverGotIsRecordedAsUndelivered()
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.hungUp(), TOOL, 7);
        call.toolFinished(completion(true));
        assertEquals(RunningToolCall.Delivery.DELIVERY_FAILED, call.answerWithResult(() -> {
            throw new IOException("connection reset by peer"); //$NON-NLS-1$
        }));

        Map<String, Object> record = newest();
        assertEquals(McpHistory.ARBITRATED_BY_TOOL, record.get("arbitratedBy")); //$NON-NLS-1$
        assertEquals(McpHistory.DELIVERY_FAILED, record.get("deliveryStatus")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, record.get("success")); //$NON-NLS-1$
    }

    @Test
    public void aCallTheServerAbandonedIsRecordedAsUndelivered()
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.open(), TOOL, 7);
        call.toolFinished(completion(true));
        call.abandon();
        Map<String, Object> record = newest();
        assertEquals(McpHistory.ARBITRATED_BY_TOOL, record.get("arbitratedBy")); //$NON-NLS-1$
        assertEquals(McpHistory.DELIVERY_FAILED, record.get("deliveryStatus")); //$NON-NLS-1$
    }

    // ---- 9: the window says it ----------------------------------------------------------------

    @Test
    public void theOutcomeColumnNamesTheSignalAndTheLostDelivery()
    {
        interrupted(true, FakeExchange.open());
        assertEquals("ok, interrupted (CANCEL)", McpHistoryDialog.outcomeLabel(newest())); //$NON-NLS-1$
        McpHistory.clear();
        interrupted(false, FakeExchange.hungUp());
        assertEquals("failed, interrupted (CANCEL), not delivered", McpHistoryDialog.outcomeLabel(newest())); //$NON-NLS-1$
        McpHistory.clear();
        undeliveredResult(true);
        assertEquals("ok, not delivered", McpHistoryDialog.outcomeLabel(newest())); //$NON-NLS-1$
        McpHistory.clear();
        ordinary(true);
        assertEquals("ok", McpHistoryDialog.outcomeLabel(newest())); //$NON-NLS-1$
    }

    // ---- 11: what is parked for the next call -----------------------------------------------

    @Test
    public void onlyASignalNobodyArbitratedIsKeptForTheNextCall()
    {
        assertTrue(McpStatusBarItem.parksSignal(RunningToolCall.Delivery.NOT_ARBITRATED));
        assertFalse(McpStatusBarItem.parksSignal(RunningToolCall.Delivery.DELIVERED));
        assertFalse(McpStatusBarItem.parksSignal(RunningToolCall.Delivery.DELIVERY_FAILED));
    }

    @Test
    public void nobodyHasAnsweredUntilSomebodyHas()
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.open(), TOOL, 7);
        assertNull(call.answer());
        call.toolFinished(completion(true));
        assertNull("the tool finishing is not an answer to the agent", call.answer()); //$NON-NLS-1$
        assertEquals(0, McpHistory.size());
    }

    private static int recordsWhenOrdered(boolean toolFirst) throws Exception
    {
        McpHistory.clear();
        RunningToolCall call = new RunningToolCall(FakeExchange.open(), TOOL, 7);
        CountDownLatch toolMayFinish = new CountDownLatch(1);
        CountDownLatch toolDone = new CountDownLatch(1);
        CountDownLatch signalMayGo = new CountDownLatch(1);
        CountDownLatch signalDone = new CountDownLatch(1);
        Thread tool = new Thread(() -> {
            await(toolMayFinish);
            call.toolFinished(completion(true));
            toolDone.countDown();
        });
        Thread operator = new Thread(() -> {
            await(signalMayGo);
            call.sendSignalResponse(new OperatorSignal(OperatorSignal.SignalType.EXPERT, null));
            signalDone.countDown();
        });
        tool.start();
        operator.start();
        if (toolFirst)
        {
            toolMayFinish.countDown();
            assertTrue(toolDone.await(10, TimeUnit.SECONDS));
            assertEquals("nothing is recorded until the agent has been answered", 0, McpHistory.size()); //$NON-NLS-1$
            signalMayGo.countDown();
            assertTrue(signalDone.await(10, TimeUnit.SECONDS));
        }
        else
        {
            signalMayGo.countDown();
            assertTrue(signalDone.await(10, TimeUnit.SECONDS));
            assertEquals("nothing is recorded until the tool has finished", 0, McpHistory.size()); //$NON-NLS-1$
            toolMayFinish.countDown();
            assertTrue(toolDone.await(10, TimeUnit.SECONDS));
        }
        tool.join(TimeUnit.SECONDS.toMillis(10));
        operator.join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(McpHistory.ARBITRATED_BY_SIGNAL, newest().get("arbitratedBy")); //$NON-NLS-1$
        return McpHistory.size();
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void ordinary(boolean success)
    {
        FakeExchange exchange = FakeExchange.open();
        RunningToolCall call = new RunningToolCall(exchange, TOOL, 7);
        call.toolFinished(completion(success));
        call.answerWithResult(() -> exchange.sendResponseHeaders(200, 0));
    }

    private static void interrupted(boolean success, FakeExchange exchange)
    {
        RunningToolCall call = new RunningToolCall(exchange, TOOL, 7);
        call.sendSignalResponse(new OperatorSignal(OperatorSignal.SignalType.CANCEL, null));
        call.toolFinished(completion(success));
    }

    private static void undeliveredResult(boolean success)
    {
        RunningToolCall call = new RunningToolCall(FakeExchange.hungUp(), TOOL, 7);
        call.toolFinished(completion(success));
        call.answerWithResult(() -> {
            throw new IOException("connection reset by peer"); //$NON-NLS-1$
        });
    }

    private static McpHistory.Completion completion(boolean success)
    {
        return new McpHistory.Completion(TOOL, "a=1", false, "ok-result", 12L, success); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, Object> newest()
    {
        return McpHistory.recent(1).get(0);
    }
}
