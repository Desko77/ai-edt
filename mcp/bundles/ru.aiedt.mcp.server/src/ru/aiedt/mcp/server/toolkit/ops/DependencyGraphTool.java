/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import ru.aiedt.mcp.server.support.WatchForCancel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmReferencesHelper;
import ru.aiedt.mcp.server.support.BslCallGraphHelper;
import ru.aiedt.mcp.server.support.DependencyGraphBuilder;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Builds a dependency graph between metadata objects and / or BSL modules.
 * <p>
 * Levels: {@code metadata} (Document.Sale -> Catalog.Products edges via
 * reference attributes), {@code modules} (CommonModule.A -> CommonModule.B via
 * call graph), {@code mixed} (both).
 * <p>
 * BFS runs inside a single {@code IBmModel.executeReadonlyTask}; never call
 * {@code display.syncExec} inside a BFS loop.
 */
public class DependencyGraphTool implements IMcpTool
{
    public static final String NAME = "dependency_graph"; //$NON-NLS-1$

    /** The values {@code scope} accepts. */
    static final List<String> SCOPES = List.of("project", "subsystem", "object", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** The largest {@code maxNodes} a walk takes; a larger value is cut to it. */
    static final int MAX_NODES = 2000;

    /** The largest {@code maxEdges} a walk takes; a larger value is cut to it. */
    static final int MAX_EDGES = 5000;

    /** The tag of the refusal given when the root the scope names is absent from the project. */
    static final String ROOT_NOT_FOUND = "rootNotFound"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=dependency_graph`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Build a dependency graph between metadata objects and / or BSL modules. " //$NON-NLS-1$
            + "Levels: metadata / modules / mixed. " //$NON-NLS-1$
            + "Formats: json (structured nodes/edges/cycles), mermaid, plantuml, dot. " //$NON-NLS-1$
            + "Caps: maxNodes (default 200, at most 2000), maxEdges (default 500, at most 5000). " //$NON-NLS-1$
            + "When BFS hits a cap or BM watchdog cancels, returns partial graph " //$NON-NLS-1$
            + "with truncated=true."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("level", //$NON-NLS-1$
                "metadata | modules | mixed (default metadata)") //$NON-NLS-1$
            .stringProperty("scope", //$NON-NLS-1$
                "project | subsystem | object | module; absent, the selectors decide the root") //$NON-NLS-1$
            .stringProperty("subsystemName", "Subsystem name when scope=subsystem") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", "Object FQN when scope=object") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("moduleFqn", "Module FQN when scope=module") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("depth", "BFS depth (1-5, default 2)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("format", "json | mermaid | plantuml | dot (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("direction", "in | out | both (default both)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("edgeKinds", //$NON-NLS-1$
                "dependency_graph: via values to keep, comma-separated or a JSON array. " //$NON-NLS-1$
                    + "Omit to keep every kind.") //$NON-NLS-1$
            .integerProperty("maxNodes", "Node cap, default 200, at most 2000") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("maxEdges", "Edge cap, default 500, at most 5000") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }

        String levelStr = orDefault(JsonUtils.extractStringArgument(params, "level"), //$NON-NLS-1$
            "metadata"); //$NON-NLS-1$
        String askedScope = JsonUtils.extractStringArgument(params, "scope"); //$NON-NLS-1$
        String formatStr = orDefault(JsonUtils.extractStringArgument(params, "format"), //$NON-NLS-1$
            "json"); //$NON-NLS-1$
        String directionStr = orDefault(JsonUtils.extractStringArgument(params, "direction"), //$NON-NLS-1$
            "both"); //$NON-NLS-1$
        int depth = clamp(parseInt(params, "depth", 2), 1, 5); //$NON-NLS-1$
        int maxNodes = nodeCap(params);
        int maxEdges = edgeCap(params);
        List<String> edgeKinds = understoodEdgeKinds(params);

        Level level = parseLevel(levelStr);
        if (level == null)
        {
            return ToolResult.error(TextSuggest.invalidValue("level", levelStr, //$NON-NLS-1$
                java.util.Arrays.asList("metadata", "modules", "mixed"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        BmReferencesHelper.Direction direction = parseDirection(directionStr);
        if (direction == null)
        {
            return ToolResult.error(TextSuggest.invalidValue("direction", directionStr, //$NON-NLS-1$
                java.util.Arrays.asList("in", "out", "both"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        DependencyGraphBuilder.Format format = parseFormat(formatStr);
        if (format == null)
        {
            return ToolResult.error(TextSuggest.invalidValue("format", formatStr, //$NON-NLS-1$
                java.util.Arrays.asList("json", "mermaid", "plantuml", "dot"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String scopeRefusal = scopeRefusal(askedScope, params);
        if (scopeRefusal != null)
        {
            return ToolResult.error(scopeRefusal).toJson();
        }
        String scopeStr = scopeWord(askedScope, params);

        try
        {
            return UiSync.call(() -> {
                try
                {
                    return buildGraph(project, level, scopeStr, params, direction, depth,
                        maxNodes, maxEdges, format, edgeKinds);
                }
                catch (Exception e)
                {
                    Activator.logError("dependency_graph error", e); //$NON-NLS-1$
                    return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
                }
            });
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    private String buildGraph(IProject project, Level level, String scopeStr,
        Map<String, String> params, BmReferencesHelper.Direction direction, int depth,
        int maxNodes, int maxEdges, DependencyGraphBuilder.Format format,
        List<String> edgeKinds) throws Exception
    {
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("configuration provider is not published as a service").toJson(); //$NON-NLS-1$
        }
        Configuration configuration = configProvider.getConfiguration(project);
        if (configuration == null)
        {
            return ToolResult.error("Configuration not available for project").toJson(); //$NON-NLS-1$
        }
        IBmModelManager bmManager = Activator.getDefault().getBmModelManager();
        if (bmManager == null)
        {
            return ToolResult.error("Error: object model manager is not published as a service").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        AtomicReference<BmReferencesHelper.BfsResult> bfsRef = new AtomicReference<>();
        AtomicReference<Exception> errRef = new AtomicReference<>();
        AtomicReference<String> notFoundRef = new AtomicReference<>();
        AtomicReference<String> notBuiltRef = new AtomicReference<>();
        List<String> modulesUnloaded = new ArrayList<>();
        ModuleLookup lookup = pathModuleLookup(project);
        // Started outside the task: WatchForCancel reads the call scope, which belongs
        // to this thread, and the task body runs on the BM one.
        WatchForCancel watch = WatchForCancel.begin();
        bmModel.executeReadonlyTask(new AbstractBmTask<Void>("dependency_graph.bfs") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                try
                {
                    Collection<IBmObject> roots = resolveScopeRoots(level, scopeStr, params,
                        configuration, tx, lookup);
                    if (roots == null)
                    {
                        notFoundRef.set(rootNotFound(scopeStr, params, configuration));
                        return null;
                    }
                    BmReferencesHelper.BfsResult result;
                    if (level == Level.MODULES)
                    {
                        LinkedHashMap<String, Module> rootModules = asModules(roots, lookup,
                            modulesUnloaded);
                        if (rootModules.isEmpty())
                        {
                            if (!modulesUnloaded.isEmpty())
                            {
                                notBuiltRef.set(moduleLevelNotBuilt(modulesUnloaded));
                                return null;
                            }
                            result = new BmReferencesHelper.BfsResult();
                        }
                        else
                        {
                            result = buildModuleGraph(project, bmModel, rootModules, lookup,
                                direction, depth, maxNodes, maxEdges, monitor, watch);
                        }
                    }
                    else
                    {
                        if (roots.isEmpty())
                        {
                            result = new BmReferencesHelper.BfsResult();
                        }
                        else
                        {
                            Set<String> keep =
                                edgeKinds == null ? null : new LinkedHashSet<>(edgeKinds);
                            BmReferencesHelper.EdgePolicy policy = edgePolicy(level, keep);
                            result = BmReferencesHelper.bfs(tx, bmModel.getEngine(), roots,
                                direction, maxNodes, maxEdges, depth,
                                () -> monitor.isCanceled() || watch.stopHere(), policy);
                        }
                    }
                    bfsRef.set(result);
                }
                catch (Exception ex)
                {
                    errRef.set(ex);
                }
                return null;
            }
        }, true);

        if (errRef.get() != null)
        {
            throw errRef.get();
        }
        if (notFoundRef.get() != null)
        {
            return ToolResult.error(notFoundRef.get()).put("tag", ROOT_NOT_FOUND).toJson(); //$NON-NLS-1$
        }
        if (notBuiltRef.get() != null)
        {
            return ToolResult.error(notBuiltRef.get()).toJson();
        }
        BmReferencesHelper.BfsResult bfs = bfsRef.get();
        if (bfs == null)
        {
            bfs = new BmReferencesHelper.BfsResult();
        }
        Map<String, Object> rendered = DependencyGraphBuilder.render(bfs, format);
        ToolResult tr = ToolResult.success();
        for (Map.Entry<String, Object> entry : rendered.entrySet())
        {
            tr.put(entry.getKey(), entry.getValue());
        }
        tr.put("cancelled", watch.note("nodes")); //$NON-NLS-1$ //$NON-NLS-2$
        tr.put("level", level.name().toLowerCase()); //$NON-NLS-1$
        tr.put("depth", depth); //$NON-NLS-1$
        for (Map.Entry<String, Object> extra : BmReferencesHelper.edgeKindFields(
            level.name().toLowerCase(java.util.Locale.ROOT), edgeKinds, bfs).entrySet())
        {
            tr.put(extra.getKey(), extra.getValue());
        }
        for (Map.Entry<String, Object> extra : moduleLevelFields(modulesUnloaded).entrySet())
        {
            tr.put(extra.getKey(), extra.getValue());
        }
        return tr.toJson();
    }

    /**
     * The filter of the walk a level makes.
     * <p>
     * The metadata level is a graph between metadata objects, and the mixed level shows both halves
     * of a project, so a BSL module is an object of that graph as well. An EDT service object is an
     * object of neither level and is refused at whichever end reports it and as a root.
     * </p>
     *
     * @param level the level asked for
     * @param keepKinds the kinds to keep, or <code>null</code> for all of them
     * @return the filter
     */
    static BmReferencesHelper.EdgePolicy edgePolicy(Level level, Set<String> keepKinds)
    {
        return level == Level.MIXED
            ? BmReferencesHelper.EdgePolicy.mixed(keepKinds)
            : BmReferencesHelper.EdgePolicy.metadata(keepKinds);
    }

    /**
     * The via values to keep, in the order given, with blanks and repeats dropped.
     * <p>
     * Absent is <code>null</code> and keeps every kind. Present and empty keeps none: an empty
     * list and a missing argument are different, and the reader of a list returns
     * <code>null</code> for both, so the key itself is what tells them apart.
     * </p>
     *
     * @param params the call
     * @return the kinds, an empty list when the argument was present and named nothing, or
     *         <code>null</code> when it was absent
     */
    private static List<String> understoodEdgeKinds(Map<String, String> params)
    {
        if (params == null || !params.containsKey("edgeKinds")) //$NON-NLS-1$
        {
            return null;
        }
        List<String> raw = JsonUtils.extractArrayArgument(params, "edgeKinds"); //$NON-NLS-1$
        List<String> understood = new ArrayList<>();
        if (raw != null)
        {
            for (String item : raw)
            {
                if (item == null)
                {
                    continue;
                }
                String trimmed = item.trim();
                if (!trimmed.isEmpty() && !understood.contains(trimmed))
                {
                    understood.add(trimmed);
                }
            }
        }
        return understood;
    }

    /**
     * What one module address lookup came to.
     * <p>
     * A module file that exists but whose model would not load is neither a node nor silence: the
     * level that found it says it could not be walked, and the answer carries the address so the
     * caller can tell a project whose modules are all absent from one whose model is not built.
     * </p>
     */
    static final class ModuleResolution
    {
        /** The module's model, or <code>null</code> when it did not load. */
        final Module module;

        /** Whether the module's file exists where its address says. */
        final boolean addressPresent;

        private ModuleResolution(Module module, boolean addressPresent)
        {
            this.module = module;
            this.addressPresent = addressPresent;
        }

        /**
         * @param module the module that loaded
         * @return a resolution that carries it
         */
        static ModuleResolution loaded(Module module)
        {
            return new ModuleResolution(module, true);
        }

        /**
         * @return a resolution of an address nothing answers
         */
        static ModuleResolution absent()
        {
            return new ModuleResolution(null, false);
        }

        /**
         * @return a resolution of a file that exists and a model that did not load
         */
        static ModuleResolution unloaded()
        {
            return new ModuleResolution(null, true);
        }
    }

    /** Resolves a module FQN to its model, the route the module tools take. */
    @FunctionalInterface
    interface ModuleLookup
    {
        /**
         * @param fqn a module FQN such as {@code CommonModule.Sales.Module}
         * @return the resolution, never <code>null</code>
         */
        ModuleResolution byFqn(String fqn);
    }

    /**
     * The module lookup of a live project: the FQN becomes a {@code src/} path the way
     * {@code call_hierarchy} resolves one, and the module model is loaded from the project's
     * resource set the way {@code read_method_source} loads it.
     * <p>
     * The BM top-object index was the first route tried here and answered nothing for a module
     * FQN on a live stand: every module level came back empty and was reported as success. The
     * file layout is the address the neighbouring tools already agree on, so it is the route this
     * one takes as well.
     * </p>
     *
     * @param project the project the modules belong to
     * @return the lookup, never <code>null</code>
     */
    static ModuleLookup pathModuleLookup(IProject project)
    {
        return fqn -> {
            BslModuleAccess.ModulePathResolution path = BslModuleAccess.resolveModulePath(project,
                fqn);
            if (path == null || !path.isResolved())
            {
                return ModuleResolution.absent();
            }
            Module module = BslModuleAccess.loadModule(project, path.getPath());
            return module == null ? ModuleResolution.unloaded() : ModuleResolution.loaded(module);
        };
    }

    /**
     * The refusal a module level gets when none of its modules would load although their files
     * are there: the level was not built, and an empty graph would have read as a project with no
     * module dependencies.
     *
     * @param unloaded the module FQNs whose file exists and whose model did not load
     * @return the refusal naming the first few addresses
     */
    static String moduleLevelNotBuilt(List<String> unloaded)
    {
        return "The module level could not be built: the module model is not available for " //$NON-NLS-1$
            + unloaded.size() + " module(s): " + String.join(", ", firstFive(unloaded)) //$NON-NLS-1$ //$NON-NLS-2$
            + ". The BSL model of the project is not built yet or could not be read;" //$NON-NLS-1$
            + " the metadata level remains available."; //$NON-NLS-1$
    }

    /**
     * The fields a module-level answer adds about modules it could not walk.
     *
     * @param unloaded the module FQNs whose file exists and whose model did not load
     * @return the fields, empty when every module the level found was walked
     */
    static Map<String, Object> moduleLevelFields(List<String> unloaded)
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (unloaded == null || unloaded.isEmpty())
        {
            return fields;
        }
        fields.put("modulesUnloaded", Integer.valueOf(unloaded.size())); //$NON-NLS-1$
        fields.put("modulesUnloadedNames", firstFive(unloaded)); //$NON-NLS-1$
        return fields;
    }

    /** The first five entries of a list, for a field that must not repeat a thousand names. */
    private static List<String> firstFive(List<String> names)
    {
        return names.size() <= 5 ? List.copyOf(names) : List.copyOf(names.subList(0, 5));
    }

    /**
     * Walks the module level from the modules the roots resolved to.
     *
     * @param project the project the modules belong to
     * @param bmModel the project's object model, which the caller lookup needs
     * @param rootModules the root modules, keyed by the FQN their address names
     * @param lookup the module lookup, which turns an edge's FQN back into a module to walk
     * @param direction in (back) | out (forward) | both
     * @param depth how many rings to expand past the roots
     * @param maxNodes the node cap
     * @param maxEdges the edge cap
     * @param monitor the cancel signal of the BM task
     * @param watch the cancel signal of the call
     * @return the walk
     */
    private BmReferencesHelper.BfsResult buildModuleGraph(IProject project, IBmModel bmModel,
        LinkedHashMap<String, Module> rootModules, ModuleLookup lookup,
        BmReferencesHelper.Direction direction, int depth, int maxNodes, int maxEdges,
        IProgressMonitor monitor, WatchForCancel watch)
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        java.util.Deque<Module> queue = new java.util.ArrayDeque<>();
        java.util.Set<String> visited = new java.util.LinkedHashSet<>(rootModules.keySet());
        for (Map.Entry<String, Module> entry : rootModules.entrySet())
        {
            if (entry.getValue() != null)
            {
                result.nodes.put(entry.getKey(), (IBmObject)entry.getValue());
                queue.add(entry.getValue());
            }
        }
        int currentDepth = 0;
        while (!queue.isEmpty() && currentDepth < depth)
        {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++)
            {
                if ((monitor != null && monitor.isCanceled()) || watch.stopHere())
                {
                    result.truncated = true;
                    return result;
                }
                if (result.nodes.size() >= maxNodes || result.edges.size() >= maxEdges)
                {
                    result.truncated = true;
                    return result;
                }
                Module module = queue.poll();
                if (module == null)
                {
                    continue;
                }
                String selfFqn = BslCallGraphHelper.moduleFqnOf(module);
                BslCallGraphHelper.emitEdgesForModule(project, bmModel, module,
                    direction == BmReferencesHelper.Direction.IN
                        || direction == BmReferencesHelper.Direction.BOTH,
                    direction == BmReferencesHelper.Direction.OUT
                        || direction == BmReferencesHelper.Direction.BOTH,
                    edge -> {
                        // Which half of the call graph reported this edge: the module being walked
                        // is the source of an outgoing one and the target of an incoming one.
                        BmReferencesHelper.Side side = selfFqn != null
                            && selfFqn.equals(edge.fromFqn)
                                ? BmReferencesHelper.Side.FORWARD
                                : BmReferencesHelper.Side.BACKWARD;
                        if (!addModuleEdge(result, edge.fromFqn, edge.toFqn, side, maxEdges))
                        {
                            return;
                        }
                        addModuleNodeIfNew(result, queue, visited, lookup, edge.fromFqn, maxNodes);
                        addModuleNodeIfNew(result, queue, visited, lookup, edge.toFqn, maxNodes);
                    });
            }
            currentDepth++;
        }
        return result;
    }

    /**
     * Records one {@code calls} edge, merging it with the same edge already recorded.
     * <p>
     * The two halves of the call graph name the same connection between two modules: walking the
     * caller reports it as an outgoing one and walking the callee reports it as an incoming one.
     * Added as they came, one connection was two rows under {@code direction=both}. Merging is by
     * {@code from} / {@code to} / {@code via}, as on the other levels, and each half is tallied on
     * its own there, so the pair stands for one reference either way.
     * </p>
     *
     * @param result the graph being built
     * @param fromFqn the caller's FQN
     * @param toFqn the callee's FQN
     * @param side which half of the call graph reported the edge
     * @param maxEdges the edge cap, counted after merging
     * @return <code>true</code> when the edge is in the graph afterwards
     */
    static boolean addModuleEdge(BmReferencesHelper.BfsResult result, String fromFqn, String toFqn,
        BmReferencesHelper.Side side, int maxEdges)
    {
        if (fromFqn == null || toFqn == null || fromFqn.equals(toFqn))
        {
            return false;
        }
        for (BmReferencesHelper.Edge existing : result.edges)
        {
            if (fromFqn.equals(existing.fromFqn) && toFqn.equals(existing.toFqn)
                && CALLS.equals(existing.featureName))
            {
                existing.observe(side);
                return true;
            }
        }
        if (result.edges.size() >= maxEdges)
        {
            result.truncated = true;
            return false;
        }
        result.edges.add(new BmReferencesHelper.Edge(fromFqn, toFqn, CALLS, side));
        return true;
    }

    /** The {@code via} of every edge of the module level. */
    private static final String CALLS = "calls"; //$NON-NLS-1$

    /**
     * Records a module the walk has just found, and puts it in the queue so the next ring walks it.
     * <p>
     * The node used to be recorded without the object behind it, and the queue was never told. The
     * walk then had nothing to expand after the first ring: {@code depth=2} at module level returned
     * the same graph as {@code depth=1}, the neighbours of the roots and nothing past them.
     * </p>
     *
     * @param result the graph being built
     * @param queue the walk's queue
     * @param visited the FQNs already recorded
     * @param lookup the module lookup, which turns the FQN into the module to walk
     * @param fqn the module's FQN as the edge names it
     * @param maxNodes the node cap
     */
    private static void addModuleNodeIfNew(BmReferencesHelper.BfsResult result,
        java.util.Deque<Module> queue, java.util.Set<String> visited, ModuleLookup lookup,
        String fqn, int maxNodes)
    {
        if (fqn == null || visited.contains(fqn))
        {
            return;
        }
        if (result.nodes.size() >= maxNodes)
        {
            result.truncated = true;
            return;
        }
        visited.add(fqn);
        Module module = lookup.byFqn(fqn).module;
        result.nodes.put(fqn, (IBmObject)module); // the renderer reads the key; the walk needs the object
        if (module != null)
        {
            queue.add(module);
        }
    }

    /**
     * The FQN a BM object carries, or <code>null</code> when it carries none.
     *
     * @param object the object
     * @return its FQN
     */
    private static String fqnOf(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        try
        {
            return object.bmGetFqn();
        }
        catch (Exception notATopObject)
        {
            // Only a top object answers this; anything else is not a node of this graph.
            return null;
        }
    }

    /**
     * The modules behind a set of roots, for the level whose nodes are modules.
     * <p>
     * The roots of {@code scope=project} are common modules - that is, the metadata objects that own
     * a module - and the walk works on modules, so every root was dropped and the graph came back
     * empty. An owner is asked for each module name it can carry; a root that is already a module is
     * kept as it is.
     * </p>
     * <p>
     * A module is resolved through the lookup, which turns the FQN into a {@code src/} path and
     * loads the model: the BM top-object index answered no module FQN on a live stand, and this
     * level came back empty as success. An address whose file is there but whose model would not
     * load is reported in {@code unloaded} rather than dropped in silence.
     * </p>
     *
     * @param roots the roots as the scope resolved them
     * @param lookup the module lookup
     * @param unloaded the addresses whose file exists and whose model did not load, filled
     * @return the modules keyed by the FQN their address names, in the order the roots named them
     */
    static LinkedHashMap<String, Module> asModules(Collection<IBmObject> roots, ModuleLookup lookup,
        List<String> unloaded)
    {
        LinkedHashMap<String, Module> modules = new LinkedHashMap<>();
        for (IBmObject root : roots)
        {
            if (root instanceof Module)
            {
                Module module = (Module)root;
                String own = BslCallGraphHelper.moduleFqnOf(module);
                if (own == null)
                {
                    // The module is its own top object on some builds, and then the lookup that
                    // walks up to a container has nothing to walk to.
                    own = fqnOf(root);
                }
                if (own != null)
                {
                    modules.putIfAbsent(own, module);
                }
                continue;
            }
            String ownerFqn = fqnOf(root);
            if (ownerFqn == null)
            {
                continue;
            }
            for (String segment : MODULE_SEGMENTS)
            {
                String candidateFqn = ownerFqn + "." + segment; //$NON-NLS-1$
                ModuleResolution resolution = lookup.byFqn(candidateFqn);
                if (resolution.module != null)
                {
                    modules.putIfAbsent(candidateFqn, resolution.module);
                }
                else if (resolution.addressPresent)
                {
                    unloaded.add(candidateFqn);
                }
            }
        }
        return modules;
    }

    /** The module names an owning metadata object can carry, in the spelling a BM FQN uses. */
    private static final List<String> MODULE_SEGMENTS = java.util.Arrays.asList(
        "Module", "ObjectModule", "ManagerModule", "RecordSetModule", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ValueManagerModule", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * The roots of the walk, as the scope names them.
     *
     * @param level the level asked for
     * @param scopeStr the scope asked for, already canonical
     * @param params the call arguments
     * @param configuration the project configuration
     * @param tx the live transaction
     * @param lookup the module lookup, for the root a module FQN names
     * @return the roots, possibly empty when the named root has no content, or <code>null</code>
     *         when the root the scope names is absent from the project
     */
    private Collection<IBmObject> resolveScopeRoots(Level level, String scopeStr,
        Map<String, String> params, Configuration configuration, IBmTransaction tx,
        ModuleLookup lookup)
    {
        List<IBmObject> roots = new ArrayList<>();
        switch (scopeStr.toLowerCase())
        {
            case "project": //$NON-NLS-1$
                if (level == Level.MODULES)
                {
                    addAllCommonModuleRoots(configuration, roots);
                }
                else
                {
                    addAllTopMdObjects(configuration, roots);
                }
                return roots;
            case "subsystem": //$NON-NLS-1$
            {
                String subsystemName = JsonUtils.extractStringArgument(params, "subsystemName"); //$NON-NLS-1$
                if (subsystemName == null || subsystemName.isEmpty())
                {
                    return null;
                }
                Subsystem ss = findSubsystemByName(configuration, subsystemName);
                if (ss == null)
                {
                    return null;
                }
                for (Object content : ss.getContent())
                {
                    if (content instanceof IBmObject)
                    {
                        roots.add((IBmObject) content);
                    }
                }
                return roots;
            }
            case "object": //$NON-NLS-1$
            {
                String fqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
                if (fqn == null || fqn.isEmpty())
                {
                    return null;
                }
                String[] parts = MetadataTypeCatalog.normalizeFqn(fqn).split("\\.", 2); //$NON-NLS-1$
                if (parts.length < 2)
                {
                    return null;
                }
                MdObject obj = MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]);
                if (!(obj instanceof IBmObject))
                {
                    return null;
                }
                roots.add((IBmObject) obj);
                return roots;
            }
            case "module": //$NON-NLS-1$
            {
                String fqn = JsonUtils.extractStringArgument(params, "moduleFqn"); //$NON-NLS-1$
                if (fqn == null || fqn.isEmpty())
                {
                    return null;
                }
                Object top = tx.getTopObjectByFqn(fqn);
                if (top instanceof IBmObject)
                {
                    roots.add((IBmObject) top);
                    return roots;
                }
                // A module FQN is not always answered by the top-object index; the loader is the
                // route the module tools take, and the one this level resolves roots with.
                Module module = lookup.byFqn(fqn).module;
                if (module != null)
                {
                    roots.add((IBmObject)module);
                }
                return roots.isEmpty() ? null : roots;
            }
            default:
                return null;
        }
    }

    private void addAllTopMdObjects(Configuration configuration, List<IBmObject> roots)
    {
        for (Object item : configuration.eContents())
        {
            if (item instanceof java.util.List)
            {
                for (Object entry : (java.util.List<?>) item)
                {
                    if (entry instanceof IBmObject)
                    {
                        roots.add((IBmObject) entry);
                    }
                }
            }
            else if (item instanceof IBmObject)
            {
                roots.add((IBmObject) item);
            }
        }
        // Configuration.eContents() returns child elements not root collections.
        // Walk the well-known getters reflectively for completeness.
        try
        {
            for (java.lang.reflect.Method m : configuration.getClass().getMethods())
            {
                if (m.getParameterCount() != 0)
                {
                    continue;
                }
                String name = m.getName();
                if (!name.startsWith("get") || "getClass".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    continue;
                }
                Class<?> ret = m.getReturnType();
                if (!java.util.List.class.isAssignableFrom(ret))
                {
                    continue;
                }
                try
                {
                    Object value = m.invoke(configuration);
                    if (value instanceof java.util.List)
                    {
                        for (Object entry : (java.util.List<?>) value)
                        {
                            if (entry instanceof IBmObject && ((IBmObject) entry).bmIsTop())
                            {
                                roots.add((IBmObject) entry);
                            }
                        }
                    }
                }
                catch (Throwable ignored)
                {
                    // ignore inaccessible getters
                }
            }
        }
        catch (Throwable ignored)
        {
            // best-effort scan
        }
    }

    private void addAllCommonModuleRoots(Configuration configuration, List<IBmObject> roots)
    {
        try
        {
            // Configuration declares getCommonModules(), so it is called, not looked up.
            for (Object entry : configuration.getCommonModules())
            {
                if (entry instanceof IBmObject)
                {
                    roots.add((IBmObject)entry);
                }
            }
        }
        catch (Throwable ignored)
        {
            // best-effort
        }
    }

    private Subsystem findSubsystemByName(Configuration configuration, String name)
    {
        try
        {
            // Configuration declares getSubsystems(), so it is called, not looked up.
            for (Subsystem entry : configuration.getSubsystems())
            {
                if (entry != null && name.equalsIgnoreCase(entry.getName()))
                {
                    return entry;
                }
            }
        }
        catch (Throwable ignored)
        {
            // best-effort
        }
        return null;
    }

    /**
     * The refusal a call gets for its scope and its selectors before any walk: an unknown scope
     * word, a scope without the argument that names its root, a selector the scope does not
     * accept, and - when no scope was asked - selectors that name more than one root.
     * <p>
     * A selector used to be dropped in silence when {@code scope} stayed at its project default,
     * so a call that named an object walked the whole project and read as a project with
     * thousands of nodes and no edges. The selector now either drives the walk or is refused.
     * Does not look the root up; {@link #rootNotFound} answers for a root the project lacks.
     * </p>
     *
     * @param asked the scope as the caller wrote it, or <code>null</code> when absent
     * @param params the call arguments
     * @return the refusal text, or <code>null</code> when the scope and its argument are given
     */
    static String scopeRefusal(String asked, Map<String, String> params)
    {
        String scope = asked == null ? null : asked.toLowerCase(Locale.ROOT);
        if (scope != null && !SCOPES.contains(scope))
        {
            return TextSuggest.invalidValue("scope", asked, SCOPES); //$NON-NLS-1$
        }
        if (scope == null)
        {
            List<String> named = namedSelectors(params);
            if (named.size() > 1)
            {
                return named.get(0) + " conflicts with " //$NON-NLS-1$
                    + String.join(", ", named.subList(1, named.size())) //$NON-NLS-1$
                    + ": they name different roots. Pass one of them."; //$NON-NLS-1$
            }
            return null;
        }
        String argument = rootArgument(scope);
        if (argument != null)
        {
            String value = present(params, argument);
            if (value == null)
            {
                String example = "subsystemName".equals(argument) ? "'Sales'" : "'Catalog.Products'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return TextSuggest.missingParam(argument, example)
                    + " It names the root when scope=" + scope + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        List<String> stray = new ArrayList<>();
        for (String named : namedSelectors(params))
        {
            // The scope's own argument names its root; every other selector names a different one.
            if (argument == null || !named.startsWith(argument + " ")) //$NON-NLS-1$
            {
                stray.add(named);
            }
        }
        if (!stray.isEmpty())
        {
            return "scope=" + scope + " does not accept " + String.join(", ", stray) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return null;
    }

    /**
     * The scope a call walks, with the absent word decided by its selectors: {@code objectFqn}
     * names an object, {@code moduleFqn} a module, {@code subsystemName} a subsystem, and nothing
     * the whole project.
     * <p>
     * Called after {@link #scopeRefusal}, so the selectors never conflict by the time this runs.
     * </p>
     *
     * @param asked the scope as the caller wrote it, or <code>null</code> when absent
     * @param params the call arguments
     * @return the scope word, lower case
     */
    static String scopeWord(String asked, Map<String, String> params)
    {
        if (asked != null && !asked.isBlank())
        {
            return asked.toLowerCase(Locale.ROOT);
        }
        if (present(params, "objectFqn") != null) //$NON-NLS-1$
        {
            return "object"; //$NON-NLS-1$
        }
        if (present(params, "moduleFqn") != null) //$NON-NLS-1$
        {
            return "module"; //$NON-NLS-1$
        }
        if (present(params, "subsystemName") != null) //$NON-NLS-1$
        {
            return "subsystem"; //$NON-NLS-1$
        }
        return "project"; //$NON-NLS-1$
    }

    /**
     * The selectors a call names, each as {@code argument 'value'}.
     *
     * @param params the call arguments
     * @return the named selectors, in a fixed order
     */
    private static List<String> namedSelectors(Map<String, String> params)
    {
        List<String> named = new ArrayList<>();
        for (String argument : java.util.Arrays.asList("objectFqn", "moduleFqn", "subsystemName")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String value = present(params, argument);
            if (value != null)
            {
                named.add(argument + " '" + value + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return named;
    }

    /**
     * The value of an argument as a non-blank string.
     *
     * @param params the call arguments
     * @param argument the argument name
     * @return the trimmed value, or <code>null</code> when absent or blank
     */
    private static String present(Map<String, String> params, String argument)
    {
        String value = JsonUtils.extractStringArgument(params, argument);
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The refusal a call gets when the root its scope names is absent from the project.
     *
     * @param scopeStr the scope asked for, one of {@link #SCOPES}
     * @param params the call arguments
     * @param configuration the project configuration, read for close names
     * @return the refusal text naming the argument, its value and the close names found
     */
    static String rootNotFound(String scopeStr, Map<String, String> params, Configuration configuration)
    {
        String scope = scopeStr.toLowerCase(Locale.ROOT);
        String argument = rootArgument(scope);
        if (argument == null)
        {
            return "scope=" + scope + " names no root."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String value = JsonUtils.extractStringArgument(params, argument);
        if ("subsystemName".equals(argument)) //$NON-NLS-1$
        {
            return argument + " '" + value + "' names no top-level subsystem of the project." //$NON-NLS-1$ //$NON-NLS-2$
                + closeNames("Subsystem", MetadataTypeCatalog.findSimilarObjects(configuration, //$NON-NLS-1$
                    "Subsystem", value, 5)); //$NON-NLS-1$
        }
        String fqn = MetadataTypeCatalog.normalizeFqn(value);
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1)
        {
            return argument + " '" + value + "' is not an FQN of the form Type.Name."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String type = fqn.substring(0, dot);
        return argument + " '" + value + "' names no object of the project." //$NON-NLS-1$ //$NON-NLS-2$
            + closeNames(type, MetadataTypeCatalog.findSimilarObjects(configuration, type,
                fqn.substring(dot + 1), 5));
    }

    /**
     * The argument that names the root of a scope.
     *
     * @param scope the scope in lower case
     * @return the argument name, or <code>null</code> for the project scope
     */
    private static String rootArgument(String scope)
    {
        switch (scope)
        {
            case "subsystem": return "subsystemName"; //$NON-NLS-1$ //$NON-NLS-2$
            case "object": return "objectFqn"; //$NON-NLS-1$ //$NON-NLS-2$
            case "module": return "moduleFqn"; //$NON-NLS-1$ //$NON-NLS-2$
            default: return null;
        }
    }

    /**
     * The close names of a refusal, as a sentence.
     *
     * @param type the type prefix to put before each name
     * @param names the close names, possibly empty
     * @return the sentence with a leading space, or an empty string when there are none
     */
    private static String closeNames(String type, List<String> names)
    {
        if (names.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        List<String> qualified = new ArrayList<>(names.size());
        for (String name : names)
        {
            qualified.add(type + "." + name); //$NON-NLS-1$
        }
        return " Close names: " + String.join(", ", qualified) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The node cap of a walk: {@code maxNodes}, default 200, cut to 1..{@link #MAX_NODES}.
     *
     * @param params the call arguments
     * @return the cap
     */
    static int nodeCap(Map<String, String> params)
    {
        return clamp(parseInt(params, "maxNodes", 200), 1, MAX_NODES); //$NON-NLS-1$
    }

    /**
     * The edge cap of a walk: {@code maxEdges}, default 500, cut to 1..{@link #MAX_EDGES}.
     *
     * @param params the call arguments
     * @return the cap
     */
    static int edgeCap(Map<String, String> params)
    {
        return clamp(parseInt(params, "maxEdges", 500), 1, MAX_EDGES); //$NON-NLS-1$
    }

    // ---- helpers -----------------------------------------------------------

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static int parseInt(Map<String, String> params, String key, int fallback)
    {
        // Delegate to the shared parser: it accepts Gson's "1.0" number form
        // (Integer.parseInt("1.0") throws, which silently dropped depth=1 etc.).
        return JsonUtils.extractIntArgument(params, key, fallback);
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    /** What the nodes of the graph are. Visible to this package so the choice it drives is testable. */
    enum Level
    {
        METADATA, MODULES, MIXED
    }

    private static Level parseLevel(String s)
    {
        switch (s.toLowerCase())
        {
            case "metadata": return Level.METADATA; //$NON-NLS-1$
            case "modules": return Level.MODULES; //$NON-NLS-1$
            case "mixed": return Level.MIXED; //$NON-NLS-1$
            default: return null;
        }
    }

    private static BmReferencesHelper.Direction parseDirection(String s)
    {
        switch (s.toLowerCase())
        {
            case "in": return BmReferencesHelper.Direction.IN; //$NON-NLS-1$
            case "out": return BmReferencesHelper.Direction.OUT; //$NON-NLS-1$
            case "both": return BmReferencesHelper.Direction.BOTH; //$NON-NLS-1$
            default: return null;
        }
    }

    private static DependencyGraphBuilder.Format parseFormat(String s)
    {
        switch (s.toLowerCase())
        {
            case "json": return DependencyGraphBuilder.Format.JSON; //$NON-NLS-1$
            case "mermaid": return DependencyGraphBuilder.Format.MERMAID; //$NON-NLS-1$
            case "plantuml": return DependencyGraphBuilder.Format.PLANTUML; //$NON-NLS-1$
            case "dot": return DependencyGraphBuilder.Format.DOT; //$NON-NLS-1$
            default: return null;
        }
    }
}
