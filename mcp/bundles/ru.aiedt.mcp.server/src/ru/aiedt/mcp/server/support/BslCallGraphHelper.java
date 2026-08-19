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
     * Best-effort: when the Xtext reference index is not reachable, returns an
     * empty list (caller decides whether to fall back).
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
            return Collections.emptyList();
        }
        IReferenceFinder finder = rsp.get(IReferenceFinder.class);
        if (finder == null)
        {
            return Collections.emptyList();
        }
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
        }
        // Filter self-references.
        String selfFqn = moduleFqnOf(targetModule);
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
     * Returns the set of modules called by {@code sourceModule}. Walks the
     * BSL AST of {@code sourceModule} and collects external method invocation
     * targets.
     * <p>
     * Implementation: for each {@code Invocation} / {@code FeatureAccess} in
     * the module AST, resolve the referred EObject; if it resides in a different
     * Module, record the edge. Best-effort.
     */
    public static List<String> calleesOfModule(Module sourceModule)
    {
        if (sourceModule == null)
        {
            return Collections.emptyList();
        }
        Set<String> calleeModules = new LinkedHashSet<>();
        String selfFqn = moduleFqnOf(sourceModule);
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
                    String fqn = moduleFqnOf(containing);
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
     */
    private static Collection<EObject> referencedExternalEObjects(EObject node)
    {
        if (node == null)
        {
            return Collections.emptyList();
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
     * Returns the BM FQN of the BM top-object enclosing the given module.
     * Example: {@code CommonModule.SalesUtils.Module} for an exported method.
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
     * Convenience: emit edges for a single module in both directions.
     */
    public static void emitEdgesForModule(IProject project, IBmModel bmModel, Module module,
        boolean includeIncoming, boolean includeOutgoing, ModuleEdgeVisitor visitor)
    {
        if (visitor == null || module == null)
        {
            return;
        }
        String selfFqn = moduleFqnOf(module);
        if (selfFqn == null)
        {
            return;
        }
        if (includeIncoming)
        {
            for (String caller : callersOfModule(project, bmModel, module))
            {
                visitor.visit(new ModuleEdge(caller, selfFqn, 1));
            }
        }
        if (includeOutgoing)
        {
            for (String callee : calleesOfModule(module))
            {
                visitor.visit(new ModuleEdge(selfFqn, callee, 1));
            }
        }
    }
}
