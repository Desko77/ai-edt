/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.PrefKeys;

/**
 * The origin gate answers the same on every branch a browser can take: the preflight, the info and
 * stream GET, the JSON-RPC POST and the session DELETE. One branch that forgets the gate is the
 * one a page from the wrong site will find.
 */
public class TheOriginGateHoldsOnEveryBranchTest
{
    private static final int FORBIDDEN = 403;

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"; //$NON-NLS-1$

    private static final String CALL_VERSION = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," //$NON-NLS-1$
        + "\"params\":{\"name\":\"get_edt_version\",\"arguments\":{}}}"; //$NON-NLS-1$

    private static final String[] ACCEPTED = {
        "http://localhost", //$NON-NLS-1$
        "http://localhost:3000", //$NON-NLS-1$
        "HTTP://LOCALHOST", //$NON-NLS-1$
        "http://127.0.0.1:5173", //$NON-NLS-1$
        "http://[::1]:8080", //$NON-NLS-1$
        "https://localhost", //$NON-NLS-1$
        "vscode-webview://abc123"}; //$NON-NLS-1$

    private static final String[] REJECTED = {
        "http://localhost.evil.example", //$NON-NLS-1$
        "https://localhost.evil.example", //$NON-NLS-1$
        "http://127.0.0.1.evil.example", //$NON-NLS-1$
        "http://localhostx", //$NON-NLS-1$
        "http://user@localhost", //$NON-NLS-1$
        "http://localhost/path", //$NON-NLS-1$
        "http://localhost?x=1", //$NON-NLS-1$
        "http://localhost#f", //$NON-NLS-1$
        "http://localhost:99999", //$NON-NLS-1$
        "file:///c:/page.html", //$NON-NLS-1$
        "not an origin at all"}; //$NON-NLS-1$

    private LiveServer server;

    private IPreferenceStore store;

    private boolean nullOriginBefore;

    @Before
    public void startTheServer() throws IOException
    {
        store = Activator.getDefault().getPreferenceStore();
        nullOriginBefore = store.getBoolean(PrefKeys.PREF_ALLOW_NULL_ORIGIN);
        store.setValue(PrefKeys.PREF_ALLOW_NULL_ORIGIN, false);
        server = LiveServer.start();
    }

    @After
    public void stopTheServer()
    {
        store.setValue(PrefKeys.PREF_ALLOW_NULL_ORIGIN, nullOriginBefore);
        server.close();
    }

    @Test
    public void aForgedLoopbackNameIsTurnedAwayOnEveryBranch() throws IOException
    {
        for (String origin : REJECTED)
        {
            for (Branch branch : Branch.values())
            {
                assertEquals(branch + " " + origin, FORBIDDEN, send(branch, origin).code); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void aRealLoopbackOriginPassesOnEveryBranch() throws IOException
    {
        for (String origin : ACCEPTED)
        {
            for (Branch branch : Branch.values())
            {
                LiveServer.Response answer = send(branch, origin);
                assertEquals(branch + " " + origin, branch.expectedCode, answer.code); //$NON-NLS-1$
                assertEquals(branch + " " + origin, origin, answer.header("Access-Control-Allow-Origin")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    @Test
    public void aPageWithoutAnOriginIsTurnedAwayUntilTheOperatorAllowsIt() throws IOException
    {
        for (Branch branch : Branch.values())
        {
            assertEquals(branch.toString(), FORBIDDEN, send(branch, BrowserOrigin.NULL_ORIGIN).code);
        }
        store.setValue(PrefKeys.PREF_ALLOW_NULL_ORIGIN, true);
        for (Branch branch : Branch.values())
        {
            assertEquals(branch.toString(), branch.expectedCode, send(branch, BrowserOrigin.NULL_ORIGIN).code);
        }
    }

    @Test
    public void aClientWithNoOriginRunsATool() throws IOException
    {
        LiveServer.Response answer = server.request("POST", "/mcp", //$NON-NLS-1$ //$NON-NLS-2$
            LiveServer.headers("Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
                "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
                "Authorization", server.bearer()), //$NON-NLS-1$
            CALL_VERSION);
        assertEquals(answer.body, 200, answer.code);
        assertTrue(answer.body, answer.body.contains("\"result\"")); //$NON-NLS-1$
        assertFalse(answer.body, answer.body.contains("\"error\"")); //$NON-NLS-1$
        assertNotEquals(FORBIDDEN, answer.code);
    }

    private LiveServer.Response send(Branch branch, String origin) throws IOException
    {
        Map<String, String> headers = LiveServer.headers("Origin", origin, //$NON-NLS-1$
            "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Authorization", server.bearer()); //$NON-NLS-1$
        return server.request(branch.method, "/mcp", headers, branch.body); //$NON-NLS-1$
    }

    /** The four ways into the endpoint, and what each answers when the origin passes. */
    private enum Branch
    {
        PREFLIGHT("OPTIONS", null, 204), //$NON-NLS-1$
        INFO("GET", null, 200), //$NON-NLS-1$
        RPC("POST", PING, 200), //$NON-NLS-1$
        SESSION_END("DELETE", null, 200); //$NON-NLS-1$

        final String method;

        final String body;

        final int expectedCode;

        Branch(String method, String body, int expectedCode)
        {
            this.method = method;
            this.body = body;
            this.expectedCode = expectedCode;
        }
    }
}
