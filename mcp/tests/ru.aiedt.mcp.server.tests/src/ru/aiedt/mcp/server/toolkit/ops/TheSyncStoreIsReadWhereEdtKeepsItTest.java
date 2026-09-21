/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The synchronization baseline is read where EDT keeps it: in the project's private working
 * location inside the workspace. A baseline there is listed by {@code status} as the matched
 * one, and {@code reseed_baseline} finds it by the infobase id.
 *
 * <p>The index is written by this test in the format the tool parses - a timestamp, the
 * signatures, a generation id and the configuration id - into the working location of a
 * workspace project created over a temporary directory.</p>
 */
public class TheSyncStoreIsReadWhereEdtKeepsItTest
{
    private static final String PROJECT = "AiEdtSyncStoreProbe"; //$NON-NLS-1$

    // Fresh ids: the machine running the test may hold a roaming store with real baselines, and a
    // configuration id shared with one of them would make the prediction INCREMENTAL before the
    // workspace store is consulted at all.
    private static final String INFOBASE = java.util.UUID.randomUUID().toString();

    private static final String CONFIGURATION = java.util.UUID.randomUUID().toString();

    private static final String DRIFTED = java.util.UUID.randomUUID().toString();

    private static Path root;

    private static IProject project;

    private static Path index;

    @BeforeClass
    public static void aProjectWithABaselineInItsWorkingLocation() throws Exception
    {
        root = Files.createTempDirectory("aiedt-sync-store"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path configuration = projectDir.resolve("src/Configuration"); //$NON-NLS-1$
        Files.createDirectories(configuration);
        Files.write(configuration.resolve("Configuration.mdo"), //$NON-NLS-1$
            ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:Configuration uuid=\"" + CONFIGURATION //$NON-NLS-1$
                + "\">\n</mdclass:Configuration>\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        Path store = project.getWorkingLocation("com._1c.g5.v8.dt.platform.services.core").toFile().toPath() //$NON-NLS-1$
            .resolve("ib-sync").resolve("ss").resolve(INFOBASE); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(store);
        index = store.resolve("index.idx"); //$NON-NLS-1$
        writeIndex(index, DRIFTED);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * status lists the baseline from the workspace store and names the store; a drifted
     * configuration id makes the prediction FULL, and the reseed that fixes it finds the same
     * file by the infobase id.
     */
    @Test
    public void theWorkspaceStoreIsListedAndReseeded() throws Exception
    {
        SyncControlTool tool = new SyncControlTool();
        JsonObject status = JsonParser.parseString(tool.execute(Map.of("operation", "status", "projectName", PROJECT))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();
        assertTrue(status.toString(), status.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(status.get("syncStorePath").getAsString(), //$NON-NLS-1$
            status.get("syncStorePath").getAsString().contains("com._1c.g5.v8.dt.platform.services.core")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("FULL", status.get("prediction").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject ours = baselineOf(status.getAsJsonArray("baselines")); //$NON-NLS-1$
        assertEquals("workspace", ours.get("store").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DRIFTED, ours.get("configurationUuid").getAsString()); //$NON-NLS-1$
        assertEquals(2, ours.get("signatureCount").getAsInt()); //$NON-NLS-1$

        JsonObject reseeded = JsonParser.parseString(tool.execute(Map.of("operation", "reseed_baseline", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", INFOBASE, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(reseeded.toString(), reseeded.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(reseeded.toString(), reseeded.get("changed").getAsBoolean()); //$NON-NLS-1$

        JsonObject after = JsonParser.parseString(tool.execute(Map.of("operation", "status", "projectName", PROJECT))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();
        assertEquals("INCREMENTAL", after.get("prediction").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(INFOBASE, after.getAsJsonObject("matchedBaseline").get("infobaseUuid").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("workspace", after.getAsJsonObject("matchedBaseline").get("store").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // The rewrite kept the layout: the version prefix, both signatures and the resource id.
        byte[] rewritten = Files.readAllBytes(index);
        assertEquals(2, after.getAsJsonObject("matchedBaseline").get("signatureCount").getAsInt()); //$NON-NLS-1$
        assertTrue(new String(rewritten, 0, 5, StandardCharsets.ISO_8859_1).endsWith("1.0")); //$NON-NLS-1$
        assertTrue(new String(rewritten, StandardCharsets.ISO_8859_1).contains("0f7a0c5e-1c1d-4f3e-9a1e-4b2c8d9e0f11")); //$NON-NLS-1$
    }

    private static JsonObject baselineOf(JsonArray baselines)
    {
        for (int i = 0; i < baselines.size(); i++)
        {
            JsonObject entry = baselines.get(i).getAsJsonObject();
            if (INFOBASE.equals(entry.get("infobaseUuid").getAsString())) //$NON-NLS-1$
            {
                return entry;
            }
        }
        throw new AssertionError("the workspace baseline is not listed: " + baselines); //$NON-NLS-1$
    }

    /**
     * Writes the index in the layout EDT 2026 writes: a version, then per signature a flag that
     * says whether a resource id follows.
     */
    private static void writeIndex(Path file, String configurationUuid) throws Exception
    {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile())))
        {
            out.writeUTF("1.0"); //$NON-NLS-1$
            out.writeLong(System.currentTimeMillis());
            out.writeInt(2);
            out.writeUTF("src/CommonModules/Probe/Module.bsl"); //$NON-NLS-1$
            out.writeInt(2);
            out.write(new byte[] { 1, 2 });
            out.writeBoolean(false);
            out.writeUTF("src/Catalogs/Products/Forms/ItemForm/Form.oform"); //$NON-NLS-1$
            out.writeInt(2);
            out.write(new byte[] { 3, 4 });
            out.writeBoolean(true);
            out.writeUTF("0f7a0c5e-1c1d-4f3e-9a1e-4b2c8d9e0f11"); //$NON-NLS-1$
            out.writeUTF("generation-1"); //$NON-NLS-1$
            out.writeUTF(configurationUuid);
        }
    }
}
