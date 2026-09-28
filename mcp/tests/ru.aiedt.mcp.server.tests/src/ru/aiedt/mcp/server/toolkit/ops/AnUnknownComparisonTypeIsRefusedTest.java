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
 * An unknown comparison or sort literal is refused before the element is added.
 * <p>
 * The setter's answer used to be discarded, so the call reported success and the model kept the
 * enumeration's default. The refusal carries that answer and the literals the enumeration accepts.
 * </p>
 */
public class AnUnknownComparisonTypeIsRefusedTest
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
     * The settings of the first variant, which these operations create.
     *
     * @return the settings object
     */
    private EObject settings()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull(variants);
        assertTrue("the call reached the settings", !variants.isEmpty()); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull(settings);
        return (EObject)settings;
    }

    /**
     * How many items a settings child holds.
     *
     * @param feature the child feature, such as {@code filter}
     * @return the item count, or 0 when the child was never created
     */
    private int itemCount(String feature)
    {
        Object child = settings().eGet(settings().eClass().getEStructuralFeature(feature));
        if (child == null)
        {
            return 0;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(child, "getItems"); //$NON-NLS-1$
        return items == null ? 0 : items.size();
    }

    /**
     * Asserts that the refusal names the failed property and the literals that would have worked.
     *
     * @param error the refusal
     * @param property the property that did not take
     */
    private static void assertRefusalNamesTheLiterals(Exception error, String property)
    {
        String message = String.valueOf(error.getMessage());
        assertTrue("the refusal carries the setter's reason: " + message, //$NON-NLS-1$
            message.contains("Failed to set " + property)); //$NON-NLS-1$
        assertTrue("and it names the literals that are allowed: " + message, //$NON-NLS-1$
            message.contains("Allowed:")); //$NON-NLS-1$
        assertTrue("set off from the setter's answer as a sentence of its own: " + message, //$NON-NLS-1$
            message.contains(". Allowed: ")); //$NON-NLS-1$
    }

    /**
     * An unknown filter comparison adds nothing.
     */
    @Test
    public void anUnknownFilterComparisonAddsNothing()
    {
        try
        {
            run("add_filter", "field", "Контрагент", "comparisonType", "Больше", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "value", "10"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an unknown comparison type must be refused before the filter gains an item"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertRefusalNamesTheLiterals(e, "comparisonType"); //$NON-NLS-1$
        }
        assertEquals("the filter gained no item", 0, itemCount("filter")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An unknown appearance comparison adds nothing.
     */
    @Test
    public void anUnknownAppearanceComparisonAddsNothing()
    {
        try
        {
            run("add_appearance", "field", "Контрагент", "conditionType", "Больше", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "conditionValue", "10"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an unknown condition type must be refused before the appearance gains an item"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertRefusalNamesTheLiterals(e, "comparisonType"); //$NON-NLS-1$
        }
        assertEquals("the appearance gained no item", 0, itemCount("conditionalAppearance")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An unknown sort direction adds nothing.
     */
    @Test
    public void anUnknownOrderTypeAddsNothing()
    {
        try
        {
            run("add_order", "field", "Контрагент", "orderType", "Sideways"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("an unknown order type must be refused before the order gains an item"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertRefusalNamesTheLiterals(e, "orderType"); //$NON-NLS-1$
        }
        assertEquals("the order gained no item", 0, itemCount("order")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
