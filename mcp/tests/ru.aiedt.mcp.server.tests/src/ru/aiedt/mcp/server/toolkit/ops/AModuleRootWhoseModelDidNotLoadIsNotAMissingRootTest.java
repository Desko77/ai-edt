/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * A module root whose file is there and whose model did not load is not a root the project lacks.
 * <p>
 * The scope read the loader's answer for the module and nothing else, so the address behind an
 * unloadable module was lost and the call refused with "names no object of the project" - the one
 * sentence that says the name is wrong, while the name was right and the module was unreadable.
 * </p>
 */
public class AModuleRootWhoseModelDidNotLoadIsNotAMissingRootTest
{
    /** A model whose top-object index answers nothing, as it does for a module FQN. */
    private static IBmTransaction transactionAnsweringNothing()
    {
        return (IBmTransaction)Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
            new Class<?>[] {IBmTransaction.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                default:
                    return method.getReturnType().isPrimitive()
                        ? (method.getReturnType() == boolean.class
                            ? Boolean.FALSE
                            : Integer.valueOf(0))
                        : null;
                }
            });
    }

    /** A stand-in module, compared by identity. */
    private static Module module()
    {
        return (Module)Proxy.newProxyInstance(AModuleRootWhoseModelDidNotLoadIsNotAMissingRootTest.class.getClassLoader(),
            new Class<?>[] {Module.class, IBmObject.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                default:
                    return method.getReturnType().isPrimitive()
                        ? (method.getReturnType() == boolean.class
                            ? Boolean.FALSE
                            : Integer.valueOf(0))
                        : null;
                }
            });
    }

    /** The address of an unloadable module is named to the caller instead of being dropped. */
    @Test
    public void anAddressWhoseModelDidNotLoadIsNamedAsUnloaded()
    {
        List<String> unloaded = new ArrayList<>();

        Collection<IBmObject> roots = DependencyGraphTool.resolveScopeRoots(
            DependencyGraphTool.Level.MODULES, "module", //$NON-NLS-1$
            Map.of("moduleFqn", "CommonModule.Sales.Module"), null, transactionAnsweringNothing(), //$NON-NLS-1$ //$NON-NLS-2$
            fqn -> DependencyGraphTool.ModuleResolution.unloaded(), unloaded);

        assertNull("the root did not resolve", roots); //$NON-NLS-1$
        assertEquals("the address is the fact the refusal names", //$NON-NLS-1$
            List.of("CommonModule.Sales.Module"), unloaded); //$NON-NLS-1$
    }

    /** A module the loader answers is the root, and nothing is reported as unloaded. */
    @Test
    public void aModuleTheLoaderAnswersIsTheRoot()
    {
        Module module = module();
        List<String> unloaded = new ArrayList<>();

        Collection<IBmObject> roots = DependencyGraphTool.resolveScopeRoots(
            DependencyGraphTool.Level.MODULES, "module", //$NON-NLS-1$
            Map.of("moduleFqn", "CommonModule.Sales.Module"), null, transactionAnsweringNothing(), //$NON-NLS-1$ //$NON-NLS-2$
            fqn -> DependencyGraphTool.ModuleResolution.loaded(module), unloaded);

        assertEquals(List.of((IBmObject)module), List.copyOf(roots));
        assertTrue("a module that loaded is not reported unloaded", unloaded.isEmpty()); //$NON-NLS-1$
    }
}
