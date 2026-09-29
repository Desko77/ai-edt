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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.DcsSettingsImpact;

/**
 * Removing a composition element reports the settings that still reference it, and leaves them.
 * <p>
 * The report is a read of every settings variant. It does not clear a selection, an order, a
 * filter or an appearance, and it does not create a variant in order to have something to read.
 * </p>
 */
public class DcsSettingsImpactTest
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
     * A field used by a selection, an order, a filter and an appearance is listed in all four,
     * and those four are still there after the field is gone.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void fieldRemovalListsSelectionOrderFilterAndAppearance() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_settings_order", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_appearance", "field", "Сумма", "conditionValue", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        int selection = itemCount("Основной", "getSelection"); //$NON-NLS-1$ //$NON-NLS-2$
        int order = itemCount("Основной", "getOrder"); //$NON-NLS-1$ //$NON-NLS-2$
        int filter = itemCount("Основной", "getFilter"); //$NON-NLS-1$ //$NON-NLS-2$
        int appearance = itemCount("Основной", "getConditionalAppearance"); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNotNull("the list is built when the flag is left at its default", hits); //$NON-NLS-1$
        assertEquals("selection, order, filter and appearance: " + hits, 4, hits.size()); //$NON-NLS-1$
        assertEquals(setOf("selection", "order", "filter", "conditionalAppearance"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            sections(hits));
        assertEquals("Основной", hits.get(0).get("variant")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the selected field is still selected", selection, //$NON-NLS-1$
            itemCount("Основной", "getSelection")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the order is still there", order, itemCount("Основной", "getOrder")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the filter is still there", filter, itemCount("Основной", "getFilter")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the appearance is still there", appearance, //$NON-NLS-1$
            itemCount("Основной", "getConditionalAppearance")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Сумма", storedField("Основной", "getSelection")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the dataset field itself is gone", datasetHasField("Продажи", "Сумма")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Map<String, Object>> still = DcsSettingsImpact.collect(schema,
            Arrays.asList("Сумма", "Продажи.Сумма"), Collections.<String>emptyList()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("reading the settings again finds the same four references", 4, still.size()); //$NON-NLS-1$
    }

    /**
     * A parameter is listed where a filter addresses it and where the settings hold its value.
     * A selected field that merely shares the name is a different thing.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void parameterRemovalListsFilterAndDataParameter() throws Exception
    {
        run("add_parameter", "name", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_filter", "field", "ПараметрыДанных.Период", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_parameter", "name", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull(hits);
        assertEquals("the filter and the data parameter, not the field of the same name: " + hits, //$NON-NLS-1$
            2, hits.size());
        assertEquals(setOf("filter", "dataParameters"), sections(hits)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Nothing to report is an empty list, and looking does not create the default variant.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void noReferencesYieldsAnEmptyList() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("a field on its own creates no settings variant", 0, variantCount()); //$NON-NLS-1$

        JsonObject answer = answerOf("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(0, answer.get("affectedCount").getAsInt()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("affectedSettings").size()); //$NON-NLS-1$
        assertEquals("the report did not create a variant to read", 0, variantCount()); //$NON-NLS-1$
    }

    /**
     * A second variant is read as well as the first.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void bothVariantsAreListed() throws Exception
    {
        referencesToAmount();
        run("clone_settings_variant", "sourceName", "Основной", "name", "Дополнительный"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("each of the four references, in each variant: " + hits, 8, hits.size()); //$NON-NLS-1$
        assertEquals(setOf("Основной", "Дополнительный"), variants(hits)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code reportAffectedSettings=false} does not build the list, and the answer omits both keys.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void reportAffectedSettingsFalseOmitsTheList() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "reportAffectedSettings", "false"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("false does not build the list", hits); //$NON-NLS-1$
        assertEquals("the selected field is still selected", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$
            storedField("Основной", "getSelection")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same refusal, as the caller reads it: the answer JSON has neither key.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void theAnswerOmitsBothKeysWhenTheFlagIsFalse() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject answer = answerOf("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "reportAffectedSettings", "false"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(answer.has("affectedSettings")); //$NON-NLS-1$
        assertFalse(answer.has("affectedCount")); //$NON-NLS-1$
    }

    /**
     * The default builds the list into the answer, and the count matches the array.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void theAnswerCarriesTheListWhenTheFlagIsLeftOn() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String json = jsonOf("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(1, answer.get("affectedCount").getAsInt()); //$NON-NLS-1$
        assertEquals(1, answer.getAsJsonArray("affectedSettings").size()); //$NON-NLS-1$
        String listed = json.substring(json.indexOf("\"affectedSettings\"")); //$NON-NLS-1$
        int variant = listed.indexOf("\"variant\""); //$NON-NLS-1$
        int section = listed.indexOf("\"section\""); //$NON-NLS-1$
        int path = listed.indexOf("\"path\""); //$NON-NLS-1$
        int item = listed.indexOf("\"item\""); //$NON-NLS-1$
        assertTrue("a hit names variant, section, path, item, in that order", //$NON-NLS-1$
            variant >= 0 && variant < section && section < path && path < item);
    }

    /**
     * {@code Sales.amount} is the field {@code Amount}, and the shorter {@code Sales} is not.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aDottedPathMatchesIgnoringCase() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Продажи.сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("the parent path is a different field: " + hits, 1, hits.size()); //$NON-NLS-1$
        assertEquals("selection", hits.get(0).get("section")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Продажи.сумма", hits.get(0).get("item")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code Returns.Amount} is not the field {@code Amount} of {@code Sales}.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void anotherDataSetWithTheSameFieldNameIsNotListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_dataset", "name", "Возвраты"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Возвраты", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Возвраты.Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNotNull(hits);
        assertTrue("a field of another dataset is not this field: " + hits, hits.isEmpty()); //$NON-NLS-1$
        assertEquals("Возвраты.Сумма", storedField("Основной", "getSelection")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the other dataset keeps its field", datasetHasField("Возвраты", "Сумма")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A path that continues the field by a whole segment is still that field.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aChildPathIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Продажи.Сумма.Код"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(1, hits.size());
        assertEquals("Продажи.Сумма.Код", hits.get(0).get("item")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A field whose data path has a dot is stored qualified by its dataset, and that spelling is
     * the field too.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aQualifiedCompoundPathIsListed() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Контрагент.ИНН"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_dataset", "name", "Возвраты"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Продажи.Контрагент.ИНН"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Контрагент.ИНН"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("the qualified spelling is this field: " + hits, 1, hits.size()); //$NON-NLS-1$
        assertEquals("Продажи.Контрагент.ИНН", hits.get(0).get("item")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A grouping field is reported under the structure, not under the selection.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aGroupingFieldIsListedUnderStructure() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_grouping", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_dataset_field", //$NON-NLS-1$
            "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(1, hits.size());
        assertEquals("structure", hits.get(0).get("section")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(hits.get(0).get("path")), //$NON-NLS-1$
            String.valueOf(hits.get(0).get("path")).contains("groupFields")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A calculated field is referenced by its data path. Its formula is not a field path.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aCalculatedFieldIsListedByItsDataPath() throws Exception
    {
        run("add_calculated_field", "name", "Итого", "expression", "Сумма * 2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Итого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_calculated_field", "name", "Итого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the formula does not pull in the field it mentions: " + hits, 1, hits.size()); //$NON-NLS-1$
        assertEquals("selection", hits.get(0).get("section")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Итого", hits.get(0).get("item")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A total is referenced by its data path. The aggregate wrapped around a field is not a path.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aTotalFieldIsListedByItsDataPath() throws Exception
    {
        run("add_total", "expression", "Сумма", "dataPath", "Итог", "aggregateFunction", "Sum"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        run("select_field", "field", "Итог"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<Map<String, Object>> hits = impact("remove_total_field", "name", "Итог"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the aggregate does not pull in the field it totals: " + hits, 1, hits.size()); //$NON-NLS-1$
        assertEquals("Итог", hits.get(0).get("item")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removing a dataset lists every field of it that the settings use, and still reports a write.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void removingADataSetListsEveryFieldItOwns() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_field", "dataSetName", "Продажи", "name", "Количество"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_calculated_field", "name", "Итого", "expression", "Продажи.Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Количество"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("select_field", "field", "Итого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Object reported = tool.applyToSchemaForTest("remove_dataset", //$NON-NLS-1$
            args("name", "Продажи"), schema); //$NON-NLS-1$ //$NON-NLS-2$
        BmDcsHelper.Result result = new BmDcsHelper.Result();
        result.ok = true;
        result.message = String.valueOf(reported);
        DcsWorkshopTool.attachRemovalImpact(result.tags);

        assertTrue("the removal still names what it wrote", reported instanceof BmDcsHelper.Wrote); //$NON-NLS-1$
        assertNull("the dataset is gone", //$NON-NLS-1$
            BmDcsHelper.findByNameInList(schema, "getDataSets", "Продажи")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("both fields, and the calculated field the cascade takes with them", //$NON-NLS-1$
            3, ((Number)result.tags.get("affectedCount")).intValue()); //$NON-NLS-1$
        assertEquals(setOf("Сумма", "Количество", "Итого"), items(tags(result))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A field of a table row is reported under the structure, on the rows step.
     *
     * @throws Exception if a call refuses
     */
    @Test
    public void aTableRowFieldIsListedUnderStructure() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_settings_table"); //$NON-NLS-1$
        EObject settings = settingsOf("Основной"); //$NON-NLS-1$
        EList<EObject> structure = BmDcsHelper.getEObjectList(settings, "getItems"); //$NON-NLS-1$
        Assume.assumeTrue("the table was not added to the structure", //$NON-NLS-1$
            structure != null && !structure.isEmpty());
        EObject table = structure.get(structure.size() - 1);
        EList<EObject> rows = BmDcsHelper.getEObjectList(table, "getRows"); //$NON-NLS-1$
        Assume.assumeNotNull("this runtime's table has no rows collection", rows); //$NON-NLS-1$
        Object group = BmDcsHelper.createElement("createDataCompositionTableGroup"); //$NON-NLS-1$
        Object groupFields = BmDcsHelper.createElement("createDataCompositionGroupFields"); //$NON-NLS-1$
        Object groupField = BmDcsHelper.createElement("createDataCompositionGroupField"); //$NON-NLS-1$
        Object field = BmDcsHelper.createDataCompositionField("Сумма"); //$NON-NLS-1$
        Assume.assumeTrue("the table-row factories are not in this runtime", //$NON-NLS-1$
            group instanceof EObject && groupFields instanceof EObject && groupField instanceof EObject
                && field instanceof EObject);
        Assume.assumeTrue(BmDcsHelper.setProperty(groupField, "field", field) == null); //$NON-NLS-1$
        EList<EObject> fieldItems = BmDcsHelper.getEObjectList(groupFields, "getItems"); //$NON-NLS-1$
        Assume.assumeNotNull(fieldItems);
        fieldItems.add((EObject)groupField);
        Assume.assumeTrue(BmDcsHelper.setProperty(group, "groupFields", groupFields) == null); //$NON-NLS-1$
        rows.add((EObject)group);

        List<Map<String, Object>> hits = DcsSettingsImpact.collect(schema,
            Arrays.asList("Сумма", "Продажи.Сумма"), Collections.<String>emptyList()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, hits.size());
        assertEquals("structure", hits.get(0).get("section")); //$NON-NLS-1$ //$NON-NLS-2$
        String path = String.valueOf(hits.get(0).get("path")); //$NON-NLS-1$
        assertTrue(path, path.contains("rows") && path.contains("groupFields")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The four references the field-removal cases start from.
     *
     * @throws Exception if a call refuses
     */
    private void referencesToAmount() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "dataSetName", "Продажи", "name", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("select_field", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_settings_order", "field", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_filter", "field", "Сумма", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_appearance", "field", "Сумма", "conditionValue", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
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
        return tool.applyToSchemaForTest(op, args(keysAndValues), schema);
    }

    /**
     * Runs one removal and returns the settings report it built.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return the hits, or <code>null</code> when the list was not built
     * @throws Exception if the operation refuses
     */
    private List<Map<String, Object>> impact(String op, String... keysAndValues) throws Exception
    {
        return tool.removalImpactForTest(op, args(keysAndValues), schema);
    }

    /**
     * Runs one removal and renders the answer the caller receives.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return the answer JSON
     * @throws Exception if the operation refuses
     */
    private String jsonOf(String op, String... keysAndValues) throws Exception
    {
        Object reported = tool.applyToSchemaForTest(op, args(keysAndValues), schema);
        BmDcsHelper.Result result = new BmDcsHelper.Result();
        result.ok = true;
        result.message = String.valueOf(reported);
        DcsWorkshopTool.attachRemovalImpact(result.tags);
        return tool.formatResultForTest(result, op);
    }

    /**
     * Runs one removal and parses the answer.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return the answer
     * @throws Exception if the operation refuses
     */
    private JsonObject answerOf(String op, String... keysAndValues) throws Exception
    {
        return JsonParser.parseString(jsonOf(op, keysAndValues)).getAsJsonObject();
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
     * The sections a report names.
     *
     * @param hits the report
     * @return the sections, without duplicates
     */
    private static Set<String> sections(List<Map<String, Object>> hits)
    {
        Set<String> sections = new LinkedHashSet<>();
        for (Map<String, Object> hit : hits)
        {
            sections.add(String.valueOf(hit.get("section"))); //$NON-NLS-1$
        }
        return sections;
    }

    /**
     * The variants a report names.
     *
     * @param hits the report
     * @return the variant names
     */
    private static Set<String> variants(List<Map<String, Object>> hits)
    {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> hit : hits)
        {
            names.add(String.valueOf(hit.get("variant"))); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * The stored texts a report names.
     *
     * @param hits the report
     * @return the item texts
     */
    private static Set<String> items(List<Map<String, Object>> hits)
    {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> hit : hits)
        {
            names.add(String.valueOf(hit.get("item"))); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * The hits attached to a write result.
     *
     * @param result the result
     * @return the hits
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tags(BmDcsHelper.Result result)
    {
        return (List<Map<String, Object>>)result.tags.get("affectedSettings"); //$NON-NLS-1$
    }

    /**
     * A set of the given texts.
     *
     * @param values the texts
     * @return the set
     */
    private static Set<String> setOf(String... values)
    {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /**
     * How many settings variants the schema holds.
     *
     * @return the count, zero when there are none
     */
    private int variantCount()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        return variants == null ? 0 : variants.size();
    }

    /**
     * The settings of one variant.
     *
     * @param variantName the variant
     * @return its settings, or <code>null</code>
     */
    private EObject settingsOf(String variantName)
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            return null;
        }
        for (EObject variant : variants)
        {
            Object name = read(variant, "getName"); //$NON-NLS-1$
            if (name != null && variantName.equalsIgnoreCase(name.toString()))
            {
                Object settings = read(variant, "getSettings"); //$NON-NLS-1$
                return settings instanceof EObject ? (EObject)settings : null;
            }
        }
        return null;
    }

    /**
     * How many items a settings container holds.
     *
     * @param variantName the variant
     * @param containerGetter the container, such as {@code getSelection}
     * @return the item count, zero when the container is absent
     */
    private int itemCount(String variantName, String containerGetter)
    {
        EObject settings = settingsOf(variantName);
        if (settings == null)
        {
            return 0;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(read(settings, containerGetter), "getItems"); //$NON-NLS-1$
        return items == null ? 0 : items.size();
    }

    /**
     * The field path stored on the first item of a container.
     *
     * @param variantName the variant
     * @param containerGetter the container
     * @return the path, or <code>null</code>
     */
    private String storedField(String variantName, String containerGetter)
    {
        EObject settings = settingsOf(variantName);
        if (settings == null)
        {
            return null;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(read(settings, containerGetter), "getItems"); //$NON-NLS-1$
        if (items == null || items.isEmpty())
        {
            return null;
        }
        Object field = read(items.get(0), "getField"); //$NON-NLS-1$
        Object value = read(field, "getValue"); //$NON-NLS-1$
        return value != null ? value.toString() : (field == null ? null : field.toString());
    }

    /**
     * Whether a dataset still holds a field of this data path.
     *
     * @param dataSetName the dataset
     * @param fieldName the data path
     * @return <code>true</code> when the field is there
     */
    private boolean datasetHasField(String dataSetName, String fieldName)
    {
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            return false;
        }
        EList<EObject> fields = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        if (fields == null)
        {
            return false;
        }
        for (EObject field : fields)
        {
            Object path = read(field, "getDataPath"); //$NON-NLS-1$
            if (path != null && fieldName.equalsIgnoreCase(path.toString()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Calls a no-argument getter.
     *
     * @param target the object, possibly <code>null</code>
     * @param name the getter
     * @return the value, or <code>null</code>
     */
    private static Object read(Object target, String name)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        }
        catch (ReflectiveOperationException absent)
        {
            return null;
        }
    }
}
