/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.support.BmReferencesHelper;

/**
 * The module level seeds its walk under the node cap it hands that walk.
 * <p>
 * Every root module used to enter the graph before the cap was consulted, so a project scope of
 * more modules than {@code maxNodes} answered with more nodes than asked for. The walk below is
 * given no rings to expand: the seed is what is under test here.
 * </p>
 */
public class TheModuleSeedFitsTheNodeCapTest
{
    /** A stand-in module: the walk asks a module for its FQN and nothing else at this depth. */
    private static Module module(String fqn)
    {
        List<Class<?>> types = new ArrayList<>();
        types.add(Module.class);
        types.add(IBmObject.class);
        types.add(EObject.class);
        return (Module)Proxy.newProxyInstance(
            TheModuleSeedFitsTheNodeCapTest.class.getClassLoader(),
            types.toArray(new Class<?>[0]), (proxy, method, args) -> {
                switch (method.getName())
                {
                case "bmGetFqn": //$NON-NLS-1$
                    return fqn;
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

    /** A scope of five modules under a cap of three is cut to three and says so. */
    @Test
    public void aModuleScopeLargerThanTheCapIsCutToIt()
    {
        LinkedHashMap<String, Module> roots = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++)
        {
            roots.put("CommonModule.M" + i + ".Module", module("CommonModule.M" + i + ".Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        BmReferencesHelper.BfsResult walk = DependencyGraphTool.buildModuleGraph(null, null, roots,
            fqn -> DependencyGraphTool.ModuleResolution.absent(), new ArrayList<>(),
            BmReferencesHelper.Direction.OUT, 0, 3, 500, null, null);

        assertEquals("five roots under a cap of three", 3, walk.nodes.size()); //$NON-NLS-1$
        assertTrue("the cut seed says truncated", walk.truncated); //$NON-NLS-1$
    }

    /** A seed that fits the cap is taken whole, in the order the scope named it. */
    @Test
    public void aSeedThatFitsIsTakenWholeAndInOrder()
    {
        LinkedHashMap<String, Module> roots = new LinkedHashMap<>();
        roots.put("CommonModule.Sales.Module", module("CommonModule.Sales.Module")); //$NON-NLS-1$ //$NON-NLS-2$
        roots.put("CommonModule.Stock.Module", module("CommonModule.Stock.Module")); //$NON-NLS-1$ //$NON-NLS-2$

        BmReferencesHelper.BfsResult walk = DependencyGraphTool.buildModuleGraph(null, null, roots,
            fqn -> DependencyGraphTool.ModuleResolution.absent(), new ArrayList<>(),
            BmReferencesHelper.Direction.OUT, 0, 3, 500, null, null);

        assertEquals(List.copyOf(roots.keySet()), List.copyOf(walk.nodes.keySet()));
        assertTrue("nothing was cut", !walk.truncated); //$NON-NLS-1$
    }
}
