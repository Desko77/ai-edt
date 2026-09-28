/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
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
 * What {@code add_field} does with the arguments beyond the field's name and dataset.
 * <p>
 * The title and a property/value pair are written on the field. A property the field does not
 * have, and a type that could not be applied, refuse the call before the field joins the dataset,
 * so nothing is written.
 * </p>
 */
public class ADataSetFieldTakesItsArgumentsTest
{
    private static final String DATA_SET = "Продажи"; //$NON-NLS-1$

    private EObject schema;

    private DcsWorkshopTool tool;

    @Before
    public void buildASchemaWithADataSet() throws Exception
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
        tool.applyToSchemaForTest("add_dataset", params("name", DATA_SET), schema); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theTitleIsWrittenOnTheField() throws Exception
    {
        tool.applyToSchemaForTest("add_field", //$NON-NLS-1$
            params("dataSetName", DATA_SET, "name", "Сумма", "title", "Сумма продажи"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EObject field = onlyField();
        assertNotNull("the title has to reach the field, not be dropped", //$NON-NLS-1$
            field.eGet(field.eClass().getEStructuralFeature("title"))); //$NON-NLS-1$
    }

    @Test
    public void aPropertyValuePairIsWrittenOnTheField() throws Exception
    {
        tool.applyToSchemaForTest("add_field", params("dataSetName", DATA_SET, "name", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "property", "presentationExpression", "value", "Формат(Сумма)"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        EObject field = onlyField();
        assertEquals("Формат(Сумма)", //$NON-NLS-1$
            field.eGet(field.eClass().getEStructuralFeature("presentationExpression"))); //$NON-NLS-1$
    }

    @Test
    public void aPropertyTheFieldDoesNotHaveIsRefusedAndNothingIsAdded() throws Exception
    {
        try
        {
            tool.applyToSchemaForTest("add_field", params("dataSetName", DATA_SET, "name", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "property", "такогоСвойстваНет", "value", "1"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("an unknown property was reported as written"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("такогоСвойстваНет")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains("Nothing was written")); //$NON-NLS-1$
        }
        assertEquals("the refused field must not stay in the dataset", 0, fields().size()); //$NON-NLS-1$
    }

    /** property=type is the type; without a project to resolve it, the call is refused. */
    @Test
    public void aTypeThatCannotBeAppliedIsRefusedAndNothingIsAdded() throws Exception
    {
        try
        {
            tool.applyToSchemaForTest("add_field", params("dataSetName", DATA_SET, "name", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "property", "type", "value", "Number"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("a type that was not applied was reported as written"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Type 'Number'")); //$NON-NLS-1$
        }
        assertEquals(0, fields().size());
    }

    private EList<EObject> fields()
    {
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", DATA_SET); //$NON-NLS-1$
        return BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
    }

    private EObject onlyField()
    {
        EList<EObject> fields = fields();
        assertEquals(1, fields.size());
        return fields.get(0);
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
}
