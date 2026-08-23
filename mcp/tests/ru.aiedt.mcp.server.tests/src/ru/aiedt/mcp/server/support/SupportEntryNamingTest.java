/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

/**
 * Guards what a snapshot says about the entries it could not name.
 * <p>
 * <b>It used to call them absent from the configuration, and they are not.</b> The name index walks
 * top-level objects on purpose - naming every subordinate entity would load the whole model for a
 * listing - so attributes, forms and templates come back as identities. Reporting that under
 * {@code notInTheConfiguration} states something untrue about objects the configuration has:
 * measured on a real one, 7239 of 10843 entries, two thirds of the snapshot.
 * </p>
 * <p>
 * The count itself is right and stays. What changed is that it no longer claims to mean something
 * else - a reader who believes it goes looking for deleted objects that were never deleted.
 * </p>
 */
public class SupportEntryNamingTest
{
    @Test
    public void anEntryWithoutANameIsStillCounted()
    {
        // Dropping it would make the listing disagree with the support file's own total, which is
        // the one number a person can check independently.
        SupportSnapshot snapshot = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("id", "Demo", "3.2.1.505");
        parent.modes.put(UUID.randomUUID(), "Edited");
        parent.modes.put(UUID.randomUUID(), "NotEditable");
        snapshot.parents.add(parent);
        snapshot.unresolved = 1;
        assertEquals(2, snapshot.entries());
        assertEquals(1, snapshot.unresolved);
    }

    @Test
    public void theCountSurvivesTheRoundTripToFile() throws Exception
    {
        // The snapshot is the whole of what makes a restore possible, so what it records has to
        // come back the same. An entry that reads back short is an entry that cannot be put back.
        SupportSnapshot snapshot = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("id-1", "Demo", "3.2.1.505");
        UUID one = UUID.randomUUID();
        parent.modes.put(one, "Edited");
        parent.modes.put(UUID.randomUUID(), null);
        snapshot.parents.add(parent);

        java.nio.file.Path file = java.nio.file.Files.createTempFile("aiedt-modes", ".tsv");
        try
        {
            snapshot.write(file);
            SupportSnapshot back = SupportSnapshot.read(file);
            assertEquals(snapshot.entries(), back.entries());
            assertEquals(1, back.parents.size());
            assertEquals("Demo", back.parents.get(0).name);
            assertEquals("Edited", back.parents.get(0).modes.get(one));
            assertTrue("a mode recorded as the model default has to come back as one",
                back.parents.get(0).modes.containsValue(null));
        }
        finally
        {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    public void aVendorIsMatchedByIdentityNotByVersion()
    {
        // The version moves with every delivery; the identity does not. Matching on the version
        // would make every update look like a different vendor, and every restore refuse.
        SupportSnapshot before = new SupportSnapshot();
        before.parents.add(new SupportSnapshot.Parent("vendor-id", "Demo", "3.2.1.505"));
        SupportSnapshot after = new SupportSnapshot();
        after.parents.add(new SupportSnapshot.Parent("vendor-id", "Demo", "3.2.1.600"));

        SupportSnapshot.Drift drift = SupportSnapshot.compare(before, after);
        assertTrue("the same vendor at a newer release is the same vendor",
            drift.parentsGone.isEmpty());
        assertTrue(drift.parentsMatched.size() == 1);
    }
}
