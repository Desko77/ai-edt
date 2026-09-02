/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/**
 * Holds the schema workshop to checking the text it is about to write - and only that text.
 * <p>
 * <b>It used to decide by operation name, and the list of names fell behind the operations twice.</b>
 * {@code set_dataset_property property=query} writes a whole query through the generic setter and
 * was checked by nothing at all. Four expression writers - {@code set_calculated_field},
 * {@code set_total_field}, {@code add_user_field}, {@code set_user_field} - were missing, every one
 * of them the {@code set_} half of a pair whose {@code add_} half was present.
 * </p>
 * <p>
 * <b>And one operation checked text it was never going to store.</b> {@code add_total} composes the
 * aggregate into the expression before writing, so {@code expression=Amount} with
 * {@code aggregateFunction=NoSuchFunction} passed as a bare field name while
 * {@code NoSuchFunction(Amount)} went into the schema unchecked.
 * </p>
 * <p>
 * <b>Deciding by argument instead overshoots.</b> One schema serves all 63 operations, so a call
 * may carry arguments its operation never reads - a reused argument map is enough - and a stale
 * {@code queryText} beside an ordinary rename would refuse the rename. So the decision is made by
 * operation AND argument together, which is what the handler does.
 * </p>
 * <p>
 * <b>The census below is what keeps the classification from falling behind again.</b> A list that
 * must be extended by hand fails silently when it is not; this one fails loudly, here.
 * </p>
 */
public class WhatTheCallWritesIsWhatIsCheckedTest
{
    /** Operations that write a query. */
    private static final Set<String> WRITE_A_QUERY = new LinkedHashSet<>(Arrays.asList(
        "add_dataset", "add_union_item", "set_dataset_query", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "set_dataset_property")); //$NON-NLS-1$

