/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * An address that ends at a nested subsystem is judged by that subsystem, not by the one above it.
 * <p>
 * Subsystems below the root are held by reference rather than by containment, so the walk the guard
 * takes through the contents of an object does not see them: the address
 * {@code Subsystem.Продажи.Subsystem.Розница} would stop at {@code Продажи} and the nested subsystem's
 * own support record would never be asked. The answer comes through the guard's probe seam and the
 * owner resolves through its owner-resolver seam, so what is proven here is the walk of the address
 * itself.
 * </p>
 */
public class ANestedSubsystemIsJudgedByItsOwnRecordTest
{
    private static final String OUTER_ADDRESS = "Subsystem.Продажи"; //$NON-NLS-1$

    private static final String INNER_ADDRESS = "Subsystem.Продажи.Subsystem.Розница"; //$NON-NLS-1$

    private static Subsystem outer;

    private static Subsystem inner;

    /**
     * Answers as a closed object for the nested subsystem and as an open one for every other object,
     * and remembers what it was asked about.
     */
    private static final class PerObjectProbe implements ModelEditabilityGuard.Probe
    {
        final List<MdObject> asked = new ArrayList<>();

        @Override
        public ModelEditabilityGuard.Editability ask(IProject askedProject, MdObject judged)
        {
            asked.add(judged);
            boolean closed = judged == inner;
            ModelEditabilityGuard.Editability answer = new ModelEditabilityGuard.Editability();
            answer.answered = true;
            answer.canEdit = !closed;
            answer.userMode = closed ? "ChangesNotAllowed" : "ChangesAllowed"; //$NON-NLS-1$ //$NON-NLS-2$
            answer.route = "test"; //$NON-NLS-1$
            return answer;
        }
    }

    private static final PerObjectProbe PROBE = new PerObjectProbe();

    @BeforeClass
    public static void aSubsystemInsideAnother()
    {
        outer = MdClassFactory.eINSTANCE.createSubsystem();
        outer.setName("Продажи"); //$NON-NLS-1$
        inner = MdClassFactory.eINSTANCE.createSubsystem();
        inner.setName("Розница"); //$NON-NLS-1$
        outer.getSubsystems().add(inner);

        ModelEditabilityGuard.useOwnerResolverForTest(
            (project, ownerFqn) -> OUTER_ADDRESS.equals(ownerFqn) ? outer : null);
        ModelEditabilityGuard.useProbeForTest(PROBE);
    }

    @AfterClass
    public static void theGuardAsksForRealAgain()
    {
        ModelEditabilityGuard.useProbeForTest(null);
        ModelEditabilityGuard.useOwnerResolverForTest(null);
    }

    /**
     * A nested subsystem is not among the contents of the subsystem above it, so the walk has to
     * reach it the way the write path does - and the refusal names the object the address ends at.
     */
    @Test
    public void theNestedSubsystemIsTheOneJudged()
    {
        PROBE.asked.clear();

        MetadataGuards.Verdict verdict = ModelEditabilityGuard.checkFqn(null, INNER_ADDRESS);

        assertEquals("the address is judged by the one object it names", 1, PROBE.asked.size()); //$NON-NLS-1$
        assertEquals("the nested subsystem is the object asked about", inner, PROBE.asked.get(0)); //$NON-NLS-1$
        assertTrue("its record closes it, so the write is refused", verdict.blocked); //$NON-NLS-1$
        assertTrue(verdict.error, verdict.error.contains(INNER_ADDRESS)); //$NON-NLS-1$
    }

    /** The subsystem the address names without a child is judged by its own record, as before. */
    @Test
    public void theSubsystemTheAddressNamesIsJudgedByItsOwnRecord()
    {
        PROBE.asked.clear();

        MetadataGuards.Verdict verdict = ModelEditabilityGuard.checkFqn(null, OUTER_ADDRESS);

        assertEquals(1, PROBE.asked.size());
        assertEquals(outer, PROBE.asked.get(0));
        assertFalse("its record leaves it open, so nothing is refused", verdict.blocked); //$NON-NLS-1$
    }
}
