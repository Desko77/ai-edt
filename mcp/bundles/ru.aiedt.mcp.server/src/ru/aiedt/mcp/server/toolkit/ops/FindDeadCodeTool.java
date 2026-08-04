/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

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

import ru.aiedt.mcp.server.support.BslCallGraphHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
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

        String pathPrefix = resolvePathPrefix(JsonUtils.extractStringArgument(params, "metadataType")); //$NON-NLS-1$
        String moduleFilter = JsonUtils.extractStringArgument(params, "moduleFilter"); //$NON-NLS-1$
        boolean includeForms = JsonUtils.extractBooleanArgument(params, "includeFormModules", false); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> extraPatterns = parsePatterns(JsonUtils.extractStringArgument(params, "allowlistPatterns")); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
        if (limit < 1)
        {
            limit = DEFAULT_LIMIT;
        }

        List<IFile> bslFiles = new ArrayList<>();
        IFolder sourceFolder = project.getFolder("src"); //$NON-NLS-1$
        try
        {
            if (sourceFolder.exists())
            {
                collectBslFiles(sourceFolder, bslFiles);
            }
        }
        catch (CoreException e)
        {
            return "Error: failed to walk the source tree: " + e.getMessage(); //$NON-NLS-1$
        }

        List<Candidate> candidates = new ArrayList<>();
        int modulesScanned = 0;
        int modulesExcludedForms = 0;
        int exportMethods = 0;
        int handlersSkipped = 0;
        int interceptorsSkipped = 0;
        int loadFailures = 0;
        int indeterminate = 0;

        for (IFile file : bslFiles)
        {
            if (modulesScanned >= MODULE_SCAN_CAP)
            {
                break;
            }
            String modulePath = modulePathOf(file);
            if (modulePath == null)
            {
                continue;
            }
            if (!includeForms && (modulePath.contains(FORMS_SEGMENT) || modulePath.startsWith(COMMON_FORMS_PREFIX)))
            {
                modulesExcludedForms++;
                continue;
            }
            if (pathPrefix != null && !modulePath.startsWith(pathPrefix))
            {
                continue;
            }
            if (moduleFilter != null && !moduleFilter.isEmpty()
                && !modulePath.toLowerCase(Locale.ROOT).contains(moduleFilter.toLowerCase(Locale.ROOT)))
            {
                continue;
            }

            Module module = BslModuleAccess.loadModule(project, modulePath);
            if (module == null)
            {
                loadFailures++;
                continue;
            }
            modulesScanned++;

            for (Method method : module.allMethods())
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
                int callers = BslCallGraphHelper.countCallers(method);
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
                    candidates.add(new Candidate(modulePath, name, startLineOf(method)));
                    if (candidates.size() >= limit)
                    {
                        break;
                    }
                }
            }
            if (candidates.size() >= limit)
            {
                break;
            }
        }

        return format(projectName, candidates, modulesScanned, modulesExcludedForms, exportMethods,
            handlersSkipped, interceptorsSkipped, loadFailures, indeterminate, limit, params);
    }

    // -- = --
    // Output
    // -- = --

    private String format(String projectName, List<Candidate> candidates, int modulesScanned,
        int modulesExcludedForms, int exportMethods, int handlersSkipped, int interceptorsSkipped,
        int loadFailures, int indeterminate, int limit, Map<String, String> params)
    {
        StringBuilder out = new StringBuilder();
        out.append("# Dead-code candidates: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("**Candidates:** ").append(candidates.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("- modules scanned: ").append(modulesScanned); //$NON-NLS-1$
        out.append(" | export methods examined: ").append(exportMethods).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("- skipped: ").append(handlersSkipped).append(" known handlers, ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(interceptorsSkipped).append(" interceptors, ") //$NON-NLS-1$
            .append(modulesExcludedForms).append(" form modules"); //$NON-NLS-1$
        if (loadFailures > 0)
        {
            out.append(", ").append(loadFailures).append(" modules failed to load"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (indeterminate > 0)
        {
            out.append(", ").append(indeterminate).append(" methods indeterminate (index/finder unavailable - NOT reported as dead; run clean_project and retry)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (modulesScanned >= MODULE_SCAN_CAP)
        {
            out.append(" (scan cap ").append(MODULE_SCAN_CAP).append(" reached)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append("\n\n"); //$NON-NLS-1$

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
            out.append("No dead export methods found in the scanned scope.\n"); //$NON-NLS-1$
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
        if (candidates.size() >= limit)
        {
            out.append("\n(candidate list capped at ").append(limit) //$NON-NLS-1$
                .append("; raise `limit` or narrow the scope to see more)\n"); //$NON-NLS-1$
        }
        return out.toString();
    }

    // -- = --
    // Helpers
    // -- = --

    /**
     * Maps a metadataType argument to the src-relative path prefix that selects its module family.
     *
     * @param metadataType the argument, or {@code null}/empty
     * @return the prefix (e.g. "CommonModules/"), or {@code null} for all
     */
    private static String resolvePathPrefix(String metadataType)
    {
        if (metadataType == null || metadataType.isEmpty() || "all".equalsIgnoreCase(metadataType)) //$NON-NLS-1$
        {
            return null;
        }
        switch (metadataType.toLowerCase(Locale.ROOT))
        {
            case "commonmodules": return "CommonModules/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "documents": return "Documents/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "catalogs": return "Catalogs/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "informationregisters": return "InformationRegisters/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "accumulationregisters": return "AccumulationRegisters/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "reports": return "Reports/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dataprocessors": return "DataProcessors/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "exchangeplans": return "ExchangePlans/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "businessprocesses": return "BusinessProcesses/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "tasks": return "Tasks/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "constants": return "Constants/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "commoncommands": return "CommonCommands/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "commonforms": return "CommonForms/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "webservice": case "webservices": return "WebServices/"; //$NON-NLS-1$ //$NON-NLS-2$
            case "httpservices": return "HTTPServices/"; //$NON-NLS-1$ //$NON-NLS-2$
            default: return null;
        }
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

    private static void collectBslFiles(IFolder folder, List<IFile> out) throws CoreException
    {
        for (IResource member : folder.members())
        {
            if (member instanceof IFolder)
            {
                collectBslFiles((IFolder)member, out);
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

    /** A dead-code candidate: the module path, the export method name, and its declaration line. */
    private static final class Candidate
    {
        final String modulePath;
        final String methodName;
        final int line;

        Candidate(String modulePath, String methodName, int line)
        {
            this.modulePath = modulePath;
            this.methodName = methodName;
            this.line = line;
        }
    }
}
