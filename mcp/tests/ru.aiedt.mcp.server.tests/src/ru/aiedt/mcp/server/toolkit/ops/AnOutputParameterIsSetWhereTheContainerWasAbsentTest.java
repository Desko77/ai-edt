/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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
 * Setting an output parameter of a settings variant.
 * <p>
 * A settings object carries no output-parameter container until something puts one there, and the
 * absent container was reported as {@code DefaultSettings.getOutputParameters() not available} -
 * which names a missing method rather than an empty property, and left the operation with nothing
 * it could write. The container is now created, and a name addresses the same entry whether that
 * container was just created or was already serialized with the entry in it.
 * </p>
 * <p>
 * Run against a real composition schema from {@code DcsFactory.eINSTANCE}, which needs no workspace.
 * </p>
 */
public class AnOutputParameterIsSetWhereTheContainerWasAbsentTest
{
    private static final String PARAMETER = "Заголовок"; //$NON-NLS-1$

    private static final String WRITTEN = "Мой отчет"; //$NON-NLS-1$

    private DcsWorkshopTool tool;

    private EObject schema;

    /**
     * Builds an empty composition schema.
     * <p>
     * The schema has no settings variant yet, so the operation is the one that creates the default
     * settings - and those settings arrive without an output-parameter container.
     * </p>
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
     * The output-parameter entries of the first settings variant.
     *
     * @return the items of the container, which the call creates when the settings had none
     */
    private EList<EObject> entries()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the schema should have a variant by now", variants); //$NON-NLS-1$
        assertTrue("the schema should have a variant by now", !variants.isEmpty()); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("a variant with no settings holds no output parameters", settings); //$NON-NLS-1$
        EObject asObject = (EObject)settings;
        Object container = asObject.eGet(asObject.eClass().getEStructuralFeature("outputParameters")); //$NON-NLS-1$
        assertNotNull("the container is created when the settings carry none", container); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        assertNotNull("the container should list its entries", items); //$NON-NLS-1$
        return items;
    }

    /**
     * The parameter name an entry is keyed by.
     *
     * @param entry one output-parameter entry
     * @return {@code getParameter().getValue()}
     */
    private static String keyOf(EObject entry)
    {
        Object parameter = entry.eGet(entry.eClass().getEStructuralFeature("parameter")); //$NON-NLS-1$
        assertNotNull("an entry with no parameter names nothing", parameter); //$NON-NLS-1$
        EObject asObject = (EObject)parameter;
        return String.valueOf(asObject.eGet(asObject.eClass().getEStructuralFeature("value"))); //$NON-NLS-1$
    }

    /**
     * The value the entry holds.
     *
     * @param entry one output-parameter entry
     * @return the text of its single value
     */
    private static String valueOf(EObject entry)
    {
        EList<EObject> values = BmDcsHelper.getEObjectList(entry, "getValues"); //$NON-NLS-1$
        assertNotNull("an output parameter holds its value in getValues()", values); //$NON-NLS-1$
        assertEquals("the call replaces whatever the entry held with the value it was given", //$NON-NLS-1$
            1, values.size());
        EObject literal = values.get(0);
        return String.valueOf(literal.eGet(literal.eClass().getEStructuralFeature("value"))); //$NON-NLS-1$
    }

    /**
     * Settings that have no output-parameter container receive the value, and reading the entry
     * back returns it.
     *
     * @throws Exception if the operation refuses
     */
    @Test
    public void aMissingContainerReceivesTheValueAndReadsItBack()
        throws Exception
    {
        run("set_output_parameter", "name", PARAMETER, "value", WRITTEN); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> items = entries();
        assertEquals("the parameter belongs in the settings once", 1, items.size()); //$NON-NLS-1$
        assertEquals("and under the name it was asked for", PARAMETER, keyOf(items.get(0))); //$NON-NLS-1$
        assertEquals("reading it back returns what was written", WRITTEN, valueOf(items.get(0))); //$NON-NLS-1$
    }

    /**
     * A second write into the container the first write created finds the same entry.
     *
     * @throws Exception if the operation refuses
     */
    @Test
    public void aSecondWriteFindsTheParameterInTheContainerItCreated()
        throws Exception
    {
        run("set_output_parameter", "name", PARAMETER, "value", WRITTEN); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        EObject created = entries().get(0);

        run("set_output_parameter", "name", "заголовок", "value", "Другой"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> items = entries();
        assertEquals("the second call finds the entry rather than adding another", //$NON-NLS-1$
            1, items.size());
        assertSame("and it is the entry the first call created", created, items.get(0)); //$NON-NLS-1$
        assertEquals("the key keeps the spelling the first call wrote", PARAMETER, keyOf(created)); //$NON-NLS-1$
        assertEquals("the value is the one the second call wrote", "Другой", valueOf(created)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A container that already holds the parameter - the shape a serialized schema has - is found
     * by name, and the write updates that entry.
     *
     * @throws Exception if the operation refuses
     */
    @Test
    public void anAlreadySerializedContainerIsFoundByName()
        throws Exception
    {
        EObject present = containerAlreadyHolding(PARAMETER, "Старый"); //$NON-NLS-1$

        run("set_output_parameter", "name", PARAMETER, "value", WRITTEN); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> items = entries();
        assertEquals("a parameter the container already holds is not added again", //$NON-NLS-1$
            1, items.size());
        assertSame("the write finds the entry that was already there", present, items.get(0)); //$NON-NLS-1$
        assertEquals("reading it back returns what was written", WRITTEN, valueOf(present)); //$NON-NLS-1$
    }

    /**
     * Omitting {@code value} is refused, and the literal already stored stays.
     *
     * @throws Exception if a call that should succeed refuses
     */
    @Test
    public void aCallWithoutAValueIsRefusedAndTheLiteralStays() throws Exception
    {
        run("set_output_parameter", "name", PARAMETER, "value", WRITTEN); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        EObject literal = BmDcsHelper.getEObjectList(entries().get(0), "getValues").get(0); //$NON-NLS-1$

        try
        {
            run("set_output_parameter", "name", PARAMETER); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a call with no value must not clear the one already stored"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertTrue("the refusal says there was nothing to set: " + e.getMessage(), //$NON-NLS-1$
                String.valueOf(e.getMessage()).contains("nothing to set")); //$NON-NLS-1$
        }

        EList<EObject> values = BmDcsHelper.getEObjectList(entries().get(0), "getValues"); //$NON-NLS-1$
        assertEquals("the literal that was there is still the only one", 1, values.size()); //$NON-NLS-1$
        assertSame("and it is the same literal", literal, values.get(0)); //$NON-NLS-1$
        assertEquals("reading it back returns what was written", WRITTEN, valueOf(entries().get(0))); //$NON-NLS-1$
    }

    /**
     * Puts an output-parameter container, with one named entry, on the schema's first variant.
     * <p>
     * That is the shape {@code defaultSettingsFor} does not produce: the container is already set,
     * the way a schema read back from a file has it.
     * </p>
     *
     * @param name the parameter name to put on the entry
     * @param value the value the entry holds before the call
     * @return the entry, so a later read can tell it was updated rather than replaced
     */
    private EObject containerAlreadyHolding(String name, String value)
    {
        Object variant = BmDcsHelper.createElement("createSettingsVariant"); //$NON-NLS-1$
        Object settings = BmDcsHelper.createElement("createDataCompositionSettings"); //$NON-NLS-1$
        Object container = BmDcsHelper.createElement("createDataCompositionOutputParameterValues"); //$NON-NLS-1$
        Object entry = BmDcsHelper.createElement("createSettingsParameterValue"); //$NON-NLS-1$
        Object key = BmDcsHelper.createElement("createDataCompositionParameter"); //$NON-NLS-1$
        assertNotNull("the settings model has to be reachable in this runtime", variant); //$NON-NLS-1$
        assertNotNull("a variant needs settings", settings); //$NON-NLS-1$
        assertNotNull("the output-parameter container has to be creatable", container); //$NON-NLS-1$
        assertNotNull("an output parameter is a settings parameter value", entry); //$NON-NLS-1$
        assertNotNull("an entry is keyed by a parameter", key); //$NON-NLS-1$

        assertEquals(null, BmDcsHelper.setProperty(key, "value", name)); //$NON-NLS-1$
        assertEquals(null, BmDcsHelper.setProperty(entry, "parameter", key)); //$NON-NLS-1$
        EList<EObject> values = BmDcsHelper.getEObjectList(entry, "getValues"); //$NON-NLS-1$
        assertNotNull("a pre-built entry holds its value the same way a written one does", values); //$NON-NLS-1$
        Object literal = BmDcsHelper.createLiteralValue(value);
        assertNotNull("the value has to be a literal the model can hold", literal); //$NON-NLS-1$
        values.add((EObject)literal);
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        assertNotNull("a pre-built container lists its entries", items); //$NON-NLS-1$
        items.add((EObject)entry);

        assertEquals(null, BmDcsHelper.setProperty(settings, "outputParameters", container)); //$NON-NLS-1$
        assertEquals(null, BmDcsHelper.setProperty(variant, "name", "Основной")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, BmDcsHelper.setProperty(variant, "settings", settings)); //$NON-NLS-1$
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("a schema lists its settings variants", variants); //$NON-NLS-1$
        variants.add((EObject)variant);
        return (EObject)entry;
    }
}
