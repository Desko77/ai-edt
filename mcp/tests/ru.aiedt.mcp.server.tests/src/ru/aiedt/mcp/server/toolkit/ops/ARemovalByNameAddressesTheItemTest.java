/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * remove_settings_item addresses an item by number as before, and by name now.
 * <p>
 * A bracketed key that does not spell a number names an item: the removal matches it, ignoring
 * case, against the name, dataPath, field, parameter or filter left value the item carries.
 * Several items of one name are refused with their positions, so the removal never takes another.
 * </p>
 */
public class ARemovalByNameAddressesTheItemTest
{
    private DcsWorkshopTool tool;

    private EObject schema;

    /**
     * Builds an empty composition schema.
     */
    @Before
    public void buildASchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
    }

    /**
     * Runs one schema operation.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return what the operation reports
     * @throws Exception if the operation refuses
     */
    private Object run(String op, String... keysAndValues) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    /**
     * The settings of the first variant, which these operations create.
     *
     * @return the settings object
     */
    private EObject settings()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the call reached the settings", variants); //$NON-NLS-1$
        assertFalse("the settings are there", variants.isEmpty()); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull(settings);
        return (EObject)settings;
    }

    /**
     * How many items a settings container holds.
     *
     * @param settings the settings
     * @param feature the container feature, such as {@code filter}
     * @return the item count, zero when the container was never created
     */
    private static int itemCount(EObject settings, String feature)
    {
        Object container = settings.eGet(settings.eClass().getEStructuralFeature(feature));
        if (container == null)
        {
            return 0;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        return items == null ? 0 : items.size();
    }

    /**
     * How many filter items the settings hold.
     *
     * @return the count
     */
    private int filterCount()
    {
        return itemCount(settings(), "filter"); //$NON-NLS-1$
    }

    /**
     * The field path the item at the position of a container carries.
     *
     * @param settings the settings
     * @param feature the container feature
     * @param index the position
     * @param itemFeature the feature of the item that holds the field, {@code field} or
     *            {@code left}
     * @return the field's value as text, or null when it holds none
     */
    private static String fieldTextOf(EObject settings, String feature, int index,
        String itemFeature)
    {
        Object container = settings.eGet(settings.eClass().getEStructuralFeature(feature));
        assertNotNull("the container is there", container); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        assertNotNull("the container has items", items); //$NON-NLS-1$
        EObject item = items.get(index);
        Object field = item.eGet(item.eClass().getEStructuralFeature(itemFeature));
        if (!(field instanceof EObject))
        {
            return null;
        }
        EObject carrier = (EObject)field;
        Object value = carrier.eGet(carrier.eClass().getEStructuralFeature("value")); //$NON-NLS-1$
        return value != null ? value.toString() : null;
    }

    /**
     * A numbered removal still takes exactly the item at that position.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aNumberedRemovalStillTakesTheItemAtThatPosition() throws Exception
    {
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_filter", "field", "Количество", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_settings_item", "itemPath", "Filter[1]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("one filter is left", 1, filterCount()); //$NON-NLS-1$
        assertEquals("and it is the first one", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$
            fieldTextOf(settings(), "filter", 0, "left")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A filter is taken by the field it filters.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aFilterIsTakenByTheFieldItFilters() throws Exception
    {
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_filter", "field", "Количество", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_settings_item", "itemPath", "Filter[Сумма]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("one filter is left", 1, filterCount()); //$NON-NLS-1$
        assertEquals("and it is the one on the other field", "Количество", //$NON-NLS-1$ //$NON-NLS-2$
            fieldTextOf(settings(), "filter", 0, "left")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The name matches ignoring case.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void theNameMatchesIgnoringCase() throws Exception
    {
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_settings_item", "itemPath", "Filter[сумма]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the filter is gone", 0, filterCount()); //$NON-NLS-1$
    }

    /**
     * A selected field is taken by the path it selects.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aSelectedFieldIsTakenByItsPath() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_field", "dataSetName", "Продажи", "name", "Количество"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Количество"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("remove_settings_item", "itemPath", "Selection[Количество]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("one selected field is left", 1, itemCount(settings(), "selection")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and it is the other one", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$
            fieldTextOf(settings(), "selection", 0, "field")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An order item is taken by the field it orders.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void anOrderItemIsTakenByItsField() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_settings_order", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("remove_settings_item", "itemPath", "Order[Сумма]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the order is empty", 0, itemCount(settings(), "order")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A settings parameter is taken by the parameter it holds.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aSettingsParameterIsTakenByItsName() throws Exception
    {
        run("add_parameter", "name", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_settings_item", "itemPath", "DataParameters[Период]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the data parameters are empty", 0, //$NON-NLS-1$
            itemCount(settings(), "dataParameters")); //$NON-NLS-1$
    }

    /**
     * A name may carry a dot: the dots inside brackets are the name's, not the path's.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aNameWithADotIsOneStepOfThePath() throws Exception
    {
        run("add_filter", "field", "ПараметрыДанных.Период", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_settings_item", "itemPath", "Filter[ПараметрыДанных.Период]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the filter is gone", 0, filterCount()); //$NON-NLS-1$
    }

    /**
     * Several items of one name are refused with their positions, and a numbered address still
     * takes one of them.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void severalItemsOfOneNameAreRefusedWithTheirPositions() throws Exception
    {
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_filter", "field", "Количество", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_filter", "field", "Сумма", "value", "2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("remove_settings_item", "itemPath", "Filter[Сумма]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("two filters on one field must be refused by name"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names the positions: " + message, //$NON-NLS-1$
                message.contains("indices 0, 2")); //$NON-NLS-1$
        }
        assertEquals("nothing was removed by the refused call", 3, filterCount()); //$NON-NLS-1$

        run("remove_settings_item", "itemPath", "Filter[2]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the numbered address takes the one it named", 2, filterCount()); //$NON-NLS-1$
    }

    /**
     * A name nothing answers to is refused, and the collection is left alone.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aNameNothingAnswersToIsRefused() throws Exception
    {
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("remove_settings_item", "itemPath", "Filter[Несуществующее]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a name no item carries must be refused"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names the collection and the name: " + message, //$NON-NLS-1$
                message.contains("Filter") && message.contains("Несуществующее")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals("the filter is still there", 1, filterCount()); //$NON-NLS-1$
    }

    /**
     * A step without a key is refused.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aStepWithoutAKeyIsRefused() throws Exception
    {
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("remove_settings_item", "itemPath", "Filter"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a collection named without an item must be refused"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal asks for a number or a name: " + message, //$NON-NLS-1$
                message.contains("number or name")); //$NON-NLS-1$
        }
    }

    /**
     * The parameter schema spells both address forms out.
     */
    @Test
    public void theSchemaNamesBothAddressForms()
    {
        String schema = tool.getInputSchema();
        int itemPath = schema.indexOf("itemPath"); //$NON-NLS-1$
        assertTrue("the schema declares itemPath", itemPath >= 0); //$NON-NLS-1$
        String description = schema.substring(itemPath);
        assertTrue("the numeric form is spelled out: " + description, //$NON-NLS-1$
            description.contains("Structure[0]")); //$NON-NLS-1$
        assertTrue("the name form is spelled out: " + description, //$NON-NLS-1$
            description.contains("[Период]")); //$NON-NLS-1$
        assertTrue("the name form is named: " + description, //$NON-NLS-1$
            description.contains("by name")); //$NON-NLS-1$
    }
}
