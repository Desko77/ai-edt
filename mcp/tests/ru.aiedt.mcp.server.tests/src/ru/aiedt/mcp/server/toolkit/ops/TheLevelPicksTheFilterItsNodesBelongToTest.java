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
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.BmReferencesHelper;

/**
 * The level decides which objects a graph is made of, so the filter of the walk is chosen from the
 * level and not by the test that happens to walk it.
 * <p>
 * A test that builds the filter itself answers the wrong question: it stays green whichever way the
 * selection is wired, and a graph asked for at one level would carry the objects of the other. This
 * one goes through the same selection {@code buildGraph} calls.
 * </p>
 */
public class TheLevelPicksTheFilterItsNodesBelongToTest
{
    /** A stand-in for one end of an edge: what the filter asks about is the type it answers to. */
    private static IBmObject of(Class<?> type)
    {
        List<Class<?>> types = new ArrayList<>();
        types.add(type);
        if (!IBmObject.class.isAssignableFrom(type))
        {
            types.add(IBmObject.class);
        }
        if (!EObject.class.isAssignableFrom(type))
        {
            types.add(EObject.class);
        }
        return (IBmObject)Proxy.newProxyInstance(
            TheLevelPicksTheFilterItsNodesBelongToTest.class.getClassLoader(),
            types.toArray(new Class<?>[0]), (proxy, method, args) -> {
                switch (method.getName())
                {
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "toString": //$NON-NLS-1$
                    return String.valueOf(type.getSimpleName());
                default:
                    return method.getReturnType().isPrimitive()
                        ? (method.getReturnType() == boolean.class
                            ? Boolean.FALSE
                            : Integer.valueOf(0))
                        : null;
                }
            });
    }

    /** The mixed level carries modules. The metadata level, the level of metadata objects, does not. */
    @Test
    public void theMixedLevelKeepsModulesAndTheMetadataLevelDoesNot()
    {
        IBmObject module = of(Module.class);
        IBmObject goods = of(MdObject.class);
        Set<String> keep = Set.of("calls"); //$NON-NLS-1$

        BmReferencesHelper.EdgePolicy mixed =
            DependencyGraphTool.edgePolicy(DependencyGraphTool.Level.MIXED, keep);
        assertEquals(BmReferencesHelper.Ends.MIXED, mixed.ends);
        assertTrue("a module is an object of the mixed graph", mixed.ends.accepts(module)); //$NON-NLS-1$
        assertTrue(mixed.ends.accepts(goods));
        assertEquals(keep, mixed.keepKinds);

        BmReferencesHelper.EdgePolicy metadata =
            DependencyGraphTool.edgePolicy(DependencyGraphTool.Level.METADATA, keep);
        assertEquals(BmReferencesHelper.Ends.METADATA, metadata.ends);
        assertFalse("a module is a stranger to the metadata graph", //$NON-NLS-1$
            metadata.ends.accepts(module));
        assertTrue(metadata.ends.accepts(goods));
        assertEquals(keep, metadata.keepKinds);
    }

    /** The level that is not mixed is the metadata one, and the argument passes through as given. */
    @Test
    public void everyLevelThatIsNotMixedIsTheMetadataOne()
    {
        assertEquals(BmReferencesHelper.Ends.METADATA,
            DependencyGraphTool.edgePolicy(DependencyGraphTool.Level.MODULES, null).ends);
        BmReferencesHelper.EdgePolicy all =
            DependencyGraphTool.edgePolicy(DependencyGraphTool.Level.MIXED, null);
        assertEquals("no argument keeps every kind", null, all.keepKinds); //$NON-NLS-1$
        assertEquals("and an empty one keeps none", Set.of(), //$NON-NLS-1$
            DependencyGraphTool.edgePolicy(DependencyGraphTool.Level.METADATA, Set.of()).keepKinds);
    }
}
