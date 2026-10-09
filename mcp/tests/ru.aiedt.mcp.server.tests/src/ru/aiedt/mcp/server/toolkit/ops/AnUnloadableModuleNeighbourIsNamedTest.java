/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.support.BmReferencesHelper;

/**
 * A module an edge reaches whose file is there and whose model did not load is named in the answer.
 * <p>
 * The walk recorded such a neighbour as a node with no object behind it and queued nothing, and the
 * unloaded addresses were collected while the roots were resolved alone: a successful answer said
 * nothing about the walk having stopped at this module, which read as a module with no calls.
 * </p>
 */
public class AnUnloadableModuleNeighbourIsNamedTest
{
    /** An edge to an unloadable module is a node without an object, named as unloaded. */
    @Test
    public void aNeighbourWhoseModelDidNotLoadIsNamed()
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        Deque<DependencyGraphTool.QueuedModule> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String> unloaded = new ArrayList<>();

        DependencyGraphTool.addModuleNodeIfNew(result, queue, visited,
            fqn -> DependencyGraphTool.ModuleResolution.unloaded(), "CommonModule.Gone.Module", //$NON-NLS-1$
            200, unloaded);

        assertEquals(1, result.nodes.size());
        assertNull("the node stands for a module the walk could not read", //$NON-NLS-1$
            result.nodes.get("CommonModule.Gone.Module")); //$NON-NLS-1$
        assertTrue("a module that did not load is not walked", queue.isEmpty()); //$NON-NLS-1$
        assertEquals(List.of("CommonModule.Gone.Module"), unloaded); //$NON-NLS-1$
    }

    /** An address the level already named is not counted a second time. */
    @Test
    public void anAddressAlreadyNamedIsNotCountedTwice()
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        List<String> unloaded = new ArrayList<>(List.of("CommonModule.Gone.Module")); //$NON-NLS-1$

        DependencyGraphTool.addModuleNodeIfNew(result, new ArrayDeque<>(), new LinkedHashSet<>(),
            fqn -> DependencyGraphTool.ModuleResolution.unloaded(), "CommonModule.Gone.Module", //$NON-NLS-1$
            200, unloaded);

        assertEquals("one address is one module", 1, unloaded.size()); //$NON-NLS-1$
    }

    /** A module that loaded is walked and is not named as unloaded. */
    @Test
    public void aModuleThatLoadedIsWalked()
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        Deque<DependencyGraphTool.QueuedModule> queue = new ArrayDeque<>();
        List<String> unloaded = new ArrayList<>();
        Module module = (Module)java.lang.reflect.Proxy.newProxyInstance(
            AnUnloadableModuleNeighbourIsNamedTest.class.getClassLoader(),
            new Class<?>[] {Module.class, com._1c.g5.v8.bm.core.IBmObject.class},
            (proxy, method, args) -> method.getReturnType().isPrimitive()
                ? (method.getReturnType() == boolean.class ? Boolean.FALSE : Integer.valueOf(0))
                : null);

        DependencyGraphTool.addModuleNodeIfNew(result, queue, new LinkedHashSet<>(),
            fqn -> DependencyGraphTool.ModuleResolution.loaded(module), "CommonModule.Sales.Module", //$NON-NLS-1$
            200, unloaded);

        assertEquals(1, queue.size());
        assertTrue("a module that loaded is not reported unloaded", unloaded.isEmpty()); //$NON-NLS-1$
    }
}
