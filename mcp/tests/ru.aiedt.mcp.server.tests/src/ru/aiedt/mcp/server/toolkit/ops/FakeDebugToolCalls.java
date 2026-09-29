/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * Calls a debug tool the way an agent does, against a fake suspended session.
 *
 * <p>The tools take their arguments as the strings an MCP request carries and answer with the JSON a
 * client reads, so a test drives the same two ends. The session is registered under its application
 * id, which is what ties the suspended thread - and the frames handed out for it - to a session the
 * tools will find.</p>
 */
final class FakeDebugToolCalls
{
    private FakeDebugToolCalls()
    {
        // helpers
    }

    /**
     * @param pairs argument names and values, one after another
     * @return the argument map, in the order given
     */
    static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * Records the session as suspended and answers the thread id the tools address it by.
     *
     * @param applicationId the application the session belongs to
     * @param session the session, with the frames it holds
     * @return the thread id
     */
    static long register(String applicationId, FakeDebugFrames.Session session)
    {
        DebugSessionBook registry = DebugSessionBook.get();
        registry.injectSuspend(applicationId, session.thread());
        return registry.getSnapshot(applicationId).threadId;
    }

    /**
     * @param answer what a tool returned
     * @return the document, parsed
     */
    static JsonObject json(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    /**
     * @param answer what a tool returned
     * @return what it says went wrong
     */
    static String error(String answer)
    {
        JsonObject document = json(answer);
        return document.has("error") ? document.get("error").getAsString() : document.toString(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
