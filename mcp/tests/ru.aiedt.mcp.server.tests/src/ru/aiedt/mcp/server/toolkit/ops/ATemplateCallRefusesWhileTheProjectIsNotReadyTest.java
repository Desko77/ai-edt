/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * A template write is refused while the project is not ready, and the file is left as it is.
 * <p>
 * The project opened here is an ordinary workspace project. {@code set_cell} and
 * {@code create_template} are answered by the readiness guard. {@code read_template} continues
 * to the model entry: a read stops on a building state, and this project is not building.
 * </p>
 */
public class ATemplateCallRefusesWhileTheProjectIsNotReadyTest
{
    private static final String PROJECT = "AiEdtMxlReadinessGate"; //$NON-NLS-1$

    private static final String KEPT = "<document>kept-bytes</document>\n"; //$NON-NLS-1$

    private static final String[] READINESS = {
        "Not an EDT project", //$NON-NLS-1$
        "DtProjectManager cannot be reached", //$NON-NLS-1$
        "Project is still building", //$NON-NLS-1$
        "Project build is still running", //$NON-NLS-1$
        "Build state cannot be determined", //$NON-NLS-1$
        "The project is closed", //$NON-NLS-1$
        "Project handle is null" //$NON-NLS-1$
    };

    private static Path root;

    private static IProject project;

    private static Path catalogFile;

    private static Path commonFile;

    @BeforeClass
    public static void anOrdinaryProjectWithTemplateFiles() throws Exception
    {
        root = Files.createTempDirectory("aiedt-mxl-readiness"); //$NON-NLS-1$
        catalogFile = root.resolve("src/Catalogs/Goods/Templates/Print/Template.mxlx"); //$NON-NLS-1$
        commonFile = root.resolve("src/CommonTemplates/Print/Template.mxlx"); //$NON-NLS-1$
        Files.createDirectories(catalogFile.getParent());
        Files.createDirectories(commonFile.getParent());
        Files.writeString(catalogFile, KEPT, StandardCharsets.UTF_8);
        Files.writeString(commonFile, KEPT, StandardCharsets.UTF_8);
        project = openProject(PROJECT, root);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (Stream<Path> entries = Files.walk(root))
            {
                entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    public void setCellReadAndCreateAreRefusedAndTheFilesStay() throws Exception
    {
        byte[] catalogBefore = Files.readAllBytes(catalogFile);
        byte[] commonBefore = Files.readAllBytes(commonFile);

        String setCell = new MxlWorkshopTool().execute(call("set_cell", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        String read = new MxlWorkshopTool().execute(call("read_template", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        String create = new MxlWorkshopTool().execute(call("create_template", "CommonTemplate.Print")); //$NON-NLS-1$ //$NON-NLS-2$

        assertReadiness(setCell);
        assertReadiness(create);
        assertFalse(read, readContainsReadiness(read));
        assertFalse(read, read.contains("Owner not found")); //$NON-NLS-1$
        assertTrue(Arrays.equals(catalogBefore, Files.readAllBytes(catalogFile)));
        assertTrue(Arrays.equals(commonBefore, Files.readAllBytes(commonFile)));
    }

    @Test
    public void aReadStopsOnlyWhileTheBuildIsUnsettled()
    {
        ProjectStateGuard.ProjectStateResult building = state(ProjectStateGuard.ProjectState.BUILDING,
            "Project is still building: derived data is still being computed"); //$NON-NLS-1$
        ProjectStateGuard.ProjectStateResult buildUnknown = state(ProjectStateGuard.ProjectState.UNKNOWN,
            "Build state cannot be determined"); //$NON-NLS-1$
        ProjectStateGuard.ProjectStateResult managerMissing = state(ProjectStateGuard.ProjectState.UNKNOWN,
            "DtProjectManager cannot be reached"); //$NON-NLS-1$
        ProjectStateGuard.ProjectStateResult notEdt = state(ProjectStateGuard.ProjectState.NOT_AVAILABLE,
            "Not an EDT project (the EDT nature is missing)"); //$NON-NLS-1$
        ProjectStateGuard.ProjectStateResult closed = state(ProjectStateGuard.ProjectState.NOT_AVAILABLE,
            "The project is closed"); //$NON-NLS-1$
        ProjectStateGuard.ProjectStateResult ready = state(ProjectStateGuard.ProjectState.READY,
            "Project is ready for calls"); //$NON-NLS-1$

        assertTrue(MxlWorkshopTool.readinessBlocks("read_template", building)); //$NON-NLS-1$
        assertTrue(MxlWorkshopTool.readinessBlocks("list_named_areas", buildUnknown)); //$NON-NLS-1$
        assertTrue(MxlWorkshopTool.readinessBlocks("check_print_width", closed)); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.readinessBlocks("read_template", managerMissing)); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.readinessBlocks("read_template", notEdt)); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.readinessBlocks("read_template", ready)); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.readinessBlocks("read_template", null)); //$NON-NLS-1$

        assertTrue(MxlWorkshopTool.readinessBlocks("set_cell", managerMissing)); //$NON-NLS-1$
        assertTrue(MxlWorkshopTool.readinessBlocks("create_template", notEdt)); //$NON-NLS-1$
        assertTrue(MxlWorkshopTool.readinessBlocks("set_cell", building)); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.readinessBlocks("set_cell", ready)); //$NON-NLS-1$
    }

    private static void assertReadiness(String answer)
    {
        assertTrue(answer, readContainsReadiness(answer));
        assertFalse(answer, answer.contains("BM model not available")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("Owner not found")); //$NON-NLS-1$
    }

    private static boolean readContainsReadiness(String answer)
    {
        for (String phrase : READINESS)
        {
            if (answer.contains(phrase))
            {
                return true;
            }
        }
        return false;
    }

    private static ProjectStateGuard.ProjectStateResult state(ProjectStateGuard.ProjectState kind, String message)
    {
        return new ProjectStateGuard.ProjectStateResult(kind, message);
    }

    private static Map<String, String> call(String operation, String ownerFqn)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", operation); //$NON-NLS-1$
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("text", "x"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        if (opened.exists())
        {
            opened.delete(false, true, new NullProgressMonitor());
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }
}
