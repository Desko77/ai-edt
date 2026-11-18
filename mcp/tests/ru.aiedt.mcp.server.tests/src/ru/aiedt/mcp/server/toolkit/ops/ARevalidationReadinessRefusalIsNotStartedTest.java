/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.support.ProjectStateGuard.ProjectState;

/** Exercises the public argument-validation and readiness branch without a live workspace. */
public class ARevalidationReadinessRefusalIsNotStartedTest
{
    private JsonObject execute(ProjectState state, String message)
    {
        ObjectsRevalidator tool = new ObjectsRevalidator()
        {
            @Override protected ProjectStateGuard.ProjectStateResult readinessState(String project)
            {
                return new ProjectStateGuard.ProjectStateResult(state, message);
            }
        };
        return JsonParser.parseString(tool.execute(Map.of("projectName", "SyntheticProject")))
            .getAsJsonObject();
    }

    private void notStarted(JsonObject answer, boolean retryable)
    {
        assertTrue("readiness refusal must declare not_started", answer.has("status"));
        assertEquals("not_started", answer.get("status").getAsString());
        assertFalse(answer.get("mutationStarted").getAsBoolean());
        assertEquals(retryable, answer.get("retryable").getAsBoolean());
        assertEquals("revalidate_objects", answer.get("operation").getAsString());
    }

    @Test public void buildingIsRetryableWithoutGuessingFromMessage()
    {
        JsonObject answer = execute(ProjectState.BUILDING, "Derived data is being computed");
        notStarted(answer, true);
        assertEquals("projectBuilding", answer.get("code").getAsString());
        assertEquals(500, answer.get("retryAfterMs").getAsInt());
    }

    @Test public void unknownContainingBuildingIsNotRetryable()
    {
        JsonObject answer = execute(ProjectState.UNKNOWN, "building service unavailable");
        notStarted(answer, false);
        assertEquals("projectNotReady", answer.get("code").getAsString());
        assertFalse(answer.has("retryAfterMs"));
    }

    @Test public void unavailableAuthenticationDiagnosticIsNotBlindlyRetried()
    {
        JsonObject answer = execute(ProjectState.NOT_AVAILABLE, "Synthetic authentication failure");
        notStarted(answer, false);
        assertFalse(answer.has("retryAfterMs"));
        assertTrue(answer.toString().contains("Synthetic authentication failure"));
    }

    @Test public void malformedObjectsStillRefuseBeforeReadingReadiness()
    {
        ObjectsRevalidator tool = new ObjectsRevalidator()
        {
            @Override protected ProjectStateGuard.ProjectStateResult readinessState(String project)
            {
                throw new AssertionError("Malformed arguments must not reach project services");
            }
        };
        String answer = tool.execute(Map.of("projectName", "SyntheticProject", "objects", "Catalog.Products"));
        assertTrue(answer.contains("objects must be a JSON array"));
    }
}
