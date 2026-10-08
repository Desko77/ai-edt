/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * Helper for building BSL call graphs (caller/callee adjacency) for the
 * {@code dependency_graph level=modules} mode and similar tools.
 * <p>
 * Learned the hard way: the existing
 * {@code CallHierarchyReader} encapsulates Xtext {@code IReferenceFinder}
 * integration but only as part of a tool, not as a reusable helper. This class
 * exposes the reusable pieces. Reflection-free; depends only on Xtext + EDT
 * BSL packages already in MANIFEST Import-Package.
 * <p>
 * <b>Granularity:</b> module-level edges (CommonModule.A -> CommonModule.B).
 * Method-level callgraph remains in {@code CallHierarchyReader}.
 */
@SuppressWarnings("restriction")
public final class BslCallGraphHelper
{
    private BslCallGraphHelper()
    {
        // utility class
    }

    /**
     * Module-level edge {@code from --calls--> to}.
     */
    public static final class ModuleEdge
    {
        public final String fromFqn;
        public final String toFqn;
        public final int callCount;

        public ModuleEdge(String fromFqn, String toFqn, int callCount)
        {
            this.fromFqn = fromFqn;
            this.toFqn = toFqn;
            this.callCount = callCount;
        }
    }

    /**
     * Returns the set of modules that call exported methods of {@code targetModule}.
     * <p>
     * Returns {@code null} (NOT an empty list) when the lookup could not run - the Xtext resource
     * provider or {@link IReferenceFinder} is unavailable, or {@code findAllReferences} throws.
     * An empty list is reserved for a lookup that ran and found no calling module: a broken or
     * unbuilt index must not read as "nothing calls this module", the same distinction
     * {@link #countCallers(Method)} draws with {@code -1}.
     * </p>
     *
     * @param project the project the module belongs to
     * @param bmModel the BM model the module lives in
     * @param targetModule the module whose callers to list
     * @return the calling modules' FQNs, an empty list when none, or {@code null} when the lookup
     *         could not run
     */
    public static List<String> callersOfModule(IProject project, IBmModel bmModel,
        Module targetModule)
    {
        if (project == null || targetModule == null)
        {
            return Collections.emptyList();
        }
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
        if (rsp == null)
        {
            return null;
        }
        IReferenceFinder finder = rsp.get(IReferenceFinder.class);
        if (finder == null)
        {
            return null;
        }
        return callersOfModule(finder, targetModule);
    }

