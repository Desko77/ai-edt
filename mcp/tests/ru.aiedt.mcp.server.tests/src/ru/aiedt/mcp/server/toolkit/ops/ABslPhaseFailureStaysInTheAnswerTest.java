/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.ProjectScopeResolver;
import ru.aiedt.mcp.server.support.WatchForCancel;

/**
 * Holds a BSL-phase failure to staying in the answer.
 * <p>
 * The index throwing used to be logged and dropped. The report then either listed the metadata
 * hits as a finished answer or said nothing references the object, and a caller grading impact
 * treated that number as the whole.
 * </p>
 */
public class ABslPhaseFailureStaysInTheAnswerTest
{
    /** A finder that throws leaves the answer incomplete, in the text and in the count. */
    @Test
    public void aFinderFailureIsNotAnEmptyCodeSection() throws Exception
    {
        Object harvester = harvester();
        runFinder(harvester, new IllegalStateException("index exploded")); //$NON-NLS-1$

        String report = format(harvester);
        assertTrue(report, report.contains("the BSL phase did not finish")); //$NON-NLS-1$
        assertTrue(report, report.contains("index exploded")); //$NON-NLS-1$
        assertTrue(report, report.contains("incomplete")); //$NON-NLS-1$
        assertFalse(report, report.contains("Nothing references this object.")); //$NON-NLS-1$

        Object result = resultOf(harvester);
        Method isExact = result.getClass().getDeclaredMethod("isExact"); //$NON-NLS-1$
        isExact.setAccessible(true);
        assertFalse("a phase that threw is not the whole answer", //$NON-NLS-1$
            ((Boolean)isExact.invoke(result)).booleanValue());
        assertEquals("PARTIAL", String.valueOf(field(result, "certainty"))); //$NON-NLS-1$ //$NON-NLS-2$
        Method why = result.getClass().getDeclaredMethod("whyNotExact"); //$NON-NLS-1$
        why.setAccessible(true);
        assertTrue(String.valueOf(why.invoke(result)).contains("index exploded")); //$NON-NLS-1$
    }

    /** A failure with no message is still named, by the exception's type. */
    @Test
    public void aFailureWithoutAMessageIsStillNamed() throws Exception
    {
        Object harvester = harvester();
        runFinder(harvester, new IllegalStateException((String)null));
        String report = format(harvester);
        assertTrue(report, report.contains("IllegalStateException")); //$NON-NLS-1$
        assertFalse(report, report.contains("Nothing references this object.")); //$NON-NLS-1$
    }

    private static void runFinder(Object harvester, Exception failure) throws Exception
    {
        ClassLoader loader = IReferenceFinder.class.getClassLoader();
        Object finder = Proxy.newProxyInstance(loader, new Class<?>[] {IReferenceFinder.class},
            (proxy, method, args) -> {
                if ("findAllReferences".equals(method.getName())) //$NON-NLS-1$
                {
                    throw failure;
                }
                return plain(method);
            });
        Method run = harvester.getClass().getDeclaredMethod("runBslFinder", IReferenceFinder.class, List.class); //$NON-NLS-1$
        run.setAccessible(true);
        invoke(run, harvester, finder, List.of());
    }

    private static Object harvester() throws Exception
    {
        Class<?> filterType = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$CategoryFilter"); //$NON-NLS-1$
        Method from = filterType.getDeclaredMethod("from", String.class, boolean.class, boolean.class); //$NON-NLS-1$
        from.setAccessible(true);
        Object filter = from.invoke(null, null, Boolean.FALSE, Boolean.FALSE);
        Class<?> type = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$BmReferenceHarvester"); //$NON-NLS-1$
        Constructor<?> constructor = type.getDeclaredConstructor(IBmModel.class, MdObject.class, int.class,
            boolean.class, filterType, WatchForCancel.class);
        constructor.setAccessible(true);
        ClassLoader loader = MdObject.class.getClassLoader();
        MdObject target = (MdObject)Proxy.newProxyInstance(loader, new Class<?>[] {MdObject.class},
            (proxy, method, args) -> "getName".equals(method.getName()) ? "Products" : plain(method)); //$NON-NLS-1$ //$NON-NLS-2$
        return constructor.newInstance(null, target, Integer.valueOf(8), Boolean.FALSE, filter,
            WatchForCancel.begin());
    }

    private static String format(Object harvester) throws Exception
    {
        Class<?> filterType = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$CategoryFilter"); //$NON-NLS-1$
        Method format = ReferenceLocator.class.getDeclaredMethod("formatOutput", String.class, //$NON-NLS-1$
            harvester.getClass(), filterType, ProjectScopeResolver.ScopeResult.class, List.class,
            WatchForCancel.class);
        format.setAccessible(true);
        Field filter = harvester.getClass().getDeclaredField("filter"); //$NON-NLS-1$
        filter.setAccessible(true);
        return (String)invoke(format, null, "Catalog.Products", harvester, filter.get(harvester), null, //$NON-NLS-1$
            List.of(), WatchForCancel.begin());
    }

    private static Object resultOf(Object harvester) throws Exception
    {
        Class<?> sinkType = Class.forName("ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$Sink"); //$NON-NLS-1$
        Constructor<?> constructor = sinkType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object sink = constructor.newInstance();
        Method record = ReferenceLocator.class.getDeclaredMethod("recordOutcome", sinkType, //$NON-NLS-1$
            harvester.getClass(), WatchForCancel.class);
        record.setAccessible(true);
        invoke(record, null, sink, harvester, WatchForCancel.begin());
        Method toResult = sinkType.getDeclaredMethod("toResult", String.class); //$NON-NLS-1$
        toResult.setAccessible(true);
        return invoke(toResult, sink, "# Usages of Catalog.Products\n"); //$NON-NLS-1$
    }

    private static Object field(Object target, String name) throws Exception
    {
        Field declared = target.getClass().getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(target);
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
