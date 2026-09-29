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

import ru.aiedt.mcp.server.support.ModelEditabilityGuard;

/**
 * Listing the conditional-appearance rules of a form reads, and the write of the same facade is
 * still the one that asks the support registry.
 * <p>
 * The three operations of this facade go through one helper: the listing takes the reading entry,
 * which asks nothing, and the rule add and remove take the writing entry, which asks the registry
 * and refuses a closed object. Nothing on the reading side would fail loudly if the listing were
 * moved back to the writing entry - a closed configuration would simply stop answering - so the
 * listing is measured here through the guard's probe seam: it is not refused, it asks about
 * nothing, and it reaches the model lookup the reading entry passes through.
 * </p>
 * <p>
 * The removal of a rule through the same address is the control: it asks the question once and is
 * refused, so a probe that never answers cannot make the listing look clean.
 * </p>
 */
public class TheAppearanceListGoesThroughTheReadEntryTest
{
    private static final String PROJECT = "AiEdtAppearanceRouteProbe"; //$NON-NLS-1$

    private static final String FORM_FQN = "Catalog.Валюты.Form.ФормаЭлемента"; //$NON-NLS-1$

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

    /**
     * Opens a workspace project without a 1C model, and points the guard at the closed catalog.
     *
     * @throws Exception when the project cannot be created
     */
    @BeforeClass
    public static void aProjectOfItsOwn() throws Exception
    {
        root = Files.createTempDirectory("aiedt-appearance-route"); //$NON-NLS-1$
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

    /**
     * Puts the real support service back and deletes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
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

    /**
     * The arguments of a facade call.
     *
     * @param pairs alternating names and values
     * @return the argument map
     */
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
     * The listing is not refused and asks the support question about nothing.
     * <p>
     * The project carries no 1C model, so the call stops at the model lookup - which is past the
     * question the reading entry does not ask and exactly where the writing entry would have
     * refused. Without that the call could satisfy the rest of this test by stopping at an argument
     * check and prove nothing about the entry it took.
     * </p>
     */
    @Test
    public void theRulesAreListedWithoutAskingTheWriteQuestion()
    {
        int before = PROBE.asked.size();
        String answer = new FormAppearanceOps().opListFormAppearanceRules(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "formFqn", FORM_FQN)); //$NON-NLS-1$

        assertFalse("a listing is a read and must not be refused by the registry: " + answer, //$NON-NLS-1$
            answer.contains("vendor support")); //$NON-NLS-1$
        assertFalse("nor carry the registry's tag: " + answer, answer.contains("supportLock")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the reading entry asks the support question about nothing", before, //$NON-NLS-1$
            PROBE.asked.size());
        assertTrue("the call reached the model lookup the reading entry passes through: " + answer, //$NON-NLS-1$
            answer.contains("not published as a service")); //$NON-NLS-1$
    }

    /**
     * The control: removing a rule through the same address goes through the writing entry, which
     * asks the question and refuses the closed object.
     */
    @Test
    public void aRuleRemovalThroughTheSameAddressIsRefused()
    {
        int before = PROBE.asked.size();
        String answer = new FormAppearanceOps().opRemoveFormAppearanceRule(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "formFqn", FORM_FQN, //$NON-NLS-1$
            "index", "0")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the writing entry is reached and refuses the closed object: " + answer, //$NON-NLS-1$
            answer.contains("vendor support")); //$NON-NLS-1$
        assertEquals("the writing entry asks the question", before + 1, PROBE.asked.size()); //$NON-NLS-1$
    }
}