    /** Operations that write a DCS expression. */
    private static final Set<String> WRITE_AN_EXPRESSION = new LinkedHashSet<>(Arrays.asList(
        "add_calculated_field", "set_calculated_field", "set_total_field", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "add_user_field", "set_user_field", "add_parameter", "set_parameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "add_total", "add_dataset_link", "set_dataset_link_property")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Everything else, named one by one on purpose.
     * <p>
     * Naming them is the point: an operation added tomorrow appears in neither set and this test
     * fails, so what it writes is decided rather than defaulted.
     * </p>
     */
    private static final Set<String> WRITE_NEITHER = new LinkedHashSet<>(Arrays.asList(
        "add_appearance", "add_chart", "add_data_source", "add_field_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "add_group_template", "add_nested_schema", "add_schema_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "add_template_cell", "add_template_row", "add_total_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "add_field", "add_filter", "add_filter_group", "add_grouping", "add_order", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "add_query_condition", "add_query_field", "add_settings_chart", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "add_settings_filter_group", "add_settings_order", //$NON-NLS-1$ //$NON-NLS-2$
        "add_settings_selected_field", "add_settings_table", "add_settings_variant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "add_variant", "clear_settings_selected_fields", "clone_settings_variant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "deselect_field", "move_parameter", "remove_appearance", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_calculated_field", "remove_conditional_appearance", "remove_data_source", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_dataset", "remove_dataset_field", "remove_dataset_link", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_parameter", "remove_query_condition", "remove_query_field", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_settings_filter", "remove_settings_item", "remove_settings_order", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_field_template", "remove_group_template", "remove_nested_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_schema_template", "remove_settings_selected_field", "remove_settings_variant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_total_field", "remove_total_template", "remove_union_item", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "remove_user_field", "rename_settings_variant", "select_field", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "set_data_set_field_appearance", "set_data_source_property", //$NON-NLS-1$ //$NON-NLS-2$
        "set_field_appearance", "set_output_param", //$NON-NLS-1$ //$NON-NLS-2$
        "set_output_parameter", "set_param_value", "set_settings_item_user_mode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "set_settings_parameter")); //$NON-NLS-1$

    @Test
    public void everyOperationIsClassifiedOneWayOrTheOther()
    {
        Set<String> census = new TreeSet<>();
        census.addAll(WRITE_A_QUERY);
        census.addAll(WRITE_AN_EXPRESSION);
        census.addAll(WRITE_NEITHER);
        Set<String> registered = new TreeSet<>(new DcsWorkshopTool().operationNamesForTest());

        Set<String> unclassified = new TreeSet<>(registered);
        unclassified.removeAll(census);
        assertTrue("operations nobody has said what they write: " + unclassified //$NON-NLS-1$
            + " - add each to WRITE_A_QUERY, WRITE_AN_EXPRESSION or WRITE_NEITHER, and to the" //$NON-NLS-1$
            + " WRITES map in DcsWorkshopTool if it writes something", //$NON-NLS-1$
            unclassified.isEmpty());

        Set<String> gone = new TreeSet<>(census);
        gone.removeAll(registered);
        assertTrue("classified here but no longer registered: " + gone, gone.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void theToolAgreesWithTheCensus()
    {
        for (String operation : WRITE_A_QUERY)
        {
            String written = DcsWorkshopTool.writesForTest(operation);
            assertTrue(operation + " is classified " + written, written.startsWith("QUERY_")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String operation : WRITE_AN_EXPRESSION)
        {
            String written = DcsWorkshopTool.writesForTest(operation);
            assertTrue(operation + " is classified " + written, written.startsWith("EXPRESSION")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String operation : WRITE_NEITHER)
        {
            assertEquals(operation, "NOTHING", DcsWorkshopTool.writesForTest(operation)); //$NON-NLS-1$
        }
    }

    @Test
    public void theGenericSetterWritingAQueryIsRecognised()
    {
        // The path that was checked by nothing: no queryText argument anywhere, the whole query
        // arriving as the value of a property named query.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("property", "query"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("value", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SELECT 1", //$NON-NLS-1$
            DcsWorkshopTool.queryThisCallWrites("set_dataset_property", call)); //$NON-NLS-1$
    }

    @Test
    public void theGenericSetterWritingSomethingElseIsLeftAlone()
    {
        // Same operation, ordinary property. Checking this as a query would refuse a rename.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("property", "name"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("value", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DcsWorkshopTool.queryThisCallWrites("set_dataset_property", call)); //$NON-NLS-1$
    }

    @Test
    public void theCarrierIsTheOneTheOperationActuallyWrites()
    {
        // A call may carry both. The generic setter writes value; checking the named argument
        // instead would pass a clean queryText and store a malformed value.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("property", "query"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("value", "SELECT malformed FROM"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("queryText", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SELECT malformed FROM", //$NON-NLS-1$
            DcsWorkshopTool.queryThisCallWrites("set_dataset_property", call)); //$NON-NLS-1$
    }

    @Test
    public void anArgumentTheOperationNeverReadsIsNotChecked()
    {
        // The other way this can be wrong. One schema serves every operation, so a leftover
        // queryText can ride along on a removal - and refusing the removal over text it will never
        // store is as wrong as storing text nothing checked.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("name", "Main"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("queryText", "SELECT malformed FROM"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DcsWorkshopTool.queryThisCallWrites("remove_dataset", call)); //$NON-NLS-1$

        Map<String, String> another = new LinkedHashMap<>();
        another.put("name", "Main"); //$NON-NLS-1$ //$NON-NLS-2$
        another.put("expression", "1 +"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DcsWorkshopTool.expressionsThisCallWrites("remove_dataset", another).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void everyExpressionWriterIsRecognisedUnderItsOwnArgument()
    {
        // Driven through the argument each one actually reads. Four of these were missing from the
        // old list of names, and two more - the link operations - were missing from the census that
        // replaced it, because they carry their expressions under names of their own and a search
        // for the word "expression" never reached them.
        for (String operation : WRITE_AN_EXPRESSION)
        {
            Map<String, String> call = new LinkedHashMap<>();
            if ("add_dataset_link".equals(operation)) //$NON-NLS-1$
            {
                call.put("sourceExpression", "Amount * 2"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if ("set_dataset_link_property".equals(operation)) //$NON-NLS-1$
            {
                call.put("property", "sourceExpression"); //$NON-NLS-1$ //$NON-NLS-2$
                call.put("value", "Amount * 2"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                call.put("expression", "Amount * 2"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            assertEquals(operation, Arrays.asList("Amount * 2"), //$NON-NLS-1$
                DcsWorkshopTool.expressionsThisCallWrites(operation, call));
        }
    }

    @Test
    public void bothEndsOfALinkAreChecked()
    {
        // A link constrains a join and can carry an expression at each end. Checking one and
        // storing two would leave half of every such call unchecked.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("sourceExpression", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("destinationExpression", "Total"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("Amount", "Total"), //$NON-NLS-1$ //$NON-NLS-2$
            DcsWorkshopTool.expressionsThisCallWrites("add_dataset_link", call)); //$NON-NLS-1$
    }

    @Test
    public void aLinkSetterWritingSomethingElseIsLeftAlone()
    {
        // The same generic setter reaching an ordinary property of the link.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("property", "sourceDataSet"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("value", "Main"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DcsWorkshopTool
            .expressionsThisCallWrites("set_dataset_link_property", call).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aTotalIsCheckedAsItWillBeStored()
    {
        // The composed text, not the argument. This is the whole of the third defect.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("expression", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("aggregateFunction", "NoSuchFunction"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("NoSuchFunction(Amount)"), //$NON-NLS-1$
            DcsWorkshopTool.expressionsThisCallWrites("add_total", call)); //$NON-NLS-1$
    }

    @Test
    public void anExpressionThatAlreadyCallsSomethingIsTakenAsItIs()
    {
        assertEquals("Sum(Amount)", //$NON-NLS-1$
            DcsWorkshopTool.totalExpressionOf("Sum(Amount)", "Maximum")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void withoutAnAggregateTheExpressionStandsAlone()
    {
        assertEquals("Amount", DcsWorkshopTool.totalExpressionOf("Amount", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Amount", DcsWorkshopTool.totalExpressionOf("Amount", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void onlyTheTotalComposes()
    {
        // The same two arguments on a neighbouring operation must not be composed: set_total_field
        // stores the expression verbatim, and inventing a call around it would check text that is
        // not written there either.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("expression", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("aggregateFunction", "Sum"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("Amount"), //$NON-NLS-1$
            DcsWorkshopTool.expressionsThisCallWrites("set_total_field", call)); //$NON-NLS-1$
    }

    @Test
    public void aLinkExpressionIsRecognisedByTheShapeOfItsName()
    {
        // The link carries more expressions than an add call can set - startExpression and
        // linkConditionExpression among them - and the generic setter reaches every one. Listing
        // the names known today would be one more list to fall behind.
        for (String property : new String[] {"sourceExpression", "destinationExpression", //$NON-NLS-1$ //$NON-NLS-2$
            "startExpression", "linkConditionExpression"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Map<String, String> call = new LinkedHashMap<>();
            call.put("property", property); //$NON-NLS-1$
            call.put("value", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(property, Arrays.asList("Amount"), //$NON-NLS-1$
                DcsWorkshopTool.expressionsThisCallWrites("set_dataset_link_property", call)); //$NON-NLS-1$
        }
    }
}
