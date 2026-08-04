/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers the mapping from a form's fully qualified name to the file that holds it.
 * <p>
 * Everything that edits a form starts here, so a wrong answer is not a wrong path but a write into
 * the wrong object - or a refusal to touch a form that does exist. The two shapes it accepts are the
 * two the platform has: a common form named by two segments, and an owned form named by four.
 * </p>
 */
public class MetadataPathMapperTest
{
    @Test
    public void aCommonFormResolvesToItsOwnFolder()
    {
        assertEquals("src/CommonForms/Settings/Form.form", //$NON-NLS-1$
            MetadataPathMapper.resolveFormFilePath("CommonForm.Settings")); //$NON-NLS-1$
    }

    @Test
    public void anOwnedFormResolvesUnderItsOwner()
    {
        assertEquals("src/Documents/Invoice/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathMapper.resolveFormFilePath("Document.Invoice.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void theFormsKeywordIsMatchedLooselyAndWrittenBackInEdtSpelling()
    {
        // Agents type it in whatever case they please; the path on disk has only one spelling.
        assertEquals("src/Catalogs/Products/Forms/ListForm/Form.form", //$NON-NLS-1$
            MetadataPathMapper.resolveFormFilePath("Catalog.Products.forms.ListForm")); //$NON-NLS-1$
    }

    @Test
    public void aTypeThatOwnsNoFormsResolvesToNothing()
    {
        // Roles have no forms. Answering with a path would invite a write that creates a folder the
        // platform never reads.
        assertNull(MetadataPathMapper.resolveFormFilePath("Role.Administrator.Forms.Main")); //$NON-NLS-1$
    }

    @Test
    public void aTwoSegmentNameThatIsNotACommonFormIsNotAForm()
    {
        assertNull(MetadataPathMapper.resolveFormFilePath("Document.Invoice")); //$NON-NLS-1$
    }

    @Test
    public void aFourSegmentNameWithoutTheFormsKeywordIsRejected()
    {
        assertNull(MetadataPathMapper.resolveFormFilePath("Document.Invoice.Commands.Post")); //$NON-NLS-1$
    }

    @Test
    public void namesWithTheWrongNumberOfSegmentsResolveToNothing()
    {
        assertNull(MetadataPathMapper.resolveFormFilePath("Invoice")); //$NON-NLS-1$
        assertNull(MetadataPathMapper.resolveFormFilePath("Document.Invoice.Forms")); //$NON-NLS-1$
        assertNull(MetadataPathMapper.resolveFormFilePath("A.B.Forms.C.D")); //$NON-NLS-1$
    }

    @Test
    public void nullAndEmptyResolveToNothing()
    {
        assertNull(MetadataPathMapper.resolveFormFilePath(null));
        assertNull(MetadataPathMapper.resolveFormFilePath("")); //$NON-NLS-1$
    }

    @Test
    public void aKnownTypeMapsToItsDirectory()
    {
        assertEquals("Documents", MetadataPathMapper.resolveMetadataDir("Document")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalogs", MetadataPathMapper.resolveMetadataDir("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnknownTypeMapsToNothing()
    {
        assertNull(MetadataPathMapper.resolveMetadataDir("Martian")); //$NON-NLS-1$
        assertNull(MetadataPathMapper.resolveMetadataDir(null));
    }
}
