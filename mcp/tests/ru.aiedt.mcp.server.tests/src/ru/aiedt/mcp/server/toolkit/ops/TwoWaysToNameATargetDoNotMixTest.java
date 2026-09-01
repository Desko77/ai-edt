/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How the schema workshop reads the target a caller named.
 * <p>
 * A schema is named by object plus template; a form's dynamic list by form plus attribute. Half of
 * either, or both at once, is refused in words rather than resolved by preference - a call that
 * quietly picks one target and reports success would leave the caller reading about something they
 * did not ask for.
 * </p>
 */
public class TwoWaysToNameATargetDoNotMixTest
{
    private static final String PROJECT = "SomeProject"; //$NON-NLS-1$
    private static final String FORM = "Catalog.Currencies.Form.ListForm"; //$NON-NLS-1$
    private static final String ATTRIBUTE = "List"; //$NON-NLS-1$
    private static final String OBJECT = "Report.Sales"; //$NON-NLS-1$

    @Test
    public void aSchemaAddressPasses()
    {
        assertNull(DcsWorkshopTool.addressRefusal(PROJECT, OBJECT, null, null));
        assertFalse(DcsWorkshopTool.addressesAList(null, null));
    }

    @Test
    public void aListAddressPasses()
    {
        assertNull(DcsWorkshopTool.addressRefusal(PROJECT, null, FORM, ATTRIBUTE));
        assertTrue(DcsWorkshopTool.addressesAList(FORM, ATTRIBUTE));
    }

    @Test
    public void halfOfTheListAddressIsNamedAsHalf()
    {
        String onlyForm = DcsWorkshopTool.addressRefusal(PROJECT, null, FORM, null);
        assertNotNull(onlyForm);
        assertTrue("the refusal must say which half is missing: " + onlyForm, //$NON-NLS-1$
            onlyForm.contains("attributeName")); //$NON-NLS-1$

        String onlyAttribute = DcsWorkshopTool.addressRefusal(PROJECT, null, null, ATTRIBUTE);
        assertNotNull(onlyAttribute);
        assertTrue("the refusal must say which half is missing: " + onlyAttribute, //$NON-NLS-1$
            onlyAttribute.contains("formFqn")); //$NON-NLS-1$

        assertFalse(DcsWorkshopTool.addressesAList(FORM, null));
        assertFalse(DcsWorkshopTool.addressesAList(null, ATTRIBUTE));
    }

    @Test
    public void anEmptyStringCountsAsAbsent()
    {
        assertFalse(DcsWorkshopTool.addressesAList("", ATTRIBUTE)); //$NON-NLS-1$
        assertFalse(DcsWorkshopTool.addressesAList(FORM, "")); //$NON-NLS-1$
        assertNull("an empty objectName beside a full list address is not a second target", //$NON-NLS-1$
            DcsWorkshopTool.addressRefusal(PROJECT, "", FORM, ATTRIBUTE)); //$NON-NLS-1$
    }

    @Test
    public void bothAddressesAtOnceAreRefused()
    {
        String both = DcsWorkshopTool.addressRefusal(PROJECT, OBJECT, FORM, ATTRIBUTE);
        assertNotNull("naming a schema and a list in one call must not resolve silently", both); //$NON-NLS-1$
        assertTrue(both.contains("objectName")); //$NON-NLS-1$
        assertTrue(both.contains("formFqn")); //$NON-NLS-1$
    }

    @Test
    public void theProjectIsNeededWhicheverTargetIsNamed()
    {
        assertNotNull(DcsWorkshopTool.addressRefusal(null, OBJECT, null, null));
        assertNotNull(DcsWorkshopTool.addressRefusal(null, null, FORM, ATTRIBUTE));
        assertNotNull(DcsWorkshopTool.addressRefusal("", null, FORM, ATTRIBUTE)); //$NON-NLS-1$
    }

    @Test
    public void namingNoTargetAtAllIsRefused()
    {
        String none = DcsWorkshopTool.addressRefusal(PROJECT, null, null, null);
        assertNotNull(none);
        assertTrue("the refusal must offer both ways in: " + none, //$NON-NLS-1$
            none.contains("objectName") && none.contains("attributeName")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
