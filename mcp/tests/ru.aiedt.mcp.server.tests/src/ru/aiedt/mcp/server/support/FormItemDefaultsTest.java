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

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Guards the property values a new table and a new usual group are born with.
 * <p>
 * These are not style preferences. The EDT model accepts a wider set of values than the infobase
 * does, so a value can pass validation, sit in the form for weeks and only surface when someone
 * updates the infobase - as an XDTO property mismatch with no hint about which element caused it.
 * Three such values shipped at once ({@code rowSelectionMode=Auto}, {@code representation=Auto},
 * {@code group=Auto}) plus two properties newer than a typical compatibility mode. The tests below
 * are the cheap standing check that they do not come back, since nothing in the build can notice
 * them: EDT validation is green either way.
 * </p>
 */
public class FormItemDefaultsTest
{
    /**
     * Properties whose platform enum has no Auto, whatever the EDT model says. An Auto here is the
     * exact defect that broke the import.
     */
    private static final Set<String> NO_AUTO_ALLOWED = new HashSet<>();

    /** Properties that must not be written at all - newer than the compatibility mode in use. */
    private static final Set<String> MUST_NOT_BE_WRITTEN = new HashSet<>();

    static
    {
        NO_AUTO_ALLOWED.add("rowSelectionMode"); //$NON-NLS-1$
        NO_AUTO_ALLOWED.add("group"); //$NON-NLS-1$
        NO_AUTO_ALLOWED.add("representation"); //$NON-NLS-1$
        MUST_NOT_BE_WRITTEN.add("autoMaxCardHeight"); //$NON-NLS-1$
        MUST_NOT_BE_WRITTEN.add("showCommandBarNeedDereferenced"); //$NON-NLS-1$
    }

    @Test
    public void aNewTableSelectsWholeRowsRatherThanAuto()
    {
        assertEquals("Row", valueOf(BmFormHelper.TABLE_RENDER_DEFAULTS, "rowSelectionMode")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aNewGroupStacksItsChildrenVerticallyRatherThanAuto()
    {
        assertEquals("Vertical", valueOf(BmFormHelper.USUAL_GROUP_DEFAULTS, "group")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aNewGroupIsLeftWithoutARepresentation()
    {
        // An editor-created group carries no <representation>; writing one is how Auto got in.
        assertNotWritten("representation", BmFormHelper.USUAL_GROUP_DEFAULTS); //$NON-NLS-1$
    }

    @Test
    public void noDefaultCarriesAnAutoThePlatformWouldReject()
    {
        assertNoAutoIn(BmFormHelper.TABLE_RENDER_DEFAULTS, "table"); //$NON-NLS-1$
        assertNoAutoIn(BmFormHelper.USUAL_GROUP_DEFAULTS, "usual group"); //$NON-NLS-1$
    }

    @Test
    public void noDefaultIsNewerThanTheConfigurationBeingEdited()
    {
        for (String property : MUST_NOT_BE_WRITTEN)
        {
                assertNotWritten(property, BmFormHelper.TABLE_RENDER_DEFAULTS);
        }
    }

    @Test
    public void everyDefaultIsAUsablePair()
    {
        assertWellFormed(BmFormHelper.TABLE_RENDER_DEFAULTS, "table"); //$NON-NLS-1$
        assertWellFormed(BmFormHelper.USUAL_GROUP_DEFAULTS, "usual group"); //$NON-NLS-1$
    }

    @Test
    public void noPropertyIsSetTwiceWithDifferentValues()
    {
        assertNoDuplicates(BmFormHelper.TABLE_RENDER_DEFAULTS, "table"); //$NON-NLS-1$
        assertNoDuplicates(BmFormHelper.USUAL_GROUP_DEFAULTS, "usual group"); //$NON-NLS-1$
    }

    private static void assertNoAutoIn(String[][] defaults, String what)
    {
        for (String[] pair : defaults)
        {
            if (NO_AUTO_ALLOWED.contains(pair[0]))
            {
                assertFalse("the platform enum behind " + what + " property '" + pair[0] //$NON-NLS-1$ //$NON-NLS-2$
                    + "' has no Auto literal, so an Auto here fails the infobase import", //$NON-NLS-1$
                    "Auto".equalsIgnoreCase(pair[1])); //$NON-NLS-1$
            }
        }
    }

    private static void assertWellFormed(String[][] defaults, String what)
    {
        assertTrue(what + " defaults should not be empty", defaults.length > 0); //$NON-NLS-1$
        for (String[] pair : defaults)
        {
            assertEquals("every " + what + " default is a name/value pair", 2, pair.length); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(pair[0]);
            assertNotNull(pair[1]);
            assertFalse("a property name cannot be blank", pair[0].trim().isEmpty()); //$NON-NLS-1$
            assertFalse("a property value cannot be blank", pair[1].trim().isEmpty()); //$NON-NLS-1$
        }
    }

    private static void assertNoDuplicates(String[][] defaults, String what)
    {
        Set<String> seen = new HashSet<>();
        for (String[] pair : defaults)
        {
            assertTrue(what + " property '" + pair[0] + "' is listed twice - the later value " //$NON-NLS-1$ //$NON-NLS-2$
                + "silently wins", seen.add(pair[0])); //$NON-NLS-1$
        }
    }

    private static void assertNotWritten(String property, String[][] defaults)
    {
        assertFalse("'" + property + "' must not be written on a new element", //$NON-NLS-1$ //$NON-NLS-2$
            valueOf(defaults, property) != null);
    }

    private static String valueOf(String[][] defaults, String property)
    {
        for (String[] pair : defaults)
        {
            if (pair[0].equals(property))
            {
                return pair[1];
            }
        }
        return null;
    }
}
