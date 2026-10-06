/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * An empty picture reference {@code ref="v8ui:/"} in Template.mxlx is an export finding.
 * <p>
 * The platform refuses to load that reference. A reference that names a picture, including one
 * whose name starts with a slash, is not this finding.
 * </p>
 */
public class AnEmptyPictureReferenceIsAnExportFindingTest
{
    private static final String FILE = "src/CommonTemplates/Print/Template.mxlx"; //$NON-NLS-1$

    @Test
    public void emptyPictureReferencesAreOneFindingThatNamesTheFileAndTheCount()
    {
        String content = "<pictures>\n" //$NON-NLS-1$
            + "\t<picture ref=\"v8ui:/\"/>\n" //$NON-NLS-1$
            + "\t<picture ref = \"v8ui:/\"/>\n" //$NON-NLS-1$
            + "</pictures>\n"; //$NON-NLS-1$

        List<Map<String, Object>> findings = ValidateForExportTool.mxlxFindings(content, FILE,
            "CommonTemplate.Print"); //$NON-NLS-1$

        assertEquals(1, findings.size());
        Map<String, Object> finding = findings.get(0);
        assertEquals("mxlx-empty-picture-ref", finding.get("check")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ERROR", finding.get("severity")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FILE, finding.get("file")); //$NON-NLS-1$
        assertEquals("CommonTemplate.Print", finding.get("fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        String message = String.valueOf(finding.get("message")); //$NON-NLS-1$
        assertTrue(message, message.contains("2 picture reference")); //$NON-NLS-1$
        assertTrue(message, message.contains("ref=\"v8ui:/\"")); //$NON-NLS-1$
        assertEquals(2, ValidateForExportTool.emptyPictureRefCount(content));
    }

    @Test
    public void aSingleQuotedEmptyReferenceIsCountedToo()
    {
        String content = "<picture ref='v8ui:/'/><picture ref = 'v8ui:/'/>" //$NON-NLS-1$
            + "<picture ref=\"v8ui:/\"/><picture ref='v8ui:/Foo'/>"; //$NON-NLS-1$

        assertEquals(3, ValidateForExportTool.emptyPictureRefCount(content));
        assertEquals(1, ValidateForExportTool.mxlxFindings(content, FILE, null).size());
    }

    @Test
    public void aCommentedReferenceAndAnotherAttributeAreNotRead()
    {
        String content = "<!-- <picture ref=\"v8ui:/\"/> -->" //$NON-NLS-1$
            + "<picture href=\"v8ui:/\"/><link ref=\"v8ui:/\"/>" //$NON-NLS-1$
            + "<picture t=\"false\" ref=\"v8ui:/\"/>"; //$NON-NLS-1$

        assertEquals(1, ValidateForExportTool.emptyPictureRefCount(content));
    }

    @Test
    public void aNamedPictureReferenceIsNotAnEmptyOne()
    {
        String content = "<picture ref=\"v8ui:SomePicture\"/>" //$NON-NLS-1$
            + "<picture ref=\"v8ui:/Foo\"/>" //$NON-NLS-1$
            + "<picture ref=\"v8ui:ДокументЗаписанКоннекторЛево\"/>"; //$NON-NLS-1$

        assertTrue(ValidateForExportTool.mxlxFindings(content, FILE, null).isEmpty());
        assertEquals(0, ValidateForExportTool.emptyPictureRefCount(content));
        assertEquals(0, ValidateForExportTool.emptyPictureRefCount(null));
        assertEquals(0, ValidateForExportTool.emptyPictureRefCount("")); //$NON-NLS-1$
    }
}
