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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.ModelEditabilityGuard;

/**
 * The reading tools answer for an object the support registry closed.
 * <p>
 * Every write tool refuses such an object, and that refusal is proven at the entry it goes through.
 * The reading tools go through the reading entries instead, which ask nothing - so the question here
 * is asked through the tools themselves, not through the entries: a call of the form structure tool
 * and of the three reading operations of the template tool is made against an object whose every
 * answer from the probe is "closed". None of them may come back as a support refusal.
 * </p>
 * <p>
 * The support service cannot be put into that state from a test, so the answer comes through the
 * guard's probe seam; the project is a real workspace project without a 1C model, so the calls fail
 * for their own reasons - what is measured is that the reason is never the support lock. One test
 * calls the writing entry with the same setup and requires the refusal, so the four silent ones
 * cannot pass on a probe that never answers.
 * </p>
 */
public class AReadToolAnswersForAClosedObjectTest
{
    private static final String PROJECT = "AiEdtClosedReadProbe"; //$NON-NLS-1$

    private static final String OWNER = "Catalog.Валюты"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private static Catalog catalog;

    /** Answers as a closed object for everything, and remembers that it was asked. */
    private static final class ClosedObjectProbe implements ModelEditabilityGuard.Probe
    {
        final List<MdObject> asked = new ArrayList<>();

        @Override
        public ModelEditabilityGuard.Editability ask(IProject askedProject, MdObject judged)
        {
            asked.add(judged);
            ModelEditabilityGuard.Editability answer = new ModelEditabilityGuard.Editability();
            answer.answered = true;
            answer.userMode = "ChangesNotAllowed"; //$NON-NLS-1$
            answer.canEdit = false;
            answer.route = "test"; //$NON-NLS-1$
            return answer;
        }
    }

    private static final ClosedObjectProbe PROBE = new ClosedObjectProbe();

    @BeforeClass
    public static void aProjectOfItsOwn() throws Exception
    {
        root = Files.createTempDirectory("aiedt-closed-read-probe"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Валюты"); //$NON-NLS-1$

        ModelEditabilityGuard.useOwnerResolverForTest((askedProject, ownerFqn) -> catalog);
        ModelEditabilityGuard.useProbeForTest(PROBE);
    }

    @AfterClass
    public static void theGuardAsksForRealAgain() throws Exception
    {
        ModelEditabilityGuard.useProbeForTest(null);
        ModelEditabilityGuard.useOwnerResolverForTest(null);
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * Asserts that an answer of a reading tool is not a support refusal: the registry's own refusal
     * carries the tag and the words from the live guard, and the tool asked the guard about nothing.
     * <p>
     * The project carries no 1C model, so every call fails - the last assertion pins down where:
     * the service the entry needs is missing, and that is read past the question the entry would
     * have asked had it been the writing one. Without it a call that stopped at its own argument
     * check would satisfy the rest of this method while proving nothing about the guard.
     * </p>
     *
     * @param operation the operation under test, for the failure message
     * @param answer the answer the tool produced
     * @param askedBefore the number of asks counted before the call
     */
    private void assertNoSupportRefusal(String operation, String answer, int askedBefore)
    {
        assertFalse(operation + " must not be refused by the support registry: " + answer, //$NON-NLS-1$
            answer.contains("supportLock")); //$NON-NLS-1$
        assertFalse(operation + " must not be refused by the support registry: " + answer, //$NON-NLS-1$
            answer.contains("vendor support")); //$NON-NLS-1$
        assertEquals(operation + " asks the support question about nothing", askedBefore, //$NON-NLS-1$
            PROBE.asked.size());
        assertTrue(operation + " failed at the model it could not reach: " + answer, //$NON-NLS-1$
            answer.contains("not published as a service")); //$NON-NLS-1$
    }

    /**
     * The control for the four calls below: the same probe and the same address do produce the
     * registry's refusal the moment a call goes through a writing entry.
     */
    @Test
    public void aWriteThroughTheSameAddressIsRefused()
    {
        int before = PROBE.asked.size();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, OWNER, false,
            (tx, owner) -> "written"); //$NON-NLS-1$

        assertFalse(r.error, r.ok);
        assertTrue(r.error, r.error.contains("vendor support")); //$NON-NLS-1$
        assertEquals("the writing entry asks the question", before + 1, PROBE.asked.size()); //$NON-NLS-1$
    }

    /** The form structure walk serves a form of a closed object. */
    @Test
    public void theFormStructureToolAnswers()
    {
        int before = PROBE.asked.size();
        String answer = new GetFormStructureTool().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "formPath", OWNER + ".Form.ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNoSupportRefusal("get_form_structure", answer, before); //$NON-NLS-1$
    }

    /** Listing the named areas of a template reads a closed object. */
    @Test
    public void theNamedAreasAreListed()
    {
        int before = PROBE.asked.size();
        String answer = new MxlWorkshopTool().execute(templateArgs("list_named_areas")); //$NON-NLS-1$

        assertNoSupportRefusal("list_named_areas", answer, before); //$NON-NLS-1$
    }

    /** Reading a template back reads a closed object. */
    @Test
    public void theTemplateIsReadBack()
    {
        int before = PROBE.asked.size();
        String answer = new MxlWorkshopTool().execute(templateArgs("read_template")); //$NON-NLS-1$

        assertNoSupportRefusal("read_template", answer, before); //$NON-NLS-1$
    }

    /** Measuring the print width reads a closed object. */
    @Test
    public void thePrintWidthIsMeasured()
    {
        int before = PROBE.asked.size();
        String answer = new MxlWorkshopTool().execute(templateArgs("check_print_width")); //$NON-NLS-1$

        assertNoSupportRefusal("check_print_width", answer, before); //$NON-NLS-1$
    }

    private static Map<String, String> templateArgs(String operation)
    {
        return args(
            "operation", operation, //$NON-NLS-1$
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "ownerFqn", OWNER, //$NON-NLS-1$ //$NON-NLS-2$
            "templateName", "Макет1"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
