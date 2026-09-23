/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Walks the dependency graph over stand-in objects, so that both promises of one walk are checked
 * where a reader meets them: which objects the answer carries, and what one edge is worth.
 * <p>
 * The ends are proxies on a stand-in EMF model - a metadata object, a BSL module, an EDT service
 * object - and the engine reports the backward references. No project and no BM runtime are
 * involved; the walk itself is the production one.
 * </p>
 * <p>
 * A reference between two objects is enumerated by the forward pass of the source and by the
 * backward pass of the target, so the default {@code direction=both} walk meets it twice. The two
 * sightings are one reference. And an end is judged on both sides: the source of a backward
 * reference is reached without passing the target check, so a service object there used to enter
 * the graph as a node and be walked from.
 * </p>
 */
public class MixedEndsAndReferenceCountTest
{
    /** The namespace of the stand-in EMF package; it must not look like an EDT-internal one. */
    private static final String PACKAGE_URI = "http://example.org/dt"; //$NON-NLS-1$

    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 500;

    /** What a stand-in object answers for everything it was not asked about. */
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
        if (type == char.class)
        {
            return Character.valueOf('\0');
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        if (type == float.class)
        {
            return Float.valueOf(0);
        }
        if (type == double.class)
        {
            return Double.valueOf(0);
        }
        return Integer.valueOf(0);
    }

    private static Object respond(Object proxy, Method method, Object[] args,
        Map<String, Object> answers)
    {
        String name = method.getName();
        if (answers.containsKey(name))
        {
            return answers.get(name);
        }
        switch (name)
        {
        case "hashCode":
            return Integer.valueOf(System.identityHashCode(proxy));
        case "equals":
            return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
        case "toString":
            return String.valueOf(answers.getOrDefault("fqn", name));
        default:
            return defaultOf(method.getReturnType());
        }
    }

    private static Object stub(Class<?> type, Map<String, Object> answers)
    {
        return Proxy.newProxyInstance(MixedEndsAndReferenceCountTest.class.getClassLoader(),
            new Class<?>[] {type}, (proxy, method, args) -> respond(proxy, method, args, answers));
    }

