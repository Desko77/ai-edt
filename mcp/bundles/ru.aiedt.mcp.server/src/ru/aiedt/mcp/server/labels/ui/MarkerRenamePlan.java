/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Remembers which markers were assigned before a rename, using the name the marker had then.
 * <p>
 * The edit dialog holds the same {@link Marker} instance the service renames. Reading
 * {@link Marker#getName()} after the update therefore returns the new name, and the set of
 * assignments is left pointing at the old one. Confirming the dialog then cannot tell that a
 * checkbox was cleared, because the old name is no longer the name on the marker. The plan is
 * captured before the update runs.
 * </p>
 */
public final class MarkerRenamePlan
{
    private final String nameBefore;

    private final String newName;

    private final Set<String> initiallyAssigned;

    private MarkerRenamePlan(String nameBefore, String newName, Set<String> initiallyAssigned)
    {
        this.nameBefore = nameBefore;
        this.newName = newName;
        this.initiallyAssigned = initiallyAssigned == null ? Set.of() : initiallyAssigned;
    }

    /**
     * Renames the marker, then returns the assigned names with the old name replaced by the new one.
     * <p>
     * The name passed to {@code rename} is the one the marker had when this method started, before
     * {@code rename} is allowed to change the instance.
     * </p>
     *
     * @param marker the marker being edited; its name is read before {@code rename} runs
     * @param newName the name the dialog asked for
     * @param initiallyAssigned the names that were assigned when the dialog opened
     * @param rename applies the rename to the service; receives the name from before the update and the
     *        new name
     * @return the assigned names after the rename
     */
    public static Set<String> apply(Marker marker, String newName, Set<String> initiallyAssigned,
        BiConsumer<String, String> rename)
    {
        String nameBefore = marker.getName();
        MarkerRenamePlan plan = new MarkerRenamePlan(nameBefore, newName, initiallyAssigned);
        if (rename != null)
        {
            rename.accept(nameBefore, newName);
        }
        return plan.assignedAfterRename();
    }

    /**
     * Returns the assigned names with this plan's old name replaced by its new name.
     *
     * @return the updated set; the same set when nothing had to move
     */
    public Set<String> assignedAfterRename()
    {
        if (newName == null || newName.equals(nameBefore) || !initiallyAssigned.contains(nameBefore))
        {
            return initiallyAssigned;
        }
        Set<String> updated = new HashSet<>(initiallyAssigned);
        updated.remove(nameBefore);
        updated.add(newName);
        return updated;
    }

    /**
     * Tells whether confirming the dialog should take this marker off the object.
     * <p>
     * The assigned set is the one {@link #assignedAfterRename()} returned, and {@code markerName} is the
     * name the marker has after the rename. A set that still holds only the old name does not match, and
     * the checkbox the user cleared is then left on the object.
     * </p>
     *
     * @param assignedAfterRename the names that count as assigned after the rename
     * @param markerName the marker's name after the rename
     * @param checked whether the checkbox is on
     * @return <code>true</code> when the marker was assigned and the checkbox is now off
     */
    public static boolean unassignOnApply(Set<String> assignedAfterRename, String markerName, boolean checked)
    {
        return assignedAfterRename != null && assignedAfterRename.contains(markerName) && !checked;
    }
}
