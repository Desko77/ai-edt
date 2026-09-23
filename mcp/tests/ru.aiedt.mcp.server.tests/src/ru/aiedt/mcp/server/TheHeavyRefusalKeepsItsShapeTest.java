/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.PendingExecutor;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ToolRoad;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;
import ru.aiedt.mcp.server.wire.JsonUtils;

/**
 * A heavy call the limiter turns away keeps the refusal it has always had: {@code 503}, the same
 * body, {@code Retry-After}.
 * <p>
 * The refusal is forced with a limit of one and a probe whose work outlives its Pending answer -
 * which is the new lifetime rule working on the wire: the permit stays with the background run, so
 * the second heavy call meets the limit while the first one's work is still going. The heap half
 * of the refusal is the same conversion - one code path turns a road refusal into this answer - and
 * its text is pinned where the road is tested ({@code TheBundlesWordForItsOwnWeightTest}).
 * </p>
 */
public class TheHeavyRefusalKeepsItsShapeTest
{
    /** Soft wait short enough that a held latch always turns into a Pending answer. */
    private static final long SOFT_MS = 150L;

    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private IPreferenceStore store;

    private String limitBefore;

    private LiveServer server;

    @Before
    public void aContextAndAOneHeavyLimit()
    {
        context = FrameworkUtil.getBundle(TheHeavyRefusalKeepsItsShapeTest.class).getBundleContext();
        store = Activator.getDefault().getPreferenceStore();
        limitBefore = String.valueOf(store.getInt(PrefKeys.PREF_HEAVY_TOOL_LIMIT));
        store.setValue(PrefKeys.PREF_HEAVY_TOOL_LIMIT, 1);
    }

    @After
    public void theServerTheProbesAndTheLimitGo()
    {
        if (server != null)
        {
            server.close();
            server = null;
        }
        for (ServiceRegistration<?> registration : registrations)
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // unregistered by the test itself
            }
        }
        store.setValue(PrefKeys.PREF_HEAVY_TOOL_LIMIT, limitBefore);
    }

    /** At the limit the wire answers 503 with the limit's text and Retry-After, as it always did. */
    @Test
    public void aSecondHeavyCallIsRefusedAsBefore() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        String pending = "road_wire_pending_" + System.nanoTime(); //$NON-NLS-1$
        String plain = "road_wire_plain_" + System.nanoTime(); //$NON-NLS-1$
        publish(new PendingWireProbe(pending, hold));
        publish(new PlainWireProbe(plain));
        waitFor(pending);
        waitFor(plain);
        server = LiveServer.start();

        String runKey = PendingWorkRegistry.computeRunKey(pending, "wire"); //$NON-NLS-1$
        LiveServer.Response first = server.request("POST", "/mcp", //$NON-NLS-1$ //$NON-NLS-2$
            LiveServer.headers("Authorization", server.bearer()),
            toolsCall(pending, Map.of("tag", "wire"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(200, first.code);
        assertTrue("the held work answered Pending with its runKey", first.body.contains(runKey)); //$NON-NLS-1$

        // The permit is held by the background work now, so the second heavy call meets the limit.
        LiveServer.Response refused = server.request("POST", "/mcp", //$NON-NLS-1$ //$NON-NLS-2$
            LiveServer.headers("Authorization", server.bearer()), toolsCall(plain, Map.of()));
        assertEquals("the limit refusal is a 503", 503, refused.code); //$NON-NLS-1$
        assertEquals(JsonUtils.buildSimpleError(ToolRoad.MSG_HEAVY_BUSY), refused.body);
        assertEquals("2", refused.header("Retry-After")); //$NON-NLS-1$ //$NON-NLS-2$

        // The work ends, the permit returns, and the same call goes through again.
        hold.countDown();
        assertTrue("the finished result came back", eventuallyCollected(server, plain)); //$NON-NLS-1$
    }

    /**
     * Repeats a plain call until it is no longer turned away, which is how the wire observes the
     * permit returning without being able to read the limiter.
     */
    private boolean eventuallyCollected(LiveServer live, String tool) throws Exception
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline)
        {
            LiveServer.Response answer = live.request("POST", "/mcp", //$NON-NLS-1$ //$NON-NLS-2$
                LiveServer.headers("Authorization", live.bearer()), toolsCall(tool, Map.of()));
            if (answer.code == 200 && answer.body.contains("ran")) //$NON-NLS-1$
            {
                return true;
            }
            Thread.sleep(50L);
        }
        return false;
    }

    private void publish(IMcpTool probe)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.TRUE);
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, probe, properties));
    }

    private static String toolsCall(String tool, Map<String, String> arguments)
    {
        StringBuilder params = new StringBuilder("{\"name\":\"" + tool + "\",\"arguments\":{"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean first = true;
        for (Map.Entry<String, String> argument : arguments.entrySet())
        {
            if (!first)
            {
                params.append(','); //$NON-NLS-1$
            }
            first = false;
            params.append('"').append(argument.getKey()).append("\":\"") //$NON-NLS-1$
                .append(argument.getValue()).append('"');
        }
        params.append("}}"); //$NON-NLS-1$
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":" //$NON-NLS-1$
            + params + "}"; //$NON-NLS-1$
    }

    private static void waitFor(String name)
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (McpToolCatalog.getInstance().getTool(name) == null)
        {
            if (System.currentTimeMillis() > deadline)
            {
                throw new IllegalStateException("the whiteboard never picked up " + name); //$NON-NLS-1$
            }
            sleep(20L);
        }
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e); //$NON-NLS-1$
        }
    }

    /** A heavy tool whose Pending envelope is a real one over the REFERENCES domain. */
    private static final class PendingWireProbe implements IMcpTool
    {
        private final String name;

        private final CountDownLatch hold;

        PendingWireProbe(String name, CountDownLatch hold)
        {
            this.name = name;
            this.hold = hold;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public ru.aiedt.mcp.server.toolkit.IMcpTool.ResponseType getResponseType()
        {
            return ru.aiedt.mcp.server.toolkit.IMcpTool.ResponseType.TEXT;
        }

        @Override
        public String execute(Map<String, String> params)
        {
            String runKey = PendingWorkRegistry.computeRunKey(name, params.get("tag")); //$NON-NLS-1$
            return PendingExecutor.execute(PendingWorkRegistry.REFERENCES, name, params, runKey,
                SOFT_MS, () -> {
                    try
                    {
                        hold.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    return "finished-wire"; //$NON-NLS-1$
                }, null);
        }
    }

    /** A heavy tool that answers at once. */
    private static final class PlainWireProbe implements IMcpTool
    {
        private final String name;

        PlainWireProbe(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "ran"; //$NON-NLS-1$
        }
    }
}
