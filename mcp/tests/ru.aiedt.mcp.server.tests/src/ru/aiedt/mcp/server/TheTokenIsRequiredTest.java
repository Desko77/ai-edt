/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.McpAuth;
import ru.aiedt.mcp.server.settings.PrefKeys;

/**
 * Every request to {@code /mcp} carries the bearer token, and the token reaches the disk before it
 * answers a single request.
 */
public class TheTokenIsRequiredTest
{
    private static final int OK = 200;

    private static final int UNAUTHORIZED = 401;

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"; //$NON-NLS-1$

    private static final String REGISTRY_DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    private LiveServer server;

    private String tokenBefore;

    private boolean switchBefore;

    private boolean workspaceStoreTouched;

    @After
    public void putEverythingBack()
    {
        if (server != null)
        {
            server.close();
        }
        McpAuth.useStore(null);
        McpAuth.forgetPublished();
        if (workspaceStoreTouched)
        {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            store.setValue(PrefKeys.PREF_AUTH_TOKEN, tokenBefore);
            store.setValue(PrefKeys.PREF_AUTH_ENABLED, switchBefore);
        }
    }

    // ---- 1 and 2: the old switch decides nothing --------------------------------------------

    @Test
    public void aWorkspaceWithTheSwitchOffAndNoTokenRequiresOne() throws IOException
    {
        IPreferenceStore store = rememberTheWorkspaceStore();
        store.setValue(PrefKeys.PREF_AUTH_ENABLED, false);
        store.setValue(PrefKeys.PREF_AUTH_TOKEN, ""); //$NON-NLS-1$

        server = LiveServer.start(null);

        assertEquals(UNAUTHORIZED, ping(null).code);
        String created = store.getString(PrefKeys.PREF_AUTH_TOKEN);
        assertFalse("a token was created and stored", created.isEmpty()); //$NON-NLS-1$
        assertEquals(OK, ping("Bearer " + created).code); //$NON-NLS-1$
    }

    @Test
    public void aWorkspaceWithTheSwitchOffAndAStoredTokenRequiresThatToken() throws IOException
    {
        IPreferenceStore store = rememberTheWorkspaceStore();
        store.setValue(PrefKeys.PREF_AUTH_ENABLED, false);
        store.setValue(PrefKeys.PREF_AUTH_TOKEN, "stored-before-the-update"); //$NON-NLS-1$

        server = LiveServer.start(null);

        assertEquals(UNAUTHORIZED, ping(null).code);
        assertEquals(UNAUTHORIZED, ping("Bearer some-other-token").code); //$NON-NLS-1$
        assertEquals(OK, ping("Bearer stored-before-the-update").code); //$NON-NLS-1$
        assertEquals("stored-before-the-update", store.getString(PrefKeys.PREF_AUTH_TOKEN)); //$NON-NLS-1$
    }

    // ---- 3: created once, kept afterwards ---------------------------------------------------

    @Test
    public void anEmptyStoreGetsATokenOnTheFirstStartAndKeepsItOnTheSecond() throws IOException
    {
        LiveServer.MemoryTokenStore tokens = new LiveServer.MemoryTokenStore(""); //$NON-NLS-1$
        server = LiveServer.start(tokens);
        String first = tokens.read();
        assertFalse(first.isEmpty());
        assertEquals(first, server.token());
        server.close();

        server = LiveServer.start(tokens);
        assertEquals(first, tokens.read());
        assertEquals(first, server.token());
    }

    // ---- 4 and 10: no flush, no socket -----------------------------------------------------

