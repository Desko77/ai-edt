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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Every write-blocking preset refuses each tag write by its door, before the markers file is
 * read, and the file keeps its bytes.
 * <p>
 * The five writes of {@code tag_admin} edit {@code .settings/aiedt-markers.yaml}, and their
 * doors sit in a group every one of those presets keeps enabled - so the gate each branch asks
 * first is the only thing standing between the preset's promise and a written file. The service
 * here is the real one over a real file, so the bytes are compared as bytes: a gate that refused
 * after reading, or a refusal that still wrote, shows up as a diff.
 * </p>
 */
public class ATagWriteDoorRefusesUnderWriteBlockingPresetsTest
{
    private static final String PROJECT = "AiEdtTagDoors"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private TagAdminFacadeTool facade;

    private IPreferenceStore store;

    private String presetBefore;

    /**
     * Opens a project and defines one tag through the real service so the file holds bytes worth
     * protecting, and registers the facade with its doors as the live server does.
     *
     * @throws Exception when the project cannot be created
     */
    @BeforeClass
    public static void aProjectAFileAndTheDoors() throws Exception
    {
        root = Files.createTempDirectory("aiedt-tag-doors"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        IProject created = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        created.create(description, new NullProgressMonitor());
        created.open(new NullProgressMonitor());
        project = created;
    }

    /**
     * A fresh service, a fresh file and a defined tag, then the doors are registered.
     *
     * @throws Exception when the marker file cannot be removed
     */
    @Before
    public void aTagWorthProtecting() throws Exception
    {
        MarkerManager.dispose();
        Path settings = project.getLocation().toFile().toPath()
            .resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
        if (Files.exists(settings))
        {
            Files.deleteIfExists(settings);
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        facade = new TagAdminFacadeTool()
        {
            @Override
            ObjectCheck checkObject(IProject askedProject, String objectFqn)
            {
                // The gate is asked before the model, so this is never reached under the presets;
                // answered permissive for the case a gate fails open, which the byte comparison
                // then catches.
                return ObjectCheck.of(objectFqn.trim());
            }
        };
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(facade);
        catalog.register(new TagCreateTool());
        catalog.register(new TagUpdateTool());
        catalog.register(new TagDeleteTool());
        catalog.register(new TagAssignTool());
        catalog.register(new TagUnassignTool());
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertTrue(MarkerManager.getInstance().createMarker(project, "Important", "#112233", null)
            != null);
    }

    /**
     * Takes the doors back out, puts the preset back, stops the service and closes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
    @After
    public void theDoorsAndThePresetGo() throws Exception
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.unregister(TagAdminFacadeTool.NAME);
        catalog.unregister(TagAdminFacadeTool.DOOR_CREATE);
        catalog.unregister(TagAdminFacadeTool.DOOR_UPDATE);
        catalog.unregister(TagAdminFacadeTool.DOOR_DELETE);
        catalog.unregister(TagAdminFacadeTool.DOOR_ASSIGN);
        catalog.unregister(TagAdminFacadeTool.DOOR_UNASSIGN);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        MarkerManager.dispose();
    }

    /**
     * Removes the project and the directory it was created in.
     *
     * @throws Exception when the project cannot be deleted
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * One call to the facade with the given operation and arguments.
     *
     * @param operation the operation
     * @param arguments name/value pairs
     * @return the answer as parsed JSON
     */
    private JsonObject call(String operation, String... arguments)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", operation); //$NON-NLS-1$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        for (int i = 0; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return JsonParser.parseString(facade.execute(params)).getAsJsonObject();
    }

    /**
     * The bytes of the markers file, as a comparable string; "absent" when there is no file.
     *
     * @return the file's content or its absence
     */
    private String markersFileBytes()
    {
        try
        {
            Path file = project.getLocation().toFile().toPath()
                .resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!Files.exists(file))
            {
                return "absent"; //$NON-NLS-1$
            }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("the markers file could not be read", e); //$NON-NLS-1$
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
            new String[] {"create_tag", "tag", "Rack"}, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] {"update_tag", "tag", "Important", "newName", "Case"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            // A dry run passes the same door: the operation is a write even when it writes
            // nothing, and a preset that blocks writes is asked about it too.
            new String[] {"delete_tag", "tag", "Important", "dryRun", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new String[] {"delete_tag", "tag", "Important"}, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] {"assign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
                "tags", "[\"Important\"]"}, //$NON-NLS-1$
            new String[] {"unassign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
                "tags", "[\"Important\"]"}); //$NON-NLS-1$
        List<String> leaks = new java.util.ArrayList<>();
        for (String preset : presets)
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            for (String[] write : writes)
            {
                String before = markersFileBytes();
                String door = write[0];
                JsonObject answer = call(write[0], Arrays.copyOfRange(write, 1, write.length));
                assertFalse(preset + " let " + door + " answer as a success", //$NON-NLS-1$ //$NON-NLS-2$
                    answer.get("success").getAsBoolean()); //$NON-NLS-1$
                assertTrue(preset + " refused " + door + " with something other than the gate: " //$NON-NLS-1$ //$NON-NLS-2$
                    + answer,
                    answer.get("error").getAsString() //$NON-NLS-1$
                        .startsWith(ToolGate.disabledMessage(door)));
                if (!before.equals(markersFileBytes()))
                {
                    leaks.add(preset + " wrote the markers file on " + door); //$NON-NLS-1$
                }
                // The tag the file started with is still there, and still alone.
                assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
                    .getMarkerByName("Important") != null); //$NON-NLS-1$
                assertEquals(1, MarkerManager.getInstance().getMarkerStorage(project).getTags()
                    .size());
            }
        }
        assertTrue("these presets wrote through a door they had switched off: " + leaks, //$NON-NLS-1$
            leaks.isEmpty());
    }

    /** Every write answers under the all-tools preset again once it is put back. */
    @Test
    public void theWritesAnswerAgainUnderAllTools()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertFalse(call("create_tag", "tag", "Blocked").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        JsonObject answer = call("create_tag", "tag", "Allowed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerByName("Allowed") != null); //$NON-NLS-1$
    }
}
