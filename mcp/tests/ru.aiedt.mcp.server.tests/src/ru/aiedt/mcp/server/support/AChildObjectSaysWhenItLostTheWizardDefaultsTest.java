/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * A child metadata object - an enumeration value, a recalculation, a service method - is created
 * through the project-aware factory, and when that factory cannot run the object still comes from
 * the raw factory and the caller is told what it lacks.
 */
public class AChildObjectSaysWhenItLostTheWizardDefaultsTest
{
    private final List<String> warnings = new ArrayList<>();

    /**
     * Puts the factory lookup back to the service registry.
     */
    @After
    public void restoreFactoryLookup()
    {
        BmObjectHelper.setFactorySupplier(null);
    }

    /**
     * @param answer what {@code create} returns when it does not throw
     * @param failure what {@code create} throws, or <code>null</code>
     * @return a factory stand-in
     */
    private static IModelObjectFactory factory(Object answer, RuntimeException failure)
    {
        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
            {
                if (!"create".equals(method.getName())) //$NON-NLS-1$
                {
                    return null;
                }
                if (failure != null)
                {
                    throw failure;
                }
                return answer;
            }
        };
        return (IModelObjectFactory)Proxy.newProxyInstance(IModelObjectFactory.class.getClassLoader(),
            new Class<?>[] { IModelObjectFactory.class }, handler);
    }

    /**
     * @return a project stand-in that answers nothing
     */
    private static IV8Project project()
    {
        return (IV8Project)Proxy.newProxyInstance(IV8Project.class.getClassLoader(),
            new Class<?>[] { IV8Project.class }, (proxy, method, args) -> null);
    }

    /** A factory that produces the object gives it back, with nothing to warn about. */
    @Test
    public void anObjectFromTheFactoryCarriesNoWarning()
    {
        EnumValue made = MdClassFactory.eINSTANCE.createEnumValue();
        BmObjectHelper.setFactorySupplier(() -> factory(made, null));

        MdObject created = BmObjectHelper.createChildObject("EnumValue", project(), warnings::add); //$NON-NLS-1$

        assertSame(made, created);
        assertTrue(warnings.toString(), warnings.isEmpty());
    }

    /** A factory that throws leaves a raw object and a warning naming what it lacks. */
    @Test
    public void aFailingFactoryLeavesARawObjectAndAWarning()
    {
        BmObjectHelper.setFactorySupplier(() -> factory(null, new IllegalStateException("boom"))); //$NON-NLS-1$

        MdObject created = BmObjectHelper.createChildObject("EnumValue", project(), warnings::add); //$NON-NLS-1$

        assertTrue(String.valueOf(created), created instanceof EnumValue);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).startsWith("Created without the EDT wizard defaults")); //$NON-NLS-1$
        assertTrue(warnings.get(0), warnings.get(0).contains("boom")); //$NON-NLS-1$
    }

    /** No V8 project leaves a raw object and a warning. */
    @Test
    public void noProjectLeavesARawObjectAndAWarning()
    {
        MdObject created = BmObjectHelper.createChildObject("RecalculationDimension", null, warnings::add); //$NON-NLS-1$

        assertNotNull(created);
        assertEquals(1, warnings.size());
    }

    /** A type that does not resolve creates nothing and warns about nothing. */
    @Test
    public void anUnknownTypeCreatesNothing()
    {
        assertNull(BmObjectHelper.createChildObject("NoSuchType", project(), warnings::add)); //$NON-NLS-1$
        assertTrue(warnings.toString(), warnings.isEmpty());
    }

    /** No project, no V8 project. */
    @Test
    public void noWorkspaceProjectHasNoV8Project()
    {
        assertNull(BmObjectHelper.v8ProjectOf(null));
    }
}
