/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * BFS-friendly helper for collecting forward and backward references on BM
 * objects. Designed for tools that traverse object graphs (e.g.
 * {@code dependency_graph}, {@code project_metrics} usage counters):
 * <p>
 * Learned the hard way: calling {@code ReferenceLocator} from
 * a BFS loop with one {@code display.syncExec} per node deadlocks the EDT UI
 * thread. This helper assumes the caller already owns a BM read-only
 * transaction (single {@code IBmModel.executeReadonlyTask}) and exposes pure
 * functions that operate on the transaction's engine.
 * <p>
 * <b>Contract:</b> all methods are safe to call repeatedly inside a single
 * transaction. No {@code display.syncExec}, no nested BM tasks.
 */
public final class BmReferencesHelper
{
    private BmReferencesHelper()
    {
        // utility class
    }

    /**
     * Lightweight reference record for BFS edges.
     */
    public static final class Reference
    {
        public final IBmObject source;
        public final IBmObject target;
        public final EStructuralFeature feature;
        public final String featureName;

        public Reference(IBmObject source, IBmObject target, EStructuralFeature feature)
        {
            this.source = source;
            this.target = target;
            this.feature = feature;
            this.featureName = feature != null ? feature.getName() : null;
        }
    }

    /**
     * Returns all backward references to the {@code target} object recorded by
     * the BM engine. Filters internal/transient features (dbview, deriveddata,
     * transient setters).
     */
    public static List<Reference> backReferences(IBmEngine engine, IBmObject target)
    {
        if (engine == null || target == null)
        {
            return Collections.emptyList();
        }
        Collection<IBmCrossReference> refs = engine.getBackReferences(target);
        if (refs == null || refs.isEmpty())
        {
            return Collections.emptyList();
        }
        List<Reference> out = new ArrayList<>(refs.size());
        for (IBmCrossReference ref : refs)
        {
            IBmObject source = ref.getObject();
            if (source == null || isInternalReference(ref))
            {
                continue;
            }
            out.add(new Reference(source, target, ref.getFeature()));
        }
        return out;
    }

    /**
     * Returns all forward references from the {@code source} object that point
     * at top-level BM objects in the same project. Walks the object's EMF
     * containment via {@link EObject#eAllContents()} and collects EReference
     * targets that are themselves {@link IBmObject}s.
     * <p>
     * For {@link MdObject}-style sources this returns metadata-graph edges
     * suitable for {@code dependency_graph level=metadata direction=out}.
     */
    public static List<Reference> forwardReferences(IBmTransaction tx, IBmObject source)
    {
        if (tx == null || source == null)
        {
            return Collections.emptyList();
        }
        if (!(source instanceof EObject))
        {
            return Collections.emptyList();
        }
        List<Reference> out = new ArrayList<>();
        // Include the source EObject itself + its full containment subtree.
        EObject root = (EObject) source;
        collectForwardEdges(root, source, out);
        java.util.Iterator<EObject> it = root.eAllContents();
        while (it.hasNext())
        {
            EObject child = it.next();
            collectForwardEdges(child, source, out);
        }
        return out;
    }

    private static void collectForwardEdges(EObject node, IBmObject sourceTopObject,
        List<Reference> out)
    {
        for (EReference eref : node.eClass().getEAllReferences())
        {
            if (eref.isContainment() || eref.isTransient() || eref.isDerived())
            {
                continue;
            }
            Object value = node.eGet(eref);
            if (value == null)
            {
                continue;
            }
            if (eref.isMany())
            {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                for (Object item : list)
                {
                    addEdgeIfBmTop(item, sourceTopObject, eref, out);
                }
            }
            else
            {
                addEdgeIfBmTop(value, sourceTopObject, eref, out);
            }
        }
    }

    private static void addEdgeIfBmTop(Object candidate, IBmObject sourceTopObject,
        EStructuralFeature feature, List<Reference> out)
    {
        if (!(candidate instanceof IBmObject))
        {
            return;
        }
        IBmObject targetBm = (IBmObject) candidate;
        // Walk up to the top-level BM object so the edge connects two top-objects
        // (callers can then key on bmGetFqn()).
        IBmObject targetTop = findTopContainer(targetBm);
        if (targetTop == null || targetTop == sourceTopObject)
        {
            return;
        }
        out.add(new Reference(sourceTopObject, targetTop, feature));
    }

