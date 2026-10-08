/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * The module level resolves its modules through the module loader, and a module whose file is
 * there but whose model would not load is named rather than dropped.
 * <p>
 * The roots of the level were resolved through the BM top-object index, which answered no module
 * FQN on a live stand: the level came back empty and was reported as success. The loader - the
 * route {@code call_hierarchy} and the module tools take - is the resolution here, and an
 * unloadable module is a fact the answer carries.
 * </p>
 */
public class TheModuleLevelResolvesThroughTheModuleLoaderTest
{
    /**
     * A stand-in for one end of the resolution: what it is called is all the resolution asks of
     * it. A metadata root answers its FQN; a module root is the module itself.
     */
    private static IBmObject of(Class<?> type, String fqn)
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
            TheModuleLevelResolvesThroughTheModuleLoaderTest.class.getClassLoader(),
            types.toArray(new Class<?>[0]), (proxy, method, args) -> {
                if ("bmGetFqn".equals(method.getName())) //$NON-NLS-1$
                {
                    return fqn;
                }
                switch (method.getName())
                {
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                default:
                    return method.getReturnType().isPrimitive()
                        ? (method.getReturnType() == boolean.class
                            ? Boolean.FALSE
                            : Integer.valueOf(0))
                        : null;
                }
            });
    }

    /** A lookup that answers what the test hands it, absent for everything else. */
    private static DependencyGraphTool.ModuleLookup lookup(Map<String, DependencyGraphTool.ModuleResolution> answers)
    {
        return fqn -> answers.getOrDefault(fqn, DependencyGraphTool.ModuleResolution.absent());
    }

    /** An owner's modules are the modules its addresses resolve to, keyed by that address. */
    @Test
    public void anOwnersModulesAreResolvedThroughTheLookup()
    {
        IBmObject owner = of(MdObject.class, "Catalog.Goods"); //$NON-NLS-1$
        Module objectModule = (Module)of(Module.class, "Catalog.Goods.ObjectModule"); //$NON-NLS-1$
        Module managerModule = (Module)of(Module.class, "Catalog.Goods.ManagerModule"); //$NON-NLS-1$

        LinkedHashMap<String, Module> modules = DependencyGraphTool.asModules(List.of(owner),
            lookup(Map.of(
                "Catalog.Goods.ObjectModule", DependencyGraphTool.ModuleResolution.loaded(objectModule), //$NON-NLS-1$
                "Catalog.Goods.ManagerModule", DependencyGraphTool.ModuleResolution.loaded(managerModule))), //$NON-NLS-1$
            new ArrayList<>());

        assertEquals(2, modules.size());
        assertEquals(objectModule, modules.get("Catalog.Goods.ObjectModule")); //$NON-NLS-1$
        assertEquals(managerModule, modules.get("Catalog.Goods.ManagerModule")); //$NON-NLS-1$
    }

    /** A module that is its own root is kept under its own FQN, once. */
    @Test
    public void aModuleRootIsKeptUnderItsOwnFqn()
    {
        Module module = (Module)of(Module.class, "CommonModule.Sales.Module"); //$NON-NLS-1$

        LinkedHashMap<String, Module> modules = DependencyGraphTool.asModules(List.of((IBmObject)module),
            lookup(Map.of()), new ArrayList<>());

        assertEquals(Map.of("CommonModule.Sales.Module", module), modules); //$NON-NLS-1$
    }

    /**
     * An address whose file is there and whose model is not says so in {@code unloaded}, so a
     * level with nothing else to show refuses instead of answering an empty graph.
     */
    @Test
    public void anUnloadableModuleIsNamedNotDropped()
    {
        IBmObject owner = of(MdObject.class, "Catalog.Goods"); //$NON-NLS-1$
        List<String> unloaded = new ArrayList<>();

        LinkedHashMap<String, Module> modules = DependencyGraphTool.asModules(List.of(owner),
            lookup(Map.of("Catalog.Goods.ObjectModule", //$NON-NLS-1$
                DependencyGraphTool.ModuleResolution.unloaded())),
            unloaded);

        assertTrue("an unloadable module is no node", modules.isEmpty()); //$NON-NLS-1$
        assertEquals(List.of("Catalog.Goods.ObjectModule"), unloaded); //$NON-NLS-1$
    }

    /** A level none of whose modules would load is refused, naming the addresses. */
    @Test
    public void aLevelNothingLoadsFromIsRefusedByName()
    {
        String refusal = DependencyGraphTool.moduleLevelNotBuilt(
            List.of("CommonModule.Sales.Module", "CommonModule.Stock.Module")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(refusal, refusal.startsWith("The module level could not be built")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("2 module(s)")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("CommonModule.Sales.Module")); //$NON-NLS-1$
    }

    /** A walk that lost some modules carries the count and the first names, not all of them. */
    @Test
    public void aPartialWalkCarriesTheCountAndTheFirstNames()
    {
        assertTrue(DependencyGraphTool.moduleLevelFields(List.of()).isEmpty());

        Map<String, Object> fields = DependencyGraphTool.moduleLevelFields(java.util.Arrays.asList(
            "A", "B", "C", "D", "E", "F", "G")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

        assertEquals(Integer.valueOf(7), fields.get("modulesUnloaded")); //$NON-NLS-1$
        assertEquals("five names, not seven", 5, ((List<?>)fields.get("modulesUnloadedNames")).size()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
