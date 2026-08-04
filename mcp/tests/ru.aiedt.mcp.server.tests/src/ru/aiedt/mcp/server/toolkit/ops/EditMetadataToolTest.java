/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Covers the F2 delegated, preset-gated operations ({@code delete_metadata_object},
 * {@code rename_metadata_object}, {@code add_metadata_attribute}) at the point that matters headless:
 * the gate path must return a parseable JSON document.
 * <p>
 * {@code EditMetadataTool.getResponseType()} is JSON, so {@code McpRequestRouter.shapeStructured} runs
 * {@code JsonParser.parseString} over whatever the operation returns and fails the call if it is not
 * JSON. The three delegated handlers run {@code ToolGate.gateOrNull} first; when it trips they must
 * therefore hand back JSON, not the gate's plain rejection text - the same reason the enabled
 * rename path is wrapped by {@code convertRenamerMarkdownToJson}. This test pins that: an unwrapped
 * gate string would make {@code JsonParser.parseString} below throw.
 * </p>
 * <p>
 * {@code ToolGate.gateOrNull} asks {@code McpToolCatalog.isToolEnabled}, which is
 * {@code tools.containsKey(name) && !disabled}. A bare JUnit run never registers a tool into the
 * JVM-wide catalog (that needs a live {@code McpServer} start), so the containsKey half is always
 * false and the gate deterministically trips here - which is exactly why these assertions see the
 * disabled branch. The ENABLED route (gate returns {@code null}, the standalone runs) and a genuine
 * user-disabled preset both need a live EDT workspace and are live-verify items; the standalone-vs-
 * facade wording equivalence is structural (router and facade share {@code ToolGate}, see
 * {@code ToolGateTest}). {@code dispatch} is called reflectively because it is private and, unlike
 * {@code execute}, never touches {@code PlatformUI}.
 * </p>
 */
public class EditMetadataToolTest
{
    /** What dispatch answers for an operation it has no handler for. */
    private static final String UNIMPLEMENTED = "not implemented"; //$NON-NLS-1$

    private static String invokeDispatch(String op, Map<String, String> params) throws Exception
    {
        Method dispatch = EditMetadataTool.class.getDeclaredMethod("dispatch", String.class, Map.class); //$NON-NLS-1$
        dispatch.setAccessible(true);
        return (String)dispatch.invoke(new EditMetadataTool(), op, params);
    }

    private static void assertGatedRejectionIsValidJson(String op) throws Exception
    {
        String result = invokeDispatch(op, new HashMap<>());
        // Must parse as JSON: an unwrapped gate string (the bug this guards) would throw here, the
        // very failure shapeStructured would hit at runtime for this JSON-typed tool.
        JsonElement parsed = JsonParser.parseString(result);
        assertTrue("gated rejection must be a JSON object", parsed.isJsonObject()); //$NON-NLS-1$
        // The gate tripped (headless registry is empty) and named the operation it rejected.
        assertTrue("must carry the disabled wording", result.contains("is disabled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the gated operation", result.contains(op)); //$NON-NLS-1$
        // Not the registry's unknown-op fallback - the op IS registered, it was gated.
        assertFalse("registered op must not read as unimplemented", result.contains(UNIMPLEMENTED)); //$NON-NLS-1$
    }

    @Test
    public void deleteMetadataObjectGatePathIsValidJson() throws Exception
    {
        assertGatedRejectionIsValidJson("delete_metadata_object"); //$NON-NLS-1$
    }

    @Test
    public void renameMetadataObjectGatePathIsValidJson() throws Exception
    {
        assertGatedRejectionIsValidJson("rename_metadata_object"); //$NON-NLS-1$
    }

    @Test
    public void addMetadataAttributeGatePathIsValidJson() throws Exception
    {
        assertGatedRejectionIsValidJson("add_metadata_attribute"); //$NON-NLS-1$
    }

    @Test
    public void dispatchOfAnUnregisteredOperationIsTheNotImplementedFallback() throws Exception
    {
        // Contrast case: an op the registry truly does not know about hits dispatch's own JSON
        // fallback - proving the three names above are registered operations that were gated, not
        // unknown ones. Both branches return JSON (edit_metadata is JSON-typed throughout).
        String op = "not_a_real_edit_metadata_operation_xyz"; //$NON-NLS-1$
        String result = invokeDispatch(op, new HashMap<>());
        JsonElement parsed = JsonParser.parseString(result);
        assertTrue(parsed.isJsonObject());
        assertTrue("unknown op must read as unimplemented, got: " + result, //$NON-NLS-1$
            result.contains(UNIMPLEMENTED));
        assertTrue(result.contains(op));
    }
}
