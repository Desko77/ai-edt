/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;

/**
 * A walk whose seed filled the node cap walks the roots it took and answers their edges.
 * <p>
 * The cap stops new nodes from entering the graph; it is not a reason to stop walking. Counting
 * nodes at the ring boundary used to end the walk as soon as the seed had filled the cap exactly,
 * so a scope of two objects with a cap of two answered with two nodes and not one edge between them.
 * </p>
 */
public class AWalkThatFilledTheCapStillFollowsItsRootsTest
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

    /**
     * A stand-in top object that reports the FQN asked of it and, when it has one, a single
     * non-containment reference to the object handed in.
     *
     * @param fqn the FQN the object answers with
     * @param target what the reference points at, or <code>null</code> for an object with none
     * @return the stand-in
     */
    private static IBmObject node(String fqn, IBmObject target)
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("bmIsTop", Boolean.TRUE); //$NON-NLS-1$
        answers.put("bmGetFqn", fqn); //$NON-NLS-1$
        answers.put("eContainer", null); //$NON-NLS-1$
        answers.put("eAllContents", NO_CHILDREN); //$NON-NLS-1$
        if (target == null)
        {
            answers.put("eClass", eClassOf(new BasicEList<EReference>())); //$NON-NLS-1$
        }
        else
        {
            EReference reference = reference("R" + fqn); //$NON-NLS-1$
            BasicEList<EReference> references = new BasicEList<>();
            references.add(reference);
            answers.put("eClass", eClassOf(references)); //$NON-NLS-1$
            answers.put("eGet", target); //$NON-NLS-1$
        }
        return (IBmObject)Proxy.newProxyInstance(
            AWalkThatFilledTheCapStillFollowsItsRootsTest.class.getClassLoader(),
            new Class<?>[] {IBmObject.class, EObject.class},
            (proxy, method, args) -> respond(proxy, method, args, answers));
    }

    private static org.eclipse.emf.ecore.EClass eClassOf(BasicEList<EReference> references)
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getEAllReferences", references); //$NON-NLS-1$
        answers.put("getName", "MdObject"); //$NON-NLS-1$ //$NON-NLS-2$
        return (org.eclipse.emf.ecore.EClass)Proxy.newProxyInstance(
            AWalkThatFilledTheCapStillFollowsItsRootsTest.class.getClassLoader(),
            new Class<?>[] {org.eclipse.emf.ecore.EClass.class},
            (proxy, method, args) -> respond(proxy, method, args, answers));
    }

    /** A reference of the ordinary kind: not containment, not transient, not derived, single. */
    private static EReference reference(String name)
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getName", name); //$NON-NLS-1$
        answers.put("isContainment", Boolean.FALSE); //$NON-NLS-1$
        answers.put("isTransient", Boolean.FALSE); //$NON-NLS-1$
        answers.put("isDerived", Boolean.FALSE); //$NON-NLS-1$
        answers.put("isMany", Boolean.FALSE); //$NON-NLS-1$
        return (EReference)Proxy.newProxyInstance(
            AWalkThatFilledTheCapStillFollowsItsRootsTest.class.getClassLoader(),
            new Class<?>[] {EReference.class, EStructuralFeature.class},
            (proxy, method, args) -> respond(proxy, method, args, answers));
    }

    /** An iterator with nothing under it, so a node has no containment to walk. */
    private static final TreeIterator<EObject> NO_CHILDREN = new TreeIterator<>()
    {
        @Override
        public boolean hasNext()
        {
            return false;
        }

        @Override
        public EObject next()
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

    /** Two roots under a cap of two: the walk still answers the edge between them. */
    @Test
    public void theEdgeBetweenTwoAcceptedRootsSurvivesAFullSeed()
    {
        IBmObject target = node("Catalog.Target", null); //$NON-NLS-1$
        IBmObject root = node("Catalog.Root", target); //$NON-NLS-1$

        BmReferencesHelper.BfsResult walk = BmReferencesHelper.bfs(transaction(), engine(),
            List.of(root, target), BmReferencesHelper.Direction.OUT, 2, 500, 1, null, null);

        assertEquals("both roots are nodes", 2, walk.nodes.size()); //$NON-NLS-1$
        assertEquals("the reference between them is an edge", 1, walk.edges.size()); //$NON-NLS-1$
        assertEquals("Catalog.Root", walk.edges.get(0).fromFqn); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Target", walk.edges.get(0).toFqn); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
