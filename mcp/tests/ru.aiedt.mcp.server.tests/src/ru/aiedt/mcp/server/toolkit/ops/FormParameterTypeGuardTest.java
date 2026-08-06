/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the one form-parameter type the plugin refuses to write.
 * <p>
 * {@code Array} resolves in the model and validates clean, so nothing downstream objects to it -
 * and the parameter is then useless. Across 5789 form parameters of two real configurations it
 * does not occur once. Refusing costs the caller a single retry with {@code ValueList}, which is
 * why the refusal names it; writing the type costs a form that only misbehaves after the infobase
 * is updated, at which point the cause is several steps behind.
 * </p>
 */
public class FormParameterTypeGuardTest
{
    @Test
    public void anArrayParameterIsRefused()
    {
        assertNotNull("Array must not reach the form", //$NON-NLS-1$
            FormItemsOps.rejectUnsupportedParameterType("Array")); //$NON-NLS-1$
    }

    @Test
    public void theRefusalNamesTheTypeToUseInstead()
    {
        // A refusal an agent cannot act on just turns into a retry of the same call.
        String refusal = FormItemsOps.rejectUnsupportedParameterType("Array"); //$NON-NLS-1$
        assertTrue("the refusal should point at ValueList: " + refusal, //$NON-NLS-1$
            refusal.contains("ValueList")); //$NON-NLS-1$
    }

    @Test
    public void spellingAndPaddingDoNotSlipItThrough()
    {
        assertNotNull(FormItemsOps.rejectUnsupportedParameterType("array")); //$NON-NLS-1$
        assertNotNull(FormItemsOps.rejectUnsupportedParameterType("ARRAY")); //$NON-NLS-1$
        assertNotNull(FormItemsOps.rejectUnsupportedParameterType("  Array  ")); //$NON-NLS-1$
    }

    @Test
    public void everyTypeThatWorksIsLeftAlone()
    {
        // The types a form parameter actually carries in the wild - the guard is a deny list of
        // one, not a white list, so a type it has never heard of has to pass.
        String[] used = { "String", "Boolean", "Number", "Date", "UUID", "ValueList", "AnyRef", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "TypeDescription", "StandardPeriod", "SpreadsheetDocument", "FormattedString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Picture", "DataCompositionSettingsComposer", "CatalogRef.Products" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String type : used)
        {
            assertNull(type + " is a working parameter type and must pass", //$NON-NLS-1$
                FormItemsOps.rejectUnsupportedParameterType(type));
        }
    }

    @Test
    public void anAbsentTypeIsNotAnError()
    {
        // type is optional: a parameter can be declared untyped and given a type later.
        assertNull(FormItemsOps.rejectUnsupportedParameterType(null));
        assertNull(FormItemsOps.rejectUnsupportedParameterType("")); //$NON-NLS-1$
    }
}
