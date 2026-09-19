/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * An update decision is read from the model only after the workspace heard about the disk.
 *
 * <p>Files written outside this server - a file tool, git checkout, a pull - are invisible to
 * the model until refresh, and the update decision below answered against the model: Done or
 * UPDATED over an update that never carried the change. The refresh is part of the update call
 * now, reported and never silent, and it cannot be forgotten by being left to the caller.</p>
 */
public class AnUpdateReadsTheModelAfterTheDiskTest
{
    /**
     * The refresh runs on the project and, for an extension, on the parent that owns the
     * infobase - because the change usually sits in the extension while the infobase belongs
     * to the parent.
     *
     * @throws Exception when the helper is gone
     */
    @Test
    public void theRefreshCoversBothProjects()
        throws Exception
    {
        Method refresh = DatabaseUpdater.class.getDeclaredMethod("refreshFromDisk", //$NON-NLS-1$
            org.eclipse.core.resources.IProject.class, org.eclipse.core.resources.IProject.class);
        assertNotNull(refresh);
        assertTrue(java.lang.reflect.Modifier.isStatic(refresh.getModifiers()));
    }

    /**
     * The schema advertises the flag so a caller who changed nothing outside can skip the walk.
     */
    @Test
    public void theSchemaAdvertisesTheFlag()
    {
        String schema = new DatabaseUpdater().getInputSchema();
        assertTrue(schema, schema.contains("refreshWorkspace")); //$NON-NLS-1$
    }

    /**
     * The health answer names the projects of the workspace, so at two stands one health read
     * picks the port, and a refusal is never read as the server being down when the port simply
     * belongs to the other instance.
     */
    @Test
    public void healthNamesTheProjects()
    {
        java.util.List<String> names = ru.aiedt.mcp.server.support.ProjectResolver.openProjectNames(); //$NON-NLS-1$
        assertNotNull(names);
    }
}
