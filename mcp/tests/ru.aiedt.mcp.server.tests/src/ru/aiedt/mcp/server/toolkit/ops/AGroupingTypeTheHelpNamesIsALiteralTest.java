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
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
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
 * The grouping types the help names are literals of the enumeration, and a value that is not is
 * refused.
 * <p>
 * The help used to name {@code Standard} and {@code DetailRecords}, which the enumeration does not
 * have, and the setter's refusal was discarded, so the call reported success and the model kept
 * the default.
 * </p>
 */
public class AGroupingTypeTheHelpNamesIsALiteralTest
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
     * How many structure items the first variant holds.
     *
     * @return the count, or 0 when the settings were never created
     */
    private int structureSize()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null || variants.isEmpty())
        {
            return 0;
        }
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        if (settings == null)
        {
            return 0;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(settings, "getItems"); //$NON-NLS-1$
        return items == null ? 0 : items.size();
    }

    /**
     * The grouping-type bullets of the property-values help.
     *
     * @return the tokens, in the order the help lists them
     * @throws Exception if the help cannot be read
     */
    private static List<String> groupingTypesNamedByHelp() throws Exception
    {
        Method method = DcsWorkshopTool.class.getDeclaredMethod("buildPropertyValuesHelp"); //$NON-NLS-1$
        method.setAccessible(true);
        String text = (String)method.invoke(new DcsWorkshopTool());
        List<String> literals = new ArrayList<>();
        boolean inSection = false;
        for (String line : text.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("**groupingType**")) //$NON-NLS-1$
            {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("**")) //$NON-NLS-1$
            {
                break;
            }
            if (inSection && line.startsWith("- ")) //$NON-NLS-1$
            {
                String token = line.substring(2).trim();
                int space = token.indexOf(' ');
                if (space > 0)
                {
                    token = token.substring(0, space);
                }
                while (token.endsWith(".") || token.endsWith(",")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    token = token.substring(0, token.length() - 1);
                }
                literals.add(token);
            }
        }
        return literals;
    }

    /**
     * {@code Standard} is not a literal, so the grouping is not added.
     */
    @Test
    public void standardIsRefusedAndNoGroupingIsAdded()
    {
        try
        {
            run("add_grouping", "field", "Tovar", "groupingType", "Standard"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("Standard is not a grouping type, so the call must be refused"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal carries the setter's reason: " + message, //$NON-NLS-1$
                message.contains("Failed to set")); //$NON-NLS-1$
            assertTrue(message, message.contains("groupType") || message.contains("Standard")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals("the structure gained no grouping", 0, structureSize()); //$NON-NLS-1$
    }

    /**
     * Every grouping type the help names resolves through the same coercion the setter uses.
     *
     * @throws Exception if the help cannot be read
     */
    @Test
    public void everyGroupingTypeTheHelpNamesIsALiteral() throws Exception
    {
        List<String> literals = groupingTypesNamedByHelp();
        assertFalse("the help has to name the grouping types", literals.isEmpty()); //$NON-NLS-1$
        assertFalse("Standard is not a literal", literals.contains("Standard")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("DetailRecords is not a literal", literals.contains("DetailRecords")); //$NON-NLS-1$ //$NON-NLS-2$
        Object field = BmDcsHelper.createElement("createDataCompositionGroupField"); //$NON-NLS-1$
        assertNotNull("a group field has to be creatable in this runtime", field); //$NON-NLS-1$
        for (String literal : literals)
        {
            assertNull(literal + " is named by the help and must be a literal", //$NON-NLS-1$
                BmDcsHelper.setProperty(field, "groupType", literal)); //$NON-NLS-1$
        }
    }
}
