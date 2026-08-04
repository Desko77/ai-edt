/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the BFS result from {@link BmReferencesHelper.BfsResult} into
 * different graph formats: JSON (for AI agents), Mermaid (for chat
 * preview), PlantUML and DOT (for documentation).
 * <p>
 * Cyrillic node names are quoted via {@code "..."} in Mermaid/PlantUML/DOT.
 */
public final class DependencyGraphBuilder
{
    private DependencyGraphBuilder()
    {
        // utility class
    }

    /**
     * Output format selector.
     */
    public enum Format
    {
        JSON, MERMAID, PLANTUML, DOT
    }

    /**
     * Renders the BFS result. {@code Format.JSON} returns a structured object
     * keyed in the resulting Map; other formats return text wrapped under the
     * {@code text} key.
     */
    public static Map<String, Object> render(BmReferencesHelper.BfsResult bfs, Format format)
    {
        if (format == null)
        {
            format = Format.JSON;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", format.name().toLowerCase()); //$NON-NLS-1$
        out.put("nodeCount", bfs.nodes.size()); //$NON-NLS-1$
        out.put("edgeCount", bfs.edges.size()); //$NON-NLS-1$
        out.put("truncated", bfs.truncated); //$NON-NLS-1$
        out.put("partial", bfs.truncated); //$NON-NLS-1$
        switch (format)
        {
            case JSON:
                out.put("nodes", buildJsonNodes(bfs)); //$NON-NLS-1$
                out.put("edges", buildJsonEdges(bfs)); //$NON-NLS-1$
                out.put("cycles", detectCycles(bfs)); //$NON-NLS-1$
                break;
            case MERMAID:
                out.put("text", renderMermaid(bfs)); //$NON-NLS-1$
                break;
            case PLANTUML:
                out.put("text", renderPlantUml(bfs)); //$NON-NLS-1$
                break;
            case DOT:
                out.put("text", renderDot(bfs)); //$NON-NLS-1$
                break;
            default:
                out.put("text", renderMermaid(bfs)); //$NON-NLS-1$
                break;
        }
        return out;
    }

    private static List<Map<String, Object>> buildJsonNodes(BmReferencesHelper.BfsResult bfs)
    {
        List<Map<String, Object>> result = new ArrayList<>(bfs.nodes.size());
        for (Map.Entry<String, com._1c.g5.v8.bm.core.IBmObject> entry : bfs.nodes.entrySet())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("fqn", entry.getKey()); //$NON-NLS-1$
            node.put("kind", entry.getValue() != null //$NON-NLS-1$
                ? ((org.eclipse.emf.ecore.EObject) entry.getValue()).eClass().getName()
                : null);
            result.add(node);
        }
        return result;
    }

    private static List<Map<String, Object>> buildJsonEdges(BmReferencesHelper.BfsResult bfs)
    {
        List<Map<String, Object>> result = new ArrayList<>(bfs.edges.size());
        for (BmReferencesHelper.Edge e : bfs.edges)
        {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", e.fromFqn); //$NON-NLS-1$
            edge.put("to", e.toFqn); //$NON-NLS-1$
            if (e.featureName != null)
            {
                edge.put("via", e.featureName); //$NON-NLS-1$
            }
            result.add(edge);
        }
        return result;
    }

