/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

    /** A uri under no scheme this server serves is named as such. */
    @Test
    public void aUriThisServerDoesNotServeIsNamed()
    {
        String answer = call("resources/read", "{\"uri\":\"file:///etc/passwd\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("-32602")); //$NON-NLS-1$
        assertTrue("the refusal must say what IS served: " + answer, //$NON-NLS-1$
            answer.contains("aiedt://checks/")); //$NON-NLS-1$
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
}
