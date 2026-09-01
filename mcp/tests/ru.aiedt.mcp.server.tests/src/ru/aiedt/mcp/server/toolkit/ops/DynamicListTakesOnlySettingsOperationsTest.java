/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Which operations the schema workshop accepts against a form's dynamic list.
 * <p>
 * A list carries composition settings and no schema, so only the settings half of the catalog
 * applies to it. The set naming that half is written out by hand rather than derived from the
 * catalog, which is the point: a new settings operation has to be added to it deliberately, and an
 * operation that shapes a schema can never drift into it unnoticed.
 * </p>
 */
public class DynamicListTakesOnlySettingsOperationsTest
{
    /**
     * Word by word what a caller may aim at a list. Kept here in full so a change to the set shows
     * up as a change to this file.
     */
    private static final List<String> EXPECTED = Arrays.asList("add_appearance", "add_chart",
        "add_filter", "add_filter_group", "add_grouping", "add_order", "add_settings_chart",
        "add_settings_filter_group", "add_settings_order", "add_settings_selected_field",
        "add_settings_table", "add_user_field", "clear_settings_selected_fields", "deselect_field",
        "remove_appearance", "remove_conditional_appearance", "remove_settings_filter",
        "remove_settings_item", "remove_settings_order", "remove_settings_selected_field",
        "remove_user_field", "select_field", "set_output_param", "set_output_parameter",
        "set_param_value", "set_settings_item_user_mode", "set_settings_parameter",
        "set_user_field");

    @Test
    public void theSetIsExactlyWhatIsWrittenDown()
    {
        assertEquals("the accepted set changed - update this test alongside it", //$NON-NLS-1$
            new LinkedHashSet<>(EXPECTED), DcsWorkshopTool.SETTINGS_OPS);
    }

    @Test
    public void everyAcceptedOperationIsOneTheToolActuallyHas()
    {
        Set<String> catalog = new DcsWorkshopTool().operationNamesForTest();

        for (String op : DcsWorkshopTool.SETTINGS_OPS)
        {
            assertTrue("'" + op + "' is accepted on a dynamic list but is not an operation", //$NON-NLS-1$ //$NON-NLS-2$
                catalog.contains(op));
        }
    }

    @Test
    public void nothingThatShapesASchemaIsAccepted()
    {
        // A dynamic list has no datasets, no data sources, no schema parameters and no settings
        // variants. An operation reaching for any of those would find nothing and say so poorly.
        for (String op : Arrays.asList("add_dataset", "remove_dataset", "set_dataset_query", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "add_data_source", "add_parameter", "set_parameter", "remove_parameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "add_calculated_field", "add_total", "add_variant", "add_settings_variant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "clone_settings_variant", "remove_settings_variant", "add_dataset_link")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertFalse(op + " shapes a schema and must not be accepted on a dynamic list", //$NON-NLS-1$
                DcsWorkshopTool.SETTINGS_OPS.contains(op));
        }
    }

    @Test
    public void theSetIsNotTheWholeCatalog()
    {
        Set<String> catalog = new DcsWorkshopTool().operationNamesForTest();

        assertTrue("the catalog should be the larger of the two", //$NON-NLS-1$
            catalog.size() > DcsWorkshopTool.SETTINGS_OPS.size());
        assertFalse("help is not a mutation and cannot run against a list", //$NON-NLS-1$
            DcsWorkshopTool.SETTINGS_OPS.contains("help")); //$NON-NLS-1$
    }
}
