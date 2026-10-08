/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerWriteOutcome;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What each {@code tag_admin} operation answers, and every reason it can refuse with.
 * <p>
 * The facade runs against the real marker service over a real temporary project, so the file
 * refusals are produced the way the service produces them - as a refusal code on the write - and
 * the answers can be compared against the bytes on disk. The model check of {@code assign_tag} is
 * a seam the test fills per case, because a unit test cannot stand a configuration model up; the
 * rule itself is proven by {@link ATagAdminModelCheckResolvesNestedAddressesTest}.
 * </p>
 */
public class ATagAdminFacadeAnswersItsOperationsTest
{
    private static final String PROJECT = "AiEdtTagAdminFacade"; //$NON-NLS-1$

    private static final String CONFLICT = "<<<<<<< ours" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "tags: []" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "=======" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + ">>>>>>> theirs"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private TagAdminFacadeTool facade;

    private ToolResult modelRefusal;

    private String canonical;

    /**
     * A temporary project the marker file can live in.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-tag-admin"); //$NON-NLS-1$
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
     * A fresh service and a fresh file, so one test cannot read what another wrote.
     *
     * @throws Exception when the marker file cannot be removed
     */
    @Before
    public void aFreshServiceAndFile() throws Exception
    {
        MarkerManager.dispose();
        Path settings = project.getLocation().toFile().toPath()
            .resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
        if (Files.exists(settings))
        {
            settings.toFile().setWritable(true);
            Files.deleteIfExists(settings);
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        modelRefusal = null;
        canonical = null;
        facade = new TagAdminFacadeTool()
        {
            @Override
            ObjectCheck checkObject(IProject askedProject, String objectFqn)
            {
                if (modelRefusal != null)
                {
                    return ObjectCheck.refused(modelRefusal);
                }
                return ObjectCheck.of(canonical != null ? canonical : objectFqn.trim());
            }
        };
        // The writes ask their gate about the doors, so the doors have to sit in the catalog the
        // way the live server puts them there at startup.
        ru.aiedt.mcp.server.toolkit.McpToolCatalog catalog =
            ru.aiedt.mcp.server.toolkit.McpToolCatalog.getInstance();
        catalog.register(facade);
        catalog.register(new TagCreateTool());
        catalog.register(new TagUpdateTool());
        catalog.register(new TagDeleteTool());
        catalog.register(new TagAssignTool());
        catalog.register(new TagUnassignTool());
    }

    /**
     * Takes the doors back out and drops the service the test used.
     */
    @After
    public void theDoorsAndTheServiceGo()
    {
        ru.aiedt.mcp.server.toolkit.McpToolCatalog catalog =
            ru.aiedt.mcp.server.toolkit.McpToolCatalog.getInstance();
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
     * The path of the project's marker file.
     *
     * @return the path
     */
    private Path markerFile()
    {
        return project.getLocation().toFile().toPath()
            .resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A tag is created with the color and description the call gave. */
    @Test
    public void aTagIsCreatedWithColorAndDescription()
    {
        JsonObject answer = call("create_tag", "tag", "Important", "color", "#112233", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "description", "needs a look"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("created").getAsBoolean()); //$NON-NLS-1$
        JsonObject tag = answer.getAsJsonObject("tag"); //$NON-NLS-1$
        assertEquals("Important", tag.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#112233", tag.get("color").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("needs a look", tag.get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Files.exists(markerFile()));
        assertNotNull(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerByName("Important")); //$NON-NLS-1$
    }

    /** A create without a color keeps the default gray, and without a description an empty one. */
    @Test
    public void aCreateWithoutColorKeepsTheDefault()
    {
        JsonObject answer = call("create_tag", "tag", "Plain"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject tag = answer.getAsJsonObject("tag"); //$NON-NLS-1$
        assertEquals("#808080", tag.get("color").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(tag.get("description").getAsString().isEmpty()); //$NON-NLS-1$
    }

    /** A name another tag already carries refuses the create. */
    @Test
    public void aTakenNameRefusesTheCreate()
    {
        assertTrue(call("create_tag", "tag", "Important").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = call("create_tag", "tag", "Important", "color", "#445566"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("tagExists", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // The refusal changed nothing: the stored color is still the default.
        assertEquals("#808080", MarkerManager.getInstance().getMarkerStorage(project) //$NON-NLS-1$
            .getMarkerByName("Important").getColor()); //$NON-NLS-1$
    }

    /** Names that differ only in case are different tags. */
    @Test
    public void namesThatDifferByCaseAreDifferentTags()
    {
        assertTrue(call("create_tag", "tag", "Bug").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = call("create_tag", "tag", "bug", "color", "#654321"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, MarkerManager.getInstance().getMarkerStorage(project).getTags().size());
    }

    /** A name that is empty once trimmed refuses the call. */
    @Test
    public void anEmptyNameRefusesTheCreate()
    {
        JsonObject blanks = call("create_tag", "tag", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(blanks.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("invalidName", blanks.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject missing = call("create_tag"); //$NON-NLS-1$
        assertFalse(missing.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("invalidName", missing.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A color that is not a #RRGGBB hex value refuses the call. */
    @Test
    public void aColorOfTheWrongShapeRefusesTheCreate()
    {
        for (String color : new String[] {"blue", "112233", "#1122", "#1122334"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            JsonObject answer = call("create_tag", "tag", "Colored", "color", color); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(color, answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("invalidColor", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue("the refusals wrote nothing", markersFileBytes().equals("absent")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A rename carries every assignment and answers how many objects it moved. */
    @Test
    public void aRenameCarriesTheAssignments()
    {
        assertTrue(call("create_tag", "tag", "Old").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"Old\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Clients", "tags", "[\"Old\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject answer = call("update_tag", "tag", "Old", "newName", "New", "color", "#445566"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, answer.get("movedAssignments").getAsInt()); //$NON-NLS-1$
        assertEquals("New", answer.getAsJsonObject("tag").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(MarkerManager.getInstance().getMarkerStorage(project).getMarkerByName("Old")); //$NON-NLS-1$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").contains("New")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Clients").contains("New")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A name another tag carries refuses the update and touches no assignment. */
    @Test
    public void aTakenNewNameRefusesTheUpdate()
    {
        assertTrue(call("create_tag", "tag", "First").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("create_tag", "tag", "Second", "color", "#010203").get("success") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsBoolean());
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"Second\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        String before = markersFileBytes();
        JsonObject answer = call("update_tag", "tag", "Second", "newName", "First"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("nameTaken", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, markersFileBytes());
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").contains("Second")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A tag that is not defined refuses the update. */
    @Test
    public void anUnknownTagRefusesTheUpdate()
    {
        JsonObject answer = call("update_tag", "tag", "Ghost", "newName", "Whatever"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("tagNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An empty tag or newName refuses the update. */
    @Test
    public void anEmptyNameRefusesTheUpdate()
    {
        assertTrue(call("create_tag", "tag", "Kept").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("invalidName", call("update_tag", "tag", "  ").get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("invalidName", //$NON-NLS-1$
            call("update_tag", "tag", "Kept", "newName", " ").get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("invalidColor", //$NON-NLS-1$
            call("update_tag", "tag", "Kept", "color", "red").get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull(MarkerManager.getInstance().getMarkerStorage(project).getMarkerByName("Kept")); //$NON-NLS-1$
    }

    /** A delete takes the tag off every object and answers how many assignments went. */
    @Test
    public void aDeleteAnswersTheAssignmentsItTook()
    {
        assertTrue(call("create_tag", "tag", "Gone").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"Gone\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Clients", "tags", "[\"Gone\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject answer = call("delete_tag", "tag", "Gone"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("deleted").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, answer.get("assignments").getAsInt()); //$NON-NLS-1$
        assertNull(MarkerManager.getInstance().getMarkerStorage(project).getMarkerByName("Gone")); //$NON-NLS-1$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").isEmpty()); //$NON-NLS-1$
    }

    /** A dry run answers the assignment count and writes nothing. */
    @Test
    public void aDryRunAnswersTheCountWithoutWriting()
    {
        assertTrue(call("create_tag", "tag", "Dry").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"Dry\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        String before = markersFileBytes();
        JsonObject answer = call("delete_tag", "tag", "Dry", "dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get("deleted").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, answer.get("assignments").getAsInt()); //$NON-NLS-1$
        assertEquals(before, markersFileBytes());
        assertNotNull(MarkerManager.getInstance().getMarkerStorage(project).getMarkerByName("Dry")); //$NON-NLS-1$
    }

    /** A tag that is not defined refuses the delete. */
    @Test
    public void anUnknownTagRefusesTheDelete()
    {
        JsonObject answer = call("delete_tag", "tag", "Ghost"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("tagNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Assignments land on the object the model named, in the order the call listed. */
    @Test
    public void tagsAreAssignedToTheObject()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("create_tag", "tag", "Two").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        canonical = "Catalog.Products"; //$NON-NLS-1$
        JsonObject answer = call("assign_tag", "objectFqn", "catalog.products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\", \"Two\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        // The address is the one the model answered, not the spelling the caller wrote.
        assertEquals("Catalog.Products", answer.get("objectFqn").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray assigned = answer.getAsJsonArray("assigned"); //$NON-NLS-1$
        assertEquals(Arrays.asList("One", "Two"), //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList(assigned.get(0).getAsString(), assigned.get(1).getAsString()));
        assertEquals(0, answer.getAsJsonArray("skipped").size()); //$NON-NLS-1$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").containsAll(Arrays.asList("One", "Two"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A tag the object already carries is skipped, not refused. */
    @Test
    public void anAlreadyCarriedTagIsSkipped()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("create_tag", "tag", "Two").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"One\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject answer = call("assign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\", \"Two\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, answer.getAsJsonArray("assigned").size()); //$NON-NLS-1$
        assertEquals("Two", answer.getAsJsonArray("assigned").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject skip = answer.getAsJsonArray("skipped").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("One", skip.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("alreadyAssigned", skip.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** One unknown tag in the list refuses the whole call and assigns nothing. */
    @Test
    public void oneUnknownTagRefusesTheWholeAssign()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        String before = markersFileBytes();
        JsonObject answer = call("assign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\", \"Ghost\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("tagNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, markersFileBytes());
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").isEmpty()); //$NON-NLS-1$
    }

    /** An object the model does not hold refuses the assign, with the nearest names. */
    @Test
    public void anUnknownObjectRefusesTheAssignWithNearestNames()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        modelRefusal = ToolResult.error(
            "No object 'Catalog.Prodcts' in the configuration of this project. " //$NON-NLS-1$
                + "Did you mean Catalog.Products?")
            .put("reason", TagAdminFacadeTool.REASON_OBJECT_NOT_FOUND); //$NON-NLS-1$
        JsonObject answer = call("assign_tag", "objectFqn", "Catalog.Prodcts", "tags", "[\"One\"]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("objectNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("error").getAsString().contains("Did you mean Catalog.Products?")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Prodcts").isEmpty()); //$NON-NLS-1$
    }

    /** Unassignment removes what the object carries and skips the rest. */
    @Test
    public void tagsAreUnassignedFromTheObject()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("create_tag", "tag", "Two").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"One\", \"Two\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject answer = call("unassign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\", \"Two\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(Arrays.asList("One", "Two"), //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList(answer.getAsJsonArray("removed").get(0).getAsString(), //$NON-NLS-1$
                answer.getAsJsonArray("removed").get(1).getAsString())); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("skipped").size()); //$NON-NLS-1$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").isEmpty()); //$NON-NLS-1$
    }

    /** Nothing removed is an error naming notAssigned, with the skipped list saying why. */
    @Test
    public void nothingRemovedIsAnError()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"One\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject answer = call("unassign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject again = call("unassign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("notAssigned", again.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("notAssigned", //$NON-NLS-1$
            again.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** One unknown tag refuses the whole unassign. */
    @Test
    public void oneUnknownTagRefusesTheWholeUnassign()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"One\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject answer = call("unassign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\", \"Ghost\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("tagNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").contains("One")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A blank element in the tags array refuses the whole assign, and nothing is written. */
    @Test
    public void aBlankTagRefusesTheWholeAssign()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        String before = markersFileBytes();
        JsonObject answer = call("assign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "tags", "[\"One\", \" \"]"); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("invalidName", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, markersFileBytes());
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").isEmpty()); //$NON-NLS-1$
    }

    /** A blank element in the tags array refuses the whole unassign, and nothing is written. */
    @Test
    public void aBlankTagRefusesTheWholeUnassign()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"One\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        String before = markersFileBytes();
        JsonObject answer = call("unassign_tag", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "tags", "[\"One\", \"\"]"); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("invalidName", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, markersFileBytes());
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").contains("One")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A marker file that cannot be read at all - refused access, a drive that went away - refuses
     * the pre-checked writes with {@code saveFailed}, not with {@code tagNotFound} off an empty
     * snapshot, and the file is left as it stands.
     */
    @Test
    public void aFileThatCannotBeReadRefusesTheWritesWithSaveFailed() throws Exception
    {
        Path yaml = markerFile();
        Files.createDirectories(yaml.getParent());
        byte[] original = ("tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "- color: '#112233'" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "  description: ''" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "  name: First" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "assignments: {}" + "\n").getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.write(yaml, original);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        refuseReading(yaml, true);
        try
        {
            Assume.assumeFalse("the file system lets this user read a refused file", //$NON-NLS-1$
                Files.isReadable(yaml));
            for (String[] write : new String[][] {
                {"update_tag", "tag", "First", "color", "#445566"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                {"delete_tag", "tag", "First"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"First\"]"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {"unassign_tag", "objectFqn", "Catalog.Products", "tags", "[\"First\"]"}}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                JsonObject answer = call(write[0], Arrays.copyOfRange(write, 1, write.length));
                assertFalse(write[0], answer.get("success").getAsBoolean()); //$NON-NLS-1$
                assertEquals(write[0], "saveFailed", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        finally
        {
            refuseReading(yaml, false);
        }
        org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(yaml));
    }

    /**
     * Refuses or allows the file's reading for its owner: an access-control entry on file systems
     * that have them, the owner's read permission where POSIX rules apply.
     *
     * @param file the file to refuse or allow
     * @param refuse true to refuse the owner's reading
     * @throws IOException when the access cannot be changed
     */
    private static void refuseReading(Path file, boolean refuse) throws IOException
    {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null)
        {
            java.nio.file.attribute.UserPrincipal owner = acl.getOwner();
            List<AclEntry> entries = new ArrayList<>(acl.getAcl());
            if (refuse)
            {
                entries.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build());
            }
            else
            {
                entries.removeIf(entry -> entry.type() == AclEntryType.DENY
                    && owner.equals(entry.principal()));
            }
            acl.setAcl(entries);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null)
        {
            Set<PosixFilePermission> permissions = new HashSet<>(posix.readAttributes().permissions());
            if (refuse)
            {
                permissions.remove(PosixFilePermission.OWNER_READ);
            }
            else
            {
                permissions.add(PosixFilePermission.OWNER_READ);
            }
            posix.setPermissions(permissions);
        }
    }

    /**
     * A tag another call assigned between this call's pre-checks and the operation's write lock is
     * reported as skipped: the answer names what the operation itself changed, so an operation
     * that changed nothing answers an empty {@code assigned}.
     */
    @Test
    public void aTagAnotherCallAssignedFirstIsSkippedNotAssigned()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        TagAdminFacadeTool racing = new TagAdminFacadeTool()
        {
            @Override
            ObjectCheck checkObject(IProject askedProject, String objectFqn)
            {
                return ObjectCheck.of(objectFqn.trim());
            }

            @Override
            MarkerWriteOutcome assignViaService(MarkerManager service, IProject project,
                String objectFqn, List<String> tags)
            {
                // The competing write lands in the window between the pre-checks and the lock:
                // the operation this call runs changes nothing of its own.
                service.assignMarkersWithOutcome(project, objectFqn, tags);
                return service.assignMarkersWithOutcome(project, objectFqn, tags);
            }
        };
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "assign_tag"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("tags", "[\"One\"]"); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(racing.execute(params)).getAsJsonObject();
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("assigned").size()); //$NON-NLS-1$
        JsonObject skip = answer.getAsJsonArray("skipped").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("One", skip.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("alreadyAssigned", skip.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // The object does carry the tag - the competing call put it there.
        assertTrue(MarkerManager.getInstance().getMarkerStorage(project)
            .getMarkerNames("Catalog.Products").contains("One")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A tag another call took off between this call's pre-checks and the operation's write lock is
     * reported as skipped: an operation that removed nothing answers the {@code notAssigned}
     * error with an empty {@code removed}, not a success naming tags it did not take off.
     */
    @Test
    public void aTagAnotherCallRemovedFirstIsSkippedNotRemoved()
    {
        assertTrue(call("create_tag", "tag", "One").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"One\"]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get("success").getAsBoolean()); //$NON-NLS-1$
        TagAdminFacadeTool racing = new TagAdminFacadeTool()
        {
            @Override
            MarkerWriteOutcome unassignViaService(MarkerManager service, IProject project,
                String objectFqn, List<String> tags)
            {
                // The competing write lands in the window between the pre-checks and the lock:
                // the operation this call runs removes nothing of its own.
                service.unassignMarkersWithOutcome(project, objectFqn, tags);
                return service.unassignMarkersWithOutcome(project, objectFqn, tags);
            }
        };
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "unassign_tag"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("tags", "[\"One\"]"); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(racing.execute(params)).getAsJsonObject();
        assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("notAssigned", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, answer.getAsJsonArray("removed").size()); //$NON-NLS-1$
        JsonObject skip = answer.getAsJsonArray("skipped").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("One", skip.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("notAssigned", skip.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A file that does not parse refuses every write with unreadableFile. */
    @Test
    public void anUnreadableFileRefusesTheWrites() throws Exception
    {
        Path yaml = markerFile();
        Files.createDirectories(yaml.getParent());
        byte[] original = CONFLICT.getBytes(StandardCharsets.UTF_8);
        Files.write(yaml, original);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        for (String[] write : new String[][] {
            {"create_tag", "tag", "New"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"update_tag", "tag", "New", "color", "#112233"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"delete_tag", "tag", "New"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"assign_tag", "objectFqn", "Catalog.Products", "tags", "[\"New\"]"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"unassign_tag", "objectFqn", "Catalog.Products", "tags", "[\"New\"]"}}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            JsonObject answer = call(write[0], Arrays.copyOfRange(write, 1, write.length));
            assertFalse(write[0], answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(write[0], "unreadableFile", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(yaml));
    }

    /** A read-only marker file refuses the write with readOnlyFile. */
    @Test
    public void aReadOnlyFileRefusesTheWrite() throws Exception
    {
        assertTrue(call("create_tag", "tag", "First").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        Path yaml = markerFile();
        yaml.toFile().setWritable(false);
        try
        {
            JsonObject answer = call("create_tag", "tag", "Second"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals("readOnlyFile", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            yaml.toFile().setWritable(true);
        }
    }

    /**
     * An edit made outside the workspace is read before the next write and carried by it, so the
     * changing party's marker survives the create that follows.
     */
    @Test
    public void anExternalEditIsCarriedByTheNextSave() throws Exception
    {
        assertTrue(call("create_tag", "tag", "First").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        Path yaml = markerFile();
        byte[] outside = Files.readAllBytes(yaml);
        String rewritten = new String(outside, StandardCharsets.UTF_8).replace("tags:", //$NON-NLS-1$
            "tags:\n- color: '#654321'\n  description: written outside\n  name: external"); //$NON-NLS-1$
        Files.write(yaml, rewritten.getBytes(StandardCharsets.UTF_8));
        JsonObject answer = call("create_tag", "tag", "Second"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.getMarkerStorage(project).getMarkerByName("external")); //$NON-NLS-1$
        assertNotNull(manager.getMarkerStorage(project).getMarkerByName("Second")); //$NON-NLS-1$
    }

    /** A project the workspace does not hold refuses with projectNotFound. */
    @Test
    public void anUnknownProjectRefuses()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "create_tag"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "NoSuchProjectAtAll"); //$NON-NLS-1$
        params.put("tag", "One"); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(facade.execute(params)).getAsJsonObject();
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("projectNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A marker service that is not there refuses with serviceUnavailable. */
    @Test
    public void aMissingServiceRefuses()
    {
        TagAdminFacadeTool headless = new TagAdminFacadeTool()
        {
            @Override
            MarkerManager markerService()
            {
                return null;
            }
        };
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "create_tag"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("tag", "One"); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(headless.execute(params)).getAsJsonObject();
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("serviceUnavailable", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The help catalog names every operation the facade dispatches. */
    @Test
    public void theHelpNamesEveryOperation()
    {
        JsonObject answer = call("help"); //$NON-NLS-1$
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        String text = answer.get("text").getAsString(); //$NON-NLS-1$
        for (String operation : new String[] {"create_tag", "update_tag", "delete_tag", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "assign_tag", "unassign_tag"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(operation, text.contains("**" + operation + "**")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** An unknown operation names the ones the facade accepts. */
    @Test
    public void anUnknownOperationIsRefused()
    {
        JsonObject answer = call("create_tags"); //$NON-NLS-1$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("create_tag")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
