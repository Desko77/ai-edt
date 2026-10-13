/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * What a {@code tag_admin} preview answers, what it leaves alone, and how its refusals compare with
 * the write it previews.
 * <p>
 * Every operation of the facade takes {@code dryRun=true}: the answer names what the call would
 * write - the tag a create would define, the tag before and after an update, the count a delete
 * would take off, the object's tags before and after an assign or an unassign - and the markers
 * file, the service's storage and the change notifications stay as they were. The facade runs
 * against the real marker service over a real temporary project, so "writes nothing" is checked as
 * the bytes on disk and "notifies nobody" as a listener that counts.
 * </p>
 * <p>
 * A refusal is the other half: a preview of a call the write would refuse has to refuse it the same
 * way, or a caller reads a preview as a promise and meets an error on the call it was told would
 * work. The state refusals are compared against the real call's own answer on the same state, text
 * and reason code - which is what pins the sentences the facade repeats from the service.
 * </p>
 */
public class ATagPreviewAnswersWithoutWritingTest
{
    private static final String PROJECT = "AiEdtTagPreview"; //$NON-NLS-1$

    private static final String OBJECT = "Catalog.Products"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private TagAdminFacadeTool facade;

    private MarkerManager service;

    private int notifications;

    /**
     * A temporary project the markers file can live in.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-tag-preview"); //$NON-NLS-1$
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
     * A fresh service over a fresh file, the facade with its doors registered the way the live
     * server registers them, and a listener counting the change notifications.
     *
     * @throws Exception when the markers file cannot be removed
     */
    @Before
    public void aFreshServiceAndFile() throws Exception
    {
        MarkerManager.dispose();
        Path settings = markerFile();
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
        notifications = 0;
        service = MarkerManager.getInstance();
        service.addMarkerChangeListener(new MarkerManager.IMarkerChangeListener()
        {
            @Override
            public void onMarkersChanged(IProject changed)
            {
                notifications++;
            }

            @Override
            public void onAssignmentsChanged(IProject changed, String objectFqn)
            {
                notifications++;
            }
        });
    }

