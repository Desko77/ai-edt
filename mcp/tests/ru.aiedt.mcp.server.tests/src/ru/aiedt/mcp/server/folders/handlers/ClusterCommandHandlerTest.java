/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;

import org.eclipse.core.resources.IProject;

import org.junit.Test;

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

        assertEquals("The cluster changes were not saved. Check .settings/aiedt-clusters.yaml " //$NON-NLS-1$
            + "in project 'Demo' and try again.", ClusterCommandHandler.saveFailureMessage(project)); //$NON-NLS-1$
    }
}
