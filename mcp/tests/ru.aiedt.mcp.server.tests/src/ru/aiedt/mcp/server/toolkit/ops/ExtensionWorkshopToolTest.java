/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Covers the preset-gated deployment operations of {@code extension_workshop} ({@code install_extension},
 * {@code uninstall_extension}, {@code list_extension}, {@code export_extension}) at the point that matters
 * headless: the gate rejection must be a parseable JSON document.
 * <p>
 * {@code extension_workshop} is {@code ResponseType.JSON}, so {@code McpRequestRouter.shapeStructured}
 * parses its result and fails the call on non-JSON. Those four cases run {@code ToolGate.gateOrNull}
 * through {@code gatedRoute}; when it trips they must hand back JSON, not the gate's plain text. This test
 * pins that - an unwrapped gate string would make {@code JsonParser.parseString} below throw.
 * </p>
 * <p>
 * {@code gateOrNull} asks {@code McpToolCatalog.isToolEnabled} = {@code tools.containsKey(name) && !disabled}.
 * A bare JUnit run never registers a tool into the JVM-wide catalog, so containsKey is always false and the
 * gate deterministically trips here - which is why these assertions see the disabled branch. The enabled
 * route and a genuine user-disabled preset are live-verify items. {@code execute} is called directly: it
 * reaches the gated switch cases without touching {@code PlatformUI} first, and a tripped gate returns
 * before the standalone tool (which would need EDT) is ever constructed.
 * </p>
 */
public class ExtensionWorkshopToolTest
{
    private static String run(String op)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", op); //$NON-NLS-1$
        return new ExtensionWorkshopTool().execute(params);
    }

    private static void assertGatedRejectionIsValidJson(String op)
    {
        String result = run(op);
        JsonElement parsed = JsonParser.parseString(result); // throws if the gate returned a bare string
        assertTrue("gated rejection must be a JSON object", parsed.isJsonObject()); //$NON-NLS-1$
        assertTrue("must carry the disabled wording", result.contains("is disabled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the gated operation", result.contains(op)); //$NON-NLS-1$
        assertFalse("a known op must not read as unknown", result.contains("Unknown operation")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void installExtensionGatePathIsValidJson()
    {
        assertGatedRejectionIsValidJson("install_extension"); //$NON-NLS-1$
    }

    @Test
    public void uninstallExtensionGatePathIsValidJson()
    {
        assertGatedRejectionIsValidJson("uninstall_extension"); //$NON-NLS-1$
    }

    @Test
    public void listExtensionGatePathIsValidJson()
    {
        assertGatedRejectionIsValidJson("list_extension"); //$NON-NLS-1$
    }

    @Test
    public void exportExtensionGatePathIsValidJson()
    {
        assertGatedRejectionIsValidJson("export_extension"); //$NON-NLS-1$
    }

    @Test
    public void unknownOperationIsReportedAsUnknownNotGated()
    {
        // Contrast: a name the facade does not know hits its own unknown-operation JSON, proving the four
        // above are known operations the gate rejected, not unknown ones. Both are valid JSON.
        String result = run("not_a_real_extension_workshop_operation_xyz"); //$NON-NLS-1$
        JsonElement parsed = JsonParser.parseString(result);
        assertTrue(parsed.isJsonObject());
        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
    }
}
