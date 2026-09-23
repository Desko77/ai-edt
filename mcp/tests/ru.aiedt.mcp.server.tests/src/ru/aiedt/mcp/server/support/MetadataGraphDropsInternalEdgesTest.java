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
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * The dependency graph keeps the objects its level is about, merges a repeated edge, and can keep
 * only the kinds it was asked for.
 * <p>
 * An edge is accepted or dropped by looking at BOTH ends, and the two ends of one reference report
 * it, so the same edge arrives twice and is one edge. A metadata level carries metadata objects; a
 * mixed level carries metadata objects and BSL modules; neither carries an EDT service object.
 * </p>
 * <p>
 * No OSGi project: the ends are proxies, the same way {@link EveryEdgeEndIsANodeTest} stands in
 * for a BM object. The unfiltered walk is not this policy, and that test still reflects the
 * method it always did.
 * </p>
 */
public class MetadataGraphDropsInternalEdgesTest
{
    /** The three kinds of end a level can meet. */
    private enum Kind
    {
        /** A metadata object, the only end the metadata level carries. */
        METADATA("Catalog"),
        /** A BSL module, an end of the mixed level as well. */
        MODULE("Module"),
        /** An EDT service object, an end of neither level. */
        SERVICE("ModuleContextDefIndex");

        final String eClassName;

        Kind(String eClassName)
        {
            this.eClassName = eClassName;
        }
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

    private static IBmObject object(Kind kind, String fqn)
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
                return eClass(kind.eClassName);
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
        types.add(kind == Kind.MODULE ? Module.class : (kind == Kind.METADATA
            ? MdObject.class : IBmObject.class));
        if (!IBmObject.class.isAssignableFrom(types.get(0)))
        {
            types.add(IBmObject.class);
        }
        if (!EObject.class.isAssignableFrom(types.get(0)))
        {
            types.add(EObject.class);
        }
        return (IBmObject)Proxy.newProxyInstance(MetadataGraphDropsInternalEdgesTest.class.getClassLoader(),
            types.toArray(new Class<?>[0]), handler);
    }

    /** No arguments keeps every kind. Named arguments keep only those. */
    private static BmReferencesHelper.EdgePolicy policy(String... kinds)
    {
        return policy(BmReferencesHelper.Ends.METADATA, kinds);
    }

    private static BmReferencesHelper.EdgePolicy policy(BmReferencesHelper.Ends ends,
        String... kinds)
    {
        if (kinds == null || kinds.length == 0)
        {
            return new BmReferencesHelper.EdgePolicy(ends, null);
        }
        return new BmReferencesHelper.EdgePolicy(ends, new LinkedHashSet<>(List.of(kinds)));
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
        edge(result, from, to, via, maxEdges, policy, BmReferencesHelper.Side.FORWARD);
    }

