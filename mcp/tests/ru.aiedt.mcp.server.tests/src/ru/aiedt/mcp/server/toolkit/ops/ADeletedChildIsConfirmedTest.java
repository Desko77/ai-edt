/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers which question a deleted address can be asked after the delete has run.
 * <p>
 * The leftover check used to answer for exactly two segments and nothing else, so a form, a template
 * and an attribute alike went through unverified. The two have different evidence: a form and a
 * template own a folder under the owner, and the disk answers for them; an attribute or a tabular
 * section is a few lines inside the owner's {@code .mdo}, where the disk can only report that the
 * owner is still there - true, and not what was asked. This pins the split and the folder names the
 * disk check walks.
 * </p>
 */
public class ADeletedChildIsConfirmedTest
{
    /** A child that owns a folder is one the disk can answer for. */
    @Test
    public void aChildWithAFolderIsAskedOfTheDisk()
    {
        assertTrue("a form owns a folder", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertTrue("so does a template", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Template.PrintForm")); //$NON-NLS-1$
        assertTrue("and a command, which carries CommandModule.bsl", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Command.Recalculate")); //$NON-NLS-1$
    }

    /** The same holds when the caller wrote the kind in Russian. */
    @Test
    public void theRussianSpellingOfAKindCountsToo()
    {
        assertTrue("Форма", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Форма.ФормаСписка")); //$NON-NLS-1$
        assertTrue("Макет", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Макет.Печать")); //$NON-NLS-1$
        assertTrue("Команда", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Команда.Пересчитать")); //$NON-NLS-1$
    }

    /** A child that lives in the owner's file has nothing on disk to look for. */
    @Test
    public void aChildInsideTheOwnersFileIsNotAskedOfTheDisk()
    {
        assertFalse("an attribute", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.Attribute.Price")); //$NON-NLS-1$
        assertFalse("a tabular section", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.TabularSection.Prices")); //$NON-NLS-1$
        assertFalse("a dimension", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("InformationRegister.Rates.Dimension.Currency")); //$NON-NLS-1$
        assertFalse("an enum value", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Enum.Status.EnumValue.Closed")); //$NON-NLS-1$
    }

    /** A whole object and a deeper address are not children with a folder either. */
    @Test
    public void anObjectAndADeeperAddressAreNotChildrenWithAFolder()
    {
        assertFalse("a top-level object has its own directory, checked elsewhere", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products")); //$NON-NLS-1$
        assertFalse("an attribute of a tabular section is six segments", //$NON-NLS-1$
            MetadataObjectDeleter.hasItsOwnResource("Catalog.Products.TabularSection.Prices.Attribute.Price")); //$NON-NLS-1$
        assertFalse("nothing is not an address", MetadataObjectDeleter.hasItsOwnResource(null)); //$NON-NLS-1$
        assertFalse("neither is a bare name", MetadataObjectDeleter.hasItsOwnResource("Products")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The folder the disk check walks is the one the export writes. */
    @Test
    public void theFolderNameMatchesWhatTheExportWrites()
    {
        assertEquals("Forms", MetadataObjectDeleter.englishKindFolder("Form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Forms", MetadataObjectDeleter.englishKindFolder("Форма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Templates", MetadataObjectDeleter.englishKindFolder("Template")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Templates", MetadataObjectDeleter.englishKindFolder("Макет")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Commands", MetadataObjectDeleter.englishKindFolder("Command")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Commands", MetadataObjectDeleter.englishKindFolder("Команда")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
