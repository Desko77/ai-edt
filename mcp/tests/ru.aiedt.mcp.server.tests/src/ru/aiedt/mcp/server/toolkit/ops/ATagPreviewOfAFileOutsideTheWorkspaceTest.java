/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
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
 * A markers file that is on disk while the resource tree knows nothing of it is judged by the file
 * itself, and a preview judges it exactly as the write does.
 * <p>
 * A program outside the workspace can write the settings folder without the workspace ever hearing
 * about it, and then the resource handle answers that nothing is there. A preview that took that
 * answer for the state of the file would pass its checks and promise a write the write itself
 * refuses, which is the one thing a preview must not do.
 * </p>
 * <p>
 * The workspace is told nothing between the file appearing on disk and the calls under test, so the
 * desync belongs to the fixture rather than to the code; the light refresh that would repair it
 * behind the test's back is switched off for this class and put back afterwards.
 * </p>
 */
public class ATagPreviewOfAFileOutsideTheWorkspaceTest
{
    private static final String PROJECT = "AiEdtTagPreviewOutside"; //$NON-NLS-1$

    private static final String SETTINGS = ".settings"; //$NON-NLS-1$

    private static final String CURRENT = "aiedt-markers.yaml"; //$NON-NLS-1$

    private static final String LEGACY = "metadata-tags.yaml"; //$NON-NLS-1$

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
        root = Files.createTempDirectory("aiedt-tag-preview-outside"); //$NON-NLS-1$
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
     * A fresh service over an empty settings folder and the facade with its doors registered the way
     * the live server registers them.
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
     * A preview of a create over a read-only file the workspace does not know refuses exactly what
     * the write refuses, and neither call touches the bytes.
     *
     * @throws Exception when the file cannot be made read-only or read back
     */
    @Test
    public void aPreviewOfAReadOnlyFileOutsideTheWorkspaceRefusesLikeTheWrite() throws Exception
    {
        Path file = aFileOutsideTheWorkspace();
        byte[] content = Files.readAllBytes(file);
        readOnly(file, true);
        try
        {
            assertFalse("the workspace does not know the file: " + settingsFile().exists(), //$NON-NLS-1$
                settingsFile().exists());
            assumeFalse("the file system lets this user write a read-only file", //$NON-NLS-1$
                Files.isWritable(file));

            JsonObject preview = call("create_tag", "tag", "Rack", "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            JsonObject write = call("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertFalse("the preview refuses the write it previews: " + preview, //$NON-NLS-1$
                preview.get("success").getAsBoolean());
            assertEquals("readOnlyFile", preview.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(write.toString(), preview.toString());
            assertEquals("the file kept its bytes", //$NON-NLS-1$
                java.util.Base64.getEncoder().encodeToString(content), //$NON-NLS-1$
                java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
        finally
        {
            readOnly(file, false);
        }
    }

    /**
     * A writable file the workspace does not know is read from disk and previewed: the answer names
     * the tag, so the refusal over the read-only file comes from the file rather than from the
     * workspace having no file to name, and the bytes stay as they were.
     *
     * @throws Exception when the file cannot be read back
     */
    @Test
    public void aWritableFileOutsideTheWorkspaceIsPreviewed() throws Exception
    {
        Path file = aFileOutsideTheWorkspace();
        String before = Files.readString(file);
        assertFalse("the workspace does not know the file", settingsFile().exists()); //$NON-NLS-1$

        JsonObject preview = succeeded(call("create_tag", "tag", "Rack", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("Rack", preview.getAsJsonObject("tag").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the preview stored nothing", before, Files.readString(file)); //$NON-NLS-1$
    }

    /**
     * Leaves a markers file holding one tag on disk with the workspace told nothing about it.
     * <p>
     * The file is written by this version's own write, so its shape is the shape the loader reads.
     * The settings folder then goes through the workspace, which takes its entry with it, and comes
     * back on disk alone: the handle the code under test reads names a file the tree has never
     * heard of, and the light refresh that would pick the file up is off for this class. Putting
     * the folder back on disk rather than refreshing the tree keeps the state the same on every
     * run - a folder whose own stamp did not move is left unscanned by a refresh.
     * </p>
     *
     * @return the file's path
     * @throws Exception when the file cannot be written, forgotten or read back
     */
    private Path aFileOutsideTheWorkspace() throws Exception
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path folder = settingsFolder();
        Path file = folder.resolve(CURRENT);
        byte[] content = Files.readAllBytes(file);

        project.getFolder(SETTINGS).delete(IResource.FORCE, new NullProgressMonitor());
        Files.createDirectories(folder);
        Files.write(file, content);

        assertTrue("the file is on disk", Files.exists(file)); //$NON-NLS-1$
        assertFalse("and the workspace does not know it", settingsFile().exists()); //$NON-NLS-1$
        assertNotNull("and the handle still names the path on disk", settingsFile().getLocation()); //$NON-NLS-1$
        return file;
    }

    /**
     * The markers file as a resource.
     *
     * @return the handle, which answers that it does not exist while the tree knows nothing
     */
    private static IFile settingsFile()
    {
        IFolder folder = project.getFolder(SETTINGS);
        return folder.getFile(CURRENT);
    }

    /**
     * Turns a file's write permission on or off.
     * <p>
     * The permission bit is what makes the file read-only on every system; the DOS attribute is
     * changed on Windows only, although Linux offers a DOS view as well.
     * </p>
     *
     * @param file the file to change
     * @param readOnly true to make the file read-only
     * @throws Exception when the attribute cannot be set
     */
    private static void readOnly(Path file, boolean readOnly) throws Exception
    {
        file.toFile().setWritable(!readOnly);
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null && System.getProperty("os.name", "").toLowerCase(Locale.ROOT) //$NON-NLS-1$ //$NON-NLS-2$
            .startsWith("windows")) //$NON-NLS-1$
        {
            dos.setReadOnly(readOnly);
        }
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
}
