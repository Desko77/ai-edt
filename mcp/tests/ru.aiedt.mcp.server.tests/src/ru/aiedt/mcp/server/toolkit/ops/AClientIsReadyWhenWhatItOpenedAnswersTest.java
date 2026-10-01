/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import ru.aiedt.mcp.server.support.EndpointWaiter;

/**
 * A launch reports ready when what it opened answers, not when a process exists.
 *
 * <p>The wait and its budget are one rule on every surface the launch comes from: a GET, ready is
 * any final status below 500, at most five redirects, one read is never longer than 5 seconds, and
 * the wait itself never outlives 50 seconds - the same limit a request may not outlive anywhere in
 * this server. A client that never gets there is reported, not killed.
 */
public class AClientIsReadyWhenWhatItOpenedAnswersTest
{
    /**
     * The rule lives in one helper on both launchers' path.
     */
    @Test
    public void theWaitLivesInOneHelper()
    {
        assertTrue(EndpointWaiter.MAX_SINGLE_REQUEST_MS == 5000);
        assertTrue(EndpointWaiter.MAX_REDIRECTS == 5);
        assertNotNull(EndpointWaiter.class.getDeclaredMethods());
    }

    /**
     * Every schema the launch comes from advertises both arguments.
     */
    @Test
    public void everySchemaAdvertisesTheWait()
    {
        for (String schema : new String[] {
            new DebugSessionStarter().getInputSchema(),
            new ClientSessionStarter().getInputSchema(),
            new LaunchDebuggerTool().getInputSchema(),
            new InfobaseAdminFacadeTool().getInputSchema() })
        {
            assertTrue(schema, schema.contains("waitForEndpoint")); //$NON-NLS-1$
            assertTrue(schema, schema.contains("endpointTimeoutSeconds")); //$NON-NLS-1$
        }
    }

    /**
     * A redirect that answers every hop with another hop spends the whole budget when each
     * transition is not counted: the limit never trips. The wait has to refuse the chain once it
     * passes the limit, and say how far it went.
     */
    @Test
    public void aRedirectLoopIsRefusedWithTheHopCount() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
        server.createContext("/loop", exchange -> //$NON-NLS-1$
        {
            exchange.getResponseHeaders().set("Location", "/loop"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try
        {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/loop"; //$NON-NLS-1$ //$NON-NLS-2$
            EndpointWaiter.Outcome outcome = EndpointWaiter.waitFor(url, 5000);

            assertFalse(outcome.ready);
            assertNotNull(outcome.lastProblem);
            assertTrue(outcome.lastProblem, outcome.lastProblem.contains("redirect")); //$NON-NLS-1$
            // Six hops were followed when the limit of five tripped.
            assertTrue(outcome.lastProblem, outcome.lastProblem.contains("6")); //$NON-NLS-1$
        }
        finally
        {
            server.stop(0);
        }
    }

    /**
     * A chain inside the limit is followed to its end, and the end's answer is the readiness.
     */
    @Test
    public void aRedirectWithinTheLimitIsFollowed() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
        server.createContext("/start", exchange -> //$NON-NLS-1$
        {
            exchange.getResponseHeaders().set("Location", "/final"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> //$NON-NLS-1$
        {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try
        {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/start"; //$NON-NLS-1$ //$NON-NLS-2$
            EndpointWaiter.Outcome outcome = EndpointWaiter.waitFor(url, 5000);

            assertTrue(outcome.lastProblem, outcome.ready);
            assertEquals(200, outcome.httpStatus);
            assertTrue(outcome.asked, outcome.asked.endsWith("/final")); //$NON-NLS-1$
        }
        finally
        {
            server.stop(0);
        }
    }
}
