/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.ImportOperations;
import ru.aiedt.mcp.oform.ops.OperationSpec;

/**
 * The import catalog's registered creation handler at work on the fake-project setup: a form the
 * owner's model does not list refuses before the export - the spy road is never called, the
 * project's container keeps its bytes - and the refusal names the operation that creates the
 * form. The same handler names the missing road when the plugin's service is not held. The
 * handler answers the tool's own JSON.
 */
public class TheImportOperationsCreationHandlerTest
{
    private static final String PROJECT = "AiEdtOformImportHandlerProbe"; //$NON-NLS-1$

    private static final String FQN = "Catalog.Banks.Form.ItemForm"; //$NON-NLS-1$

    private static final String CONTAINER = "Catalogs/Banks/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String LAYOUT = "{27,0,0,{3,\"Проц1\",0}}" + CRLF; //$NON-NLS-1$

    private static final String MODULE = "Процедура Проц1()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static Path root;

    private static IProject project;

    private static byte[] containerBefore;

    private final ToolRoadHolder holder = new ToolRoadHolder();

    private ru.aiedt.mcp.server.toolkit.IToolRoad roadBefore;

    /** A workspace project over a temporary directory, holding the form's container. */
    @BeforeClass
    public static void aProjectWithTheFormsContainer() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-import-handler"); //$NON-NLS-1$
        Path form = root.resolve(PROJECT).resolve("src/Catalogs/Banks/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of("form", withBom(LAYOUT))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of("module", withBom(MODULE))); //$NON-NLS-1$
        Files.write(form.resolve("Form.oform"), v8.write()); //$NON-NLS-1$
        containerBefore = Files.readAllBytes(form.resolve("Form.oform")); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(root.resolve(PROJECT).toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /** The project and its temporary directory go. */
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

    /** Remembers the road the runtime holds, so a test can give it back whatever it did. */
    @Before
    public void theRoadTheRuntimeHolds()
    {
        roadBefore = ToolRoadHolder.road();
    }

    /** Gives back the road the runtime held: a held one is bound again, a held-none is cleared. */
    @After
    public void theRoadGoesBack()
    {
        if (roadBefore != null)
        {
            holder.bind(roadBefore);
            return;
        }
        ru.aiedt.mcp.server.toolkit.IToolRoad current = ToolRoadHolder.road();
        if (current != null)
        {
            holder.unbind(current);
        }
    }

    /** The catalog declares the import as a creation that writes and needs a project. */
    @Test
    public void theCatalogDeclaresTheImport()
    {
        List<OperationSpec> specs = ImportOperations.specs();
        assertEquals(1, specs.size());
        OperationSpec spec = specs.get(0);
        assertEquals("import_from_infobase", spec.name); //$NON-NLS-1$
        assertEquals(OperationSpec.Kind.CREATION, spec.kind);
        assertTrue(spec.writes);
        assertTrue(spec.needsProject);
        List<String> params = spec.params.stream().map(param -> param.name).toList();
        assertTrue(params.contains("module")); //$NON-NLS-1$
        assertTrue(params.contains("applicationId")); //$NON-NLS-1$
    }

    /**
     * A dry run through the registered handler on the plain project refuses before the export:
     * the owner's model lists no form of a project with no configuration, the spy road is never
     * called, the project's container keeps its bytes, and the refusal names the operation that
     * creates the form.
     */
    @Test
    public void aDryRunRefusesBeforeTheExportAndWritesNothing() throws IOException
    {
        AnOrdinaryFormIsPulledFromTheInfobaseTest.FakeRoad road = AnOrdinaryFormIsPulledFromTheInfobaseTest.FakeRoad
            .thatExports(containerBefore);
        holder.bind(road);
        assertSame("the spy road is the one the bundle holds", road, ToolRoadHolder.road()); //$NON-NLS-1$

        String answer = theHandlerAnswers(Map.of("formFqn", FQN, "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(answer, error.contains("import_from_infobase writes into a form the project already has")); //$NON-NLS-1$
        assertTrue(answer, error.contains("create_form")); //$NON-NLS-1$
        assertEquals(FQN, json.get("formFqn").getAsString()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, json.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the road was never called", List.of(), road.calls); //$NON-NLS-1$
        assertTrue("the container keeps its bytes", //$NON-NLS-1$
            java.util.Arrays.equals(containerBefore, theContainerBytes()));
    }

    /** With no road held the handler names the missing service and writes nothing. */
    @Test
    public void withoutARoadTheHandlerNamesTheMissingService() throws IOException
    {
        ru.aiedt.mcp.server.toolkit.IToolRoad current = ToolRoadHolder.road();
        if (current != null)
        {
            holder.unbind(current);
        }

        String answer = theHandlerAnswers(Map.of("formFqn", FQN)); //$NON-NLS-1$

        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, json.get("error").getAsString().contains("tool road service is not available")); //$NON-NLS-1$
        assertTrue("the container keeps its bytes", //$NON-NLS-1$
            java.util.Arrays.equals(containerBefore, theContainerBytes()));
    }

    /**
     * @param params the tool's arguments
     * @return the answer of the registered creation handler of the import catalog
     */
    private static String theHandlerAnswers(Map<String, String> params)
    {
        OperationSpec spec = ImportOperations.specs().get(0);
        return spec.creation.run(project, params);
    }

    /** @return the bytes of the project's container as they stand now */
    private static byte[] theContainerBytes() throws IOException
    {
        return Files.readAllBytes(root.resolve(PROJECT).resolve("src").resolve(CONTAINER)); //$NON-NLS-1$
    }

    /**
     * @param text the entry's text
     * @return its bytes with the byte order mark the platform's entries carry
     */
    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
