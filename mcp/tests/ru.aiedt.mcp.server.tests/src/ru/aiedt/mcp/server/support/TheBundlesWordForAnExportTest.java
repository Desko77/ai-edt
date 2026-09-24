/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DumpInfoRebuilder.Abandoned;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.DispatchEnv;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ExportIo;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.IoFactory;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.IoResolution;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ModelResolver;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ParsedAddress;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.Resolved;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;

/**
 * The bundle's word for an export: an internal call through {@code IToolRoad} answers the Pending
 * envelope, the runKey resumes to the result, and a cancel through the road reaches the work the
 * envelope named.
 */
public class TheBundlesWordForAnExportTest
{
    private BundleContext context;

    private ServiceRegistration<?> registration;

    private ToolRoad road;

    /** Set once the work's Designer run saw the caller's cancellation flag. */
    private CountDownLatch cancellationSeen;

    @Before
    public void aRoadOfItsOwn()
    {
        context = FrameworkUtil.getBundle(TheBundlesWordForAnExportTest.class).getBundleContext();
        road = new ToolRoad(new Semaphore(1));
        cancellationSeen = new CountDownLatch(1);
    }

    @After
    public void theProbeGoes()
    {
        if (registration != null)
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
    }

    /**
     * An internal call answers Pending once the Designer run outlasts the soft timeout, the runKey
     * resumes to the placed result, and the outcome carries the answer the operation rendered.
     */
    @Test
    public void anInternalCallAnswersPendingAndTheRunKeyResumesToTheResult() throws Exception
    {
        Path destination = Files.createTempDirectory("export-road-result");
        try
        {
            ExportProbe probe = new ExportProbe("road_export_probe_" + System.nanoTime(),
                destination, false);
            publish(probe);
            waitFor(probe.name);

            ToolRoadOutcome first = road.call(probe.name, Map.of("slow", "true"), "export-test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(first.finished());
            assertNotNull(first.runKey());

            ToolRoadOutcome done = road.resume(first.runKey(), 15_000L);
            assertTrue("the resumed call finished", done.finished()); //$NON-NLS-1$
            JsonObject answer = JsonParser.parseString(done.text()).getAsJsonObject();
            assertTrue(answer.get("ok").getAsBoolean()); //$NON-NLS-1$
            assertTrue("the result is placed where the caller asked", Files.isRegularFile( //$NON-NLS-1$
                destination.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"))); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(destination);
        }
    }

    /**
     * A cancel through the road reaches the work: the flag the Designer run watches rises, the run
     * answers its own abandonment, and the runKey is no longer tracked afterwards.
     */
    @Test
    public void aCancelThroughTheRoadReachesTheWork() throws Exception
    {
        Path destination = Files.createTempDirectory("export-road-cancel");
        try
        {
            ExportProbe probe = new ExportProbe("road_export_cancel_" + System.nanoTime(),
                destination, true);
            publish(probe);
            waitFor(probe.name);

            ToolRoadOutcome pending = road.call(probe.name, Map.of("slow", "true"), "cancel-test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(pending.finished());
            assertNotNull(pending.runKey());

            assertTrue(road.cancel(pending.runKey()));
            assertTrue("the cancellation reached the work's Designer run", //$NON-NLS-1$
                cancellationSeen.await(20, TimeUnit.SECONDS));

            ToolRoadOutcome gone = road.resume(pending.runKey(), 50L);
            assertTrue("a cancelled run is no longer tracked", gone.refused()); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(destination);
        }
    }

    private void publish(IMcpTool probe)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.FALSE);
        properties.put(ToolWhiteboard.WRITES, Boolean.TRUE);
        registration = context.registerService(IMcpTool.class, probe, properties);
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
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteTree(Path dir) throws IOException
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // best effort cleanup
                }
            });
        }
    }

    /**
     * A tool whose whole body is the export dispatch, the way a bundle calls it through the road.
     * The Designer run either places its files after a wait, or watches the caller's cancellation
     * and answers its own abandonment when it rises.
     */
    private final class ExportProbe implements IMcpTool
    {
        final String name;

        final Path destination;

        final boolean watchCancellation;

        ExportProbe(String name, Path destination, boolean watchCancellation)
        {
            this.name = name;
            this.destination = destination;
            this.watchCancellation = watchCancellation;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "export probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            Map<String, String> call = new LinkedHashMap<>();
            call.put("timeoutSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$
            return InfobaseObjectsExporter.dispatchExport(call, "Проект", null, //$NON-NLS-1$
                java.util.Arrays.asList("Catalog.Банки"), destination.toString(), null, //$NON-NLS-1$
                STUB_ENV, ioFactory(), name);
        }

        private IoFactory ioFactory()
        {
            return (projectName, applicationId, outputPath, runKey, live, cancelled) -> IoResolution
                .of(new ProbeIo(watchCancellation), "probe-infobase"); //$NON-NLS-1$
        }
    }

    /** The environment the probe runs against: one Designer run, one placement. */
    private final class ProbeIo implements ExportIo
    {
        private final boolean watchCancellation;

        ProbeIo(boolean watchCancellation)
        {
            this.watchCancellation = watchCancellation;
        }

        @Override
        public String infobaseIdentity()
        {
            return "file:///probe-infobase"; //$NON-NLS-1$
        }

        @Override
        public String takeLock()
        {
            return null;
        }

        @Override
        public void releaseLock()
        {
            // nothing held
        }

        @Override
        public Path serviceDirectory() throws IOException
        {
            return Files.createTempDirectory("aiedt-export-probe"); //$NON-NLS-1$
        }

        @Override
        public boolean releaseInfobase()
        {
            return true;
        }

        @Override
        public String runDesigner(Path dir, Path listFile, BooleanSupplier watch) throws Exception
        {
            if (watchCancellation)
            {
                while (watch == null || !watch.getAsBoolean())
                {
                    sleep(20L);
                }
                cancellationSeen.countDown();
                throw new Abandoned("the Designer export was cancelled while it was still running", //$NON-NLS-1$
                    false, null);
            }
            Thread.sleep(6_000L);
            Path file = dir.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"); //$NON-NLS-1$
            Files.createDirectories(file.getParent());
            Files.write(file, "module".getBytes(java.nio.charset.StandardCharsets.UTF_8)); //$NON-NLS-1$
            return "log"; //$NON-NLS-1$
        }

        @Override
        public void reconnectInfobase()
        {
            // nothing to reconnect
        }

        @Override
        public boolean destinationVacant(Path output)
        {
            return !Files.exists(output) || isEmpty(output);
        }

        private boolean isEmpty(Path dir)
        {
            try (java.util.stream.Stream<Path> entries = Files.list(dir))
            {
                return !entries.findAny().isPresent();
            }
            catch (IOException e)
            {
                return true;
            }
        }

        @Override
        public void removeVacantDestination(Path output) throws IOException
        {
            Files.deleteIfExists(output);
        }

        @Override
        public void moveIntoPlace(Path dir, Path output) throws IOException
        {
            Files.move(dir, output);
        }

        @Override
        public void deleteDirectory(Path dir)
        {
            try
            {
                deleteTree(dir);
            }
            catch (IOException ignored)
            {
                // best effort
            }
        }
    }

    /** The project side on stubs: a proxy project and a resolver that knows one catalog. */
    private static final DispatchEnv STUB_ENV = new DispatchEnv()
    {
        @Override
        public IProject resolveProject(String projectName)
        {
            return (IProject)Proxy.newProxyInstance(
                TheBundlesWordForAnExportTest.class.getClassLoader(),
                new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) //$NON-NLS-1$
                    {
                        return projectName;
                    }
                    if ("toString".equals(method.getName())) //$NON-NLS-1$
                    {
                        return projectName;
                    }
                    return null;
                });
        }

        @Override
        public ModelResolver resolverFor(IProject project)
        {
            return address -> {
                if ("Банки".equals(address.objectName) //$NON-NLS-1$
                    && address.shape == ParsedAddress.Shape.TOP)
                {
                    return new Resolved("Справочник.Банки", "Банки", null, null); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return new Resolved(null, null, null, "not found"); //$NON-NLS-1$
            };
        }
    };
}