    /**
     * Tarjan-style strongly connected component detection over the directed
     * edges. Returns SCCs of size >= 2 (cycles).
     */
    private static List<List<String>> detectCycles(BmReferencesHelper.BfsResult bfs)
    {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String node : bfs.nodes.keySet())
        {
            adjacency.put(node, new ArrayList<>());
        }
        for (BmReferencesHelper.Edge edge : bfs.edges)
        {
            adjacency.computeIfAbsent(edge.fromFqn, k -> new ArrayList<>()).add(edge.toFqn);
            adjacency.computeIfAbsent(edge.toFqn, k -> new ArrayList<>());
        }
        TarjanContext ctx = new TarjanContext(adjacency);
        for (String node : adjacency.keySet())
        {
            if (!ctx.indices.containsKey(node))
            {
                strongconnect(node, ctx);
            }
        }
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> scc : ctx.sccs)
        {
            if (scc.size() >= 2)
            {
                cycles.add(scc);
            }
        }
        return cycles;
    }

    private static void strongconnect(String node, TarjanContext ctx)
    {
        ctx.indices.put(node, ctx.index);
        ctx.lowlinks.put(node, ctx.index);
        ctx.index++;
        ctx.stack.push(node);
        ctx.onStack.add(node);
        for (String successor : ctx.adjacency.getOrDefault(node, java.util.Collections.emptyList()))
        {
            if (!ctx.indices.containsKey(successor))
            {
                strongconnect(successor, ctx);
                ctx.lowlinks.put(node, Math.min(ctx.lowlinks.get(node), ctx.lowlinks.get(successor)));
            }
            else if (ctx.onStack.contains(successor))
            {
                ctx.lowlinks.put(node, Math.min(ctx.lowlinks.get(node), ctx.indices.get(successor)));
            }
        }
        if (ctx.lowlinks.get(node).equals(ctx.indices.get(node)))
        {
            List<String> scc = new ArrayList<>();
            String w;
            do
            {
                w = ctx.stack.pop();
                ctx.onStack.remove(w);
                scc.add(w);
            }
            while (!w.equals(node));
            ctx.sccs.add(scc);
        }
    }

    private static final class TarjanContext
    {
        final Map<String, List<String>> adjacency;
        int index = 0;
        final java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        final Set<String> onStack = new LinkedHashSet<>();
        final Map<String, Integer> indices = new LinkedHashMap<>();
        final Map<String, Integer> lowlinks = new LinkedHashMap<>();
        final List<List<String>> sccs = new ArrayList<>();

        TarjanContext(Map<String, List<String>> adjacency)
        {
            this.adjacency = adjacency;
        }
    }

    // ---- Renderers ---------------------------------------------------------

    private static String renderMermaid(BmReferencesHelper.BfsResult bfs)
    {
        StringBuilder sb = new StringBuilder("graph LR\n"); //$NON-NLS-1$
        Map<String, String> ids = new LinkedHashMap<>();
        int counter = 0;
        for (String fqn : bfs.nodes.keySet())
        {
            String id = "n" + (counter++); //$NON-NLS-1$
            ids.put(fqn, id);
            sb.append("    ").append(id).append("[\"").append(escape(fqn)).append("\"]\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        for (BmReferencesHelper.Edge edge : bfs.edges)
        {
            String fromId = ids.get(edge.fromFqn);
            String toId = ids.get(edge.toFqn);
            if (fromId == null || toId == null)
            {
                continue;
            }
            sb.append("    ").append(fromId).append(" --"); //$NON-NLS-1$ //$NON-NLS-2$
            if (edge.featureName != null)
            {
                sb.append("|\"").append(escape(edge.featureName)).append("\"|"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("> ").append(toId).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    private static String renderPlantUml(BmReferencesHelper.BfsResult bfs)
    {
        StringBuilder sb = new StringBuilder("@startuml\n"); //$NON-NLS-1$
        sb.append("left to right direction\n"); //$NON-NLS-1$
        Map<String, String> ids = new LinkedHashMap<>();
        int counter = 0;
        for (String fqn : bfs.nodes.keySet())
        {
            String id = "n" + (counter++); //$NON-NLS-1$
            ids.put(fqn, id);
            sb.append("rectangle \"").append(escape(fqn)).append("\" as ").append(id).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        for (BmReferencesHelper.Edge edge : bfs.edges)
        {
            String fromId = ids.get(edge.fromFqn);
            String toId = ids.get(edge.toFqn);
            if (fromId == null || toId == null)
            {
                continue;
            }
            sb.append(fromId).append(" --> ").append(toId); //$NON-NLS-1$
            if (edge.featureName != null)
            {
                sb.append(" : ").append(escape(edge.featureName)); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        sb.append("@enduml\n"); //$NON-NLS-1$
        return sb.toString();
    }

    private static String renderDot(BmReferencesHelper.BfsResult bfs)
    {
        StringBuilder sb = new StringBuilder("digraph G {\n"); //$NON-NLS-1$
        sb.append("  rankdir=LR;\n"); //$NON-NLS-1$
        Map<String, String> ids = new LinkedHashMap<>();
        int counter = 0;
        for (String fqn : bfs.nodes.keySet())
        {
            String id = "n" + (counter++); //$NON-NLS-1$
            ids.put(fqn, id);
            sb.append("  ").append(id).append(" [label=\"").append(escape(fqn)).append("\"];\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        for (BmReferencesHelper.Edge edge : bfs.edges)
        {
            String fromId = ids.get(edge.fromFqn);
            String toId = ids.get(edge.toFqn);
            if (fromId == null || toId == null)
            {
                continue;
            }
            sb.append("  ").append(fromId).append(" -> ").append(toId); //$NON-NLS-1$ //$NON-NLS-2$
            if (edge.featureName != null)
            {
                sb.append(" [label=\"").append(escape(edge.featureName)).append("\"]"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append(";\n"); //$NON-NLS-1$
        }
        sb.append("}\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Escapes string for inclusion inside a quoted token in Mermaid / PlantUML
     * / DOT. Replaces {@code "} with {@code \"} and unwraps any embedded
     * newlines (Mermaid does not allow {@code \n} inside labels).
     */
    private static String escape(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
