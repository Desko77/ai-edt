/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.internal.ClusterManagerImpl;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Every write-blocking preset refuses each cluster write by its door, before the clusters file is
 * read, and the file keeps its bytes.
 * <p>
 * The five writes of {@code cluster_admin} edit {@code .settings/aiedt-clusters.yaml}, and their
 * doors sit in a group every one of those presets keeps enabled - so the gate each branch asks
 * first is the only thing standing between the preset's promise and a written file. The manager
 * here is the real one over a real file, so the bytes are compared as bytes: a gate that refused
 * after reading, or a refusal that still wrote, shows up as a diff.
 * </p>
 */
public class AClusterWriteDoorRefusesUnderWriteBlockingPresetsTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    private ClusterAdminFacadeTool facade;

    private IPreferenceStore store;

    private String presetBefore;

    /**
     * Opens a project, creates one cluster through the real manager so the file holds bytes worth
     * protecting, and registers the facade with its doors as the live server does.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProjectAFileAndTheDoors() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterDoors"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
        facade = new ClusterAdminFacadeTool()
        {
            @Override
            IClusterManager clusterService()
            {
                return manager;
            }

            @Override
            ToolResult objectModelRefusal(IProject project, String objectFqn,
                String collectionPath)
            {
                // The gate is asked before the model, so this is never reached under the presets;
                // answered permissive for the case a gate fails open, which the byte comparison
                // then catches.
                return null;
            }
        };
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(facade);
        catalog.register(new ClusterCreateTool());
        catalog.register(new ClusterUpdateTool());
        catalog.register(new ClusterDeleteTool());
        catalog.register(new ClusterAddTool());
        catalog.register(new ClusterRemoveTool());
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalog", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Takes the doors back out, puts the preset back, and closes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
    @After
    public void theDoorsThePresetAndTheProjectGo() throws Exception
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.unregister(ClusterAdminFacadeTool.NAME);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_CREATE);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_UPDATE);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_DELETE);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_ADD);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_REMOVE);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        if (probe != null)
        {
            probe.close();
        }
    }

    /** One call to the facade with the given operation and arguments. */
    private JsonObject call(String operation, String... arguments)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", operation); //$NON-NLS-1$
        params.put("projectName", probe.project.getName()); //$NON-NLS-1$
        for (int i = 0; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return JsonParser.parseString(facade.execute(params)).getAsJsonObject();
    }

    /**
     * One call to the facade from a write row: the first cell is the operation, the rest are
     * name/value pairs.
     *
     * @param write the row
     * @return the answer as parsed JSON
     */
    private JsonObject call(String[] write)
    {
        return call(write[0], Arrays.copyOfRange(write, 1, write.length));
    }

    /**
     * The bytes of the clusters file, as a comparable string; "absent" when there is no file.
     *
     * @return the file's content or its absence
     */
    private String clustersFileBytes()
    {
        try
        {
            Path file = probe.clustersFile();
            if (!Files.exists(file))
            {
                return "absent"; //$NON-NLS-1$
            }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("the clusters file could not be read", e); //$NON-NLS-1$
        }
    }

    /**
     * Under each write-blocking preset, every write answers the gate's own wording and the file
     * keeps its bytes.
     */
    @Test
    public void everyWriteIsRefusedByItsDoorAndTheFileKeepsItsBytes()
    {
        List<String> presets = Arrays.asList(ToolProfile.READ_ONLY.name(),
            ToolProfile.DEBUG_AND_TEST.name(), ToolProfile.CODE_REVIEW.name());
        List<String[]> writes = Arrays.asList(
            new String[] {"create_cluster", "name", "Rack", "collectionPath", "Catalog"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new String[] {"update_cluster", "clusterPath", "Catalog/Shelf", "newName", "Case"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            // A dry run passes the same door: the operation is a write even when it writes
            // nothing, and a preset that blocks writes is asked about it too.
            new String[] {"delete_cluster", "clusterPath", "Catalog/Shelf", "dryRun", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new String[] {"delete_cluster", "clusterPath", "Catalog/Shelf"}, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] {"add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
                "clusterPath", "Catalog/Shelf"}, //$NON-NLS-1$
            new String[] {"remove_from_cluster", "objectFqn", "Catalog.Products"}); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> leaks = new java.util.ArrayList<>();
        for (String preset : presets)
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            for (String[] write : writes)
            {
                String before = clustersFileBytes();
                String door = write[0];
                JsonObject answer = call(write);
                assertFalse(preset + " let " + door + " answer as a success", //$NON-NLS-1$ //$NON-NLS-2$
                    answer.get("success").getAsBoolean()); //$NON-NLS-1$
                assertTrue(preset + " refused " + door + " with something other than the gate: " //$NON-NLS-1$ //$NON-NLS-2$
                    + answer,
                    answer.get("error").getAsString() //$NON-NLS-1$
                        .startsWith(ToolGate.disabledMessage(door)));
                if (!before.equals(clustersFileBytes()))
                {
                    leaks.add(preset + " wrote the clusters file on " + door); //$NON-NLS-1$
                }
                // The cluster the file started with is still there, and still alone.
                assertTrue(manager.getClusterStorage(probe.project)
                    .getClusterByFullPath("Catalog/Shelf") != null); //$NON-NLS-1$
                assertEquals(1, manager.getAllClusters(probe.project).size());
            }
        }
        assertTrue("these presets wrote through a door they had switched off: " + leaks, //$NON-NLS-1$
            leaks.isEmpty());
    }

    /** The read of the same facade keeps answering under the strictest of the presets. */
    @Test
    public void theReadKeepsAnsweringUnderReadOnly()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        JsonObject answer = call("get_clusters"); //$NON-NLS-1$
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, answer.getAsJsonArray("collections").size()); //$NON-NLS-1$
    }
}
