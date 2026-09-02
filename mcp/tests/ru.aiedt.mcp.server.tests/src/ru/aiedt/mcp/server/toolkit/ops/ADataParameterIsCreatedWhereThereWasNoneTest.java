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
}
