/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.DispatchEnv;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ExportIo;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.IoFactory;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.IoResolution;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ModelResolver;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ParsedAddress;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.Resolved;

/**
 * The dispatch an export call takes: the answer's shape, the destination claim that refuses a
 * second export into the same path while the first is placing its result, the unique runKey that
 * keeps two identical calls as two runs, and the {@code Pending} envelope a slow Designer run is
 * answered with.
 */
public class ExportInfobaseObjectsDispatchTest
{
    private Path root;

    /** The environments the dispatch ran against, one per started work. */
    private final List<RecordingIo> ios = new ArrayList<>();

    /** The Designer delay every environment this test builds is given. */
    private long designerDelayMs;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("export-dispatch-test");
    }

    @After
    public void dropTemp() throws IOException
    {
        deleteTree(root);
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

    /** The environment one dispatch runs against, recording what it was asked for. */
    private final class RecordingIo implements ExportIo
    {
        final List<String> asked = new ArrayList<>();

        final CountDownLatch designerStarted = new CountDownLatch(1);

        @Override
        public String infobaseIdentity()
        {
            asked.add("identity"); //$NON-NLS-1$
            return "file:///dispatch-infobase"; //$NON-NLS-1$
        }

        @Override
        public String takeLock()
        {
            asked.add("takeLock"); //$NON-NLS-1$
            return null;
        }

        @Override
        public void releaseLock()
        {
            asked.add("releaseLock"); //$NON-NLS-1$
        }

        @Override
        public Path serviceDirectory() throws IOException
        {
            asked.add("serviceDir"); //$NON-NLS-1$
            return Files.createTempDirectory("aiedt-export-service"); //$NON-NLS-1$
        }

        @Override
        public boolean releaseInfobase()
        {
            asked.add("release"); //$NON-NLS-1$
            return true;
        }

        @Override
        public String runDesigner(Path dir, Path listFile, BooleanSupplier cancelled)
            throws Exception
        {
            asked.add("designer"); //$NON-NLS-1$
            designerStarted.countDown();
            if (designerDelayMs > 0)
            {
                Thread.sleep(designerDelayMs);
            }
            Path file = dir.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"); //$NON-NLS-1$
            Files.createDirectories(file.getParent());
            Files.write(file, "module".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            return "log"; //$NON-NLS-1$
        }

        @Override
        public void reconnectInfobase()
        {
            asked.add("reconnect"); //$NON-NLS-1$
        }

        @Override
        public boolean destinationVacant(Path destination)
        {
            asked.add("destinationVacant"); //$NON-NLS-1$
            return true;
        }

        @Override
        public void removeVacantDestination(Path destination) throws IOException
        {
            Files.deleteIfExists(destination);
        }

        @Override
        public void moveIntoPlace(Path dir, Path destination) throws IOException
        {
            asked.add("moveIntoPlace"); //$NON-NLS-1$
            Files.move(dir, destination);
        }

        @Override
        public void deleteDirectory(Path dir)
        {
            asked.add("deleteDirectory"); //$NON-NLS-1$
            try
            {
                deleteTree(dir);
            }
            catch (IOException ignored)
            {
                // best effort, as the contract says
            }
        }
    }

    /** A project stand-in: the dispatch only passes it to the resolver. */
    private static final DispatchEnv ENV = new DispatchEnv()
    {
        @Override
        public IProject resolveProject(String projectName)
        {
            return (IProject)Proxy.newProxyInstance(ExportInfobaseObjectsDispatchTest.class
                .getClassLoader(), new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) //$NON-NLS-1$
                    {
                        return projectName;
                    }
                    if ("hashCode".equals(method.getName())) //$NON-NLS-1$
                    {
                        return Integer.valueOf(projectName.hashCode());
                    }
                    if ("toString".equals(method.getName())) //$NON-NLS-1$
                    {
                        return projectName; //$NON-NLS-1$
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
                return new Resolved(null, null, null,
                    "no " + address.typeEnglish + " named '" + address.objectName //$NON-NLS-1$ //$NON-NLS-2$
                        + "' in the project's configuration"); //$NON-NLS-1$
            };
        }
    };

    private IoFactory factory()
    {
        return (projectName, applicationId, outputPath, runKey, live, cancelled) -> {
            RecordingIo io = new RecordingIo();
            ios.add(io);
            return IoResolution.of(io, "dispatch-infobase"); //$NON-NLS-1$
        };
    }

    private Map<String, String> call(String outputPath)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "Проект"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objects", "Catalog.Банки"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputPath", outputPath); //$NON-NLS-1$
        return params;
    }

    private String dispatch(Map<String, String> params)
    {
        return InfobaseObjectsExporter.dispatchExport(params,
            params.get("projectName"), null, //$NON-NLS-1$
            ru.aiedt.mcp.server.wire.JsonUtils.extractArrayArgument(params, "objects"), //$NON-NLS-1$
            params.get("outputPath"), null, ENV, factory(), "config_io"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The happy path: the answer carries ok, the output path, the placed files, the objects map,
     * the source and the infobase.
     */
    @Test
    public void theAnswerCarriesTheResultFields() throws IOException
    {
        Path outputPath = root.resolve("one");
        String answer = dispatch(call(outputPath.toString()));

        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(json.get("ok").getAsBoolean());
        assertEquals(outputPath.toAbsolutePath().toString(), json.get("outputPath").getAsString());
        assertEquals("infobase", json.get("source").getAsString()); //$NON-NLS-1$
        assertEquals("dispatch-infobase", json.get("infobase").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("files").getAsJsonArray().size() == 1);
        assertEquals("Справочник.Банки", //$NON-NLS-1$
            json.get("objects").getAsJsonObject().get("Catalog.Банки").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.has("elapsedMs")); //$NON-NLS-1$
        assertTrue("the result is placed", //$NON-NLS-1$
            Files.isRegularFile(outputPath.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"))); //$NON-NLS-1$
    }

    /**
     * A refused address never starts an environment: the refusal answers before the io factory is
     * asked for anything.
     */
    @Test
    public void aRefusedAddressStartsNoEnvironment()
    {
        Map<String, String> params = call(root.resolve("two").toString());
        params.put("objects", "Catalog.НетТакого"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = dispatch(params);

        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("error").getAsString().contains("НетТакого")); //$NON-NLS-1$
        assertTrue("no environment was built", ios.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Two identical calls in a row are two runs: two environments, two placements.
     */
    @Test
    public void twoIdenticalCallsAreTwoRuns() throws IOException
    {
        Path outputPath = root.resolve("three");
        Map<String, String> params = call(outputPath.toString());

        assertTrue(JsonParser.parseString(dispatch(params)).getAsJsonObject().get("ok") //$NON-NLS-1$
            .getAsBoolean());
        assertTrue(JsonParser.parseString(dispatch(params)).getAsJsonObject().get("ok") //$NON-NLS-1$
            .getAsBoolean());
        assertEquals("each call ran its own environment", 2, ios.size()); //$NON-NLS-1$
    }

    /**
     * A second export into the same destination while the first is still placing its result is
     * refused with the first's runKey.
     */
    @Test
    public void aSecondExportIntoTheSameDestinationIsRefusedWithTheFirstRunKey() throws Exception
    {
        Path outputPath = root.resolve("four");
        Map<String, String> params = call(outputPath.toString());
        params.put("timeoutSeconds", "6"); //$NON-NLS-1$ //$NON-NLS-2$
        designerDelayMs = 1_500L;

        Thread first = new Thread(() -> dispatch(params));
        first.start();
        // Wait for the first work to be inside its Designer run, holding the destination.
        long deadline = System.currentTimeMillis() + 10_000L;
        while (ios.isEmpty() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
        assertFalse(ios.isEmpty());
        assertTrue("the first export reached its Designer run", //$NON-NLS-1$
            ios.get(0).designerStarted.await(10, TimeUnit.SECONDS));

        String second = dispatch(params);
        JsonObject refusal = JsonParser.parseString(second).getAsJsonObject();
        assertFalse(refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refusal.get("error").getAsString().contains("already placing")); //$NON-NLS-1$
        assertTrue(refusal.has("heldByRunKey")); //$NON-NLS-1$

        first.join(15_000L);
        assertFalse("the first export finished", first.isAlive()); //$NON-NLS-1$
        assertTrue("the destination holds the first export's result", //$NON-NLS-1$
            Files.isRegularFile(outputPath.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"))); //$NON-NLS-1$
    }

    /**
     * A Designer run that outlasts the soft timeout answers a Pending envelope with a runKey, and
     * the runKey collects the finished result.
     */
    @Test
    public void aSlowDesignerRunAnswersPendingAndTheRunKeyCollectsTheResult() throws Exception
    {
        Path outputPath = root.resolve("five");
        Map<String, String> params = call(outputPath.toString());
        params.put("timeoutSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$
        designerDelayMs = 6_000L;

        String pending = dispatch(params);

        JsonObject envelope = JsonParser.parseString(pending).getAsJsonObject();
        assertEquals("Pending", envelope.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(envelope.has("runKey")); //$NON-NLS-1$
        String runKey = envelope.get("runKey").getAsString();

        Map<String, String> poll = new LinkedHashMap<>();
        poll.put("runKey", runKey); //$NON-NLS-1$
        poll.put("timeoutSeconds", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String collected = InfobaseObjectsExporter.dispatchExport(poll, params.get("projectName"), null, //$NON-NLS-1$
            null, null, runKey, ENV, factory(), "config_io"); //$NON-NLS-1$

        JsonObject result = JsonParser.parseString(collected).getAsJsonObject();
        assertTrue(result.get("ok").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the result is placed after the collection", //$NON-NLS-1$
            Files.isRegularFile(outputPath.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"))); //$NON-NLS-1$
    }
}
