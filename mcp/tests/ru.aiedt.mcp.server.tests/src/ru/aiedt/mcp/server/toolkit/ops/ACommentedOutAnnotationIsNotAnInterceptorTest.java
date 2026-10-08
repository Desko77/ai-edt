/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;

import org.junit.Test;

/**
 * An annotation commented out is not an interceptor the extension carries.
 * <p>
 * The pattern used to read the annotation wherever it stood on the line, so a commented-out one -
 * {@code // &Перед("...")} above a live procedure - bound that procedure and reported an
 * interceptor the extension does not apply. The annotation is now anchored to the start of its
 * line, where a comment marker in front of it keeps it from matching.
 * </p>
 */
public class ACommentedOutAnnotationIsNotAnInterceptorTest
{
    private static final String COMMENTED_OUT = "// &Перед(\"Catalog.ПередЗаписью\")\r\n" //$NON-NLS-1$
        + "Процедура Расш_Обработчик()\r\n" //$NON-NLS-1$
        + "КонецПроцедуры\r\n"; //$NON-NLS-1$

    private static final String LIVE = "&Перед(\"Catalog.ПередЗаписью\")\r\n" //$NON-NLS-1$
        + "Процедура Расш_Обработчик()\r\n" //$NON-NLS-1$
        + "КонецПроцедуры\r\n"; //$NON-NLS-1$

    /** A commented-out annotation above a live procedure binds nothing. */
    @Test
    public void aCommentedOutAnnotationBindsNothing()
    {
        Matcher m = ListInterceptorsTool.ANNOTATION_PATTERN.matcher(COMMENTED_OUT);
        assertFalse(m.find());
    }

    /** The same annotation uncommented, on a line of its own, binds the procedure below it. */
    @Test
    public void theSameAnnotationUncommentedBindsTheProcedure()
    {
        Matcher m = ListInterceptorsTool.ANNOTATION_PATTERN.matcher(LIVE);
        assertTrue(m.find());
        assertEquals("Перед", m.group(1)); //$NON-NLS-1$
        assertEquals("Catalog.ПередЗаписью", m.group(2)); //$NON-NLS-1$
        assertEquals("Расш_Обработчик", m.group(3)); //$NON-NLS-1$
    }

    /** An indented live annotation is still the annotation of the procedure below it. */
    @Test
    public void anIndentedAnnotationStillBinds()
    {
        Matcher m = ListInterceptorsTool.ANNOTATION_PATTERN.matcher(
            "    &После(\"Catalog.ПослеЗаписи\")\r\nПроцедура Расш_После()\r\n"); //$NON-NLS-1$
        assertTrue(m.find());
        assertEquals("Расш_После", m.group(3)); //$NON-NLS-1$
    }
}
