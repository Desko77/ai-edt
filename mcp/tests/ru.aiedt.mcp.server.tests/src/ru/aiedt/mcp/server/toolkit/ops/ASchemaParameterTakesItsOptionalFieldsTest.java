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

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * What {@code add_parameter} and {@code set_parameter} write into the optional fields of a schema
 * parameter: {@code use}, {@code expression}, {@code valueListAllowed} and
 * {@code denyIncompleteValues}.
 * <p>
 * {@code use} is the parameter's enumeration Auto / Always, reached from {@code true}/{@code false},
 * the English names and the Russian ones. A value it cannot take refuses the call, and the parameter
 * keeps what it had.
 * </p>
 */
public class ASchemaParameterTakesItsOptionalFieldsTest
{
    private EObject schema;

    private DcsWorkshopTool tool;

    /**
     * Builds an empty composition schema in memory.
     */
    @Before
    public void buildAnEmptySchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
    }

    /**
     * {@code use=true} is Always, {@code use=Auto} is Auto and {@code use=Всегда} is Always.
     *
     * @throws Exception when the parameter cannot be read back
     */
    @Test
    public void useIsTheParametersEnumeration() throws Exception
    {
        tool.applyToSchemaForTest("add_parameter", params("name", "Первый", "use", "true"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertUse("Always", "Первый"); //$NON-NLS-1$ //$NON-NLS-2$

        tool.applyToSchemaForTest("add_parameter", params("name", "Второй", "use", "Auto"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertUse("Auto", "Второй"); //$NON-NLS-1$ //$NON-NLS-2$

        tool.applyToSchemaForTest("set_parameter", params("name", "Второй", "use", "всегда"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertUse("Always", "Второй"); //$NON-NLS-1$ //$NON-NLS-2$

        tool.applyToSchemaForTest("set_parameter", params("name", "Второй", "use", "false"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertUse("Auto", "Второй"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A value {@code use} cannot take refuses the call: a new parameter is not added, an existing
     * one keeps its value.
     *
     * @throws Exception when the parameter cannot be read back
     */
    @Test
    public void anUnknownUseIsRefused() throws Exception
    {
        int before = parameterCount();
        assertRefused("add_parameter", params("name", "Третий", "use", "Maybe")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("the refused parameter must not stay in the schema", before, parameterCount()); //$NON-NLS-1$

        tool.applyToSchemaForTest("add_parameter", params("name", "Третий", "use", "Always"), schema); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertRefused("set_parameter", params("name", "Третий", "use", "Maybe")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertUse("Always", "Третий"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code expression}, {@code valueListAllowed} and {@code denyIncompleteValues} are written
     * into the parameter.
     *
     * @throws Exception when the parameter cannot be read back
     */
    @Test
    public void theOtherFieldsAreWritten() throws Exception
    {
        tool.applyToSchemaForTest("add_parameter", params("name", "Период", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "expression", "ТекущаяДата()", "valueListAllowed", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "denyIncompleteValues", "true"), schema); //$NON-NLS-1$ //$NON-NLS-2$

        EObject parameter = parameter("Период"); //$NON-NLS-1$
        assertEquals("ТекущаяДата()", get(parameter, "getExpression")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, get(parameter, "isValueListAllowed")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, get(parameter, "isDenyIncompleteValues")); //$NON-NLS-1$

        tool.applyToSchemaForTest("set_parameter", params("name", "Период", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "valueListAllowed", "false"), schema); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, get(parameter, "isValueListAllowed")); //$NON-NLS-1$
        assertEquals("a field the call does not name is kept", Boolean.TRUE, //$NON-NLS-1$
            get(parameter, "isDenyIncompleteValues")); //$NON-NLS-1$
    }

    /**
     * Asserts the value of {@code use} of one parameter.
     *
     * @param expected the literal expected
     * @param name the parameter name
     * @throws Exception when the parameter cannot be read back
     */
    private void assertUse(String expected, String name) throws Exception
    {
        Object use = get(parameter(name), "getUse"); //$NON-NLS-1$
        assertNotNull("use has to be set", use); //$NON-NLS-1$
        assertTrue(expected + " expected, got " + use, expected.equalsIgnoreCase(String.valueOf(use))); //$NON-NLS-1$
    }

    /**
     * Asserts that a call is refused with the values {@code use} can take.
     *
     * @param operation the operation to call
     * @param params the call's arguments
     * @throws Exception when the call fails in another way
     */
    private void assertRefused(String operation, Map<String, String> params) throws Exception
    {
        try
        {
            tool.applyToSchemaForTest(operation, params, schema);
            fail("use=Maybe was reported as written"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Auto or Always")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains("Nothing was written")); //$NON-NLS-1$
        }
    }

    /**
     * @return the number of parameters in the schema
     */
    private int parameterCount()
    {
        Collection<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        return parameters == null ? 0 : parameters.size();
    }

    /**
     * @param name the parameter name
     * @return the parameter, which has to be in the schema
     */
    private EObject parameter(String name)
    {
        EObject parameter = BmDcsHelper.findByNameInList(schema, "getParameters", name); //$NON-NLS-1$
        assertNotNull("the parameter has to be in the schema", parameter); //$NON-NLS-1$
        return parameter;
    }

    /**
     * @param target the object
     * @param getter the getter name
     * @return what the getter returns
     * @throws Exception when the getter cannot be called
     */
    private static Object get(Object target, String getter) throws Exception
    {
        Method method = target.getClass().getMethod(getter);
        return method.invoke(target);
    }

    /**
     * @param keysAndValues argument names and values, alternating
     * @return the call's arguments
     */
    private static Map<String, String> params(String... keysAndValues)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }
}
