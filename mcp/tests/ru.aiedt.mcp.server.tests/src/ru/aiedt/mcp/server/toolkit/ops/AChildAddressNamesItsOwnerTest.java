/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void anAddressStoppingOnAKindNamesNoChild()
    {
        // Measured: these three were reported as validated objects. They end on the kind of a child
        // and never say which one, and the walk down the model steps through whole pairs - so it
        // never enters, and the owner comes back looking like an answer.
        assertFalse(ObjectsRevalidator.namesAChild("Catalog.Users.Attribute"));
        assertFalse(ObjectsRevalidator.namesAChild("DataProcessor.PageCheck.Form"));
        assertFalse(ObjectsRevalidator.namesAChild("DataProcessor.PageCheck.Template"));
    }

    @Test
    public void awholePairBeyondTheOwnerNamesAChild()
    {
        assertTrue(ObjectsRevalidator.namesAChild("Catalog.Users.Attribute.Email"));
        assertTrue(ObjectsRevalidator.namesAChild("DataProcessor.PageCheck.Form.MainForm"));
        assertTrue(ObjectsRevalidator.namesAChild("InformationRegister.Rates.Resource.Rate"));
    }

    @Test
    public void theFormsOwnRootNamesAChildDespiteItsOddLength()
    {
        // How the index spells a form's own root. The last step is a marker, not the kind of
        // anything, which is why an odd number of steps is right here and nowhere else.
        assertTrue(ObjectsRevalidator.namesAChild("Catalog.Users.Form.UserForm.Form"));
    }

    @Test
    public void aTopObjectNamesNoChild()
    {
        assertFalse(ObjectsRevalidator.namesAChild("Catalog.Users"));
        assertFalse(ObjectsRevalidator.namesAChild("Configuration"));
        assertFalse(ObjectsRevalidator.namesAChild(null));
    }

    @Test
    public void anEmptyStepNamesNoChild()
    {
        assertFalse(ObjectsRevalidator.namesAChild("Catalog..Attribute.Email"));
        assertFalse(ObjectsRevalidator.namesAChild("Catalog.Users.Attribute."));
    }
}
