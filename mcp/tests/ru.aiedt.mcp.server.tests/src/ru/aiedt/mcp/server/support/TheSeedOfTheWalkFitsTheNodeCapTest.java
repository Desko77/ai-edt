/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;

/**
 * The seed of a walk fits the node cap it hands the walk itself.
 * <p>
 * Every root used to be seeded before the cap was consulted, so a project scope holding more
 * objects than {@code maxNodes} answered with thousands of nodes and not one edge: the cap tripped
 * on the first ring check, before any edge was ever followed. A cut seed says {@code truncated}
 * like any other cut, and the edges of the roots it did take are in the answer.
 * </p>
 */
public class TheSeedOfTheWalkFitsTheNodeCapTest
{
    private static Object respond(Object proxy, Method method, Object[] args,
        Map<String, Object> answers)
    {
        String name = method.getName();
        if (answers.containsKey(name))
        {
            return answers.get(name);
        }
        if ("hashCode".equals(name)) //$NON-NLS-1$
        {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(name)) //$NON-NLS-1$
        {
            return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive())
        {
            return null;
        }
        return type == boolean.class ? Boolean.FALSE : Integer.valueOf(0);
    }

    /** A stand-in top object: a name is all the seeding asks of it. */
    private static IBmObject node(String fqn)
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("bmIsTop", Boolean.TRUE); //$NON-NLS-1$
        answers.put("bmGetFqn", fqn); //$NON-NLS-1$
        answers.put("eContainer", null); //$NON-NLS-1$
        answers.put("eClass", eClassOfNothing()); //$NON-NLS-1$
        answers.put("eAllContents", NO_CHILDREN); //$NON-NLS-1$
        return (IBmObject)Proxy.newProxyInstance(
            TheSeedOfTheWalkFitsTheNodeCapTest.class.getClassLoader(),
            new Class<?>[] {IBmObject.class, org.eclipse.emf.ecore.EObject.class},
            (proxy, method, args) -> respond(proxy, method, args, answers));
    }

    /** A model type with no references, so the forward pass of a node finds nothing. */
    private static org.eclipse.emf.ecore.EClass eClassOfNothing()
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getEAllReferences", new org.eclipse.emf.common.util.BasicEList<>()); //$NON-NLS-1$
        return (org.eclipse.emf.ecore.EClass)Proxy.newProxyInstance(
            TheSeedOfTheWalkFitsTheNodeCapTest.class.getClassLoader(),
            new Class<?>[] {org.eclipse.emf.ecore.EClass.class},
            (proxy, method, args) -> respond(proxy, method, args, answers));
    }

    /** An iterator with nothing under it, so a node has no containment to walk. */
    private static final org.eclipse.emf.common.util.TreeIterator<
        org.eclipse.emf.ecore.EObject> NO_CHILDREN = new org.eclipse.emf.common.util.TreeIterator<>()
    {
        @Override
        public boolean hasNext()
        {
            return false;
        }

        @Override
        public org.eclipse.emf.ecore.EObject next()
        {
            throw new java.util.NoSuchElementException();
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prune()
        {
            // nothing to prune
        }
    };

    /**
     * The transaction is here for its identity alone - {@code forwardReferences} answers nothing
     * without one. Its loader builds the proxy, not this bundle: one method of the interface names
     * an internal type of the same BM bundle, and a proxy built by a loader that cannot see that
     * type is refused outright.
     */
    private static IBmTransaction transaction()
    {
        return (IBmTransaction)Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
            new Class<?>[] {IBmTransaction.class},
            (proxy, method, args) -> respond(proxy, method, args, new LinkedHashMap<>()));
    }

    /** An engine that reports no backward references. */
    private static IBmEngine engine()
    {
        return (IBmEngine)Proxy.newProxyInstance(IBmEngine.class.getClassLoader(),
            new Class<?>[] {IBmEngine.class},
            (proxy, method, args) -> "getBackReferences".equals(method.getName()) //$NON-NLS-1$
                ? java.util.Collections.emptyList()
                : respond(proxy, method, args, new LinkedHashMap<>()));
    }

    /** A seed larger than the cap is cut to it and says so; the walk keeps the roots it took. */
    @Test
    public void aSeedLargerThanTheCapIsCutToIt()
    {
        List<IBmObject> roots = new ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            roots.add(node("Catalog.Object" + i)); //$NON-NLS-1$
        }

        BmReferencesHelper.BfsResult walk = BmReferencesHelper.bfs(transaction(), engine(), roots,
            BmReferencesHelper.Direction.OUT, 3, 500, 1, null, null);

        assertTrue("five roots under a cap of three", walk.nodes.size() <= 3); //$NON-NLS-1$
        assertTrue("the cut seed says truncated", walk.truncated); //$NON-NLS-1$
        assertEquals("the roots it took are the first ones", "Catalog.Object0", //$NON-NLS-1$ //$NON-NLS-2$
            walk.nodes.keySet().iterator().next());
    }

    /** A seed that fits the cap is taken whole, cut or not. */
    @Test
    public void aSeedThatFitsIsTakenWhole()
    {
        List<IBmObject> roots = List.of(node("Catalog.Object0"), node("Catalog.Object1")); //$NON-NLS-1$ //$NON-NLS-2$

        BmReferencesHelper.BfsResult walk = BmReferencesHelper.bfs(transaction(), engine(), roots,
            BmReferencesHelper.Direction.OUT, 3, 500, 1, null, null);

        assertEquals(2, walk.nodes.size());
        assertTrue("nothing was cut", !walk.truncated); //$NON-NLS-1$
    }
}