    private static EPackage ePackage()
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getName", "dt");
        answers.put("getNsURI", PACKAGE_URI);
        return (EPackage)stub(EPackage.class, answers);
    }

    /** A non-containment reference of the feature name, pointing at one object. */
    private static EReference reference(String name)
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getName", name);
        answers.put("isContainment", Boolean.FALSE);
        answers.put("isTransient", Boolean.FALSE);
        answers.put("isDerived", Boolean.FALSE);
        answers.put("isMany", Boolean.FALSE);
        return (EReference)stub(EReference.class, answers);
    }

    private static final TreeIterator<EObject> NO_CHILDREN = new TreeIterator<EObject>()
    {
        @Override
        public boolean hasNext()
        {
            return false;
        }

        @Override
        public EObject next()
        {
            throw new NoSuchElementException();
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
     * A stand-in BM object: what it is called, which model type it belongs to, and what it points
     * at.
     * <p>
     * Wire the references before or after {@link #build()} - the model type hands the proxy the
     * live list, so a proxy built first reports what is added to it later. Build each object once
     * and wire it once; two proxies of one {@code Node} are two identities to the walk.
     * </p>
     */
    private static final class Node
    {
        private final String fqn;

        private final Class<?> type;

        private final String eClassName;

        private final Map<String, Object> answers = new LinkedHashMap<>();

        private final Map<EReference, Object> outgoing = new LinkedHashMap<>();

        private final EList<EReference> references = new BasicEList<>();

        Node(String fqn, Class<?> type, String eClassName)
        {
            this.fqn = fqn;
            this.type = type;
            this.eClassName = eClassName;
        }

        Node pointsAt(EReference feature, Object target)
        {
            references.add(feature);
            outgoing.put(feature, target);
            return this;
        }

        IBmObject build()
        {
            answers.put("bmIsTop", Boolean.TRUE);
            answers.put("bmGetFqn", fqn);
            answers.put("fqn", fqn);
            answers.put("eContainer", null);
            answers.put("eClass", eClass());
            answers.put("eAllContents", NO_CHILDREN);
            List<Class<?>> types = new ArrayList<>();
            types.add(type);
            if (!type.isAssignableFrom(IBmObject.class))
            {
                types.add(IBmObject.class);
            }
            return (IBmObject)Proxy.newProxyInstance(
                MixedEndsAndReferenceCountTest.class.getClassLoader(),
                types.toArray(new Class<?>[0]),
                (proxy, method, args) -> "eGet".equals(method.getName())
                    ? outgoing.get(args[0])
                    : respond(proxy, method, args, answers));
        }

        private EClass eClass()
        {
            Map<String, Object> classAnswers = new LinkedHashMap<>();
            classAnswers.put("getName", eClassName);
            classAnswers.put("getEPackage", ePackage());
            classAnswers.put("getEAllReferences", references);
            return (EClass)stub(EClass.class, classAnswers);
        }
    }

    private static Node metadata(String fqn)
    {
        return new Node(fqn, MdObject.class, "Catalog");
    }

    private static Node module(String fqn)
    {
        return new Node(fqn, Module.class, "Module");
    }

    private static Node service(String fqn)
    {
        return new Node(fqn, IBmObject.class, "ModuleContextDefIndex");
    }

    private static IBmCrossReference backReference(IBmObject source, EStructuralFeature feature)
    {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getObject", source);
        answers.put("getFeature", feature);
        return (IBmCrossReference)stub(IBmCrossReference.class, answers);
    }

    /** An engine that reports the backward references it was handed, by target. */
    private static IBmEngine engine(Map<IBmObject, Collection<IBmCrossReference>> backReferences)
    {
        return (IBmEngine)Proxy.newProxyInstance(
            MixedEndsAndReferenceCountTest.class.getClassLoader(), new Class<?>[] {IBmEngine.class},
            (proxy, method, args) -> {
                if ("getBackReferences".equals(method.getName()))
                {
                    Collection<IBmCrossReference> refs = backReferences.get(args[0]);
                    return refs == null ? Collections.emptyList() : refs;
                }
                return respond(proxy, method, args, new LinkedHashMap<>());
            });
    }

    /**
     * The transaction is here for its identity alone - {@code forwardReferences} answers nothing
     * without one - so a stand-in is enough.
     * <p>
     * Its loader builds the proxy, not this bundle: one method of the interface names an internal
     * type of the same BM bundle, and a proxy built by a loader that cannot see that type is
     * refused outright.
     * </p>
     */
    private static IBmTransaction transaction()
    {
        return (IBmTransaction)Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
            new Class<?>[] {IBmTransaction.class},
            (proxy, method, args) -> respond(proxy, method, args, new LinkedHashMap<>()));
    }

    private static BmReferencesHelper.EdgePolicy policy(BmReferencesHelper.Ends ends)
    {
        return new BmReferencesHelper.EdgePolicy(ends, null);
    }

    private static BmReferencesHelper.BfsResult walk(Collection<IBmObject> roots,
        BmReferencesHelper.Direction direction, int depth,
        Map<IBmObject, Collection<IBmCrossReference>> backReferences,
        BmReferencesHelper.EdgePolicy policy)
    {
        return BmReferencesHelper.bfs(transaction(), engine(backReferences), roots, direction,
            MAX_NODES, MAX_EDGES, depth, null, policy);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> jsonEdges(BmReferencesHelper.BfsResult result)
    {
        return (List<Map<String, Object>>)DependencyGraphBuilder
            .render(result, DependencyGraphBuilder.Format.JSON).get("edges");
    }

    /** A module target is a project node on the mixed level and no end at all on the metadata one. */
    @Test
    public void aModuleTargetIsAProjectNodeOnMixedOnly()
    {
        Node goods = metadata("Catalog.Goods");
        IBmObject root = goods.build();
        IBmObject sales = module("CommonModule.Sales.Module").build();
        goods.pointsAt(reference("types"), sales);

        BmReferencesHelper.BfsResult mixed = walk(List.of(root),
            BmReferencesHelper.Direction.OUT, 1, Map.of(), policy(BmReferencesHelper.Ends.MIXED));

        assertEquals(2, mixed.nodes.size());
        assertTrue("the module is a node", mixed.nodes.containsKey("CommonModule.Sales.Module"));
        assertEquals(1, mixed.edges.size());
        assertEquals("CommonModule.Sales.Module", mixed.edges.get(0).toFqn);
        assertEquals(0, mixed.internalEdgesDropped);

        BmReferencesHelper.BfsResult onlyMetadata = walk(List.of(root),
            BmReferencesHelper.Direction.OUT, 1, Map.of(),
            policy(BmReferencesHelper.Ends.METADATA));

        assertEquals(Set.of("Catalog.Goods"), onlyMetadata.nodes.keySet());
        assertTrue(onlyMetadata.edges.isEmpty());
        assertEquals(1, onlyMetadata.internalEdgesDropped);
    }

    /**
     * A module root - what {@code level=mixed scope=module} walks from - keeps its module
     * dependencies; on the metadata level only the root stays.
     */
    @Test
    public void aModuleRootKeepsItsModuleDependenciesOnMixedOnly()
    {
        Node sales = module("CommonModule.Sales.Module");
        IBmObject root = sales.build();
        IBmObject prices = module("CommonModule.Prices.Module").build();
        sales.pointsAt(reference("calls"), prices);

        BmReferencesHelper.BfsResult mixed = walk(List.of(root),
            BmReferencesHelper.Direction.OUT, 2, Map.of(), policy(BmReferencesHelper.Ends.MIXED));

        assertEquals(2, mixed.nodes.size());
        assertTrue(mixed.nodes.containsKey("CommonModule.Prices.Module"));
        assertEquals(1, mixed.edges.size());
        assertEquals("calls", mixed.edges.get(0).featureName);

        BmReferencesHelper.BfsResult onlyMetadata = walk(List.of(root),
            BmReferencesHelper.Direction.OUT, 2, Map.of(),
            policy(BmReferencesHelper.Ends.METADATA));

        assertEquals("only the root stays", Set.of("CommonModule.Sales.Module"),
            onlyMetadata.nodes.keySet());
        assertTrue(onlyMetadata.edges.isEmpty());
    }

    /** One reference between two objects is one edge, seen from both sides of the walk. */
    @Test
    public void oneReferenceWalkedBothWaysIsOneEdge()
    {
        Node goods = metadata("Catalog.Goods");
        IBmObject root = goods.build();
        IBmObject target = metadata("Catalog.Currencies").build();
        EReference types = reference("types");
        goods.pointsAt(types, target);

        BmReferencesHelper.BfsResult result = walk(List.of(root), BmReferencesHelper.Direction.BOTH,
            2, Map.of(target, List.of(backReference(root, types))),
            policy(BmReferencesHelper.Ends.METADATA));

        assertEquals(2, result.nodes.size());
        assertEquals(1, result.edges.size());
        assertEquals("one reference is one", 1, result.edges.get(0).count);
        assertFalse("a count of one is not reported",
            jsonEdges(result).get(0).containsKey("count"));
    }

    /** Three references between the same two objects are three, whichever way they are met. */
    @Test
    public void threeReferencesWalkedBothWaysAreThree()
    {
        Node goods = metadata("Catalog.Goods");
        IBmObject root = goods.build();
        IBmObject target = metadata("Catalog.Currencies").build();
        List<IBmCrossReference> back = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            EReference types = reference("types");
            goods.pointsAt(types, target);
            back.add(backReference(root, types));
        }

        BmReferencesHelper.BfsResult result = walk(List.of(root), BmReferencesHelper.Direction.BOTH,
            2, Map.of(target, back), policy(BmReferencesHelper.Ends.METADATA));

        assertEquals(1, result.edges.size());
        assertEquals(3, result.edges.get(0).count);
        assertEquals(Integer.valueOf(3), jsonEdges(result).get(0).get("count"));
    }

    /**
     * A service object that holds a reference is not a node and is not walked from: the reference
     * it holds to a metadata object never reaches the graph.
     */
    @Test
    public void aServiceSourceOfABackReferenceIsNotANodeAndIsNotWalked()
    {
        Node order = metadata("Document.Order");
        IBmObject root = order.build();
        Node index = service("Document.Order.Form.Index");
        IBmObject holder = index.build();
        EReference uses = reference("uses");
        index.pointsAt(uses, metadata("Catalog.Partner").build());

        BmReferencesHelper.BfsResult result = walk(List.of(root), BmReferencesHelper.Direction.BOTH,
            2, Map.of(root, List.of(backReference(holder, uses))),
            policy(BmReferencesHelper.Ends.METADATA));

        assertTrue(result.edges.isEmpty());
        assertEquals(Set.of("Document.Order"), result.nodes.keySet());
        assertFalse("a service object is not a node",
            result.nodes.containsKey("Document.Order.Form.Index"));
        assertFalse("and nothing it points at is reached",
            result.nodes.containsKey("Catalog.Partner"));
        assertEquals(1, result.internalEdgesDropped);
    }

    /** The same walk on the mixed level keeps the module nodes and the edges between them. */
    @Test
    public void theMixedLevelWalksModulesBesideMetadata()
    {
        Node sales = module("CommonModule.Sales.Module");
        IBmObject root = sales.build();
        Node prices = module("CommonModule.Prices.Module");
        IBmObject intermediate = prices.build();
        IBmObject goods = metadata("Catalog.Goods").build();
        prices.pointsAt(reference("types"), goods);
        sales.pointsAt(reference("calls"), intermediate);

        BmReferencesHelper.BfsResult result = walk(List.of(root), BmReferencesHelper.Direction.OUT,
            3, Map.of(), policy(BmReferencesHelper.Ends.MIXED));

        assertEquals(new LinkedHashSet<>(List.of("CommonModule.Sales.Module",
            "CommonModule.Prices.Module", "Catalog.Goods")), result.nodes.keySet());
        assertEquals(2, result.edges.size());
        assertEquals("CommonModule.Prices.Module", result.edges.get(0).toFqn);
        assertEquals("Catalog.Goods", result.edges.get(1).toFqn);
    }
}
