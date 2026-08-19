/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResourceFactory;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.common.Functions;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.md.PredefinedItemUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeSet;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypes;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.TypeItem;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectScopeResolver;

/**
 * Finds every place a metadata object is referenced - back-references, produced types, predefined
 * items, fields, and BSL code - across the BM index. The slow BSL phase goes through Xtext; the
 * caller can drop it ({@code skipBsl=true}) or wait for it via the soft-timeout / {@code runKey}
 * protocol.
 * <p>
 * A search that outlasts its HTTP-handler budget is handed to {@link PendingWorkRegistry#REFERENCES}
 * and the caller polls with the returned {@code runKey}. Because the worker is a pure read, a fresh
 * call with identical params never evicts a completed entry - re-running it is harmless - and the
 * entry is removed only after a successful {@code await}.
 * </p>
 */
public class ReferenceLocator implements IMcpTool
{
    public static final String NAME = "find_references"; //$NON-NLS-1$

    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Per-phase soft cap (headroom before the global {@code limit} is applied in formatOutput). */
    private static final int PER_PHASE_CAP_MULTIPLIER = 10;

    /**
     * Ordered substring rules that turn an EClass name into a human-readable pluralized bucket
     * label. First match wins, so order matters - keep parallel-derivation parity with EDT's
     * own category resolver.
     */
    private static final List<Map.Entry<String, String>> TYPE_BUCKETS = List.of(
        Map.entry("Subsystem", "Subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Role", "Roles"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonModule", "Common modules"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonAttribute", "Common attributes"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("EventSubscription", "Event subscriptions"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ScheduledJob", "Scheduled jobs"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Form", "Forms"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Document", "Documents"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Catalog", "Catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Register", "Registers"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Report", "Reports"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("DataProcessor", "Data processors"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Command", "Commands"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("TypeDescription", "Type descriptions"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("FunctionalOption", "Functional options"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Template", "Templates") //$NON-NLS-1$ //$NON-NLS-2$
    );

    /** Display-path prefixes that mark a reference as EDT-internal noise. */
    private static final List<String> INTERNAL_PATH_PREFIXES = List.of(
        "Value types", //$NON-NLS-1$
        "Form context", //$NON-NLS-1$
        "Db view defs", //$NON-NLS-1$
        "Standard commands"); //$NON-NLS-1$

    /**
     * Each group is an all-must-contain test on a package URI; a single hit on every token of any
     * group classifies the reference as EDT-internal.
     */
    private static final List<List<String>> INTERNAL_URI_TOKEN_GROUPS = List.of(
        List.of("dbview"), //$NON-NLS-1$
        List.of("cmi", "deriveddata")); //$NON-NLS-1$ //$NON-NLS-2$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `code_search` `operation=object_references`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Locate every usage of a metadata object. Reports each spot the object appears: inside other " //$NON-NLS-1$
            + "metadata objects, within BSL modules with their line numbers, forms, roles, subsystems, and more. Accepts " //$NON-NLS-1$
            + "metadata type names in English or Russian (e.g., 'Справочник.Номенклатура', 'Документ.Заказ')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. May be left out - when absent, the tool scans the workspace " //$NON-NLS-1$
                    + "to find whichever project owns the FQN and treats that as the search scope. Supply it directly on a big " //$NON-NLS-1$
                    + "configuration so the auto-detection scan can be skipped.") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "Fully qualified name of the object whose references you want to collect " //$NON-NLS-1$
                    + "(e.g. 'Catalog.Products', 'Document.SalesOrder', 'CommonModule.Common'). " //$NON-NLS-1$
                    + "Russian type names work too (e.g. 'Справочник.Номенклатура')", //$NON-NLS-1$
                true)
            .integerProperty("limit", "Cap on how many results each category returns. Default: 100") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("deep", //$NON-NLS-1$
                "Widen produced-type tracking: tag each discovered type-reference with its concrete kind " //$NON-NLS-1$
                    + "(Object, Reference, Selection, Manager, Cache, List) derived from its EClass. Default: false. " //$NON-NLS-1$
                    + "Handy when assessing the impact of a refactoring.") //$NON-NLS-1$
            .booleanProperty("skipBsl", //$NON-NLS-1$
                "Bypass the BSL code search (the slowest phase). Yields metadata-only references within seconds. " //$NON-NLS-1$
                    + "Handy when metadata refactoring is all you need, or when the BSL search overruns on big objects " //$NON-NLS-1$
                    + "(e.g. Catalog.Сотрудники). Default: false.") //$NON-NLS-1$
            .booleanProperty("bslOnly", //$NON-NLS-1$
                "Look at BSL code alone and omit metadata back-references. The opposite of skipBsl. Default: false.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("categories", //$NON-NLS-1$
                "Comma-separated allow-list naming which categories to gather. Choices: " //$NON-NLS-1$
                    + "back (direct back references), produced (produced types), predefined (predefined items), " //$NON-NLS-1$
                    + "fields (attribute/dimension references), bsl (BSL code). Default: empty = everything on.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "Soft wait budget in seconds before a Pending JSON with a runKey is handed back. Default: 30. " //$NON-NLS-1$
                    + "Range: 5-120 (out-of-range values are clamped). Repeating the call with identical params keeps " //$NON-NLS-1$
                    + "waiting (no work is duplicated).") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Continue polling a search already in flight. Supply the runKey from an earlier " //$NON-NLS-1$
                    + "Pending response; the remaining params are disregarded. Hands back the result when ready, otherwise another " //$NON-NLS-1$
                    + "Pending JSON.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn != null && !objectFqn.isEmpty())
        {
            return "references-" + objectFqn.replace(".", "-").toLowerCase(Locale.ROOT) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return "references.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return resumePending(runKeyParam, params);
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        boolean deep = JsonUtils.extractBooleanArgument(params, "deep", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean skipBsl = JsonUtils.extractBooleanArgument(params, "skipBsl", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean bslOnly = JsonUtils.extractBooleanArgument(params, "bslOnly", false); //$NON-NLS-1$ //$NON-NLS-2$
        String categoriesCsv = JsonUtils.extractStringArgument(params, "categories"); //$NON-NLS-1$

        if (objectFqn == null || objectFqn.isEmpty())
        {
            return "Error: objectFqn must be supplied"; //$NON-NLS-1$
        }

        // Auto-detect the owning project when omitted (1.42). Falls back to a helpful error when the
        // workspace holds no 1C project that owns the FQN.
        OwnerInfo owner = null;
        if (projectName == null || projectName.isEmpty())
        {
            owner = autoDetectOwnerProject(MetadataTypeCatalog.normalizeFqn(objectFqn));
            if (owner == null)
            {
                return "Error: projectName was omitted and no 1C project in the workspace owns '" //$NON-NLS-1$
                    + objectFqn + "'. Provide projectName directly, or first open the owning project in EDT."; //$NON-NLS-1$
            }
        }
        final String resolvedProjectName = owner != null ? owner.projectName : projectName;

        if (skipBsl && bslOnly)
        {
            return "Error: skipBsl and bslOnly cannot both be set"; //$NON-NLS-1$
        }

        int limit = 100;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int)Double.parseDouble(limitStr), 500);
            }
            catch (NumberFormatException e)
            {
                // keep the default - a bad limit is not a fatal error
            }
        }

        CategoryFilter filter = CategoryFilter.from(categoriesCsv, skipBsl, bslOnly);

        String runKey = PendingWorkRegistry.computeRunKey(resolvedProjectName, objectFqn, categoriesCsv,
            String.valueOf(skipBsl), String.valueOf(bslOnly), String.valueOf(limit), String.valueOf(deep));

        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        final int maxResults = limit;
        final boolean deepFinal = deep;
        final String fqnFinal = objectFqn;
        final CategoryFilter filterFinal = filter;

        PendingWorkRegistry registry = PendingWorkRegistry.REFERENCES;
        registry.pruneExpired();
        // Unlike update_database, there is NO fresh-call guard here: the worker is a pure read and a
        // re-run is harmless, so a completed entry is removed only after a successful await below.
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey,
            () -> findReferencesInternal(resolvedProjectName, fqnFinal, maxResults, deepFinal, filterFinal));

        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, resolvedProjectName, objectFqn, filter, timeoutMs);
    }

    /**
     * Resumes a previously-issued search by its runKey.
     *
     * @param runKey the key to poll
     * @param params the call params (for the timeout)
     * @return the cached result (and entry removed), a fresh Pending body, or a JSON error
     */
    private String resumePending(String runKey, Map<String, String> params)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.REFERENCES;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult
                .error("runKey not found - the search has either finished and already been collected, " //$NON-NLS-1$
                    + "or was dropped and evicted by TTL. Send a fresh request without runKey to begin again.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        // projectName/objectFqn are omitted on the resume path - the retry call may not repeat them.
        return buildPendingJson(runKey, entry, null, null, null, timeoutMs);
    }


    /**
     * Builds the Pending body returned when the wait budget runs out before the search finishes.
     *
     * @param runKey the key to resume with
     * @param entry the in-flight search
     * @param projectName the resolved project, or {@code null} to omit
     * @param objectFqn the FQN, or {@code null} to omit
     * @param filter accepted for future use; not serialized
     * @param timeoutMs how long was waited
     * @return a JSON Pending body
     */
    private String buildPendingJson(String runKey, PendingWorkRegistry.PendingEntry entry, String projectName,
        String objectFqn, CategoryFilter filter, long timeoutMs)
    {
        ToolResult body = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "Search is still in progress. Invoke this tool once more with runKey=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + runKey + "\" to carry on waiting (or repeat the same projectName/objectFqn/filters - identical " //$NON-NLS-1$
                + "params yield the same runKey). Set skipBsl=true to drop the slow BSL phase."); //$NON-NLS-1$
        if (projectName != null)
        {
            body.put("projectName", projectName); //$NON-NLS-1$
        }
        if (objectFqn != null)
        {
            body.put("objectFqn", objectFqn); //$NON-NLS-1$
        }
        return body.toJson();
    }

    // -- = --
    // Worker
    // -- = --

    /**
     * The worker: resolves the target, builds the scope, and runs the reference collector on the BM
     * thread. Runs off the HTTP thread via the registry.
     *
     * @param projectName the resolved project name
     * @param objectFqn the FQN (Russian-type-normalized inside)
     * @param limit the per-category result cap
     * @param deep whether to expand produced-type kinds
     * @param filter the category filter
     * @return a MARKDOWN report (or an {@code "Error: ..."} plain string)
     */
    private String findReferencesInternal(String projectName, String objectFqn, int limit, boolean deep,
        CategoryFilter filter)
    {
        Activator.logInfo("find_references filter: back=" + filter.back //$NON-NLS-1$
            + " produced=" + filter.produced //$NON-NLS-1$
            + " predefined=" + filter.predefined //$NON-NLS-1$
            + " fields=" + filter.fields //$NON-NLS-1$
            + " bsl=" + filter.bsl); //$NON-NLS-1$

        objectFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider is unavailable"; //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);

        boolean isExternalProject = ExternalProjectResolver.isExternalProject(project);
        if (config == null && !isExternalProject)
        {
            return "Error: Failed to obtain the configuration for project: " + projectName; //$NON-NLS-1$
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return "Error: BM model manager is unavailable"; //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return "Error: BM model is unavailable for project: " + projectName; //$NON-NLS-1$
        }

        // Resolve the target - first via the configuration container, then by direct model lookup
        // (covers external-object projects whose getConfiguration() yields nothing usable).
        MdObject targetObject = config != null ? findMdObjectByFqn(config, objectFqn) : null;
        if (targetObject == null)
        {
            targetObject = ExternalProjectResolver.resolveByFqn(project, MetadataTypeCatalog.normalizeFqn(objectFqn));
        }
        if (targetObject == null)
        {
            String[] dotParts = objectFqn.split("\\."); //$NON-NLS-1$
            if (dotParts.length > 2)
            {
                return "Error: Object could not be located: " + objectFqn //$NON-NLS-1$
                    + ".\nNote: find_references handles only top-level metadata objects " //$NON-NLS-1$
                    + "for example Catalog.Products, Document.Invoice or CommonModule.Billing. " //$NON-NLS-1$
                    + "Nested elements like attributes, forms, commands and tabular sections are not handled " //$NON-NLS-1$
                    + "(e.g. 'Catalog.DataAreas.Attribute.DataAreaStatus' is invalid)."; //$NON-NLS-1$
            }
            return "Error: Object could not be located: " + objectFqn; //$NON-NLS-1$
        }

        // Empty-filter short-circuit (1.40.6). Avoids running the BM transaction at all when every
        // phase was disabled - typically categories="bsl" combined with skipBsl=true.
        if (filter.isAllDisabled())
        {
            return "# Usages of " + objectFqn + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "**No categories are on - this combination of filters switches off every collector phase.**\n\n" //$NON-NLS-1$
                + "Typical reason: `categories=\"bsl\"` together with `skipBsl=true` cancels out the sole requested phase.\n\n" //$NON-NLS-1$
                + "Supply `categories=metadata,bsl` (or clear the categories/skipBsl/bslOnly flags) to gather references.\n"; //$NON-NLS-1$
        }

        // Multi-project scope (1.42): the owner + sister extensions / externals reachable through
        // Eclipse project references.
        String ownerKindForScope = inferOwnerKindForScope(project, isExternalProject);
        ProjectScopeResolver.ScopeResult scope = ProjectScopeResolver.resolveScope(project, ownerKindForScope);

        BmReferenceHarvester master = new BmReferenceHarvester(bmModel, targetObject, limit, deep, filter);
        List<String> searchedProjectNames = new ArrayList<>();
        try
        {
            bmModel.executeReadonlyTask(master, true);
            searchedProjectNames.add(project.getName());

            for (int i = 1; i < scope.projects.size(); i++)
            {
                if (master.references.size() >= limit)
                {
                    break; // global cap reached
                }
                IProject sister = scope.projects.get(i);
                IBmModel sisterBm = bmModelManager.getModel(sister);
                if (sisterBm == null)
                {
                    continue;
                }
                BmReferenceHarvester sisterCollector =
                    new BmReferenceHarvester(sisterBm, targetObject, limit, deep, filter);
                // Share the dedup set so a reference already seen in the owner is not re-added.
                sisterCollector.seenReferences.addAll(master.seenReferences);
                try
                {
                    sisterBm.executeReadonlyTask(sisterCollector, true);
                    master.references.addAll(sisterCollector.references);
                    master.seenReferences.addAll(sisterCollector.seenReferences);
                    searchedProjectNames.add(sister.getName());
                    // Enforce the global cap after the merge.
                    synchronized (master.references)
                    {
                        int size = master.references.size();
                        if (size > limit)
                        {
                            master.references.subList(limit, size).clear();
                        }
                    }
                }
                catch (Exception sisterEx)
                {
                    Activator.logWarning("find_references sister-scope pass failed for " + sister.getName() //$NON-NLS-1$
                        + ": " //$NON-NLS-1$
                        + (sisterEx.getMessage() != null ? sisterEx.getMessage()
                            : sisterEx.getClass().getSimpleName()));
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed while running the BM task", e); //$NON-NLS-1$
            return "Error: the reference search failed: " + e.getMessage(); //$NON-NLS-1$
        }

        return formatOutput(objectFqn, master, filter, scope, searchedProjectNames);
    }

    /**
     * Infers which kind of project owns the search target, for scope resolution. External-object
     * projects and extensions are walked through the Eclipse reference graph to find sister projects.
     *
     * @param project the owning project
     * @param isExternalProject whether the project was already identified as external
     * @return one of {@code "external"} / {@code "extension"} / {@code "configuration"}
     */
    private static String inferOwnerKindForScope(IProject project, boolean isExternalProject)
    {
        if (isExternalProject)
        {
            return "external"; //$NON-NLS-1$
        }
        if (ExternalProjectResolver.detectExternalKind(project) != null)
        {
            return "external"; //$NON-NLS-1$
        }
        try
        {
            for (IProject ref : project.getDescription().getReferencedProjects())
            {
                if (ref != null && ref.isAccessible() && ExternalProjectResolver.detectExternalKind(ref) == null)
                {
                    return "extension"; //$NON-NLS-1$
                }
            }
        }
        catch (CoreException | RuntimeException ignored)
        {
            // getDescription() unavailable - fall through to the default.
        }
        return "configuration"; //$NON-NLS-1$
    }

    /**
     * Auto-detects the workspace project that owns an FQN (1.42). Returns the first project whose
     * configuration (or external-object model) holds the object.
     *
     * @param objectFqn the normalized FQN
     * @return the owner info, or {@code null} when no project owns it
     */
    private static OwnerInfo autoDetectOwnerProject(String objectFqn)
    {
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return null;
        }
        String[] parts = objectFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (candidate == null || !candidate.isAccessible())
            {
                continue;
            }
            Configuration config = configProvider.getConfiguration(candidate);
            if (config != null)
            {
                MdObject hit = MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
                if (hit != null)
                {
                    String kind =
                        ExternalProjectResolver.detectExternalKind(candidate) != null ? "external" : "configuration"; //$NON-NLS-1$ //$NON-NLS-2$
                    return new OwnerInfo(candidate.getName(), kind);
                }
            }
            if (ExternalProjectResolver.detectExternalKind(candidate) != null)
            {
                MdObject extHit = ExternalProjectResolver.resolveByFqn(candidate, objectFqn);
                if (extHit != null)
                {
                    return new OwnerInfo(candidate.getName(), "external"); //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /**
     * Resolves a top-level FQN through a configuration container.
     *
     * @param config the configuration
     * @param fqn the FQN
     * @return the object, or {@code null}
     */
    private static MdObject findMdObjectByFqn(Configuration config, String fqn)
    {
        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        return MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
    }

    // -- = --
    // Output formatting
    // -- = --

    /**
     * Renders the MARKDOWN report.
     *
     * @param objectFqn the FQN
     * @param collector the populated reference collector
     * @param filter the active filter
     * @param scope the search scope (may be {@code null})
     * @param searchedProjectNames the projects actually searched
     * @return the MARKDOWN string
     */
    private static String formatOutput(String objectFqn, BmReferenceHarvester collector, CategoryFilter filter,
        ProjectScopeResolver.ScopeResult scope, List<String> searchedProjectNames)
    {
        int totalCount = collector.getTotalCount();
        StringBuilder out = new StringBuilder();
        out.append("# Usages of ").append(objectFqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("**Total references located:** ").append(totalCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (scope != null && !searchedProjectNames.isEmpty())
        {
            int n = searchedProjectNames.size();
            out.append("\n> **Projects searched** (1.42): ").append(n) //$NON-NLS-1$
                .append(n == 1 ? " project\n" : " projects\n"); //$NON-NLS-1$ //$NON-NLS-2$
            out.append("> - searchedProjects: ").append(String.join(", ", searchedProjectNames)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            out.append("> - scopeReason: ").append(scope.reason).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (scope.notes != null && !scope.notes.isEmpty())
            {
                out.append("> - notes: ").append(String.join("; ", scope.notes)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        // Active-phases block only when at least one phase is off.
        if (!filter.back || !filter.produced || !filter.predefined || !filter.fields || !filter.bsl)
        {
            List<String> active = new ArrayList<>();
            if (filter.back)
            {
                active.add("back"); //$NON-NLS-1$
            }
            if (filter.produced)
            {
                active.add("produced"); //$NON-NLS-1$
            }
            if (filter.predefined)
            {
                active.add("predefined"); //$NON-NLS-1$
            }
            if (filter.fields)
            {
                active.add("fields"); //$NON-NLS-1$
            }
            if (filter.bsl)
            {
                active.add("bsl"); //$NON-NLS-1$
            }
            out.append("\n> Phases run: ").append(String.join(", ", active)); //$NON-NLS-1$ //$NON-NLS-2$
            if (!filter.bsl)
            {
                out.append(" *(BSL phase omitted)*"); //$NON-NLS-1$
            }
            out.append("\n"); //$NON-NLS-1$
        }

        if (totalCount == 0)
        {
            out.append("\nNothing references this object.\n"); //$NON-NLS-1$
            return out.toString();
        }

        // Split references: BSL refs group by module -> sorted line numbers; the rest sorts by path.
        TreeMap<String, List<Integer>> bslByModule = new TreeMap<>();
        List<UsageHit> metadataRefs = new ArrayList<>();
        for (UsageHit ref : collector.references)
        {
            if (ref.isBslReference)
            {
                bslByModule.computeIfAbsent(ref.sourcePath, k -> new ArrayList<>()).add(Integer.valueOf(ref.line));
            }
            else
            {
                metadataRefs.add(ref);
            }
        }
        metadataRefs.sort((a, b) -> {
            String pa = a.sourcePath != null ? a.sourcePath : ""; //$NON-NLS-1$
            String pb = b.sourcePath != null ? b.sourcePath : ""; //$NON-NLS-1$
            return pa.compareToIgnoreCase(pb);
        });

        for (UsageHit ref : metadataRefs)
        {
            String displayPath = ref.sourcePath != null && ref.sourcePath.startsWith("/") //$NON-NLS-1$
                ? ref.sourcePath.substring(1)
                : ref.sourcePath;
            out.append("\n- ").append(displayPath); //$NON-NLS-1$
            if (ref.feature != null && !ref.feature.isEmpty())
            {
                out.append(" - ").append(ref.feature); //$NON-NLS-1$
            }
        }

        if (!bslByModule.isEmpty())
        {
            out.append("\n\n### BSL code references\n"); //$NON-NLS-1$
            for (Map.Entry<String, List<Integer>> entry : bslByModule.entrySet())
            {
                String modulePath = entry.getKey();
                if (modulePath != null && modulePath.startsWith("/")) //$NON-NLS-1$
                {
                    modulePath = modulePath.substring(1);
                }
                List<Integer> lines = entry.getValue();
                Collections.sort(lines);
                StringBuilder lineList = new StringBuilder();
                for (Integer line : lines)
                {
                    if (lineList.length() > 0)
                    {
                        lineList.append("; "); //$NON-NLS-1$
                    }
                    lineList.append("Line ").append(line); //$NON-NLS-1$
                }
                out.append("\n- ").append(modulePath).append(" [").append(lineList).append("]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            out.append("\n"); //$NON-NLS-1$
        }

        return out.toString();
    }

    // -- = --
    // Inner data classes
    // -- = --

    /** Five collection-phase toggles. Defaults to all-true for backward compatibility. */
    private static final class CategoryFilter
    {
        boolean back = true;
        boolean produced = true;
        boolean predefined = true;
        boolean fields = true;
        boolean bsl = true;

        /**
         * Builds a filter from the CSV whitelist and the convenience flags. The CSV sets everything
         * to false first and re-enables named phases; {@code skipBsl} / {@code bslOnly} override the
         * CSV after, in that order.
         *
         * @param csv the comma-separated whitelist, or {@code null}/empty for "all enabled"
         * @param skipBsl drop the BSL phase
         * @param bslOnly keep only the BSL phase
         * @return the filter
         */
        static CategoryFilter from(String csv, boolean skipBsl, boolean bslOnly)
        {
            CategoryFilter f = new CategoryFilter();
            if (csv != null && !csv.isEmpty())
            {
                f.back = false;
                f.produced = false;
                f.predefined = false;
                f.fields = false;
                f.bsl = false;
                for (String token : csv.split("\\s*,\\s*")) //$NON-NLS-1$
                {
                    String t = token.toLowerCase(Locale.ROOT);
                    switch (t)
                    {
                        case "back": //$NON-NLS-1$
                            f.back = true;
                            break;
                        case "produced": //$NON-NLS-1$
                            f.produced = true;
                            break;
                        case "predefined": //$NON-NLS-1$
                            f.predefined = true;
                            break;
                        case "fields": //$NON-NLS-1$
                            f.fields = true;
                            break;
                        case "bsl": //$NON-NLS-1$
                            f.bsl = true;
                            break;
                        case "metadata": //$NON-NLS-1$
                            // Convenience alias: every metadata-bearing phase, except BSL.
                            f.back = true;
                            f.produced = true;
                            f.predefined = true;
                            f.fields = true;
                            break;
                        default:
                            // Unknown tokens are ignored silently for backward compat.
                            break;
                    }
                }
            }
            // Convenience flags override the CSV.
            if (skipBsl)
            {
                f.bsl = false;
            }
            if (bslOnly)
            {
                f.back = false;
                f.produced = false;
                f.predefined = false;
                f.fields = false;
                f.bsl = true;
            }
            return f;
        }

        boolean isAllDisabled()
        {
            return !back && !produced && !predefined && !fields && !bsl;
        }
    }

    /** Resolved owner project + the scope kind inferred at auto-detect time. */
    private static final class OwnerInfo
    {
        final String projectName;
        final String kind;

        OwnerInfo(String projectName, String kind)
        {
            this.projectName = projectName;
            this.kind = kind;
        }
    }

    /** A single reference: metadata refs use {@code feature}, BSL refs use {@code line}. */
    private static final class UsageHit
    {
        final String category;
        final String sourcePath;
        final String feature;
        final int line;
        final boolean isBslReference;

        private UsageHit(String category, String sourcePath, String feature, int line, boolean isBslReference)
        {
            this.category = category;
            this.sourcePath = sourcePath;
            this.feature = feature;
            this.line = line;
            this.isBslReference = isBslReference;
        }

        /** Metadata reference: feature is set, line is unused. */
        static UsageHit metadata(String category, String sourcePath, String feature)
        {
            return new UsageHit(category, sourcePath, feature, 0, false);
        }

        /** BSL reference: line is set, feature is unused. */
        static UsageHit bsl(String category, String sourcePath, int line)
        {
            return new UsageHit(category, sourcePath, null, line, true);
        }
    }

    // -- = --
    // BM task (reference collector)
    // -- = --

    /**
     * A read-only BM task that runs the five collection phases. Its synchronized collections are
     * shared across sister-project runs so dedup carries over.
     */
    private static final class BmReferenceHarvester extends AbstractBmTask<Void>
    {
        private final IBmModel bmModel;
        private final MdObject targetObject;
        private final int limit;
        private final boolean deep;
        private final CategoryFilter filter;

        final List<UsageHit> references = Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenReferences = Collections.synchronizedSet(new HashSet<>());

        BmReferenceHarvester(IBmModel bmModel, MdObject targetObject, int limit, boolean deep, CategoryFilter filter)
        {
            super("Locate references to " + targetObject.getName()); //$NON-NLS-1$
            this.bmModel = bmModel;
            this.targetObject = targetObject;
            this.limit = limit;
            this.deep = deep;
            this.filter = filter;
        }

        @Override
        public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
        {
            IBmEngine engine = bmModel.getEngine();
            IBmObject targetBmObject = (IBmObject)targetObject;
            if (filter.back)
            {
                scanBackRefs(engine, targetBmObject);
            }
            if (filter.produced)
            {
                collectProducedTypesReferences(engine, targetObject);
            }
            if (filter.predefined)
            {
                collectPredefinedItemsReferences(engine, targetObject);
            }
            if (filter.fields)
            {
                collectFieldReferences(engine, targetObject);
            }
            if (filter.bsl)
            {
                collectBslReferences(targetBmObject);
            }
            return null;
        }

        /**
         * Dedup + internal-path filter. Self-references are intentionally kept (EDT shows them); the
         * internal-path filter catches EDT-internal artifacts that would only confuse an agent.
         *
         * @param ref the reference to add
         * @return {@code true} when added
         */
        boolean addReference(UsageHit ref)
        {
            String key = ref.category + ":" + ref.sourcePath + ":" //$NON-NLS-1$ //$NON-NLS-2$
                + (ref.isBslReference ? ref.line : ref.feature);
            synchronized (seenReferences)
            {
                if (seenReferences.contains(key))
                {
                    return false;
                }
                if (ref.sourcePath != null && isEdtInternalPath(ref.sourcePath))
                {
                    return false;
                }
                seenReferences.add(key);
            }
            references.add(ref);
            return true;
        }

        int getTotalCount()
        {
            return references.size();
        }

        // ---- Phase 1: back-references ----

        private void scanBackRefs(IBmEngine engine, IBmObject target)
        {
            Collection<IBmCrossReference> refs = engine.getBackReferences(target);
            for (IBmCrossReference ref : refs)
            {
                if (references.size() >= limit * PER_PHASE_CAP_MULTIPLIER)
                {
                    break;
                }
                IBmObject sourceObject = ref.getObject();
                if (sourceObject == null)
                {
                    continue;
                }
                if (looksEdtInternal(ref))
                {
                    continue;
                }
                String category = bucketForType(sourceObject);
                String sourcePath = buildDisplayPath(sourceObject, ref);
                if (sourcePath == null)
                {
                    continue;
                }
                EStructuralFeature feature = ref.getFeature();
                String featureName = feature != null ? feature.getName() : null;
                addReference(UsageHit.metadata(category, sourcePath, featureName));
            }
        }

        // ---- Phase 2: produced-type references ----

        private void collectProducedTypesReferences(IBmEngine engine, MdObject target)
        {
            MdTypes producedTypes = MdClassUtil.getProducedTypes(target);
            if (producedTypes == null)
            {
                return;
            }
            for (EObject type : producedTypes.eContents())
            {
                TypeItem typeItem = getTypeItem(type);
                if (!(typeItem instanceof IBmObject))
                {
                    continue;
                }
                String typeKind = deep ? classifyTypeKind(type) : null;
                Collection<IBmCrossReference> refs = engine.getBackReferences((IBmObject)typeItem);
                for (IBmCrossReference ref : refs)
                {
                    if (references.size() >= limit * PER_PHASE_CAP_MULTIPLIER)
                    {
                        break;
                    }
                    IBmObject sourceObject = ref.getObject();
                    if (sourceObject == null || looksEdtInternal(ref))
                    {
                        continue;
                    }
                    String category = bucketForType(sourceObject);
                    String sourcePath = buildDisplayPath(sourceObject, ref);
                    if (sourcePath == null)
                    {
                        continue;
                    }
                    EStructuralFeature feature = ref.getFeature();
                    String featureName = feature != null ? feature.getName() : ""; //$NON-NLS-1$
                    String featureLabel =
                        typeKind != null ? "Type[" + typeKind + "]: " + featureName : "Type: " + featureName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    addReference(UsageHit.metadata(category, sourcePath, featureLabel));
                }
            }
        }

        // ---- Phase 3: predefined-item references ----

        private void collectPredefinedItemsReferences(IBmEngine engine, MdObject target)
        {
            for (PredefinedItem item : PredefinedItemUtil.getItems((EObject)target))
            {
                if (!(item instanceof IBmObject))
                {
                    continue;
                }
                Collection<IBmCrossReference> refs = engine.getBackReferences((IBmObject)item);
                for (IBmCrossReference ref : refs)
                {
                    if (references.size() >= limit * PER_PHASE_CAP_MULTIPLIER)
                    {
                        break;
                    }
                    IBmObject sourceObject = ref.getObject();
                    if (sourceObject == null)
                    {
                        continue;
                    }
                    String category = "Predefined items"; //$NON-NLS-1$
                    String sourcePath = buildDisplayPath(sourceObject, ref);
                    if (sourcePath == null)
                    {
                        continue;
                    }
                    String feature = item.getName();
                    addReference(UsageHit.metadata(category, sourcePath, feature));
                }
            }
        }

        // ---- Phase 4: field references ----

        private void collectFieldReferences(IBmEngine engine, MdObject target)
        {
            if (!(target instanceof FieldSource))
            {
                return;
            }
            FieldSource fieldSource = (FieldSource)target;
            for (Object fieldObj : fieldSource.getFields())
            {
                if (!(fieldObj instanceof IBmObject))
                {
                    continue;
                }
                IBmObject field = (IBmObject)fieldObj;
                Collection<IBmCrossReference> refs = engine.getBackReferences(field);
                for (IBmCrossReference ref : refs)
                {
                    if (references.size() >= limit * PER_PHASE_CAP_MULTIPLIER)
                    {
                        break;
                    }
                    IBmObject sourceObject = ref.getObject();
                    if (sourceObject == null)
                    {
                        continue;
                    }
                    // Skip self-references for fields - the field's own object is not a "user".
                    if (sourceObject == target)
                    {
                        continue;
                    }
                    String category = "Field references"; //$NON-NLS-1$
                    String sourcePath = buildDisplayPath(sourceObject, ref);
                    if (sourcePath == null)
                    {
                        continue;
                    }
                    String feature = null;
                    if (field instanceof NamedElement)
                    {
                        feature = ((NamedElement)field).getName();
                    }
                    addReference(UsageHit.metadata(category, sourcePath, feature));
                }
            }
        }

        // ---- Phase 5: BSL references (the slow phase) ----

        private void collectBslReferences(IBmObject target)
        {
            try
            {
                IResourceServiceProvider rsp =
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
                if (rsp == null)
                {
                    return;
                }
                IReferenceFinder finder = rsp.get(IReferenceFinder.class);
                if (finder == null)
                {
                    return;
                }

                List<URI> targetURIs = new ArrayList<>();
                targetURIs.add(EcoreUtil.getURI((EObject)target));
                if (target instanceof MdObject)
                {
                    MdTypes producedTypes = MdClassUtil.getProducedTypes((MdObject)target);
                    if (producedTypes != null)
                    {
                        for (EObject type : producedTypes.eContents())
                        {
                            TypeItem typeItem = getTypeItem(type);
                            if (typeItem != null)
                            {
                                targetURIs.add(EcoreUtil.getURI(typeItem));
                            }
                        }
                    }
                }

                finder.findAllReferences(targetURIs, null, this::onBslRefHit,
                    new NullProgressMonitor());
            }
            catch (Exception e)
            {
                Activator.logError("Failed to find BSL references", e); //$NON-NLS-1$
            }
        }

        /** The acceptor callback for {@link IReferenceFinder#findAllReferences}. */
        private void onBslRefHit(IReferenceDescription refDesc)
        {
            if (references.size() >= limit * PER_PHASE_CAP_MULTIPLIER)
            {
                return;
            }
            URI sourceUri = refDesc.getSourceEObjectUri();
            if (sourceUri == null)
            {
                return;
            }
            String path = sourceUri.path();
            if (path == null)
            {
                path = sourceUri.toString();
            }
            String modulePath = reduceToModulePath(path);
            int line = extractLineNumberFromSourceUri(sourceUri);
            addReference(UsageHit.bsl("BSL modules", modulePath, line)); //$NON-NLS-1$
        }

        /**
         * Reduces a BSL source URI path to the module path an agent can read.
         *
         * @param path the raw URI path
         * @return the module path, never {@code null}
         */
        private String reduceToModulePath(String path)
        {
            if (path == null)
            {
                return "Unidentified module"; //$NON-NLS-1$
            }
            int srcIdx = path.indexOf("/src/"); //$NON-NLS-1$
            if (srcIdx >= 0)
            {
                return path.substring(srcIdx + 5);
            }
            String[] segments = path.split("/"); //$NON-NLS-1$
            if (segments.length >= 3)
            {
                return segments[segments.length - 3] + "/" + segments[segments.length - 2] //$NON-NLS-1$
                    + "/" + segments[segments.length - 1]; //$NON-NLS-1$
            }
            return path;
        }

        /**
         * Resolves the source EObject for a BSL reference and returns its line number, or 0 when the
         * node model cannot be loaded.
         *
         * @param sourceUri the URI to resolve
         * @return the 1-based line, or 0
         */
        private int extractLineNumberFromSourceUri(URI sourceUri)
        {
            try
            {
                ResourceSet resourceSet = new ResourceSetImpl();
                IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
                if (rsp != null)
                {
                    try
                    {
                        XtextResourceFactory factory = rsp.get(XtextResourceFactory.class);
                        if (factory != null)
                        {
                            resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bsl", factory); //$NON-NLS-1$
                        }
                    }
                    catch (Exception factoryEx)
                    {
                        // keep going - the resource set may still resolve without the factory
                    }
                }
                Resource resource = resourceSet.getResource(sourceUri.trimFragment(), true);
                EObject eObject = resource.getEObject(sourceUri.fragment());
                INode node = NodeModelUtils.findActualNodeFor(eObject);
                if (node != null)
                {
                    return node.getStartLine();
                }
            }
            catch (RuntimeException e)
            {
                // WrappedException (EMF resource load failure) and any other runtime failure both
                // land here - WrappedException is itself a RuntimeException, so the multicatch form
                // would be rejected as non-disjoint.
                Activator.logError("Failed to extract the line number from URI: " + sourceUri, e); //$NON-NLS-1$
            }
            // No node-model fallback is wired up - the safe answer is line 0.
            return 0;
        }
    }

    // -- = --
    // Internal-path / internal-reference filters
    // -- = --

    /**
     * Tells whether a back-reference originates from an EDT-internal construct that an agent would
     * not act on - DB view definitions, transient features, or derived command-interface data.
     *
     * @param ref the back-reference
     * @return {@code true} when the reference is EDT-internal
     */
    private static boolean looksEdtInternal(IBmCrossReference ref)
    {
        IBmObject object = ref.getObject();
        if (object == null)
        {
            return true;
        }
        EStructuralFeature feature = ref.getFeature();
        if (feature != null && feature.isTransient())
        {
            return true;
        }
        String packageUri;
        try
        {
            packageUri = object.eClass().getEPackage().getNsURI();
        }
        catch (RuntimeException ignored)
        {
            return true;
        }
        if (packageUri == null)
        {
            return true;
        }
        // Each group is an all-must-contain test against the package URI.
        for (List<String> group : INTERNAL_URI_TOKEN_GROUPS)
        {
            boolean allPresent = true;
            for (String token : group)
            {
                if (!packageUri.contains(token))
                {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a built reference path is EDT-internal. Applied to the built path, not the raw
     * URI.
     *
     * @param path the built path
     * @return {@code true} when the path is an EDT-internal section
     */
    private static boolean isEdtInternalPath(String path)
    {
        if (path == null)
        {
            return false;
        }
        for (String prefix : INTERNAL_PATH_PREFIXES)
        {
            if (path.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    // -- = --
    // Category + path helpers
    // -- = --

    /**
     * Maps an EClass name to a readable pluralized category label. First substring match wins.
     *
     * @param object the BM object
     * @return the category label, never {@code null}
     */
    private static String bucketForType(IBmObject object)
    {
        if (object == null)
        {
            return "Other"; //$NON-NLS-1$
        }
        String name;
        try
        {
            name = object.eClass().getName();
        }
        catch (RuntimeException ignored)
        {
            return "Other"; //$NON-NLS-1$
        }
        if (name == null)
        {
            return "Other"; //$NON-NLS-1$
        }
        // First substring match wins - TYPE_BUCKETS preserves the prior cascade order.
        for (Map.Entry<String, String> rule : TYPE_BUCKETS)
        {
            if (name.contains(rule.getKey()))
            {
                return rule.getValue();
            }
        }
        return name;
    }

    /**
     * Builds the display path for a reference - the top object's FQN plus an EDT-style inner path.
     * Returns {@code null} to signal "skip this reference".
     *
     * @param sourceObject the referencing BM object
     * @param ref the back-reference
     * @return the full path, or {@code null} when the reference should be skipped
     */
    private static String buildDisplayPath(IBmObject sourceObject, IBmCrossReference ref)
    {
        IBmObject topObject = topmostContainer(sourceObject);
        if (topObject == null)
        {
            return getObjectPath(sourceObject);
        }
        String topPath;
        try
        {
            topPath = topObject.bmGetFqn();
        }
        catch (RuntimeException ignored)
        {
            topPath = null;
        }
        if (topPath == null || topPath.isEmpty())
        {
            return getObjectPath(sourceObject);
        }
        String innerPath = composeInnerPath(sourceObject, topObject, ref.getFeature());
        if (innerPath != null && isEdtInternalPath(innerPath))
        {
            return null;
        }
        String result = topPath;
        if (innerPath != null && !innerPath.isEmpty())
        {
            result = result + " - " + innerPath; //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Walks up the containment chain to the top BM object.
     *
     * @param object the starting object
     * @return the top object, or {@code null}
     */
    private static IBmObject topmostContainer(IBmObject object)
    {
        IBmObject current = object;
        while (current != null)
        {
            try
            {
                if (current.bmIsTop())
                {
                    return current;
                }
            }
            catch (RuntimeException ignored)
            {
                return null;
            }
            EObject container = current.eContainer();
            if (!(container instanceof IBmObject))
            {
                return null;
            }
            current = (IBmObject)container;
        }
        return null;
    }

    /**
     * Builds a path through the containment chain using EDT-style feature labels - reproduces EDT's
     * {@code TableItemsFactory.getTopObjectPathToReference}.
     *
     * @param sourceObject the referencing object
     * @param topObject the top container
     * @param baseFeature the back-reference feature
     * @return the inner path, or {@code null} when empty
     */
    private static String composeInnerPath(EObject sourceObject, EObject topObject,
        EStructuralFeature baseFeature)
    {
        // Walk from the source up to (but not including) the top, pushing each (object, feature)
        // pair. eContainingFeature() gives the feature on the parent that holds the child.
        Deque<ContainmentStep> stack = new ArrayDeque<>();
        EObject current = sourceObject;
        while (current != null && current != topObject)
        {
            EStructuralFeature feature = current.eContainingFeature();
            stack.push(new ContainmentStep(current, feature));
            current = current.eContainer();
        }
        if (stack.isEmpty())
        {
            // The source itself is the top - emit just the base feature label.
            String label = labelOfFeature(baseFeature);
            return label != null ? label : null;
        }

        // Pop the top segment and seed the path with its feature label.
        ContainmentStep topPair = stack.pop();
        StringBuilder path = new StringBuilder();
        String topFeatureLabel = labelOfFeature(topPair.feature);
        if (topFeatureLabel != null)
        {
            path.append(topFeatureLabel);
        }

        String previousFeatureLabel = topFeatureLabel;
        while (!stack.isEmpty())
        {
            ContainmentStep segment = stack.pop();
            String segmentName = getSegmentObjectName(segment.object);
            if (segmentName != null)
            {
                if (path.length() > 0)
                {
                    path.append("."); //$NON-NLS-1$
                }
                path.append(segmentName);
            }
            String currentFeatureLabel = labelOfFeature(segment.feature);
            if (currentFeatureLabel != null)
            {
                // Collapse redundant ".Items.Items" chains - EDT drops the duplicate.
                if ("Items".equals(currentFeatureLabel) && "Items".equals(previousFeatureLabel)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    // skip
                }
                else
                {
                    path.append(".").append(currentFeatureLabel); //$NON-NLS-1$
                }
            }
            previousFeatureLabel = currentFeatureLabel;
        }

        // Append the base feature that triggered the back-reference.
        String baseLabel = labelOfFeature(baseFeature);
        if (baseLabel != null)
        {
            if ("Items".equals(baseLabel) && "Items".equals(previousFeatureLabel)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // skip
            }
            else
            {
                if (path.length() > 0)
                {
                    path.append("."); //$NON-NLS-1$
                }
                path.append(baseLabel);
            }
        }

        return path.length() > 0 ? path.toString() : null;
    }

    /**
     * @param feature the structural feature
     * @return the EDT-localized label, or a capitalized name fallback, or {@code null}
     */
    private static String labelOfFeature(EStructuralFeature feature)
    {
        if (feature == null)
        {
            return null;
        }
        try
        {
            String label = Functions.featureToLabel().apply(feature);
            if (label != null && !label.isEmpty())
            {
                return label;
            }
        }
        catch (RuntimeException ignored)
        {
            // fall through to the name-based fallback
        }
        return upperFirst(feature.getName());
    }

    /**
     * @param object the EMF object
     * @return its name (NamedElement, then MdObject, then {@code *ExtInfo} class name), or
     *         {@code null}
     */
    private static String getSegmentObjectName(EObject object)
    {
        if (object == null)
        {
            return null;
        }
        if (object instanceof NamedElement)
        {
            String name = ((NamedElement)object).getName();
            if (name != null)
            {
                return name;
            }
        }
        if (object instanceof MdObject)
        {
            String name = ((MdObject)object).getName();
            if (name != null)
            {
                return name;
            }
        }
        String className;
        try
        {
            className = object.eClass().getName();
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
        if (className != null && className.endsWith("ExtInfo")) //$NON-NLS-1$
        {
            return className;
        }
        return null;
    }

    /**
     * Builds a best-effort path from the object's own name chain - used when the top-container walk
     * does not yield a usable FQN.
     *
     * @param object the BM object
     * @return the object path, or {@code null}
     */
    private static String getObjectPath(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        try
        {
            String fqn = object.bmGetFqn();
            if (fqn != null && !fqn.isEmpty())
            {
                return renderFqn(fqn);
            }
        }
        catch (RuntimeException ignored)
        {
            // bmGetFqn is restricted to top objects on EDT 2026.1 - fall through to the nested walk.
        }
        return walkNestedChain(object);
    }

    /**
     * Walks the containment chain and builds a human-readable path from named segments.
     *
     * @param object the starting object
     * @return the nested path, or {@code null}
     */
    private static String walkNestedChain(EObject object)
    {
        if (object == null)
        {
            return null;
        }
        Deque<String> parts = new ArrayDeque<>();
        EObject current = object;
        while (current != null)
        {
            String part = getObjectPart(current);
            if (part != null && !part.isEmpty())
            {
                parts.push(part);
            }
            current = current.eContainer();
        }
        if (parts.isEmpty())
        {
            return null;
        }
        return String.join(" / ", parts); //$NON-NLS-1$
    }

    /**
     * @param object the EMF object
     * @return the name segment for a containment path
     */
    private static String getObjectPart(EObject object)
    {
        if (object instanceof NamedElement)
        {
            String name = ((NamedElement)object).getName();
            if (name != null && !name.isEmpty())
            {
                return name;
            }
        }
        if (object instanceof MdObject)
        {
            String name = ((MdObject)object).getName();
            if (name != null && !name.isEmpty())
            {
                return name;
            }
        }
        return null;
    }

    /**
     * Formats an FQN for display: keep the first two dot-separated parts, then append the rest (minus
     * a trailing duplicate {@code Form}) joined by {@code /}.
     *
     * @param fqn the raw FQN
     * @return the formatted path
     */
    private static String renderFqn(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return fqn;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length <= 2)
        {
            return fqn;
        }
        StringBuilder out = new StringBuilder(parts[0]).append(".").append(parts[1]); //$NON-NLS-1$
        for (int i = 2; i < parts.length; i++)
        {
            // Drop a trailing duplicate "Form" segment - it is implied by the form name.
            if (i == parts.length - 1 && "Form".equals(parts[i])) //$NON-NLS-1$
            {
                continue;
            }
            out.append(" / ").append(parts[i]); //$NON-NLS-1$
        }
        return out.toString();
    }

    /**
     * Capitalizes the first letter, leaving the rest alone.
     *
     * @param value the input
     * @return the capitalized value, or {@code null}
     */
    private static String upperFirst(String value)
    {
        if (value == null || value.isEmpty())
        {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Unwraps a produced-type element to its {@link TypeItem}.
     *
     * @param type the EMF object (an {@code MdType} or {@code MdTypeSet})
     * @return the type item, or {@code null}
     */
    private static TypeItem getTypeItem(EObject type)
    {
        if (type instanceof MdType)
        {
            return ((MdType)type).getType();
        }
        if (type instanceof MdTypeSet)
        {
            return ((MdTypeSet)type).getTypeSet();
        }
        return null;
    }

    /**
     * Deep-mode kind classifier: takes the EClass name of a produced-type element and normalizes it to
     * one of {@code Reference / Object / Selection / Manager / Cache / List}, falling back to the
     * trimmed name (or full class name when trim yields nothing).
     *
     * @param type the produced-type element
     * @return the kind label
     */
    private static String classifyTypeKind(EObject type)
    {
        String name;
        try
        {
            name = type.eClass().getName();
        }
        catch (RuntimeException ignored)
        {
            return "Type"; //$NON-NLS-1$
        }
        if (name == null)
        {
            return "Type"; //$NON-NLS-1$
        }
        String trimmed = name;
        String[] suffixes = {"MdTypeSet", "MdType", "TypeSet", "Type"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String suffix : suffixes)
        {
            int idx = trimmed.indexOf(suffix);
            if (idx >= 0)
            {
                trimmed = trimmed.substring(0, idx) + trimmed.substring(idx + suffix.length());
                break;
            }
        }
        if (trimmed.isEmpty())
        {
            return name;
        }
        switch (trimmed)
        {
            case "Ref": //$NON-NLS-1$
                return "Reference"; //$NON-NLS-1$
            case "Object": //$NON-NLS-1$
                return "Object"; //$NON-NLS-1$
            case "Selection": //$NON-NLS-1$
                return "Selection"; //$NON-NLS-1$
            case "Manager": //$NON-NLS-1$
                return "Manager"; //$NON-NLS-1$
            case "Cache": //$NON-NLS-1$
                return "Cache"; //$NON-NLS-1$
            case "List": //$NON-NLS-1$
                return "List"; //$NON-NLS-1$
            default:
                return trimmed;
        }
    }

    /** A (object, containing-feature) pair used in the inner-path walk. */
    private static final class ContainmentStep
    {
        final EObject object;
        final EStructuralFeature feature;

        ContainmentStep(EObject object, EStructuralFeature feature)
        {
            this.object = object;
            this.feature = feature;
        }
    }
}
