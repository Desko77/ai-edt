/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.junit.Test;

/**
 * Holds an index failure on a call-hierarchy walk to staying in the answer.
 * <p>
 * {@code findAllReferences} throwing was logged and dropped. Both the direct walk and the
 * transitive one then rendered an empty caller list, which reads as "nothing calls this method".
 * </p>
 */
public class AnIndexFailureIsNotAnEmptyCallGraphTest
{
    /** The same failure is the answer of the direct walk and of the transitive one. */
    @Test
    public void aFinderFailureIsReportedByBothWalks() throws Exception
    {
        Object harvest = harvestThatFailed(new IllegalStateException("index exploded")); //$NON-NLS-1$
        String direct = renderCallers(harvest);
        String transitive = finishTransitive(harvest);

        assertTrue(direct, direct.contains("collecting callers failed")); //$NON-NLS-1$
        assertTrue(direct, direct.contains("index exploded")); //$NON-NLS-1$
        assertFalse(direct, direct.contains("Nothing calls this method.")); //$NON-NLS-1$
        assertTrue(transitive, transitive.contains("collecting callers failed")); //$NON-NLS-1$
        assertTrue(transitive, transitive.contains("index exploded")); //$NON-NLS-1$
        assertFalse(transitive, transitive.contains("Nothing calls this method.")); //$NON-NLS-1$
    }

    /** A failure with no message is still named, in both walks. */
    @Test
    public void aFailureWithoutAMessageIsStillNamed() throws Exception
    {
        Object harvest = harvestThatFailed(new IllegalStateException((String)null));
        String direct = renderCallers(harvest);
        String transitive = finishTransitive(harvest);
        assertTrue(direct, direct.contains("IllegalStateException")); //$NON-NLS-1$
        assertTrue(transitive, transitive.contains("IllegalStateException")); //$NON-NLS-1$
        assertFalse(direct, direct.contains("Nothing calls this method.")); //$NON-NLS-1$
        assertFalse(transitive, transitive.contains("Nothing calls this method.")); //$NON-NLS-1$
    }

    private static Object harvestThatFailed(Exception failure) throws Exception
    {
        Class<?> harvestType = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.CallHierarchyReader$Harvest"); //$NON-NLS-1$
        Constructor<?> constructor = harvestType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object harvest = constructor.newInstance();

        ClassLoader loader = IReferenceFinder.class.getClassLoader();
        Object finder = Proxy.newProxyInstance(loader, new Class<?>[] {IReferenceFinder.class},
            (proxy, method, args) -> {
                if ("findAllReferences".equals(method.getName())) //$NON-NLS-1$
                {
                    throw failure;
                }
                return plain(method);
            });
        Method collect = CallHierarchyReader.class.getDeclaredMethod("collectFromIndex", //$NON-NLS-1$
            IReferenceFinder.class, List.class, ResourceSet.class, harvestType, int.class);
        collect.setAccessible(true);
        invoke(collect, new CallHierarchyReader(), finder, List.of(), null, harvest, Integer.valueOf(10));
        return harvest;
    }

    private static String renderCallers(Object harvest) throws Exception
    {
        Method render = CallHierarchyReader.class.getDeclaredMethod("renderCallers", harvest.getClass(), //$NON-NLS-1$
            String.class, String.class, int.class);
        render.setAccessible(true);
        return (String)invoke(render, new CallHierarchyReader(), harvest,
            "CommonModules/Billing/Module.bsl", "Post", Integer.valueOf(10)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String finishTransitive(Object harvest) throws Exception
    {
        Field error = harvest.getClass().getDeclaredField("error"); //$NON-NLS-1$
        error.setAccessible(true);
        Method finish = CallHierarchyReader.class.getDeclaredMethod("finishTransitive", String.class, //$NON-NLS-1$
            String.class, List.class, int.class, int.class, int.class, boolean.class, String.class);
        finish.setAccessible(true);
        return (String)invoke(finish, new CallHierarchyReader(), "CommonModules/Billing/Module.bsl", "Post", //$NON-NLS-1$ //$NON-NLS-2$
            List.of(), Integer.valueOf(10), Integer.valueOf(3), Integer.valueOf(1), Boolean.FALSE,
            error.get(harvest));
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception
    {
        try
        {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw e;
        }
    }

    private static Object plain(Method method)
    {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive())
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == void.class)
        {
            return null;
        }
        if (type == long.class)
        {
            return Long.valueOf(0);
        }
        return Integer.valueOf(0);
    }
}