    @Test
    public void theSocketStaysClosedWhenTheTokenCannotBeSaved() throws IOException
    {
        BrokenFlushStore tokens = new BrokenFlushStore(""); //$NON-NLS-1$
        int port = LiveServer.freePort();
        java.nio.file.Path registry = java.nio.file.Files.createTempDirectory("aiedt-refused"); //$NON-NLS-1$
        String registryBefore = System.getProperty(REGISTRY_DIR_PROPERTY);
        System.setProperty(REGISTRY_DIR_PROPERTY, registry.toString());
        McpAuth.useStore(tokens);
        McpAuth.forgetPublished();
        McpHttpEndpoint endpoint = new McpHttpEndpoint();
        try
        {
            endpoint.start(port);
            fail("the server started without a saved token"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("preference store")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains("disk is full")); //$NON-NLS-1$
        }
        finally
        {
            endpoint.stop();
            if (registryBefore == null)
            {
                System.clearProperty(REGISTRY_DIR_PROPERTY);
            }
            else
            {
                System.setProperty(REGISTRY_DIR_PROPERTY, registryBefore);
            }
            java.nio.file.Files.deleteIfExists(registry);
        }
        assertFalse(endpoint.isRunning());
        assertEquals("the store was put back to what it held", "", tokens.read()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(McpAuth.activeToken());
        try (Socket probe = new Socket("127.0.0.1", port)) //$NON-NLS-1$
        {
            fail("something is listening on the port the server refused to open"); //$NON-NLS-1$
        }
        catch (ConnectException nobody)
        {
            // What a closed port answers.
        }
    }

    // ---- 5: the refusal names the page, not the token --------------------------------------

    @Test
    public void aRequestWithoutTheTokenIsRefusedWithoutRevealingIt() throws IOException
    {
        server = LiveServer.start();
        LiveServer.Response answer = ping(null);
        assertEquals(UNAUTHORIZED, answer.code);
        assertFalse(answer.body, answer.body.contains(server.token()));
        assertTrue(answer.body, answer.body.contains("preference page")); //$NON-NLS-1$
        assertNotNull(answer.header("WWW-Authenticate")); //$NON-NLS-1$

        LiveServer.Response wrong = ping("Bearer not-the-token"); //$NON-NLS-1$
        assertEquals(UNAUTHORIZED, wrong.code);
        assertFalse(wrong.body, wrong.body.contains(server.token()));
    }

    // ---- 6: the token is in no log line -----------------------------------------------------

    @Test
    public void theTokenNeverReachesTheLog() throws IOException
    {
        List<String> logged = new ArrayList<>();
        ILogListener listener = (status, plugin) -> {
            synchronized (logged)
            {
                logged.add(String.valueOf(status.getMessage()));
                if (status.getException() != null)
                {
                    logged.add(String.valueOf(status.getException().getMessage()));
                }
            }
        };
        Activator.getDefault().getLog().addLogListener(listener);
        String first;
        String second = "second-" + McpAuth.generateToken(); //$NON-NLS-1$
        try
        {
            server = LiveServer.start(new LiveServer.MemoryTokenStore("")); //$NON-NLS-1$
            first = server.token();
            ping(null);
            ping(server.bearer());
            assertNull(McpAuth.publish(second));
            ping("Bearer " + second); //$NON-NLS-1$
            server.close();
            server = null;
        }
        finally
        {
            Activator.getDefault().getLog().removeLogListener(listener);
        }
        synchronized (logged)
        {
            assertFalse("the log carried something", logged.isEmpty()); //$NON-NLS-1$
            for (String line : logged)
            {
                assertFalse(line, line.contains(first));
                assertFalse(line, line.contains(second));
            }
        }
    }

    // ---- 7: /health says three things to a stranger ---------------------------------------

    @Test
    public void healthTellsAStrangerThreeThingsAndTheHolderEverything() throws IOException
    {
        server = LiveServer.start();
        Set<String> liveness = new TreeSet<>();
        liveness.add("status"); //$NON-NLS-1$
        liveness.add("phase"); //$NON-NLS-1$
        liveness.add("edt_version"); //$NON-NLS-1$

        assertEquals(liveness, keysOf(health(null)));
        assertEquals(liveness, keysOf(health("Bearer not-the-token"))); //$NON-NLS-1$

        Set<String> full = keysOf(health(server.bearer()));
        assertTrue(full.toString(), full.containsAll(liveness));
        assertTrue(full.toString(), full.contains("instance")); //$NON-NLS-1$
        assertTrue(full.toString(), full.contains("heapFreeMb")); //$NON-NLS-1$
        assertTrue(full.toString(), full.contains("uiResponding")); //$NON-NLS-1$
    }

    // ---- 8: an empty value on Apply is a new token ------------------------------------------

    @Test
    public void publishingAnEmptyValueMakesANewToken() throws IOException
    {
        LiveServer.MemoryTokenStore tokens = new LiveServer.MemoryTokenStore("one"); //$NON-NLS-1$
        server = LiveServer.start(tokens);
        assertNull(McpAuth.publish("   ")); //$NON-NLS-1$
        String made = McpAuth.activeToken();
        assertFalse(made.isEmpty());
        assertFalse("one".equals(made)); //$NON-NLS-1$
        assertEquals(made, tokens.read());
        assertEquals(OK, ping("Bearer " + made).code); //$NON-NLS-1$
        assertEquals(UNAUTHORIZED, ping("Bearer one").code); //$NON-NLS-1$
    }

    // ---- 9: the previous token holds until the flush is through ----------------------------

    @Test
    public void thePreviousTokenHoldsWhileTheNewOneIsBeingSaved() throws Exception
    {
        DelayedFlushStore tokens = new DelayedFlushStore("before"); //$NON-NLS-1$
        server = LiveServer.start(tokens);
        assertEquals(OK, ping("Bearer before").code); //$NON-NLS-1$

        AtomicReference<String> refusal = new AtomicReference<>();
        Thread publishing = new Thread(() -> refusal.set(McpAuth.publish("after"))); //$NON-NLS-1$
        publishing.start();
        assertTrue("the flush began", tokens.flushing.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

        // The store already holds the new value; the wire still answers to the old one.
        assertEquals("after", tokens.read()); //$NON-NLS-1$
        assertEquals(OK, ping("Bearer before").code); //$NON-NLS-1$
        assertEquals(UNAUTHORIZED, ping("Bearer after").code); //$NON-NLS-1$

        tokens.release.countDown();
        publishing.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(publishing.isAlive());
        assertNull(refusal.get());
        assertEquals(OK, ping("Bearer after").code); //$NON-NLS-1$
        assertEquals(UNAUTHORIZED, ping("Bearer before").code); //$NON-NLS-1$
    }

    // ---- 11: a failed save publishes nothing ------------------------------------------------

    @Test
    public void aFailedSaveKeepsThePreviousTokenInForce() throws IOException
    {
        BrokenFlushStore tokens = new BrokenFlushStore("keeps"); //$NON-NLS-1$
        server = LiveServer.start(tokens);
        assertEquals(OK, ping("Bearer keeps").code); //$NON-NLS-1$

        String refusal = McpAuth.publish("would-be-new"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("previous token stays")); //$NON-NLS-1$
        assertEquals("keeps", McpAuth.activeToken()); //$NON-NLS-1$
        assertEquals("the store was put back", "keeps", tokens.read()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(OK, ping("Bearer keeps").code); //$NON-NLS-1$
        assertEquals(UNAUTHORIZED, ping("Bearer would-be-new").code); //$NON-NLS-1$
    }

    private IPreferenceStore rememberTheWorkspaceStore()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        tokenBefore = store.getString(PrefKeys.PREF_AUTH_TOKEN);
        switchBefore = store.getBoolean(PrefKeys.PREF_AUTH_ENABLED);
        workspaceStoreTouched = true;
        return store;
    }

    private LiveServer.Response ping(String authorization) throws IOException
    {
        return server.request("POST", "/mcp", requestHeaders(authorization), PING); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private JsonObject health(String authorization) throws IOException
    {
        LiveServer.Response answer = server.request("GET", "/health", requestHeaders(authorization), null); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(answer.body, OK, answer.code);
        return JsonParser.parseString(answer.body).getAsJsonObject();
    }

    private static java.util.Map<String, String> requestHeaders(String authorization)
    {
        java.util.Map<String, String> headers = LiveServer.headers(
            "Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Accept", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        if (authorization != null)
        {
            headers.put("Authorization", authorization); //$NON-NLS-1$
        }
        return headers;
    }

    private static Set<String> keysOf(JsonObject object)
    {
        return new TreeSet<>(object.keySet());
    }

    /** A store whose flush never reaches the disk. */
    private static final class BrokenFlushStore
        extends LiveServer.MemoryTokenStore
    {
        BrokenFlushStore(String token)
        {
            super(token);
        }

        @Override
        public void flush() throws BackingStoreException
        {
            throw new BackingStoreException("disk is full"); //$NON-NLS-1$
        }
    }

    /** A store whose flush waits until the test lets it go. */
    private static final class DelayedFlushStore
        extends LiveServer.MemoryTokenStore
    {
        final CountDownLatch flushing = new CountDownLatch(1);

        final CountDownLatch release = new CountDownLatch(1);

        DelayedFlushStore(String token)
        {
            super(token);
        }

        @Override
        public void flush() throws BackingStoreException
        {
            flushing.countDown();
            try
            {
                if (!release.await(10, TimeUnit.SECONDS))
                {
                    throw new BackingStoreException("the test never let the flush go"); //$NON-NLS-1$
                }
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new BackingStoreException("interrupted"); //$NON-NLS-1$
            }
        }
    }
}
