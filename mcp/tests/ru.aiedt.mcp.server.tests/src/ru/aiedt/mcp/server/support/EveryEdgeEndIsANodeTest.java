/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;

/**
 * Covers the two things an edge of the dependency graph has to hold for a reader to follow it.
 * <p>
 * Both ends have to be nodes of the same answer - an edge pointing at a node the answer does not
 * carry is a dead end with nothing said about why - and both ends have to be objects a reader can
 * look up. A backward reference arrives from whatever holds it, which is often an internal piece of
 * the model with no address of its own; naming a node after its class collected hundreds of edges
 * into one node standing for nothing.
 * </p>
 */
public class EveryEdgeEndIsANodeTest
{
    /** A stand-in for a BM object: it answers whether it is a top object and what it is called. */
    private static IBmObject object(boolean top, String fqn)
    {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName())
            {
            case "bmIsTop": //$NON-NLS-1$
                return top;
            case "bmGetFqn": //$NON-NLS-1$
                return fqn;
            case "eContainer": //$NON-NLS-1$
                return null;
            case "hashCode": //$NON-NLS-1$
                return System.identityHashCode(proxy);
            case "equals": //$NON-NLS-1$
                return proxy == args[0];
            case "toString": //$NON-NLS-1$
                return String.valueOf(fqn);
            default:
                return defaultOf(method.getReturnType());
            }
        };
        return (IBmObject)Proxy.newProxyInstance(EveryEdgeEndIsANodeTest.class.getClassLoader(),
            new Class<?>[] {IBmObject.class, EObject.class}, handler);
    }

    private static Object defaultOf(Class<?> type)
    {
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
        return Integer.valueOf(0);
    }

    private static Object addEdge(Object result, Deque<IBmObject> queue, Set<String> visited,
        IBmObject from, IBmObject to, int maxNodes, int maxEdges) throws Exception
    {
        Method method = BmReferencesHelper.class.getDeclaredMethod("addBfsEdge", //$NON-NLS-1$
            BmReferencesHelper.BfsResult.class, Deque.class, Set.class, IBmObject.class,
            IBmObject.class, String.class, int.class, int.class);
        method.setAccessible(true);
        return method.invoke(null, result, queue, visited, from, to, "uses", maxNodes, //$NON-NLS-1$
            maxEdges);
    }

    /** With the node cap reached, nothing is added at all and the answer says it was cut. */
    @Test
    public void anEdgeIsNeverAddedWithoutItsEnds() throws Exception
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        IBmObject root = object(true, "Catalog.Products"); //$NON-NLS-1$
        visited.add("Catalog.Products"); //$NON-NLS-1$
        result.nodes.put("Catalog.Products", root); //$NON-NLS-1$

        addEdge(result, queue, visited, root, object(true, "Document.Order"), 1, 100); //$NON-NLS-1$

        assertTrue("the walk says it was cut", result.truncated); //$NON-NLS-1$
        for (BmReferencesHelper.Edge edge : result.edges)
        {
            assertTrue("edge target " + edge.toFqn + " is a node", //$NON-NLS-1$ //$NON-NLS-2$
                result.nodes.containsKey(edge.toFqn));
            assertTrue("edge source " + edge.fromFqn + " is a node", //$NON-NLS-1$ //$NON-NLS-2$
                result.nodes.containsKey(edge.fromFqn));
        }
    }

    /** Room enough: both ends become nodes, and the far end is queued for the next ring. */
    @Test
    public void bothEndsBecomeNodesAndTheFarEndIsWalked() throws Exception
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        IBmObject caller = object(true, "CommonModule.Sales.Module"); //$NON-NLS-1$
        IBmObject target = object(true, "CommonModule.Prices.Module"); //$NON-NLS-1$
        visited.add("CommonModule.Prices.Module"); //$NON-NLS-1$
        result.nodes.put("CommonModule.Prices.Module", target); //$NON-NLS-1$

        // An inbound reference: the object being walked is the target, and the new end is the source.
        addEdge(result, queue, visited, caller, target, 50, 50);

        assertTrue("the caller is a node", //$NON-NLS-1$
            result.nodes.containsKey("CommonModule.Sales.Module")); //$NON-NLS-1$
        assertEquals("and one edge was recorded", 1, result.edges.size()); //$NON-NLS-1$
        assertTrue("the caller is queued, or direction=in stops at the first ring", //$NON-NLS-1$
            queue.contains(caller));
        assertFalse("nothing was cut", result.truncated); //$NON-NLS-1$
    }

    /** An end with no address of its own is not a node, so the edge is dropped. */
    @Test
    public void anEndWithNoAddressIsNotANode() throws Exception
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        IBmObject root = object(true, "Catalog.Products"); //$NON-NLS-1$
        visited.add("Catalog.Products"); //$NON-NLS-1$
        result.nodes.put("Catalog.Products", root); //$NON-NLS-1$

        addEdge(result, queue, visited, object(false, null), root, 50, 50);

        assertEquals("no node was invented for it", 1, result.nodes.size()); //$NON-NLS-1$
        assertTrue("and no edge leads to one", result.edges.isEmpty()); //$NON-NLS-1$
    }

    /** Both ends on the same object describe nothing about the graph between objects. */
    @Test
    public void anEdgeOntoItselfIsDropped() throws Exception
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        IBmObject root = object(true, "Catalog.Products"); //$NON-NLS-1$
        visited.add("Catalog.Products"); //$NON-NLS-1$
        result.nodes.put("Catalog.Products", root); //$NON-NLS-1$

        addEdge(result, queue, visited, root, object(true, "Catalog.Products"), 50, 50); //$NON-NLS-1$

        assertTrue("an object does not depend on itself", result.edges.isEmpty()); //$NON-NLS-1$
        assertEquals("and no second node of the same name", 1, result.nodes.size()); //$NON-NLS-1$
    }
}
