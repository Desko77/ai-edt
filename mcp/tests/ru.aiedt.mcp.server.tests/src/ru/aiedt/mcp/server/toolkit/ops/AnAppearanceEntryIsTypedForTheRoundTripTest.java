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
 * An appearance entry written into a dataset field is the type the environment keeps.
 *
 * <p>Round-tripped through the environment on 18.09: an appearance parameter written as the plain
 * carrier survived the first write and lost its appearance when the schema was read back and
 * serialized; written as {@code SettingsParameterValue} the environment rewrites it exactly that way
 * and keeps it. The writer now asks for the typed element first, falling back to the plain one only
 * where the factory cannot make it.
 *
 * <p>What decides the round trip is the type of the element that lands in the appearance container,
 * and that is what these tests read back from the schema - the operation is driven on a real
 * composition schema from {@code DcsFactory.eINSTANCE}, which needs no workspace, and the entry it
 * writes is inspected. The serializer that turns it into XML branches on the same thing: measured
 * with {@code javap} on {@code com._1c.g5.v8.dt.dcs} 22.0.0, its
 * {@code writeParameterValue(...)} is gated by {@code instanceof SettingsParameterValue} and writes
 * the literal local name {@code SettingsParameterValue}, which its {@code readParameterValue(...)}
 * matches back to that same factory method.
 */
public class AnAppearanceEntryIsTypedForTheRoundTripTest
{
    private static final String DATA_SET = "Набор"; //$NON-NLS-1$
    private static final String FIELD = "Сумма"; //$NON-NLS-1$
    private static final String APPEARANCE = "TextColor=#FF0000"; //$NON-NLS-1$

    private DcsWorkshopTool tool;
    private EObject schema;

    /** Builds a schema, a dataset and a field, so an appearance has somewhere to be written. */
    @Before
    public void buildASchemaWithAField()
        throws Exception
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();

        run("add_dataset", "name", DATA_SET); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_field", "name", FIELD, "dataSetName", DATA_SET); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    private Object run(String op, String... keysAndValues)
        throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    /**
     * The appearance entries the operation left on the dataset field.
     *
     * @return the items of the field's appearance container.
     */
    private EList<EObject> appearanceEntries()
        throws Exception
    {
        run("set_data_set_field_appearance", "dataSet", DATA_SET, "field", FIELD, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "appearance", APPEARANCE); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> dataSets = BmDcsHelper.getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        assertNotNull("the schema should carry the dataset by now", dataSets); //$NON-NLS-1$
        EObject dataSet = null;
        for (EObject candidate : dataSets)
        {
            if (DATA_SET.equals(candidate.eGet(
                candidate.eClass().getEStructuralFeature("name")))) //$NON-NLS-1$
            {
                dataSet = candidate;
            }
        }
        assertNotNull("the dataset the field belongs to", dataSet); //$NON-NLS-1$

        EList<EObject> fields = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        assertNotNull("the dataset should carry the field by now", fields); //$NON-NLS-1$
        EObject field = null;
        for (EObject candidate : fields)
        {
            if (FIELD.equals(candidate.eGet(
                candidate.eClass().getEStructuralFeature("dataPath")))) //$NON-NLS-1$
            {
                field = candidate;
            }
        }
        assertNotNull("the field the appearance was asked for", field); //$NON-NLS-1$

        Object appearance = field.eGet(field.eClass().getEStructuralFeature("appearance")); //$NON-NLS-1$
        assertNotNull("the field's appearance container is created where it had none", appearance); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(appearance, "getItems"); //$NON-NLS-1$
        assertNotNull("the appearance container should list its entries", items); //$NON-NLS-1$
        assertTrue("the appearance that was asked for should be there: " + items, //$NON-NLS-1$
            !items.isEmpty());
        return items;
    }

    /** What the written entry is, by the name of its type in the model. */
    @Test
    public void aWrittenEntryIsASettingsParameterValue()
        throws Exception
    {
        EObject entry = appearanceEntries().get(0);

        assertEquals("the plain carrier loses its appearance on the way back; the typed one is " //$NON-NLS-1$
            + "what the environment writes and keeps", "SettingsParameterValue", //$NON-NLS-1$
            entry.eClass().getName());
    }

    /**
     * The written entry is an instance of the type the environment's own writer branches on, so
     * the type that decides the round trip is the type that was written.
     *
     * @throws Exception when the model type cannot be loaded.
     */
    @Test
    public void aWrittenEntryIsAnInstanceOfTheTypeTheWriterBranchesOn()
        throws Exception
    {
        EObject entry = appearanceEntries().get(0);

        Class<?> typed;
        try
        {
            typed = Class.forName("com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException absent)
        {
            Assume.assumeNoException("the settings model is not in this runtime", absent); //$NON-NLS-1$
            return;
        }
        assertTrue("writeParameterValue branches on this very type: " //$NON-NLS-1$
            + entry.eClass().getName(), typed.isInstance(entry));
    }

    /** Asking for the same appearance again finds the entry rather than adding a second one. */
    @Test
    public void writingTheSameAppearanceTwiceKeepsOneTypedEntry()
        throws Exception
    {
        appearanceEntries();

        EList<EObject> afterSecondCall = appearanceEntries();

        assertEquals("the same key is one entry, not two", 1, afterSecondCall.size()); //$NON-NLS-1$
        assertEquals("and it stays the typed one", "SettingsParameterValue", //$NON-NLS-1$ //$NON-NLS-2$
            afterSecondCall.get(0).eClass().getName());
    }

    /**
     * The entry is addressed by the Russian key the environment stores, which is what the schema
     * carries back - {@code TextColor} is only the spelling the caller passes in.
     */
    @Test
    public void theEntryCarriesTheKeyTheEnvironmentStores()
        throws Exception
    {
        EObject entry = appearanceEntries().get(0);

        Object parameter = entry.eGet(entry.eClass().getEStructuralFeature("parameter")); //$NON-NLS-1$
        assertNotNull("an entry with no parameter names nothing", parameter); //$NON-NLS-1$
        EObject asObject = (EObject)parameter;
        assertEquals("ЦветТекста", //$NON-NLS-1$
            asObject.eGet(asObject.eClass().getEStructuralFeature("value"))); //$NON-NLS-1$
        assertEquals("an entry that is not marked used is not written at all", Boolean.TRUE, //$NON-NLS-1$
            entry.eGet(entry.eClass().getEStructuralFeature("use"))); //$NON-NLS-1$
    }

    /** The value the caller asked for reaches the entry rather than being dropped. */
    @Test
    public void theValueReachesTheEntry()
        throws Exception
    {
        EObject entry = appearanceEntries().get(0);

        EList<EObject> values = BmDcsHelper.getEObjectList(entry, "getValues"); //$NON-NLS-1$
        assertNotNull("the entry should carry the value it was set to", values); //$NON-NLS-1$
        assertEquals("a colour the caller named is a colour the entry holds", 1, values.size()); //$NON-NLS-1$
        assertNotNull(values.get(0));
        if (!"ColorValue".equals(values.get(0).eClass().getName())) //$NON-NLS-1$
        {
            fail("a colour should land as a colour value, not as " //$NON-NLS-1$
                + values.get(0).eClass().getName());
        }
    }
}
