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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * A table or a chart a call adds comes out with the resources of the schema selected.
 *
 * <p>Measured 28.09: {@code add_settings_table} answered "settings table 'X' added" while the
 * structure item carried no selected fields, so a table built through the tool output nothing
 * until every resource was picked by hand - the answer reported success on a table that renders
 * empty. The resources a schema declares are its total fields: in this model the field role
 * carries no resource flag and the schema has no resource collection, so a total's
 * {@code dataPath} is the name a selected field refers to.
 */
public class SettingsTableTakesTheSchemaResourcesTest
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

    /**
     * Declares a resource the way the model carries one: a total field over a data path.
     *
     * @param dataPath the path the resource is named by
     * @param aggregate the aggregate expression computed for it
     */
    private void resource(String dataPath, String aggregate)
    {
        Object total = BmDcsHelper.createElement("createDataCompositionSchemaTotalField"); //$NON-NLS-1$
        assertNotNull("the composition model carries no total field factory", total); //$NON-NLS-1$
        assertNull(BmDcsHelper.setProperty(total, "dataPath", dataPath)); //$NON-NLS-1$
        assertNull(BmDcsHelper.setProperty(total, "expression", aggregate)); //$NON-NLS-1$
        EList<EObject> totals = BmDcsHelper.getEObjectList(schema, "getTotalFields"); //$NON-NLS-1$
        assertNotNull("the schema should hold totals", totals); //$NON-NLS-1$
        totals.add((EObject)total);
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

    /** The settings the operations work on: those of the first variant, or the schema's own. */
    private EObject settings()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the schema should have a variant by now", variants); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("a variant with no settings holds no structure", settings); //$NON-NLS-1$
        return (EObject)settings;
    }

    /** The structure items of the settings. */
    private EList<EObject> structure()
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(settings(), "getItems"); //$NON-NLS-1$
        assertNotNull("the settings should hold a structure", items); //$NON-NLS-1$
        return items;
    }

    /** The data paths a container selects, in order; a container that is absent selects none. */
    private static List<String> selectedIn(EObject container)
    {
        Object selection = container.eGet(container.eClass().getEStructuralFeature("selection")); //$NON-NLS-1$
        if (selection == null)
        {
            return Collections.emptyList();
        }
        EList<EObject> selected = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (selected == null)
        {
            return Collections.emptyList();
        }
        List<String> paths = new ArrayList<>();
        for (EObject one : selected)
        {
            paths.add(fieldPathOf(one.eGet(one.eClass().getEStructuralFeature("field")))); //$NON-NLS-1$
        }
        return paths;
    }

    /**
     * Reads the path out of a {@code DataCompositionField} value carrier.
     *
     * @param field the carrier, or null
     * @return the path it holds, and the carrier's own text when it holds no value
     */
    private static String fieldPathOf(Object field)
    {
        if (field == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method value = field.getClass().getMethod("getValue"); //$NON-NLS-1$
            Object held = value.invoke(field);
            return held != null ? held.toString() : String.valueOf(field);
        }
        catch (Exception noValueGetter)
        {
            // A carrier whose value cannot be read is reported as it stands rather than as a
            // missing selection: the text names it, an empty string would hide it.
            return String.valueOf(field);
        }
    }

    /** The answer names the resources it selected, not only the item it made. */
    @Test
    public void aTableSelectsTheResourcesOfTheSchema() throws Exception
    {
        resource("Количество", "Сумма(Продажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$
        resource("Стоимость", "Сумма(Продажи.Стоимость)"); //$NON-NLS-1$ //$NON-NLS-2$

        Object answer = run("add_settings_table", "name", "Таблица"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the item selects the resources of the schema", //$NON-NLS-1$
            Arrays.asList("Количество", "Стоимость"), selectedIn(structure().get(0))); //$NON-NLS-1$ //$NON-NLS-2$
        String said = String.valueOf(answer);
        assertTrue("the answer names what was selected: " + said, //$NON-NLS-1$
            said.contains("Количество") && said.contains("Стоимость")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The fix is shared: a chart is filled the same way. */
    @Test
    public void aChartSelectsTheResourcesOfTheSchema() throws Exception
    {
        resource("Количество", "Сумма(Продажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$

        run("add_settings_chart", "name", "Диаграмма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a chart takes the resources too", //$NON-NLS-1$
            Collections.singletonList("Количество"), selectedIn(structure().get(0))); //$NON-NLS-1$
    }

    /** A second table is its own item with its own selection - nothing is selected twice. */
    @Test
    public void aSecondTableDoesNotDoubleTheResources() throws Exception
    {
        resource("Количество", "Сумма(Продажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$

        run("add_settings_table", "name", "Первая"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_settings_table", "name", "Вторая"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("two calls make two items", 2, structure().size()); //$NON-NLS-1$
        assertEquals("the first selects the resource once", //$NON-NLS-1$
            Collections.singletonList("Количество"), selectedIn(structure().get(0))); //$NON-NLS-1$
        assertEquals("and so does the second", //$NON-NLS-1$
            Collections.singletonList("Количество"), selectedIn(structure().get(1))); //$NON-NLS-1$
    }

    /** Two aggregates over one path are one selected field: a selection names a path. */
    @Test
    public void twoTotalsOverOnePathSelectItOnce() throws Exception
    {
        resource("Количество", "Сумма(Продажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$
        resource("Количество", "Среднее(Продажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$

        run("add_settings_table", "name", "Таблица"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("one path, one selected field", //$NON-NLS-1$
            Collections.singletonList("Количество"), selectedIn(structure().get(0))); //$NON-NLS-1$
    }

    /** A schema that declares nothing is not a failure, and the answer does not stay silent. */
    @Test
    public void aSchemaWithoutResourcesSaysSo() throws Exception
    {
        Object answer = run("add_settings_table", "name", "Таблица"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String said = String.valueOf(answer);
        assertTrue("the answer says the schema declared no resource: " + said, //$NON-NLS-1$
            said.contains("no resources")); //$NON-NLS-1$
        assertEquals("nothing is selected", Collections.emptyList(), //$NON-NLS-1$
            selectedIn(structure().get(0)));
    }

    /**
     * A total with no data path carries no name to select.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void aResourceWithNoPathIsNotSelected() throws Exception
    {
        resource("", "Сумма(Продажи.Количество)"); //$NON-NLS-1$ //$NON-NLS-2$

        run("add_settings_table", "name", "Таблица"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a nameless total is not a field to select", Collections.emptyList(), //$NON-NLS-1$
            selectedIn(structure().get(0)));
    }

    /** The explicit operation keeps writing the one field it was asked for. */
    @Test
    public void theExplicitOperationStillWritesOneSelectedField() throws Exception
    {
        run("add_settings_selected_field", "field", "Объект"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the settings select the field that was named", //$NON-NLS-1$
            Collections.singletonList("Объект"), selectedIn(settings())); //$NON-NLS-1$
    }
}
