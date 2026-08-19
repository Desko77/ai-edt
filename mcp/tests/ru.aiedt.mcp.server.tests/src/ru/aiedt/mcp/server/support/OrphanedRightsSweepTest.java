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

import org.junit.Test;

/**
 * Covers the contract of the orphaned-rights sweep before it reaches a workspace.
 * <p>
 * What needs pinning here is the refusal side. Removing a rights entry takes a permission away
 * silently - nobody re-reads a role after a repair - so the sweep has to be unable to run by
 * accident, unable to leave the role directory, and unable to act on anything it is unsure of.
 * </p>
 */
public class OrphanedRightsSweepTest
{
    /** Without a project, a role and a resolver there is nothing to sweep, and it says so. */
    @Test
    public void everyArgumentIsRequired()
    {
        BmRightsHelper.OrphanSweep noProject =
            BmRightsHelper.sweepOrphanedRights(null, "Role", fqn -> Boolean.TRUE, false); //$NON-NLS-1$
        assertFalse(noProject.ok);
        assertNotNull(noProject.error);

        BmRightsHelper.OrphanSweep noResolver =
            BmRightsHelper.sweepOrphanedRights(null, "Role", null, false); //$NON-NLS-1$
        assertFalse(noResolver.ok);
        assertNotNull("without a resolver nothing can be decided", noResolver.error); //$NON-NLS-1$
    }

    /**
     * A role name is a path segment, so it may not be a path.
     * <p>
     * The same guard the rights setters carry. Here it matters more: this operation DELETES, and a
     * traversal would delete out of somebody else's role.
     * </p>
     */
    @Test
    public void aRoleNameThatIsAPathIsRefused()
    {
        for (String bad : new String[] {"../Other", "sub/Role", "..\\\\Other"}) //$NON-NLS-1$
        {
            BmRightsHelper.OrphanSweep sweep =
                BmRightsHelper.sweepOrphanedRights(null, bad, fqn -> Boolean.FALSE, false);

            assertFalse("a role name that walks out of the roles directory was accepted: " + bad, //$NON-NLS-1$
                sweep.ok);
        }
    }

    /** A fresh sweep reports nothing until it has read something. */
    @Test
    public void aRefusedSweepReportsNoOrphansRatherThanAnEmptySuccess()
    {
        BmRightsHelper.OrphanSweep sweep =
            BmRightsHelper.sweepOrphanedRights(null, "Role", fqn -> Boolean.FALSE, true); //$NON-NLS-1$

        assertFalse("a refusal must not read as a clean role", sweep.ok); //$NON-NLS-1$
        assertTrue(sweep.orphaned.isEmpty());
        assertFalse("nothing may be written when the sweep never ran", sweep.changed); //$NON-NLS-1$
        assertEquals(0, sweep.total);
    }
}
