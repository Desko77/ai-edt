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
import java.util.ArrayList;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * A preview of a project whose markers still sit under the name an earlier version wrote leaves the
 * settings folder exactly as it found it.
 * <p>
 * Reading markers the ordinary way carries such a file over to the current name: it copies the file
 * and renames the old one away. A call that promises to write nothing must not do that, and that
 * promise is what carries the preview past a write-blocking gate, so the check here is the whole
 * folder - the names in it and the bytes of every file - taken before and after each of the five
 * previews.
 * </p>
 * <p>
 * The answers still have to come from the file that is there: a preview that read nothing would
 * answer a create the write refuses, or an empty tag list where the file names two, and that is the
 * answer a caller acts on.
 * </p>
 */
public class ATagPreviewOfALegacyMarkerFileTest
{
    private static final String PROJECT = "AiEdtTagPreviewLegacy"; //$NON-NLS-1$

    private static final String OBJECT = "Catalog.Products"; //$NON-NLS-1$

    private static final String CURRENT = "aiedt-markers.yaml"; //$NON-NLS-1$

    private static final String LEGACY = "metadata-tags.yaml"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private TagAdminFacadeTool facade;

    private IPreferenceStore store;

    private String presetBefore;

    /**
     * A temporary project the markers files can live in.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-tag-preview-legacy"); //$NON-NLS-1$
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
     * Removes the project and the directory it was created in.
     *
     * @throws Exception when the project cannot be deleted
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        MarkerManager.dispose();
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
     * A fresh service over an empty settings folder, the facade with its doors registered the way
     * the live server registers them, and the preset the test may walk through.
     *
     * @throws Exception when the settings folder cannot be emptied
     */
    @Before
    public void anEmptyProjectAndTheDoors() throws Exception
    {
        MarkerManager.dispose();
        Path settings = settingsFolder();
        if (Files.isDirectory(settings))
        {
            try (var walk = Files.list(settings))
            {
                for (Path file : walk.toList())
                {
                    Files.deleteIfExists(file);
                }
            }
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        facade = new TagAdminFacadeTool()
        {
            @Override
            ObjectCheck checkObject(IProject askedProject, String objectFqn)
            {
                // The model check needs a configuration a unit test cannot stand up; the rule
                // itself is proven by ATagAdminModelCheckResolvesNestedAddressesTest. Answering
                // the address as written keeps every preview running past the check.
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
    }

    /**
     * Takes the doors back out, puts the preset back and drops the service the test used.
     */
    @After
    public void theDoorsAndThePresetGo()
    {
        if (store != null && presetBefore != null)
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        }
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.unregister(TagAdminFacadeTool.NAME);
        catalog.unregister(TagAdminFacadeTool.DOOR_CREATE);
        catalog.unregister(TagAdminFacadeTool.DOOR_UPDATE);
        catalog.unregister(TagAdminFacadeTool.DOOR_DELETE);
        catalog.unregister(TagAdminFacadeTool.DOOR_ASSIGN);
        catalog.unregister(TagAdminFacadeTool.DOOR_UNASSIGN);
        MarkerManager.dispose();
    }

    /**
     * Every preview answers what the file under the old name holds, and not one of them creates,
     * renames or changes a file.
     *
     * @throws Exception when the markers file cannot be moved aside
     */
    @Test
    public void everyPreviewLeavesALegacyOnlyProjectAsItWas() throws Exception
    {
        aLegacyFileOnly();
        String before = settingsFolderBytes();
        assertTrue("the old file is there: " + before, before.contains(LEGACY + "=")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the current name is not there yet: " + before, //$NON-NLS-1$
            before.contains(CURRENT + "=")); //$NON-NLS-1$

        JsonObject create = call("create_tag", "tag", "Legacy", "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertFalse("the create sees the tag the old file defines: " + create, //$NON-NLS-1$
            create.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("tagExists", create.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject fresh = succeeded(call("create_tag", "tag", "Fresh", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("the old file parses", "Fresh", //$NON-NLS-1$ //$NON-NLS-2$
            fresh.getAsJsonObject("tag").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject update = succeeded(call("update_tag", "tag", "Legacy", "newName", "Renamed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Legacy", update.getAsJsonObject("tag").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Renamed", update.getAsJsonObject("tagAfter").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject delete = succeeded(call("delete_tag", "tag", "Legacy", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("the old file's assignment is counted", 1, //$NON-NLS-1$
            delete.get("assignments").getAsInt()); //$NON-NLS-1$

        JsonObject assign = succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[\"Second\"]", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the old file's assignment is what the preview starts from", //$NON-NLS-1$
            List.of("Legacy"), names(assign.getAsJsonArray("tagsBefore"))); //$NON-NLS-1$
        assertEquals(List.of("Legacy", "Second"), //$NON-NLS-1$ //$NON-NLS-2$
            names(assign.getAsJsonArray("tagsAfter"))); //$NON-NLS-1$

        JsonObject unassign = succeeded(call("unassign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[\"Legacy\"]", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("Legacy"), names(unassign.getAsJsonArray("removed"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the removal leaves the object with nothing", //$NON-NLS-1$
            unassign.getAsJsonArray("tagsAfter").isEmpty()); //$NON-NLS-1$

        assertEquals("no preview created, renamed or changed a file", before, //$NON-NLS-1$
            settingsFolderBytes());
    }

    /**
     * The same five previews answer the same way under every write-blocking preset, and the folder
     * is untouched there too.
     * <p>
     * A preset lets an edit that writes nothing through on the strength of that promise, so this is
     * where a preview that carries a file over stops being a preview and becomes a write the preset
     * was supposed to refuse.
     * </p>
     *
     * @throws Exception when the markers file cannot be moved aside
     */
    @Test
    public void everyPreviewUnderAWriteBlockingPresetLeavesALegacyOnlyProjectAsItWas()
        throws Exception
    {
        aLegacyFileOnly();
        String before = settingsFolderBytes();
        List<String> unanswered = previews();
        for (String answer : unanswered)
        {
            assertTrue("the five previews answer a tag rather than a refusal: " + answer, //$NON-NLS-1$
                JsonParser.parseString(answer).getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
        }

        List<String> differences = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (String preset : Arrays.asList(ToolProfile.READ_ONLY.name(),
            ToolProfile.DEBUG_AND_TEST.name(), ToolProfile.CODE_REVIEW.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            List<String> underPreset = previews();
            if (!unanswered.equals(underPreset))
            {
                differences.add(preset + ": " + underPreset + " instead of " + unanswered); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!before.equals(settingsFolderBytes()))
            {
                written.add(preset);
            }
        }
        assertTrue("these presets changed the settings folder: " + written, written.isEmpty()); //$NON-NLS-1$
        assertTrue("these presets answered differently: " + differences, differences.isEmpty()); //$NON-NLS-1$
        assertEquals("the folder is as it was", before, settingsFolderBytes()); //$NON-NLS-1$
    }

    /**
     * Puts the markers in a project that has only the file an earlier version wrote.
     * <p>
     * The file is written by this version's own write, so its shape is the one the loader reads,
     * and then moved to the old name with the current name left absent - the state a project is in
     * before anything reads its markers.
     * </p>
     *
     * @throws Exception when the files cannot be written, moved or read
     */
    private void aLegacyFileOnly() throws Exception
    {
        succeeded(call("create_tag", "tag", "Legacy", "color", "#112233", "description", //$NON-NLS-1$
            "an old tag"));
        succeeded(call("create_tag", "tag", "Second"));
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Legacy\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Files.move(settingsFolder().resolve(CURRENT), settingsFolder().resolve(LEGACY));
        MarkerManager.dispose();
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        assertFalse("the current name is gone", Files.exists(settingsFolder().resolve(CURRENT))); //$NON-NLS-1$
        assertTrue("the old name holds the markers", Files.exists(settingsFolder().resolve(LEGACY))); //$NON-NLS-1$
    }

    /**
     * The five previews, as their answers read.
     *
     * @return the five answers, in the order the operations are listed
     */
    private List<String> previews()
    {
        return Arrays.asList(
            call("create_tag", "tag", "Fresh", "dryRun", "true").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            call("update_tag", "tag", "Legacy", "newName", "Renamed", "dryRun", "true").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            call("delete_tag", "tag", "Legacy", "dryRun", "true").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Second\"]", "dryRun", "true") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                .toString(),
            call("unassign_tag", "objectFqn", OBJECT, "tags", "[\"Legacy\"]", "dryRun", "true") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                .toString());
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
     * A call that succeeded, or a failure naming the answer.
     *
     * @param answer the answer to insist on
     * @return the same answer
     */
    private static JsonObject succeeded(JsonObject answer)
    {
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        return answer;
    }

    /**
     * The project's settings folder.
     *
     * @return the path
     */
    private static Path settingsFolder()
    {
        return project.getLocation().toFile().toPath().resolve(".settings"); //$NON-NLS-1$
    }

    /**
     * Every file of the settings folder as its name and its bytes, so that a comparison sees a
     * created, a renamed, a changed and a deleted file alike.
     *
     * @return the folder's names and contents
     * @throws Exception when the folder cannot be listed or a file read
     */
    private static String settingsFolderBytes() throws Exception
    {
        Path settings = settingsFolder();
        StringBuilder text = new StringBuilder();
        if (Files.isDirectory(settings))
        {
            List<Path> files = new ArrayList<>();
            try (var walk = Files.list(settings))
            {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            files.sort(Comparator.comparing(file -> settings.relativize(file).toString()));
            for (Path file : files)
            {
                text.append(settings.relativize(file)).append('=') //$NON-NLS-1$
                    .append(Base64.getEncoder().encodeToString(Files.readAllBytes(file)))
                    .append('\n');
            }
        }
        return text.toString();
    }

    /**
     * The tag names of a JSON array of nodes, in the order it carries them.
     *
     * @param nodes the array
     * @return the names
     */
    private static List<String> names(JsonArray nodes)
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++)
        {
            names.add(nodes.get(i).getAsString());
        }
        return names;
    }
}
