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
import java.util.Arrays;
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
 * A preview of a project whose markers the service has already read answers from the state it
 * holds there, exactly as a write of the same edit does.
 * <p>
 * A write works from the storage the service read - the file, or the emptiness of a project that
 * has none - rather than going to disk again, which is what keeps an edit from overwriting a file
 * another process is in the middle of. A preview that read the disk instead would answer from
 * something the write never looks at: a file an earlier build wrote that appears after the read
 * would hand the preview a tag to delete while the write, working from the state it holds, refuses
 * that there is any such tag. A caller acts on the preview, so the two must not part.
 * </p>
 * <p>
 * The file appears on disk with the workspace told nothing, the state a file another program wrote
 * is in, and the refresh that would pick it up behind the test's back is off for this class and put
 * back afterwards.
 * </p>
 */
public class ATagPreviewTakesTheStateTheWriteTakesTest
{
    private static final String PROJECT = "AiEdtTagPreviewState"; //$NON-NLS-1$

    private static final String SETTINGS = ".settings"; //$NON-NLS-1$

    private static final String CURRENT = "aiedt-markers.yaml"; //$NON-NLS-1$

    private static final String LEGACY = "metadata-tags.yaml"; //$NON-NLS-1$

    private static final String EARLIER_DOCUMENT = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- name: Legacy" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String NO_SUCH_TAG = "tagNotFound"; //$NON-NLS-1$

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
        root = Files.createTempDirectory("aiedt-tag-preview-state"); //$NON-NLS-1$
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
     * The write refuses the tag, the preview answers sentence for sentence the same, and neither the
     * file an earlier build wrote nor the empty current name is touched.
     *
     * @throws Exception when the earlier file cannot be written or read back
     */
    @Test
    public void aPreviewAnswersFromTheStateTheServiceHolds() throws Exception
    {
        JsonObject state = call("delete_tag", "tag", "Ghost"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a project with no markers file holds no tag: " + state, //$NON-NLS-1$
            state.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(NO_SUCH_TAG, state.get("reason").getAsString()); //$NON-NLS-1$

        aFileAnEarlierBuildWrote();

        assertThePreviewAnswersTheWrite("delete_tag", "tag", "Legacy"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the earlier file is as it was written", EARLIER_DOCUMENT, //$NON-NLS-1$
            Files.readString(settingsFolder().resolve(LEGACY)));
    }

    /**
     * Leaves a markers file under the name an earlier build wrote, with the workspace told nothing
     * about it.
     *
     * @throws Exception when the folder cannot be created or the file written
     */
    private static void aFileAnEarlierBuildWrote() throws Exception
    {
        Path folder = settingsFolder();
        Files.createDirectories(folder);
        Files.write(folder.resolve(LEGACY), EARLIER_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        assertTrue("the earlier file is on disk", Files.exists(folder.resolve(LEGACY))); //$NON-NLS-1$
    }

    /**
     * Calls a write and the same call asked to write nothing, and insists that both answer the
     * refusal of the state the service holds, sentence for sentence.
     *
     * @param arguments the operation followed by name/value pairs
     */
    private void assertThePreviewAnswersTheWrite(String... arguments)
    {
        JsonObject write = call(arguments);
        assertFalse("the write refuses the delete the state does not hold: " + write, //$NON-NLS-1$
            write.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(NO_SUCH_TAG, write.get("reason").getAsString()); //$NON-NLS-1$
        String[] previewing = Arrays.copyOf(arguments, arguments.length + 2);
        previewing[arguments.length] = "dryRun"; //$NON-NLS-1$
        previewing[arguments.length + 1] = "true"; //$NON-NLS-1$
        JsonObject preview = call(previewing);
        assertEquals("the preview answers the write sentence for sentence", write.toString(), //$NON-NLS-1$
            preview.toString());
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

    /**
     * The project's settings folder.
     *
     * @return the path
     */
    private static Path settingsFolder()
    {
        return project.getLocation().toFile().toPath().resolve(SETTINGS);
    }
}
