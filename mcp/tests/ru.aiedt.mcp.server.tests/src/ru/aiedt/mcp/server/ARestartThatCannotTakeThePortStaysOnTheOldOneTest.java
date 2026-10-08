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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * The port span is held at one except where a test widens it on purpose: with the shipped span of
 * ten, a start walks forward, and a fallback written as an ordinary start would quietly take the
 * next free port and report that the server had stayed where it was.
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

    /** The refusal that comes out names both ports, so whoever reads it knows what was tried. */
    @Test
    public void theRefusalNamesBothPorts() throws IOException
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
                assertTrue("and the port the server had been serving on: " + refused.getMessage(), //$NON-NLS-1$
                    refused.getMessage().contains(String.valueOf(wasOn)));
            }
        }
        assertFalse("nothing may be left listening", endpoint.isRunning()); //$NON-NLS-1$
    }

    /**
     * Going back to the old port takes that port alone, not a run of ports starting at it.
     * <p>
     * With a span wider than one, a fallback that walked would find the free port next to the old
     * one, bind it and return as though the move had been refused and the server left where it was:
     * the strip, the registry and every client would name a port nothing is listening on.
     * </p>
     */
    @Test
    public void theFallbackDoesNotMoveTheServerToTheNextPort() throws IOException
    {
        int wasOn = endpoint.getPort();
        // The span is the shipped kind of walk, set for this test alone: the port after the old one
        // is left free on purpose, so a fallback that walked would take it and answer success.
        store.setValue(PrefKeys.PREF_PORT_SPAN, 2);
        int askedFor = wasOn + 2;
        // Holding these two makes the first start fail over its whole span; the port between them
        // stays free for the walk the fallback must not make.
        endpoint.stop();
        try (ServerSocket oldPort = hold(wasOn);
            ServerSocket firstAsked = hold(askedFor);
            ServerSocket secondAsked = hold(askedFor + 1))
        {
            try
            {
                endpoint.restart(askedFor);
                fail("a fallback that cannot have the old port has to refuse, not move aside"); //$NON-NLS-1$
            }
            catch (IOException refused)
            {
                assertTrue("the answer has to name the port the caller set: " + refused.getMessage(), //$NON-NLS-1$
                    refused.getMessage().contains(String.valueOf(askedFor)));
                assertTrue("and the port the server was on: " + refused.getMessage(), //$NON-NLS-1$
                    refused.getMessage().contains(String.valueOf(wasOn)));
            }
            assertFalse("nothing may be left listening", endpoint.isRunning()); //$NON-NLS-1$
            // A bind that succeeds is the whole assertion: had the fallback moved aside, the port
            // next to the old one would already be this process's and this would not open.
            try (ServerSocket neighbour = new ServerSocket(wasOn + 1, 1,
                InetAddress.getByName(LOOPBACK)))
            {
                assertTrue("the port next to the old one must still be free", //$NON-NLS-1$
                    neighbour.isBound());
            }
        }
    }

    /**
     * A stop arriving while the fallback is opening the old port waits for it, and closes what it
     * opened.
     * <p>
     * The fallback runs a start, and a start is what the status bar and the auto-start call the
     * endpoint with as well. A restart cannot hold the monitor itself - it calls the stop and the
     * start that do - so the fallback has to take that monitor on its own. Without it, a stop
     * arriving between the refused start and the fallback runs against an endpoint that is not
     * listening, finds nothing to close and returns, and the fallback then opens a listener that
     * nothing is left to close: the endpoint is answering on a port its own stop was asked to free.
     * </p>
     * <p>
     * The window is held open by the endpoint's own seam, so the stop is put beside the fallback at
     * a known moment rather than at whatever moment a bare race happens to give.
     * </p>
     */
    @Test
    public void aStopDuringTheFallbackWaitsForItAndClosesWhatItOpened() throws Exception
    {
        int wasOn = endpoint.getPort();
        int taken = LiveServer.freePort();
        CountDownLatch atTheFallback = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        endpoint.fallbackWindowForTest = () ->
        {
            atTheFallback.countDown();
            await(release);
        };
        try (ServerSocket holder = hold(taken))
        {
            Thread restarting = new Thread(() -> restartKeepingTheRefusalOut(taken), "a-restart"); //$NON-NLS-1$
            restarting.start();
            assertTrue("the fallback has to be reached", //$NON-NLS-1$
                atTheFallback.await(10, TimeUnit.SECONDS));

            Thread stopping = new Thread(endpoint::stop, "a-stop"); //$NON-NLS-1$
            stopping.start();
            try
            {
                assertTrue("the stop has to wait for the fallback", blockedFor(stopping)); //$NON-NLS-1$
            }
            finally
            {
                // Whatever the line above decided, the fallback has to be let go: the monitor it
                // holds is the one the endpoint's own teardown waits for.
                release.countDown();
            }
            restarting.join(TimeUnit.SECONDS.toMillis(20));
            stopping.join(TimeUnit.SECONDS.toMillis(20));
        }
        assertFalse("nothing may be left listening", endpoint.isRunning()); //$NON-NLS-1$
        // A bind that succeeds is the whole assertion: had the fallback opened after the stop
        // returned, this port would still be this process's and this would not open.
        try (ServerSocket probe = hold(wasOn))
        {
            assertTrue("the port the fallback had to be closed on has to be free", probe.isBound()); //$NON-NLS-1$
        }
    }

    /**
     * Runs a restart on its own thread, keeping the refusal it can end in out of that thread's way.
     *
     * @param port the port to move to, which this test holds
     */
    private void restartKeepingTheRefusalOut(int port)
    {
        try
        {
            endpoint.restart(port);
        }
        catch (IOException refused)
        {
            // Expected when the fallback cannot have the old port either; the test is about the
            // ordering, not about which of the two answers comes back.
        }
    }

    /**
     * Waits for a thread to be waiting for a monitor, which is what waiting for the endpoint's own
     * looks like from the outside.
     *
     * @param thread the thread
     * @return <code>true</code> when it was seen waiting; <code>false</code> when it finished, or
     *         was still not waiting when the time ran out
     * @throws InterruptedException when the test thread is interrupted
     */
    private static boolean blockedFor(Thread thread) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline)
        {
            if (thread.getState() == Thread.State.BLOCKED)
            {
                return true;
            }
            if (!thread.isAlive())
            {
                return false;
            }
            Thread.sleep(20L);
        }
        return false;
    }

    /**
     * Waits on a latch, for the seam the endpoint runs inside a restart.
     *
     * @param latch the latch
     */
    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
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
