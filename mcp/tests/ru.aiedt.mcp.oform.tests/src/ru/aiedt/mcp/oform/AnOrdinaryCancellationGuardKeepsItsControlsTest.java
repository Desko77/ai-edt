/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * Distinguishes cancellation from an existing scope, a project requirement and normal argument validation.
 * No project or infobase is created, and cancellation after dispatch is not exercised.
 */
public class AnOrdinaryCancellationGuardKeepsItsControlsTest
{
    /** Removes a scope if the test introduced one; exit also accepts an unbound thread. */
    @After
    public void leaveTheScope()
    {
        ToolCallScope.exit();
    }

    /** An active, noncancelled writer scope still reaches normal project-name validation. */
    @Test
    public void anActiveScopeDoesNotCancelAWriter()
    {
        ToolCallScope.enter(ToolCallScope.create(null));
        assertProjectRequired("set_property"); //$NON-NLS-1$
    }

    /** A writer outside any scope retains the same project-name validation result. */
    @Test
    public void aWriterWithoutAScopeStillValidatesItsProjectName()
    {
        assertProjectRequired("set_property"); //$NON-NLS-1$
    }

    /** A cancelled read-only query requiring a project is not misclassified as a writer. */
    @Test
    public void aReadOnlyProjectQueryDoesNotBecomeAWriter()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("withdrawn by the client"); //$NON-NLS-1$
        ToolCallScope.enter(scope);
        assertProjectRequired("inspect_layout"); //$NON-NLS-1$
    }

    /** The early refusal identifies the operation rather than only reporting a generic error. */
    @Test
    public void theCancellationRefusalNamesTheWriter()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("withdrawn by the client"); //$NON-NLS-1$
        ToolCallScope.enter(scope);
        JsonObject answer = answerFor("set_property"); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("The operation set_property was cancelled before execution; nothing was changed.", //$NON-NLS-1$
            answer.get("error").getAsString()); //$NON-NLS-1$
    }

    /**
     * Requires the unchanged argument-validation refusal for an operation with no project name.
     *
     * @param operation the registered operation requiring a project
     */
    private static void assertProjectRequired(String operation)
    {
        JsonObject answer = answerFor(operation);
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("projectName must be provided", answer.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Executes an operation without a project-name argument and parses its structured result.
     *
     * @param operation the registered operation
     * @return the public tool's JSON answer
     */
    private static JsonObject answerFor(String operation)
    {
        return JsonParser.parseString(new OrdinaryFormsTool().execute(Map.of("operation", operation))) //$NON-NLS-1$
            .getAsJsonObject();
    }
}
