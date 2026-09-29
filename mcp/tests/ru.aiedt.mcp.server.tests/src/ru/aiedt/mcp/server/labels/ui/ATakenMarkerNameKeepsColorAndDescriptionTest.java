/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.labels.model.Marker;
import ru.aiedt.mcp.server.labels.model.MarkerStore;

/**
 * A rename onto a name that is already taken keeps the color and the description.
 */
public class ATakenMarkerNameKeepsColorAndDescriptionTest
{
    @Test
    public void theTakenNameIsRefusedAndTheAppearanceIsStored()
    {
        MarkerStore storage = new MarkerStore();
        storage.addMarker(new Marker("bug", "#111111", "old")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        storage.addMarker(new Marker("note", "#222222", "other")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MarkerEditCommit.Result result = MarkerEditCommit.apply(editor(storage), "bug", "note", //$NON-NLS-1$ //$NON-NLS-2$
            "#ABCDEF", "kept"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(result.message());
        assertTrue(result.message().contains("note")); //$NON-NLS-1$
        assertFalse(result.nameChanged());
        assertTrue(result.stored());
        assertEquals("#ABCDEF", storage.getMarkerByName("bug").getColor()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("kept", storage.getMarkerByName("bug").getDescription()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#222222", storage.getMarkerByName("note").getColor()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("other", storage.getMarkerByName("note").getDescription()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFreeNameIsStoredWithNoMessage()
    {
        MarkerStore storage = new MarkerStore();
        storage.addMarker(new Marker("bug", "#111111", "old")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MarkerEditCommit.Result result = MarkerEditCommit.apply(editor(storage), "bug", "bug2", //$NON-NLS-1$ //$NON-NLS-2$
            "#ABCDEF", "kept"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.message());
        assertTrue(result.nameChanged());
        assertEquals("#ABCDEF", storage.getMarkerByName("bug2").getColor()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(storage.getMarkerByName("bug")); //$NON-NLS-1$
    }

    /**
     * The same refusal the marker service makes: a taken name changes nothing, a kept name stores the
     * color and the description.
     *
     * @param storage the markers under test
     * @return an editor over that storage
     */
    private static MarkerEditCommit.Editor editor(MarkerStore storage)
    {
        return new MarkerEditCommit.Editor()
        {
            @Override
            public boolean update(String oldName, String newName, String color, String description)
            {
                Marker marker = storage.getMarkerByName(oldName);
                if (marker == null)
                {
                    return false;
                }
                if (newName != null && !newName.equals(oldName))
                {
                    if (storage.getMarkerByName(newName) != null)
                    {
                        return false;
                    }
                    marker.setName(newName);
                }
                if (color != null)
                {
                    marker.setColor(color);
                }
                if (description != null)
                {
                    marker.setDescription(description);
                }
                return true;
            }

            @Override
            public boolean defined(String name)
            {
                return storage.getMarkerByName(name) != null;
            }
        };
    }
}
