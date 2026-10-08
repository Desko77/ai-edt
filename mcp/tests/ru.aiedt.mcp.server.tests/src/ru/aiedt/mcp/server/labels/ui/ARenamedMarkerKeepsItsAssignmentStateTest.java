/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Renaming a marker inside the dialog has to keep the assignment, and a cleared checkbox has to
 * come off the object.
 * <p>
 * The service renames the same {@link Marker} the dialog is holding. The name used to move the
 * assignment set is the one captured before that rename.
 * </p>
 */
public class ARenamedMarkerKeepsItsAssignmentStateTest
{
    @Test
    public void aRenameCapturedBeforeTheUpdateMovesTheAssignment()
    {
        Marker marker = new Marker("bug"); //$NON-NLS-1$
        Set<String> initiallyAssigned = new HashSet<>();
        initiallyAssigned.add("bug"); //$NON-NLS-1$

        Set<String> after = MarkerRenamePlan.apply(marker, "bug2", initiallyAssigned, (nameBefore, newName) -> { //$NON-NLS-1$
            assertEquals("bug", nameBefore); //$NON-NLS-1$
            marker.setName(newName);
            return true;
        });

        assertEquals(Set.of("bug2"), after); //$NON-NLS-1$
        assertEquals("bug2", marker.getName()); //$NON-NLS-1$
        assertTrue(MarkerRenamePlan.unassignOnApply(after, "bug2", false)); //$NON-NLS-1$
    }

    @Test
    public void aSetLeftOnTheOldNameDoesNotUnassignTheRenamedMarker()
    {
        assertFalse(MarkerRenamePlan.unassignOnApply(Set.of("bug"), "bug2", false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRefusedRenameLeavesTheAssignedNamesOnTheOldName()
    {
        Marker marker = new Marker("bug"); //$NON-NLS-1$
        Set<String> initiallyAssigned = new HashSet<>();
        initiallyAssigned.add("bug"); //$NON-NLS-1$
        // A refused rename does not touch the instance, the way the service leaves it when the
        // new name is taken or the file would not write.
        Set<String> after = MarkerRenamePlan.apply(marker, "bug2", initiallyAssigned,
            (nameBefore, newName) -> false);
        assertEquals(Set.of("bug"), after); //$NON-NLS-1$
        assertEquals("bug", marker.getName()); //$NON-NLS-1$
        // The checkbox the user cleared still takes the marker off the object.
        assertTrue(MarkerRenamePlan.unassignOnApply(after, "bug", false)); //$NON-NLS-1$
    }
}
