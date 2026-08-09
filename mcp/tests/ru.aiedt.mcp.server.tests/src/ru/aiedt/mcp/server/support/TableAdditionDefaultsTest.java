/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Guards the behaviour properties a new table is born with.
 * <p>
 * These values are not cosmetic. The same three that once carried an infobase-rejected Auto broke the
 * .epf build as well: {@code export_object} failed in under a second with an empty platform message,
 * while project validation reported nothing, and the cause could only be found by deleting the table
 * and trying again. A property added here without checking it against a real configuration is a defect
 * nothing in the build can see.
 * </p>
 */
public class TableAdditionDefaultsTest
{
    /** Values the infobase rejects even though the EDT model accepts them. */
    private static final Set<String> NO_AUTO_ALLOWED = new HashSet<>();

    /** Properties newer than the compatibility mode of an ordinary configuration. */
    private static final Set<String> MUST_NOT_BE_WRITTEN = new HashSet<>();

    static
    {
        NO_AUTO_ALLOWED.add("rowSelectionMode"); //$NON-NLS-1$
        MUST_NOT_BE_WRITTEN.add("autoMaxCardHeight"); //$NON-NLS-1$
        MUST_NOT_BE_WRITTEN.add("showCommandBarNeedDereferenced"); //$NON-NLS-1$
    }

    @Test
    public void aTableSelectsWholeRows()
    {
        assertEquals("Row", valueOf("rowSelectionMode")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void noDefaultCarriesAnAutoTheInfobaseRejects()
    {
        for (String[] pair : BmFormHelper.TABLE_RENDER_DEFAULTS)
        {
            if (NO_AUTO_ALLOWED.contains(pair[0]))
            {
                assertFalse(pair[0] + " must not be Auto - the platform enum has no such literal, " //$NON-NLS-1$
                    + "and the mismatch breaks both the infobase import and the .epf build", //$NON-NLS-1$
                    "Auto".equalsIgnoreCase(pair[1])); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void noDefaultIsNewerThanTheConfigurationBeingEdited()
    {
        for (String property : MUST_NOT_BE_WRITTEN)
        {
            assertNull(property);
        }
    }

    @Test
    public void everyDefaultIsAUsablePair()
    {
        assertTrue("the table defaults should not be empty", //$NON-NLS-1$
            BmFormHelper.TABLE_RENDER_DEFAULTS.length > 0);
        Set<String> seen = new HashSet<>();
        for (String[] pair : BmFormHelper.TABLE_RENDER_DEFAULTS)
        {
            assertEquals("every default is a name/value pair", 2, pair.length); //$NON-NLS-1$
            assertFalse("a property name cannot be blank", pair[0].trim().isEmpty()); //$NON-NLS-1$
            assertFalse("a property value cannot be blank", pair[1].trim().isEmpty()); //$NON-NLS-1$
            assertTrue("'" + pair[0] + "' is listed twice - the later value silently wins", //$NON-NLS-1$ //$NON-NLS-2$
                seen.add(pair[0]));
        }
    }

    private static void assertNull(String property)
    {
        assertFalse("'" + property + "' must not be written on a new table", //$NON-NLS-1$ //$NON-NLS-2$
            valueOf(property) != null);
    }

    private static String valueOf(String property)
    {
        for (String[] pair : BmFormHelper.TABLE_RENDER_DEFAULTS)
        {
            if (pair[0].equals(property))
            {
                return pair[1];
            }
        }
        return null;
    }
}
