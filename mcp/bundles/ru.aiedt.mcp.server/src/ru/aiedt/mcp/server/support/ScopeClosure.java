/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Works out what a set of objects cannot be carried without.
 * <p>
 * Moving a subsystem into another configuration drags whatever it refers to. Discovering that
 * afterwards - as broken references in a configuration somebody already merged into - is the failure
 * this exists to prevent, so the closure is computed and shown BEFORE anything is written.
 * </p>
 * <p>
 * <b>The definition, stated rather than implied.</b>
 * </p>
 * <ul>
 * <li><b>Direction: outward only.</b> What the requested objects refer to, never what refers to
 * them. A catalogue used by a hundred documents does not drag those documents along; a catalogue
 * that refers to another one cannot arrive without it.</li>
 * <li><b>Transitive, to a stated depth.</b> A reference of a reference is still a dependency. The
 * depth is bounded and the bound is reported, because a closure that silently stopped would name
 * fewer objects than the move actually needs.</li>
 * <li><b>Cycles are visited once.</b> Two objects referring to each other appear once each; the
 * traversal keeps a visited set, so a cycle ends the branch instead of the walk.</li>
 * <li><b>Every reference the model holds counts</b> - attributes of reference and composite type,
 * defined types, event subscriptions, roles, forms, commands, filter criteria, exchange plan
 * content. They are not enumerated in code: the traversal follows what the model records, so a kind
 * nobody thought of is followed too.</li>
 * </ul>
 * <p>
 * <b>What a closure cannot contain, and this is the part worth reading.</b> Only what the model
 * records as a reference can be followed. A dependency written as a string - a metadata name built
 * in code, a name inside a query, a name in a template or a form's data path expression - is
 * invisible here, and the object it names will be missing after the move with nothing having warned
 * about it. That list travels with every answer instead of being left for somebody to rediscover.
 * </p>
 */
public final class ScopeClosure
{
    /** How deep the walk goes before it stops and says so. */
    public static final int MAX_DEPTH = 12;

    /** How many objects one closure may name. */
    public static final int MAX_NODES = 5000;

    /** How many references it may follow. */
    private static final int MAX_EDGES = 20000;

    /**
     * The dependencies that a reference walk is structurally unable to find.
     * <p>
     * Shipped with every closure. A caller who reads a list of additions and no list of blind spots
     * will take the first for the whole answer.
     * </p>
     *
     * @return what cannot be discovered by following references
     */
    public static List<String> whatReferencesCannotExpress()
    {
        List<String> blind = new ArrayList<>();
        blind.add("metadata named as a string in code - Metadata.Catalogs[name], " //$NON-NLS-1$
            + "Documents[name].CreateDocument and every other name assembled at run time"); //$NON-NLS-1$
        blind.add("tables and fields named inside a query, which is text to the model"); //$NON-NLS-1$
        blind.add("names inside templates, including spreadsheet parameters and text bodies"); //$NON-NLS-1$
        blind.add("anything a form reaches through a data path built at run time"); //$NON-NLS-1$
        blind.add("external files, web services and infobases the code talks to"); //$NON-NLS-1$
        return blind;
    }

    /** What a set of objects cannot travel without. */
    public static final class Closure
    {
        /** Why nothing could be worked out. Present only when the answer is a refusal. */
        public String cannotTell;

        /** The objects that were asked for and found. */
        public final List<String> requested = new ArrayList<>();

        /** Names asked for that the configuration does not have. */
        public final List<String> notFound = new ArrayList<>();

        /**
         * What the requested objects cannot be carried without, beyond themselves.
         * <p>
         * This is the number a person weighs: "move one subsystem" and "move one subsystem and the
         * ninety objects it turns out to need" are different decisions.
         * </p>
         */
        public final List<String> added = new ArrayList<>();

        /** True when the walk hit a bound and stopped short of the whole closure. */
        public boolean truncated;

        /** How many references were followed. */
        public int referencesFollowed;

        /**
         * Nodes the walk reached that are not metadata objects.
         * <p>
         * The model's own furniture - a module's context index, a type node - reached through the
         * same references. Counted rather than listed: they are not things a move carries, and
         * naming them would make the move read as larger than it is.
         * </p>
         */
        public int internalNodes;
    }

    private ScopeClosure()
    {
        // Static helper.
    }

