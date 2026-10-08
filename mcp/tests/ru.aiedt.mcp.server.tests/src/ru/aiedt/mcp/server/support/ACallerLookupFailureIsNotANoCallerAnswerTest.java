/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.BiFunction;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.IAcceptor;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * Holds a caller lookup that failed to looking like a module nothing calls.
 * <p>
 * {@code findAllReferences} throwing was logged and dropped, and the walk answered with an empty
 * list - the same list a module without callers earns by being looked at. The direct contract of
 * {@code callersOfModule} now separates the two the way {@code countCallers} separates them with
 * {@code -1}: null for a lookup that could not run, an empty list for one that ran.
 * </p>
 */
public class ACallerLookupFailureIsNotANoCallerAnswerTest
{
    /** A finder that throws answers null, which no successful walk ever answers. */
    @Test
    public void aFinderFailureIsNullNotAnEmptyList()
    {
        IReferenceFinder finder = finder("findAllReferences", //$NON-NLS-1$
            (proxy, args) -> {
                throw new IllegalStateException("index exploded"); //$NON-NLS-1$
            });

        assertNull(BslCallGraphHelper.callersOfModule(finder, moduleWithAnExport()));
    }

    /** A finder that runs and reports nothing answers an empty list, which null never means. */
    @Test
    public void aQuietIndexIsAnEmptyListNotNull()
    {
        IReferenceFinder finder = finder("findAllReferences", (proxy, args) -> null); //$NON-NLS-1$

        List<String> callers = BslCallGraphHelper.callersOfModule(finder, moduleWithAnExport());
        assertNotNull(callers);
        assertTrue(callers.isEmpty());
    }

    /** A finder that reports a reference answers the module it comes from. */
    @Test
    public void aReportedReferenceAnswersItsModule()
    {
        IReferenceFinder finder = finder("findAllReferences", (proxy, args) -> { //$NON-NLS-1$
            @SuppressWarnings("unchecked")
            IAcceptor<IReferenceDescription> acceptor = (IAcceptor<IReferenceDescription>)args[2];
            acceptor.accept(referenceFrom(
                "platform:/resource/P/src/CommonModules/Other/Module.bsl#//x")); //$NON-NLS-1$
            return null;
        });

        List<String> callers = BslCallGraphHelper.callersOfModule(finder, moduleWithAnExport());
        assertNotNull(callers);
        assertTrue(callers.contains("CommonModule.Other.Module")); //$NON-NLS-1$
    }

    /**
     * A finder whose every {@code findAllReferences} call runs the given behaviour.
     *
     * @param behaviour what the call does with the call's arguments
     * @return the finder
     */
    private static IReferenceFinder finder(String name, BiFunction<Object, Object[], Object> behaviour)
    {
        return (IReferenceFinder)Proxy.newProxyInstance(IReferenceFinder.class.getClassLoader(),
            new Class<?>[] {IReferenceFinder.class}, (proxy, method, args) -> {
                if (name.equals(method.getName()))
                {
                    return behaviour.apply(proxy, args);
                }
                return plain(method);
            });
    }

    /**
     * A module with one exported method, on the model objects the walk reads.
     *
     * @return the module
     */
    private static Module moduleWithAnExport()
    {
        Object exported = Proxy.newProxyInstance(Module.class.getClassLoader(),
            new Class<?>[] {com._1c.g5.v8.dt.bsl.model.Method.class,
                org.eclipse.emf.ecore.InternalEObject.class},
            (proxy, method, args) -> {
                if ("isExport".equals(method.getName())) //$NON-NLS-1$
                {
                    return Boolean.TRUE;
                }
                if ("eProxyURI".equals(method.getName())) //$NON-NLS-1$
                {
                    return URI.createURI("fake:/CommonModules/Probe/Module.bsl#//m"); //$NON-NLS-1$
                }
                return plain(method);
            });
        return (Module)Proxy.newProxyInstance(Module.class.getClassLoader(),
            new Class<?>[] {Module.class, IBmObject.class}, (proxy, method, args) -> {
                if ("allMethods".equals(method.getName())) //$NON-NLS-1$
                {
                    BasicEList<Object> methods = new BasicEList<>();
                    methods.add(exported);
                    return methods;
                }
                return plain(method);
            });
    }

    /**
     * A reference description whose source lives at the given address.
     *
     * @param address the platform URI of the calling module
     * @return the description
     */
    private static IReferenceDescription referenceFrom(String address)
    {
        return (IReferenceDescription)Proxy.newProxyInstance(
            IReferenceDescription.class.getClassLoader(),
            new Class<?>[] {IReferenceDescription.class}, (proxy, method, args) -> {
                if ("getSourceEObjectUri".equals(method.getName())) //$NON-NLS-1$
                {
                    return URI.createURI(address);
                }
                return plain(method);
            });
    }

    private static Object plain(java.lang.reflect.Method method)
    {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class)
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == long.class)
        {
            return Long.valueOf(0);
        }
        return Integer.valueOf(0);
    }
}
