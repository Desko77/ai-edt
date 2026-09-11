/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * A child address names the object that holds it, and a top-level address names nobody.
 * <p>
 * This is what stands between "the model does not hold this" and "the index cannot answer for
 * this". Getting it wrong in one direction sends a caller to create an object that already exists;
 * in the other it validates some unrelated object and reports the address as checked.
 * </p>
 */
public class AChildAddressNamesItsOwnerTest
{
    @Test
    public void aFormIsHeldByItsObject()
    {
        assertEquals("DataProcessor.PageCheck",
            ObjectsRevalidator.ownerOf("DataProcessor.PageCheck.Form.MainForm"));
    }

    @Test
    public void aTemplateIsHeldByItsObject()
    {
        assertEquals("DataProcessor.PageCheck",
            ObjectsRevalidator.ownerOf("DataProcessor.PageCheck.Template.BatchSchema"));
    }

    @Test
    public void anAttributeIsHeldByItsObject()
    {
        assertEquals("Catalog.Users", ObjectsRevalidator.ownerOf("Catalog.Users.Attribute.Email"));
    }

    @Test
    public void aResourceIsHeldByItsRegister()
    {
        assertEquals("InformationRegister.Rates",
            ObjectsRevalidator.ownerOf("InformationRegister.Rates.Resource.Rate"));
    }

    @Test
    public void theFormsOwnRootStillNamesTheObject()
    {
        // The BaseForm root carries a longer address than the form metadata object does. Both have
        // to arrive at the same owner, or one of the two spellings validates nothing.
        assertEquals("Catalog.Users",
            ObjectsRevalidator.ownerOf("Catalog.Users.Form.UserForm.Form"));
    }

    @Test
    public void aTopObjectIsHeldByNobody()
    {
        assertNull("a two-step address is the object itself, and validating its owner would mean "
            + "validating something else", ObjectsRevalidator.ownerOf("Catalog.Users"));
    }

    @Test
    public void theConfigurationRootIsHeldByNobody()
    {
        assertNull(ObjectsRevalidator.ownerOf("Configuration"));
    }

    @Test
    public void nothingIsHeldByNobody()
    {
        assertNull(ObjectsRevalidator.ownerOf(null));
        assertNull(ObjectsRevalidator.ownerOf(""));
    }

    @Test
    public void anAddressThatStopsAtTheSecondStepNamesNobody()
    {
        // "Catalog.Users." is not a child address; treating it as one would hand back the owner
        // for an address that names no child at all.
        assertNull(ObjectsRevalidator.ownerOf("Catalog.Users."));
    }

    @Test
    public void anEmptyStepNamesNobody()
    {
        assertNull(ObjectsRevalidator.ownerOf("Catalog..Attribute.Email"));
    }
}
