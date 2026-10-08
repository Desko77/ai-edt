/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.BiFunction;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.IAcceptor;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Block;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * A module of the module level is named by the address it was loaded from, not by the object model.
 * <p>
 * The level loads its modules by path, and the object model answers no FQN for such a module: a name
 * read through the object model alone leaves the module unnamed, the emission writes no edge, and a
 * graph over a project of thousands of modules answers with nodes and no edges in every direction.
 * The name is read off the module's own resource address - the same {@code src/} path the level
 * names its nodes by - with the object model kept as the fallback for a module addressed some other
 * way.
 * </p>
 */
public class AModuleIsNamedByItsAddressTest
{
    /** The class the walk reads references off: an empty one, so it finds none. */
    private static final EClass NO_REFERENCES = EcoreFactory.eINSTANCE.createEClass();

    /** The module whose method is called is named by that module's own address. */
    @Test
    public void theCalledModuleIsNamedByItsAddress()
    {
        Module called = moduleAt("CommonModules/Sales/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        Method callee = methodIn(called, List.of(), List.of());

        List<EObject> body = new BasicEList<>();
        Module caller = moduleAt("CommonModules/Stock/Module.bsl", body); //$NON-NLS-1$
        body.add(methodIn(caller, List.of(callee), List.of()));

        assertEquals(List.of("CommonModule.Sales.Module"), //$NON-NLS-1$
            BslCallGraphHelper.calleesOfModule(caller));
    }

    /**
     * A method is read through what it calls. The blocks that call it live in the calling modules,
     * so following that link would report a call in the wrong direction.
     */
    @Test
    public void aMethodIsReadThroughWhatItCallsAndNotThroughItsCallers()
    {
        Module called = moduleAt("CommonModules/Sales/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        Method callee = methodIn(called, List.of(), List.of());
        Module foreign = moduleAt("CommonModules/Report/Module.bsl", new BasicEList<>()); //$NON-NLS-1$
        EObject foreignCall = blockIn(foreign);

        List<EObject> body = new BasicEList<>();
        Module caller = moduleAt("CommonModules/Stock/Module.bsl", body); //$NON-NLS-1$
        body.add(methodIn(caller, List.of(callee), List.of(foreignCall)));

        List<String> callees = BslCallGraphHelper.calleesOfModule(caller);
        assertEquals(List.of("CommonModule.Sales.Module"), callees); //$NON-NLS-1$
        assertFalse("the module that calls this one is not a module it calls", //$NON-NLS-1$
            callees.contains("CommonModule.Report.Module")); //$NON-NLS-1$
    }

    /** A module that calls its own exported method is not reported as a caller of itself. */
    @Test
    public void theModuleItselfIsNotItsOwnCaller()
    {
        List<EObject> body = new BasicEList<>();
        Module module = moduleAt("CommonModules/Stock/Module.bsl", body); //$NON-NLS-1$
        body.add(methodIn(module, List.of(), List.of()));

        IReferenceFinder finder = finderReporting(
            "platform:/resource/P/src/CommonModules/Stock/Module.bsl#//m"); //$NON-NLS-1$

        List<String> callers = BslCallGraphHelper.callersOfModule(finder, module);
        assertTrue("the module's own call is not a caller module", //$NON-NLS-1$
            callers != null && callers.isEmpty());
    }

    /** A module whose address names no module falls back on the object model for its name. */
    @Test
    public void aModuleWithoutAnAddressIsNamedByTheObjectModel()
    {
        Module module = (Module)Proxy.newProxyInstance(
            AModuleIsNamedByItsAddressTest.class.getClassLoader(),
            new Class<?>[] {Module.class, IBmObject.class, EObject.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "bmGetFqn": //$NON-NLS-1$
                    return "CommonModule.Remote.Module"; //$NON-NLS-1$
                case "bmIsTop": //$NON-NLS-1$
                    return Boolean.TRUE;
                default:
                    return plain(method);
                }
            });

        assertEquals("CommonModule.Remote.Module", BslCallGraphHelper.moduleFqn(module)); //$NON-NLS-1$
    }

    /**
     * A module whose model is addressed by the given path inside project {@code P}.
     *
     * @param srcRelativePath the path below {@code src/}, forward slashes
     * @param contents what the module contains, filled by the caller where a method refers back to
     *            the module it lives in
     * @return the module
     */
    private static Module moduleAt(String srcRelativePath, List<EObject> contents)
    {
        ResourceImpl resource =
            new ResourceImpl(URI.createURI("platform:/resource/P/src/" + srcRelativePath)); //$NON-NLS-1$
        return (Module)Proxy.newProxyInstance(AModuleIsNamedByItsAddressTest.class.getClassLoader(),
            new Class<?>[] {Module.class, IBmObject.class, InternalEObject.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                case "eResource": //$NON-NLS-1$
                    return resource;
                case "eContents": //$NON-NLS-1$
                    return new BasicEList<>(contents);
                case "eClass": //$NON-NLS-1$
                    // The walk reads a node's model references off its class; an empty class has
                    // none, which is what a module stands for here.
                    return NO_REFERENCES;
                case "allMethods": //$NON-NLS-1$
                    return new BasicEList<>(contents);
                case "eAllContents": //$NON-NLS-1$
                    // Resolving, so the walk reads the contents through eContents: the plain
                    // traversal casts them to an internal list, which this one is not.
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
     * A method of the given module.
     *
     * @param owner the module the method lives in
     * @param callees the methods this one calls
     * @param callers the blocks that call this one, which live in other modules
     * @return the method
     */
    private static Method methodIn(Module owner, List<Method> callees, List<EObject> callers)
    {
        return (Method)Proxy.newProxyInstance(AModuleIsNamedByItsAddressTest.class.getClassLoader(),
            new Class<?>[] {Method.class, InternalEObject.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "isExport": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "getCallees": //$NON-NLS-1$
                    return new BasicEList<>(callees);
                case "getCallers": //$NON-NLS-1$
                    return new BasicEList<>(callers);
                case "eContainer": //$NON-NLS-1$
                    return owner;
                case "eResource": //$NON-NLS-1$
                    return owner.eResource();
                case "eProxyURI": //$NON-NLS-1$
                    // The caller walk addresses a method by its URI; a method that answers one
                    // is read without walking up to its module.
                    return URI.createURI("fake:/Method.bsl#//m"); //$NON-NLS-1$
                case "eContents": //$NON-NLS-1$
                    return new BasicEList<>();
                default:
                    return plain(method);
                }
            });
    }

    /**
     * A block of the given module, which is what a caller link holds.
     *
     * @param owner the module the block lives in
     * @return the block
     */
    private static EObject blockIn(Module owner)
    {
        return (EObject)Proxy.newProxyInstance(AModuleIsNamedByItsAddressTest.class.getClassLoader(),
            new Class<?>[] {Block.class, InternalEObject.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "eContainer": //$NON-NLS-1$
                    return owner;
                case "eContents": //$NON-NLS-1$
                    return new BasicEList<>();
                default:
                    return plain(method);
                }
            });
    }

    /**
     * A finder that reports one reference from the given address.
     *
     * @param sourceAddress the platform URI of the calling code
     * @return the finder
     */
    private static IReferenceFinder finderReporting(String sourceAddress)
    {
        return finder("findAllReferences", (proxy, args) -> { //$NON-NLS-1$
            @SuppressWarnings("unchecked")
            IAcceptor<IReferenceDescription> acceptor = (IAcceptor<IReferenceDescription>)args[2];
            acceptor.accept((IReferenceDescription)Proxy.newProxyInstance(
                IReferenceDescription.class.getClassLoader(),
                new Class<?>[] {IReferenceDescription.class}, (description, method, descriptionArgs) -> {
                    if ("getSourceEObjectUri".equals(method.getName())) //$NON-NLS-1$
                    {
                        return URI.createURI(sourceAddress);
                    }
                    return plain(method);
                }));
            return null;
        });
    }

    /**
     * A finder whose every {@code findAllReferences} call runs the given behaviour.
     *
     * @param name the method the behaviour stands for
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
