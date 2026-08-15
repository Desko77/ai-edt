/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.ToolParamSettings;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.session.SessionChangeTracker;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Reports the validation problems EDT holds, filtered and scoped to what an agent actually asked
 * about.
 * <p>
 * The severity argument is read as a group, not a single level: {@code ERROR} covers the four levels
 * an agent means by "error", and each wider group adds the next one down. Scope decides how much is
 * scanned - only what the session touched, only named objects, the project, or everything - and a
 * compact mode folds a large set into per-check and per-location counts. Under the EDT markers a
 * second pass picks up plain Eclipse problem markers the model does not carry.
 * </p>
 */
public class ProjectProblemsReader
    implements IMcpTool
{
    private static final int LIMIT_MIN = 1;

    private static final int LIMIT_MAX = 1000;

    private static final int DEFAULT_LIMIT = 100;

    private static final int COMPACT_SCAN_CAP = 5000;

    private static final int PROJECT_SUMMARY_THRESHOLD = 200;

    private static final int REFRESH_ATTEMPTS = 3;

    private static final long REFRESH_SLEEP_MS = 300;

    private static final long READY_WAIT_MS = 60000;

    private static final int DEDUP_MESSAGE_LENGTH = 80;

    private static final int EXAMPLE_LENGTH = 100;

    private static final int TOP_LOCATIONS = 20;

    @Override
    public String getName()
    {
        return "get_project_errors"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `diagnostics` `operation=get_project_errors`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Get configuration problems from EDT, each with its check code, description, object " //$NON-NLS-1$
            + "location, and severity. severity accepts group levels (ERROR is the default, returning " //$NON-NLS-1$
            + "ERRORS+BLOCKER+CRITICAL+MAJOR; WARNING adds MINOR; INFO adds TRIVIAL; ALL removes the filter) " //$NON-NLS-1$
            + "or the concrete EDT levels themselves (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL, " //$NON-NLS-1$
            + "NONE). A typo such as 'ERORR' raises a clear error instead of silently returning everything. " //$NON-NLS-1$
            + "Scope (default 'session') limits the scan to files touched in the current MCP session - " //$NON-NLS-1$
            + "handy right after write_module_source / edit_metadata. Each finding carries the " //$NON-NLS-1$
            + "line it sits on where the check reports one; the cell is empty for a finding on " //$NON-NLS-1$
            + "an object as a whole. scope=object needs 'objects'; " //$NON-NLS-1$
            + "scope=project / scope=all scan everything (scope=project auto-summarizes past 200 markers). " //$NON-NLS-1$
            + "Results can be filtered to specific objects by FQN (e.g. 'Document.SalesOrder', 'Catalog.Products'). " //$NON-NLS-1$
            + "Russian type names work too (e.g. '\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041F\u0440\u0438\u0445\u043E\u0434\u043D\u0430\u044F\u041D\u0430\u043A\u043B\u0430\u0434\u043D\u0430\u044F', " //$NON-NLS-1$
            + "'\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430'). compact=true collapses the result into grouped counts (per checkId " //$NON-NLS-1$
            + "and per location, one representative message each) instead of one row per marker - reach for " //$NON-NLS-1$
            + "it on large error sets."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Restrict results to this project (optional). Also accepted as 'project'. " //$NON-NLS-1$
                    + "Omitted, the answer covers the whole workspace - and a workspace where a " //$NON-NLS-1$
                    + "configuration and its extension carry the same object names is exactly " //$NON-NLS-1$
                    + "where that matters.") //$NON-NLS-1$
            .stringProperty("severity", //$NON-NLS-1$
                "Severity filter. Group levels (recommended): ERROR (the default - " //$NON-NLS-1$
                    + "ERRORS+BLOCKER+CRITICAL+MAJOR), WARNING (adds MINOR), INFO (adds TRIVIAL), ALL " //$NON-NLS-1$
                    + "(no filtering). Concrete native levels are accepted too: ERRORS, BLOCKER, CRITICAL, " //$NON-NLS-1$
                    + "MAJOR, MINOR, TRIVIAL, NONE.") //$NON-NLS-1$
            .stringProperty("checkId", //$NON-NLS-1$
                "Keep only checks whose id contains this substring (e.g. 'ql-temp-table-index') (optional)") //$NON-NLS-1$
            .stringArrayProperty("objects", //$NON-NLS-1$
                "Keep only these object FQNs (e.g. ['Document.SalesOrder', 'Catalog.Products']); Russian " //$NON-NLS-1$
                    + "type names work too (e.g. '\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041F\u0440\u043E\u0434\u0430\u0436\u0430\u0422\u043E\u0432\u0430\u0440\u043E\u0432'). Limits the result to " //$NON-NLS-1$
                    + "these objects, and implies scope=object when set.") //$NON-NLS-1$
            .integerProperty("limit", "Cap on the number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", //$NON-NLS-1$
                "Marker scope: 'session' (default - only files this MCP session has touched, tracked " //$NON-NLS-1$
                    + "internally), 'object' (needs 'objects'), 'project' (every marker in the " //$NON-NLS-1$
                    + "project, auto-summarized past 200), 'all' (every open project). Passing " //$NON-NLS-1$
                    + "'objects' implies scope=object. Note: a full revalidation (revalidate_objects " //$NON-NLS-1$
                    + "with no objects given) or clean_project marks the entire project as changed, so " //$NON-NLS-1$
                    + "scope=session right afterward reports the whole project - use scope=object with " //$NON-NLS-1$
                    + "explicit FQNs to narrow it down.") //$NON-NLS-1$
            .stringProperty("fileFilter", //$NON-NLS-1$
                "Substring filter on the marker's location text, applied on top of scope. E.g. " //$NON-NLS-1$
                    + "'CommonModule.Common' to keep only common modules.") //$NON-NLS-1$
            .booleanProperty("waitForRefresh", //$NON-NLS-1$
                "If true (the default), poll the marker stream up to 3x300ms after an empty first " //$NON-NLS-1$
                    + "read, giving EDT time to publish freshly-computed markers. Turn off on very " //$NON-NLS-1$
                    + "large projects when speed matters more than freshness.") //$NON-NLS-1$
            .booleanProperty("compact", //$NON-NLS-1$
                "If true (default false), returns a grouped summary instead of one row per marker: a " //$NON-NLS-1$
                    + "'by check' table (checkId, count, one sample message) and a 'top locations' " //$NON-NLS-1$
                    + "table (location, count). Aggregates across up to 5000 markers and " //$NON-NLS-1$
                    + "bypasses the scope=project past-200 summarizer. Best when a flat list would be " //$NON-NLS-1$
                    + "unreadable.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            // "project" is what a caller reaches for, and an unrecognised name was simply dropped:
            // the answer then covered the whole workspace and looked like a filtered one. Measured
            // on the demo stand - 3 findings under projectName, 32 under project.
            projectName = JsonUtils.extractStringArgument(params, "project"); //$NON-NLS-1$
        }
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        List<String> objects = parseObjectsList(JsonUtils.extractStringArgument(params, "objects")); //$NON-NLS-1$
        String scope = JsonUtils.extractStringArgument(params, "scope"); //$NON-NLS-1$
        String fileFilter = JsonUtils.extractStringArgument(params, "fileFilter"); //$NON-NLS-1$
        boolean waitForRefresh = JsonUtils.extractBooleanArgument(params, "waitForRefresh", true); //$NON-NLS-1$
        boolean compact = JsonUtils.extractBooleanArgument(params, "compact", false); //$NON-NLS-1$

        if (projectName != null && !projectName.isEmpty())
        {
            String gate = waitForRefresh ? ProjectStateGuard.checkReadyOrWait(projectName, READY_WAIT_MS)
                : ProjectStateGuard.checkReadyOrError(projectName);
            if (gate != null)
            {
                // The one place this markdown tool answers with a JSON error object.
                return ToolResult.error(gate).toJson();
            }
        }

        String resolvedScope =
            scope == null || scope.isEmpty() ? (objects.isEmpty() ? "session" : "object") //$NON-NLS-1$ //$NON-NLS-2$
                : scope.toLowerCase(Locale.ROOT);

        int configured = ToolParamSettings.getInstance()
            .getParameterValue("get_project_errors", "limit", DEFAULT_LIMIT); //$NON-NLS-1$ //$NON-NLS-2$
        int limit = JsonUtils.extractIntArgument(params, "limit", configured); //$NON-NLS-1$
        limit = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, limit));

        return getProjectErrors(projectName, severity, checkId, objects, limit, resolvedScope, fileFilter,
            waitForRefresh, compact);
    }

    /**
     * The full nine-argument entry point.
     *
     * @param projectName the project to restrict to, or <code>null</code>/empty for all
     * @param severity the severity filter argument
     * @param checkId a check id substring to keep, or <code>null</code>/empty for any
     * @param objects the object FQNs to keep
     * @param limit the most rows to return, already clamped
     * @param scope the resolved scope
     * @param fileFilter a presentation substring to keep, or <code>null</code>/empty for any
     * @param waitForRefresh whether to poll after an empty first read
     * @param compact whether to fold the result into grouped counts
     * @return the markdown report, or a {@code # Error} document
     */
    public static String getProjectErrors(String projectName, String severity, String checkId,
        List<String> objects, int limit, String scope, String fileFilter, boolean waitForRefresh,
        boolean compact)
    {
        try
        {
            Activator activator = Activator.getDefault();
            IMarkerManager markerManager = activator == null ? null : activator.getMarkerManager();
            if (markerManager == null)
            {
                return "# Request Failed\n\nThe IMarkerManager service is unavailable"; //$NON-NLS-1$
            }
            ICheckRepository checkRepository = activator == null ? null : activator.getCheckRepository();

            Set<MarkerSeverity> severityFilter;
            try
            {
                severityFilter = parseSeverityFilter(severity);
            }
            catch (IllegalArgumentException e)
            {
                return "# Request Failed\n\n" + e.getMessage(); //$NON-NLS-1$
            }

            if (projectName != null && !projectName.isEmpty()
                && !ResourcesPlugin.getWorkspace().getRoot().getProject(projectName).exists())
            {
                return "# Request Failed\n\n" + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
            }

            String resolvedScope = scope == null ? "all" : scope.toLowerCase(Locale.ROOT); //$NON-NLS-1$

            boolean sessionScope = "session".equals(resolvedScope); //$NON-NLS-1$
            if (sessionScope && SessionChangeTracker.size() == 0)
            {
                return "# No Session Changes\n\nscope=session is set, but no files have been touched in the " //$NON-NLS-1$
                    + "current MCP session yet.\n\nUse scope=project to scan the whole project, or write a " //$NON-NLS-1$
                    + "module first via write_module_source / edit_metadata."; //$NON-NLS-1$
            }

            Set<String> sessionFqns = sessionScope ? buildSessionFqns() : new HashSet<>();
            Set<String> objectFqns = new HashSet<>();
            for (String fqn : objects)
            {
                objectFqns.addAll(MetadataTypeCatalog.getAllFqnVariants(fqn));
            }

            Filter filter =
                new Filter(projectName, severityFilter, checkId, objectFqns, sessionFqns, sessionScope, fileFilter);

            int collectLimit = compact ? Math.max(limit, COMPACT_SCAN_CAP) : limit;

            List<ErrorInfo> collected = collectMarkers(markerManager, checkRepository, filter, collectLimit);
            if (collected.isEmpty() && waitForRefresh)
            {
                for (int attempt = 0; attempt < REFRESH_ATTEMPTS; attempt++)
                {
                    try
                    {
                        Thread.sleep(REFRESH_SLEEP_MS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    collected = collectMarkers(markerManager, checkRepository, filter, collectLimit);
                    if (!collected.isEmpty())
                    {
                        break;
                    }
                }
            }

            if (collected.size() < collectLimit)
            {
                collectEclipseMarkers(collected, targetProjects(projectName), filter, collectLimit);
            }

            if (compact)
            {
                return formatCompact(collected, resolvedScope, projectName, collectLimit);
            }
            if ("project".equals(resolvedScope) && collected.size() >= limit) //$NON-NLS-1$
            {
                long total = countProjectMarkers(markerManager, projectName);
                if (total > PROJECT_SUMMARY_THRESHOLD)
                {
                    return "# Too Many Project Markers\n\n**Total markers in project:** " + total //$NON-NLS-1$
                        + "\n**Returned:** " + collected.size() + " (capped)\n\nNarrow the scope: pass " //$NON-NLS-1$ //$NON-NLS-2$
                        + "`objects=[...]`, `checkId=...`, or `scope=session` to see only this session's " //$NON-NLS-1$
                        + "changes."; //$NON-NLS-1$
                }
            }
            return formatMarkers(collected, resolvedScope, projectName, severity, objects, limit);
        }
        catch (Exception e)
        {
            return "# Request Failed\n\nCould not collect project errors: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Five-argument legacy overload. Scope becomes {@code object} when objects are given and
     * {@code all} otherwise - note {@code all}, not {@code session}.
     *
     * @param projectName the project to restrict to
     * @param severity the severity filter argument
     * @param checkId a check id substring to keep
     * @param objects the object FQNs to keep
     * @param limit the most rows to return
     * @return the markdown report
     */
    public static String getProjectErrors(String projectName, String severity, String checkId,
        List<String> objects, int limit)
    {
        String scope = objects != null && !objects.isEmpty() ? "object" : "all"; //$NON-NLS-1$ //$NON-NLS-2$
        return getProjectErrors(projectName, severity, checkId, objects == null ? new ArrayList<>() : objects,
            limit, scope, null, false, false);
    }

    /**
     * Eight-argument legacy overload; compact is off.
     *
     * @param projectName the project to restrict to
     * @param severity the severity filter argument
     * @param checkId a check id substring to keep
     * @param objects the object FQNs to keep
     * @param limit the most rows to return
     * @param scope the scope
     * @param fileFilter a presentation substring to keep
     * @param waitForRefresh whether to poll after an empty first read
     * @return the markdown report
     */
    public static String getProjectErrors(String projectName, String severity, String checkId,
        List<String> objects, int limit, String scope, String fileFilter, boolean waitForRefresh)
    {
        return getProjectErrors(projectName, severity, checkId, objects, limit, scope, fileFilter,
            waitForRefresh, false);
    }

    /**
     * Parses the {@code objects} argument, a JSON array of strings.
     *
     * @param raw the raw argument value
     * @return the FQNs, never <code>null</code>, empty when the argument is missing or not an array
     */
    private static List<String> parseObjectsList(String raw)
    {
        List<String> result = new ArrayList<>();
        if (raw == null)
        {
            return result;
        }
        String value = raw.trim();
        if (value.isEmpty())
        {
            return result;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(value);
            if (parsed.isJsonArray())
            {
                for (JsonElement element : parsed.getAsJsonArray())
                {
                    if (element.isJsonPrimitive())
                    {
                        result.add(element.getAsString());
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Not a JSON array; leave the list empty.
        }
        return result;
    }

    /**
     * Turns the severity argument into the set of levels it stands for.
     *
     * @param severity the severity argument
     * @return the levels to keep, or <code>null</code> for no filter
     * @throws IllegalArgumentException when the value is neither a group nor a native level
     */
    private static Set<MarkerSeverity> parseSeverityFilter(String severity)
    {
        Set<MarkerSeverity> errorGroup = EnumSet.of(MarkerSeverity.ERRORS, MarkerSeverity.BLOCKER,
            MarkerSeverity.CRITICAL, MarkerSeverity.MAJOR);
        if (severity == null || severity.isEmpty())
        {
            return errorGroup;
        }
        switch (severity.toUpperCase(Locale.ROOT))
        {
        case "ERROR": //$NON-NLS-1$
            return errorGroup;
        case "WARNING": //$NON-NLS-1$
            errorGroup.add(MarkerSeverity.MINOR);
            return errorGroup;
        case "INFO": //$NON-NLS-1$
            errorGroup.add(MarkerSeverity.MINOR);
            errorGroup.add(MarkerSeverity.TRIVIAL);
            return errorGroup;
        case "ALL": //$NON-NLS-1$
            return null;
        default:
            try
            {
                return EnumSet.of(MarkerSeverity.valueOf(severity.toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException e)
            {
                throw new IllegalArgumentException("Unrecognized severity '" + severity //$NON-NLS-1$
                    + "'. Use a group level (ERROR, WARNING, INFO, ALL) or one of the concrete native levels " //$NON-NLS-1$
                    + "(ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL, NONE)."); //$NON-NLS-1$
            }
        }
    }

    /**
     * Builds the set of FQN variants of the objects the session touched.
     *
     * @return the lowercased FQN variants, never <code>null</code>
     */
    private static Set<String> buildSessionFqns()
    {
        Set<String> fqns = new HashSet<>();
        for (String path : SessionChangeTracker.getModifiedPaths())
        {
            String[] parts = path.split("/"); //$NON-NLS-1$
            int srcIndex = -1;
            for (int i = 0; i < parts.length; i++)
            {
                if ("src".equals(parts[i])) //$NON-NLS-1$
                {
                    srcIndex = i;
                    break;
                }
            }
            if (srcIndex < 0 || srcIndex + 2 >= parts.length)
            {
                continue;
            }
            String type = MetadataTypeCatalog.getTypeByDirectoryName(parts[srcIndex + 1]);
            if (type == null)
            {
                continue;
            }
            fqns.addAll(MetadataTypeCatalog.getAllFqnVariants(type + "." + parts[srcIndex + 2])); //$NON-NLS-1$
        }
        return fqns;
    }

    /**
     * Collects the EDT markers that pass the filter, up to the limit.
     *
     * @param markerManager the marker manager
     * @param checkRepository the check repository, for resolving short ids
     * @param filter the predicate
     * @param collectLimit the most to collect
     * @return the collected rows
     */
    private static List<ErrorInfo> collectMarkers(IMarkerManager markerManager,
        ICheckRepository checkRepository, Filter filter, int collectLimit)
    {
        List<ErrorInfo> result = new ArrayList<>();
        // The check-id filter runs on the mapped row, not the raw marker: only the
        // row carries the resolved symbolic id, and testing the same value the
        // report prints is what keeps the filter and the grouped view in step.
        markerManager.markers()
            .filter(filter::matchesEdt)
            .map(marker -> toErrorInfo(marker, checkRepository))
            .filter(error -> filter.matchesCheckIdentity(error.checkId, error.checkCode))
            .limit(collectLimit)
            .forEach(result::add);
        return result;
    }

    /**
     * Maps an EDT marker to a report row.
     *
     * @param marker the marker
     * @param checkRepository the check repository, for resolving the short id
     * @return the row
     */
    private static ErrorInfo toErrorInfo(Marker marker, ICheckRepository checkRepository)
    {
        String checkCode = marker.getCheckId();
        String resolvedCheckId = resolveCheckId(checkRepository, checkCode, marker.getProject());
        boolean hasDocumentation = CheckDocReader.hasCheckDocumentation(resolvedCheckId);
        MarkerSeverity severity = marker.getSeverity();
        String severityNative = severity != null ? severity.name() : MarkerSeverity.NONE.name();
        // The position lives in the marker's extra info, not on the marker itself. Without it a
        // module with eight findings of one check can only be compared by count - which tells a
        // caller that its own rule is wrong somewhere, and nothing about where.
        int line = -1;
        int charStart = -1;
        int charEnd = -1;
        try
        {
            IExtraInfoMap extra = marker.getExtraInfo();
            if (extra != null)
            {
                line = extra.getIntOrDefault(StandardExtraInfo.TEXT_LINE, -1);
                charStart = extra.getIntOrDefault(StandardExtraInfo.TEXT_OFFSET, -1);
                int length = extra.getIntOrDefault(StandardExtraInfo.TEXT_LENGTH, -1);
                charEnd = charStart >= 0 && length >= 0 ? charStart + length : -1;
            }
        }
        catch (RuntimeException e)
        {
            // A marker whose extra info will not read is still worth reporting without a position;
            // losing the finding entirely would be the worse trade.
            line = -1;
        }
        return new ErrorInfo(checkCode, resolvedCheckId, hasDocumentation, marker.getMessage(),
            marker.getObjectPresentation(), severityNative, line, charStart, charEnd);
    }

    /**
     * Resolves a marker's short check id to its symbolic id.
     *
     * @param checkRepository the check repository; may be <code>null</code>
     * @param shortUid the short id
     * @param project the project the marker belongs to
     * @return the symbolic check id, or <code>null</code> when it cannot be resolved
     */
    private static String resolveCheckId(ICheckRepository checkRepository, String shortUid, IProject project)
    {
        if (checkRepository == null || shortUid == null || shortUid.isEmpty() || project == null)
        {
            return null;
        }
        try
        {
            CheckUid uid = checkRepository.getUidForShortUid(shortUid, project);
            return uid != null ? uid.getCheckId() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Appends Eclipse problem markers that the EDT pass did not already cover.
     *
     * @param collected the rows so far, appended in place
     * @param projects the projects to scan
     * @param filter the predicate
     * @param collectLimit the most to hold
     */
    private static void collectEclipseMarkers(List<ErrorInfo> collected, IProject[] projects, Filter filter,
        int collectLimit)
    {
        Set<String> seen = new HashSet<>();
        for (ErrorInfo info : collected)
        {
            seen.add(dedupKey(info.objectPresentation, info.message));
        }

        for (IProject project : projects)
        {
            if (collected.size() >= collectLimit)
            {
                return;
            }
            if (project == null || !project.isOpen())
            {
                continue;
            }
            IMarker[] markers;
            try
            {
                markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            }
            catch (CoreException e)
            {
                continue;
            }
            for (IMarker marker : markers)
            {
                if (collected.size() >= collectLimit)
                {
                    return;
                }
                MarkerSeverity mapped = mapEclipseSeverity(
                    marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO));
                String sourceId = marker.getAttribute(IMarker.SOURCE_ID, ""); //$NON-NLS-1$
                String presentation = buildEclipsePresentation(marker);
                if (!filter.matchesEclipse(mapped, sourceId, presentation))
                {
                    continue;
                }
                String message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                String key = dedupKey(presentation, message);
                if (!seen.add(key))
                {
                    continue;
                }
                boolean hasDocumentation = CheckDocReader.hasCheckDocumentation(sourceId);
                // An Eclipse marker keeps its position in the standard attributes rather than in
                // extra info; -1 is what getAttribute answers when the attribute is absent, which
                // is the same "no position" the EDT path reports.
                collected.add(new ErrorInfo(sourceId, null, hasDocumentation, message, presentation,
                    mapped.name(), marker.getAttribute(IMarker.LINE_NUMBER, -1),
                    marker.getAttribute(IMarker.CHAR_START, -1),
                    marker.getAttribute(IMarker.CHAR_END, -1)));
            }
        }
    }

    /**
     * Maps an Eclipse severity to the closest EDT level.
     *
     * @param eclipseSeverity one of the {@link IMarker} severities
     * @return {@code ERRORS}, {@code MINOR} or {@code TRIVIAL}
     */
    private static MarkerSeverity mapEclipseSeverity(int eclipseSeverity)
    {
        if (eclipseSeverity == IMarker.SEVERITY_ERROR)
        {
            return MarkerSeverity.ERRORS;
        }
        if (eclipseSeverity == IMarker.SEVERITY_WARNING)
        {
            return MarkerSeverity.MINOR;
        }
        return MarkerSeverity.TRIVIAL;
    }

    /**
     * Builds a presentation for an Eclipse marker: its location, else path and line, else path.
     *
     * @param marker the marker
     * @return the presentation
     */
    private static String buildEclipsePresentation(IMarker marker)
    {
        String location = marker.getAttribute(IMarker.LOCATION, ""); //$NON-NLS-1$
        if (location != null && !location.isEmpty())
        {
            return location;
        }
        String resourcePath = marker.getResource().getFullPath().toString();
        int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
        if (line >= 0)
        {
            return resourcePath + ":" + line; //$NON-NLS-1$
        }
        return resourcePath;
    }

    /**
     * Builds the key two markers are considered the same by.
     *
     * @param presentation the object presentation
     * @param message the message
     * @return the dedup key
     */
    private static String dedupKey(String presentation, String message)
    {
        String left = presentation == null ? "" : presentation.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String right = message == null ? "" : message.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (right.length() > DEDUP_MESSAGE_LENGTH)
        {
            right = right.substring(0, DEDUP_MESSAGE_LENGTH);
        }
        return left + "::" + right; //$NON-NLS-1$
    }

    /**
     * @param projectName the project to restrict to, or <code>null</code>/empty for all
     * @return the projects the Eclipse pass scans
     */
    private static IProject[] targetProjects(String projectName)
    {
        if (projectName != null && !projectName.isEmpty())
        {
            return new IProject[] {ResourcesPlugin.getWorkspace().getRoot().getProject(projectName)};
        }
        return ResourcesPlugin.getWorkspace().getRoot().getProjects();
    }

    /**
     * Counts every EDT marker of the target project(s).
     *
     * @param markerManager the marker manager
     * @param projectName the project to restrict to, or <code>null</code>/empty for all
     * @return the total marker count
     */
    private static long countProjectMarkers(IMarkerManager markerManager, String projectName)
    {
        return markerManager.markers().filter(marker -> {
            IProject project = marker.getProject();
            if (project == null)
            {
                return false;
            }
            return projectName == null || projectName.isEmpty() || project.getName().equals(projectName);
        }).count();
    }

    /**
     * Renders the rows as one line per marker.
     *
     * @param errors the rows
     * @param resolvedScope the scope, for the header
     * @param projectName the project filter, for the empty message
     * @param severity the severity argument, for the empty message
     * @param objects the object filter, for the empty message
     * @param limit the limit, for the "limited" note
     * @return the markdown
     */
    private static String formatMarkers(List<ErrorInfo> errors, String resolvedScope, String projectName,
        String severity, List<String> objects, int limit)
    {
        if (errors.isEmpty())
        {
            StringBuilder builder = new StringBuilder("# Nothing Found\n\n"); //$NON-NLS-1$
            if (resolvedScope != null && !resolvedScope.isEmpty())
            {
                builder.append("Scope: **").append(resolvedScope).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (projectName != null && !projectName.isEmpty())
            {
                builder.append("Project: **").append(projectName).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (severity != null && !severity.isEmpty())
            {
                builder.append("Severity: ").append(severity).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!objects.isEmpty())
            {
                builder.append("Objects: ").append(String.join(", ", objects)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            builder.append("\nNothing matches the given criteria."); //$NON-NLS-1$
            return builder.toString();
        }

        StringBuilder builder = new StringBuilder("# Configuration Issues\n\n"); //$NON-NLS-1$
        builder.append("**Scope:** ").append(scopeOrAll(resolvedScope)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("**Found:** ").append(errors.size()); //$NON-NLS-1$
        if (errors.size() >= limit)
        {
            builder.append("+ (capped at ").append(limit).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n\n"); //$NON-NLS-1$
        builder.append("| Message | Where | Line | Severity | Check | Docs |\n"); //$NON-NLS-1$
        builder.append("|-------------|----------|------|----------|------------|----------|\n"); //$NON-NLS-1$
        for (ErrorInfo error : errors)
        {
            String displayCheckId =
                error.checkId != null && !error.checkId.isEmpty() ? error.checkId : error.checkCode;
            builder.append("| ").append(MarkdownTableHelper.escapeForTable(error.message)) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(error.objectPresentation)) //$NON-NLS-1$
                .append(" | ").append(error.line >= 0 ? Integer.toString(error.line) : "") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | ").append(error.severityNative) //$NON-NLS-1$
                .append(" | `").append(displayCheckId).append("` | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(error.hasDocumentation ? "true" : "false") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Renders the rows as per-check and per-location counts.
     *
     * @param errors the rows
     * @param resolvedScope the scope, for the header
     * @param projectName the project filter, for the header
     * @param scanCap the collection cap, for the "cap reached" note
     * @return the markdown
     */
    private static String formatCompact(List<ErrorInfo> errors, String resolvedScope, String projectName,
        int scanCap)
    {
        if (errors.isEmpty())
        {
            return formatMarkers(errors, resolvedScope, projectName, null, new ArrayList<>(), 0);
        }

        Map<String, Integer> checkCounts = new HashMap<>();
        Map<String, String> checkExamples = new HashMap<>();
        Map<String, Integer> locationCounts = new HashMap<>();
        for (ErrorInfo error : errors)
        {
            String check = checkKey(error);
            checkCounts.merge(check, 1, Integer::sum);
            checkExamples.putIfAbsent(check, error.message);
            String location = error.objectPresentation != null && !error.objectPresentation.isEmpty()
                ? error.objectPresentation : "(unlocated)"; //$NON-NLS-1$
            locationCounts.merge(location, 1, Integer::sum);
        }

        StringBuilder builder = new StringBuilder("# Configuration Issues (compact)\n\n"); //$NON-NLS-1$
        builder.append("**Scope:** ").append(scopeOrAll(resolvedScope)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (projectName != null && !projectName.isEmpty())
        {
            builder.append("**In project:** ").append(projectName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("**Total markers:** ").append(errors.size()); //$NON-NLS-1$
        if (errors.size() >= scanCap)
        {
            builder.append("+ (hit the aggregation cap of ").append(scanCap) //$NON-NLS-1$
                .append("; counts below are a lower bound)"); //$NON-NLS-1$
        }
        builder.append("\n\n"); //$NON-NLS-1$

        builder.append("**Grouped by check (").append(checkCounts.size()).append("):**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("| Check | Count | Sample |\n"); //$NON-NLS-1$
        builder.append("|-------|-------|---------|\n"); //$NON-NLS-1$
        List<Map.Entry<String, Integer>> checkList = new ArrayList<>(checkCounts.entrySet());
        checkList.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        for (Map.Entry<String, Integer> entry : checkList)
        {
            builder.append("| `").append(entry.getKey()).append("` | ").append(entry.getValue()) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(truncate(checkExamples.get(entry.getKey())))) //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
        }
        builder.append("\n"); //$NON-NLS-1$

        int locationCount = locationCounts.size();
        builder.append("**Busiest ").append(Math.min(TOP_LOCATIONS, locationCount)).append(" of ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(locationCount).append(" locations:**\n\n"); //$NON-NLS-1$
        builder.append("| Where | Count |\n"); //$NON-NLS-1$
        builder.append("|----------|-------|\n"); //$NON-NLS-1$
        List<Map.Entry<String, Integer>> locationList = new ArrayList<>(locationCounts.entrySet());
        locationList.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        int shown = Math.min(TOP_LOCATIONS, locationList.size());
        for (int i = 0; i < shown; i++)
        {
            Map.Entry<String, Integer> entry = locationList.get(i);
            builder.append("| ").append(MarkdownTableHelper.escapeForTable(entry.getKey())).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(entry.getValue()).append(" |\n"); //$NON-NLS-1$
        }
        builder.append("\n_compact mode shows grouped counts only. Drop compact, or pass checkId / " //$NON-NLS-1$
            + "objects, to see individual markers._\n"); //$NON-NLS-1$
        return builder.toString();
    }

    /**
     * The aggregation key of a row: its symbolic id, then its short code, then a placeholder.
     *
     * @param error the row
     * @return the key
     */
    private static String checkKey(ErrorInfo error)
    {
        if (error.checkId != null && !error.checkId.isEmpty())
        {
            return error.checkId;
        }
        if (error.checkCode != null && !error.checkCode.isEmpty())
        {
            return error.checkCode;
        }
        return "(unidentified check)"; //$NON-NLS-1$
    }

    /**
     * @param resolvedScope the scope
     * @return the scope, or {@code all} when it is empty
     */
    private static String scopeOrAll(String resolvedScope)
    {
        return resolvedScope == null || resolvedScope.isEmpty() ? "all" : resolvedScope; //$NON-NLS-1$
    }

    /**
     * Flattens a message to one line and caps its length.
     *
     * @param text the message
     * @return the flattened, capped text, never <code>null</code>
     */
    private static String truncate(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        String flattened = text.replace("\n", " ").replace("\r", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (flattened.length() > EXAMPLE_LENGTH)
        {
            return flattened.substring(0, EXAMPLE_LENGTH) + "..."; //$NON-NLS-1$
        }
        return flattened;
    }

    /** One problem, flattened to what the report shows. */
    private static final class ErrorInfo
    {
        private final String checkCode;

        private final String checkId;

        private final boolean hasDocumentation;

        private final String message;

        private final String objectPresentation;

        private final String severityNative;

        /**
         * The line the finding sits on, or {@code -1} when it has none.
         * <p>
         * Not every finding has one: a check that fires on an object as a whole has nothing to
         * point at inside a module. Absent is reported as an empty cell rather than as line zero,
         * because a caller reconciling its own findings against ours would otherwise read the
         * zero as a real position and go looking at the top of the file.
         * </p>
         */
        private final int line;

        /** Character offset of the finding, or {@code -1}. */
        private final int charStart;

        /** Character offset just past the finding, or {@code -1}. */
        private final int charEnd;

        ErrorInfo(String checkCode, String checkId, boolean hasDocumentation, String message,
            String objectPresentation, String severityNative)
        {
            this(checkCode, checkId, hasDocumentation, message, objectPresentation, severityNative,
                -1, -1, -1);
        }

        ErrorInfo(String checkCode, String checkId, boolean hasDocumentation, String message,
            String objectPresentation, String severityNative, int line, int charStart, int charEnd)
        {
            this.checkCode = checkCode;
            this.checkId = checkId;
            this.hasDocumentation = hasDocumentation;
            this.message = message;
            this.objectPresentation = objectPresentation;
            this.severityNative = severityNative;
            this.line = line;
            this.charStart = charStart;
            this.charEnd = charEnd;
        }
    }

    /** The marker predicate, holding every filter the two channels share. */
    static final class Filter
    {
        private final String projectName;

        private final Set<MarkerSeverity> severityFilter;

        private final String checkId;

        private final Set<String> objectFqns;

        private final Set<String> sessionFqns;

        private final boolean sessionScope;

        private final String fileFilter;

        Filter(String projectName, Set<MarkerSeverity> severityFilter, String checkId,
            Set<String> objectFqns, Set<String> sessionFqns, boolean sessionScope, String fileFilter)
        {
            this.projectName = projectName;
            this.severityFilter = severityFilter;
            this.checkId = checkId;
            this.objectFqns = objectFqns;
            this.sessionFqns = sessionFqns;
            this.sessionScope = sessionScope;
            this.fileFilter = fileFilter;
        }

        boolean matchesEdt(Marker marker)
        {
            IProject project = marker.getProject();
            if (project == null)
            {
                return false;
            }
            if (projectName != null && !projectName.isEmpty() && !project.getName().equals(projectName))
            {
                return false;
            }
            if (severityFilter != null)
            {
                MarkerSeverity severity = marker.getSeverity();
                if (severity == null || !severityFilter.contains(severity))
                {
                    return false;
                }
            }
            return matchesPresentation(marker.getObjectPresentation());
        }

        boolean matchesEclipse(MarkerSeverity mapped, String sourceId, String presentation)
        {
            if (severityFilter != null && !severityFilter.contains(mapped))
            {
                return false;
            }
            if (!matchesCheckIdentity(null, sourceId))
            {
                return false;
            }
            return matchesPresentation(presentation);
        }

        /**
         * Applies the {@code checkId} filter to a row's check identity.
         * <p>
         * An EDT marker carries a short opaque uid; the symbolic id the report
         * prints ({@code common-module-type}) is resolved from it separately.
         * Matching only the uid made the filter reject every marker the grouped
         * view had just listed by symbolic id, so both spellings are accepted -
         * whichever the caller copied out of a previous report.
         *
         * @param symbolicId the resolved symbolic check id; may be <code>null</code>
         * @param shortCode the marker's own check code; may be <code>null</code>
         * @return <code>true</code> when the row survives the filter
         */
        boolean matchesCheckIdentity(String symbolicId, String shortCode)
        {
            if (checkId == null || checkId.isEmpty())
            {
                return true;
            }
            String needle = checkId.toLowerCase(Locale.ROOT);
            return contains(symbolicId, needle) || contains(shortCode, needle);
        }

        private static boolean contains(String candidate, String lowercaseNeedle)
        {
            return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
        }

        private boolean matchesPresentation(String presentation)
        {
            String presLower = presentation == null ? "" : presentation.toLowerCase(Locale.ROOT); //$NON-NLS-1$
            if (!objectFqns.isEmpty() && !containsAny(presLower, objectFqns))
            {
                return false;
            }
            if (sessionScope && !containsAny(presLower, sessionFqns))
            {
                return false;
            }
            if (fileFilter != null && !fileFilter.isEmpty()
                && !presLower.contains(fileFilter.toLowerCase(Locale.ROOT)))
            {
                return false;
            }
            return true;
        }

        private static boolean containsAny(String haystack, Set<String> needles)
        {
            for (String needle : needles)
            {
                if (haystack.contains(needle))
                {
                    return true;
                }
            }
            return false;
        }
    }
}
