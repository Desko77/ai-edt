/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A new common template is a spreadsheet unless the caller already named another type.
 * <p>
 * Only {@code SpreadsheetDocument} is written as {@code Template.mxlx}. A text template is not.
 * </p>
 */
public class ACommonTemplateCarriesSpreadsheetContentTest
{
    @Test
    public void noDeclaredTypeIsASpreadsheet()
    {
        assertEquals("SpreadsheetDocument", ObjectOps.commonTemplateContentType(null)); //$NON-NLS-1$
        assertEquals("SpreadsheetDocument", //$NON-NLS-1$
            ObjectOps.commonTemplateContentType(new LinkedHashMap<String, String>()));
        assertTrue(ObjectOps.writesSpreadsheetFile("SpreadsheetDocument")); //$NON-NLS-1$
    }

    @Test
    public void aDeclaredTextTypeIsKeptAndWritesNoSpreadsheetFile()
    {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("templateType", "TextDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("TextDocument", ObjectOps.commonTemplateContentType(properties)); //$NON-NLS-1$
        assertFalse(ObjectOps.writesSpreadsheetFile("TextDocument")); //$NON-NLS-1$
        assertFalse(ObjectOps.writesSpreadsheetFile(null));
    }

    @Test
    public void anAliasIsWrittenToTheModelInTheModelsSpelling()
    {
        assertEquals("SpreadsheetDocument", //$NON-NLS-1$
            ObjectOps.valueToApply("CommonTemplate", "TemplateType", "spreadsheet")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("TextDocument", //$NON-NLS-1$
            ObjectOps.valueToApply("CommonTemplate", "templateType", "TextDocument")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("spreadsheet", //$NON-NLS-1$
            ObjectOps.valueToApply("Catalog", "templateType", "spreadsheet")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("spreadsheet", //$NON-NLS-1$
            ObjectOps.valueToApply("CommonTemplate", "comment", "spreadsheet")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aFileThatAlreadyStoodIsReportedAsKeptNotCreated()
    {
        assertEquals("templateContentKept", ObjectOps.contentOutcomeKey(true)); //$NON-NLS-1$
        assertEquals("templateContentCreated", ObjectOps.contentOutcomeKey(false)); //$NON-NLS-1$
    }

    @Test
    public void aSpreadsheetAliasAndACaseInsensitiveKeyResolveToTheSpreadsheet()
    {
        Map<String, String> alias = new LinkedHashMap<>();
        alias.put("TemplateType", "spreadsheet"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("SpreadsheetDocument", ObjectOps.commonTemplateContentType(alias)); //$NON-NLS-1$
        assertTrue(ObjectOps.writesSpreadsheetFile(ObjectOps.commonTemplateContentType(alias)));
    }
}
