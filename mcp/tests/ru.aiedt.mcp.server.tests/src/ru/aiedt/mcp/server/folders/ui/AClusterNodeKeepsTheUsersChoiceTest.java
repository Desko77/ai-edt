/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * The cluster navigator undoes only the selection a rebuilding tree slips into - nothing, or the
 * project node - and a cluster node offers to expand only when it has something to show.
 */
public class AClusterNodeKeepsTheUsersChoiceTest
{
    private static final IProject PROJECT =
        ResourcesPlugin.getWorkspace().getRoot().getProject("AiEdtClusterNavProbe"); //$NON-NLS-1$

    private static final ISelectionProvider SOURCE = new ISelectionProvider()
    {
        @Override
        public void addSelectionChangedListener(ISelectionChangedListener listener)
        {
            // Events are made by hand here.
        }

        @Override
        public ISelection getSelection()
        {
            return StructuredSelection.EMPTY;
        }

        @Override
        public void removeSelectionChangedListener(ISelectionChangedListener listener)
        {
            // Events are made by hand here.
        }

        @Override
        public void setSelection(ISelection selection)
        {
            // Events are made by hand here.
        }
    };

    /**
     * The selection helper with the re-selection counted instead of made.
     */
    private static final class Counting extends NavigatorClusterSelection
    {
        private int restores;

        Counting()
        {
            super(null);
        }

        @Override
        void restoreSelection(EObject eObject)
        {
            restores++;
        }
    }

    /**
     * A cluster node whose names resolve as a script says.
     */
    private static final class Scripted extends ClusterNavigatorBridge
    {
        private final String resolvable;

        /**
         * @param cluster the cluster
         * @param resolvable the one name that resolves, or {@code null} for none
         */
        Scripted(Cluster cluster, String resolvable)
        {
            super(cluster, PROJECT, null);
            this.resolvable = resolvable;
        }

        @Override
        EObject resolveFqnToEObject(String fqn)
        {
            return fqn.equals(resolvable) ? EcoreFactory.eINSTANCE.createEObject() : null;
        }

        @Override
        boolean anyResolves(List<String> names)
        {
            return names.contains(resolvable);
        }
    }

    /**
     * Opens the project the cluster nodes belong to.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aProject() throws Exception
    {
        if (!PROJECT.exists())
        {
            PROJECT.create(new NullProgressMonitor());
        }
        PROJECT.open(new NullProgressMonitor());
    }

    /**
     * Removes the project.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (PROJECT.exists())
        {
            PROJECT.delete(true, true, new NullProgressMonitor());
        }
    }

    /**
     * @param elements what is selected
     * @return the event of that selection
     */
    private static SelectionChangedEvent selecting(Object... elements)
    {
        return new SelectionChangedEvent(SOURCE, new StructuredSelection(Arrays.asList(elements)));
    }

    /**
     * @return a cluster holding two names
     */
    private static Cluster aClusterOfTwoNames()
    {
        Cluster cluster = new Cluster("Sales", ""); //$NON-NLS-1$ //$NON-NLS-2$
        cluster.addChild("Catalog.Goods"); //$NON-NLS-1$
        cluster.addChild("Document.Order"); //$NON-NLS-1$
        return cluster;
    }

    /** A cluster node chosen right after an object stays chosen. */
    @Test
    public void aClusterChosenRightAfterAnObjectStays()
    {
        Counting helper = new Counting();
        helper.selectionChanged(selecting(EcoreFactory.eINSTANCE.createEObject()));
        helper.selectionChanged(selecting(new ClusterNavigatorBridge(new Cluster("Sales", ""), PROJECT, null))); //$NON-NLS-1$ //$NON-NLS-2$
        helper.selectionChanged(selecting("a collection folder")); //$NON-NLS-1$
        assertEquals(0, helper.restores);
    }

    /** A choice the user made after the object ends the window: the project node is kept. */
    @Test
    public void aChoiceAfterTheObjectEndsTheWindow()
    {
        Counting helper = new Counting();
        helper.selectionChanged(selecting(EcoreFactory.eINSTANCE.createEObject()));
        helper.selectionChanged(selecting(new ClusterNavigatorBridge(new Cluster("Sales", ""), PROJECT, null))); //$NON-NLS-1$ //$NON-NLS-2$
        helper.selectionChanged(selecting(PROJECT));
        assertEquals(0, helper.restores);
    }

    /** Nothing selected, or the project node, right after an object is the slip that is undone. */
    @Test
    public void theProjectNodeOrNothingRightAfterAnObjectIsUndone()
    {
        Counting helper = new Counting();
        helper.selectionChanged(selecting(EcoreFactory.eINSTANCE.createEObject()));
        helper.selectionChanged(new SelectionChangedEvent(SOURCE, StructuredSelection.EMPTY));
        helper.selectionChanged(selecting(PROJECT));
        assertEquals(2, helper.restores);
    }

    /** A cluster whose names all fail to resolve offers nothing to expand. */
    @Test
    public void aClusterOfStaleNamesHasNoChildren()
    {
        Scripted node = new Scripted(aClusterOfTwoNames(), null);
        assertFalse(new ClusterTreeContent().hasChildren(node));
    }

    /** A cluster with one name that resolves offers it, and shows it when expanded. */
    @Test
    public void aClusterWithOneLiveNameHasChildren()
    {
        Scripted node = new Scripted(aClusterOfTwoNames(), "Document.Order"); //$NON-NLS-1$
        assertTrue(new ClusterTreeContent().hasChildren(node));
        assertTrue(node.getChildren(node).length > 0);
    }
}
