/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A write that crashes after the mutation has run leaves no removal report on the thread.
 * <p>
 * The crash happens before the call has a result to attach the report to, so nothing but the
 * cleanup on the way out can clear it. Whatever survives lands in the next call the same thread
 * serves, which is the defect this guards against.
 * </p>
 */
public class ACrashedWriteLeavesNoImpactBehindTest
{
    private static final String PROJECT = "AiEdtDcsImpactCrashProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    private EObject schema;

    /**
     * Opens a plain project, whose name alone the routed call has to resolve.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void openAPlainProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-dcs-impact-crash"); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Removes the project and the directory under it.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        deleteTree(projectDir);
    }

    /**
     * Deletes a directory tree.
     *
     * @param root the directory, possibly null
     * @throws IOException when a walk cannot be opened
     */
    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                .forEach(File::delete);
        }
    }

    /**
     * Builds an empty composition schema, on a thread carrying no report from an earlier test.
     */
    @Before
    public void buildASchema()
    {
        // Reading and dropping whatever a previous test on this thread left behind keeps the
        // assertion below about the crash alone.
        DcsWorkshopTool.attachRemovalImpact(null);
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
    }

    /**
     * A tool whose schema write crashes once the mutation, and with it the report, has run.
     *
     * @return the tool to route the failing call through
     */
    private DcsWorkshopTool toolThatCrashesAfterTheMutation()
    {
        return new DcsWorkshopTool()
        {
            @Override
            BmDcsHelper.Result runSchemaWrite(IProject p, String objectName, String templateName,
                boolean dryRun, BmDcsHelper.DcsAction action)
            {
                try
                {
                    action.execute(null, schema);
                }
                catch (Exception failure)
                {
                    throw new RuntimeException("the write crashed on the mutation itself", failure); //$NON-NLS-1$
                }
                throw new RuntimeException("the write crashed after the mutation ran"); //$NON-NLS-1$
            }
        };
    }

    /**
     * Runs one schema operation against the schema object.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return what the operation reports
     * @throws Exception if the operation refuses
     */
    private Object run(String op, String... keysAndValues) throws Exception
    {
        return new DcsWorkshopTool().applyToSchemaForTest(op, args(keysAndValues), schema);
    }

    /**
     * Pairs argument names with values.
     *
     * @param keysAndValues argument names and values, alternating
     * @return the arguments
     */
    private static Map<String, String> args(String... keysAndValues)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }

    /**
     * The report the crashed call built is gone once the call is gone: the next reader on the
     * same thread, before any new call of its own has run, reads nothing.
     *
     * @throws Exception if a setup call refuses
     */
    @Test
    public void aWriteThatCrashesAfterTheMutationLeavesNoReportBehind() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            toolThatCrashesAfterTheMutation().opSchemaMutation("remove_dataset", //$NON-NLS-1$
                args("projectName", PROJECT, "objectName", "Report.Продажи", "name", "Продажи")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("a crashed write must surface as an exception"); //$NON-NLS-1$
        }
        catch (RuntimeException crashed)
        {
            assertEquals("the write crashed after the mutation ran", crashed.getMessage()); //$NON-NLS-1$
        }

        // The read and the drop in one step, exactly as the answer building reads it: whatever
        // lands in the tags here is what the crashed call left on this thread.
        Map<String, Object> tagsOfTheNextReader = new LinkedHashMap<>();
        DcsWorkshopTool.attachRemovalImpact(tagsOfTheNextReader);
        assertNull("the crashed call left a report on this thread", //$NON-NLS-1$
            tagsOfTheNextReader.get("affectedSettings")); //$NON-NLS-1$
    }
}
