/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.McpAuth;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.InstanceRegistry;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * What a restart leaves behind when the port it was asked for is taken.
 * <p>
 * A restart is a stop followed by a start, and the port comes from a field a user types into, so the
 * port being held by another program is an ordinary event rather than an exotic one. Stopping first
 * means the refusal lands on a server that is no longer listening: a client that was connected is
 * now refused, the strip reads "off", and the only way back is to remember which port the server had
 * been on. The endpoint therefore goes back to that port, and only gives up when that is gone too.
 * </p>
 * <p>
 * The port span is held at one for the whole test: with the shipped span of ten, a restart that
 * cannot take the port asked for would quietly take the next one, which is the behaviour being
 * distinguished from the fallback here.
 * </p>
 */
public class ARestartThatCannotTakeThePortStaysOnTheOldOneTest
{
    private static final String REGISTRY_DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    /** The address the endpoint binds first, and the one a holder has to take to refuse it. */
    private static final String LOOPBACK = "127.0.0.1"; //$NON-NLS-1$

    private Path registry;

    private String previousRegistryDir;

    private IPreferenceStore store;

    private String spanBefore;

    private McpHttpEndpoint endpoint;

    /**
     * Starts an endpoint on a free port, with the span at one and the instance registry in a
     * temporary directory of its own.
     *
     * @throws Exception when the endpoint cannot be started
     */
    @Before
    public void startAnEndpointOnOnePort() throws Exception
    {
        registry = Files.createTempDirectory("aiedt-restart"); //$NON-NLS-1$
        previousRegistryDir = System.getProperty(REGISTRY_DIR_PROPERTY);
        System.setProperty(REGISTRY_DIR_PROPERTY, registry.toString());
        store = Activator.getDefault().getPreferenceStore();
        spanBefore = String.valueOf(store.getInt(PrefKeys.PREF_PORT_SPAN));
        store.setValue(PrefKeys.PREF_PORT_SPAN, 1);
        McpAuth.useStore(new LiveServer.MemoryTokenStore("restart-" + McpAuth.generateToken())); //$NON-NLS-1$
        McpAuth.forgetPublished();
        endpoint = new McpHttpEndpoint();
        endpoint.start(LiveServer.freePort());
    }

    /**
     * Stops the endpoint and puts the span, the token store and the registry directory back.
     *
     * @throws Exception when the temporary registry cannot be removed
     */
    @After
    public void letGoOfEverything() throws Exception
    {
        endpoint.stop();
        McpToolCatalog.getInstance().clear();
        McpAuth.useStore(null);
        McpAuth.forgetPublished();
        store.setValue(PrefKeys.PREF_PORT_SPAN, spanBefore);
        InstanceRegistry.withdraw();
        if (previousRegistryDir == null)
        {
            System.clearProperty(REGISTRY_DIR_PROPERTY);
        }
        else
        {
            System.setProperty(REGISTRY_DIR_PROPERTY, previousRegistryDir);
        }
        try (Stream<Path> entries = Files.walk(registry))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** The endpoint comes back on the port it was serving on, and answers there. */
    @Test
    public void theServerComesBackOnThePortItWasServingOn() throws IOException
    {
        int wasOn = endpoint.getPort();
        int taken = LiveServer.freePort();
        try (ServerSocket holder = hold(taken))
        {
            endpoint.restart(taken);

            assertTrue("a restart that could not move must leave the server listening", //$NON-NLS-1$
                endpoint.isRunning());
            assertEquals("and it must be back on the port it was serving on", wasOn, //$NON-NLS-1$
                endpoint.getPort());
            try (Socket probe = new Socket(LOOPBACK, wasOn))
            {
                assertTrue("the port it went back to must answer", probe.isConnected()); //$NON-NLS-1$
            }
        }
    }

    /** The refusal that comes out names the port that was asked for, not the one fallen back to. */
    @Test
    public void theRefusalNamesThePortThatWasAskedFor() throws IOException
    {
        int wasOn = endpoint.getPort();
        int taken = LiveServer.freePort();
        // The old port is let go of first: the fallback has to fail because something holds it, not
        // because this endpoint still does.
        endpoint.stop();
        try (ServerSocket oldPort = hold(wasOn); ServerSocket newPort = hold(taken))
        {
            try
            {
                endpoint.restart(taken);
                fail("a restart that can take neither port has to refuse"); //$NON-NLS-1$
            }
            catch (IOException refused)
            {
                assertTrue("the answer has to name the port the caller set: " + refused.getMessage(), //$NON-NLS-1$
                    refused.getMessage().contains(String.valueOf(taken)));
                assertFalse("and it must not name the port that was only fallen back to", //$NON-NLS-1$
                    refused.getMessage().contains(String.valueOf(wasOn)));
            }
        }
        assertFalse("nothing may be left listening", endpoint.isRunning()); //$NON-NLS-1$
    }

    /**
     * Takes the loopback address of a port, which is the address the endpoint binds first.
     *
     * @param port the port to hold
     * @return the holder, to be closed by the caller
     * @throws IOException when the port cannot be taken
     */
    private static ServerSocket hold(int port) throws IOException
    {
        long deadline = System.currentTimeMillis() + 5000L;
        while (true)
        {
            try
            {
                return new ServerSocket(port, 1, InetAddress.getByName(LOOPBACK));
            }
            catch (IOException taken)
            {
                // The endpoint that has just stopped may still be letting go of its listener.
                if (System.currentTimeMillis() >= deadline)
                {
                    throw taken;
                }
                try
                {
                    Thread.sleep(100L);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw taken;
                }
            }
        }
    }
}
