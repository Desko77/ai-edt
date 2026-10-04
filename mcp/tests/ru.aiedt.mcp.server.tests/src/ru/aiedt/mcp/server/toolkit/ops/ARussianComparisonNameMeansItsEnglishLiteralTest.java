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
 * A Russian comparison name is stored as the literal its English name is stored as.
 * <p>
 * The platform names the comparison kinds in Russian and the model holds the English literals,
 * so the name a caller writes in Russian has to land on the same enumeration value, whatever way
 * the call is spelled.
 * </p>
 */
public class ARussianComparisonNameMeansItsEnglishLiteralTest
{
    private DcsWorkshopTool tool;

    /**
     * Builds a tool the schema calls run through.
     */
    @Before
    public void buildATool()
    {
        tool = new DcsWorkshopTool();
    }

    /**
     * Runs one schema operation against a schema object.
     *
     * @param schema the schema to write into
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return what the operation reports
     * @throws Exception if the operation refuses
     */
    private Object run(EObject schema, String op, String... keysAndValues) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    /**
     * Builds an empty composition schema holding one filtered dataset field.
     *
     * @param comparisonType the comparison the filter asks for
     * @return the schema
     * @throws Exception if a call refuses
     */
    private EObject schemaWithAFilter(String comparisonType) throws Exception
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        EObject schema = (EObject)built;
        run(schema, "add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run(schema, "add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run(schema, "add_filter", "field", "Сумма", "value", "1", "comparisonType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            comparisonType);
        return schema;
    }

    /**
     * The literal the one filter item's comparison kind is stored as.
     *
     * @param schema the schema holding the filter
     * @return the stored literal, or null when nothing is stored
     */
    private static String storedComparisonType(EObject schema)
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the call reached the settings", variants); //$NON-NLS-1$
        assertFalse("the settings are there", variants.isEmpty()); //$NON-NLS-1$
        EObject settings = (EObject)variants.get(0).eGet(
            variants.get(0).eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        Object filter = settings.eGet(settings.eClass().getEStructuralFeature("filter")); //$NON-NLS-1$
        assertNotNull("the filter is there", filter); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        assertNotNull("the filter has items", items); //$NON-NLS-1$
        assertFalse("the filter holds the one item", items.isEmpty()); //$NON-NLS-1$
        Object kind = items.get(0).eGet(items.get(0).eClass().getEStructuralFeature("comparisonType")); //$NON-NLS-1$
        return literalOf(kind);
    }

    /**
     * The literal an enumeration value is stored as.
     *
     * @param value the enumeration value, possibly null
     * @return its literal as the model spells it, or null when there is none
     */
    private static String literalOf(Object value)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            return String.valueOf(value.getClass().getMethod("getLiteral").invoke(value)); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException notAnEnumerator)
        {
            return String.valueOf(value);
        }
    }

    /**
     * {@code Больше} is stored exactly as {@code Greater} is.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aRussianNameIsStoredAsTheSameLiteralAsTheEnglishOne() throws Exception
    {
        EObject russian = schemaWithAFilter("Больше"); //$NON-NLS-1$
        EObject english = schemaWithAFilter("Greater"); //$NON-NLS-1$

        String storedFromRussian = storedComparisonType(russian);
        String storedFromEnglish = storedComparisonType(english);
        assertEquals("one kind, however it was named", storedFromEnglish, storedFromRussian); //$NON-NLS-1$
        assertEquals("and the kind is the one Greater names", "Greater", storedFromRussian); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The name is read ignoring case.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aRussianNameIsReadIgnoringCase() throws Exception
    {
        EObject schema = schemaWithAFilter("больше"); //$NON-NLS-1$

        assertEquals("Greater", storedComparisonType(schema)); //$NON-NLS-1$
    }

    /**
     * {@code ВСпискеПоИерархии} is stored as {@code InListByHierarchy}, the literal the model
     * knows.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void theListByHierarchyNameIsStoredAsItsModelLiteral() throws Exception
    {
        EObject russian = schemaWithAFilter("ВСпискеПоИерархии"); //$NON-NLS-1$
        EObject english = schemaWithAFilter("InListByHierarchy"); //$NON-NLS-1$

        String storedFromRussian = storedComparisonType(russian);
        assertEquals("InListByHierarchy", storedFromRussian); //$NON-NLS-1$
        assertEquals(storedComparisonType(english), storedFromRussian); //$NON-NLS-1$
    }

    /**
     * A text that is neither an English literal nor a Russian platform name is still refused.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aNameOutsideBothSetsIsStillRefused() throws Exception
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue(built instanceof EObject);
        EObject schema = (EObject)built;
        run(schema, "add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run(schema, "add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        try
        {
            run(schema, "add_filter", "field", "Сумма", "value", "1", "comparisonType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "ТочноРавно");
            fail("a name outside both sets must be refused"); //$NON-NLS-1$
        }
        catch (Exception refused)
        {
            assertTrue("the refusal names the allowed literals: " + refused.getMessage(), //$NON-NLS-1$
                String.valueOf(refused.getMessage()).contains("Allowed:")); //$NON-NLS-1$
        }
        assertEquals("the filter gained no item", 0, filterItemCount(schema)); //$NON-NLS-1$
    }

    /**
     * How many filter items the first variant's settings hold.
     *
     * @param schema the schema
     * @return the count, zero when the filter was never created
     */
    private static int filterItemCount(EObject schema)
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null || variants.isEmpty())
        {
            return 0;
        }
        EObject settings = (EObject)variants.get(0).eGet(
            variants.get(0).eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        Object filter = settings.eGet(settings.eClass().getEStructuralFeature("filter")); //$NON-NLS-1$
        if (filter == null)
        {
            return 0;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        return items == null ? 0 : items.size();
    }
}
