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
 * The removal report reaches the references the plain settings walk used to miss.
 * <p>
 * A user field computing from the removed field, a dataset link joined on it, a link that uses
 * the removed dataset at all, and a settings parameter whose value is the removed field are all
 * references: removing the element breaks them, and the report has to say so before it does.
 * </p>
 */
public class ARemovalReportNamesUserFieldsLinksAndValuesTest
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
     * Runs one removal and returns the settings report it built.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return the hits
     * @throws Exception if the operation refuses
     */
    private List<Map<String, Object>> impact(String op, String... keysAndValues) throws Exception
    {
        List<Map<String, Object>> hits = tool.removalImpactForTest(op, args(keysAndValues), schema);
        assertNotNull("the report is built", hits); //$NON-NLS-1$
        return hits;
    }

    /**
     * Pairs argument names with values.
     *
     * @param keysAndValues argument names and values, alternating
     * @return the arguments
     */
    private static Map<String, String> args(String... keysAndValues)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }

    /**
     * The settings of the first variant.
     *
     * @return the settings object
     */
    private EObject settings()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        Assume.assumeNotNull(variants);
        Assume.assumeTrue(!variants.isEmpty());
        Object settings = variants.get(0).eGet(
            variants.get(0).eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        return settings instanceof EObject ? (EObject)settings : null;
    }

    /**
     * Whether one of the hits sits in the given section and path.
     *
     * @param hits the report
     * @param section the section to look for
     * @param pathPart a part of the path to look for
     * @return <code>true</code> when a hit matches
     */
    /**
     * The hit at a path, or <code>null</code> when there is none.
     *
     * @param hits the report
     * @param section the section of the hit
     * @param pathPart the path fragment
     * @return the hit, or <code>null</code>
     */
    private static Map<String, Object> hitAt(List<Map<String, Object>> hits, String section,
        String pathPart)
    {
        for (Map<String, Object> hit : hits)
        {
            if (section.equals(hit.get("section")) //$NON-NLS-1$
                && String.valueOf(hit.get("path")).contains(pathPart)) //$NON-NLS-1$
            {
                return hit;
            }
        }
        return null;
    }

    private static boolean hasHit(List<Map<String, Object>> hits, String section, String pathPart)
    {
        for (Map<String, Object> hit : hits)
        {
            if (section.equals(hit.get("section")) //$NON-NLS-1$
                && String.valueOf(hit.get("path")).contains(pathPart)) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds one expression user field to the variant's settings.
     *
     * @param dataPath the data path the user field computes into
     * @param detailExpression the expression it computes from, or null for none
     */
    private void addUserField(String dataPath, String detailExpression)
    {
        EObject settings = settings();
        Assume.assumeNotNull("the settings are in this runtime", settings); //$NON-NLS-1$
        Object container = settings.eGet(settings.eClass().getEStructuralFeature("userFields")); //$NON-NLS-1$
        if (container == null)
        {
            container = BmDcsHelper.createElement("createDataCompositionUserFields"); //$NON-NLS-1$
            assertNotNull("the user-fields container was created", container); //$NON-NLS-1$
            assertNull(BmDcsHelper.setProperty(settings, "userFields", container)); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        assertNotNull("the user-fields list is there", items); //$NON-NLS-1$
        Object field = BmDcsHelper.createElement("createDataCompositionUserFieldExpression"); //$NON-NLS-1$
        assertNotNull("the user field was created", field); //$NON-NLS-1$
        assertNull(BmDcsHelper.setProperty(field, "dataPath", dataPath)); //$NON-NLS-1$
        if (detailExpression != null)
        {
            assertNull(BmDcsHelper.setProperty(field, "detailExpression", detailExpression)); //$NON-NLS-1$
        }
        items.add((EObject)field);
    }

    /**
     * Adds one dataset link to the schema.
     *
     * @param source the dataset the link joins from
     * @param destination the dataset the link joins to
     * @param condition the link condition expression, or null for none
     */
    private void addDataSetLink(String source, String destination, String condition)
    {
        Object link = BmDcsHelper.createElement("createDataCompositionSchemaDataSetLink"); //$NON-NLS-1$
        assertNotNull("the dataset link was created", link); //$NON-NLS-1$
        assertNull(BmDcsHelper.setProperty(link, "sourceDataSet", source)); //$NON-NLS-1$
        assertNull(BmDcsHelper.setProperty(link, "destinationDataSet", destination)); //$NON-NLS-1$
        if (condition != null)
        {
            assertNull(BmDcsHelper.setProperty(link, "linkConditionExpression", condition)); //$NON-NLS-1$
        }
        EList<EObject> links = BmDcsHelper.getEObjectList(schema, "getDataSetLinks"); //$NON-NLS-1$
        assertNotNull("the schema has a links collection", links); //$NON-NLS-1$
        links.add((EObject)link);
    }

    /**
     * A user field whose own data path is the removed field is a reference to it.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aUserFieldWhoseDataPathIsTheRemovedFieldIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        // A settings write creates the variant the user field is attached to.
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addUserField("Сумма", null);

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("the user field is in the report: " + hits, //$NON-NLS-1$
            hasHit(hits, "userFields", "userFields[0]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A user field computing from the removed field breaks with it, and the report says so.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aUserFieldComputingFromTheRemovedFieldIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        // A settings write creates the variant the user field is attached to.
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addUserField("Итог", "Сумма * 2"); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("the expression is in the report: " + hits, //$NON-NLS-1$
            hasHit(hits, "userFields", "detailExpression")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A user field on other fields is not pulled in by a name that merely contains the removed
     * field's name.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aUserFieldOnOtherFieldsIsNotListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_field", "dataSetName", "Продажи", "name", "Количество"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        // A settings write creates the variant the user field is attached to; the selection
        // names the other field, so the report has exactly nothing to say about Сумма.
        run("select_field", "field", "Количество"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addUserField("ИтогСумма", "Количество * 2"); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNotNull(hits);
        assertTrue("nothing references the removed field: " + hits, hits.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A link joined on the removed dataset is a reference to it, and belongs to no variant.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aDataSetLinkJoinedOnTheRemovedDataSetIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_dataset", "name", "Возвраты"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addDataSetLink("Продажи", "Возвраты", null); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> hits = impact("remove_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the link is in the report: " + hits, //$NON-NLS-1$
            hasHit(hits, "dataSetLinks", "sourceDataSet")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the links belong to no variant", "schema", hits.get(0).get("variant")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A link whose condition reads the removed field breaks with it, and the report says so.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aLinkConditionOnTheRemovedFieldIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_dataset", "name", "Возвраты"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addDataSetLink("Продажи", "Возвраты", "Сумма > 0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("the condition is in the report: " + hits, //$NON-NLS-1$
            hasHit(hits, "dataSetLinks", "linkConditionExpression")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A settings parameter whose value is the removed field is a reference to it.
     *
     * @throws Exception if a call refuses
     */
    /**
     * A parameter value is a literal: however it spells, it is not a reference to the field of
     * that name, and the report leaves it alone.
     */
    @Test
    public void aLiteralThatSpellsTheRemovedFieldIsNotListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_parameter", "name", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("set_settings_parameter", "name", "Период", "value", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull("a literal is not a field reference: " + hits, //$NON-NLS-1$
            hitAt(hits, "dataParameters", "values[0]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A parameter value that carries the field itself - a model DataCompositionField in the
     * values list - is a reference the removal breaks.
     */
    @Test
    public void aParameterValueHoldingTheFieldItselfIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_parameter", "name", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("set_settings_parameter", "name", "Период", "value", "текст"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Object entry = firstParameterEntry();
        Assume.assumeNotNull("the settings carry the parameter entry", entry); //$NON-NLS-1$
        EList<EObject> values = BmDcsHelper.getEObjectList(entry, "getValues"); //$NON-NLS-1$
        Assume.assumeNotNull(values);
        values.clear();
        com._1c.g5.v8.dt.dcs.model.core.DataCompositionField field =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        field.setValue("Сумма"); //$NON-NLS-1$
        values.add(field);

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the field held as a value is in the report: " + hits, //$NON-NLS-1$
            hasHit(hits, "dataParameters", "values[0]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The first entry of the settings' data parameters.
     *
     * @return the entry, or <code>null</code> when the settings carry none
     */
    private EObject firstParameterEntry()
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(
            BmDcsHelper.getEObjectList(settings(), "getDataParameters"), "getItems"); //$NON-NLS-1$ //$NON-NLS-2$
        return items == null || items.isEmpty() ? null : items.get(0);
    }
}
