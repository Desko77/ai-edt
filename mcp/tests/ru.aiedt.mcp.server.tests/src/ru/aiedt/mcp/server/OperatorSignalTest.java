/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.OperatorSignal.SignalType;

/**
 * Unit tests for {@link OperatorSignal}. Covers the type enum, construction and timestamp capture, the
 * JSON rendering, and the per-type default messages.
 */
public class OperatorSignalTest
{
    @Test
    public void constructorStoresTypeAndMessage()
    {
        OperatorSignal signal = new OperatorSignal(SignalType.CANCEL, "stop now");
        assertEquals(SignalType.CANCEL, signal.getType());
        assertEquals("stop now", signal.getMessage());
    }

    @Test
    public void timestampIsPositiveWhenRaised()
    {
        OperatorSignal signal = new OperatorSignal(SignalType.BACKGROUND, "running");
        assertTrue(signal.getTimestamp() > 0);
    }

    @Test
    public void timestampFallsBetweenWallClockBoundsAtConstruction()
    {
        long before = System.currentTimeMillis();
        OperatorSignal signal = new OperatorSignal(SignalType.RETRY, "again");
        long after = System.currentTimeMillis();
        assertTrue(signal.getTimestamp() >= before);
        assertTrue(signal.getTimestamp() <= after);
    }

    @Test
    public void allFiveSignalKindsExist()
    {
        SignalType[] kinds = SignalType.values();
        assertTrue("expected at least five signal kinds", kinds.length >= 5);
        assertNotNull(SignalType.valueOf("CANCEL"));
        assertNotNull(SignalType.valueOf("RETRY"));
        assertNotNull(SignalType.valueOf("BACKGROUND"));
        assertNotNull(SignalType.valueOf("EXPERT"));
        assertNotNull(SignalType.valueOf("CUSTOM"));
    }

    @Test
    public void toJsonSerializesTypeAndMessageWithFlagSet()
    {
        OperatorSignal signal = new OperatorSignal(SignalType.CANCEL, "stop");
        JsonObject parsed = JsonParser.parseString(signal.toJson()).getAsJsonObject();
        assertTrue(parsed.get("userSignal").getAsBoolean());
        assertEquals("CANCEL", parsed.get("signalType").getAsString());
        assertEquals("stop", parsed.get("message").getAsString());
    }

    @Test
    public void toJsonCarriesRetryKind()
    {
        OperatorSignal signal = new OperatorSignal(SignalType.RETRY, "once more");
        JsonObject parsed = JsonParser.parseString(signal.toJson()).getAsJsonObject();
        assertEquals("RETRY", parsed.get("signalType").getAsString());
    }

    @Test
    public void toJsonCarriesExpertKind()
    {
        OperatorSignal signal = new OperatorSignal(SignalType.EXPERT, "ask a person");
        JsonObject parsed = JsonParser.parseString(signal.toJson()).getAsJsonObject();
        assertEquals("EXPERT", parsed.get("signalType").getAsString());
    }

    @Test
    public void defaultCancelMessageMentionsCancelling()
    {
        String text = OperatorSignal.getDefaultMessage(SignalType.CANCEL);
        assertNotNull(text);
        assertFalse(text.isEmpty());
        assertTrue(text.toLowerCase().contains("cancel"));
    }

    @Test
    public void defaultRetryMessageMentionsRetrying()
    {
        String text = OperatorSignal.getDefaultMessage(SignalType.RETRY);
        assertNotNull(text);
        assertFalse(text.isEmpty());
        assertTrue(text.toLowerCase().contains("retry"));
    }

    @Test
    public void defaultBackgroundMessageIsNonEmpty()
    {
        String text = OperatorSignal.getDefaultMessage(SignalType.BACKGROUND);
        assertNotNull(text);
        assertFalse(text.isEmpty());
    }

    @Test
    public void defaultExpertMessageMentionsExpert()
    {
        String text = OperatorSignal.getDefaultMessage(SignalType.EXPERT);
        assertNotNull(text);
        assertFalse(text.isEmpty());
        assertTrue(text.toLowerCase().contains("expert"));
    }

    @Test
    public void defaultCustomMessageStartsBlank()
    {
        assertEquals("", OperatorSignal.getDefaultMessage(SignalType.CUSTOM));
    }

    @Test
    public void defaultMessageForNullTypeIsEmpty()
    {
        assertEquals("", OperatorSignal.getDefaultMessage(null));
    }
}
