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
 * A dataset-link property that did not take is refused by the call that asked for it.
 * <p>
 * The setter's answer was discarded and the operation answered "set" for a property the link does
 * not carry. The model kept nothing, the schema reached disk byte-identical to what it held, and
 * the refusal came back from the write path as {@code schemaUnchanged} - a message naming neither
 * the property nor the link. The answer is read now, and the refusal names both.
 * </p>
 */
public class ALinkPropertyThatWasNotSetIsRefusedTest
{
    private static final String SOURCE = "Набор"; //$NON-NLS-1$

    private static final String DESTINATION = "НаборДетальный"; //$NON-NLS-1$

    private static final String EXPRESSION = "Ссылка"; //$NON-NLS-1$

    private DcsWorkshopTool tool;

    private EObject schema;

    /**
     * Builds a schema carrying one dataset link.
     *
     * @throws Exception if the link cannot be added
     */
    @Before
    public void buildASchemaWithALink() throws Exception
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
        run("add_dataset_link", "sourceDataSet", SOURCE, "destinationDataSet", DESTINATION); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
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
     * The one dataset link of the schema.
     *
     * @return the link
     */
    private EObject theLink()
    {
        EList<EObject> links = BmDcsHelper.getEObjectList(schema, "getDataSetLinks"); //$NON-NLS-1$
        assertNotNull("a schema lists its dataset links", links); //$NON-NLS-1$
        assertEquals("the call adds one link", 1, links.size()); //$NON-NLS-1$
        return links.get(0);
    }

    /**
     * What a property of the link holds.
     *
     * @param property the feature name, such as {@code sourceExpression}
     * @return the value, or <code>null</code> when the link holds none
     */
    private Object valueOf(String property)
    {
        EObject link = theLink();
        return link.eGet(link.eClass().getEStructuralFeature(property));
    }

    /**
     * A property the link does not carry is refused, and the refusal names it.
     *
     * @throws Exception if the call that should succeed refuses
     */
    @Test
    public void aPropertyTheLinkDoesNotCarryIsRefused() throws Exception
    {
        try
        {
            run("set_dataset_link_property", "sourceDataSet", SOURCE, "destinationDataSet", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                DESTINATION, "property", "expression", "value", EXPRESSION); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a property the link does not carry cannot be reported as set"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names the property: " + message, //$NON-NLS-1$
                message.contains("expression")); //$NON-NLS-1$
            assertTrue("and the link it was asked of: " + message, //$NON-NLS-1$
                message.contains(SOURCE + " -> " + DESTINATION)); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("and it carries the setter's own answer: " + message, //$NON-NLS-1$
                message.contains("Property 'expression' is absent on")); //$NON-NLS-1$
            assertTrue("the refusal is not the write path's unchanged-file guard, which names " //$NON-NLS-1$
                + "neither: " + message, !message.contains("byte-identical")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals("and the link was left as it was", null, valueOf("sourceExpression")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A property the link carries is written and reads back.
     *
     * @throws Exception if the operation refuses
     */
    @Test
    public void aPropertyTheLinkCarriesIsWritten() throws Exception
    {
        Object answer = run("set_dataset_link_property", "sourceDataSet", SOURCE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "destinationDataSet", DESTINATION, "property", "sourceExpression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "value", EXPRESSION); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the answer reports the property set: " + answer, //$NON-NLS-1$
            String.valueOf(answer).contains("property 'sourceExpression' set")); //$NON-NLS-1$
        assertEquals("and the link holds the value", EXPRESSION, valueOf("sourceExpression")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
