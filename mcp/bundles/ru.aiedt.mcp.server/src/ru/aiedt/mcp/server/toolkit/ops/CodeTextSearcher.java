/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.ToolParamSettings;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * Searches the text of every BSL module in a project, plainly or by regex, and answers with the
 * matches and their surroundings, a bare count, or a per-file tally.
 * <p>
 * No model is loaded: the file bytes are read and scanned, so the search works on a project the index
 * has not caught up with. A per-file pre-filter skips a file whose whole text cannot match before the
 * line-by-line scan begins, which is what makes an unfiltered search over a large configuration
 * bearable. A soft wall-clock budget bounds that search - when it runs out the partial results come
 * back under a banner rather than nothing at all.
 * </p>
 */
public class CodeTextSearcher
    implements IMcpTool
{
    private static final int MAX_RESULTS_CAP = 500;

    private static final int CONTEXT_LINES_CAP = 5;

    private static final int TIMEOUT_MIN_SECONDS = 5;

    private static final int TIMEOUT_MAX_SECONDS = 120;

    private static final int TOP_FILES = 5;

    @Override
    public String getName()
    {
        return "search_in_code"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `code_search` `operation=text_search`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Full-text search across all BSL modules of a project - literal or regex, with " //$NON-NLS-1$
            + "context lines, case sensitivity, and file/metadata-type filters. " //$NON-NLS-1$
            + "Use outputMode 'count' or 'files' for a cheap probe before a full search. " //$NON-NLS-1$
            + "wholeWord=true matches whole tokens only (avoids 'КурсыВалют' matching 'КурсыВалютРасчетов'). " //$NON-NLS-1$
            + "compact=true trims large result sets to the first N hits plus summary stats. " //$NON-NLS-1$
            + "On large configurations, narrow with metadataType or fileMask rather than raising timeoutSeconds."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "The EDT project to search in (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("query", "Literal text or a regex pattern to look for (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("caseSensitive", "Whether letter case must match exactly. Default: false") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("isRegex", "Treat query as a regular expression rather than literal text. Default: false") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Ceiling on how many matches come back with context. Default: 100, max: 500") //$NON-NLS-1$
            .integerProperty("contextLines", //$NON-NLS-1$
                "Number of surrounding lines to include before/after each match. Default: 2, max: 5") //$NON-NLS-1$
            .integerProperty("linesBefore", //$NON-NLS-1$
                "Lines shown BEFORE a match (asymmetric context). Falls back to contextLines when omitted. " //$NON-NLS-1$
                    + "Pair with linesAfter for grep -B/-A style output.") //$NON-NLS-1$
            .integerProperty("linesAfter", //$NON-NLS-1$
                "Lines shown AFTER a match (asymmetric context). Falls back to contextLines when omitted.") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "Keep only modules whose path contains this substring (e.g. 'CommonModules' or 'Documents/SalesOrder')") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Narrows the search to one metadata kind: 'documents', 'catalogs', 'commonModules', " //$NON-NLS-1$
                    + "'informationRegisters', 'accumulationRegisters', 'reports', 'dataProcessors', " //$NON-NLS-1$
                    + "'exchangePlans', 'businessProcesses', 'tasks', 'constants', 'commonCommands', " //$NON-NLS-1$
                    + "'commonForms', 'webServices', 'httpServices'. Tighter and more dependable than fileMask.") //$NON-NLS-1$
            .stringProperty("outputMode", //$NON-NLS-1$
                "Controls the shape of the reply: 'full' returns matches with context (the default), 'count' " //$NON-NLS-1$
                    + "gives just the total (fastest), 'files' lists per-file hit counts with no context") //$NON-NLS-1$
            .booleanProperty("wholeWord", //$NON-NLS-1$
                "Limit hits to whole tokens - 'КурсыВалют' will not match 'КурсыВалютРасчетов'. " //$NON-NLS-1$
                    + "Wraps the query (or regex) in word boundaries (\\b...\\b). Default: false.") //$NON-NLS-1$
            .booleanProperty("compact", //$NON-NLS-1$
                "When outputMode=full, trims the reply to the first maxResults matches plus " //$NON-NLS-1$
                    + "summary statistics and the busiest 5 files by hit count. Useful for large result sets " //$NON-NLS-1$
                    + "when context budget is tight. Default: false.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Soft wall-clock budget for the file scan, in seconds (default 25, range 5-120). On a " //$NON-NLS-1$
                    + "very large configuration (thousands of objects) an unfiltered search can outrun it; " //$NON-NLS-1$
                    + "the tool then returns whatever partial results it gathered, with a hint to narrow " //$NON-NLS-1$
                    + "via metadataType / fileMask. Narrowing the search beats raising this value.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$
        if (query == null || query.isEmpty())
        {
            return "search-results.md"; //$NON-NLS-1$
        }
        String safe = query.replaceAll("[^a-zA-Z0-9\\u0400-\\u04ff]", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        if (safe.length() > 40)
        {
            safe = safe.substring(0, 40);
        }
        return "search-" + safe + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (query == null || query.isEmpty())
        {
            return "Error: query is required"; //$NON-NLS-1$
        }

        boolean caseSensitive = JsonUtils.extractBooleanArgument(params, "caseSensitive", false); //$NON-NLS-1$
        boolean isRegex = JsonUtils.extractBooleanArgument(params, "isRegex", false); //$NON-NLS-1$
        boolean wholeWord = JsonUtils.extractBooleanArgument(params, "wholeWord", false); //$NON-NLS-1$
        boolean compact = JsonUtils.extractBooleanArgument(params, "compact", false); //$NON-NLS-1$

        ToolParamSettings settings = ToolParamSettings.getInstance();
        int maxResults = JsonUtils.extractIntArgument(params, "maxResults", //$NON-NLS-1$
            settings.getParameterValue("search_in_code", "maxResults", 100)); //$NON-NLS-1$ //$NON-NLS-2$
        int contextLines = JsonUtils.extractIntArgument(params, "contextLines", //$NON-NLS-1$
            settings.getParameterValue("search_in_code", "contextLines", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        // Asymmetric context (RSV 5.0 -B/-A parity). Default each side to the symmetric
        // contextLines so the wire contract is unchanged when neither is passed.
        int linesBefore = JsonUtils.extractIntArgument(params, "linesBefore", contextLines); //$NON-NLS-1$
        int linesAfter = JsonUtils.extractIntArgument(params, "linesAfter", contextLines); //$NON-NLS-1$

        String fileMask = JsonUtils.extractStringArgument(params, "fileMask"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String outputMode = JsonUtils.extractStringArgument(params, "outputMode"); //$NON-NLS-1$

        int timeoutSeconds = JsonUtils.extractIntArgument(params, "timeoutSeconds", 25); //$NON-NLS-1$
        timeoutSeconds = Math.max(TIMEOUT_MIN_SECONDS, Math.min(TIMEOUT_MAX_SECONDS, timeoutSeconds));

        if (outputMode == null || outputMode.isEmpty())
        {
            outputMode = "full"; //$NON-NLS-1$
        }
        outputMode = outputMode.toLowerCase(Locale.ROOT);
        if (!"full".equals(outputMode) && !"count".equals(outputMode) && !"files".equals(outputMode)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return "Error: outputMode must be one of 'full', 'count', or 'files'"; //$NON-NLS-1$
        }

        maxResults = Math.max(1, Math.min(MAX_RESULTS_CAP, maxResults));
        contextLines = Math.max(0, Math.min(CONTEXT_LINES_CAP, contextLines));
        linesBefore = Math.max(0, Math.min(CONTEXT_LINES_CAP, linesBefore));
        linesAfter = Math.max(0, Math.min(CONTEXT_LINES_CAP, linesAfter));

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        Pattern pattern;
        try
        {
            int flags = Pattern.UNICODE_CHARACTER_CLASS;
            if (!caseSensitive)
            {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            String effective = isRegex ? query : Pattern.quote(query);
            if (wholeWord)
            {
                effective = "\\b(?:" + effective + ")\\b"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            pattern = Pattern.compile(effective, flags);
        }
        catch (PatternSyntaxException e)
        {
            return "Error: failed to compile the regex pattern '" + query + "': " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String metadataFolderPrefix = null;
        if (metadataType != null && !metadataType.isEmpty())
        {
            metadataFolderPrefix = resolveMetadataFolder(metadataType);
            if (metadataFolderPrefix == null)
            {
                return "Error: metadataType '" + metadataType //$NON-NLS-1$
                    + "' is not recognized. Accepted values: documents, catalogs, commonModules, informationRegisters, " //$NON-NLS-1$
                    + "accumulationRegisters, reports, dataProcessors, exchangePlans, " //$NON-NLS-1$
                    + "businessProcesses, tasks, constants, commonCommands, commonForms, " //$NON-NLS-1$
                    + "webServices, httpServices"; //$NON-NLS-1$
            }
        }

        boolean collectDetails = "full".equals(outputMode); //$NON-NLS-1$
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        // The scan runs inline on the worker thread, so the call's scope is present here and the
        // visitor can watch its cancellation flag to bail out early when the operator cancels.
        ToolCallScope scope = ToolCallScope.current();
        ToolCallScope.Cancellation cancellation = scope != null ? scope.cancellation() : null;
        SearchCollector collector = new SearchCollector(pattern, fileMask, metadataFolderPrefix,
            collectDetails, maxResults, linesBefore, linesAfter, deadline, cancellation);

        IResource src = project.findMember("src"); //$NON-NLS-1$
        if (src == null || !src.exists())
        {
            return "Error: no src/ folder found in project " + projectName; //$NON-NLS-1$
        }
        try
        {
            src.accept(collector);
        }
        catch (OperationCanceledException e)
        {
            // Soft timeout or an operator cancel - the partial results collected so far are kept.
        }
        catch (CoreException e)
        {
            return "Error: the project search failed: " + e.getMessage(); //$NON-NLS-1$
        }

        // RSV 5.9 parity: an exact literal search that finds nothing, for a query of
        // several whitespace-separated tokens, gets ONE whitespace-flexible retry
        // (tokens rejoined with \s+). Catches copy-pasted snippets whose line breaks
        // or indentation differ from the on-disk source. Not applied to regex/wholeWord.
        boolean relaxedRetry = false;
        boolean retryCutShort = false;
        if (collector.totalMatches == 0 && !collector.cancelled && !isRegex && !wholeWord
            && hasFlexibleWhitespace(query))
        {
            Pattern relaxed = buildWhitespaceFlexPattern(query, caseSensitive);
            if (relaxed != null)
            {
                SearchCollector retry = new SearchCollector(relaxed, fileMask, metadataFolderPrefix,
                    collectDetails, maxResults, linesBefore, linesAfter, deadline, cancellation);
                boolean retryInterrupted = false;
                try
                {
                    src.accept(retry);
                }
                catch (OperationCanceledException | CoreException e)
                {
                    retryInterrupted = true;
                }
                if (retry.totalMatches > 0)
                {
                    collector = retry;
                    relaxedRetry = true;
                }
                else if (retry.cancelled)
                {
                    // Cancelled mid-retry: surface the cancel banner, not a time-budget one.
                    collector.cancelled = true;
                }
                else if (retry.timedOut || retryInterrupted)
                {
                    // Retry found nothing but did not finish - a plain "no matches" would hide
                    // that the flexible-whitespace pass was cut short by the time budget
                    // (codex MEDIUM).
                    retryCutShort = true;
                }
            }
        }

        String formatted;
        if ("count".equals(outputMode)) //$NON-NLS-1$
        {
            formatted = formatCountOutput(query, collector);
        }
        else if ("files".equals(outputMode)) //$NON-NLS-1$
        {
            formatted = formatFilesOutput(query, collector);
        }
        else
        {
            formatted = formatFullOutput(query, collector, compact);
        }

        if (relaxedRetry)
        {
            formatted = "_No exact match; retried with flexible whitespace (\\\\s+ between tokens) " //$NON-NLS-1$
                + "and this is what came up._\n\n" + formatted; //$NON-NLS-1$
        }
        else if (retryCutShort)
        {
            formatted = "_No exact match; a flexible-whitespace retry started but was cut short " //$NON-NLS-1$
                + "by the time budget - narrow the query or metadataType and try again._\n\n" + formatted; //$NON-NLS-1$
        }

        if (collector.cancelled)
        {
            return "_Search cancelled by the operator after scanning " + collector.scannedFiles //$NON-NLS-1$
                + " file(s); the matches below are what had been found so far._\n\n" + formatted; //$NON-NLS-1$
        }
        if (collector.timedOut)
        {
            boolean filtered = (fileMask != null && !fileMask.isEmpty()) || metadataFolderPrefix != null;
            return timeoutBanner(timeoutSeconds, collector.scannedFiles, filtered) + formatted;
        }
        return formatted;
    }

    /** True when the query splits into 2+ whitespace-separated tokens (a retry candidate). */
    private static boolean hasFlexibleWhitespace(String query)
    {
        return query != null && query.trim().split("\\s+").length >= 2; //$NON-NLS-1$
    }

    /**
     * Builds a pattern that matches the query's tokens in order, each literal-quoted, rejoined
     * with {@code \s+} so any run of whitespace (spaces, tabs, line breaks) between tokens matches.
     * Returns {@code null} for a single-token query or a malformed pattern.
     */
    private static Pattern buildWhitespaceFlexPattern(String query, boolean caseSensitive)
    {
        String[] parts = query.trim().split("\\s+"); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            if (i > 0)
            {
                sb.append("\\s+"); //$NON-NLS-1$
            }
            sb.append(Pattern.quote(parts[i]));
        }
        int flags = Pattern.UNICODE_CHARACTER_CLASS;
        if (!caseSensitive)
        {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        try
        {
            return Pattern.compile(sb.toString(), flags);
        }
        catch (PatternSyntaxException e)
        {
            return null;
        }
    }

    /**
     * Maps a metadata-type token to the folder under {@code src/} its objects live in.
     *
     * @param metadataType the token, in any case
     * @return the folder prefix ending in {@code /}, or <code>null</code> when the token is not one
     *         this filter accepts
     */
    private static String resolveMetadataFolder(String metadataType)
    {
        switch (metadataType.toLowerCase(Locale.ROOT))
        {
        case "documents": //$NON-NLS-1$
            return "Documents/"; //$NON-NLS-1$
        case "catalogs": //$NON-NLS-1$
            return "Catalogs/"; //$NON-NLS-1$
        case "commonmodules": //$NON-NLS-1$
            return "CommonModules/"; //$NON-NLS-1$
        case "informationregisters": //$NON-NLS-1$
            return "InformationRegisters/"; //$NON-NLS-1$
        case "accumulationregisters": //$NON-NLS-1$
            return "AccumulationRegisters/"; //$NON-NLS-1$
        case "reports": //$NON-NLS-1$
            return "Reports/"; //$NON-NLS-1$
        case "dataprocessors": //$NON-NLS-1$
            return "DataProcessors/"; //$NON-NLS-1$
        case "exchangeplans": //$NON-NLS-1$
            return "ExchangePlans/"; //$NON-NLS-1$
        case "businessprocesses": //$NON-NLS-1$
            return "BusinessProcesses/"; //$NON-NLS-1$
        case "tasks": //$NON-NLS-1$
            return "Tasks/"; //$NON-NLS-1$
        case "constants": //$NON-NLS-1$
            return "Constants/"; //$NON-NLS-1$
        case "commoncommands": //$NON-NLS-1$
            return "CommonCommands/"; //$NON-NLS-1$
        case "commonforms": //$NON-NLS-1$
            return "CommonForms/"; //$NON-NLS-1$
        case "webservices": //$NON-NLS-1$
            return "WebServices/"; //$NON-NLS-1$
        case "httpservices": //$NON-NLS-1$
            return "HTTPServices/"; //$NON-NLS-1$
        case "enums": //$NON-NLS-1$
            return "Enums/"; //$NON-NLS-1$
        case "chartsofcharacteristictypes": //$NON-NLS-1$
            return "ChartsOfCharacteristicTypes/"; //$NON-NLS-1$
        case "chartsofaccounts": //$NON-NLS-1$
            return "ChartsOfAccounts/"; //$NON-NLS-1$
        case "chartsofcalculationtypes": //$NON-NLS-1$
            return "ChartsOfCalculationTypes/"; //$NON-NLS-1$
        default:
            return null;
        }
    }

    /**
     * Formats the count-only answer.
     *
     * @param query the query, for the heading
     * @param collector the finished collector
     * @return the report
     */
    private static String formatCountOutput(String query, SearchCollector collector)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## Hit Count for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("**Total hits:** ").append(collector.totalMatches).append(" across **") //$NON-NLS-1$ //$NON-NLS-2$
            .append(collector.totalMatchedFiles).append("** modules\n"); //$NON-NLS-1$
        if (collector.skippedFiles > 0)
        {
            builder.append("**Caution:** ").append(collector.skippedFiles) //$NON-NLS-1$
                .append(" file(s) were skipped as unreadable\n"); //$NON-NLS-1$
        }
        if (collector.wasInterrupted)
        {
            builder.append("**Caution:** the search was cut short, so results may be incomplete\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Formats the per-file tally answer.
     *
     * @param query the query, for the heading
     * @param collector the finished collector
     * @return the report
     */
    private static String formatFilesOutput(String query, SearchCollector collector)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## Matched Modules for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("**Total hits:** ").append(collector.totalMatches).append(" across **") //$NON-NLS-1$ //$NON-NLS-2$
            .append(collector.totalMatchedFiles).append("** modules\n\n"); //$NON-NLS-1$
        if (collector.skippedFiles > 0)
        {
            builder.append("**Caution:** ").append(collector.skippedFiles) //$NON-NLS-1$
                .append(" file(s) were skipped as unreadable\n\n"); //$NON-NLS-1$
        }
        if (collector.wasInterrupted)
        {
            builder.append("**Caution:** the search was cut short, so results may be incomplete\n\n"); //$NON-NLS-1$
        }
        if (collector.matchCountByFile.isEmpty())
        {
            builder.append("Nothing matched.\n"); //$NON-NLS-1$
            return builder.toString();
        }
        builder.append("| Module | Hits |\n"); //$NON-NLS-1$
        builder.append("|------|---------|\n"); //$NON-NLS-1$
        for (Map.Entry<String, Integer> entry : collector.matchCountByFile.entrySet())
        {
            builder.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Formats the full answer, with the surrounding lines of each match.
     *
     * @param query the query, for the heading
     * @param collector the finished collector
     * @param compact whether to trim to statistics plus the top files
     * @return the report
     */
    private static String formatFullOutput(String query, SearchCollector collector, boolean compact)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## Search Hits for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("**Overall:** ").append(collector.totalMatches).append(" hits across ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(collector.totalMatchedFiles).append(" modules"); //$NON-NLS-1$
        if (collector.collectedMatches < collector.totalMatches)
        {
            builder.append(" (limited to the first ").append(collector.collectedMatches).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n"); //$NON-NLS-1$
        if (collector.skippedFiles > 0)
        {
            builder.append("**Caution:** ").append(collector.skippedFiles) //$NON-NLS-1$
                .append(" file(s) were skipped as unreadable\n"); //$NON-NLS-1$
        }
        if (collector.wasInterrupted)
        {
            builder.append("**Caution:** the search was cut short, so results may be incomplete\n"); //$NON-NLS-1$
        }
        builder.append("\n"); //$NON-NLS-1$

        if (collector.totalMatches == 0)
        {
            builder.append("Nothing matched.\n"); //$NON-NLS-1$
            return builder.toString();
        }

        if (compact)
        {
            builder.append(buildTopFilesTable(collector));
            if (collector.collectedMatches < collector.totalMatches)
            {
                builder.append("_compact mode: stopped at the first ").append(collector.collectedMatches) //$NON-NLS-1$
                    .append(" hits with context. Raise `maxResults` (max 500) or narrow the " //$NON-NLS-1$
                        + "query (metadataType, fileMask, wholeWord) for a fuller view._\n\n"); //$NON-NLS-1$
            }
        }

        for (Map.Entry<String, List<MatchInfo>> entry : collector.matchesByFile.entrySet())
        {
            builder.append("### ").append(entry.getKey()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (MatchInfo match : entry.getValue())
            {
                builder.append("**At line ").append(match.lineNumber).append(":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                builder.append("```bsl\n"); //$NON-NLS-1$
                for (String contextLine : match.contextLines)
                {
                    builder.append(contextLine).append("\n"); //$NON-NLS-1$
                }
                builder.append("```\n\n"); //$NON-NLS-1$
            }
        }
        return builder.toString();
    }

    /**
     * Builds the "top files by match count" table for compact mode.
     *
     * @param collector the finished collector
     * @return the table block
     */
    private static String buildTopFilesTable(SearchCollector collector)
    {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(collector.matchCountByFile.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int count = Math.min(TOP_FILES, entries.size());
        StringBuilder builder = new StringBuilder();
        builder.append("**Top ").append(count).append(" files by hit count:**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("| Module | Hits |\n"); //$NON-NLS-1$
        builder.append("|------|---------|\n"); //$NON-NLS-1$
        for (int i = 0; i < count; i++)
        {
            Map.Entry<String, Integer> entry = entries.get(i);
            builder.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" |\n"); //$NON-NLS-1$
        }
        builder.append("\n"); //$NON-NLS-1$
        return builder.toString();
    }

    /**
     * Builds the partial-results banner prepended when the soft budget was spent.
     *
     * @param timeoutSeconds the budget that was spent
     * @param scannedFiles how many files had been scanned
     * @param filtered whether a fileMask or metadataType was already narrowing the search
     * @return the banner
     */
    private static String timeoutBanner(int timeoutSeconds, int scannedFiles, boolean filtered)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("> [!] SCAN CUT SHORT - stopped after the ").append(timeoutSeconds) //$NON-NLS-1$
            .append("s soft time limit (~").append(scannedFiles).append(" files scanned so far). "); //$NON-NLS-1$ //$NON-NLS-2$
        if (filtered)
        {
            builder.append("Narrow the filter further, try outputMode=count/files first, or raise " //$NON-NLS-1$
                + "timeoutSeconds (up to 120).\n\n"); //$NON-NLS-1$
        }
        else
        {
            builder.append("On a big configuration, scope the search down with metadataType (e.g. " //$NON-NLS-1$
                + "commonModules, documents) or fileMask, try outputMode=count/files first, or raise " //$NON-NLS-1$
                + "timeoutSeconds (up to 120).\n\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Splits raw file text into lines, handling every line ending and dropping the empty line a
     * trailing newline would otherwise add - the same lines {@code readFileLines} would give.
     *
     * @param content the file text with separators preserved
     * @return the lines without separators
     */
    private static List<String> splitIntoLines(String content)
    {
        List<String> lines = new ArrayList<>();
        int length = content.length();
        int start = 0;
        int i = 0;
        while (i < length)
        {
            char symbol = content.charAt(i);
            if (symbol == '\n')
            {
                lines.add(content.substring(start, i));
                i++;
                start = i;
            }
            else if (symbol == '\r')
            {
                lines.add(content.substring(start, i));
                i++;
                if (i < length && content.charAt(i) == '\n')
                {
                    i++;
                }
                start = i;
            }
            else
            {
                i++;
            }
        }
        if (start < length)
        {
            lines.add(content.substring(start));
        }
        return lines;
    }

    /** One match: the line it is on, and the window of lines around it. */
    private static final class MatchInfo
    {
        final int lineNumber;

        final List<String> contextLines;

        MatchInfo(int lineNumber, List<String> contextLines)
        {
            this.lineNumber = lineNumber;
            this.contextLines = contextLines;
        }
    }

    /**
     * The resource visitor that walks {@code src/}, filters, scans, and accumulates every statistic
     * the three output modes read back.
     */
    private final class SearchCollector
        implements IResourceVisitor
    {
        private final Pattern pattern;

        private final String fileMask;

        private final String metadataFolderPrefix;

        private final boolean collectDetails;

        private final int maxResults;

        private final int linesBefore;

        private final int linesAfter;

        private final long deadline;

        private final ToolCallScope.Cancellation cancellation;

        int totalMatches;

        int totalMatchedFiles;

        int scannedFiles;

        int skippedFiles;

        int collectedMatches;

        boolean wasInterrupted;

        boolean timedOut;

        boolean cancelled;

        final Map<String, Integer> matchCountByFile = new LinkedHashMap<>();

        final Map<String, List<MatchInfo>> matchesByFile = new LinkedHashMap<>();

        SearchCollector(Pattern pattern, String fileMask, String metadataFolderPrefix,
            boolean collectDetails, int maxResults, int linesBefore, int linesAfter, long deadline,
            ToolCallScope.Cancellation cancellation)
        {
            this.pattern = pattern;
            this.fileMask = fileMask;
            this.metadataFolderPrefix = metadataFolderPrefix;
            this.collectDetails = collectDetails;
            this.maxResults = maxResults;
            this.linesBefore = linesBefore;
            this.linesAfter = linesAfter;
            this.deadline = deadline;
            this.cancellation = cancellation;
        }

        @Override
        public boolean visit(IResource resource)
        {
            if (wasInterrupted || Thread.currentThread().isInterrupted())
            {
                wasInterrupted = true;
                return false;
            }
            if (cancellation != null && cancellation.isCancelled())
            {
                // The operator cancelled: stop the scan at this file boundary and keep whatever was
                // found so far, the same way the time budget does. One check per resource is enough.
                cancelled = true;
                throw new OperationCanceledException();
            }
            if (System.nanoTime() - deadline > 0)
            {
                timedOut = true;
                throw new OperationCanceledException();
            }
            if (resource.getType() != IResource.FILE)
            {
                return true;
            }
            IFile file = (IFile)resource;
            if (!"bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
            {
                return true;
            }

            String relative = resource.getProjectRelativePath().toString();
            String displayPath = relative.startsWith("src/") ? relative.substring(4) : relative; //$NON-NLS-1$

            if (fileMask != null && !fileMask.isEmpty()
                && !displayPath.toLowerCase(Locale.ROOT).contains(fileMask.toLowerCase(Locale.ROOT)))
            {
                return true;
            }
            if (metadataFolderPrefix != null && !displayPath.startsWith(metadataFolderPrefix))
            {
                return true;
            }

            scannedFiles++;
            try
            {
                searchInFile(file, displayPath);
            }
            catch (Exception e)
            {
                skippedFiles++;
                Activator.logWarning("search_in_code could not read " + displayPath + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage());
            }
            return true;
        }

        /**
         * Scans one file, first skipping it whole when nothing can match, then line by line.
         *
         * @param file the file
         * @param displayPath its src-relative path
         * @throws Exception when the file cannot be read
         */
        private void searchInFile(IFile file, String displayPath) throws Exception
        {
            String content = BslModuleAccess.readFileText(file);
            if (!pattern.matcher(content).find())
            {
                return;
            }
            List<String> lines = splitIntoLines(content);
            int fileMatches = 0;
            for (int i = 0; i < lines.size(); i++)
            {
                if (!pattern.matcher(lines.get(i)).find())
                {
                    continue;
                }
                totalMatches++;
                fileMatches++;
                if (collectDetails && collectedMatches < maxResults)
                {
                    int from = Math.max(0, i - linesBefore);
                    int to = Math.min(lines.size() - 1, i + linesAfter);
                    List<String> window = new ArrayList<>();
                    for (int j = from; j <= to; j++)
                    {
                        window.add((j + 1) + ": " + lines.get(j)); //$NON-NLS-1$
                    }
                    matchesByFile.computeIfAbsent(displayPath, key -> new ArrayList<>())
                        .add(new MatchInfo(i + 1, window));
                    collectedMatches++;
                }
            }
            if (fileMatches > 0)
            {
                totalMatchedFiles++;
                matchCountByFile.put(displayPath, fileMatches);
            }
        }
    }
}
