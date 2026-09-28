/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A mutation that adds something names the text the file must carry afterwards.
 * <p>
 * The lost-write check reads that claim only when the handler returns {@link BmDcsHelper.Wrote}.
 * A plain string leaves the check with nothing to look for, so a write that landed and was then
 * overwritten answers as a success.
 * </p>
 * <p>
 * A removal returns the same label with an empty claim. A presence claim on a deletion makes a
 * successful removal look like a lost write: the file shrinks, the check sees that it did not
 * grow, and the retry does not find the dataset.
 * </p>
 */
public class AMutationNamesWhatItWroteTest
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
     * Asserts that an addition named something the file has to contain.
     *
     * @param result what the operation returned
     * @param name the element name, which the claim has to carry
     */
    private static void assertNamesWhatWasAdded(Object result, String name)
    {
        assertTrue("the result has to be a write claim, not a bare string: " + result, //$NON-NLS-1$
            result instanceof BmDcsHelper.Wrote);
        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)result;
        assertNotNull("the claim is the text the file must carry", wrote.mustAppear); //$NON-NLS-1$
        assertFalse("an empty claim checks nothing", wrote.mustAppear.isEmpty()); //$NON-NLS-1$
        assertTrue("the claim has to name what was added: " + wrote.mustAppear, //$NON-NLS-1$
            wrote.mustAppear.contains(name));
        assertTrue("the count is what the collection held after the write", //$NON-NLS-1$
            wrote.countAfterWrite >= 1);
    }

    /**
     * Adding a dataset names the dataset.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void addingADataSetNamesWhatTheFileMustCarry() throws Exception
    {
        assertNamesWhatWasAdded(run("add_dataset", "name", "Продажи"), "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Removing a dataset reports the collection it changed and does not claim a presence.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void removingADataSetNamesTheCollectionItChanged() throws Exception
    {
        run("add_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Object result = run("remove_dataset", "name", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result instanceof BmDcsHelper.Wrote);
        BmDcsHelper.Wrote wrote = (BmDcsHelper.Wrote)result;
        assertEquals("the dataset is no longer in the collection", 0, wrote.countAfterWrite); //$NON-NLS-1$
        assertEquals("dataSets", wrote.countScope); //$NON-NLS-1$
        assertTrue("a removal does not claim text the file must still contain", //$NON-NLS-1$
            wrote.mustAppear == null || wrote.mustAppear.isEmpty());
    }

    /**
     * Adding a parameter names the parameter.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void addingAParameterNamesWhatTheFileMustCarry() throws Exception
    {
        assertNamesWhatWasAdded(run("add_parameter", "name", "Период", "type", "Date"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Период"); //$NON-NLS-1$
    }

    /**
     * Adding a calculated field names the field.
     *
     * @throws Exception if the call refuses
     */
    @Test
    public void addingACalculatedFieldNamesWhatTheFileMustCarry() throws Exception
    {
        assertNamesWhatWasAdded(run("add_calculated_field", "name", "Итого", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "expression", "Сумма"), "Итого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
