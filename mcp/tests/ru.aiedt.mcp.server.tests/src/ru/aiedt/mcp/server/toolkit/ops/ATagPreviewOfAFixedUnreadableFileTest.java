/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
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
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * A preview of a project whose marker file the service holds as unreadable answers the refusal the
 * write answers, even after the file on disk has been repaired.
 * <p>
 * The sign is taken by a read that found the file unparseable, and the write refuses on that sign
 * without reading the file again - which is what keeps a mutation from writing an empty storage
 * over both sides of a merge conflict. A file repaired outside the workspace raises no change
 * notification, so the sign stands until an ordinary read reads the file again. A preview that
 * judged the repaired file itself would promise an edit the write refuses, and that is the one
 * thing a preview must not do.
 * </p>
 * <p>
 * The repair is written straight to disk with the workspace told nothing - the state a file
 * changed by another program is in. The refresh that would pick it up behind the test's back is off
 * for this class and put back afterwards, and one of the tests turns it back on: the refusal has to
 * hold whether the tree is behind the file or has already read it.
 * </p>
 */
public class ATagPreviewOfAFixedUnreadableFileTest
{
    private static final String PROJECT = "AiEdtTagPreviewFixedFile"; //$NON-NLS-1$

    private static final String SETTINGS = ".settings"; //$NON-NLS-1$

    private static final String CURRENT = "aiedt-markers.yaml"; //$NON-NLS-1$

    private static final String LEGACY = "metadata-tags.yaml"; //$NON-NLS-1$

    private static final String CONFLICT = "<<<<<<< ours" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "tags: []" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "=======" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + ">>>>>>> theirs"; //$NON-NLS-1$

    private static final String REPAIRED = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- color: '#112233'" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  name: Fixed" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

    private static Path root;

    private static IProject project;

    private static String lightRefreshBefore;

    private static String autoRefreshBefore;

    private TagAdminFacadeTool facade;

    private IPreferenceStore store;

    private String presetBefore;