    /**
     * Works out what the named objects cannot be carried without.
     *
     * @param projectName the project holding them.
     * @param names the objects asked for, named as the metadata names them.
     * @param depth how far to follow references; clamped to {@link #MAX_DEPTH}.
     * @return the closure, or one carrying a refusal
     */
    public static Closure of(String projectName, List<String> names, int depth)
    {
        Closure closure = new Closure();
        if (names == null || names.isEmpty())
        {
            closure.cannotTell = "no objects were named, so there is nothing to close over"; //$NON-NLS-1$
            return closure;
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            closure.cannotTell = ProjectResolver.describeNotFound(projectName);
            return closure;
        }
        IBmModelManager models = Activator.getDefault().getBmModelManager();
        IBmModel model = models == null ? null : models.getModel(project);
        if (model == null)
        {
            closure.cannotTell = "the object model is not loaded for " + projectName; //$NON-NLS-1$
            return closure;
        }
        int walkDepth = depth <= 0 ? MAX_DEPTH : Math.min(depth, MAX_DEPTH);
        model.executeReadonlyTask(new AbstractBmTask<Void>("ScopeClosure.of") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                walk(tx, model, project, names, walkDepth, closure, monitor);
                return null;
            }
        }, true);
        return closure;
    }

    /**
     * Reads the configuration of a project.
     *
     * @param project the project.
     * @return the configuration, or <code>null</code> when it is not loaded
     */
    private static Configuration configurationOf(IProject project)
    {
        try
        {
            com._1c.g5.v8.dt.core.platform.IConfigurationProvider provider =
                Activator.getDefault().getConfigurationProvider();
            return provider == null ? null : provider.getConfiguration(project);
        }
        catch (RuntimeException notLoaded)
        {
            Activator.logDebug("scope closure: no configuration for " + project.getName()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Follows the references outward from the named objects.
     *
     * @param tx the read transaction.
     * @param model the object model.
     * @param project the project.
     * @param names the objects asked for.
     * @param depth how far to follow.
     * @param closure the answer being built.
     * @param monitor the cancel signal.
     */
    private static void walk(IBmTransaction tx, IBmModel model, IProject project, List<String> names,
        int depth, Closure closure, IProgressMonitor monitor)
    {
        try
        {
            Configuration configuration = configurationOf(project);
            if (configuration == null)
            {
                closure.cannotTell = "the configuration of " + project.getName() //$NON-NLS-1$
                    + " could not be read"; //$NON-NLS-1$
                return;
            }
            Collection<IBmObject> roots = new ArrayList<>();
            Set<String> asked = new LinkedHashSet<>();
            for (String name : names)
            {
                MdObject found = BmSubsystemHelper.resolveByFqn(configuration, name);
                if (found instanceof IBmObject)
                {
                    roots.add((IBmObject)found);
                    asked.add(name);
                    closure.requested.add(name);
                }
                else
                {
                    closure.notFound.add(name);
                }
            }
            if (roots.isEmpty())
            {
                closure.cannotTell = "none of the names given is an object of this configuration"; //$NON-NLS-1$
                return;
            }
            BmReferencesHelper.BfsResult found = BmReferencesHelper.bfs(tx, model.getEngine(), roots,
                BmReferencesHelper.Direction.OUT, MAX_NODES, MAX_EDGES, depth, monitor::isCanceled);
            closure.truncated = found.truncated;
            closure.referencesFollowed = found.edges.size();
            for (java.util.Map.Entry<String, IBmObject> reached : found.nodes.entrySet())
            {
                // The roots come back among the nodes; what a person weighs is what came WITH them.
                if (asked.contains(reached.getKey()))
                {
                    continue;
                }
                // Metadata objects only. Measured: the walk also returns the model's own
                // furniture - a module's context index, a Type node, a ContextDef - and those are
                // not things a move carries. A list a person cannot act on is worse than a short
                // one, because it reads as if the move were larger than it is.
                if (reached.getValue() instanceof MdObject)
                {
                    closure.added.add(reached.getKey());
                }
                else
                {
                    closure.internalNodes++;
                }
            }
        }
        catch (RuntimeException | LinkageError cannotWalk)
        {
            closure.cannotTell = "the references could not be followed: " + cannotWalk; //$NON-NLS-1$
            Activator.logDebug("scope closure failed: " + cannotWalk); //$NON-NLS-1$
        }
    }
}
