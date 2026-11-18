/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

public class ATemplateReadinessRefusalNamesItsOutcomeTest
{
    @Test public void buildingIsRetryableOnlyBecauseNoMutationStarted()
    {
        JsonObject answer = refusal(ProjectStateGuard.ProjectState.BUILDING);
        assertEquals("projectBuilding", answer.get("code").getAsString());
        assertEquals("not_started", answer.get("status").getAsString());
        assertFalse(answer.get("mutationStarted").getAsBoolean());
        assertEquals(true, answer.get("retryable").getAsBoolean());
        assertEquals(500, answer.get("retryAfterMs").getAsInt());
    }

    @Test public void unavailableDoesNotSuggestBlindRetry()
    {
        JsonObject answer = refusal(ProjectStateGuard.ProjectState.NOT_AVAILABLE);
        assertEquals("not_started", answer.get("status").getAsString());
        assertFalse(answer.get("mutationStarted").getAsBoolean());
        assertFalse(answer.get("retryable").getAsBoolean());
        assertFalse(answer.has("retryAfterMs"));
    }

    private static JsonObject refusal(ProjectStateGuard.ProjectState state)
    {
        return JsonParser.parseString(MxlWorkshopTool.readinessRefusal("set_cell",
            new ProjectStateGuard.ProjectStateResult(state, "state detail"), "state detail"))
            .getAsJsonObject();
    }
}