    /**
     * Takes the doors back out and drops the service the test used.
     */
    @After
    public void theDoorsAndTheServiceGo()
    {
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
     * The bytes of the markers file, as a comparable string; "absent" when there is no file.
     *
     * @return the file's content or its absence
     */
    private String markersFileBytes()
    {
        try
        {
            Path file = markerFile();
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
     * The path of the project's markers file.
     *
     * @return the path
     */
    private Path markerFile()
    {
        return project.getLocation().toFile().toPath()
            .resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
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

    /** A preview of a create answers the tag it would define and leaves no file behind. */
    @Test
    public void aPreviewOfACreateAnswersTheTagAndWritesNothing()
    {
        assertEquals("absent", markersFileBytes()); //$NON-NLS-1$

        JsonObject answer = succeeded(call("create_tag", "tag", "Important", "color", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "#112233", "description", "needs a look", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue(answer.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get("created").getAsBoolean()); //$NON-NLS-1$
        JsonObject tag = answer.getAsJsonObject("tag"); //$NON-NLS-1$
        assertEquals("Important", tag.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#112233", tag.get("color").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("needs a look", tag.get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a preview writes no file", "absent", markersFileBytes()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and defines nothing", 0, //$NON-NLS-1$
            service.getMarkerStorage(project).getTags().size());
        assertEquals("and notifies nobody", 0, notifications); //$NON-NLS-1$
    }

    /**
     * A preview of a create of a tag that is already defined answers exactly what the write
     * answers, so a caller that ran the preview knows which call to repeat.
     */
    @Test
    public void aPreviewOfADefinedTagRefusesExactlyLikeTheWrite()
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$
        String before = markersFileBytes();

        JsonObject write = call("create_tag", "tag", "Important", "color", "#445566"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject preview = call("create_tag", "tag", "Important", "color", "#445566", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(write.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(preview.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(write.toString(), preview.toString());
        assertEquals("tagExists", preview.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("neither call wrote", before, markersFileBytes()); //$NON-NLS-1$
        assertEquals("the stored color is still the default", "#808080", //$NON-NLS-1$ //$NON-NLS-2$
            service.getMarkerStorage(project).getMarkerByName("Important").getColor()); //$NON-NLS-1$
    }

    /**
     * A preview of a create the write would refuse for a read-only markers file refuses exactly
     * the same way, and neither call touches the bytes.
     * <p>
     * The promise of a preview is that it passed the checks the write passes. A file the system
     * will not open for writing is one of them, and it is the one a preview could miss: it reads
     * the file, finds it parseable, and answers a tag the write would never store.
     * </p>
     *
     * @throws Exception when the file cannot be made read-only or read back
     */
    @Test
    public void aPreviewOfACreateOnAReadOnlyFileRefusesExactlyLikeTheWrite() throws Exception
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path file = markerFile();
        byte[] before = Files.readAllBytes(file);
        readOnly(file, true);
        try
        {
            assumeFalse("the file system lets this user write a read-only file", //$NON-NLS-1$
                Files.isWritable(file));

            JsonObject preview = call("create_tag", "tag", "Rack", "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            JsonObject write = call("create_tag", "tag", "Rack"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertFalse(preview.toString(), preview.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(write.toString(), preview.toString());
            assertEquals("readOnlyFile", preview.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals("the file kept its bytes", before, Files.readAllBytes(file)); //$NON-NLS-1$
            assertEquals("and no tag was defined", 1, //$NON-NLS-1$
                service.getMarkerStorage(project).getTags().size());
        }
        finally
        {
            readOnly(file, false);
        }
    }

    /** A preview of an update answers the tag as it is and as it would be. */
    @Test
    public void aPreviewOfAnUpdateAnswersTheTagBeforeAndAfter()
    {
        succeeded(call("create_tag", "tag", "Important", "color", "#112233")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Important\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String before = markersFileBytes();

        JsonObject answer = succeeded(call("update_tag", "tag", "Important", "newName", "Case", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "color", "#445566", "description", "checked", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue(answer.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        JsonObject tagBefore = answer.getAsJsonObject("tag"); //$NON-NLS-1$
        assertEquals("Important", tagBefore.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#112233", tagBefore.get("color").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject tagAfter = answer.getAsJsonObject("tagAfter"); //$NON-NLS-1$
        assertEquals("Case", tagAfter.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#445566", tagAfter.get("color").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("checked", tagAfter.get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the rename would carry the assignment", 1, //$NON-NLS-1$
            answer.get("movedAssignments").getAsInt()); //$NON-NLS-1$
        assertEquals("the preview wrote nothing", before, markersFileBytes()); //$NON-NLS-1$
        assertNotNull("the tag still carries its name", //$NON-NLS-1$
            service.getMarkerStorage(project).getMarkerByName("Important")); //$NON-NLS-1$
        assertEquals("and its color", "#112233", //$NON-NLS-1$ //$NON-NLS-2$
            service.getMarkerStorage(project).getMarkerByName("Important").getColor()); //$NON-NLS-1$
        assertEquals("and the object still carries it", java.util.List.of("Important"), //$NON-NLS-1$ //$NON-NLS-2$
            new java.util.ArrayList<>(service.getMarkerStorage(project).getMarkerNames(OBJECT)));
    }

    /** A preview of a rename onto a name another tag carries refuses exactly like the write. */
    @Test
    public void aPreviewOfARenameOntoATakenNameRefusesExactlyLikeTheWrite()
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$
        succeeded(call("create_tag", "tag", "Case")); //$NON-NLS-1$ //$NON-NLS-2$
        String before = markersFileBytes();

        JsonObject write = call("update_tag", "tag", "Important", "newName", "Case", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "color", "#010203"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject preview = call("update_tag", "tag", "Important", "newName", "Case", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "color", "#010203", "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(write.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(preview.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(write.get("error").getAsString(), preview.get("error").getAsString());
        assertEquals("nameTaken", preview.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("neither call wrote", before, markersFileBytes()); //$NON-NLS-1$
        assertEquals("the color the refused rename named was not set", "#808080", //$NON-NLS-1$ //$NON-NLS-2$
            service.getMarkerStorage(project).getMarkerByName("Important").getColor()); //$NON-NLS-1$
    }

    /** A preview of an assign answers the object's tags before and after, and the skipped ones. */
    @Test
    public void aPreviewOfAnAssignAnswersTheObjectTagsBeforeAndAfter()
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$
        succeeded(call("create_tag", "tag", "Review")); //$NON-NLS-1$ //$NON-NLS-2$
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Important\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String before = markersFileBytes();

        JsonObject answer = succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "[\"Important\", \"Review\"]", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(answer.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertEquals(OBJECT, answer.get("objectFqn").getAsString()); //$NON-NLS-1$
        assertEquals(java.util.List.of("Important"), names(answer.getAsJsonArray("tagsBefore"))); //$NON-NLS-1$
        assertEquals(java.util.List.of("Review"), names(answer.getAsJsonArray("assigned"))); //$NON-NLS-1$
        assertEquals(java.util.List.of("Important", "Review"), //$NON-NLS-1$ //$NON-NLS-2$
            names(answer.getAsJsonArray("tagsAfter"))); //$NON-NLS-1$
        JsonArray skipped = answer.getAsJsonArray("skipped"); //$NON-NLS-1$
        assertEquals(1, skipped.size());
        assertEquals("Important", skipped.get(0).getAsJsonObject().get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("alreadyAssigned", //$NON-NLS-1$
            skipped.get(0).getAsJsonObject().get("reason").getAsString()); //$NON-NLS-1$
        assertEquals("the preview wrote nothing", before, markersFileBytes()); //$NON-NLS-1$
        assertEquals("and assigned nothing", java.util.List.of("Important"), //$NON-NLS-1$ //$NON-NLS-2$
            new java.util.ArrayList<>(service.getMarkerStorage(project).getMarkerNames(OBJECT)));
    }

    /** A preview of an unassign answers what would come off and what the object does not carry. */
    @Test
    public void aPreviewOfAnUnassignAnswersTheRemovedAndSkipped()
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$
        succeeded(call("create_tag", "tag", "Review")); //$NON-NLS-1$ //$NON-NLS-2$
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Important\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String before = markersFileBytes();

        JsonObject answer = succeeded(call("unassign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "[\"Important\", \"Review\"]", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(answer.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertEquals(java.util.List.of("Important"), names(answer.getAsJsonArray("tagsBefore"))); //$NON-NLS-1$
        assertEquals(java.util.List.of("Important"), names(answer.getAsJsonArray("removed"))); //$NON-NLS-1$
        assertTrue(names(answer.getAsJsonArray("tagsAfter")).isEmpty()); //$NON-NLS-1$
        assertEquals("notAssigned", //$NON-NLS-1$
            answer.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString()); //$NON-NLS-1$
        assertEquals("the preview wrote nothing", before, markersFileBytes()); //$NON-NLS-1$
        assertEquals("and removed nothing", java.util.List.of("Important"), //$NON-NLS-1$ //$NON-NLS-2$
            new java.util.ArrayList<>(service.getMarkerStorage(project).getMarkerNames(OBJECT)));
    }

    /**
     * A preview of an unassign that would remove nothing refuses exactly what the write refuses,
     * and writes nothing - the write refuses too, so the two calls agree.
     */
    @Test
    public void aPreviewOfAnUnassignOfNothingRefusesLikeTheWrite()
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$
        String before = markersFileBytes();

        JsonObject write = call("unassign_tag", "objectFqn", OBJECT, "tags", "[\"Important\"]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        JsonObject preview = call("unassign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "[\"Important\"]", "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(write.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(preview.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(write.get("error").getAsString(), preview.get("error").getAsString());
        assertEquals("notAssigned", preview.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("neither call wrote", before, markersFileBytes()); //$NON-NLS-1$
    }

    /**
     * A preview of an assign and of an unassign repeats the object's tags in the order the file
     * keeps them, and answers the order a write over the same file leaves behind.
     * <p>
     * The file lists a name no tag answers, in the middle of the object's list. The previews read
     * the object's own assignment list, so that name keeps its place instead of travelling to the
     * end, and the after-state they answer is read back from a write over a copy of the same file.
     * </p>
     *
     * @throws Exception when the markers file cannot be edited or read
     */
    @Test
    public void aPreviewRepeatsTheObjectTagsInTheOrderTheFileKeepsThem() throws Exception
    {
        succeeded(call("create_tag", "tag", "First")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        succeeded(call("create_tag", "tag", "Third")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        succeeded(call("create_tag", "tag", "Fourth")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"First\", \"Third\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String withADanglingName = aDanglingNameInTheMiddleOfTheList();

        JsonObject assign = succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[\"Fourth\"]", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(java.util.List.of("First", "Ghost", "Third"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            names(assign.getAsJsonArray("tagsBefore"))); //$NON-NLS-1$
        assertEquals(java.util.List.of("First", "Ghost", "Third", "Fourth"), //$NON-NLS-1$ //$NON-NLS-2$
            names(assign.getAsJsonArray("tagsAfter"))); //$NON-NLS-1$
        assertEquals("the preview wrote nothing", withADanglingName, //$NON-NLS-1$
            Files.readString(markerFile()));

        // The write over a copy of the same file leaves the list the preview answered.
        writeMarkerFile(withADanglingName);
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Fourth\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals(java.util.List.of("First", "Ghost", "Third", "Fourth"), storedNames()); //$NON-NLS-1$

        JsonObject unassign = succeeded(call("unassign_tag", "objectFqn", OBJECT, "tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[\"Third\"]", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(java.util.List.of("First", "Ghost", "Third", "Fourth"), //$NON-NLS-1$ //$NON-NLS-2$
            names(unassign.getAsJsonArray("tagsBefore"))); //$NON-NLS-1$
        assertEquals(java.util.List.of("First", "Ghost", "Fourth"), //$NON-NLS-1$ //$NON-NLS-2$
            names(unassign.getAsJsonArray("tagsAfter"))); //$NON-NLS-1$

        succeeded(call("unassign_tag", "objectFqn", OBJECT, "tags", "[\"Third\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals(java.util.List.of("First", "Ghost", "Fourth"), storedNames()); //$NON-NLS-1$
    }

    /**
     * Puts an assignment to a name no tag answers in the middle of the object's list in the
     * markers file, and answers the file's text as it now stands.
     *
     * @return the text of the edited file
     * @throws Exception when the file cannot be read or written
     */
    private String aDanglingNameInTheMiddleOfTheList() throws Exception
    {
        String text = Files.readString(markerFile());
        String eol = text.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        java.util.regex.Matcher first = java.util.regex.Pattern.compile("(?m)^([ \\t]*)- First$") //$NON-NLS-1$
            .matcher(text);
        assertTrue("the file lists the object's first tag: " + text, first.find()); //$NON-NLS-1$
        String edited = text.substring(0, first.end()) + eol + first.group(1) + "- Ghost" //$NON-NLS-1$
            + text.substring(first.end());
        writeMarkerFile(edited);
        return edited;
    }

    /**
     * Writes the markers file with the given text, as a change from outside the workspace.
     *
     * @param text the file's content
     * @throws Exception when the file cannot be written or the project refreshed
     */
    private void writeMarkerFile(String text) throws Exception
    {
        Files.writeString(markerFile(), text, java.nio.charset.StandardCharsets.UTF_8);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * The object's tags as a fresh read of the markers file records them.
     *
     * @return the names, in file order
     */
    private java.util.List<String> storedNames()
    {
        MarkerManager.dispose();
        java.util.List<String> names = MarkerManager.getInstance().getMarkerStorage(project)
            .getAssignments().get(OBJECT);
        return names == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(names);
    }

    /**
     * The argument refusals are the same with and without {@code dryRun}: a malformed call is not
     * previewed, it is refused.
     */
    @Test
    public void everyArgumentRefusalIsTheSameWithAndWithoutDryRun()
    {
        succeeded(call("create_tag", "tag", "Important")); //$NON-NLS-1$ //$NON-NLS-2$
        String[][] calls = {
            {"create_tag", "tag", "  ", "color", "#112233"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"create_tag", "tag", "Rack", "color", "blue"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"update_tag", "tag", "NoSuchTag", "color", "#112233"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"update_tag", "tag", "Important", "color", "112233"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"update_tag", "tag", "Important", "newName", " "}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"delete_tag", "tag", "NoSuchTag"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"assign_tag", "objectFqn", OBJECT, "tags", "[\"NoSuchTag\"]"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"assign_tag", "tags", "[\"Important\"]"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"assign_tag", "objectFqn", OBJECT, "tags", "[\"  \"]"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"unassign_tag", "objectFqn", OBJECT, "tags", "[\"NoSuchTag\"]"} //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        String before = markersFileBytes();
        java.util.List<String> differences = new java.util.ArrayList<>();
        for (String[] one : calls)
        {
            String[] withDryRun = java.util.Arrays.copyOf(one, one.length + 2);
            withDryRun[one.length] = "dryRun"; //$NON-NLS-1$
            withDryRun[one.length + 1] = "true"; //$NON-NLS-1$
            JsonObject write = call(one[0], java.util.Arrays.copyOfRange(one, 1, one.length));
            JsonObject preview = call(one[0], java.util.Arrays.copyOfRange(withDryRun, 1,
                withDryRun.length));
            assertFalse(java.util.Arrays.toString(one), write.get("success").getAsBoolean()); //$NON-NLS-1$
            if (!write.toString().equals(preview.toString()))
            {
                differences.add(java.util.Arrays.toString(one) + ": " + write + " != " + preview); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue("these refusals differ with dryRun: " + differences, differences.isEmpty()); //$NON-NLS-1$
        assertEquals("no refused call wrote", before, markersFileBytes()); //$NON-NLS-1$
    }

    /**
     * Every preview leaves the file byte for byte as it was and tells no listener anything. One
     * real write follows, so the comparison and the counter are shown to see a write when there is
     * one.
     */
    @Test
    public void everyPreviewLeavesTheFileAndTheListenersAlone()
    {
        succeeded(call("create_tag", "tag", "Important", "color", "#112233")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        succeeded(call("create_tag", "tag", "Review")); //$NON-NLS-1$ //$NON-NLS-2$
        succeeded(call("assign_tag", "objectFqn", OBJECT, "tags", "[\"Important\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String before = markersFileBytes();
        notifications = 0;

        String[][] previews = {
            {"create_tag", "tag", "Fresh", "dryRun", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {"update_tag", "tag", "Important", "newName", "Case", "dryRun", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            {"delete_tag", "tag", "Important", "dryRun", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {"assign_tag", "objectFqn", OBJECT, "tags", "[\"Review\"]", "dryRun", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            {"unassign_tag", "objectFqn", OBJECT, "tags", "[\"Important\"]", "dryRun", "true"} //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        };
        for (String[] preview : previews)
        {
            JsonObject answer = call(preview[0],
                java.util.Arrays.copyOfRange(preview, 1, preview.length));
            assertTrue(java.util.Arrays.toString(preview), //$NON-NLS-1$
                answer.get("dryRun") != null && answer.get("dryRun").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(java.util.Arrays.toString(preview), before, markersFileBytes()); //$NON-NLS-1$
        }
        assertEquals("the previews notified nobody", 0, notifications); //$NON-NLS-1$

        JsonObject written = succeeded(call("create_tag", "tag", "Fresh")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(written.get("created").getAsBoolean()); //$NON-NLS-1$
        assertFalse("a write is what changes the file", before.equals(markersFileBytes())); //$NON-NLS-1$
        assertTrue("and what notifies a listener", notifications > 0); //$NON-NLS-1$
    }

    /**
     * The tag names of a JSON array of nodes, in the order it carries them.
     *
     * @param nodes the array
     * @return the names
     */
    private static java.util.List<String> names(JsonArray nodes)
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.size(); i++)
        {
            names.add(nodes.get(i).getAsString());
        }
        return names;
    }
}
