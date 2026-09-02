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
 * Nested schemas and the child datasets of a union.
 * <p>
 * These run against a real composition schema, not a stand-in: the factory behind them is
 * {@code DcsFactory.eINSTANCE}, which needs neither a workspace nor a project, so the suite can
 * build a schema and write into it exactly as the tool does. What is left to a stand is whether the
 * result reaches the file and whether the platform opens it.
 * </p>
 */
public class ASchemaHoldsSchemasInsideItTest
{
    private DcsWorkshopTool tool;
    private EObject schema;

    @Before
    public void buildASchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
    }

    private Object run(String op, String... keysAndValues) throws Exception
    {
        return tool.applyToSchemaForTest(op, params(keysAndValues), schema);
    }

    private static Map<String, String> params(String... keysAndValues)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }

    /**
     * The feature of that name, whatever case the model spells it in - this one is URL, not url.
     *
     * @param owner the object.
     * @param name the feature name, in any case.
     * @return the feature
     */
    private static org.eclipse.emf.ecore.EStructuralFeature featureNamed(EObject owner, String name)
    {
        for (org.eclipse.emf.ecore.EStructuralFeature f : owner.eClass()
            .getEAllStructuralFeatures())
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                return f;
            }
        }
        throw new AssertionError("no feature named " + name + " on " + owner.eClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private EList<EObject> nestedSchemas()
    {
        return BmDcsHelper.getEObjectList(schema, "getNestedSchemas"); //$NON-NLS-1$
    }

    // -- nested schemas ----------------------------------------------------

    @Test
    public void aNestedSchemaIsAddedWithASchemaOfItsOwn() throws Exception
    {
        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, nestedSchemas().size());
        EObject entry = nestedSchemas().get(0);
        assertEquals("Детали", entry.eGet(entry.eClass().getEStructuralFeature("name"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a nested schema with no schema inside it addresses nothing", //$NON-NLS-1$
            entry.eGet(entry.eClass().getEStructuralFeature("schema"))); //$NON-NLS-1$
    }

    @Test
    public void aNestedSchemaKeepsItsAddressAndItsTitle() throws Exception
    {
        run("add_nested_schema", "name", "Детали", "url", "Справочник.Валюты", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "title", "Расшифровка"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject entry = nestedSchemas().get(0);
        assertEquals("Справочник.Валюты", entry.eGet(featureNamed(entry, "url"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(entry.eGet(entry.eClass().getEStructuralFeature("title"))); //$NON-NLS-1$
    }

    @Test
    public void aSecondNestedSchemaOfTheSameNameIsRefused() throws Exception
    {
        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("add_nested_schema", "name", "детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a name already taken must be refused whatever its case"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1, nestedSchemas().size());
        }
    }

    @Test
    public void aNestedSchemaIsRemovedByName() throws Exception
    {
        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_nested_schema", "name", "Прочее"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("remove_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, nestedSchemas().size());
        EObject left = nestedSchemas().get(0);
        assertEquals("Прочее", left.eGet(left.eClass().getEStructuralFeature("name"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void removingANestedSchemaThatIsNotThereIsRefused() throws Exception
    {
        try
        {
            run("remove_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("removing what is not there is not a success"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Детали")); //$NON-NLS-1$
        }
    }

    // -- the children of a union -------------------------------------------

    @Test
    public void aUnionTakesChildDatasets() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("add_union_item", "dataSetName", "Объединение", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$
        run("add_union_item", "dataSetName", "Объединение", "name", "Второй", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 2 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> items = BmDcsHelper.getEObjectList(union, "getItems"); //$NON-NLS-1$
        assertEquals(2, items.size());
        assertEquals("Первый", //$NON-NLS-1$
            items.get(0).eGet(items.get(0).eClass().getEStructuralFeature("name"))); //$NON-NLS-1$
    }

    @Test
    public void aChildIsRemovedFromTheUnionThatHoldsIt() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_union_item", "dataSetName", "Объединение", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$
        run("add_union_item", "dataSetName", "Объединение", "name", "Второй", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 2 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        run("remove_union_item", "dataSetName", "Объединение", "name", "Первый"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> items = BmDcsHelper.getEObjectList(union, "getItems"); //$NON-NLS-1$
        assertEquals(1, items.size());
        assertEquals("Второй", //$NON-NLS-1$
            items.get(0).eGet(items.get(0).eClass().getEStructuralFeature("name"))); //$NON-NLS-1$
    }

    @Test
    public void aDatasetThatIsNotAUnionSaysSo() throws Exception
    {
        run("add_dataset", "name", "Запрос", "dataSetType", "Query"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_union_item", "dataSetName", "Запрос", "name", "Первый"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("only a union holds datasets"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue("the refusal must name the dataset and say what to do: " //$NON-NLS-1$
                + expected.getMessage(),
                expected.getMessage().contains("Запрос") //$NON-NLS-1$
                    && expected.getMessage().contains("Union")); //$NON-NLS-1$
        }
    }

    @Test
    public void aUnionThatIsNotThereIsRefusedByName()
    {
        try
        {
            run("add_union_item", "dataSetName", "Нет", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a dataset that does not exist is not a union"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void twoChildrenOfOneNameAreRefused() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_union_item", "dataSetName", "Объединение", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            run("add_union_item", "dataSetName", "Объединение", "name", "первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a name already taken inside the union must be refused"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, BmDcsHelper.getEObjectList(union, "getItems").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void removingAChildThatIsNotThereIsRefused() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("remove_union_item", "dataSetName", "Объединение", "name", "Первый"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("removing what is not there is not a success"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Первый")); //$NON-NLS-1$
        }
    }

    // -- a child a caller can actually finish -------------------------------

    @Test
    public void anObjectChildWithoutTheObjectItReadsIsRefused() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_union_item", "dataSetName", "Объединение", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "dataSetType", "Object"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a child nothing can finish must not be reported as added"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("dataObjectName")); //$NON-NLS-1$
            EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("and nothing must be left behind", //$NON-NLS-1$
                BmDcsHelper.getEObjectList(union, "getItems").isEmpty()); //$NON-NLS-1$
        }
    }

    @Test
    public void anObjectChildKeepsTheObjectItReads() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("add_union_item", "dataSetName", "Объединение", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dataSetType", "Object", "dataObjectName", "ТаблицаЗначений"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject child = BmDcsHelper.getEObjectList(union, "getItems").get(0); //$NON-NLS-1$
        assertEquals("ТаблицаЗначений", //$NON-NLS-1$
            child.eGet(featureNamed(child, "objectName"))); //$NON-NLS-1$
    }

    @Test
    public void aUnionInsideAUnionIsReachedByPath() throws Exception
    {
        run("add_dataset", "name", "Внешнее", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_union_item", "dataSetName", "Внешнее", "name", "Внутреннее", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$

        run("add_union_item", "dataSetName", "Внешнее.Внутреннее", "name", "Лист", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject outer = BmDcsHelper.findByNameInList(schema, "getDataSets", "Внешнее"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject inner = BmDcsHelper.getEObjectList(outer, "getItems").get(0); //$NON-NLS-1$
        EList<EObject> leaves = BmDcsHelper.getEObjectList(inner, "getItems"); //$NON-NLS-1$
        assertEquals("the leaf belongs to the inner union", 1, leaves.size()); //$NON-NLS-1$
        assertEquals("Лист", //$NON-NLS-1$
            leaves.get(0).eGet(leaves.get(0).eClass().getEStructuralFeature("name"))); //$NON-NLS-1$
        assertEquals("and the outer union still holds only the inner one", 1, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(outer, "getItems").size()); //$NON-NLS-1$
    }

    @Test
    public void aStepOfThePathThatIsNotThereIsRefusedByName() throws Exception
    {
        run("add_dataset", "name", "Внешнее", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_union_item", "dataSetName", "Внешнее.Нет", "name", "Лист", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a step that does not exist cannot be walked through"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Нет")); //$NON-NLS-1$
        }
    }

    @Test
    public void aQueryChildIsNotDisturbedByTheOwnerAddress() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        // objectName is what names the owner whose schema is being edited, and it is present on
        // every real call. A child dataset must not try to store it.
        run("add_union_item", "objectName", "Report.Продажи", "dataSetName", "Объединение", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "name", "Первый", "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, BmDcsHelper.getEObjectList(union, "getItems").size()); //$NON-NLS-1$
    }

    // -- writing inside a nested schema ------------------------------------

    /**
     * The schema inside the named nested entry, as the tool resolves it.
     *
     * @param name the nested schema.
     * @return its own schema
     */
    private EObject inside(String name)
    {
        EObject entry = BmDcsHelper.findByNameInList(schema, "getNestedSchemas", name); //$NON-NLS-1$
        assertNotNull("no nested schema named " + name, entry); //$NON-NLS-1$
        Object inner = entry.eGet(entry.eClass().getEStructuralFeature("schema")); //$NON-NLS-1$
        assertTrue("a nested schema must carry a schema of its own", inner instanceof EObject); //$NON-NLS-1$
        return (EObject)inner;
    }

    @Test
    public void aDatasetGoesIntoTheNestedSchemaThatWasNamed() throws Exception
    {
        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_dataset", "nestedSchemaName", "Детали", "name", "Внутренний", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dataSetType", "Query"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the dataset belongs to the nested schema", 1, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(inside("Детали"), "getDataSets").size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and not to the schema around it", //$NON-NLS-1$
            BmDcsHelper.getEObjectList(schema, "getDataSets").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aSchemaNestedTwiceIsReachedByPath() throws Exception
    {
        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_nested_schema", "nestedSchemaName", "Детали", "name", "Глубже"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        // Naming it the way a caller has to name it - the level below is not at the root.
        run("add_dataset", "nestedSchemaName", "Детали.Глубже", "name", "Самый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dataSetType", "Query"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject deep = inside("Детали"); //$NON-NLS-1$
        EObject deeper = (EObject)BmDcsHelper
            .findByNameInList(deep, "getNestedSchemas", "Глубже") //$NON-NLS-1$ //$NON-NLS-2$
            .eGet(featureNamed(BmDcsHelper.findByNameInList(deep, "getNestedSchemas", "Глубже"), //$NON-NLS-1$ //$NON-NLS-2$
                "schema")); //$NON-NLS-1$
        assertEquals("the dataset belongs to the schema two levels down", 1, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(deeper, "getDataSets").size()); //$NON-NLS-1$
        assertTrue("the level above it holds none", //$NON-NLS-1$
            BmDcsHelper.getEObjectList(deep, "getDataSets").isEmpty()); //$NON-NLS-1$
        assertTrue("and the root holds none", //$NON-NLS-1$
            BmDcsHelper.getEObjectList(schema, "getDataSets").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aNestedSchemaNamedThatIsNotThereIsRefused() throws Exception
    {
        try
        {
            run("add_dataset", "nestedSchemaName", "Нет", "name", "Внутренний"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("writing into a schema that is not there is not a success"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue("the schema around it must not have taken the dataset", //$NON-NLS-1$
                BmDcsHelper.getEObjectList(schema, "getDataSets").isEmpty()); //$NON-NLS-1$
        }
    }

    // -- what the dot may and may not do -----------------------------------

    @Test
    public void anAddressThatIsOnlyADotDoesNotSelectTheRoot() throws Exception
    {
        run("add_nested_schema", "name", "Жертва"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("remove_nested_schema", "nestedSchemaName", ".", "name", "Жертва"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a path of nothing must not resolve to the schema itself"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals("and nothing must have been removed", 1, nestedSchemas().size()); //$NON-NLS-1$
        }
    }

    @Test
    public void anAddressEndingInADotIsRefusedRatherThanTrimmed() throws Exception
    {
        run("add_dataset", "name", "Внешнее", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_union_item", "dataSetName", "Внешнее", "name", "Жертва", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            run("remove_union_item", "dataSetName", "Внешнее.", "name", "Жертва"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a trailing dot must not be dropped and act on the step before it"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Внешнее"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("and nothing must have been removed", 1, //$NON-NLS-1$
                BmDcsHelper.getEObjectList(union, "getItems").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void aNameThatWouldReadAsAPathIsRefusedWhenItIsMade() throws Exception
    {
        try
        {
            run("add_nested_schema", "name", "А.Б"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a name with a dot could not be addressed afterwards"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("А.Б")); //$NON-NLS-1$
            assertTrue(nestedSchemas().isEmpty());
        }
    }

    @Test
    public void aDatasetWhoseOwnNameHasADotStillAddressesItself() throws Exception
    {
        // add_dataset is older than the path rule and does not refuse such a name, so an address
        // is taken whole before it is read as a hierarchy.
        run("add_dataset", "name", "У.В", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("add_union_item", "dataSetName", "У.В", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "У.В"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, BmDcsHelper.getEObjectList(union, "getItems").size()); //$NON-NLS-1$
    }

    // -- a call that cannot be finished is not a call that worked ----------

    @Test
    public void aQueryChildWithNoQueryIsRefused() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            // No queryText on purpose - that absence is what this case is about.
            run("add_union_item", "dataSetName", "Объединение", "name", "Первый"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a query cannot be given to it afterwards, so it must be given now"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("queryText")); //$NON-NLS-1$
            EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "Объединение"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("and nothing half-built must be left behind", //$NON-NLS-1$
                BmDcsHelper.getEObjectList(union, "getItems").isEmpty()); //$NON-NLS-1$
        }
    }

    @Test
    public void anAdditionTellsTheWriteGuardsWhatToLookFor() throws Exception
    {
        Object outcome = run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("an addition has to carry what the export must contain: " + outcome, //$NON-NLS-1$
            outcome instanceof BmDcsHelper.Wrote);
        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)outcome;
        assertTrue(wrote.mustAppear, wrote.mustAppear.contains("Детали")); //$NON-NLS-1$
        assertEquals("and how many the collection came to hold", 1, wrote.countAfterWrite); //$NON-NLS-1$
        assertNotNull("a count means nothing without saying what was counted", //$NON-NLS-1$
            wrote.countScope);
    }

    @Test
    public void aUnionItemTellsTheWriteGuardsToo() throws Exception
    {
        run("add_dataset", "name", "Объединение", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        Object outcome = run("add_union_item", "dataSetName", "Объединение", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(outcome instanceof BmDcsHelper.Wrote);
        assertEquals(1, ((BmDcsHelper.Wrote)outcome).countAfterWrite);
    }

    // -- what the write guards are told -------------------------------------

    @Test
    public void whatTheGuardsLookForIsEscapedTheWayTheFileWritesIt() throws Exception
    {
        Object outcome = run("add_nested_schema", "name", "Отчет & Свод"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)outcome;
        assertTrue("the file writes an ampersand escaped, so the guard has to look for it that " //$NON-NLS-1$
            + "way: " + wrote.mustAppear, wrote.mustAppear.contains("&amp;")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and must not look for the raw character", //$NON-NLS-1$
            wrote.mustAppear.contains("& ")); //$NON-NLS-1$
    }

    @Test
    public void aRemovalReportsHowManyAreLeft() throws Exception
    {
        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_nested_schema", "name", "Прочее"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Object outcome = run("remove_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a removal that reports nothing cannot be checked against the file", //$NON-NLS-1$
            outcome instanceof BmDcsHelper.Wrote);
        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)outcome;
        assertEquals(1, wrote.countAfterWrite);
        assertNull("nothing has to appear for a removal", wrote.mustAppear); //$NON-NLS-1$
    }

    @Test
    public void aTitleIsCheckedByItsTextAndNotByItsCarrier() throws Exception
    {
        run("add_nested_schema", "name", "Детали", "title", "Расшифровка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EObject entry = nestedSchemas().get(0);
        Object title = entry.eGet(entry.eClass().getEStructuralFeature("title")); //$NON-NLS-1$
        assertNotNull(title);
        assertEquals("the text has to be in the carrier, not just the carrier present", //$NON-NLS-1$
            "Расшифровка", //$NON-NLS-1$
            ((EObject)title).eGet(featureNamed((EObject)title, "value"))); //$NON-NLS-1$
    }

    // -- an address that could mean two things ------------------------------

    @Test
    public void anAddressIsRefusedWhileANameCarriesTheSeparator() throws Exception
    {
        // add_dataset predates the rule and allows a dotted name, so both readings of "У.Х" exist
        // as soon as one is there: a dataset of that name, or Х inside У.
        run("add_dataset", "name", "У.В", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_union_item", "dataSetName", "У.Х", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("choosing one of two readings silently would write somewhere else"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("У.В")); //$NON-NLS-1$
        }
    }

    @Test
    public void anExactNameStillWinsOverThatRefusal() throws Exception
    {
        run("add_dataset", "name", "У.В", "dataSetType", "Union"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        // The name is there as written, so there is nothing to be ambiguous about.
        run("add_union_item", "dataSetName", "У.В", "name", "Первый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "queryText", "ВЫБРАТЬ 1 КАК Поле"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject union = BmDcsHelper.findByNameInList(schema, "getDataSets", "У.В"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, BmDcsHelper.getEObjectList(union, "getItems").size()); //$NON-NLS-1$
    }

    // -- the schema is otherwise untouched ---------------------------------

    @Test
    public void addingANestedSchemaLeavesTheDatasetsAlone() throws Exception
    {
        run("add_dataset", "name", "Запрос", "dataSetType", "Query"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("add_nested_schema", "name", "Детали"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, BmDcsHelper.getEObjectList(schema, "getDataSets").size()); //$NON-NLS-1$
        assertNull("a nested schema is not a dataset of the outer schema", //$NON-NLS-1$
            BmDcsHelper.findByNameInList(schema, "getDataSets", "Детали")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
