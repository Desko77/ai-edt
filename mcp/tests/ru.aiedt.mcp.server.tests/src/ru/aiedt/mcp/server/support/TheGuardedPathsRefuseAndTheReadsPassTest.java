/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/**
 * Every write path asks the editability guard and refuses when the registry closed the object, and
 * every read path asks nothing.
 * <p>
 * The three write entries - the metadata write, the form write and the module write - each carry
 * their own call of the guard, so each is proven on its own: a regression that drops the call from
 * one of them leaves the other two covered. The reads - the form structure walk and the object read
 * the template operations use - must not ask at all, or a closed configuration stops being readable.
 * </p>
 * <p>
 * The support service cannot be put into either state from a test, so the answers come through the
 * guard's probe seam and the addresses resolve through its owner-resolver seam; what stays real is
 * the wiring on the way from the entry to the refusal.
 * </p>
 */
public class TheGuardedPathsRefuseAndTheReadsPassTest
{
    private static final String PROJECT = "AiEdtGuardProbe"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private static Catalog catalog;

    private static CatalogForm form;

    /** Answers as a closed object and remembers what it was asked about. */
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
    public static void aProjectWithAClosedCatalog() throws Exception
    {
        root = Files.createTempDirectory("aiedt-guard-probe"); //$NON-NLS-1$
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
        form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ФормаЭлемента"); //$NON-NLS-1$
        catalog.getForms().add(form);
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
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * Points both seams of the guard at the closed catalog, and counts what gets asked.
     */
    @BeforeClass
    public static void theGuardAnswersForTheCatalog()
    {
        ModelEditabilityGuard.useOwnerResolverForTest((askedProject, ownerFqn) -> catalog);
        ModelEditabilityGuard.useProbeForTest(PROBE);
    }

    /** Puts the real support service and the real address resolution back. */
    @AfterClass
    public static void theGuardAsksForRealAgain()
    {
        ModelEditabilityGuard.useProbeForTest(null);
        ModelEditabilityGuard.useOwnerResolverForTest(null);
    }

    /** The asks counted so far, for a test that expects its own calls and no leftovers. */
    private static int askedSoFar()
    {
        return PROBE.asked.size();
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

    // ---------- the module write path ----------

    /** write_module_source refuses, in the failure shape a client recognizes. */
    @Test
    public void theModuleWriteIsRefused()
    {
        int before = askedSoFar();
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", "CommonModules/Probe/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "// текст")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: error")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("vendor support")); //$NON-NLS-1$
        assertTrue("the structured tag travels with the refusal", //$NON-NLS-1$
            answer.contains("supportLock: \"object=CommonModule.Probe")); //$NON-NLS-1$
        assertTrue("the answer reads as a failure to a client", FailureShape.looksFailed(answer)); //$NON-NLS-1$
        assertEquals("the question was asked once, by the write path itself", 1, //$NON-NLS-1$
            askedSoFar() - before);
    }

    /** A preview of the same write is refused the same way. */
    @Test
    public void aPreviewOfTheModuleWriteIsRefusedToo()
    {
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", "CommonModules/Probe/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", //$NON-NLS-1$ //$NON-NLS-2$
            "dryRun", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "// текст")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("vendor support")); //$NON-NLS-1$
    }

    // ---------- the metadata write path ----------

    /** The metadata write refuses and carries the tag as a structured field. */
    @Test
    public void theObjectWriteIsRefused()
    {
        int before = askedSoFar();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, "Catalog.Валюты", //$NON-NLS-1$
            false, (tx, owner) -> "written"); //$NON-NLS-1$

        assertFalse(r.error, r.ok);
        assertTrue(r.error, r.error.contains("vendor support")); //$NON-NLS-1$
        Object tag = r.tags.get(ErrorTags.SUPPORT_LOCK.wire());
        assertNotNull("the refusal carries the structured tag", tag); //$NON-NLS-1$
        assertEquals("Catalog.Валюты", ((Map<?, ?>)tag).get("object")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, askedSoFar() - before);
    }

    /** The read the template operations use asks nothing and names no vendor support. */
    @Test
    public void theObjectReadAsksNothing()
    {
        int before = askedSoFar();
        BmObjectHelper.Result r = BmObjectHelper.executeReadOnObject(project, "Catalog.Валюты", //$NON-NLS-1$
            (tx, owner) -> "read"); //$NON-NLS-1$

        assertEquals("a read never asks the question", before, askedSoFar()); //$NON-NLS-1$
        assertTrue("the read went its own way, refused by no support record", //$NON-NLS-1$
            r.error == null || !r.error.contains("vendor support")); //$NON-NLS-1$
    }

    // ---------- the form write path ----------

    /** The form write refuses with the tag as a line the shared formatter reads back. */
    @Test
    public void theFormWriteIsRefused()
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue("the form model classes are on the test runtime", helper.init()); //$NON-NLS-1$

        int before = askedSoFar();
        String answer = helper.executeFormOperation(project,
            "Catalog.Валюты.Form.ФормаЭлемента", false, (tx, fm) -> "written"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("vendor support")); //$NON-NLS-1$
        Map<String, Object> tag = ModelEditabilityGuard.parseSupportLockLine(answer);
        assertNotNull(tag);
        assertEquals("the form the address spells is the object asked about", form, //$NON-NLS-1$
            PROBE.asked.get(PROBE.asked.size() - 1));
        assertEquals("Catalog.Валюты.Form.ФормаЭлемента", tag.get("object")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, askedSoFar() - before);
    }

    /** The form structure walk asks nothing, whatever else stands in its way. */
    @Test
    public void theFormReadAsksNothing()
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());

        int before = askedSoFar();
        String answer = helper.executeFormReadOperation(project,
            "Catalog.Валюты.Form.ФормаЭлемента", (tx, fm) -> "read"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a read never asks the question", before, askedSoFar()); //$NON-NLS-1$
        assertTrue("the read was refused by no support record, whatever else it met", //$NON-NLS-1$
            answer == null || !answer.contains("vendor support")); //$NON-NLS-1$
    }
}
