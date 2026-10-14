/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;

import ru.aiedt.mcp.server.support.BmReferencesHelper;

/**
 * The mixed level joins a scheduled job and an event subscription to the common module their
 * method reference names, with the method as the {@code via} and a model resolution.
 * <p>
 * The reference walk cannot give these rows: a job's method name and a subscription's handler are
 * plain strings in the metadata. The jobs and subscriptions are built with the metadata factory,
 * the way an in-memory configuration is built elsewhere in the suite.
 * </p>
 */
public class AMixedLevelJoinsJobsAndSubscriptionsToTheirModulesTest
{
    private static final String JOB = "ScheduledJob.НочнойРасчет"; //$NON-NLS-1$
    private static final String SUBSCRIPTION = "EventSubscription.ЗаписьТовара"; //$NON-NLS-1$
    private static final String MODULE = "CommonModule.Подготовка.Module"; //$NON-NLS-1$

    /** A job and a subscription in the graph each give a row to the module their method names. */
    @Test
    public void aJobAndASubscriptionGiveARowEach()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        EventSubscription subscription = MdClassFactory.eINSTANCE.createEventSubscription();
        subscription.setHandler("CommonModule.Подготовка.ПослеЗаписи"); //$NON-NLS-1$
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put(JOB, asGraphObject(job));
        result.nodes.put(SUBSCRIPTION, asGraphObject(subscription));

        int added = DependencyGraphTool.addJobAndSubscriptionEdges(result, configuration(), 10,
            100, null);

        assertEquals(2, added);
        BmReferencesHelper.Edge jobEdge = edge(result, JOB);
        assertEquals(MODULE, jobEdge.toFqn);
        assertEquals("РассчитатьВсе", jobEdge.featureName); //$NON-NLS-1$
        assertEquals(DependencyGraphTool.RESOLUTION_MODEL, jobEdge.resolution);
        BmReferencesHelper.Edge subscriptionEdge = edge(result, SUBSCRIPTION);
        assertEquals(MODULE, subscriptionEdge.toFqn);
        assertEquals("ПослеЗаписи", subscriptionEdge.featureName); //$NON-NLS-1$
        assertTrue("the module is a node the answer lists", result.nodes.containsKey(MODULE)); //$NON-NLS-1$
    }

    /** A method reference naming no common module of the project gives no row. */
    @Test
    public void aReferenceNamingNoModuleGivesNoRow()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("НесуществующийМодуль.РассчитатьВсе"); //$NON-NLS-1$
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put(JOB, asGraphObject(job));

        int added = DependencyGraphTool.addJobAndSubscriptionEdges(result, configuration(), 10,
            100, null);

        assertEquals(0, added);
        assertNull(edge(result, JOB));
        assertEquals(1, result.nodes.size());
    }

    /**
     * A job row passes the kind filter like a row of the walk: a kind the caller did not name is
     * dropped and counted, and its target is no node.
     */
    @Test
    public void aJobRowPassesTheKindFilter()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put(JOB, asGraphObject(job));

        int added = DependencyGraphTool.addJobAndSubscriptionEdges(result, configuration(), 10,
            100, java.util.Set.of("attributes")); //$NON-NLS-1$

        assertEquals(0, added);
        assertTrue(result.edges.isEmpty());
        assertTrue("the refused kind is seen", result.kindsSeen.contains("РассчитатьВсе")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.Map.of("РассчитатьВсе", Integer.valueOf(1)), //$NON-NLS-1$
            result.edgesDroppedByKind);
        assertFalse("a dropped row adds no node", result.nodes.containsKey(MODULE)); //$NON-NLS-1$
    }

    /** A kind the caller named keeps the job row. */
    @Test
    public void aNamedKindKeepsTheJobRow()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put(JOB, asGraphObject(job));

        int added = DependencyGraphTool.addJobAndSubscriptionEdges(result, configuration(), 10,
            100, java.util.Set.of("РассчитатьВсе")); //$NON-NLS-1$

        assertEquals(1, added);
        assertEquals(MODULE, edge(result, JOB).toFqn);
    }

    /** An empty method reference gives no row. */
    @Test
    public void anEmptyReferenceGivesNoRow()
    {
        EventSubscription subscription = MdClassFactory.eINSTANCE.createEventSubscription();
        subscription.setHandler(""); //$NON-NLS-1$
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put(SUBSCRIPTION, asGraphObject(subscription));

        assertEquals(0, DependencyGraphTool.addJobAndSubscriptionEdges(result, configuration(),
            10, 100, null));
    }

    /** The configuration of the project, holding one common module. */
    private static Configuration configuration()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Подготовка"); //$NON-NLS-1$
        configuration.getCommonModules().add(module);
        return configuration;
    }

    /**
     * The edge of a node, when the graph holds exactly one.
     *
     * @param result the graph
     * @param fromFqn the node's FQN
     * @return its edge, or {@code null} when the graph holds none
     */
    private static BmReferencesHelper.Edge edge(BmReferencesHelper.BfsResult result,
        String fromFqn)
    {
        for (BmReferencesHelper.Edge edge : result.edges)
        {
            if (fromFqn.equals(edge.fromFqn))
            {
                return edge;
            }
        }
        return null;
    }

    /**
     * A metadata object in the type the graph holds its nodes in. The metadata objects the runtime
     * builds answer that type themselves; the double cast says so to the compiler.
     *
     * @param object the metadata object
     * @return the same object, as the graph holds it
     */
    private static IBmObject asGraphObject(EObject object)
    {
        return (IBmObject)(Object)object;
    }
}
