/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
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
import ru.aiedt.mcp.server.support.WatchForCancel;

/**
 * The module level draws its rows from the call source the walk was given, in both directions.
 * <p>
 * The walk takes a {@code ModuleCallSource}. These tests hand it a named one - a caller list, a
 * callee list, and a lookup that could not run - and read what lands in the graph in each direction;
 * a level that draws its rows from anywhere else answers with nodes and no edges for a module that
 * is called.
 * </p>
 * <p>
 * A headless test proves the wiring, not the live answer: the production source reads a real BSL
 * reference index, and only a project answers whether that index now fills the level.
 * </p>
 */
public class TheModuleEdgesComeFromTheSourceTheWalkWasGivenTest
{
    private static final String ROOT = "CommonModule.Root.Module"; //$NON-NLS-1$
    private static final String CALLER = "CommonModule.Caller.Module"; //$NON-NLS-1$
    private static final String CALLEE = "CommonModule.Callee.Module"; //$NON-NLS-1$
    private static final String BEYOND = "CommonModule.Beyond.Module"; //$NON-NLS-1$
    private static final String ELSEWHERE = "CommonModule.Elsewhere.Module"; //$NON-NLS-1$

    /** A module the source names as a caller is a row in, from that caller to the root. */
    @Test
    public void theCallersTheSourceNamesAreRowsFromTheCallerToTheRoot()
    {
        Module root = module();
        Map<Module, List<String>> callers = new IdentityHashMap<>();
        callers.put(root, List.of(CALLER));

        BmReferencesHelper.BfsResult walk = walk(root, Map.of(CALLER, module()),
            BmReferencesHelper.Direction.IN, 1, calling(callers, Map.of()));

        assertEquals(List.of(ROOT, CALLER), new ArrayList<>(walk.nodes.keySet()));
        assertEquals(1, walk.edges.size());
        assertEquals(CALLER, walk.edges.get(0).fromFqn);
        assertEquals(ROOT, walk.edges.get(0).toFqn);
        assertEquals("calls", walk.edges.get(0).featureName); //$NON-NLS-1$
        assertFalse(walk.truncated);
    }

    /** A module the source names as a callee is a row out, from the root to that callee. */
    @Test
    public void theCalleesTheSourceNamesAreRowsFromTheRootToTheCallee()
    {
        Module root = module();
        Map<Module, List<String>> callers = new IdentityHashMap<>();
        callers.put(root, List.of(ELSEWHERE)); // named, and named in the direction not asked for
        Map<Module, List<String>> callees = new IdentityHashMap<>();
        callees.put(root, List.of(CALLEE));

        BmReferencesHelper.BfsResult walk = walk(root, Map.of(CALLEE, module()),
            BmReferencesHelper.Direction.OUT, 1, calling(callers, callees));

        assertEquals(List.of(ROOT, CALLEE), new ArrayList<>(walk.nodes.keySet()));
        assertEquals(1, walk.edges.size());
        assertEquals(ROOT, walk.edges.get(0).fromFqn);
        assertEquals(CALLEE, walk.edges.get(0).toFqn);
        assertFalse("a caller is not a row when only the callees were asked for", //$NON-NLS-1$
            walk.nodes.containsKey(ELSEWHERE));
    }

    /**
     * One connection between two modules is one row when both halves report it. Walking each end
     * names the same pair, and the two sightings are one reference.
     */
    @Test
    public void oneConnectionIsOneRowWhicheverHalfReportedIt()
    {
        Module root = module();
        Module other = module();
        Map<Module, List<String>> callers = new IdentityHashMap<>();
        callers.put(root, List.of(CALLEE));
        callers.put(other, List.of(ROOT));
        Map<Module, List<String>> callees = new IdentityHashMap<>();
        callees.put(root, List.of(CALLEE));
        callees.put(other, List.of(ROOT));

        BmReferencesHelper.BfsResult walk = walk(root, Map.of(CALLEE, other),
            BmReferencesHelper.Direction.BOTH, 1, calling(callers, callees));

        assertEquals(2, walk.edges.size());
        for (BmReferencesHelper.Edge edge : walk.edges)
        {
            assertEquals("the two halves are one reference, not two", 1, edge.count); //$NON-NLS-1$
        }
    }

