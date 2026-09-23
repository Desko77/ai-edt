/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What a settings write reports about the settings it touched.
 * <p>
 * A composition setting that is switched on and carries nothing to act on - a filter comparing
 * against no value, a standard period with no dates - produces a report that answers a question
 * nobody asked. The write says so instead of refusing: the setting is written, and the answer
 * carries {@code settingsWarnings} beside the message.
 * </p>
 * <p>
 * Run against a composition schema built from {@code DcsFactory.eINSTANCE}, which needs no
 * workspace. What that cannot reach is the JSON answer the schema route builds - resolving a schema
 * by FQN needs a project - so the last acceptance point is checked where the field is put into the
 * answer rather than through a whole tool call.
 * </p>
 */
public class IncompleteSettingsAreWarnedAboutOnWriteTest
{
    /** The variant a finding is named after when the call wrote the schema's own settings. */
    private static final String DEFAULT_VARIANT = "default"; //$NON-NLS-1$

    /** The response field the findings are carried in. */
    private static final String WARNINGS_FIELD = "settingsWarnings"; //$NON-NLS-1$

    private DcsWorkshopTool tool;
    private EObject schema;

    @Before
    public void buildASchema()
    {
        rebuildSchema();
        tool = new DcsWorkshopTool();
    }

    private void rebuildSchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
    }

    // -----------------------------------------------------------------------
    // The seven acceptance points
    // -----------------------------------------------------------------------

    /**
     * A filter switched on, comparing by equality and given no value: one finding, naming the place
     * in the variant and the field, and the write itself is not refused.
     */
    @Test
    public void aFilterItemSwitchedOnAndComparingAgainstNothingIsReported() throws Exception
    {
        List<Map<String, Object>> found = warnings("add_filter", "field", "Организация", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the filter is written - the finding is a warning, not a refusal", //$NON-NLS-1$
            1, filterItems().size());
        assertOneEmptyFilterValue(found, DEFAULT_VARIANT, "Filter[0]", "Организация", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The same filter switched off: nothing is reported, and the neighbouring write still is. */
    @Test
    public void aFilterItemSwitchedOffIsNotReported() throws Exception
    {
        warnings("add_filter", "field", "Организация", "comparisonType", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        setFeature(filterItems().get(0), "use", Boolean.FALSE); //$NON-NLS-1$

        List<Map<String, Object>> found = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("the second element is written, so its absence from the findings is the " //$NON-NLS-1$
            + "switch and not a write that did nothing", 2, filterItems().size()); //$NON-NLS-1$
        assertTrue("a switched-off filter is how a quick filter waits to be switched on: " + found, //$NON-NLS-1$
            found.isEmpty());
    }

    /** The two comparisons that ask whether a field carries anything take no value, so they say nothing. */
    @Test
    public void theComparisonsThatAskWhetherAFieldCarriesAnythingAreNotReported() throws Exception
    {
        List<Map<String, Object>> filled = warnings("add_filter", "field", "Организация", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Filled"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("Filled is answered by the field itself: " + filled, filled.isEmpty()); //$NON-NLS-1$

        List<Map<String, Object>> notFilled = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "NotFilled"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("and so is NotFilled: " + notFilled, notFilled.isEmpty()); //$NON-NLS-1$
    }

    /** No right-hand side at all. */
    @Test
    public void anAbsentRightSideIsReported() throws Exception
    {
        assertOneEmptyFilterValue(
            warnings("add_filter", "field", "Организация", "comparisonType", "Equal"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            DEFAULT_VARIANT, "Filter[0]", "Организация", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** A right-hand side written as an empty text. */
    @Test
    public void anEmptyTextRightSideIsReported() throws Exception
    {
        assertOneEmptyFilterValue(
            warnings("add_filter", "field", "Организация", "comparisonType", "Equal", "value", ""), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            DEFAULT_VARIANT, "Filter[0]", "Организация", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** A right-hand side written as the undefined value. */
    @Test
    public void anUndefinedRightSideIsReported() throws Exception
    {
        assertOneEmptyFilterValue(
            warnings("add_filter", "field", "Организация", "comparisonType", "Equal", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "value", "undefined"), //$NON-NLS-1$ //$NON-NLS-2$
            DEFAULT_VARIANT, "Filter[0]", "Организация", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** A list comparison with nothing listed. */
    @Test
    public void anEmptyListRightSideIsReported() throws Exception
    {
        assertOneEmptyFilterValue(
            warnings("add_filter", "field", "Организация", "comparisonType", "InList"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            DEFAULT_VARIANT, "Filter[0]", "Организация", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** A list carried as a value that holds no entries. */
    @Test
    public void anEmptyArrayValueRightSideIsReported() throws Exception
    {
        warnings("add_filter", "field", "Организация", "comparisonType", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Object array = mcore("createFixedArrayValue"); //$NON-NLS-1$
        Assume.assumeTrue("the value carriers are not in this runtime", array != null); //$NON-NLS-1$
        rightOf(item(0)).add((EObject)array);

        List<Map<String, Object>> found = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertOneEmptyFilterValue(found, DEFAULT_VARIANT, "Filter[0]", "Организация", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** A list carried as a value that holds one entry: something to compare by, so nothing to say. */
    @Test
    public void aListWithAnEntryIsNotReported() throws Exception
    {
        warnings("add_filter", "field", "Организация", "comparisonType", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Object array = mcore("createFixedArrayValue"); //$NON-NLS-1$
        Object entry = array == null ? null : BmDcsHelper.createLiteralValue("Основной"); //$NON-NLS-1$
        Assume.assumeTrue("the value carriers are not in this runtime", //$NON-NLS-1$
            array != null && entry != null);
        EList<EObject> entries = BmDcsHelper.getEObjectList(array, "getValues"); //$NON-NLS-1$
        assertNotNull("a list value carries its entries", entries); //$NON-NLS-1$
        entries.add((EObject)entry);
        rightOf(item(0)).add((EObject)array);

        List<Map<String, Object>> found = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue("a list with an entry in it compares against something: " + found, found.isEmpty()); //$NON-NLS-1$
    }

    /** Several values on the right, every one of them empty: still nothing to compare by. */
    @Test
    public void severalEmptyValuesOnTheRightAreReported() throws Exception
    {
        warnings("add_filter", "field", "Организация", "comparisonType", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Object first = mcore("createUndefinedValue"); //$NON-NLS-1$
        Object second = mcore("createUndefinedValue"); //$NON-NLS-1$
        Assume.assumeTrue("the value carriers are not in this runtime", //$NON-NLS-1$
            first != null && second != null);
        rightOf(item(0)).add((EObject)first);
        rightOf(item(0)).add((EObject)second);

        List<Map<String, Object>> found = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertOneEmptyFilterValue(found, DEFAULT_VARIANT, "Filter[0]", "Организация", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** An empty value beside a real one: the real one is something to compare by. */
    @Test
    public void anEmptyValueBesideARealOneIsNotReported() throws Exception
    {
        warnings("add_filter", "field", "Организация", "comparisonType", "InList"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Object empty = mcore("createUndefinedValue"); //$NON-NLS-1$
        Object real = empty == null ? null : BmDcsHelper.createLiteralValue("Основной"); //$NON-NLS-1$
        Assume.assumeTrue("the value carriers are not in this runtime", //$NON-NLS-1$
            empty != null && real != null);
        rightOf(item(0)).add((EObject)empty);
        rightOf(item(0)).add((EObject)real);

        List<Map<String, Object>> found = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue("an empty value beside a real one compares against the real one: " + found, //$NON-NLS-1$
            found.isEmpty());
    }

    /** A standard period switched on with both dates empty. */
    @Test
    public void aStandardPeriodWithNoDatesIsReported() throws Exception
    {
        buildAStandardPeriod();

        List<Map<String, Object>> found = warnings("add_filter", "field", "Организация", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertOneEmptyPeriod(found, DEFAULT_VARIANT, "DataParameters[0]", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The same period spelled as a named period the platform resolves: no dates are needed. */
    @Test
    public void aStandardPeriodNamedAnotherWayIsNotReported() throws Exception
    {
        EObject period = (EObject)buildAStandardPeriod();
        setEnum(period, "variant", "ThisMonth"); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> found = warnings("add_filter", "field", "Организация", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue("the period carries a restriction of its own, so the dates are not missing: " //$NON-NLS-1$
            + found, found.isEmpty());
    }

    /** The same period with a date on one end. */
    @Test
    public void aStandardPeriodWithADateIsNotReported() throws Exception
    {
        EObject period = (EObject)buildAStandardPeriod();
        setFeature(period, "startDate", mcoreDate("2026-01-01")); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> found = warnings("add_filter", "field", "Организация", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue("a period told where it starts is restricted to something: " + found, //$NON-NLS-1$
            found.isEmpty());
    }

    /** A period entry that is switched off, like any other setting. */
    @Test
    public void aSwitchedOffPeriodEntryIsNotReported() throws Exception
    {
        buildAStandardPeriod();
        setFeature(dataParameterEntries().get(0), "use", Boolean.FALSE); //$NON-NLS-1$

        List<Map<String, Object>> found = warnings("add_filter", "field", "Организация", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue("a switched-off parameter reaches nothing: " + found, found.isEmpty()); //$NON-NLS-1$
    }

    /** A schema with nothing to report adds no field to the answer. */
    @Test
    public void anAnswerWithNothingToReportCarriesNoSettingsWarningsField()
    {
        Map<String, Object> tags = new LinkedHashMap<>();
        DcsWorkshopTool.attachSettingsWarnings(tags, new ArrayList<>());

        assertFalse("a complete variant adds no field to the schema route's answer", //$NON-NLS-1$
            tags.containsKey(WARNINGS_FIELD));
        DcsWorkshopTool.attachSettingsWarnings(tags, null);
        assertFalse("and neither does a call that recorded nothing at all", //$NON-NLS-1$
            tags.containsKey(WARNINGS_FIELD));

        ToolResult empty = DcsWorkshopTool.withSettingsWarnings(ToolResult.success(), new ArrayList<>());
        assertFalse("the list route answers the same way: " + empty.toJson(), //$NON-NLS-1$
            empty.toJson().contains(WARNINGS_FIELD));

        List<Map<String, Object>> found = new ArrayList<>();
        found.add(new LinkedHashMap<>(Collections.singletonMap("kind", "emptyFilterValue"))); //$NON-NLS-1$ //$NON-NLS-2$
        DcsWorkshopTool.attachSettingsWarnings(tags, found);
        assertEquals("a finding is the field, as read", found, tags.get(WARNINGS_FIELD)); //$NON-NLS-1$
        assertTrue("and it reaches the list route's answer too", //$NON-NLS-1$
            DcsWorkshopTool.withSettingsWarnings(ToolResult.success(), found).toJson()
                .contains(WARNINGS_FIELD));
    }

    /** The whole variant is read: a finding beside the element written is named as well. */
    @Test
    public void theWholeVariantIsReadNotOnlyTheElementWritten() throws Exception
    {
        warnings("add_filter", "field", "Организация", "comparisonType", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        List<Map<String, Object>> found = warnings("add_filter", "field", "Склад", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonType", "Equal", "value", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertOneEmptyFilterValue(found, DEFAULT_VARIANT, "Filter[0]", "Организация", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("both elements are there: the one written now is clean, the earlier one is not", //$NON-NLS-1$
            2, filterItems().size());
    }

    /** A variant written by name is the one read, and the one the finding is named after. */
    @Test
    public void aFindingInANamedVariantIsReportedUnderThatName() throws Exception
    {
        warnings("add_settings_variant", "name", "Сводный"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        warnings("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        // No operation writes into a named variant's filter: the default settings are what a filter
        // call reaches. The item is put in by the model, and the call that triggers the reading
        // writes the same variant through its data parameter.
        filterItemsOf(settingsOf("Сводный")).add(anEmptyFilterItem("Организация")); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> found = warnings("set_settings_parameter", "name", "Период", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "value", "2026-01-01", "variantName", "Сводный"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertOneEmptyFilterValue(found, "Сводный", "Filter[0]", "Организация", "Equal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // -----------------------------------------------------------------------
    // Driving the tool and reading the model
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> warnings(String op, String... keysAndValues) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.settingsWarningsForTest(op, params, schema);
    }

    /** The settings of the first variant, which is what a call without variantName writes. */
    private EObject defaultSettings()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the settings the write reached should be there", variants); //$NON-NLS-1$
        assertEquals("the schema should carry one variant by now", 1, variants.size()); //$NON-NLS-1$
        Object settings = variants.get(0).eGet(
            variants.get(0).eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("a variant carries settings", settings); //$NON-NLS-1$
        return (EObject)settings;
    }

    /** The settings of the variant with that name. */
    private EObject settingsOf(String variantName)
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the schema should carry variants by now", variants); //$NON-NLS-1$
        for (EObject variant : variants)
        {
            Object name = variant.eGet(variant.eClass().getEStructuralFeature("name")); //$NON-NLS-1$
            if (variantName.equals(String.valueOf(name)))
            {
                Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
                assertNotNull("the named variant carries settings", settings); //$NON-NLS-1$
                return (EObject)settings;
            }
        }
        throw new AssertionError("no variant named '" + variantName + "' was found"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private EList<EObject> filterItems()
    {
        return filterItemsOf(defaultSettings());
    }

    private EObject item(int index)
    {
        EList<EObject> items = filterItems();
        assertTrue("the filter should carry an item at " + index, items.size() > index); //$NON-NLS-1$
        return items.get(index);
    }

    private EList<EObject> dataParameterEntries()
    {
        EObject settings = defaultSettings();
        Object container = settings.eGet(
            settings.eClass().getEStructuralFeature("dataParameters")); //$NON-NLS-1$
        assertNotNull("setting a parameter creates its container", container); //$NON-NLS-1$
        EList<EObject> entries = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        assertNotNull("the container carries entries", entries); //$NON-NLS-1$
        return entries;
    }

    /** The items of a settings object's filter, the container made when it carries none. */
    private static EList<EObject> filterItemsOf(EObject settings)
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(filterOf(settings), "getItems"); //$NON-NLS-1$
        assertNotNull("a filter carries items", items); //$NON-NLS-1$
        return items;
    }

    /** The filter of a settings object, created when it carries none. */
    private static EObject filterOf(EObject settings)
    {
        EStructuralFeature feature = settings.eClass().getEStructuralFeature("filter"); //$NON-NLS-1$
        assertNotNull("a settings object carries a filter property", feature); //$NON-NLS-1$
        EObject filter = (EObject)settings.eGet(feature);
        if (filter == null)
        {
            filter = (EObject)BmDcsHelper.createElement("createDataCompositionFilter"); //$NON-NLS-1$
            assertNotNull("the model builds a filter container", filter); //$NON-NLS-1$
            settings.eSet(feature, filter);
        }
        return filter;
    }

    /** A filter item comparing a field for equality against nothing. */
    private static EObject anEmptyFilterItem(String field)
    {
        Object created = BmDcsHelper.createElement("createDataCompositionFilterItem"); //$NON-NLS-1$
        assertNotNull("the model builds a filter item", created); //$NON-NLS-1$
        EObject item = (EObject)created;
        Object asField = BmDcsHelper.createDataCompositionField(field);
        assertNotNull("the model builds a field", asField); //$NON-NLS-1$
        EStructuralFeature left = item.eClass().getEStructuralFeature("left"); //$NON-NLS-1$
        assertNotNull("a filter item carries a left-hand side", left); //$NON-NLS-1$
        item.eSet(left, asField);
        return item;
    }

    private static EList<EObject> rightOf(EObject item)
    {
        EList<EObject> right = BmDcsHelper.getEObjectList(item, "getRight"); //$NON-NLS-1$
        assertNotNull("a filter item carries a right-hand side", right); //$NON-NLS-1$
        return right;
    }

    /**
     * Sets a data parameter and leaves a standard period in its place.
     * <p>
     * No operation writes a standard period: the entry is made by the tool and the carrier is put
     * in by the model, which is the state the check exists to read. The caller shapes the period it
     * returns and then runs the write that reads it.
     * </p>
     *
     * @return the period left in the entry's place
     */
    private Object buildAStandardPeriod() throws Exception
    {
        warnings("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        warnings("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

        Object carrier = mcore("createStandardPeriodValue"); //$NON-NLS-1$
        Object period = mcore("createStandardPeriod"); //$NON-NLS-1$
        Assume.assumeTrue("the value carriers are not in this runtime", //$NON-NLS-1$
            carrier != null && period != null);
        setFeature((EObject)carrier, "value", period); //$NON-NLS-1$

        EList<EObject> entries = dataParameterEntries();
        assertTrue("setting the parameter should have made one entry", !entries.isEmpty()); //$NON-NLS-1$
        EList<EObject> values = BmDcsHelper.getEObjectList(entries.get(0), "getValues"); //$NON-NLS-1$
        assertNotNull("a data parameter entry carries values", values); //$NON-NLS-1$
        values.clear();
        values.add((EObject)carrier);
        return period;
    }

    // -----------------------------------------------------------------------
    // The model, one property at a time
    // -----------------------------------------------------------------------

    private static void setFeature(EObject target, String feature, Object value)
    {
        EStructuralFeature f = target.eClass().getEStructuralFeature(feature);
        assertNotNull("the model has no property '" + feature + "'", f); //$NON-NLS-1$ //$NON-NLS-2$
        target.eSet(f, value);
    }

    private static void setEnum(EObject target, String feature, String spelling)
    {
        String error = BmDcsHelper.setProperty(target, feature, spelling);
        assertNull("the model refused '" + spelling + "' for " + feature + ": " + error, error); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** An element of the model's own value carriers, which no schema factory makes. */
    private static Object mcore(String creator)
    {
        Object factory = BmDcsHelper.getMcoreFactory();
        if (factory == null)
        {
            return null;
        }
        try
        {
            return factory.getClass().getMethod(creator).invoke(factory);
        }
        catch (Exception noSuchCarrier)
        {
            return null;
        }
    }

    /** A date in the form the model reads and prints. */
    private static Object mcoreDate(String written) throws Exception
    {
        Class<?> dateClass = Class.forName("com._1c.g5.v8.dt.mcore.util.Date", false, //$NON-NLS-1$
            BmDcsHelper.class.getClassLoader());
        Object date = dateClass.getMethod("fromString", String.class).invoke(null, written); //$NON-NLS-1$
        assertNotNull("the model should read the date " + written, date); //$NON-NLS-1$
        return date;
    }

    // -----------------------------------------------------------------------
    // What a finding has to say
    // -----------------------------------------------------------------------

    private static void assertOneEmptyFilterValue(List<Map<String, Object>> found, String variant,
        String path, String field, String comparisonType)
    {
        assertEquals("one finding, not more: " + found, 1, found.size()); //$NON-NLS-1$
        Map<String, Object> warning = found.get(0);
        assertEquals("the finding names the variant it was read in", variant, warning.get("variant")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and the place in it", path, warning.get("path")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the kind of setting it is", "emptyFilterValue", warning.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("and the field it compares", field, warning.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("with the comparison that reads a value", comparisonType, //$NON-NLS-1$
            warning.get("comparisonType")); //$NON-NLS-1$
    }

    private static void assertOneEmptyPeriod(List<Map<String, Object>> found, String variant,
        String path, String parameter)
    {
        assertEquals("one finding, not more: " + found, 1, found.size()); //$NON-NLS-1$
        Map<String, Object> warning = found.get(0);
        assertEquals("the finding names the variant it was read in", variant, warning.get("variant")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and the place in it", path, warning.get("path")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the kind of setting it is", "emptyPeriod", warning.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("and the parameter carrying it", parameter, warning.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
