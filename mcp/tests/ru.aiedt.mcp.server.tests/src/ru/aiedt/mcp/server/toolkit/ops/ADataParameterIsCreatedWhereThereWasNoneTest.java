/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Setting a data parameter of a settings variant.
 * <p>
 * A settings object carries no data parameters until something puts them there, and the absent
 * container was reported as {@code DefaultSettings.getDataParameters() not available} - which names
 * a missing method rather than an empty property, and left the operation with nothing it could do.
 * The container and the entry are now created, and the entry is a
 * {@code SettingsParameterValue}, the carrier that holds a {@code userSettingID}.
 * </p>
 * <p>
 * Run against a real composition schema from {@code DcsFactory.eINSTANCE}, which needs no workspace.
 * </p>
 */
public class ADataParameterIsCreatedWhereThereWasNoneTest
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

    /** The data-parameter entries of the first settings variant. */
    private EList<EObject> entries()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the schema should have a variant by now", variants); //$NON-NLS-1$
        assertTrue("the schema should have a variant by now", !variants.isEmpty()); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("a variant with no settings holds no parameters", settings); //$NON-NLS-1$
        EObject asObject = (EObject)settings;
        Object container =
            asObject.eGet(asObject.eClass().getEStructuralFeature("dataParameters")); //$NON-NLS-1$
        assertNotNull("the container is created when the settings carry none", container); //$NON-NLS-1$
        return BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
    }

    private static String keyOf(EObject entry)
    {
        Object parameter = entry.eGet(entry.eClass().getEStructuralFeature("parameter")); //$NON-NLS-1$
        assertNotNull("an entry with no parameter names nothing", parameter); //$NON-NLS-1$
        EObject asObject = (EObject)parameter;
        return String.valueOf(asObject.eGet(asObject.eClass().getEStructuralFeature("value"))); //$NON-NLS-1$
    }

    @Test
    public void aParameterTheSchemaDeclaresIsSetOnSettingsThatHadNone() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> items = entries();
        assertEquals("the parameter belongs in the settings once", 1, items.size()); //$NON-NLS-1$
        assertEquals("and under the name it was asked for", "Период", keyOf(items.get(0))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void settingItTwiceLeavesOneEntry() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("set_settings_parameter", "name", "Период", "value", "2026-02-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("the second call finds the entry rather than adding another", //$NON-NLS-1$
            1, entries().size());
    }

    @Test
    public void aUserSettingIdentifierReachesTheEntry() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("set_settings_parameter", "name", "Период", "value", "2026-01-01", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "userSettingID", "b1f0"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject entry = entries().get(0);
        assertEquals("without it the parameter never reaches user settings", "b1f0", //$NON-NLS-1$ //$NON-NLS-2$
            entry.eGet(entry.eClass().getEStructuralFeature("userSettingID"))); //$NON-NLS-1$
    }

    @Test
    public void aNameSpelledInAnotherCaseAddressesTheSameParameter() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("set_settings_parameter", "name", "период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EList<EObject> items = entries();
        assertEquals("one parameter, not two spellings of it", 1, items.size()); //$NON-NLS-1$
        assertEquals("the key takes the spelling the schema declares, or the setting addresses " //$NON-NLS-1$
            + "nothing", "Период", keyOf(items.get(0))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aParameterTheSchemaDoesNotDeclareIsRefusedAndTheDeclaredOnesAreNamed()
        throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("set_settings_parameter", "name", "Склад", "value", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a setting for a parameter the schema does not declare is read by nothing"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names what the schema does declare: " + message, //$NON-NLS-1$
                message.contains("Период")); //$NON-NLS-1$
        }
    }

    @Test
    public void aSchemaWithNoVariantsGetsTheOneThatWasNamed() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("set_settings_parameter", "name", "Период", "value", "1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "variantName", "Сводный"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertEquals("a schema with no variants gets one, as it does without variantName", //$NON-NLS-1$
            1, variants.size());
        assertEquals("and it carries the name that was asked for", "Сводный", //$NON-NLS-1$ //$NON-NLS-2$
            variants.get(0).eGet(variants.get(0).eClass().getEStructuralFeature("name"))); //$NON-NLS-1$
    }

    @Test
    public void aVariantThatIsNotThereIsRefusedAndTheOnesThatAreGetNamed() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_settings_variant", "name", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try
        {
            run("set_settings_parameter", "name", "Период", "value", "1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "variantName", "Сводный"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("writing into a variant that is not there writes into nothing"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names the variants there are: " + message, //$NON-NLS-1$
                message.contains("Основной")); //$NON-NLS-1$
        }
    }

    @Test
    public void aNamedVariantIsTheOneWrittenInto() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_settings_variant", "name", "Сводный"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("set_settings_parameter", "name", "Период", "value", "1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "variantName", "Сводный"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        EObject named = null;
        for (EObject variant : variants)
        {
            if ("Сводный".equals(variant.eGet(variant.eClass().getEStructuralFeature("name")))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                named = variant;
            }
        }
        assertNotNull("the variant that was named should still be there", named); //$NON-NLS-1$
        Object settings = named.eGet(named.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("the named variant should carry settings", settings); //$NON-NLS-1$
        EObject asObject = (EObject)settings;
        Object container =
            asObject.eGet(asObject.eClass().getEStructuralFeature("dataParameters")); //$NON-NLS-1$
        assertNotNull("the parameter went to the variant that was named", container); //$NON-NLS-1$
        assertEquals("and it is there once", 1, //$NON-NLS-1$
            BmDcsHelper.getEObjectList(container, "getItems").size()); //$NON-NLS-1$
    }

    /**
     * The literals an entry holds.
     *
     * @param entry one settings-parameter entry
     * @return {@code getValues()}, never <code>null</code>
     */
    private static EList<EObject> valuesOf(EObject entry)
    {
        EList<EObject> values = BmDcsHelper.getEObjectList(entry, "getValues"); //$NON-NLS-1$
        assertNotNull("a settings parameter holds its value in getValues()", values); //$NON-NLS-1$
        return values;
    }

    /**
     * Omitting {@code value} must not wipe the literal already stored.
     * <p>
     * A call that names the parameter and nothing else has nothing to change, so it is refused.
     * The refusal happens before the value list is cleared.
     * </p>
     *
     * @throws Exception if a call that should succeed refuses
     */
    @Test
    public void aCallThatPassesNoValueLeavesTheLiteralWhereItWas() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        EObject literal = valuesOf(entries().get(0)).get(0);

        try
        {
            run("set_settings_parameter", "name", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a call that changes nothing must not report the value as set"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertTrue("the refusal says there was nothing to set: " + e.getMessage(), //$NON-NLS-1$
                String.valueOf(e.getMessage()).contains("nothing to set")); //$NON-NLS-1$
        }

        EList<EObject> values = valuesOf(entries().get(0));
        assertEquals("the literal that was there is still the only one", 1, values.size()); //$NON-NLS-1$
        assertSame("and it is the same literal, not a replacement with no value", //$NON-NLS-1$
            literal, values.get(0));
    }

    /**
     * Setting only {@code userSettingID} leaves the value and says so.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void aUserSettingIdentifierWithoutAValueLeavesTheLiteralAndNamesWhatChanged()
        throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        EObject literal = valuesOf(entries().get(0)).get(0);

        Object answer = run("set_settings_parameter", "name", "Период", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "userSettingID", "b1f0"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> values = valuesOf(entries().get(0));
        assertEquals(1, values.size());
        assertSame("the value was not part of this call, so it stays", literal, values.get(0)); //$NON-NLS-1$
        String text = String.valueOf(answer);
        assertTrue("the answer names the identifier that changed: " + text, //$NON-NLS-1$
            text.contains("userSettingID")); //$NON-NLS-1$
        assertFalse("and it does not claim the value was set: " + text, //$NON-NLS-1$
            text.contains("' set") || text.contains("added and set")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Setting only {@code use} leaves the value and says so.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void useWithoutAValueLeavesTheLiteralAndNamesWhatChanged() throws Exception
    {
        run("add_parameter", "name", "Период", "type", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("set_settings_parameter", "name", "Период", "value", "2026-01-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        EObject literal = valuesOf(entries().get(0)).get(0);

        Object answer = run("set_settings_parameter", "name", "Период", "use", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        EObject entry = entries().get(0);
        EList<EObject> values = valuesOf(entry);
        assertEquals(1, values.size());
        assertSame("the value was not part of this call, so it stays", literal, values.get(0)); //$NON-NLS-1$
        assertEquals("the flag is what the call asked for", Boolean.FALSE, //$NON-NLS-1$
            entry.eGet(entry.eClass().getEStructuralFeature("use"))); //$NON-NLS-1$
        String text = String.valueOf(answer);
        assertTrue("the answer names the flag that changed: " + text, text.contains("use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and it does not claim the value was set: " + text, //$NON-NLS-1$
            text.contains("' set") || text.contains("added and set")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
