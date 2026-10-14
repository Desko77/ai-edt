/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import ru.aiedt.mcp.server.support.WatchForCancel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmEventSubscriptionHelper;
import ru.aiedt.mcp.server.support.BslCallGraphHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;

/**
 * Finds exported BSL methods that nothing calls - dead-code candidates a project-wide scan can surface
 * but EDT's own {@code module-unused-method} check deliberately cannot, because an exported method is a
 * public API and EDT never flags those. For every exported method across the configuration's modules the
 * tool counts BSL references through the Xtext index; a method with zero callers that is neither a known
 * platform/БСП event handler (called by name) nor an extension interceptor (&После/&Вместо/&Перед) is
 * reported as a candidate.
 * <p>
 * This is a heuristic candidate list, never a verdict: БСП and some configurations dispatch to exported
 * methods by name via {@code Выполнить} / string indirection, which static analysis cannot see - confirm
 * each candidate before removing it. The scan is read-only and idempotent; the router runs it through the
 * generic soft-timeout / {@code runKey} Pending flow, so on a large configuration a single call may hand
 * back a {@code Pending} body - repeat the identical call to keep waiting.
 */
public class FindDeadCodeTool
    implements IMcpTool
{
    public static final String NAME = "find_dead_code"; //$NON-NLS-1$

    private static final int DEFAULT_LIMIT = 100;

    /** Backstop so an accidental full-tree walk of a giant workspace cannot run unbounded. */
    private static final int MODULE_SCAN_CAP = 5000;

    /**
     * Backstop of the reads the walk makes only to collect name-written calls: form modules and
     * modules outside the family prefix and the path filter. A configuration holds several of
     * those for every candidate module, so this bound sits above the candidate cap rather than
     * beside it; together the two keep the worst walk at 25000 module loads.
     */
    private static final int NAME_CALL_SCAN_CAP = 20000;

    /** Path segment that marks a module as a form module; excluded by default (form event handlers dominate). */
    private static final String FORMS_SEGMENT = "/Forms/"; //$NON-NLS-1$

    /** Path prefix of common-form modules; also form modules, excluded by default. */
    private static final String COMMON_FORMS_PREFIX = "CommonForms/"; //$NON-NLS-1$

    /**
     * Pragma symbols that mark a method as a platform-called extension interceptor (not dead), stored
     * lowercased so the check is case-insensitive. Covers the full bilingual vocabulary EDT emits:
     * Russian {@code После/Вместо/Перед/ИзменениеИКонтроль} and English {@code Before/After/Around/
     * ChangeAndValidate} (mirrors {@code ListInterceptorsTool}).
     */
    private static final Set<String> INTERCEPTOR_PRAGMAS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "после", //$NON-NLS-1$
        "вместо", //$NON-NLS-1$
        "перед", //$NON-NLS-1$
        "изменениеиконтроль", //$NON-NLS-1$
        "before", //$NON-NLS-1$
        "after", //$NON-NLS-1$
        "around", //$NON-NLS-1$
        "changeandvalidate"))); //$NON-NLS-1$

    /**
     * Method names the platform or БСП calls by name rather than via a BSL call site, so they legitimately
     * show zero references. Curated, not exhaustive - object/recordset module lifecycle events dominate; a
     * caller can add more via {@code allowlistPatterns}.
     */
    private static final Set<String> KNOWN_HANDLERS;
    static
    {
        TreeSet<String> handlers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(handlers,
            // Object / recordset module lifecycle (platform-called).
            "ПередЗаписью", //$NON-NLS-1$
            "ПриЗаписи", //$NON-NLS-1$
            "ОбработкаПроведения", //$NON-NLS-1$
            "ОбработкаУдаленияПроведения", //$NON-NLS-1$
            "ОбработкаЗаполнения", //$NON-NLS-1$
            "ОбработкаПроверкиЗаполнения", //$NON-NLS-1$
            "ПриКопировании", //$NON-NLS-1$
            "ПриУстановкеНовогоКода", //$NON-NLS-1$
            "ПередУдалением", //$NON-NLS-1$
            "ПриАннулировании", //$NON-NLS-1$
            // Manager-module presentation hooks (platform-called).
            "ОбработкаПолученияПолейПредставления", //$NON-NLS-1$
            "ОбработкаПолученияПредставления", //$NON-NLS-1$
            // Form-module events (relevant only when includeFormModules=true).
            "ПриСоздании", //$NON-NLS-1$
            "ПередОткрытием", //$NON-NLS-1$
            "ПриОткрытии", //$NON-NLS-1$
            "ПриЗакрытии", //$NON-NLS-1$
            "ПриИзменении", //$NON-NLS-1$
            "ПослеЗаписи", //$NON-NLS-1$
            "ПриПолученииДанныхНаСервере", //$NON-NLS-1$
            "ПриПолученииФормы", //$NON-NLS-1$
            "ОбработкаОповещения"); //$NON-NLS-1$
        KNOWN_HANDLERS = Collections.unmodifiableSet(handlers);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Find dead-code candidates: exported BSL methods (across the configuration's modules) that nothing " //$NON-NLS-1$
            + "calls and that are neither a known platform/БСП event handler nor an extension interceptor. EDT's own " //$NON-NLS-1$
            + "module-unused-method check never flags exports, so a dead export goes unnoticed. Output is a heuristic " //$NON-NLS-1$
            + "candidate list - confirm before removing (some methods are dispatched to by name). Read-only; runs under " //$NON-NLS-1$
            + "the generic Pending flow on large configurations."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT project name to scan (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Restrict to one module family by path prefix: commonModules, documents, catalogs, " //$NON-NLS-1$
                    + "informationRegisters, accumulationRegisters, reports, dataProcessors, exchangePlans, " //$NON-NLS-1$
                    + "businessProcesses, tasks, constants, commonCommands, commonForms, webServices, httpServices. " //$NON-NLS-1$
                    + "Default: all families.") //$NON-NLS-1$
            .stringProperty("moduleFilter", //$NON-NLS-1$
                "Keep only modules whose src-relative path contains this text (case-insensitive). " //$NON-NLS-1$
                    + "Example: 'CommonModules/Сведения'.") //$NON-NLS-1$
            .booleanProperty("includeFormModules", //$NON-NLS-1$
                "Also scan form modules. Off by default: form event handlers are platform-called by name and would " //$NON-NLS-1$
                    + "dominate the candidate list. Default: false.") //$NON-NLS-1$
            .stringProperty("allowlistPatterns", //$NON-NLS-1$
                "Comma-separated extra method-name substrings to treat as called-by-name (skip). Use for project- or " //$NON-NLS-1$
                    + "БСП-specific handlers the built-in list does not cover.") //$NON-NLS-1$
            .integerProperty("limit", "Cap on how many candidates the report lists. Default: 100.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("runKey", //$NON-NLS-1$
                "Resume handle returned in a Pending body. Repeating the call with the same parameters (or with this " //$NON-NLS-1$
                    + "runKey) continues waiting for the same scan.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "dead-code-" + projectName.toLowerCase(Locale.ROOT) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return "dead-code.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName must be supplied"; //$NON-NLS-1$
        }
        try
        {
            return scan(projectName, params);
        }
        catch (Exception e)
        {
            return "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()); //$NON-NLS-1$
        }
    }

    // -- = --
    // Scan
    // -- = --

    /**
     * Resolves the project, walks its {@code src} tree for BSL modules, and classifies each export method.
     *
     * @param projectName the project
     * @param params the call params
     * @return the MARKDOWN report
     */
    private String scan(String projectName, Map<String, String> params)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String family = familyOf(metadataType);
        if (family == null)
        {
            return "Error: " + TextSuggest.invalidValue("metadataType", metadataType, FAMILIES); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String pathPrefix = ALL_FAMILIES.equals(family) ? null : family + "/"; //$NON-NLS-1$
        Map<String, String> shown = new java.util.HashMap<>(params);
        shown.put("metadataType", family); //$NON-NLS-1$
        params = shown;
        String moduleFilter = JsonUtils.extractStringArgument(params, "moduleFilter"); //$NON-NLS-1$
        boolean includeForms = JsonUtils.extractBooleanArgument(params, "includeFormModules", false); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> extraPatterns = parsePatterns(JsonUtils.extractStringArgument(params, "allowlistPatterns")); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
        if (limit < 1)
        {
            limit = DEFAULT_LIMIT;
        }

        // The scan runs on the worker thread, so the call's scope is here and its cancel
        // flag can be read at the module boundary - the same place the scan cap stops.
        // Started before the walk that builds the list: on a large tree that walk is
        // itself work the operator may have asked to end.
        WatchForCancel watch = WatchForCancel.begin();
        List<IFile> bslFiles = new ArrayList<>();
        IFolder sourceFolder = project.getFolder("src"); //$NON-NLS-1$
        try
        {
            if (sourceFolder.exists())
            {
                collectBslFiles(sourceFolder, bslFiles, watch);
            }
        }
        catch (CoreException e)
        {
            return "Error: failed to walk the source tree: " + e.getMessage(); //$NON-NLS-1$
        }

        // Methods a written-by-name call reaches: the reference index the deadness check reads
        // carries nothing about them, so they are collected as the scan walks the same modules.
        // A scheduled job's method and an event subscription's handler are strings in the
        // metadata, so the methods they name are collected the same way.
        Map<String, String> commonModules = BslCallGraphHelper.commonModuleNames(project);
        Set<String> nameCalled = new HashSet<>();
        collectJobAndSubscriptionCalled(configurationOf(project), nameCalled);

        FirstPass first = walkSources(bslFiles,
            modulePath -> BslModuleAccess.loadModule(project, modulePath), includeForms, pathPrefix,
            moduleFilter, commonModules, nameCalled, watch);
        Outcome outcome = candidatesOf(first.walked, nameCalled, limit, extraPatterns,
            BslCallGraphHelper::countCallers);

        return format(projectName, outcome, first, limit, params,
            // The count names every boundary the walk asked about - the modules read for their
            // calls as well as the ones the candidates came from.
            watch.note("modules")); //$NON-NLS-1$
    }

    /**
     * Walks the source files of one scan and reads every module the walk caps allow.
     * <p>
     * The methods a written-by-name call names are collected from every module the walk reads,
     * including one whose own exports are no candidates - a form module at the default settings, a
     * module outside the family prefix or the path filter: the calls sit wherever they sit, and the
     * decision about candidates waits for the whole walk anyway, because a module walked after an
     * earlier one can still hold the written-by-name call that keeps that earlier candidate alive.
     * </p>
     * <p>
     * The two walks over the same file list are bounded separately: the candidate cap counts only
     * the modules whose exports the decision examines, and the collection reads - modules read for
     * their name-written calls alone - have their own cap, reported on its own line. Each cap
     * counts the loads the walk attempts, also the ones that fail, so a configuration whose model
     * is not built stops the walk rather than loading every file for nothing. Either cap stops the
     * walk, and an answer a cap stopped does not claim a finished scope.
     * </p>
     *
     * @param files the BSL files of the project, in walk order
     * @param loader the module model behind a {@code src/}-relative path, or {@code null} when it
     *        would not load
     * @param includeForms whether form modules give up their exports as candidates
     * @param pathPrefix the family prefix a candidate module must start with, or {@code null}
     * @param moduleFilter the text a candidate module's path must contain, or {@code null}/empty
     * @param commonModules the project's common modules, lower-cased name to name; {@code null}
     *        collects no call, since a module name cannot be matched
     * @param nameCalled the keys of methods a written-by-name call names, filled
     * @param watch the cancel signal of the call
     * @return what the walk read: the modules whose exports are candidates, and the walk's counts
     */
    static FirstPass walkSources(Iterable<IFile> files,
        java.util.function.Function<String, Module> loader, boolean includeForms, String pathPrefix,
        String moduleFilter, Map<String, String> commonModules, Set<String> nameCalled,
        WatchForCancel watch)
    {
        List<WalkedModule> walked = new ArrayList<>();
        int modulesScanned = 0;
        int collectionReads = 0;
        int modulesExcludedForms = 0;
        int loadFailures = 0;
        boolean stoppedByCandidateCap = false;
        boolean stoppedByCollectionCap = false;
        for (IFile file : files)
        {
            // Asked before anything the file costs, so a cancel that arrived while the previous
            // module was still loading is seen here; the cap checks below never shortcut it.
            if (watch.stopHere())
            {
                break;
            }
            String modulePath = modulePathOf(file);
            if (modulePath == null)
            {
                continue;
            }
            boolean formModule = !includeForms && (modulePath.contains(FORMS_SEGMENT)
                || modulePath.startsWith(COMMON_FORMS_PREFIX));
            boolean candidate = !formModule
                && (pathPrefix == null || modulePath.startsWith(pathPrefix))
                && (moduleFilter == null || moduleFilter.isEmpty()
                    || modulePath.toLowerCase(Locale.ROOT)
                        .contains(moduleFilter.toLowerCase(Locale.ROOT)));
            if (candidate && modulesScanned >= MODULE_SCAN_CAP)
            {
                stoppedByCandidateCap = true;
                break;
            }
            if (!candidate && collectionReads >= NAME_CALL_SCAN_CAP)
            {
                stoppedByCollectionCap = true;
                break;
            }
            Module module = loader.apply(modulePath);
            // The attempt counts against its cap, loaded or not: a configuration whose model is
            // not built fails every load, and a walk that kept counting only successes would load
            // the whole tree for nothing before its cap said stop.
            if (candidate)
            {
                modulesScanned++;
            }
            else
            {
                collectionReads++;
            }
            if (module == null)
            {
                loadFailures++;
                continue;
            }
            collectNameCalled(module, commonModules, nameCalled);
            if (candidate)
            {
                walked.add(new WalkedModule(modulePath, module));
            }
            else if (formModule)
            {
                modulesExcludedForms++;
            }
        }
        // A cancel that arrived during the walk's last load has left no boundary behind it; this
        // one question keeps the note of the stop from staying unwritten, which would read the
        // answer as a finished scan.
        watch.raised();
        return new FirstPass(walked, modulesScanned, collectionReads, modulesExcludedForms,
            loadFailures, stoppedByCandidateCap, stoppedByCollectionCap);
    }

    /**
     * Decides the dead-code candidates of the walked modules.
     * <p>
     * The set of written-by-name calls is complete before this runs, so a method such a call names
     * is dropped here rather than at the end of the scan: it neither becomes a candidate nor
     * occupies the candidate limit, which the answer caps by the candidates it really reports.
     * </p>
     *
     * @param walked the modules the first pass read
     * @param nameCalled the keys of methods a written-by-name call names
     * @param limit the candidate cap
     * @param extraPatterns extra method-name substrings to treat as called-by-name
     * @param callerCount how many call sites reference a method, or a negative number when the
     *        lookup could not run
     * @return the candidates and the counts of the decision
     */
    static Outcome candidatesOf(List<WalkedModule> walked, Set<String> nameCalled, int limit,
        List<String> extraPatterns, java.util.function.ToIntFunction<Method> callerCount)
    {
        List<Candidate> candidates = new ArrayList<>();
        boolean stoppedByLimit = false;
        int exportMethods = 0;
        int handlersSkipped = 0;
        int interceptorsSkipped = 0;
        int indeterminate = 0;
        walk: for (WalkedModule entry : walked)
        {
            for (Method method : entry.module.allMethods())
            {
                if (!method.isExport())
                {
                    continue;
                }
                exportMethods++;
                String name = method.getName();
                if (name == null || name.isEmpty())
                {
                    continue;
                }
                if (isInterceptor(method))
                {
                    interceptorsSkipped++;
                    continue;
                }
                if (KNOWN_HANDLERS.contains(name) || matchesAny(name, extraPatterns))
                {
                    handlersSkipped++;
                    continue;
                }
                if (entry.moduleFqn != null
                    && nameCalled.contains(nameCallKey(entry.moduleFqn, name)))
                {
                    continue;
                }
                int callers = callerCount.applyAsInt(method);
                if (callers < 0)
                {
                    // The reference index/finder was unavailable or the lookup threw - the method's
                    // deadness is indeterminate, NOT confirmed. Skip it so a broken/unbuilt index does
                    // not flag every non-allowlisted export as dead (systematic false positives).
                    indeterminate++;
                    continue;
                }
                if (callers == 0)
                {
                    candidates.add(new Candidate(entry.modulePath, name, startLineOf(method),
                        entry.moduleFqn));
                    if (candidates.size() >= limit)
                    {
                        stoppedByLimit = true;
                        break walk;
                    }
                }
            }
        }
        return new Outcome(candidates, stoppedByLimit, exportMethods, handlersSkipped,
            interceptorsSkipped, indeterminate);
    }

    /**
     * Adds the methods a module's text calls by name to the set of methods the scan must not
     * report: a caller that wrote the name as a string is invisible to the reference index the
     * deadness check reads.
     *
     * @param module the module whose text to read
     * @param commonModules the project's common modules, lower-cased name to name; {@code null}
     *        reads nothing, since a module name cannot be matched
     * @param nameCalled the keys of methods a written-by-name call names, filled
     */
    static void collectNameCalled(Module module, Map<String, String> commonModules,
        Set<String> nameCalled)
    {
        if (commonModules == null)
        {
            return;
        }
        for (BslCallGraphHelper.NameCall call : BslCallGraphHelper.literalNameCallsOf(module,
            commonModules))
        {
            String targetFqn = call.targetModuleFqn != null ? call.targetModuleFqn
                : BslCallGraphHelper.moduleFqn(module);
            if (targetFqn != null)
            {
                nameCalled.add(nameCallKey(targetFqn, call.methodName));
            }
        }
    }

    /**
     * The key a written-by-name call is collected under: the target module's FQN and the method
     * name folded to one case, so a call site spelling the name differently from its declaration
     * still matches.
     *
     * @param moduleFqn the target module's FQN
     * @param methodName the method name as the call spells it
     * @return the key
     */
    static String nameCallKey(String moduleFqn, String methodName)
    {
        return moduleFqn + '\u0000'
            + (methodName == null ? "" : methodName.toLowerCase(Locale.ROOT)); //$NON-NLS-1$
    }

    /**
     * Adds the methods the scheduled jobs and the event subscriptions name to the set of methods
     * the scan must not report: both hold their method reference as a plain string the reference
     * index does not carry.
     *
     * @param configuration the project configuration, or {@code null} when it is not available
     * @param nameCalled the keys of methods a written-by-name call names, filled
     */
    static void collectJobAndSubscriptionCalled(
        com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration, Set<String> nameCalled)
    {
        if (configuration == null)
        {
            return;
        }
        try
        {
            for (Object entry : configuration.getScheduledJobs())
            {
                if (entry instanceof com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob)
                {
                    collectMethodReference(
                        ((com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob)entry).getMethodName(),
                        configuration, nameCalled);
                }
            }
            for (Object entry : configuration.getEventSubscriptions())
            {
                if (entry instanceof com._1c.g5.v8.dt.metadata.mdclass.EventSubscription)
                {
                    collectMethodReference(
                        ((com._1c.g5.v8.dt.metadata.mdclass.EventSubscription)entry).getHandler(),
                        configuration, nameCalled);
                }
            }
        }
        catch (Exception unreadable)
        {
            // An unreadable configuration protects nothing, which is what the scan said before
            // jobs and subscriptions were read at all.
        }
    }

    /**
     * Adds one {@code Module.Method} reference to the collected methods.
     *
     * @param methodReference the string a job's method name or a subscription's handler holds
     * @param configuration the project configuration, for the module's own spelling
     * @param nameCalled the keys of methods a written-by-name call names, filled
     */
    private static void collectMethodReference(String methodReference,
        com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration, Set<String> nameCalled)
    {
        if (methodReference == null || methodReference.isEmpty())
        {
            return;
        }
        BmEventSubscriptionHelper.NormalizationResult parsed =
            BmEventSubscriptionHelper.normalizeHandler(methodReference);
        if (parsed == null)
        {
            return;
        }
        String module = BmEventSubscriptionHelper.commonModuleNamed(configuration,
            parsed.moduleName);
        if (module != null)
        {
            nameCalled.add(nameCallKey("CommonModule." + module + ".Module", parsed.methodName)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The project's configuration, or {@code null} when the provider or the configuration is not
     * available.
     *
     * @param project the project
     * @return its configuration
     */
    private static com._1c.g5.v8.dt.metadata.mdclass.Configuration configurationOf(IProject project)
    {
        try
        {
            return Activator.getDefault().getConfigurationProvider() == null ? null
                : Activator.getDefault().getConfigurationProvider().getConfiguration(project);
        }
        catch (Exception unavailable)
        {
            return null;
        }
    }

    // -- = --
    // Output
    // -- = --

    String format(String projectName, Outcome outcome, FirstPass first, int limit,
        Map<String, String> params, String cancelled)
    {
        List<Candidate> candidates = outcome.candidates;
        StringBuilder out = new StringBuilder();
        out.append("# Dead-code candidates: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("**Candidates:** ").append(candidates.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (cancelled != null)
        {
            // Above the counts, because the counts are what a reader would otherwise take
            // for the whole configuration.
            out.append("**Stopped: ").append(cancelled).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append("- modules scanned: ").append(first.modulesScanned); //$NON-NLS-1$
        out.append(" | export methods examined: ").append(outcome.exportMethods).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (first.collectionReads > 0)
        {
            out.append("- name-written calls collected from ").append(first.collectionReads) //$NON-NLS-1$
                .append(" module(s) outside the candidate scope\n"); //$NON-NLS-1$
        }
        out.append("- skipped: ").append(outcome.handlersSkipped).append(" known handlers, ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(outcome.interceptorsSkipped).append(" interceptors, ") //$NON-NLS-1$
            .append(first.modulesExcludedForms).append(" form modules"); //$NON-NLS-1$
        if (first.loadFailures > 0)
        {
            out.append(", ").append(first.loadFailures).append(" modules failed to load"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (outcome.indeterminate > 0)
        {
            out.append(", ").append(outcome.indeterminate).append(" methods indeterminate (index/finder unavailable - NOT reported as dead; run clean_project and retry)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (first.stoppedByCandidateCap)
        {
            out.append(" (candidate scan cap ").append(MODULE_SCAN_CAP).append(" reached)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append("\n"); //$NON-NLS-1$
        if (first.stoppedByCollectionCap)
        {
            out.append("- name-call collection stopped at its cap (").append(NAME_CALL_SCAN_CAP) //$NON-NLS-1$
                .append(" reached): modules the walk did not reach were not read\n"); //$NON-NLS-1$
        }
        out.append("\n"); //$NON-NLS-1$

        out.append("> Heuristic candidate list, not a verdict. A method is flagged when the Xtext index " //$NON-NLS-1$
            + "reports zero BSL call sites AND its name is not a known platform/БСП event handler AND it " //$NON-NLS-1$
            + "carries no &После/&Вместо/&Перед interceptor pragma. БСП and some configurations dispatch to " //$NON-NLS-1$
            + "exported methods by name via Выполнить / string indirection, which static analysis cannot see - " //$NON-NLS-1$
            + "confirm each candidate before removing it. If the index is not yet built for a module " //$NON-NLS-1$
            + "(freshly opened project), its methods may be under-counted; run clean_project and retry.\n\n"); //$NON-NLS-1$

        out.append("**Filters:** metadataType=").append(orDefault(params.get("metadataType"), "all")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.append(", moduleFilter=").append(orDefault(params.get("moduleFilter"), "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.append(", includeFormModules=") //$NON-NLS-1$
            .append(orDefault(params.get("includeFormModules"), "false")); //$NON-NLS-1$ //$NON-NLS-2$
        if (params.get("allowlistPatterns") != null && !params.get("allowlistPatterns").isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            out.append(", allowlistPatterns=").append(params.get("allowlistPatterns")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append("\n\n"); //$NON-NLS-1$

        if (candidates.isEmpty())
        {
            // Only sayable when the scan finished. Stopped short - by the operator or by a cap -
            // an empty list means the scope was not walked, not that it holds nothing.
            boolean stopped = cancelled != null || first.stoppedByCandidateCap
                || first.stoppedByCollectionCap;
            out.append(stopped
                ? "Nothing had been found when the scan stopped.\n" //$NON-NLS-1$
                : "No dead export methods found in the scanned scope.\n"); //$NON-NLS-1$
            return out.toString();
        }

        out.append("## Candidates (0 callers)\n\n"); //$NON-NLS-1$
        out.append("| Module | Method | Line |\n"); //$NON-NLS-1$
        out.append("|---|---|---|\n"); //$NON-NLS-1$
        for (Candidate c : candidates)
        {
            out.append("| ").append(escape(c.modulePath)).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(escape(c.methodName)).append(" | ") //$NON-NLS-1$
                .append(c.line > 0 ? Integer.toString(c.line) : "-").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (outcome.stoppedByLimit)
        {
            out.append("\n(candidate list capped at ").append(limit) //$NON-NLS-1$
                .append("; raise `limit` or narrow the scope to see more)\n"); //$NON-NLS-1$
        }
        return out.toString();
    }

    // -- = --
    // Helpers
    // -- = --

    /** The value of {@code metadataType} that restricts nothing. */
    static final String ALL_FAMILIES = "all"; //$NON-NLS-1$

    /** The module families {@code metadataType} selects, named as their directories under src/. */
    static final List<String> FAMILIES = List.of("CommonModules", "Documents", "Catalogs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "InformationRegisters", "AccumulationRegisters", "Reports", "DataProcessors", "ExchangePlans", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "BusinessProcesses", "Tasks", "Constants", "CommonCommands", "CommonForms", "WebServices", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "HTTPServices"); //$NON-NLS-1$

    /** Other spellings of a family: the singular English name and the Russian plural. */
    private static final Map<String, String> FAMILY_ALIASES = Map.ofEntries(
        Map.entry("commonmodule", "CommonModules"), Map.entry("общиемодули", "CommonModules"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("document", "Documents"), Map.entry("документы", "Documents"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("catalog", "Catalogs"), Map.entry("справочники", "Catalogs"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("informationregister", "InformationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("регистрысведений", "InformationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("accumulationregister", "AccumulationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("регистрынакопления", "AccumulationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("report", "Reports"), Map.entry("отчеты", "Reports"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("dataprocessor", "DataProcessors"), Map.entry("обработки", "DataProcessors"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("exchangeplan", "ExchangePlans"), Map.entry("планыобмена", "ExchangePlans"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("businessprocess", "BusinessProcesses"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("бизнеспроцессы", "BusinessProcesses"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("task", "Tasks"), Map.entry("задачи", "Tasks"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("constant", "Constants"), Map.entry("константы", "Constants"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("commoncommand", "CommonCommands"), Map.entry("общиекоманды", "CommonCommands"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("commonform", "CommonForms"), Map.entry("общиеформы", "CommonForms"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("webservice", "WebServices"), Map.entry("вебсервисы", "WebServices"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("httpservice", "HTTPServices"), Map.entry("httpсервисы", "HTTPServices")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Names the module family a {@code metadataType} argument selects.
     * <p>
     * Accepted: the family names of {@link #FAMILIES} in any case, their English singular (the
     * form the semantic metadata search takes, such as {@code Catalog}) and their Russian plural.
     * </p>
     *
     * @param metadataType the argument, or {@code null}/empty
     * @return the family as its directory name, {@link #ALL_FAMILIES} when nothing is restricted,
     *         or {@code null} when the value names no family
     */
    static String familyOf(String metadataType)
    {
        if (metadataType == null || metadataType.isBlank() || ALL_FAMILIES.equalsIgnoreCase(metadataType.trim()))
        {
            return ALL_FAMILIES;
        }
        String key = metadataType.trim().toLowerCase(Locale.ROOT);
        for (String family : FAMILIES)
        {
            if (family.toLowerCase(Locale.ROOT).equals(key))
            {
                return family;
            }
        }
        return FAMILY_ALIASES.get(key);
    }

    private static List<String> parsePatterns(String csv)
    {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isEmpty())
        {
            return out;
        }
        for (String token : csv.split("\\s*,\\s*")) //$NON-NLS-1$
        {
            if (!token.isEmpty())
            {
                out.add(token);
            }
        }
        return out;
    }

    private static boolean matchesAny(String name, List<String> patterns)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String p : patterns)
        {
            if (lower.contains(p.toLowerCase(Locale.ROOT)))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isInterceptor(Method method)
    {
        for (Pragma pragma : method.getPragmas())
        {
            String symbol = pragma.getSymbol();
            if (symbol != null && INTERCEPTOR_PRAGMAS.contains(symbol.toLowerCase(Locale.ROOT)))
            {
                return true;
            }
        }
        return false;
    }

    private static int startLineOf(Method method)
    {
        try
        {
            INode node = NodeModelUtils.findActualNodeFor(method);
            if (node != null)
            {
                return node.getStartLine();
            }
        }
        catch (RuntimeException ignored)
        {
            // node model unavailable on this resource - line is cosmetic, fall back.
        }
        return 0;
    }

    private static void collectBslFiles(IFolder folder, List<IFile> out, WatchForCancel watch)
        throws CoreException
    {
        for (IResource member : folder.members())
        {
            // Asked, not counted: building the list is not a module the scan read, and
            // the number the answer reports names modules.
            if (watch.raised())
            {
                return;
            }
            if (member instanceof IFolder)
            {
                collectBslFiles((IFolder)member, out, watch);
            }
            else if (member instanceof IFile && "bsl".equalsIgnoreCase(member.getFileExtension())) //$NON-NLS-1$
            {
                out.add((IFile)member);
            }
        }
    }

    private static String modulePathOf(IFile file)
    {
        String relative = file.getProjectRelativePath().toString();
        int srcIdx = relative.indexOf("src/"); //$NON-NLS-1$
        if (srcIdx < 0)
        {
            return null;
        }
        return relative.substring(srcIdx + 4);
    }

    private static String orDefault(String value, String fallback)
    {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String escape(String cell)
    {
        if (cell == null)
        {
            return ""; //$NON-NLS-1$
        }
        return cell.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** One module the scan read, with the address it was read under. */
    static final class WalkedModule
    {
        final String modulePath;

        final Module module;

        /** The module's FQN as its address names it, or {@code null} when the address names none. */
        final String moduleFqn;

        WalkedModule(String modulePath, Module module)
        {
            this.modulePath = modulePath;
            this.module = module;
            this.moduleFqn = BslCallGraphHelper.moduleFqn(module);
        }
    }

    /** What the walk of the sources read: the modules and the counts of the walk itself. */
    static final class FirstPass
    {
        final List<WalkedModule> walked;

        /** The candidate module loads the walk attempted, whether the module loaded or not. */
        final int modulesScanned;

        /** The loads the walk attempted only to collect name-written calls, loaded or not. */
        final int collectionReads;

        final int modulesExcludedForms;

        final int loadFailures;

        /** Whether the candidate cap stopped the walk before the list of files ran out. */
        final boolean stoppedByCandidateCap;

        /** Whether the name-call collection cap stopped the walk before the list ran out. */
        final boolean stoppedByCollectionCap;

        FirstPass(List<WalkedModule> walked, int modulesScanned, int collectionReads,
            int modulesExcludedForms, int loadFailures, boolean stoppedByCandidateCap,
            boolean stoppedByCollectionCap)
        {
            this.walked = walked;
            this.modulesScanned = modulesScanned;
            this.collectionReads = collectionReads;
            this.modulesExcludedForms = modulesExcludedForms;
            this.loadFailures = loadFailures;
            this.stoppedByCandidateCap = stoppedByCandidateCap;
            this.stoppedByCollectionCap = stoppedByCollectionCap;
        }
    }

    /** The candidates the scan reports, with the counts of the decision that collected them. */
    static final class Outcome
    {
        final List<Candidate> candidates;

        /** Whether the decision stopped because the candidate count reached the limit. */
        final boolean stoppedByLimit;

        final int exportMethods;

        final int handlersSkipped;

        final int interceptorsSkipped;

        final int indeterminate;

        Outcome(List<Candidate> candidates, boolean stoppedByLimit, int exportMethods,
            int handlersSkipped, int interceptorsSkipped, int indeterminate)
        {
            this.candidates = candidates;
            this.stoppedByLimit = stoppedByLimit;
            this.exportMethods = exportMethods;
            this.handlersSkipped = handlersSkipped;
            this.interceptorsSkipped = interceptorsSkipped;
            this.indeterminate = indeterminate;
        }
    }

    /** A dead-code candidate: the module path, the export method name, and its declaration line. */
    static final class Candidate
    {
        final String modulePath;
        final String methodName;
        final int line;

        /** The module's FQN as its address names it, or {@code null} when the address names none. */
        final String moduleFqn;

        Candidate(String modulePath, String methodName, int line, String moduleFqn)
        {
            this.modulePath = modulePath;
            this.methodName = methodName;
            this.line = line;
            this.moduleFqn = moduleFqn;
        }
    }
}
