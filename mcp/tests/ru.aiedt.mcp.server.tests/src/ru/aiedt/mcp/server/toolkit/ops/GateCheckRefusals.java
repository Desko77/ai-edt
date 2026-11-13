/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.support.GateCheck;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Runs one gate check of a facade operation against a store with exactly that name switched off.
 * <p>
 * Shared by the per-facade tests so each of them states its route operations and expected checks
 * and nothing else. It does not decide what the check list should be - the test that calls it
 * asserts that separately - it only proves that a check the facade publishes really refuses the
 * call with the wording its kind promises.
 * </p>
 */
final class GateCheckRefusals
{
    private GateCheckRefusals()
    {
        // static helper
    }

    /**
     * Disables the check's name, calls the facade with the operation and answers the refusal.
     *
     * @param facade the facade under test
     * @param argument the name of the facade's routing argument, {@code operation} or {@code action}
     * @param operation the registry operation to call
     * @param check the check whose name is switched off
     * @return the text of the facade's {@code error} member, never {@code null}
     */
    static String refusalOf(IMcpTool facade, String argument, String operation, GateCheck check)
    {
        ToolSettingsStore.getInstance().setDisabledTools(Set.of(check.name()));
        Map<String, String> params = new LinkedHashMap<>();
        params.put(argument, operation);
        JsonObject answer = JsonParser.parseString(facade.execute(params)).getAsJsonObject();
        assertFalse("the call was not refused: " + answer, //$NON-NLS-1$
            answer.get("success").getAsBoolean()); //$NON-NLS-1$
        return answer.get("error").getAsString(); //$NON-NLS-1$
    }

    /**
     * The exact text a check of this kind must produce.
     *
     * @param check the check
     * @return the gate's wording for that kind and name
     */
    static String expected(GateCheck check)
    {
        switch (check.kind())
        {
            case WRITE_DOOR:
                return ToolGate.writeDoorMessage(check.name());
            case PRESET:
            case ENABLED:
                return ToolGate.disabledMessage(check.name());
            default:
                throw new IllegalStateException("Unknown gate check kind: " + check.kind()); //$NON-NLS-1$
        }
    }
}
