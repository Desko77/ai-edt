/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.support.BmReferencesHelper;
import ru.aiedt.mcp.server.support.BslCallGraphHelper;
import ru.aiedt.mcp.server.support.DependencyGraphBuilder;
import ru.aiedt.mcp.server.support.WatchForCancel;

/**
 * The walk of the module level resolves every edge it records: an edge the reference index gave is
 * model-resolved, an edge only the module's text gave is name-resolved and lists the place of the
 * call, and an edge both gave is the model one.
 */
public class AModuleEdgeSaysHowItWasResolvedTest
{
    private static final String ROOT = "CommonModule.Root.Module"; //$NON-NLS-1$
    private static final String CALLEE = "CommonModule.Callee.Module"; //$NON-NLS-1$

    /** An edge the reference index gave is model-resolved and lists no place. */
    @Test
    public void anEdgeTheIndexGaveIsModelResolved()
    {
        Module root = module();
        Map<Module, List<String>> callers = new IdentityHashMap<>();
        callers.put(root, List.of(CALLEE));
        BmReferencesHelper.BfsResult walk = walk(root, absentLookup(),
            BmReferencesHelper.Direction.IN, 1, source(callers, List.of()));

        BmReferencesHelper.Edge edge = walk.edges.get(0);
        assertEquals(DependencyGraphTool.RESOLUTION_MODEL, edge.resolution);
        assertTrue("a model-resolved edge collects no place", edge.nameSites().isEmpty()); //$NON-NLS-1$
    }

    /** An edge only the text gave is name-resolved and lists the place of its call. */
    @Test
    public void anEdgeOnlyTheTextGaveIsNameResolved()
    {
        BslCallGraphHelper.NameCall call =
            new BslCallGraphHelper.NameCall(CALLEE, "РассчитатьВсе", 12); //$NON-NLS-1$

        BmReferencesHelper.BfsResult walk = walk(module(), absentLookup(),
            BmReferencesHelper.Direction.OUT, 1, source(new IdentityHashMap<>(), List.of(call)));

        BmReferencesHelper.Edge edge = walk.edges.get(0);
        assertEquals(DependencyGraphTool.RESOLUTION_NAME, edge.resolution);
        assertEquals(1, edge.nameSites().size());
        assertEquals(ROOT, edge.nameSites().get(0).file);
        assertEquals(12, edge.nameSites().get(0).line);
        // The rendering of the walk's own answer: the count is the number of places.
        Map<String, Object> rendered = renderedEdgesOf(walk).get(0);
        assertEquals(Integer.valueOf(1), rendered.get("count")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sites = (List<Map<String, Object>>)rendered.get("sites"); //$NON-NLS-1$
        assertEquals(1, sites.size());
        assertEquals(ROOT, sites.get(0).get("file")); //$NON-NLS-1$
    }

    /** An edge both the index and the text gave is the model one, without places. */
    @Test
    public void anEdgeBothGaveIsTheModelOne()
    {
        Module root = module();
        Module callee = module();
        BslCallGraphHelper.NameCall call =
            new BslCallGraphHelper.NameCall(CALLEE, "РассчитатьВсе", 12); //$NON-NLS-1$

        // The root names the callee in its text, and the callee's own callers, read from the
        // index, name the root back: the same pair, given by both ways.
        Map<Module, List<String>> callers = new IdentityHashMap<>();
        callers.put(root, List.of());
        callers.put(callee, List.of(ROOT));
        BmReferencesHelper.BfsResult walk = walk(root,
            fqn -> DependencyGraphTool.ModuleResolution.loaded(callee),
            BmReferencesHelper.Direction.BOTH, 2, source(callers, List.of(call)));

        BmReferencesHelper.Edge edge = walk.edges.get(0);
        assertEquals(DependencyGraphTool.RESOLUTION_MODEL, edge.resolution);
        assertFalse("the model resolution keeps no places", //$NON-NLS-1$
            renderedEdgesOf(walk).get(0).containsKey("sites")); //$NON-NLS-1$
    }

    /**
     * Walks the module level from a root over a named source.
     *
     * @param root the module walked first
     * @param lookup the module lookup
     * @param direction the direction to walk in
     * @param depth how many rings to expand past the root
     * @param source the call source
     * @return the walk
     */
    private static BmReferencesHelper.BfsResult walk(Module root,
        DependencyGraphTool.ModuleLookup lookup, BmReferencesHelper.Direction direction,
        int depth, BslCallGraphHelper.ModuleCallSource source)
    {
        LinkedHashMap<String, Module> roots = new LinkedHashMap<>();
        roots.put(ROOT, root);
        return DependencyGraphTool.buildModuleGraph(roots, lookup, new ArrayList<>(), direction,
            depth, 10, 100, null, WatchForCancel.begin(), source);
    }

    /**
     * A call source over a caller map, answering the same name-written calls for every module.
     *
     * @param callers module to the FQNs that call it, identity keyed
     * @param nameCalls the name-written calls every module reads
     * @return the source
     */
    private static BslCallGraphHelper.ModuleCallSource source(Map<Module, List<String>> callers,
        List<BslCallGraphHelper.NameCall> nameCalls)
    {
        return new BslCallGraphHelper.ModuleCallSource()
        {
            @Override
            public List<String> callersOf(Module module)
            {
                return callers.getOrDefault(module, List.of());
            }

            @Override
            public List<BslCallGraphHelper.NameCall> nameCallsOf(Module module)
            {
                return nameCalls;
            }
        };
    }

    /** A lookup that answers no module. */
    private static DependencyGraphTool.ModuleLookup absentLookup()
    {
        return fqn -> DependencyGraphTool.ModuleResolution.absent();
    }

    /** The rendered edges of a walk's answer. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> renderedEdgesOf(BmReferencesHelper.BfsResult walk)
    {
        Map<String, Object> out = DependencyGraphBuilder.render(walk,
            DependencyGraphBuilder.Format.JSON);
        return (List<Map<String, Object>>)out.get("edges"); //$NON-NLS-1$
    }

    /** A module the walk can hold, answering nothing but its own class. */
    private static Module module()
    {
        org.eclipse.emf.ecore.EClass eClass = (org.eclipse.emf.ecore.EClass)Proxy.newProxyInstance(
            AModuleEdgeSaysHowItWasResolvedTest.class.getClassLoader(),
            new Class<?>[]{org.eclipse.emf.ecore.EClass.class},
            (proxy, method, args) -> "getName".equals(method.getName()) ? "Module" : null); //$NON-NLS-1$
        return (Module)Proxy.newProxyInstance(
            AModuleEdgeSaysHowItWasResolvedTest.class.getClassLoader(),
            new Class<?>[]{Module.class, IBmObject.class, EObject.class},
            (proxy, method, args) -> {
                if ("eClass".equals(method.getName())) //$NON-NLS-1$
                {
                    return eClass;
                }
                Class<?> type = method.getReturnType();
                if (!type.isPrimitive() || type == void.class)
                {
                    return null;
                }
                if (type == boolean.class)
                {
                    return Boolean.FALSE;
                }
                if (type == long.class)
                {
                    return Long.valueOf(0);
                }
                return Integer.valueOf(0);
            });
    }
}
