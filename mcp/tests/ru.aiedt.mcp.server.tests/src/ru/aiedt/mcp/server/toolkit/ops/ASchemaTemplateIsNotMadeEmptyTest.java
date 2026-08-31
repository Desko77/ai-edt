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
 * What happens when a data composition schema is asked for through the template operation.
 * <p>
 * The operation creates the template object and nothing inside it. For every other template type
 * that is right, because the content arrives through its own workshop afterwards. For a schema it
 * is not: the template came out empty, the call reported success, and the schema workshop then
 * answered that no schema existed by that FQN. Measured on the stand 28.08.
 * </p>
 */
public class ASchemaTemplateIsNotMadeEmptyTest
{
    @Test
    public void aSchemaIsRefusedRatherThanMadeEmpty()
    {
        String refusal = TemplateOps.refusalForSchemaTemplate("DataCompositionSchema"); //$NON-NLS-1$
        assertNotNull("a schema template must not be made here", refusal); //$NON-NLS-1$
    }

    @Test
    public void theRefusalNamesTheOperationThatMakesBothHalves()
    {
        String refusal = TemplateOps.refusalForSchemaTemplate("DataCompositionSchema"); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("dcs_workshop")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("create_schema")); //$NON-NLS-1$
    }

    @Test
    public void everyOtherTypeIsStillMadeHere()
    {
        assertNull(TemplateOps.refusalForSchemaTemplate("SpreadsheetDocument")); //$NON-NLS-1$
        assertNull(TemplateOps.refusalForSchemaTemplate("TextDocument")); //$NON-NLS-1$
        assertNull(TemplateOps.refusalForSchemaTemplate("HTMLDocument")); //$NON-NLS-1$
        assertNull(TemplateOps.refusalForSchemaTemplate("BinaryData")); //$NON-NLS-1$
    }

    @Test
    public void anUnresolvedTypeIsNotTreatedAsASchema()
    {
        assertNull(TemplateOps.refusalForSchemaTemplate(null));
        assertNull(TemplateOps.refusalForSchemaTemplate("")); //$NON-NLS-1$
    }
}
