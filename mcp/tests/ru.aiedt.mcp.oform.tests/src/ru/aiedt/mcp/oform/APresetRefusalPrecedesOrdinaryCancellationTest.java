/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * Checks preset precedence in the actual OSGi preference store and restores its exact prior values.
 * Does not access a project, start a client or change an infobase.
 */
public class APresetRefusalPrecedesOrdinaryCancellationTest
{
    private static final String DISABLED_TOOLS = "mcpDisabledTools"; //$NON-NLS-1$
    private static final String TOOL_PRESET = "mcpToolPreset"; //$NON-NLS-1$

    private IPreferenceStore store;
    private String disabled;
    private String preset;
    private boolean disabledWasDefault;
    private boolean presetWasDefault;

    /** Records both preference values and their default-state bits before the test changes them. */
    @Before
    public void rememberTheSelection()
    {
        assertNotNull("An OSGi plugin runtime is required", Activator.getDefault()); //$NON-NLS-1$
        store = Activator.getDefault().getPreferenceStore();
        disabled = store.getString(DISABLED_TOOLS);
        preset = store.getString(TOOL_PRESET);
        disabledWasDefault = store.isDefault(DISABLED_TOOLS);
        presetWasDefault = store.isDefault(TOOL_PRESET);
    }

    /** Removes the scope and restores the recorded preference values, including default-state bits. */
    @After
    public void restoreTheSelection()
    {
        ToolCallScope.exit();
        if (store != null)
        {
            restore(DISABLED_TOOLS, disabled, disabledWasDefault);
            restore(TOOL_PRESET, preset, presetWasDefault);
        }
    }

    /** A disabled writer capability keeps its preset refusal even when the call was cancelled. */
    @Test
    public void thePresetRefusesBeforeCancellation()
    {
        store.setValue(DISABLED_TOOLS, disabled.isBlank() ? "ordinary_forms_writes" //$NON-NLS-1$
            : disabled + ",ordinary_forms_writes"); //$NON-NLS-1$
        store.setValue(TOOL_PRESET, ""); //$NON-NLS-1$
        enterCancelledScope();
        JsonObject answer = JsonParser.parseString(new OrdinaryFormsTool().execute(
            Map.of("operation", "set_property"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = answer.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("ordinary_forms_writes")); //$NON-NLS-1$
        assertTrue(error, error.contains("switched off by the tool settings")); //$NON-NLS-1$
        assertFalse(error, error.contains("cancelled")); //$NON-NLS-1$
    }

    /** Unknown-operation validation keeps its own refusal under a cancelled scope. */
    @Test
    public void anUnknownOperationRemainsUnknown()
    {
        enterCancelledScope();
        JsonObject answer = JsonParser.parseString(new OrdinaryFormsTool().execute(
            Map.of("operation", "no_such_operation"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = answer.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.startsWith("Unknown operation: no_such_operation.")); //$NON-NLS-1$
        assertFalse(error, error.contains("was cancelled before execution")); //$NON-NLS-1$
    }

    /** Introduces a scope whose cancellation flag is already raised. */
    private static void enterCancelledScope()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("withdrawn by the client"); //$NON-NLS-1$
        ToolCallScope.enter(scope);
    }

    /**
     * Restores one preference without converting a default value into an explicit override.
     *
     * @param key the preference key
     * @param value its prior value
     * @param wasDefault whether the key previously used its default
     */
    private void restore(String key, String value, boolean wasDefault)
    {
        if (wasDefault)
        {
            store.setToDefault(key);
        }
        else
        {
            store.setValue(key, value);
        }
    }
}