    /**
     * Walks up the EMF containment tree to find the BM top-level object
     * containing {@code object}. Returns {@code null} if none found.
     */
    public static IBmObject findTopContainer(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        if (object.bmIsTop())
        {
            return object;
        }
        EObject current = (EObject) object;
        while (current != null)
        {
            if (current instanceof IBmObject && ((IBmObject) current).bmIsTop())
            {
                return (IBmObject) current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Bidirectional BFS-aware collector. Returns adjacency map keyed by BM FQN.
     * <p>
     * Caller is responsible for invoking this from inside an
     * {@link IBmModel#executeReadonlyTask}. The method does not open a new
     * transaction, and never invokes {@code display.syncExec}.
     *
     * @param tx live BM transaction
     * @param engine the BM engine ({@code IBmModel.getEngine()})
     * @param roots starting BM top-objects
     * @param direction in (back) | out (forward) | both
     * @param maxNodes BFS cap (default caller passes 200)
     * @param maxEdges edge cap (default caller passes 500)
     * @param progressCheck optional cancel signal (e.g. from BM monitor)
     * @return BfsResult with nodes, edges and {@code truncated} flag
     */
    public static BfsResult bfs(IBmTransaction tx, IBmEngine engine, Collection<IBmObject> roots,
        Direction direction, int maxNodes, int maxEdges, CancelCheck progressCheck)
    {
        return bfs(tx, engine, roots, direction, maxNodes, maxEdges, Integer.MAX_VALUE,
            progressCheck);
    }

    /**
     * Depth-limited variant of {@link #bfs(IBmTransaction, IBmEngine, Collection,
     * Direction, int, int, CancelCheck)}.
     *
     * <p>{@code maxDepth} is the number of BFS expansion rounds out from the roots:
     * roots are depth 0; {@code maxDepth=1} expands only the roots (their direct
     * neighbours), {@code maxDepth=2} also expands those neighbours, etc. This is
     * what makes the {@code depth} parameter of {@code dependency_graph} actually
     * bound the metadata / mixed graph - previously this method was flat (bounded
     * only by maxNodes / maxEdges) so {@code depth} was silently ignored for those
     * levels. Pass {@link Integer#MAX_VALUE} for the old unbounded behaviour.
     *
     * @param maxDepth number of expansion rounds from the roots (>= 1)
     */
    public static BfsResult bfs(IBmTransaction tx, IBmEngine engine, Collection<IBmObject> roots,
        Direction direction, int maxNodes, int maxEdges, int maxDepth, CancelCheck progressCheck)
    {
        BfsResult result = new BfsResult();
        if (engine == null || roots == null || roots.isEmpty())
        {
            return result;
        }
        Set<String> visited = new LinkedHashSet<>();
        java.util.Deque<IBmObject> queue = new java.util.ArrayDeque<>(roots);
        for (IBmObject root : roots)
        {
            if (root != null)
            {
                String fqn = safeFqn(root);
                if (fqn != null)
                {
                    visited.add(fqn);
                    result.nodes.put(fqn, root);
                }
            }
        }
        // Level-by-level so maxDepth bounds the rings expanded from the roots.
        // levelSize is captured before each ring; addBfsEdge enqueues the next
        // ring, which is processed only on the following iteration.
        int currentDepth = 0;
        outer: while (!queue.isEmpty() && currentDepth < maxDepth)
        {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++)
            {
                if (progressCheck != null && progressCheck.isCanceled())
                {
                    result.truncated = true;
                    break outer;
                }
                if (result.nodes.size() >= maxNodes || result.edges.size() >= maxEdges)
                {
                    result.truncated = true;
                    break outer;
                }
                IBmObject node = queue.poll();
                if (node == null)
                {
                    continue;
                }
                // Backward references (callers / referencers).
                if (direction == Direction.IN || direction == Direction.BOTH)
                {
                    for (Reference r : backReferences(engine, node))
                    {
                        addBfsEdge(result, queue, visited, r.source, node, r.featureName, maxNodes,
                            maxEdges);
                    }
                }
                // Forward references (callees / referenced objects).
                if (direction == Direction.OUT || direction == Direction.BOTH)
                {
                    for (Reference r : forwardReferences(tx, node))
                    {
                        addBfsEdge(result, queue, visited, node, r.target, r.featureName, maxNodes,
                            maxEdges);
                    }
                }
            }
            currentDepth++;
        }
        return result;
    }

    private static void addBfsEdge(BfsResult result, java.util.Deque<IBmObject> queue,
        Set<String> visited, IBmObject from, IBmObject to, String featureName, int maxNodes,
        int maxEdges)
    {
        if (from == null || to == null)
        {
            return;
        }
        if (result.edges.size() >= maxEdges)
        {
            result.truncated = true;
            return;
        }
        IBmObject fromEnd = reportableEnd(from);
        IBmObject toEnd = reportableEnd(to);
        if (fromEnd == null || toEnd == null)
        {
            return;
        }
        String fromFqn = safeFqn(fromEnd);
        String toFqn = safeFqn(toEnd);
        if (fromFqn == null || toFqn == null || fromFqn.equals(toFqn))
        {
            // Both ends collapsed onto the same owner: the reference is internal to one object and
            // says nothing about the graph between objects.
            return;
        }
        // The target goes in FIRST. The edge used to be added before the cap was consulted, so a
        // walk that stopped at maxNodes returned an edge whose target was in no node of the graph -
        // a reader follows it and finds nothing, with the graph saying nothing about why.
        if (!visited.contains(toFqn))
        {
            if (result.nodes.size() >= maxNodes)
            {
                result.truncated = true;
                return;
            }
            visited.add(toFqn);
            result.nodes.put(toFqn, toEnd);
            queue.add(toEnd);
        }
        // The source is put in the graph AND in the queue. A backward reference arrives from an
        // object no ring has reached, and it used to be recorded as a node without being queued:
        // direction=in then found the referencers of the roots and stopped there, whatever depth
        // was asked for.
        if (!visited.contains(fromFqn))
        {
            if (result.nodes.size() >= maxNodes)
            {
                result.truncated = true;
                return;
            }
            visited.add(fromFqn);
            result.nodes.put(fromFqn, fromEnd);
            queue.add(fromEnd);
        }
        result.edges.add(new Edge(fromFqn, toFqn, featureName));
    }

    /**
     * The object an edge end should name, or <code>null</code> when the end is not part of this
     * graph at all.
     * <p>
     * A backward reference arrives from the object that holds it, and that is often an internal
     * piece of the model: a type, a field of a database view, a derived command group. None of them
     * carries an FQN, so the graph named them after their class - one node called {@code Type}
     * collecting hundreds of edges, standing for nothing a reader can look up. The end is collapsed
     * to the top object that owns it, and an end with no such owner is dropped.
     * </p>
     *
     * @param end one end of a reference
     * @return the top object to name it by, or <code>null</code>
     */
    private static IBmObject reportableEnd(IBmObject end)
    {
        IBmObject top = findTopContainer(end);
        if (top == null)
        {
            return null;
        }
        try
        {
            String fqn = top.bmGetFqn();
            return fqn != null && !fqn.isEmpty() ? top : null;
        }
        catch (Exception notATopObject)
        {
            // Only a top object answers this, and only a top object is a node of this graph.
            return null;
        }
    }

    private static String safeFqn(IBmObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            String fqn = obj.bmGetFqn();
            if (fqn != null)
            {
                return fqn;
            }
        }
        catch (Throwable ignored)
        {
            // best-effort
        }
        if (obj instanceof MdObject)
        {
            String name = ((MdObject) obj).getName();
            if (name != null)
            {
                return obj.eClass().getName() + "." + name; //$NON-NLS-1$
            }
        }
        return obj.eClass().getName();
    }

    private static boolean isInternalReference(IBmCrossReference ref)
    {
        IBmObject object = ref.getObject();
        if (object == null)
        {
            return true;
        }
        EStructuralFeature feature = ref.getFeature();
        if (feature != null && feature.isTransient())
        {
            return true;
        }
        String packageUri = object.eClass().getEPackage().getNsURI();
        if (packageUri == null)
        {
            return true;
        }
        if (packageUri.contains("dbview")) //$NON-NLS-1$
        {
            return true;
        }
        if (packageUri.contains("cmi") && packageUri.contains("deriveddata")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        return false;
    }

    /**
     * Direction selector for BFS.
     */
    public enum Direction
    {
        IN, OUT, BOTH
    }

    /**
     * Cooperative cancel signal for BFS loops; usually wraps an
     * {@link org.eclipse.core.runtime.IProgressMonitor} from BM.
     */
    @FunctionalInterface
    public interface CancelCheck
    {
        boolean isCanceled();
    }

    /**
     * BFS edge with feature label.
     */
    public static final class Edge
    {
        public final String fromFqn;
        public final String toFqn;
        public final String featureName;

        public Edge(String fromFqn, String toFqn, String featureName)
        {
            this.fromFqn = fromFqn;
            this.toFqn = toFqn;
            this.featureName = featureName;
        }
    }

    /**
     * BFS result. {@code nodes} preserves insertion order (LinkedHashMap).
     * {@code truncated} is set when {@code maxNodes}/{@code maxEdges} or
     * the cancel signal kicked in.
     */
    public static final class BfsResult
    {
        public final Map<String, IBmObject> nodes = new LinkedHashMap<>();
        public final List<Edge> edges = new ArrayList<>();
        public boolean truncated;
    }
}
