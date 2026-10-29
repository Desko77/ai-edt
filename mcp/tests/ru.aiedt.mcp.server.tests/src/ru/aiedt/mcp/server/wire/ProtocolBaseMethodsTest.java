/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;

/**
 * Covers the base-protocol methods this server answered with method-not-found until now.
 * <p>
 * {@code ping} has been in the specification from the first revision, and a client checking liveness
 * the standard way was told the method does not exist - which reads as a broken server rather than
 * as a server that never got round to it. {@code resources/list} and {@code resources/read} were the
 * same omission with more behind it: the write-ups for EDT's validation checks are documents with
 * stable names, and there was no way to ask for one except through a tool call.
 * </p>
 */
public class ProtocolBaseMethodsTest
{
    private static String call(String method, String params)
    {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"" //$NON-NLS-1$ //$NON-NLS-2$
            + (params == null ? "" : ",\"params\":" + params) + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return new McpRequestRouter().processRequest(body);
    }

    private static JsonObject resultOf(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject().getAsJsonObject("result"); //$NON-NLS-1$
    }

    private static JsonObject resultOfError(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject().getAsJsonObject("error"); //$NON-NLS-1$
    }

    /**
     * A withdrawal is a notification: it has no id of its own and gets no document back. The one
     * with no params at all is the shape that matters - it reached a read of those params and threw,
     * so a malformed notification came back as an internal error instead of being ignored.
     */
    @Test
    public void aWithdrawalIsAnsweredWithNothingAtAll()
    {
        assertNull(call("notifications/cancelled", null)); //$NON-NLS-1$
        assertNull(call("notifications/cancelled", "{}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(call("notifications/cancelled", "{\"requestId\":42}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(call("notifications/cancelled", //$NON-NLS-1$
            "{\"requestId\":42,\"reason\":\"changed my mind\"}")); //$NON-NLS-1$
    }

    /** A ping asks whether anything is listening. The answer is that something answered. */
    @Test
    public void pingIsAnswered()
    {
        String answer = call("ping", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("\"result\"")); //$NON-NLS-1$
        assertFalse("a base-protocol method must not be method-not-found: " + answer, //$NON-NLS-1$
            answer.contains("-32601")); //$NON-NLS-1$
    }

    /** Listing is answered whether or not this runtime has any documents to offer. */
    @Test
    public void resourcesAreListed()
    {
        String answer = call("resources/list", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("\"resources\"")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("-32601")); //$NON-NLS-1$
    }

    /**
     * The route document is part of the listing, with the mime type a markdown reader expects.
     * <p>
     * It is listed even when no check write-up is packaged: the guidance document is assembled from
     * code and does not depend on those files, so an empty checks folder must not hide it.
     * </p>
     */
    @Test
    public void theGuidanceDocumentIsListedAsMarkdown()
    {
        JsonArray resources = resultOf(call("resources/list", null)).getAsJsonArray("resources"); //$NON-NLS-1$
        JsonObject guidance = null;
        for (int i = 0; i < resources.size(); i++)
        {
            JsonObject candidate = resources.get(i).getAsJsonObject();
            if (McpServerMeta.RESOURCE_GUIDANCE.equals(candidate.get("uri").getAsString())) //$NON-NLS-1$
            {
                guidance = candidate;
            }
        }

        assertNotNull("resources/list has to advertise " + McpServerMeta.RESOURCE_GUIDANCE //$NON-NLS-1$
            + ": " + resources, guidance); //$NON-NLS-1$
        assertEquals("text/markdown", guidance.get("mimeType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reading the route document hands back the plugin's own version and the preset in force, not a
     * copy of either frozen into the build.
     */
    @Test
    public void theGuidanceDocumentCarriesTheVersionAndThePreset()
    {
        String answer = call("resources/read", //$NON-NLS-1$
            "{\"uri\":\"" + McpServerMeta.RESOURCE_GUIDANCE + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(answer, 1, resultOf(answer).getAsJsonArray("contents").size()); //$NON-NLS-1$
        String text = resultOf(answer).getAsJsonArray("contents").get(0).getAsJsonObject() //$NON-NLS-1$
            .get("text").getAsString(); //$NON-NLS-1$

        assertTrue("the document must name the installed version: " + text, //$NON-NLS-1$
            text.contains(McpServerMeta.PLUGIN_VERSION));
        assertTrue("the document must name the active preset: " + text, //$NON-NLS-1$
            text.contains(ToolSettingsStore.getInstance().getActivePreset().getDisplayName()));
    }

    /** A deeper path under the guidance address is an unknown resource, not a sub-page. */
    @Test
    public void aNestedGuidanceUriIsRefusedByName()
    {
        String answer = call("resources/read", //$NON-NLS-1$
            "{\"uri\":\"" + McpServerMeta.RESOURCE_GUIDANCE + "/x\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(answer, McpServerMeta.ERROR_INVALID_PARAMS, //$NON-NLS-1$
            resultOfError(answer).get("code").getAsInt()); //$NON-NLS-1$
        assertTrue(answer, answer.contains(McpServerMeta.RESOURCE_GUIDANCE)); //$NON-NLS-1$
        assertFalse("a refusal must not hand back a document", answer.contains("\"contents\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A read without a uri is refused for that reason, not answered with an empty document.
     * <p>
     * An empty document reads as "this resource is empty", which is a different and untrue claim.
     * </p>
     */
    @Test
    public void aReadWithoutAUriIsRefusedWithTheReason()
    {
        String answer = call("resources/read", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("uri is required")); //$NON-NLS-1$
        assertTrue("invalid params, not method not found: " + answer, //$NON-NLS-1$
            answer.contains("-32602")); //$NON-NLS-1$
    }

    /** A uri under no scheme this server serves is named as such, with both families named. */
    @Test
    public void aUriThisServerDoesNotServeIsNamed()
    {
        String answer = call("resources/read", "{\"uri\":\"file:///etc/passwd\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("-32602")); //$NON-NLS-1$
        assertTrue("the refusal must say what IS served: " + answer, //$NON-NLS-1$
            answer.contains("aiedt://checks/")); //$NON-NLS-1$
        assertTrue("and it must name the route document too: " + answer, //$NON-NLS-1$
            answer.contains(McpServerMeta.RESOURCE_GUIDANCE));
        assertFalse("and must not hand back a document", answer.contains("\"contents\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Announcing a capability is a promise that the methods behind it answer.
     * <p>
     * The handshake and discovery must make the same promise: a client is entitled to trust either
     * one, and a capability present in one and absent from the other makes the server's behaviour
     * depend on which the client happened to ask.
     * </p>
     */
    @Test
    public void bothAnnouncementsAgreeOnWhatIsOffered()
    {
        String handshake = call("initialize", //$NON-NLS-1$
            "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}"); //$NON-NLS-1$
        String discovery = call("server/discover", null); //$NON-NLS-1$

        assertTrue("the handshake must announce resources: " + handshake, //$NON-NLS-1$
            handshake.contains("\"resources\"")); //$NON-NLS-1$
        assertTrue("and so must discovery: " + discovery, //$NON-NLS-1$
            discovery.contains("\"resources\"")); //$NON-NLS-1$
        assertTrue(handshake.contains("\"tools\"")); //$NON-NLS-1$
        assertTrue(discovery.contains("\"tools\"")); //$NON-NLS-1$
    }

    /**
     * Both announcements carry the same guidance, whatever preset is in force.
     * <p>
     * {@code server/discover} is cached by the client, so the text may not depend on the current
     * sets: it is built under every named preset and has to come out the same, without the marks
     * the route document computes or the preset line it prints.
     * </p>
     */
    @Test
    public void announcementsCarryTheSamePresetIndependentGuidance()
    {
        var store = Activator.getDefault().getPreferenceStore();
        String preset = store.getString(PrefKeys.PREF_TOOL_PRESET);
        String disabled = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        String unlisted = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
        try
        {
            String expected = null;
            int count = 0;
            for (ToolProfile profile : ToolProfile.values())
            {
                if (profile == ToolProfile.CUSTOM)
                    continue;
                count++;
                ToolSettingsStore.getInstance().applyPreset(profile);
                String discovery = resultOf(call("server/discover", null)).get("instructions").getAsString();
                if (expected == null)
                    expected = discovery;
                assertEquals(expected, discovery);
                assertTrue(discovery.contains("aiedt://guidance"));
                assertTrue(discovery.contains("HTTP"));
                assertTrue(discovery.length() <= 1200);
                assertFalse(discovery.contains("Tool preset:"));
                assertFalse(discovery.contains("matchPreset"));
                // The marks the route document computes at read time, in the shapes it prints them.
                assertFalse(discovery.contains("available via"));
                assertFalse(discovery.contains("hidden, callable"));
                assertFalse(discovery.contains("off by preset"));
                assertFalse(discovery.contains("off: "));
                for (String version : McpServerMeta.HANDSHAKE_PROTOCOL_VERSIONS)
                {
                    JsonObject result = resultOf(call("initialize", "{\"protocolVersion\":\"" + version
                        + "\",\"capabilities\":{}}"));
                    assertEquals(version, result.get("protocolVersion").getAsString());
                    assertEquals(discovery, result.get("instructions").getAsString());
                }
            }
            assertEquals(6, count);
        }
        finally
        {
            store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabled);
            store.setValue(PrefKeys.PREF_UNLISTED_TOOLS, unlisted);
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
        }
    }

}
