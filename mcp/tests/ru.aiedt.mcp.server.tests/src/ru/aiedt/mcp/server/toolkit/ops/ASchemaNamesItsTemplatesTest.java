/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
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
 * The templates a schema names, their bodies, and what says a field or a grouping uses one.
 * <p>
 * Run against a real composition schema. A binding holds the name of its template as text, so
 * nothing in the model would notice a binding naming a template that is not there - which is why
 * the operations refuse it, and why that refusal is checked here.
 * </p>
 */
public class ASchemaNamesItsTemplatesTest
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
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    private EList<EObject> templates()
    {
        return BmDcsHelper.getEObjectList(schema, "getTemplates"); //$NON-NLS-1$
    }

    private EList<EObject> rowsOf(String templateName)
    {
        Object description = BmDcsHelper.findByNameInList(schema, "getTemplates", templateName); //$NON-NLS-1$
        assertNotNull("no template named " + templateName, description); //$NON-NLS-1$
        EObject body = (EObject)((EObject)description)
            .eGet(((EObject)description).eClass().getEStructuralFeature("template")); //$NON-NLS-1$
        assertNotNull("a template with no body draws nothing", body); //$NON-NLS-1$
        return BmDcsHelper.getEObjectList(body, "getItems"); //$NON-NLS-1$
    }

    // -- naming a template --------------------------------------------------

    @Test
    public void aTemplateIsCreatedWithABodyToFill() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, templates().size());
        assertTrue("the body has to be there, because nothing else creates it later", //$NON-NLS-1$
            rowsOf("Шапка").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aSecondTemplateOfTheSameNameIsRefused() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("add_schema_template", "name", "шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a name already taken must be refused whatever its case"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1, templates().size());
        }
    }

    @Test
    public void aTemplateIsRemovedByName() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_schema_template", "name", "Подвал"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Object outcome = run("remove_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, templates().size());
        assertEquals(1, ((BmDcsHelper.Wrote)outcome).countAfterWrite);
    }

    @Test
    public void removingATemplateThatIsNotThereIsRefused()
    {
        try
        {
            run("remove_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("removing what is not there is not a success"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Шапка")); //$NON-NLS-1$
        }
    }

    // -- the body -----------------------------------------------------------

    @Test
    public void rowsAndCellsGoIntoTheBodyOfTheTemplateNamed() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_schema_template", "name", "Подвал"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_cell", "schemaTemplateName", "Шапка", "field", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(1, rowsOf("Шапка").size()); //$NON-NLS-1$
        EObject row = rowsOf("Шапка").get(0); //$NON-NLS-1$
        assertEquals(1, BmDcsHelper.getEObjectList(row, "getCells").size()); //$NON-NLS-1$
        assertTrue("the other template must be untouched", rowsOf("Подвал").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCellShowsTheFieldItWasGiven() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_template_cell", "schemaTemplateName", "Шапка", "field", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EObject cell = BmDcsHelper.getEObjectList(rowsOf("Шапка").get(0), "getCells").get(0); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> items = BmDcsHelper.getEObjectList(cell, "getItem"); //$NON-NLS-1$
        assertEquals("a cell given a field has to hold it", 1, items.size()); //$NON-NLS-1$
        assertNotNull(items.get(0).eGet(items.get(0).eClass().getEStructuralFeature("value"))); //$NON-NLS-1$
    }

    @Test
    public void aCellWithNoFieldHoldsNothingAndIsStillACell() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_template_cell", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        EObject cell = BmDcsHelper.getEObjectList(rowsOf("Шапка").get(0), "getCells").get(0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BmDcsHelper.getEObjectList(cell, "getItem").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aCellWithNoRowToStandInIsRefused() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("add_template_cell", "schemaTemplateName", "Шапка", "field", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a cell needs a row, and the refusal has to say so"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("add_template_row")); //$NON-NLS-1$
        }
    }

    @Test
    public void aRowIndexOutsideTheBodyIsRefused() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("add_template_cell", "schemaTemplateName", "Шапка", "rowIndex", "7"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a row that is not there cannot hold a cell"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals("and no cell must have been added", 0, //$NON-NLS-1$
                BmDcsHelper.getEObjectList(rowsOf("Шапка").get(0), "getCells").size()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void aRowInATemplateThatIsNotThereIsRefused()
    {
        try
        {
            run("add_template_row", "schemaTemplateName", "Нет"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a template that is not there has no body"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Нет")); //$NON-NLS-1$
        }
    }

    // -- what uses a template ------------------------------------------------

    @Test
    public void aFieldIsSaidToBeDrawnWithATemplate() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> bindings = BmDcsHelper.getEObjectList(schema, "getFieldTemplates"); //$NON-NLS-1$
        assertEquals(1, bindings.size());
        assertEquals("Шапка", //$NON-NLS-1$
            bindings.get(0).eGet(bindings.get(0).eClass().getEStructuralFeature("template"))); //$NON-NLS-1$
    }

    @Test
    public void abindingNamingATemplateThatIsNotThereIsRefused() throws Exception
    {
        try
        {
            run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Нет"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("the name is held as text, so nothing else would ever notice"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Нет")); //$NON-NLS-1$
            assertTrue(BmDcsHelper.getEObjectList(schema, "getFieldTemplates").isEmpty()); //$NON-NLS-1$
        }
    }

    @Test
    public void aFieldIsSaidOnlyOnce() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a field drawn twice is a contradiction, not an addition"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1, BmDcsHelper.getEObjectList(schema, "getFieldTemplates").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void whatSaysAFieldIsDrawnCanBeTakenBack() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_field_template", "field", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(BmDcsHelper.getEObjectList(schema, "getFieldTemplates").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aGroupingAndItsHeaderAreSaidInSeparateCollections() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "header", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        // Same grouping, same absent type - but two collections, so two entries.

        assertEquals("the body of the grouping", 1, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(schema, "getGroupTemplates").size()); //$NON-NLS-1$
        assertEquals("and its header, which is a collection of its own", 1, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(schema, "getGroupHeaderTemplates").size()); //$NON-NLS-1$
    }

    @Test
    public void aGroupingKeepsTheTypeItWasGiven() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Header"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject binding = BmDcsHelper.getEObjectList(schema, "getGroupTemplates").get(0); //$NON-NLS-1$
        assertNotNull(
            binding.eGet(binding.eClass().getEStructuralFeature("templateType"))); //$NON-NLS-1$
    }

    @Test
    public void aGroupingIsTakenBackFromTheCollectionItWasPutIn() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "header", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            run("remove_group_template", "groupName", "ПоТоварам"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("it was put in the header collection, not the body one"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1,
                BmDcsHelper.getEObjectList(schema, "getGroupHeaderTemplates").size()); //$NON-NLS-1$
        }

        run("remove_group_template", "groupName", "ПоТоварам", "header", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(BmDcsHelper.getEObjectList(schema, "getGroupHeaderTemplates").isEmpty()); //$NON-NLS-1$
    }

    // -- a malformed argument is not an absent one --------------------------

    @Test
    public void aRowIndexThatIsNotAWholeNumberIsRefused() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("add_template_cell", "schemaTemplateName", "Шапка", "rowIndex", "0.5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("reading it as the default would put the cell in a row nobody named"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("0.5")); //$NON-NLS-1$
            assertTrue("and no cell must have been placed anywhere", //$NON-NLS-1$
                BmDcsHelper.getEObjectList(rowsOf("Шапка").get(1), "getCells").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void aFlagThatIsNeitherTrueNorFalseIsRefused() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "header", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            run("remove_group_template", "groupName", "ПоТоварам", "header", "treu"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a misspelt flag would have removed from the other collection"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals("the header binding must still be there", 1, //$NON-NLS-1$
                BmDcsHelper.getEObjectList(schema, "getGroupHeaderTemplates").size()); //$NON-NLS-1$
        }
    }

    // -- a grouping is drawn differently in different places -----------------

    @Test
    public void oneGroupingTakesATemplatePerAreaType() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Header"); //$NON-NLS-1$ //$NON-NLS-2$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Footer"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a header and a footer are two entries, not one repeated", 2, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(schema, "getGroupTemplates").size()); //$NON-NLS-1$
    }

    @Test
    public void theSameGroupingAndTypeTwiceIsStillRefused() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Header"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "templateType", "Header"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("the same grouping drawn the same way twice is a contradiction"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1, BmDcsHelper.getEObjectList(schema, "getGroupTemplates").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void oneTypeIsRemovedAndTheOtherIsLeft() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Header"); //$NON-NLS-1$ //$NON-NLS-2$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Footer"); //$NON-NLS-1$ //$NON-NLS-2$

        run("remove_group_template", "groupName", "ПоТоварам", "templateType", "Header"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> left = BmDcsHelper.getEObjectList(schema, "getGroupTemplates"); //$NON-NLS-1$
        assertEquals(1, left.size());
        assertEquals("Footer", String.valueOf( //$NON-NLS-1$
            left.get(0).eGet(left.get(0).eClass().getEStructuralFeature("templateType")))); //$NON-NLS-1$
    }

    // -- a template still drawn with cannot be taken away --------------------

    @Test
    public void aTemplateStillDrawingAFieldIsNotRemoved() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("remove_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("the binding would be left naming a template that is gone"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue("the refusal has to name what still draws with it: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains("Номенклатура")); //$NON-NLS-1$
            assertEquals(1, templates().size());
        }
    }

    @Test
    public void aTemplateNothingDrawsWithIsRemoved() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_field_template", "field", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("remove_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(templates().isEmpty());
    }

    @Test
    public void aBindingTellsTheGuardsWhatToLookFor() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Object outcome = run("add_field_template", "field", "Номенклатура", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$

        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)outcome;
        assertNotNull("the count is reported, not judged, so the text is the only check there is", //$NON-NLS-1$
            wrote.mustAppear);
        assertTrue(wrote.mustAppear, wrote.mustAppear.contains("Номенклатура")); //$NON-NLS-1$
    }

    @Test
    public void theUsageCheckReadsNamesTheWayTheLookupDoes() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field_template", "field", "Номенклатура", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            // The template is found whatever the case, so what uses it has to be found that way
            // too - otherwise the removal goes through and the binding is left naming nothing.
            run("remove_schema_template", "name", "шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("the binding still draws with it, whatever case the removal was written in"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1, templates().size());
            assertEquals(1, BmDcsHelper.getEObjectList(schema, "getFieldTemplates").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void anEntryCarryingTheDefaultIsItsOwnEntry() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Footer"); //$NON-NLS-1$ //$NON-NLS-2$

        // Naming no type is not a wildcard: this is a different entry from the footer.
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(2, BmDcsHelper.getEObjectList(schema, "getGroupTemplates").size()); //$NON-NLS-1$
    }

    @Test
    public void removingWithNoTypeTakesTheEntryCarryingTheDefault() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Footer"); //$NON-NLS-1$ //$NON-NLS-2$
        run("add_group_template", "groupName", "ПоТоварам", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        // The second was made without a type, so it carries the model's default - and that is
        // what naming no type means.
        run("remove_group_template", "groupName", "ПоТоварам"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        EList<EObject> left = BmDcsHelper.getEObjectList(schema, "getGroupTemplates"); //$NON-NLS-1$
        assertEquals(1, left.size());
        assertEquals("the footer must be the one left, not the one taken", "Footer", //$NON-NLS-1$ //$NON-NLS-2$
            String.valueOf(
                left.get(0).eGet(left.get(0).eClass().getEStructuralFeature("templateType")))); //$NON-NLS-1$
    }

    @Test
    public void anEmptyOptionalStillMeansItWasNotSent() throws Exception
    {
        run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_template_row", "schemaTemplateName", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Some clients fill every declared optional with "". Refusing that would break them, and
        // the empty string carries no other meaning here.
        run("add_template_cell", "schemaTemplateName", "Шапка", "rowIndex", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "field", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, BmDcsHelper.getEObjectList(rowsOf("Шапка").get(0), "getCells").size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- where two groupings cross ------------------------------------------

    @Test
    public void aCrossingOfTwoGroupingsIsDrawnWithATemplate() throws Exception
    {
        run("add_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> bindings =
            BmDcsHelper.getEObjectList(schema, "getTotalFieldsTemplates"); //$NON-NLS-1$
        assertEquals(1, bindings.size());
        assertEquals("ПоТоварам", //$NON-NLS-1$
            bindings.get(0).eGet(bindings.get(0).eClass().getEStructuralFeature("groupName1"))); //$NON-NLS-1$
        assertEquals("ПоСкладам", //$NON-NLS-1$
            bindings.get(0).eGet(bindings.get(0).eClass().getEStructuralFeature("groupName2"))); //$NON-NLS-1$
    }

    @Test
    public void theOtherCrossingOfTheSameTwoIsItsOwnEntry() throws Exception
    {
        run("add_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$

        // Same two groupings, but the second is drawn in its footer rather than its header - a
        // different crossing, and naming only the first would have made it look like the same one.
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType2", "Footer", "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(2, BmDcsHelper.getEObjectList(schema, "getTotalFieldsTemplates").size()); //$NON-NLS-1$
    }

    @Test
    public void theSameCrossingTwiceIsRefused() throws Exception
    {
        run("add_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType", "Footer", "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        try
        {
            run("add_total_template", "groupName", "потоварам", "groupName2", "поскладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "templateType", "Footer", "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("the same crossing drawn the same way is one entry, whatever the case"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertEquals(1,
                BmDcsHelper.getEObjectList(schema, "getTotalFieldsTemplates").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void oneCrossingIsTakenBackAndTheOtherIsLeft() throws Exception
    {
        run("add_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType2", "Footer", "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        run("remove_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType2", "Footer"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> left = BmDcsHelper.getEObjectList(schema, "getTotalFieldsTemplates"); //$NON-NLS-1$
        assertEquals(1, left.size());
    }

    @Test
    public void aCrossingNamingATemplateThatIsNotThereIsRefused()
    {
        try
        {
            run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "schemaTemplateName", "Нет"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("the name is held as text, so nothing else would notice"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertTrue(BmDcsHelper.getEObjectList(schema, "getTotalFieldsTemplates").isEmpty()); //$NON-NLS-1$
        }
    }

    @Test
    public void aTemplateStillDrawingACrossingIsNotRemoved() throws Exception
    {
        run("add_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            run("remove_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("the crossing would be left naming a template that is gone"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("ПоТоварам")); //$NON-NLS-1$
            assertEquals(1, templates().size());
        }
    }

    @Test
    public void aTypeSpeltTheWayTheModelDeclaresItNamesTheSameEntry() throws Exception
    {
        run("add_schema_template", "name", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The model declares its constants one way and prints them another. A caller using the
        // declared spelling must reach the same entry as one using the printed spelling.
        run("add_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType2", "OVERALL_FOOTER", "schemaTemplateName", "Итоги"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        run("remove_total_template", "groupName", "ПоТоварам", "groupName2", "ПоСкладам", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "templateType2", "OverallFooter"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("both spellings name one entry", //$NON-NLS-1$
            BmDcsHelper.getEObjectList(schema, "getTotalFieldsTemplates").isEmpty()); //$NON-NLS-1$
    }

    // -- what the write guards are told -------------------------------------

    @Test
    public void anAddedTemplateTellsTheGuardsWhatToLookFor() throws Exception
    {
        Object outcome = run("add_schema_template", "name", "Шапка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)outcome;
        assertTrue(wrote.mustAppear, wrote.mustAppear.contains("Шапка")); //$NON-NLS-1$
        assertEquals(1, wrote.countAfterWrite);
        assertEquals("templates", wrote.countScope); //$NON-NLS-1$
    }

    @Test
    public void aTemplateNamedLikeAPathIsRefused()
    {
        try
        {
            run("add_schema_template", "name", "А.Б"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a name with a dot could not be addressed afterwards"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertNull("and nothing must have been created", //$NON-NLS-1$
                BmDcsHelper.findByNameInList(schema, "getTemplates", "А.Б")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
