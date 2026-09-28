/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * Which spelling of a template type the answer carries.
 * <p>
 * add_template reports the type the model holds on the template, not the one the caller asked for -
 * the two differ when an alias was resolved, and the whole point of reading it back is to say what
 * was actually installed. The readback used the Java constant name, so the answer said
 * {@code SPREADSHEET_DOCUMENT} where the model, the schema and every caller write
 * {@code SpreadsheetDocument}. Measured on EDT 2025.2.3: the literal is what {@code getByName}
 * answers to, and the constant name answers to nothing.
 * </p>
 */
public class TheReportedTemplateTypeIsTheModelsOwnSpellingTest
{
    @Test
    public void theReportedTypeIsTheSpellingTheModelAnswersTo()
    {
        assertEquals("SpreadsheetDocument", //$NON-NLS-1$
            TemplateOps.typeLiteralOf(TemplateType.SPREADSHEET_DOCUMENT));
        assertNotEquals("the Java constant is not what a caller writes", "SPREADSHEET_DOCUMENT", //$NON-NLS-1$ //$NON-NLS-2$
            TemplateOps.typeLiteralOf(TemplateType.SPREADSHEET_DOCUMENT));
    }

    @Test
    public void everyReportedTypeIsOneTheModelTakesBack()
    {
        for (TemplateType type : TemplateType.values())
        {
            String reported = TemplateOps.typeLiteralOf(type);
            assertEquals(type.getLiteral(), reported);
            assertEquals("the reported type has to be one the model resolves: " + reported, //$NON-NLS-1$
                reported, BmTemplateHelper.resolveTemplateTypeLiteral(reported));
        }
    }

    @Test
    public void noValueIsNoType()
    {
        assertNull(TemplateOps.typeLiteralOf(null));
    }
}
