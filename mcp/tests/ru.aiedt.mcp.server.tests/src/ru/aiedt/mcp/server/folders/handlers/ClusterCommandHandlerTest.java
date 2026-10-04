/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;

import org.eclipse.core.resources.IProject;

import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;

/** Tests the non-UI part of cluster command failure reporting. */
public class ClusterCommandHandlerTest
{
    /** A save-failure message names both the clusters file and the affected project. */
    @Test
    public void saveFailureMessageNamesTheFileAndProject()
    {
        IProject project = (IProject)Proxy.newProxyInstance(
            IProject.class.getClassLoader(),
            new Class<?>[] {IProject.class},
            (proxy, method, arguments) -> "getName".equals(method.getName()) ? "Demo" : null); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterWriteOutcome outcome = ClusterWriteOutcome.of(
            ClusterSaveOutcome.refused(ClusterSaveOutcome.CHANGED_ON_DISK));

        assertEquals(outcome.explanation() + " Check .settings/aiedt-clusters.yaml " //$NON-NLS-1$
            + "in project 'Demo' and try again.", //$NON-NLS-1$
            ClusterCommandHandler.saveFailureMessage(project, outcome));
        assertTrue(ClusterCommandHandler.saveFailureMessage(project, outcome).startsWith(outcome.explanation()));
    }

    /** An object already in the chosen cluster is skipped, not reported as a failed save. */
    @Test
    public void anObjectAlreadyInTheChosenClusterIsSkipped()
    {
        Cluster shelf = new Cluster("Shelf", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(AddToClusterCommand.isAlreadyIn(new Cluster("Shelf", "Catalogs"), shelf)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(AddToClusterCommand.isAlreadyIn(new Cluster("Box", "Catalogs"), shelf)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(AddToClusterCommand.isAlreadyIn(null, shelf));
    }
}