    private static void edge(BmReferencesHelper.BfsResult result, IBmObject from, IBmObject to,
        String via, int maxEdges, BmReferencesHelper.EdgePolicy policy, BmReferencesHelper.Side side)
    {
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>(result.nodes.keySet());
        BmReferencesHelper.acceptEdge(result, queue, visited, from, to, via, 100, maxEdges, policy,
            side);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> jsonEdges(BmReferencesHelper.BfsResult result)
    {
        return (List<Map<String, Object>>)DependencyGraphBuilder
            .render(result, DependencyGraphBuilder.Format.JSON).get("edges");
    }

    /** A non-metadata target is not an edge and not a node. A metadata target stays. */
    @Test
    public void aServiceTargetIsDroppedAndAMetadataTargetStays()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        IBmObject index = object(Kind.SERVICE, "Documents.Order.ObjectModule.bsl.mCtxIdx");
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

    /**
     * A service object is refused as the SOURCE of an edge too, and it is not queued for the next
     * ring. A backward reference arrives from whatever holds it, and that holder is reached without
     * passing the target check - so checking the target alone let a service index of the model into
     * the graph as a node and walked from it.
     */
    @Test
    public void aServiceSourceIsDroppedTooAndIsNotQueued()
    {
        IBmObject order = object(Kind.METADATA, "Document.Order");
        IBmObject index = object(Kind.SERVICE, "Document.Order.Form.Index");
        BmReferencesHelper.BfsResult result = walk(order);
        Deque<IBmObject> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>(result.nodes.keySet());

        BmReferencesHelper.acceptEdge(result, queue, visited, index, order, "refContextDefs", 100,
            100, policy(), BmReferencesHelper.Side.BACKWARD);

        assertEquals(1, result.internalEdgesDropped);
        assertTrue(result.edges.isEmpty());
        assertEquals(1, result.nodes.size());
        assertTrue("a service object is not walked from", queue.isEmpty());
        assertFalse("and it is not visited either", visited.contains("Document.Order.Form.Index"));
    }

    /** The predicate is the metadata type, and it needs no project. */
    @Test
    public void thePredicateIsAnInstanceofCheck()
    {
        assertFalse(BmReferencesHelper.isMetadataObject(object(Kind.SERVICE, "Type.String")));
        assertTrue(BmReferencesHelper.isMetadataObject(object(Kind.METADATA, "Catalog.Goods")));
        assertFalse(BmReferencesHelper.isMetadataObject(null));
        assertFalse(BmReferencesHelper.isMetadataObject(object(Kind.MODULE, "CommonModule.A.Module")));
    }

    /** A module is an end of the mixed level and is a stranger to the metadata level. */
    @Test
    public void aModuleEndIsKeptOnMixedAndDroppedOnMetadata()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject module = object(Kind.MODULE, "CommonModule.Sales.Module");
        assertTrue(BmReferencesHelper.isBslModule(module));
        assertFalse(BmReferencesHelper.isBslModule(goods));

        BmReferencesHelper.BfsResult mixed = walk(goods);
        edge(mixed, goods, module, "types", 100,
            policy(BmReferencesHelper.Ends.MIXED));

        assertEquals(1, mixed.edges.size());
        assertEquals("CommonModule.Sales.Module", mixed.edges.get(0).toFqn);
        assertTrue(mixed.nodes.containsKey("CommonModule.Sales.Module"));
        assertEquals(0, mixed.internalEdgesDropped);

        BmReferencesHelper.BfsResult metadata = walk(goods);
        edge(metadata, goods, module, "types", 100, policy(BmReferencesHelper.Ends.METADATA));

        assertTrue(metadata.edges.isEmpty());
        assertEquals(1, metadata.internalEdgesDropped);
        assertFalse(metadata.nodes.containsKey("CommonModule.Sales.Module"));
    }

    /**
     * One reference is one edge, however the walk came by it. The forward pass enumerates what a
     * node points at and the backward pass asks the engine what points at it; between the same two
     * ends both enumerate the same references, so a reference seen from both sides is one.
     */
    @Test
    public void oneReferenceSeenFromBothSidesIsOne()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        BmReferencesHelper.BfsResult result = walk(goods);

        edge(result, goods, currencies, "types", 100, policy(),
            BmReferencesHelper.Side.FORWARD);
        edge(result, goods, currencies, "types", 100, policy(),
            BmReferencesHelper.Side.BACKWARD);