    /**
     * A temporary project, and a workspace that does not refresh resources on access.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProjectWithoutLightRefresh() throws Exception
    {
        root = Files.createTempDirectory("aiedt-tag-preview-fixed"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES);
        lightRefreshBefore = node.get(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, null);
        node.putBoolean(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, false);
        autoRefreshBefore = node.get(ResourcesPlugin.PREF_AUTO_REFRESH, null);
        node.putBoolean(ResourcesPlugin.PREF_AUTO_REFRESH, false);
        assertFalse("the light refresh is off in the node the platform reads", //$NON-NLS-1$
            node.getBoolean(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, true));
        assertFalse("and so is the background refresh", //$NON-NLS-1$
            node.getBoolean(ResourcesPlugin.PREF_AUTO_REFRESH, true));
    }

    /**
     * Puts the refresh preference back, then removes the project and its directory.
     *
     * @throws Exception when the project cannot be deleted
     */
    @AfterClass
    public static void thePreferenceAndTheProjectGo() throws Exception
    {
        MarkerManager.dispose();
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES);
        if (lightRefreshBefore == null)
        {
            node.remove(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH);
        }
        else
        {
            node.put(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, lightRefreshBefore);
        }
        if (autoRefreshBefore == null)
        {
            node.remove(ResourcesPlugin.PREF_AUTO_REFRESH);
        }
        else
        {
            node.put(ResourcesPlugin.PREF_AUTO_REFRESH, autoRefreshBefore);
        }
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
     * A fresh service over a settings folder holding no markers file, and the facade with its doors
     * registered the way the live server registers them.
     *
     * @throws Exception when the settings folder cannot be emptied
     */
    @Before
    public void anEmptyProjectAndTheDoors() throws Exception
    {
        MarkerManager.dispose();
        for (String name : new String[] {CURRENT, LEGACY})
        {
            Files.deleteIfExists(settingsFolder().resolve(name));
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
     * A marker file repaired on disk after the service took it for unreadable is still refused, and
     * a preview of the project refuses the way the write it previews does.
     *
     * @throws Exception when the markers file cannot be written or read back
     */
    @Test
    public void aPreviewOfARepairedFileRefusesLikeTheWrite() throws Exception
    {
        Path file = aFileTheServiceTookForUnreadable();
        Files.write(file, REPAIRED.getBytes(StandardCharsets.UTF_8));
        assertEquals("the repaired file is the one on disk", REPAIRED, Files.readString(file)); //$NON-NLS-1$

        assertThePreviewAnswersTheWrite("delete_tag", "tag", "Fixed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertBothRefuseForTheSameReason("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("no preview touched the repaired file", REPAIRED, Files.readString(file)); //$NON-NLS-1$
        JsonObject afterThePreviews = call("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the previews left the sign where it was", //$NON-NLS-1$
            "unreadableFile", afterThePreviews.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same repair under a tree that refreshes resources on access: the preview still refuses
     * the way the write refuses.
     * <p>
     * A refresh on access hands the repaired bytes to the next read of the file, which is what makes
     * this the case a preview is most likely to answer wrongly: the file parses, the sign is what
     * refuses, and a preview that judged the file would promise the edit. The mark is taken with the
     * refresh off, then the platform's refresh is switched back on for the rest of the test.
     * </p>
     *
     * @throws Exception when the markers file cannot be written or read back
     */
    @Test
    public void aPreviewOfARepairedFileRefusesLikeTheWriteAcrossARefresh() throws Exception
    {
        Path file = aFileTheServiceTookForUnreadable();
        lightRefresh(true);
        try
        {
            assertTrue("the platform refreshes a resource from disk when it is read", //$NON-NLS-1$
                InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES)
                    .getBoolean(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, false));
            Files.write(file, REPAIRED.getBytes(StandardCharsets.UTF_8));
            assertEquals("the repaired file is the one on disk", REPAIRED, Files.readString(file)); //$NON-NLS-1$

            assertThePreviewAnswersTheWrite("delete_tag", "tag", "Fixed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertBothRefuseForTheSameReason("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertEquals("no preview touched the repaired file", REPAIRED, Files.readString(file)); //$NON-NLS-1$
            JsonObject afterThePreviews = call("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals("the previews left the sign where it was", //$NON-NLS-1$
                "unreadableFile", afterThePreviews.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            lightRefresh(false);
        }
    }

    /**
     * Turns the platform's refresh of a resource on access on or off.
     *
     * @param on true to let a read of a resource pick up the file on disk first
     */
    private static void lightRefresh(boolean on)
    {
        InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES)
            .putBoolean(ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, on);
    }

    /**
     * Leaves a marker file that does not parse in the project and makes the service take it for
     * unreadable, the way a read of it does.
     *
     * @return the file's path
     * @throws Exception when the file cannot be written
     */
    private Path aFileTheServiceTookForUnreadable() throws Exception
    {
        Path file = settingsFolder().resolve(CURRENT);
        Files.createDirectories(settingsFolder());
        Files.write(file, CONFLICT.getBytes(StandardCharsets.UTF_8));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        JsonObject refused = call("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the file that does not parse refuses a write: " + refused, //$NON-NLS-1$
            "unreadableFile", refused.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        return file;
    }

    /**
     * Calls an operation with and without the preview flag and insists that both answer the refusal
     * the sign of the file leaves, sentence for sentence.
     * <p>
     * The write of an operation whose route asks the file for its refusal answers the sentence that
     * refusal is written with, so the whole answer is compared here.
     * </p>
     *
     * @param arguments the operation followed by name/value pairs
     */
    private void assertThePreviewAnswersTheWrite(String... arguments)
    {
        JsonObject write = aRefusedWrite(arguments);
        JsonObject preview = aRefusedPreview(arguments);
        assertEquals("the preview refuses the way the write refuses", write.toString(), //$NON-NLS-1$
            preview.toString());
    }

    /**
     * Calls an operation with and without the preview flag and insists that both refuse for the same
     * reason.
     * <p>
     * The write of an operation whose route does not ask the file for its refusal answers the
     * sentence the service's own edit refuses with, which names what was not done, so the two
     * answers carry the same reason and not the same sentence.
     * </p>
     *
     * @param arguments the operation followed by name/value pairs
     */
    private void assertBothRefuseForTheSameReason(String... arguments)
    {
        JsonObject write = aRefusedWrite(arguments);
        JsonObject preview = aRefusedPreview(arguments);
        assertEquals("the preview refuses for the reason the write refuses for: " + write //$NON-NLS-1$
            + " against " + preview, write.get("reason").getAsString(), //$NON-NLS-1$
            preview.get("reason").getAsString());
    }

    /**
     * The answer of an operation that writes, which the written file's sign has to refuse.
     *
     * @param arguments the operation followed by name/value pairs
     * @return the answer
     */
    private JsonObject aRefusedWrite(String[] arguments)
    {
        JsonObject write = call(arguments);
        assertFalse("the write is refused by the sign the broken file left: " + write, //$NON-NLS-1$
            write.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("unreadableFile", write.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        return write;
    }

    /**
     * The answer of the same operation asked to write nothing, which has to refuse as well.
     *
     * @param arguments the operation followed by name/value pairs
     * @return the answer
     */
    private JsonObject aRefusedPreview(String[] arguments)
    {
        String[] previewing = java.util.Arrays.copyOf(arguments, arguments.length + 2);
        previewing[arguments.length] = "dryRun"; //$NON-NLS-1$
        previewing[arguments.length + 1] = "true"; //$NON-NLS-1$
        JsonObject preview = call(previewing);
        assertFalse("the preview promises an edit the write refuses: " + preview, //$NON-NLS-1$
            preview.get("success").getAsBoolean()); //$NON-NLS-1$
        return preview;
    }

    /**
     * The project's settings folder.
     *
     * @return the path
     */
    private static Path settingsFolder()
    {
        return project.getLocation().toFile().toPath().resolve(SETTINGS);
    }

    /**
     * One call to the facade with the given operation and arguments.
     *
     * @param arguments the operation followed by name/value pairs
     * @return the answer as parsed JSON
     */
    private JsonObject call(String... arguments)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", arguments[0]); //$NON-NLS-1$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        for (int i = 1; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return JsonParser.parseString(facade.execute(params)).getAsJsonObject();
    }
}
