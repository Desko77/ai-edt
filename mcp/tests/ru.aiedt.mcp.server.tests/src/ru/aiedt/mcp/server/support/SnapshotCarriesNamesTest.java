/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

/**
 * Holds the snapshot file readable by a person, and readable by the build that came before it.
 * <p>
 * The file is the material of a restore - what somebody opens when an update has gone wrong - and
 * it used to hold identities and nothing else. Rows of bare UUIDs say nothing about which objects
 * an update is about to touch.
 * </p>
 * <p>
 * <b>The name is written and never read back into a decision.</b> A restore matches on identity,
 * because a name moves between releases and an identity does not. The column exists for the person
 * reading the file; using it to choose what to restore would make a renamed object look like a
 * deleted one.
 * </p>
 * <p>
 * Compatibility runs both ways without a version bump, and that is a property of the format rather
 * than a promise: rows are split with no limit and read by position through {@code parts[2]}, so an
 * older build ignores a fourth column, and a newer one finds none in an older file.
 * </p>
 */
public class SnapshotCarriesNamesTest
{
    @Test
    public void aNameWrittenComesBackWithItsEntry() throws Exception
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("v1", "Demo", "3.2.1.505"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        UUID owner = UUID.randomUUID();
        UUID subordinate = UUID.randomUUID();
        parent.modes.put(owner, "Edited"); //$NON-NLS-1$
        parent.names.put(owner, "Catalog.Контрагенты"); //$NON-NLS-1$
        parent.modes.put(subordinate, "NotEditable"); //$NON-NLS-1$
        parent.names.put(subordinate, "Catalog.Контрагенты.Attribute.ИНН"); //$NON-NLS-1$
        snapshot.parents.add(parent);

        Path file = Files.createTempFile("aiedt-named-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            snapshot.write(file);
            SupportSnapshot back = SupportSnapshot.read(file);

            assertEquals(2, back.entries());
            assertEquals("Catalog.Контрагенты", back.parents.get(0).names.get(owner)); //$NON-NLS-1$
            assertEquals("the path from the owner is the point: Attribute.ИНН alone names nothing", //$NON-NLS-1$
                "Catalog.Контрагенты.Attribute.ИНН", back.parents.get(0).names.get(subordinate)); //$NON-NLS-1$
            assertEquals("Edited", back.parents.get(0).modes.get(owner)); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void aFileWrittenBeforeTheColumnExistedStillRestores() throws Exception
    {
        // Three columns, which is every snapshot taken until now. The modes have to come back
        // whole, because those files are what a person would reach for after an update went wrong
        // and there is no second chance to take them.
        Path file = Files.createTempFile("aiedt-v1-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        Files.write(file, List.of(
            "# AI-EDT support mode snapshot v1", //$NON-NLS-1$
            "# project\tDemo", //$NON-NLS-1$
            "# vendor\tv1\tDemo\t3.2.1.505", //$NON-NLS-1$
            "v1\t" + one + "\tEdited", //$NON-NLS-1$ //$NON-NLS-2$
            "v1\t" + two + "\t")); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            SupportSnapshot back = SupportSnapshot.read(file);

            assertNull(back.cannotTell);
            assertEquals(2, back.entries());
            assertEquals("Edited", back.parents.get(0).modes.get(one)); //$NON-NLS-1$
            assertTrue("a mode recorded as the model default has to come back as one", //$NON-NLS-1$
                back.parents.get(0).modes.containsKey(two));
            assertTrue("no names in an old file, and that is not a fault", //$NON-NLS-1$
                back.parents.get(0).names.isEmpty());
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void anEntryWithoutANameWritesAnEmptyColumnRatherThanAShortRow() throws Exception
    {
        // A short row would be read by position and land the mode in the wrong field, or be
        // discarded by the length check. The column is always present and may be empty.
        SupportSnapshot snapshot = new SupportSnapshot();
        SupportSnapshot.Parent parent = new SupportSnapshot.Parent("v1", "Demo", "3.2.1.505"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        UUID nameless = UUID.randomUUID();
        parent.modes.put(nameless, "Edited"); //$NON-NLS-1$
        snapshot.parents.add(parent);

        Path file = Files.createTempFile("aiedt-nameless-", ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            snapshot.write(file);
            for (String line : Files.readAllLines(file))
            {
                if (line.startsWith("v1\t")) //$NON-NLS-1$
                {
                    assertEquals("four fields whatever happens", 4, line.split("\t", -1).length); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            SupportSnapshot back = SupportSnapshot.read(file);
            assertEquals("Edited", back.parents.get(0).modes.get(nameless)); //$NON-NLS-1$
            assertNull(back.parents.get(0).names.get(nameless));
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }
}