    /** A module reached at one ring is walked at the next, so its own callers land as well. */
    @Test
    public void aModuleReachedAtTheFirstRingIsWalkedAtTheSecond()
    {
        Module root = module();
        Module caller = module();
        Map<Module, List<String>> callers = new IdentityHashMap<>();
        callers.put(root, List.of(CALLER));
        callers.put(caller, List.of(BEYOND));

        BmReferencesHelper.BfsResult walk = walk(root, Map.of(CALLER, caller, BEYOND, module()),
            BmReferencesHelper.Direction.IN, 2, calling(callers, Map.of()));

        assertEquals(2, walk.edges.size());
        assertEquals(List.of(ROOT, CALLER, BEYOND), new ArrayList<>(walk.nodes.keySet()));
    }

    /**
     * A caller lookup that could not run leaves no incoming row and does not pass for a module
     * nothing calls; the node itself stays in the graph.
     */
    @Test
    public void aLookupThatCouldNotRunLeavesNoIncomingRow()
    {
        // The root is not in the map at all, so the source answers null for it.
        BmReferencesHelper.BfsResult walk = walk(module(), Map.of(), BmReferencesHelper.Direction.IN,
            1, calling(new IdentityHashMap<>(), Map.of()));

        assertEquals(List.of(ROOT), new ArrayList<>(walk.nodes.keySet()));
        assertTrue("a lookup that could not run is not an answer about callers", walk.edges.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Walks the module level over a named call source, from the root under the root FQN.
     *
     * @param root the module walked first, the one the source is asked about first
     * @param modules the modules the lookup answers by FQN
     * @param direction the direction to walk in
     * @param depth how many rings to expand past the root
     * @param source the call source to walk
     * @return the walk
     */
    private static BmReferencesHelper.BfsResult walk(Module root, Map<String, Module> modules,
        BmReferencesHelper.Direction direction, int depth, BslCallGraphHelper.ModuleCallSource source)
    {
        LinkedHashMap<String, Module> roots = new LinkedHashMap<>();
        roots.put(ROOT, root);

        DependencyGraphTool.ModuleLookup lookup = fqn -> {
            Module module = modules.get(fqn);
            return module == null ? DependencyGraphTool.ModuleResolution.absent()
                : DependencyGraphTool.ModuleResolution.loaded(module);
        };

        return DependencyGraphTool.buildModuleGraph(roots, lookup, new ArrayList<>(), direction,
            depth, 10, 100, null, WatchForCancel.begin(), source);
    }

    /**
     * A call source over the given lists. A module missing from {@code callers} answers null, which
     * is how the source says its lookup could not run.
     *
     * @param callers module to the FQNs that call it
     * @param callees module to the FQNs it calls
     * @return the source
     */
    private static BslCallGraphHelper.ModuleCallSource calling(Map<Module, List<String>> callers,
        Map<Module, List<String>> callees)
    {
        return new BslCallGraphHelper.ModuleCallSource()
        {
            @Override
            public List<String> callersOf(Module module)
            {
                return callers.get(module);
            }

            @Override
            public List<String> calleesOf(Module module)
            {
                List<String> named = callees.get(module);
                return named == null ? List.of() : named;
            }
        };
    }

    /**
     * A module on the model objects the walk reads, and nothing else: the walk asks it for nothing,
     * which is what makes the source the source.
     *
     * @return the module
     */
    private static Module module()
    {
        return (Module)Proxy.newProxyInstance(
            TheModuleEdgesComeFromTheSourceTheWalkWasGivenTest.class.getClassLoader(),
            new Class<?>[] {Module.class, IBmObject.class, EObject.class},
            (proxy, method, args) -> {
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
