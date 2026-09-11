/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.OperatorSignal;

/**
 * The journal line of an interrupted call says who answered the agent, the same as the buffer does.
 */
public class TheJournalCarriesWhoAnsweredTest
{
    @Test
    public void theLineCarriesBothFields() throws IOException
    {
        McpHistory.clear();
        McpHistory.record(new McpHistory.Completion("journal_probe", "", false, "done", 3L, true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            McpHistory.Answer.bySignal(new OperatorSignal(OperatorSignal.SignalType.CUSTOM, "wait"), false)); //$NON-NLS-1$
        Map<String, Object> record = McpHistory.recent(1).get(0);

        Path file = Files.createTempFile("aiedt-journal", ".jsonl"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            HistoryJournal.appendTo(file, record, false);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertEquals(1, lines.size());
            JsonObject line = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            assertEquals(McpHistory.ARBITRATED_BY_SIGNAL, line.get("arbitratedBy").getAsString()); //$NON-NLS-1$
            assertEquals(McpHistory.DELIVERY_FAILED, line.get("deliveryStatus").getAsString()); //$NON-NLS-1$
            assertEquals("CUSTOM", line.get("signalType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("wait", line.get("signalNote").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(line.get("success").getAsBoolean()); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(file);
            McpHistory.clear();
        }
    }
}
