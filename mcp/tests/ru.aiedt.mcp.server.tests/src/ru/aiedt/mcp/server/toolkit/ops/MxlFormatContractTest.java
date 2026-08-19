/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Covers what {@code format_cells} promises before it reaches a workspace.
 * <p>
 * The refusals are the part worth pinning. A formatter that quietly does nothing is the worst of the
 * three outcomes: it reports success, the template is unchanged, and whoever asked goes looking for
 * the reason somewhere else entirely.
 * </p>
 */
public class MxlFormatContractTest
{
    private final MxlWorkshopTool tool = new MxlWorkshopTool();

    /** Every formatting property is declared, or a client cannot pass it. */
    @Test
    public void everyFormattingPropertyIsDeclared()
    {
        String schema = tool.getInputSchema();

        for (String argument : new String[] {"textPlacement", "textOrientation", "rowHeight", //$NON-NLS-1$
            "autoColumnWidth", "columnWidth", "columnWidthWeight"}) //$NON-NLS-1$
        {
            assertTrue("undeclared argument " + argument, schema.contains('"' + argument + '"')); //$NON-NLS-1$
        }
    }

    /** The operation is named in the description, or an agent will not find it. */
    @Test
    public void theOperationIsNamedWhereOperationsAreListed()
    {
        assertTrue(tool.getDescription().contains("format_cells")); //$NON-NLS-1$
    }

    /**
     * A call that would change nothing is refused rather than answered with success.
     * <p>
     * Passing a range and no properties is a request with no content. Reporting success for it
     * would be the "empty answer taken for a successful one" this project keeps paying for.
     * </p>
     */
    @Test
    public void aCallThatWouldChangeNothingIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse("a request with no properties must not report success", //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
    }

    /** Without a range there is nothing to format, and it says which arguments are missing. */
    @Test
    public void aCallWithoutARangeIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("textPlacement", "wrap"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
    }
}
