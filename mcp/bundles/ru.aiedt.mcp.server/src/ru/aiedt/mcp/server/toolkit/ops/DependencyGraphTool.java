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
import java.util.List;
import java.util.Map;
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
            + "Caps: maxNodes (default 200), maxEdges (default 500). " //$NON-NLS-1$
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
                "project | subsystem | object | module (default project)") //$NON-NLS-1$
            .stringProperty("subsystemName", "Subsystem name when scope=subsystem") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", "Object FQN when scope=object") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("moduleFqn", "Module FQN when scope=module") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("depth", "BFS depth (1-5, default 2)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("format", "json | mermaid | plantuml | dot (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("direction", "in | out | both (default both)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("maxNodes", "Cap for BFS (default 200)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("maxEdges", "Cap for edges (default 500)") //$NON-NLS-1$ //$NON-NLS-2$
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
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        String levelStr = orDefault(JsonUtils.extractStringArgument(params, "level"), //$NON-NLS-1$
            "metadata"); //$NON-NLS-1$
        String scopeStr = orDefault(JsonUtils.extractStringArgument(params, "scope"), //$NON-NLS-1$
            "project"); //$NON-NLS-1$
        String formatStr = orDefault(JsonUtils.extractStringArgument(params, "format"), //$NON-NLS-1$
            "json"); //$NON-NLS-1$
        String directionStr = orDefault(JsonUtils.extractStringArgument(params, "direction"), //$NON-NLS-1$
            "both"); //$NON-NLS-1$
        int depth = clamp(parseInt(params, "depth", 2), 1, 5); //$NON-NLS-1$
        int maxNodes = Math.max(1, parseInt(params, "maxNodes", 200)); //$NON-NLS-1$
        int maxEdges = Math.max(1, parseInt(params, "maxEdges", 500)); //$NON-NLS-1$

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

        try
        {
            return UiSync.call(() -> {
                try
                {
                    return buildGraph(project, level, scopeStr, params, direction, depth,
                        maxNodes, maxEdges, format);
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
        int maxNodes, int maxEdges, DependencyGraphBuilder.Format format) throws Exception
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
                    Collection<IBmObject> roots = resolveRoots(level, scopeStr, params,
                        configuration, tx);
                    if (roots == null || roots.isEmpty())
                    {
                        bfsRef.set(new BmReferencesHelper.BfsResult());
                        return null;
                    }
                    BmReferencesHelper.BfsResult result;
                    if (level == Level.MODULES)
                    {
                        result = buildModuleGraph(project, bmModel, tx, roots, direction, depth,
                            maxNodes, maxEdges, monitor, watch);
                    }
                    else
                    {
                        result = BmReferencesHelper.bfs(tx, bmModel.getEngine(), roots, direction,
                            maxNodes, maxEdges, depth,
                            () -> monitor.isCanceled() || watch.stopHere());
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
        return tr.toJson();
    }

    private BmReferencesHelper.BfsResult buildModuleGraph(IProject project, IBmModel bmModel,
        IBmTransaction tx, Collection<IBmObject> roots, BmReferencesHelper.Direction direction,
        int depth, int maxNodes, int maxEdges, IProgressMonitor monitor, WatchForCancel watch)
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        java.util.Deque<IBmObject> queue = new java.util.ArrayDeque<>(roots);
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        for (IBmObject root : roots)
        {
            if (root instanceof Module)
            {
                String fqn = BslCallGraphHelper.moduleFqnOf((Module) root);
                if (fqn == null)
                {
                    // The module is its own top object on some builds, and then the lookup that
                    // walks up to a container has nothing to walk to.
                    fqn = fqnOf(root);
                }
                if (fqn != null)
                {
                    visited.add(fqn);
                    result.nodes.put(fqn, root);
                }
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
                IBmObject node = queue.poll();
                if (!(node instanceof Module))
                {
                    continue;
                }
                Module module = (Module) node;
                String selfFqn = BslCallGraphHelper.moduleFqnOf(module);
                BslCallGraphHelper.emitEdgesForModule(project, bmModel, module,
                    direction == BmReferencesHelper.Direction.IN
                        || direction == BmReferencesHelper.Direction.BOTH,
                    direction == BmReferencesHelper.Direction.OUT
                        || direction == BmReferencesHelper.Direction.BOTH,
                    edge -> {
                        if (result.edges.size() >= maxEdges)
                        {
                            result.truncated = true;
                            return;
                        }
                        result.edges.add(new BmReferencesHelper.Edge(edge.fromFqn, edge.toFqn,
                            "calls")); //$NON-NLS-1$
                        addModuleNodeIfNew(result, queue, visited, tx, edge.fromFqn, maxNodes);
                        addModuleNodeIfNew(result, queue, visited, tx, edge.toFqn, maxNodes);
                    });
            }
            currentDepth++;
        }
        return result;
    }

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
     * @param tx the live transaction, which resolves the FQN back to the module
     * @param fqn the module's FQN as the edge names it
     * @param maxNodes the node cap
     */
    private void addModuleNodeIfNew(BmReferencesHelper.BfsResult result,
        java.util.Deque<IBmObject> queue, java.util.Set<String> visited, IBmTransaction tx,
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
        IBmObject module = moduleByFqn(tx, fqn);
        result.nodes.put(fqn, module); // the renderer reads the key; the walk needs the object
        if (module != null)
        {
            queue.add(module);
        }
    }

    /**
     * The module a FQN names, or <code>null</code> when it names something else.
     *
     * @param tx the live transaction
     * @param fqn a module FQN such as {@code CommonModule.Sales.Module}
     * @return the module, or <code>null</code>
     */
    private static IBmObject moduleByFqn(IBmTransaction tx, String fqn)
    {
        if (tx == null || fqn == null)
        {
            return null;
        }
        Object top = tx.getTopObjectByFqn(fqn);
        return top instanceof Module ? (IBmObject)top : null;
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
     *
     * @param roots the roots as the scope resolved them
     * @param tx the live transaction
     * @return the modules, in the order the roots named them, each once
     */
    private static Collection<IBmObject> asModules(Collection<IBmObject> roots, IBmTransaction tx)
    {
        List<IBmObject> modules = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (IBmObject root : roots)
        {
            if (root instanceof Module)
            {
                String own = fqnOf(root);
                if (own == null || seen.add(own))
                {
                    modules.add(root);
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
                IBmObject module = moduleByFqn(tx, candidateFqn);
                if (module != null && seen.add(candidateFqn))
                {
                    modules.add(module);
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
     * The roots of the walk, in the form the level walks.
     *
     * @param level the level asked for
     * @param scopeStr the scope asked for
     * @param params the call arguments
     * @param configuration the project configuration
     * @param tx the live transaction
     * @return the roots, or <code>null</code> when the scope names nothing
     */
    private Collection<IBmObject> resolveRoots(Level level, String scopeStr,
        Map<String, String> params, Configuration configuration, IBmTransaction tx)
    {
        Collection<IBmObject> roots = resolveScopeRoots(level, scopeStr, params, configuration, tx);
        if (roots == null || level != Level.MODULES)
        {
            return roots;
        }
        return asModules(roots, tx);
    }

    @SuppressWarnings("unchecked")
    private Collection<IBmObject> resolveScopeRoots(Level level, String scopeStr,
        Map<String, String> params, Configuration configuration, IBmTransaction tx)
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
                if (obj instanceof IBmObject)
                {
                    roots.add((IBmObject) obj);
                }
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
                }
                return roots;
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

    private enum Level
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
