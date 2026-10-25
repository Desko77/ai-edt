/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * An already withdrawn writer refuses before resolving a project; read-only help keeps its result.
 * These tests do not start a Designer or exercise cancellation after a writer has begun.
 */
public class AWithdrawnOrdinaryWriteStopsBeforeResolvingItsProjectTest
{
    /** Removes the test's own scope so cancellation cannot affect another test. */
    @After
    public void leaveTheScope()
    {
        ToolCallScope.exit();
    }

    /** A withdrawn layout change never needs to resolve the named project. */
    @Test
    public void aWithdrawnLayoutWriteRefusesBeforeProjectResolution()
    {
        assertWithdrawn("set_property"); //$NON-NLS-1$
    }

    /** A withdrawn creation never needs to resolve the named project. */
    @Test
    public void aWithdrawnCreationRefusesBeforeProjectResolution()
    {
        assertWithdrawn("create_form"); //$NON-NLS-1$
    }

    /** A withdrawn import names cancellation before checking the project or starting an export. */
    @Test
    public void aWithdrawnImportRefusesBeforeProjectResolution()
    {
        assertWithdrawn("import_from_infobase"); //$NON-NLS-1$
    }

    /** A read-only help call produces the same answer with and without a raised cancellation flag. */
    @Test
    public void aWithdrawnScopeDoesNotChangeReadOnlyHelp()
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        Map<String, String> arguments = Map.of("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = tool.execute(arguments);
        assertFalse(JsonParser.parseString(before).getAsJsonObject().has("error")); //$NON-NLS-1$
        enterWithdrawnScope();
        assertEquals(before, tool.execute(arguments));
    }

    /**
     * Requires a cancellation refusal for one operation with a deliberately nonexistent project.
     *
     * @param operation the registered writer operation
     */
    private static void assertWithdrawn(String operation)
    {
        enterWithdrawnScope();
        String result = new OrdinaryFormsTool().execute(Map.of("operation", operation, //$NON-NLS-1$
            "projectName", "NoProjectShouldBeResolvedForAWithdrawnWrite", //$NON-NLS-1$ //$NON-NLS-2$
            "formFqn", "Catalog.Products.Form.ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
            "property", "title", "value", "Another title")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject answer = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(result, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result, answer.get("error").getAsString().contains("cancelled")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Creates and enters a scope whose flag was raised before the public tool is called. */
    private static void enterWithdrawnScope()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("withdrawn by the client"); //$NON-NLS-1$
        ToolCallScope.enter(scope);
    }
}