    /**
     * The walk behind {@link #callersOfModule(IProject, IBmModel, Module)} over a finder the
     * caller resolved: the modules whose code references an exported method of the target, with
     * the target's own module filtered out.
     * <p>
     * Returns {@code null} when {@code findAllReferences} throws, so a failed lookup stays
     * distinguishable from a module nothing calls. A target with no exported method cannot be
     * called from outside, which answers as an empty list.
     * </p>
     *
     * @param finder the reference finder to walk with
     * @param targetModule the module whose callers to list
     * @return the calling modules' FQNs, an empty list when none, or {@code null} when the lookup
     *         could not run
     */
    static List<String> callersOfModule(IReferenceFinder finder, Module targetModule)
    {
        // Collect URIs of all exported methods inside the target module.
        List<URI> targets = new ArrayList<>();
        for (Method method : targetModule.allMethods())
        {
            if (method.isExport())
            {
                targets.add(EcoreUtil.getURI(method));
            }
        }
        if (targets.isEmpty())
        {
            return Collections.emptyList();
        }
        Set<String> callerModules = new LinkedHashSet<>();
        try
        {
            finder.findAllReferences(targets, null, ref -> {
                URI src = ref.getSourceEObjectUri();
                if (src == null)
                {
                    return;
                }
                String moduleFqn = extractModuleFqnFromUri(src);
                if (moduleFqn != null)
                {
                    callerModules.add(moduleFqn);
                }
            }, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logWarning("BslCallGraphHelper.callersOfModule failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        // Filter self-references. The filter reads the module's own address, not the object model:
        // a module the object model does not name leaves the filter empty and its own calls are
        // then reported as coming from a caller module.
        String selfFqn = moduleFqn(targetModule);
        if (selfFqn != null)
        {
            callerModules.remove(selfFqn);
        }
        return new ArrayList<>(callerModules);
    }

    /**
     * Counts how many BSL call sites reference the given method, workspace-wide. The method's own
     * declaration is not a reference, so a non-recursive method that nothing calls yields zero (the
     * dead-code signal); a self-recursive method counts its own call as a reference (treated as alive).
     * <p>
     * Returns {@code -1} (NOT zero) when the lookup is indeterminate - the Xtext resource provider or
     * {@link IReferenceFinder} is unavailable, or {@code findAllReferences} throws. A caller that treats
     * zero as "dead" must treat a negative result as "unknown, skip"; otherwise a broken/unbuilt index
     * flags every non-allowlisted export as dead (systematic false positives).
     *
     * @param method the method whose callers to count
     * @return the reference count (>=0), or {@code -1} when the lookup could not run
     */
    public static int countCallers(Method method)
    {
        if (method == null)
        {
            return -1;
        }
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
        if (rsp == null)
        {
            return -1;
        }
        IReferenceFinder finder = rsp.get(IReferenceFinder.class);
        if (finder == null)
        {
            return -1;
        }
        URI target = EcoreUtil.getURI(method);
        int[] count = {0};
        try
        {
            finder.findAllReferences(Collections.singletonList(target), null, ref -> {
                count[0]++;
            }, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logWarning("BslCallGraphHelper.countCallers failed: " + e.getMessage()); //$NON-NLS-1$
            return -1;
        }
        return count[0];
    }

    /**
     * Returns the set of modules called by {@code sourceModule}. Walks the BSL AST of
     * {@code sourceModule} and collects the module of every call target it holds.
     * <p>
     * Two links carry a call and both are read: {@code Method.getCallees}, which holds the called
     * methods, and {@code FeatureEntry.getFeature} of a call site, which holds the same methods
     * resolved - a call to a common module resolves to that module. Both are written by the
     * module's type state, so a module whose state was never computed answers an empty list here
     * although it calls other modules; the production source asks for that state first and reports
     * its absence as {@code null}, which leaves the module out of the graph instead of showing it
     * as one that calls nobody.
     * </p>
     */
    public static List<String> calleesOfModule(Module sourceModule)
    {
        if (sourceModule == null)
        {
            return Collections.emptyList();
        }
        Set<String> calleeModules = new LinkedHashSet<>();
        String selfFqn = moduleFqn(sourceModule);
        try
        {
            EObject root = sourceModule;
            java.util.Iterator<EObject> it = root.eAllContents();
            while (it.hasNext())
            {
                EObject node = it.next();
                Collection<EObject> referenced = referencedExternalEObjects(node);
                for (EObject ref : referenced)
                {
                    Module containing = enclosingModule(ref);
                    if (containing == null || containing == sourceModule)
                    {
                        continue;
                    }
                    String fqn = moduleFqn(containing);
                    if (fqn != null && !fqn.equals(selfFqn))
                    {
                        calleeModules.add(fqn);
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("BslCallGraphHelper.calleesOfModule failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return new ArrayList<>(calleeModules);
    }

    /**
     * Walks one BSL AST node's outgoing references. Filters containment + self-loops.
     * <p>
     * A method is read through {@code getCallees} alone. Its other call link, {@code getCallers},
     * points the other way - it holds the blocks that call the method, and those blocks live in the
     * calling modules - so following it here recorded a call in the wrong direction, from the
     * module being walked to the module that calls it.
     * <p>
     * A call site is read through the feature entry it carries. The entry's own reference to the
     * target is transient, so the reference loop below skips it, and the access holding the entry
     * reaches the entry by containment - which the loop skips as well. Without this branch a call
     * between modules is invisible to the walk, and a module calling several of them reads as one
     * that calls nobody.
     * </p>
     */
    private static Collection<EObject> referencedExternalEObjects(EObject node)
    {
        if (node == null)
        {
            return Collections.emptyList();
        }
        if (node instanceof FeatureEntry)
        {
            List<EObject> resolved = new ArrayList<>(1);
            try
            {
                EObject feature = ((FeatureEntry)node).getFeature();
                if (feature != null)
                {
                    resolved.add(feature);
                }
            }
            catch (Exception notResolved)
            {
                return Collections.emptyList();
            }
            return resolved;
        }
        if (node instanceof Method)
        {
            List<EObject> called = new ArrayList<>();
            try
            {
                called.addAll(((Method)node).getCallees());
            }
            catch (Exception notLinked)
            {
                return Collections.emptyList();
            }
            return called;
        }
        List<EObject> out = new ArrayList<>(2);
        for (var eref : node.eClass().getEAllReferences())
        {
            if (eref.isContainment() || eref.isContainer() || eref.isTransient())
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
                    if (item instanceof EObject)
                    {
                        out.add((EObject) item);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                out.add((EObject) value);
            }
        }
        return out;
    }

    private static Module enclosingModule(EObject obj)
    {
        EObject current = obj;
        Set<EObject> seen = new HashSet<>();
        while (current != null && seen.add(current))
        {
            if (current instanceof Module)
            {
                return (Module) current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * The FQN of a module, read off the address it was loaded from: the {@code src/} path of its
     * resource, converted the same way the module level converts the address of a node.
     * <p>
     * This is the route every module of the level answers to, because the level loads its modules
     * by path - {@code BslModuleAccess.loadModule} opens
     * {@code platform:/resource/<project>/src/<modulePath>} - so a module the walk holds always
     * carries the address the level named it by.
     * </p>
     * <p>
     * The BM top-object route ({@link #moduleFqnOf(Module)}) is the fallback, for a module whose
     * resource is addressed some other way.
     * </p>
     *
     * @param module the module
     * @return its FQN, or {@code null} when neither the address nor the object model names it
     */
    public static String moduleFqn(Module module)
    {
        if (module == null)
        {
            return null;
        }
        String byAddress = moduleFqnFromAddress(module);
        return byAddress != null ? byAddress : moduleFqnOf(module);
    }

    /**
     * The FQN a module's own resource address names, or {@code null} when that address names none.
     *
     * @param module the module
     * @return its FQN as the {@code src/} path spells it
     */
    static String moduleFqnFromAddress(Module module)
    {
        try
        {
            org.eclipse.emf.ecore.resource.Resource resource = module.eResource();
            return resource == null ? null : extractModuleFqnFromUri(resource.getURI());
        }
        catch (Exception noResource)
        {
            return null;
        }
    }

    /**
     * Returns the BM FQN of the BM top-object enclosing the given module.
     * Example: {@code CommonModule.SalesUtils.Module} for an exported method.
     * <p>
     * Answers {@code null} for a module the object model does not hold as a top object, which is
     * every module loaded by path - see {@link #moduleFqn(Module)}.
     * </p>
     */
    public static String moduleFqnOf(Module module)
    {
        if (module == null)
        {
            return null;
        }
        IBmObject top = BmReferencesHelper.findTopContainer((IBmObject) module);
        if (top == null)
        {
            return null;
        }
        try
        {
            return top.bmGetFqn();
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * Heuristic: take a URI like
     * {@code platform:/resource/Project/src/CommonModules/Foo/Module.bsl} and
     * map it to a BM-canonical FQN like {@code CommonModule.Foo.Module}.
     */
    static String extractModuleFqnFromUri(URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        String path = uri.path();
        if (path == null)
        {
            return null;
        }
        int srcIdx = path.indexOf("/src/"); //$NON-NLS-1$
        if (srcIdx < 0)
        {
            return null;
        }
        String tail = path.substring(srcIdx + 5);
        if (tail.endsWith(".bsl")) //$NON-NLS-1$
        {
            tail = tail.substring(0, tail.length() - 4);
        }
        // Convert "/" separators back to ".".
        String[] parts = tail.split("/"); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return tail.replace("/", "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Singularize the type prefix the same way EDT does:
        // CommonModules/Foo/Module.bsl -> CommonModule.Foo.Module
        String typePart = singularizeTypePrefix(parts[0]);
        StringBuilder sb = new StringBuilder(typePart);
        for (int i = 1; i < parts.length; i++)
        {
            sb.append(".").append(parts[i]); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String singularizeTypePrefix(String typePart)
    {
        if (typePart == null || typePart.isEmpty())
        {
            return typePart;
        }
        // Common EDT directory plurals -> singular metadata type names.
        switch (typePart)
        {
            case "CommonModules": return "CommonModule"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Catalogs": return "Catalog"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Documents": return "Document"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Reports": return "Report"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DataProcessors": return "DataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$
            case "InformationRegisters": return "InformationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "AccumulationRegisters": return "AccumulationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "AccountingRegisters": return "AccountingRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CalculationRegisters": return "CalculationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfAccounts": return "ChartOfAccounts"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfCalculationTypes": return "ChartOfCalculationTypes"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfCharacteristicTypes": return "ChartOfCharacteristicTypes"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExchangePlans": return "ExchangePlan"; //$NON-NLS-1$ //$NON-NLS-2$
            case "BusinessProcesses": return "BusinessProcess"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Tasks": return "Task"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Subsystems": return "Subsystem"; //$NON-NLS-1$ //$NON-NLS-2$
            default:
                if (typePart.endsWith("s")) //$NON-NLS-1$
                {
                    return typePart.substring(0, typePart.length() - 1);
                }
                return typePart;
        }
    }

    /**
     * Visitor for module pairs. Used by {@code dependency_graph level=modules}
     * to emit edges as they are discovered.
     */
    @FunctionalInterface
    public interface ModuleEdgeVisitor
    {
        void visit(ModuleEdge edge);
    }

    /**
     * Where the two halves of the module call graph come from.
     * <p>
     * The single source the module level walks: incoming edges from the reference index the
     * {@code call_hierarchy} tool reads, outgoing edges from the module's own resolved call links.
     * A level reading them anywhere else answers about calls differently from that tool.
     * </p>
     */
    public interface ModuleCallSource
    {
        /**
         * @param module the module whose callers to list
         * @return the calling modules' FQNs, an empty list when none, or {@code null} when the
         *         lookup could not run
         */
        List<String> callersOf(Module module);

        /**
         * @param module the module whose callees to list
         * @return the called modules' FQNs, empty when the module calls nothing outside itself, or
         *         {@code null} when the module's call targets could not be read
         */
        List<String> calleesOf(Module module);

        /**
         * The source of a live project: the BSL reference index and the modules' resolved links.
         *
         * @param project the project the modules belong to
         * @param bmModel the project's object model
         * @return the source
         */
        static ModuleCallSource bslModel(IProject project, IBmModel bmModel)
        {
            return new BslModelCallSource(project, bmModel);
        }
    }

    /** The production source, over the project's BSL reference index and resolved call links. */
    private static final class BslModelCallSource implements ModuleCallSource
    {
        private final IProject project;
        private final IBmModel bmModel;

        BslModelCallSource(IProject project, IBmModel bmModel)
        {
            this.project = project;
            this.bmModel = bmModel;
        }

        @Override
        public List<String> callersOf(Module module)
        {
            return callersOfModule(project, bmModel, module);
        }

        @Override
        public List<String> calleesOf(Module module)
        {
            if (module == null)
            {
                return Collections.emptyList();
            }
            // A module's call targets are written by its type state: the resolved feature of a call
            // site and the method's own callee list are both filled while that state is computed.
            // Read without it, every call site answers no target, and a module that calls others is
            // reported as one that calls nobody - so the state is asked for first, and a module
            // whose state is not ready is left out of the graph rather than answered about.
            BslModuleAccess.TypeState state = BslModuleAccess.resolveCrossReferences(module.eResource());
            if (state != BslModuleAccess.TypeState.READY)
            {
                Activator.logWarning("BslCallGraphHelper: the call targets of a module are not read, " //$NON-NLS-1$
                    + "its type state is " + state //$NON-NLS-1$
                    + ", so its outgoing call edges are left out"); //$NON-NLS-1$
                return null;
            }
            return calleesOfModule(module);
        }
    }

    /**
     * Emits the call edges of one module in the directions asked for.
     * <p>
     * The module is named by {@code selfFqn} - the address the caller already records it under -
     * rather than asked for its own FQN: the walk holds the address the node was named by, and the
     * two must agree for an edge to land on that node. A module the caller cannot name is logged
     * and left out, so an unaddressed module shows in the log instead of passing for a module that
     * calls nothing.
     * </p>
     * <p>
     * Incoming edges are skipped when the caller lookup could not run: a failed lookup carries no
     * statement about who calls this module, so it emits nothing rather than an empty answer
     * dressed as a graph. A failed callee lookup - a module whose call targets could not be read -
     * is skipped the same way and for the same reason.
     * </p>
     *
     * @param module the module to walk
     * @param selfFqn the FQN the module is recorded under, or {@code null} when it has none
     * @param source where the two halves of the call graph come from
     * @param includeIncoming whether to emit the calls into the module
     * @param includeOutgoing whether to emit the calls out of the module
     * @param visitor the visitor of every emitted edge
     */
    public static void emitEdgesForModule(Module module, String selfFqn, ModuleCallSource source,
        boolean includeIncoming, boolean includeOutgoing, ModuleEdgeVisitor visitor)
    {
        if (visitor == null || module == null || source == null)
        {
            return;
        }
        if (selfFqn == null)
        {
            Activator.logWarning("BslCallGraphHelper.emitEdgesForModule: the module has no address " //$NON-NLS-1$
                + "to be recorded under, its call edges are left out"); //$NON-NLS-1$
            return;
        }
        if (includeIncoming)
        {
            List<String> callers = source.callersOf(module);
            if (callers != null)
            {
                for (String caller : callers)
                {
                    visitor.visit(new ModuleEdge(caller, selfFqn, 1));
                }
            }
        }
        if (includeOutgoing)
        {
            List<String> callees = source.calleesOf(module);
            if (callees != null)
            {
                for (String callee : callees)
                {
                    visitor.visit(new ModuleEdge(selfFqn, callee, 1));
                }
            }
        }
    }
}
