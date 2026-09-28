/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * Removing a dataset drops calculated fields that read it, and only those.
 * <p>
 * The cascade used to look for the substring {@code name + "."} anywhere in the expression, so a
 * field whose path merely ended in that dataset's name was removed with it, and the comparison
 * distinguished case. A reference is a dotted path, and the dataset is its first segment.
 * </p>
 */
public class ACascadeRemovesOnlyWhatTheDataSetOwnsTest
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
     * The expressions of the schema's calculated fields, in order.
     *
     * @return the expressions, possibly empty
     */
    private List<String> calculatedExpressions()
    {
        EList<EObject> fields = BmDcsHelper.getEObjectList(schema, "getCalculatedFields"); //$NON-NLS-1$
        assertNotNull("the schema lists its calculated fields", fields); //$NON-NLS-1$
        List<String> expressions = new ArrayList<>();
        for (EObject field : fields)
        {
            Object expression = field.eGet(field.eClass().getEStructuralFeature("expression")); //$NON-NLS-1$
            expressions.add(expression == null ? "" : String.valueOf(expression)); //$NON-NLS-1$
        }
        return expressions;
    }

    /**
     * A longer name that only ends in the dataset's name is not a reference to it.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void aFieldWhosePathMerelyEndsInTheDataSetNameStays() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_calculated_field", "name", "Возвраты", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "expression", "Сумма(ВозвратыПродажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$

        run("remove_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the dataset itself is gone", //$NON-NLS-1$
            BmDcsHelper.findByNameInList(schema, "getDataSets", "Продажи") == null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a path that only ends in the dataset name is a different field: " //$NON-NLS-1$
            + calculatedExpressions(),
            calculatedExpressions().contains("Сумма(ВозвратыПродажи.Количество)")); //$NON-NLS-1$
    }

    /**
     * The same name in another case is still that dataset.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void aReferenceInAnotherCaseIsStillThatDataSet() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_calculated_field", "name", "Сумма", "expression", "продажи.Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("the reference differs only in case, so the field goes with the dataset: " //$NON-NLS-1$
            + calculatedExpressions(),
            calculatedExpressions().contains("продажи.Сумма")); //$NON-NLS-1$
    }
}
