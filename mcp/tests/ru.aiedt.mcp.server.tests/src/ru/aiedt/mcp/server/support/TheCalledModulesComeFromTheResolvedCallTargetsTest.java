/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.support.BslCallGraphHelper.ModuleCallSource;
import ru.aiedt.mcp.server.support.BslCallGraphHelper.ModuleEdge;

/**
 * The outgoing direction of the module level reads the module of a call target off the call site.
 * <p>
 * A call between modules is carried by the call site's feature entry: the entry reaches the target
 * through a transient reference, and the access that holds the entry reaches it by containment.
 * A walk over a node's non-derived references skips both, so it finds no call at all and reports a
 * module that calls several others as a module that calls nobody - while
 * {@code call_hierarchy direction=callees}, which reads the call sites, answers them.
 * </p>
 */
public class TheCalledModulesComeFromTheResolvedCallTargetsTest
{
    /** The class the walk reads references off: an empty one, so it finds none. */
    private static final EClass NO_REFERENCES = EcoreFactory.eINSTANCE.createEClass();

    /** A call target of another module names that module. */
    @Test
    public void theModuleOfACallTargetIsReadFromTheCallSite()
    {
        Module called = moduleAt("CommonModules/Sales/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        Method target = methodIn(called, new BasicEList<>());

        Module caller = moduleAt("CommonModules/Stock/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        caller.eContents().add(methodCalling(caller, featureOf(target)));

        assertEquals(List.of("CommonModule.Sales.Module"), //$NON-NLS-1$
            BslCallGraphHelper.calleesOfModule(caller));
    }

    /** A call target inside the module being walked is not a module it calls. */
    @Test
    public void aCallTargetInsideTheModuleIsNotACallee()
    {
        Module module = moduleAt("CommonModules/Stock/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        Method own = methodIn(module, new BasicEList<>());
        module.eContents().add(methodCalling(module, featureOf(own)));

        assertTrue("a module that calls only itself calls no other module", //$NON-NLS-1$
            BslCallGraphHelper.calleesOfModule(module).isEmpty());
    }

    /** A module whose call targets could not be read emits no outgoing edge. */
    @Test
    public void aLookupThatCouldNotRunEmitsNoEdge()
    {
        Module module = moduleAt("CommonModules/Stock/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        List<ModuleEdge> edges = new ArrayList<>();
        ModuleCallSource failed = new ModuleCallSource()
        {
            @Override
            public List<String> callersOf(Module walked)
            {
                return new ArrayList<>();
            }

            @Override
            public List<String> calleesOf(Module walked)
            {
                return null;
            }
        };

        BslCallGraphHelper.emitEdgesForModule(module, "CommonModule.Stock.Module", failed, false, true, //$NON-NLS-1$
            edges::add);

        assertTrue("no callee is named, so no edge is written", edges.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A module whose model is addressed by the given path inside project {@code P}.
     *
     * @param srcRelativePath the path below {@code src/}, forward slashes
     * @param contents what the module contains
     * @return the module
     */
    private static Module moduleAt(String srcRelativePath, List<EObject> contents)
    {
        ResourceImpl resource =
            new ResourceImpl(URI.createURI("platform:/resource/P/src/" + srcRelativePath)); //$NON-NLS-1$
        return (Module)Proxy.newProxyInstance(TheCalledModulesComeFromTheResolvedCallTargetsTest.class.getClassLoader(),
            new Class<?>[] {Module.class, IBmObject.class, InternalEObject.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                case "eResource": //$NON-NLS-1$
                    return resource;
                case "eContents": //$NON-NLS-1$
                    return contents;
                case "eClass": //$NON-NLS-1$
                    return NO_REFERENCES;
                case "allMethods": //$NON-NLS-1$
                    return new BasicEList<>(contents);
                case "eAllContents": //$NON-NLS-1$
                    return EcoreUtil.getAllContents(List.of(proxy), true);
                case "bmIsTop": //$NON-NLS-1$
                    return Boolean.FALSE;
                case "eContainer": //$NON-NLS-1$
                    return null;
                default:
                    return plain(method);
                }
            });
    }

    /**
     * A method of the given module that carries the given call sites.
     *
     * @param owner the module the method lives in
     * @return the method
     */
    private static Method methodIn(Module owner, List<EObject> contents)
    {
        return (Method)Proxy.newProxyInstance(TheCalledModulesComeFromTheResolvedCallTargetsTest.class.getClassLoader(),
            new Class<?>[] {Method.class, InternalEObject.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "eContainer": //$NON-NLS-1$
                    return owner;
                case "eResource": //$NON-NLS-1$
                    return owner.eResource();
                case "eContents": //$NON-NLS-1$
                    return contents;
                case "eClass": //$NON-NLS-1$
                    return NO_REFERENCES;
                case "eAllContents": //$NON-NLS-1$
                    return EcoreUtil.getAllContents(List.of(proxy), true);
                case "eProxyURI": //$NON-NLS-1$
                    return URI.createURI("fake:/Method.bsl#//m"); //$NON-NLS-1$
                case "getCallees": //$NON-NLS-1$
                    // The model's own link is empty here on purpose: only the call site carries the
                    // target, which is what the walk is supposed to read.
                    return new BasicEList<>();
                case "getCallers": //$NON-NLS-1$
                    return new BasicEList<>();
                case "isExport": //$NON-NLS-1$
                    return Boolean.TRUE;
                default:
                    return plain(method);
                }
            });
    }

    /**
     * A method of the given module that makes one call.
     *
     * @param owner the module the method lives in
     * @param callSite the call site the method carries
     * @return the method
     */
    private static Method methodCalling(Module owner, EObject callSite)
    {
        List<EObject> contents = new BasicEList<>();
        contents.add(callSite);
        return methodIn(owner, contents);
    }

    /**
     * A call site's feature entry, holding the target it resolved to.
     *
     * @param target the called method or module
     * @return the entry
     */
    private static FeatureEntry featureOf(EObject target)
    {
        return (FeatureEntry)Proxy.newProxyInstance(
            TheCalledModulesComeFromTheResolvedCallTargetsTest.class.getClassLoader(),
            new Class<?>[] {FeatureEntry.class, InternalEObject.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getFeature": //$NON-NLS-1$
                    return target;
                case "eContents": //$NON-NLS-1$
                    return new BasicEList<>();
                case "eClass": //$NON-NLS-1$
                    return NO_REFERENCES;
                case "eContainer": //$NON-NLS-1$
                    return null;
                default:
                    return plain(method);
                }
            });
    }

    /**
     * The answer a proxy gives a method the test does not care about: the zero of its return type,
     * and {@code null} for anything else.
     *
     * @param method the method being called
     * @return its answer
     */
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
