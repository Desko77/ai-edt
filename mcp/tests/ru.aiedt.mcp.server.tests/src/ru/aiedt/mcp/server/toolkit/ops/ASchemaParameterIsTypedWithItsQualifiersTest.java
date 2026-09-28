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
 * What {@code add_parameter} and {@code set_parameter} write as the value type of a schema
 * parameter.
 * <p>
 * The type is written together with the qualifiers the call names, the date composition among
 * them. A type that does not resolve, a qualifier none of the requested types accepts, and a
 * qualifier without a type refuse the call, and the schema keeps the parameters it had.
 * </p>
 */
public class ASchemaParameterIsTypedWithItsQualifiersTest
{
    private EObject schema;

    private DcsWorkshopTool tool;

    @Before
    public void buildAnEmptySchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
    }

    @Test
    public void aDateParameterCarriesTheNamedDateComposition() throws Exception
    {
        tool.applyToSchemaForTest("add_parameter", params("name", "Период", "type", "Date", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dateFractions", "DateTime"), schema); //$NON-NLS-1$ //$NON-NLS-2$

        Object valueType = get(parameter("Период"), "getValueType"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the parameter has to carry a value type", valueType); //$NON-NLS-1$
        Object types = get(valueType, "getTypes"); //$NON-NLS-1$
        assertTrue("the value type has to name the type", //$NON-NLS-1$
            types instanceof Collection && ((Collection<?>)types).size() == 1);
        Object dateQualifiers = get(valueType, "getDateQualifiers"); //$NON-NLS-1$
        assertNotNull("a Date type carries its date qualifiers", dateQualifiers); //$NON-NLS-1$
        assertEquals("DateTime", String.valueOf(get(dateQualifiers, "getDateFractions"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void setParameterChangesTheDateComposition() throws Exception
    {
        tool.applyToSchemaForTest("add_parameter", params("name", "Период", "type", "Date"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            schema);
        tool.applyToSchemaForTest("set_parameter", params("name", "Период", "type", "Date", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dateFractions", "Time"), schema); //$NON-NLS-1$ //$NON-NLS-2$

        Object valueType = get(parameter("Период"), "getValueType"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Time", //$NON-NLS-1$
            String.valueOf(get(get(valueType, "getDateQualifiers"), "getDateFractions"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnresolvedTypeIsRefusedAndNoParameterIsAdded() throws Exception
    {
        assertRefusedWithoutAParameter(params("name", "Период", "type", "НетТакогоТипа"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "НетТакогоТипа"); //$NON-NLS-1$
    }

    @Test
    public void aQualifierNoRequestedTypeAcceptsIsRefused() throws Exception
    {
        assertRefusedWithoutAParameter(params("name", "Сумма", "type", "Number", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dateFractions", "DateTime"), "dateFractions"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aQualifierWithoutATypeIsRefused() throws Exception
    {
        assertRefusedWithoutAParameter(params("name", "Период", "dateFractions", "DateTime"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "'type'"); //$NON-NLS-1$
    }

    private void assertRefusedWithoutAParameter(Map<String, String> params, String named)
        throws Exception
    {
        int before = parameterCount();
        try
        {
            tool.applyToSchemaForTest("add_parameter", params, schema); //$NON-NLS-1$
            fail("a type that was not applied was reported as written"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains(named));
            assertTrue(refused.getMessage(), refused.getMessage().contains("Nothing was written")); //$NON-NLS-1$
        }
        assertEquals("the refused parameter must not stay in the schema", before, parameterCount()); //$NON-NLS-1$
    }

    private int parameterCount()
    {
        Collection<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        return parameters == null ? 0 : parameters.size();
    }

    private EObject parameter(String name)
    {
        EObject parameter = BmDcsHelper.findByNameInList(schema, "getParameters", name); //$NON-NLS-1$
        assertNotNull("the parameter has to be in the schema", parameter); //$NON-NLS-1$
        return parameter;
    }

    private static Object get(Object target, String getter) throws Exception
    {
        Method method = target.getClass().getMethod(getter);
        return method.invoke(target);
    }

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
