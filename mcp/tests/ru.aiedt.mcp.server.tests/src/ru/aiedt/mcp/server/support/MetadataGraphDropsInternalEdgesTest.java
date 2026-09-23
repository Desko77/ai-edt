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
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * The metadata dependency graph keeps metadata objects, merges a repeated edge, and can keep only
 * the kinds it was asked for.
 * <p>
 * No OSGi project: the ends are proxies, the same way {@link EveryEdgeEndIsANodeTest} stands in
 * for a BM object. The unfiltered walk is not this policy, and that test still reflects the
 * method it always did.
 * </p>
 */
public class MetadataGraphDropsInternalEdgesTest
{
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

    private static Object eClass(String name)
    {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName())
            {
            case "getName":
                return name;
            case "hashCode":
                return Integer.valueOf(System.identityHashCode(proxy));
            case "equals":
                return Boolean.valueOf(proxy == args[0]);
            case "toString":
                return name;
            default:
                return defaultOf(method.getReturnType());
            }
        };
        return Proxy.newProxyInstance(MetadataGraphDropsInternalEdgesTest.class.getClassLoader(),
            new Class<?>[] {EClass.class}, handler);
    }

    private static IBmObject object(boolean metadata, String fqn)
    {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName())
            {
            case "bmIsTop":
                return Boolean.TRUE;
            case "bmGetFqn":
                return fqn;
            case "eContainer":
                return null;
            case "eClass":
                return eClass(metadata ? "Catalog" : "ModuleContextDefIndex");
            case "hashCode":
                return Integer.valueOf(System.identityHashCode(proxy));
            case "equals":
                return Boolean.valueOf(proxy == args[0]);
            case "toString":
                return String.valueOf(fqn);
            default:
                return defaultOf(method.getReturnType());
            }
        };
        List<Class<?>> types = new ArrayList<>();
        if (metadata)
        {
            types.add(MdObject.class);
            if (!IBmObject.class.isAssignableFrom(MdObject.class))
            {
                types.add(IBmObject.class);
            }
            if (!EObject.class.isAssignableFrom(MdObject.class))
            {
                types.add(EObject.class);
            }
        }
        else
        {
            types.add(IBmObject.class);
            types.add(EObject.class);
        }
        return (IBmObject)Proxy.newProxyInstance(MetadataGraphDropsInternalEdgesTest.class.getClassLoader(),
            types.toArray(new Class<?>[0]), handler);
    }

    /** No arguments keeps every kind. Named arguments keep only those. */
    private static BmReferencesHelper.EdgePolicy policy(String... kinds)
    {
        if (kinds == null || kinds.length == 0)
        {
            return new BmReferencesHelper.EdgePolicy(true, null);
        }
        return new BmReferencesHelper.EdgePolicy(true, new LinkedHashSet<>(List.of(kinds)));
    }

    private static BmReferencesHelper.BfsResult walk(IBmObject root)
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put("Catalog.Goods", root);
        return result;
    }

    private static void edge(BmReferencesHelper.BfsResult result, IBmObject from, IBmObject to,
        String via, int maxEdges, BmReferencesHelper.EdgePolicy policy)
    {
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>(result.nodes.keySet());
        BmReferencesHelper.acceptEdge(result, queue, visited, from, to, via, 100, maxEdges, policy);
    }

    /** A non-metadata target is not an edge and not a node. A metadata target stays. */
    @Test
    public void aServiceTargetIsDroppedAndAMetadataTargetStays()
    {
        IBmObject goods = object(true, "Catalog.Goods");
        IBmObject currencies = object(true, "Catalog.Currencies");
        IBmObject index = object(false, "Documents.Order.ObjectModule.bsl.mCtxIdx");
        BmReferencesHelper.BfsResult result = walk(goods);

        edge(result, goods, index, "refContextDefs", 100, policy());
        edge(result, goods, currencies, "types", 100, policy());

        assertEquals(1, result.internalEdgesDropped);
        assertFalse(result.nodes.containsKey("Documents.Order.ObjectModule.bsl.mCtxIdx"));
        assertTrue(result.nodes.containsKey("Catalog.Currencies"));
        assertEquals(1, result.edges.size());
        assertEquals("Catalog.Currencies", result.edges.get(0).toFqn);
        assertEquals("types", result.edges.get(0).featureName);
    }

    /** The predicate is the metadata type, and it needs no project. */
    @Test
    public void thePredicateIsAnInstanceofCheck()
    {
        assertFalse(BmReferencesHelper.isMetadataObject(object(false, "Type.String")));
        assertTrue(BmReferencesHelper.isMetadataObject(object(true, "Catalog.Goods")));
        assertFalse(BmReferencesHelper.isMetadataObject(null));
    }

    /** Three references of one kind are one edge. Two kinds on the same pair are two edges. */
    @Test
    public void repeatedEdgesMergeAndDistinctKindsDoNot()
    {
        IBmObject goods = object(true, "Catalog.Goods");
        IBmObject currencies = object(true, "Catalog.Currencies");
        BmReferencesHelper.BfsResult merged = walk(goods);
        edge(merged, goods, currencies, "types", 1, policy());
        edge(merged, goods, currencies, "types", 1, policy());
        edge(merged, goods, currencies, "types", 1, policy());

        assertFalse(merged.truncated);
        assertEquals(1, merged.edges.size());
        assertEquals(3, merged.edges.get(0).count);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> json = (List<Map<String, Object>>)DependencyGraphBuilder
            .render(merged, DependencyGraphBuilder.Format.JSON).get("edges");
        assertEquals(Integer.valueOf(3), json.get(0).get("count"));

        BmReferencesHelper.BfsResult two = walk(goods);
        edge(two, goods, currencies, "types", 100, policy());
        edge(two, goods, currencies, "basedOn", 100, policy());

        assertEquals(2, two.edges.size());
        assertEquals(1, two.edges.get(0).count);
        assertEquals(1, two.edges.get(1).count);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plain = (List<Map<String, Object>>)DependencyGraphBuilder
            .render(two, DependencyGraphBuilder.Format.JSON).get("edges");
        assertFalse(plain.get(0).containsKey("count"));
        assertFalse(plain.get(1).containsKey("count"));
    }

    /** A named kind is kept. A kind the walk never saw is reported and is not a refusal. */
    @Test
    public void namedKindsAreKeptAndAnUnknownKindLeavesTheRoots()
    {
        IBmObject goods = object(true, "Catalog.Goods");
        IBmObject currencies = object(true, "Catalog.Currencies");
        IBmObject partner = object(true, "Catalog.Partner");
        BmReferencesHelper.BfsResult kept = walk(goods);
        edge(kept, goods, currencies, "types", 100, policy("types"));
        edge(kept, goods, partner, "basedOn", 100, policy("types"));

        assertEquals(1, kept.edges.size());
        assertEquals("types", kept.edges.get(0).featureName);
        assertTrue(kept.nodes.containsKey("Catalog.Currencies"));
        assertFalse(kept.nodes.containsKey("Catalog.Partner"));
        assertEquals(Integer.valueOf(1), kept.edgesDroppedByKind.get("basedOn"));

        BmReferencesHelper.BfsResult none = walk(goods);
        edge(none, goods, currencies, "types", 100, policy("nosuchkind"));

        assertTrue(none.edges.isEmpty());
        assertEquals(Set.of("Catalog.Goods"), none.nodes.keySet());
        Map<String, Object> fields = BmReferencesHelper.edgeKindFields("metadata",
            List.of("nosuchkind"), none);
        assertEquals(List.of("nosuchkind"), fields.get("edgeKinds"));
        assertEquals(List.of("nosuchkind"), fields.get("unmatchedKinds"));
        assertEquals(Integer.valueOf(1), none.edgesDroppedByKind.get("types"));
    }

    /** No argument keeps every metadata kind, and still drops the internal target. */
    @Test
    public void withoutTheArgumentEveryMetadataKindStays()
    {
        IBmObject goods = object(true, "Catalog.Goods");
        IBmObject currencies = object(true, "Catalog.Currencies");
        IBmObject index = object(false, "Documents.Order.ObjectModule.bsl.mCtxIdx");
        BmReferencesHelper.BfsResult result = walk(goods);
        BmReferencesHelper.EdgePolicy all = new BmReferencesHelper.EdgePolicy(true, null);
        edge(result, goods, currencies, "types", 100, all);
        edge(result, goods, currencies, "basedOn", 100, all);
        edge(result, goods, index, "refContextDefs", 100, all);

        assertEquals(2, result.edges.size());
        assertEquals(1, result.internalEdgesDropped);
        Map<String, Object> fields = BmReferencesHelper.edgeKindFields("metadata", null, result);
        assertFalse(fields.containsKey("edgeKinds"));
        assertEquals(Integer.valueOf(1), fields.get("internalEdgesDropped"));
        assertFalse(fields.containsKey("unmatchedKinds"));
    }

    /** On modules the argument is not applied and a calls edge is left as it was. */
    @Test
    public void modulesReportTheArgumentNotApplied()
    {
        BmReferencesHelper.BfsResult calls = new BmReferencesHelper.BfsResult();
        calls.edges.add(new BmReferencesHelper.Edge("CommonModule.A", "CommonModule.B", "calls"));

        Map<String, Object> fields =
            BmReferencesHelper.edgeKindFields("modules", List.of("types"), calls);

        assertEquals("notApplied", fields.get("edgeKinds"));
        assertFalse(fields.containsKey("edgesDroppedByKind"));
        assertFalse(fields.containsKey("unmatchedKinds"));
        assertEquals(1, calls.edges.size());
        assertEquals("calls", calls.edges.get(0).featureName);
        assertEquals(1, calls.edges.get(0).count);
    }
}