        assertEquals(1, result.edges.size());
        assertEquals(1, result.edges.get(0).count);
        assertFalse("a count of one is not reported", jsonEdges(result).get(0).containsKey("count"));
    }

    /** Three references between the same two ends are three, seen from either side. */
    @Test
    public void threeReferencesSeenFromBothSidesStayThree()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        BmReferencesHelper.BfsResult result = walk(goods);
        for (int i = 0; i < 3; i++)
        {
            edge(result, goods, currencies, "types", 100, policy(),
                BmReferencesHelper.Side.FORWARD);
            edge(result, goods, currencies, "types", 100, policy(),
                BmReferencesHelper.Side.BACKWARD);
        }

        assertEquals(1, result.edges.size());
        assertEquals(3, result.edges.get(0).count);
        assertEquals(Integer.valueOf(3), jsonEdges(result).get(0).get("count"));
    }

    /**
     * Three references of one kind are one edge. Two kinds on the same pair are two edges.
     * <p>
     * The pair is sighted three times forward and once backward, and it stands for three: the two
     * passes enumerate the same references between the same ends, so the larger tally is the one
     * worth reporting. An ordinal increment over the sightings would answer four here, and this is
     * the sighting that tells the two apart - with one side alone both readings agree.
     * </p>
     */
    @Test
    public void repeatedEdgesMergeAndDistinctKindsDoNot()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        BmReferencesHelper.BfsResult merged = walk(goods);
        edge(merged, goods, currencies, "types", 1, policy());
        edge(merged, goods, currencies, "types", 1, policy());
        edge(merged, goods, currencies, "types", 1, policy());
        edge(merged, goods, currencies, "types", 1, policy(),
            BmReferencesHelper.Side.BACKWARD);

        assertFalse(merged.truncated);
        assertEquals(1, merged.edges.size());
        assertEquals("the two passes enlist the same references, so the larger tally is the count",
            3, merged.edges.get(0).count);
        assertEquals(Integer.valueOf(3), jsonEdges(merged).get(0).get("count"));

        BmReferencesHelper.BfsResult two = walk(goods);
        edge(two, goods, currencies, "types", 100, policy());
        edge(two, goods, currencies, "basedOn", 100, policy());

        assertEquals(2, two.edges.size());
        assertEquals(1, two.edges.get(0).count);
        assertEquals(1, two.edges.get(1).count);
        List<Map<String, Object>> plain = jsonEdges(two);
        assertFalse(plain.get(0).containsKey("count"));
        assertFalse(plain.get(1).containsKey("count"));
    }

    /** A dropped edge is counted once, whichever side reported it. */
    @Test
    public void aDroppedEdgeIsCountedOnceWhicheverSideReportsIt()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject index = object(Kind.SERVICE, "Document.Order.Form.Index");
        BmReferencesHelper.BfsResult result = walk(goods);

        edge(result, goods, index, "refContextDefs", 100, policy(),
            BmReferencesHelper.Side.FORWARD);
        edge(result, goods, index, "refContextDefs", 100, policy(),
            BmReferencesHelper.Side.BACKWARD);

        assertEquals("one edge, however many times the two ends report it", 1,
            result.internalEdgesDropped);
    }

    /**
     * An edge dropped for its kind and an edge dropped for an end are two different drops, and each
     * counter answers for its own.
     * <p>
     * The two used to share one set of keys, and the end-drop published that set's size as
     * {@code internalEdgesDropped}: a kind refused earlier was counted a second time here, so one
     * internal edge was reported as two.
     * </p>
     */
    @Test
    public void anInternalDropIsNotCountedWithAKindDrop()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        IBmObject index = object(Kind.SERVICE, "Document.Order.Form.Index");
        BmReferencesHelper.BfsResult result = walk(goods);

        edge(result, goods, currencies, "basedOn", 100, policy("types"));
        edge(result, goods, index, "refContextDefs", 100, policy("types"));

        assertTrue(result.edges.isEmpty());
        assertEquals(Integer.valueOf(1), result.edgesDroppedByKind.get("basedOn"));
        assertEquals("one internal edge is one, and the refused kind is not one of them", 1,
            result.internalEdgesDropped);
    }

    /** A named kind is kept. A kind the walk never saw is reported and is not a refusal. */
    @Test
    public void namedKindsAreKeptAndAnUnknownKindLeavesTheRoots()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        IBmObject partner = object(Kind.METADATA, "Catalog.Partner");
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

    /** A kind the filter refuses is dropped once, whichever side reported the same edge. */
    @Test
    public void aRefusedKindIsCountedOnceWhicheverSideReportsIt()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        BmReferencesHelper.BfsResult result = walk(goods);

        edge(result, goods, currencies, "basedOn", 100, policy("types"),
            BmReferencesHelper.Side.FORWARD);
        edge(result, goods, currencies, "basedOn", 100, policy("types"),
            BmReferencesHelper.Side.BACKWARD);

        assertTrue(result.edges.isEmpty());
        assertEquals(Integer.valueOf(1), result.edgesDroppedByKind.get("basedOn"));
    }

    /**
     * The mixed level keeps the kinds the caller named on an edge between two modules, and still
     * drops the service object.
     */
    @Test
    public void theMixedLevelKeepsAModulePairAndStillDropsAServiceEnd()
    {
        IBmObject caller = object(Kind.MODULE, "CommonModule.Sales.Module");
        IBmObject callee = object(Kind.MODULE, "CommonModule.Prices.Module");
        IBmObject index = object(Kind.SERVICE, "CommonModule.Sales.Module.mCtxIdx");
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        result.nodes.put("CommonModule.Sales.Module", caller);

        edge(result, caller, callee, "calls", 100,
            policy(BmReferencesHelper.Ends.MIXED, "calls"));
        edge(result, caller, index, "refContextDefs", 100,
            policy(BmReferencesHelper.Ends.MIXED, "calls"));

        assertEquals(1, result.edges.size());
        assertEquals("CommonModule.Prices.Module", result.edges.get(0).toFqn);
        assertEquals(1, result.internalEdgesDropped);
    }

    /** No argument keeps every metadata kind, and still drops the internal target. */
    @Test
    public void withoutTheArgumentEveryMetadataKindStays()
    {
        IBmObject goods = object(Kind.METADATA, "Catalog.Goods");
        IBmObject currencies = object(Kind.METADATA, "Catalog.Currencies");
        IBmObject index = object(Kind.SERVICE, "Documents.Order.ObjectModule.bsl.mCtxIdx");
        BmReferencesHelper.BfsResult result = walk(goods);
        BmReferencesHelper.EdgePolicy all = new BmReferencesHelper.EdgePolicy(
            BmReferencesHelper.Ends.METADATA, null);
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
