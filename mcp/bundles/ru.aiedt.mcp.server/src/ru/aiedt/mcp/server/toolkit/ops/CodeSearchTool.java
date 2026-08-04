/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified code search facade with nine operations.
 *
 * <p>Weak LLMs route to the wrong tool when {@code search_in_code},
 * {@code find_references}, {@code go_to_definition},
 * {@code get_method_call_hierarchy}, {@code get_symbol_info},
 * {@code get_content_assist} and {@code get_outgoing_structures} live as
 * separate top-level tools. The facade collapses them under one name with an
 * explicit {@code operation} switch:
 * <ul>
 *   <li>{@code text_search} - full-text search (delegates to
 *       {@link CodeTextSearcher})</li>
 *   <li>{@code object_references} - all references to a metadata object
 *       (delegates to {@link ReferenceLocator})</li>
 *   <li>{@code method_references} - all references to a specific method
 *       (delegates to {@link CodeTextSearcher} with a method-name pattern)</li>
 *   <li>{@code resolve_symbol} - go-to-definition for a method
 *       (delegates to {@link DefinitionNavigator})</li>
 *   <li>{@code call_hierarchy} - incoming / outgoing call tree
 *       (delegates to {@link CallHierarchyReader})</li>
 *   <li>{@code symbol_info} - type / hover at a code position
 *       (delegates to {@link SymbolInfoReader})</li>
 *   <li>{@code content_assist} - completion proposals at a position
 *       (delegates to {@link ContentAssistReader})</li>
 *   <li>{@code outgoing_structures} - a metadata object's outbound references
 *       (delegates to {@link OutgoingStructuresReader})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>The standalone tools stay registered for back-compat - the facade is
 * additive. Callers who already wrote prompts against the old names keep
 * working, new prompts can target {@code code_search} for a single,
 * unambiguous entry point.
 */
public class CodeSearchTool implements IMcpTool
{
    public static final String NAME = "code_search"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Unified code search across nine operations - " //$NON-NLS-1$
            + "text_search (full-text), object_references (metadata FQN refs), " //$NON-NLS-1$
            + "method_references (refs to a specific method), resolve_symbol " //$NON-NLS-1$
            + "(go-to-definition), call_hierarchy (incoming / outgoing call tree), " //$NON-NLS-1$
            + "symbol_info (type / hover at a code position), content_assist " //$NON-NLS-1$
            + "(completion proposals at a position), outgoing_structures (a metadata " //$NON-NLS-1$
            + "object's outbound references), help (topic-driven). Pass " //$NON-NLS-1$
            + "operation=<name> (snake_case canonical; camelCase like textSearch is " //$NON-NLS-1$
            + "also accepted); remaining parameters follow the per-operation contracts. " //$NON-NLS-1$
            + "The standalone tools (search_in_code, find_references, go_to_definition, " //$NON-NLS-1$
            + "get_method_call_hierarchy, get_symbol_info, get_content_assist, " //$NON-NLS-1$
            + "get_outgoing_structures) remain available for backward compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "Operation: text_search / object_references / method_references / " //$NON-NLS-1$
                + "resolve_symbol / call_hierarchy / symbol_info / content_assist / " //$NON-NLS-1$
                + "outgoing_structures / help (snake_case canonical; camelCase like " //$NON-NLS-1$
                + "textSearch is also accepted). Pass operation=help " //$NON-NLS-1$
                + "without other params for the topic catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic name when operation=help. Topics: workflow, text_search, " //$NON-NLS-1$
                + "object_references, method_references, resolve_symbol, call_hierarchy, " //$NON-NLS-1$
                + "symbol_info, content_assist, outgoing_structures. " //$NON-NLS-1$
                + "Without topic - lists all operations with one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Optional for object_references (auto-detect via " //$NON-NLS-1$
                + "owner walk + sister extensions/external scope) - required for the " //$NON-NLS-1$
                + "other operations.") //$NON-NLS-1$
            .stringProperty("query", //$NON-NLS-1$
                "Search string for text_search / method_references. Supports plain " //$NON-NLS-1$
                + "text and regex (isRegex=true). Wildcards * and ? work inside the " //$NON-NLS-1$
                + "regex form.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "FQN of the metadata object for object_references and " //$NON-NLS-1$
                + "outgoing_structures. Aliased to objectFqn for direct delegation. " //$NON-NLS-1$
                + "Russian and English type names supported.") //$NON-NLS-1$
            .stringProperty("filePath", //$NON-NLS-1$
                "BSL file (src/-relative path) for symbol_info / content_assist - the " //$NON-NLS-1$
                + "position whose type / completions you want.") //$NON-NLS-1$
            .integerProperty("line", //$NON-NLS-1$
                "1-based line for symbol_info / content_assist.") //$NON-NLS-1$
            .integerProperty("column", //$NON-NLS-1$
                "1-based column for symbol_info / content_assist.") //$NON-NLS-1$
            .stringProperty("contains", //$NON-NLS-1$
                "content_assist: keep only proposals whose name contains this substring.") //$NON-NLS-1$
            .integerProperty("offset", //$NON-NLS-1$
                "content_assist: skip this many proposals (paging, with limit).") //$NON-NLS-1$
            .booleanProperty("extendedDocumentation", //$NON-NLS-1$
                "content_assist: include the full doc string for each proposal. Default: false.") //$NON-NLS-1$
            .stringProperty("symbol", //$NON-NLS-1$
                "Method symbol for resolve_symbol (e.g. ОбщегоНазначения.СообщитьПользователю).") //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Method name for call_hierarchy (case-insensitive).") //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "call_hierarchy: the module the method lives in - a path from src/ " //$NON-NLS-1$
                + "(CommonModules/MyModule/Module.bsl) or a module FQN " //$NON-NLS-1$
                + "(CommonModule.MyModule / Catalog.Products.ManagerModule). Required for call_hierarchy.") //$NON-NLS-1$
            .stringProperty("direction", //$NON-NLS-1$
                "call_hierarchy direction: incoming / callers (default - who calls this method) " //$NON-NLS-1$
                + "or outgoing / callees (what this method calls). Both vocabularies accepted.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum results. text_search: default 100 / max 500. " //$NON-NLS-1$
                + "object_references: default 100 / max 500. content_assist: caps the " //$NON-NLS-1$
                + "proposal list.") //$NON-NLS-1$
            .booleanProperty("caseSensitive", //$NON-NLS-1$
                "text_search / method_references case sensitivity. Default: false.") //$NON-NLS-1$
            .booleanProperty("isRegex", //$NON-NLS-1$
                "Treat query as regex. Default: false.") //$NON-NLS-1$
            .booleanProperty("wholeWord", //$NON-NLS-1$
                "Whole-word match for text_search / method_references. Closes " //$NON-NLS-1$
                + "false positives like КурсыВалют matching КурсыВалютРасчетов.") //$NON-NLS-1$
            .booleanProperty("compact", //$NON-NLS-1$
                "Trim large text_search responses to first N matches plus stats " //$NON-NLS-1$
                + "and top-5 files by match count.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Soft scan budget for text_search / method_references (default 25, range " //$NON-NLS-1$
                + "5-120). On a huge configuration an unfiltered search returns partial " //$NON-NLS-1$
                + "results plus a narrow-with-metadataType/fileMask note instead of hanging.") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "text_search / method_references: filter by module path substring " //$NON-NLS-1$
                    + "(e.g. 'CommonModules' or 'Documents/SalesOrder'). Narrow a project-wide " //$NON-NLS-1$
                    + "scan to a metadata folder to stay under the timeout on large configs.") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "text_search: filter by metadata type (commonModules, documents, catalogs, " //$NON-NLS-1$
                    + "informationRegisters, ...). More precise than fileMask.") //$NON-NLS-1$
            .stringProperty("outputMode", //$NON-NLS-1$
                "text_search: full (matches with context, default) / count (only the total) / " //$NON-NLS-1$
                    + "files (file list with match counts, no context). Use count/files for a " //$NON-NLS-1$
                    + "lightweight probe before a full scan.") //$NON-NLS-1$
            .integerProperty("contextLines", //$NON-NLS-1$
                "text_search: lines of context shown around each match (default 2, max 5).") //$NON-NLS-1$
            .integerProperty("linesBefore", //$NON-NLS-1$
                "text_search: context lines before each match (overrides contextLines).") //$NON-NLS-1$
            .integerProperty("linesAfter", //$NON-NLS-1$
                "text_search: context lines after each match (overrides contextLines).") //$NON-NLS-1$
            .booleanProperty("skipBsl", //$NON-NLS-1$
                "object_references: skip the BSL code search phase (metadata-only refs). " //$NON-NLS-1$
                    + "Much faster on large objects whose BSL phase can take 30-120s.") //$NON-NLS-1$
            .booleanProperty("bslOnly", //$NON-NLS-1$
                "object_references: search BSL code only, skip metadata back-references. " //$NON-NLS-1$
                    + "Inverse of skipBsl.") //$NON-NLS-1$
            .stringProperty("categories", //$NON-NLS-1$
                "object_references: comma-separated whitelist - back (direct back refs) / " //$NON-NLS-1$
                    + "produced (produced types) / predefined / fields (attribute/dimension refs) / " //$NON-NLS-1$
                    + "bsl (BSL code). Empty = all enabled.") //$NON-NLS-1$
            .booleanProperty("deep", //$NON-NLS-1$
                "object_references: expand produced types, labelling each by kind " //$NON-NLS-1$
                    + "(Object / Reference / Selection / Manager / Cache / List).") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "object_references: resume a Pending search issued earlier. find_references on " //$NON-NLS-1$
                    + "a large object can exceed the soft timeout and return a runKey - re-call " //$NON-NLS-1$
                    + "with it to fetch the final result.") //$NON-NLS-1$
            .booleanProperty("includeSource", //$NON-NLS-1$
                "resolve_symbol: include the resolved method's source code (default true).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isBlank())
        {
            return ToolResult.error(
                "operation is required. Allowed: text_search / object_references / " //$NON-NLS-1$
                + "method_references / resolve_symbol / call_hierarchy / symbol_info / " //$NON-NLS-1$
                + "content_assist / outgoing_structures / help.").toJson(); //$NON-NLS-1$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        switch (operation)
        {
            case "text_search": //$NON-NLS-1$
                return new CodeTextSearcher().execute(rewriteForTextSearcher(params));
            case "object_references": //$NON-NLS-1$
                return new ReferenceLocator().execute(rewriteForObjectReferences(params));
            case "method_references": //$NON-NLS-1$
                return new CodeTextSearcher().execute(rewriteForMethodReferences(rewriteForTextSearcher(params)));
            case "resolve_symbol": //$NON-NLS-1$
                return new DefinitionNavigator().execute(rewriteForResolveSymbol(params));
            case "call_hierarchy": //$NON-NLS-1$
                return new CallHierarchyReader().execute(rewriteForCallHierarchy(params));
            case "symbol_info": //$NON-NLS-1$
                return new SymbolInfoReader().execute(params);
            case "content_assist": //$NON-NLS-1$
                return new ContentAssistReader().execute(params);
            case "outgoing_structures": //$NON-NLS-1$
                // Same objectName -> objectFqn alias as object_references.
                return new OutgoingStructuresReader().execute(rewriteForObjectReferences(params));
            case "help": //$NON-NLS-1$
                return buildHelp(JsonUtils.extractStringArgument(params, "topic")); //$NON-NLS-1$
            default:
                return ToolResult.error(
                    "Unknown operation '" + operation //$NON-NLS-1$
                        + "'. Allowed: text_search / object_references / method_references / " //$NON-NLS-1$
                        + "resolve_symbol / call_hierarchy / symbol_info / content_assist / " //$NON-NLS-1$
                        + "outgoing_structures / help.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Maps RSV-style {@code objectName} to our {@code objectFqn} so the agent
     * can use the unified parameter name across operations.
     */
    private static Map<String, String> rewriteForObjectReferences(Map<String, String> params)
    {
        Map<String, String> rewritten = new LinkedHashMap<>(params);
        if (!rewritten.containsKey("objectFqn") //$NON-NLS-1$
            && rewritten.containsKey("objectName")) //$NON-NLS-1$
        {
            rewritten.put("objectFqn", rewritten.get("objectName")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return rewritten;
    }

    /**
     * The facade exposes {@code limit}; {@link CodeTextSearcher} reads
     * {@code maxResults}. Forward the alias so {@code limit} actually caps a
     * text_search / method_references scan instead of being silently ignored.
     * An explicit {@code maxResults} wins over {@code limit}.
     */
    private static Map<String, String> rewriteForTextSearcher(Map<String, String> params)
    {
        Map<String, String> rewritten = new LinkedHashMap<>(params);
        if (!rewritten.containsKey("maxResults") //$NON-NLS-1$
            && rewritten.containsKey("limit")) //$NON-NLS-1$
        {
            rewritten.put("maxResults", rewritten.get("limit")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return rewritten;
    }

    /**
     * method_references delegates to text search restricted to method-call form.
     * The query is wrapped in word boundaries via wholeWord=true (the user's
     * caseSensitive / isRegex / fileMask / metadataType are preserved).
     */
    private static Map<String, String> rewriteForMethodReferences(Map<String, String> params)
    {
        Map<String, String> rewritten = new LinkedHashMap<>(params);
        if (!rewritten.containsKey("wholeWord")) //$NON-NLS-1$
        {
            rewritten.put("wholeWord", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // methodName -> query alias for symmetry with call_hierarchy.
        if (!rewritten.containsKey("query") //$NON-NLS-1$
            && rewritten.containsKey("methodName")) //$NON-NLS-1$
        {
            rewritten.put("query", rewritten.get("methodName")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return rewritten;
    }

    /**
     * Prepares params for the call_hierarchy delegate (CallHierarchyReader),
     * which speaks {@code callers}/{@code callees}. Accepts the facade's
     * {@code incoming}/{@code outgoing} vocabulary as aliases so both work; every
     * other param (projectName / modulePath / methodName / limit) passes through
     * unchanged. {@code depth} and {@code moduleType} are intentionally NOT forwarded
     * - the delegate has no multi-hop recursion and does not disambiguate by module
     * type, so advertising them would be a false promise.
     */
    private static Map<String, String> rewriteForCallHierarchy(Map<String, String> params)
    {
        Map<String, String> rewritten = new LinkedHashMap<>(params);
        String direction = rewritten.get("direction"); //$NON-NLS-1$
        if (direction != null)
        {
            String d = direction.trim().toLowerCase();
            if ("incoming".equals(d)) //$NON-NLS-1$
            {
                rewritten.put("direction", "callers"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if ("outgoing".equals(d)) //$NON-NLS-1$
            {
                rewritten.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return rewritten;
    }

    /**
     * RSV exposes the symbol as {@code symbol}; our DefinitionNavigator
     * accepts the same name, so this rewriter only ensures backward-compat
     * aliases (methodName / fqn) are passed through unchanged.
     */
    private static Map<String, String> rewriteForResolveSymbol(Map<String, String> params)
    {
        Map<String, String> rewritten = new LinkedHashMap<>(params);
        if (!rewritten.containsKey("symbol")) //$NON-NLS-1$
        {
            String alt = rewritten.get("methodName"); //$NON-NLS-1$
            if (alt == null)
            {
                alt = rewritten.get("fqn"); //$NON-NLS-1$
            }
            if (alt != null)
            {
                rewritten.put("symbol", alt); //$NON-NLS-1$
            }
        }
        return rewritten;
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        StringBuilder sb = new StringBuilder();
        if (topic == null || topic.isEmpty())
        {
            sb.append("# code_search - operations\n\n"); //$NON-NLS-1$
            sb.append("- **text_search** - full-text search across BSL modules.\n"); //$NON-NLS-1$
            sb.append("- **object_references** - find every reference to a metadata FQN " //$NON-NLS-1$
                + "(Catalog.X / Document.Y / CommonModule.Z), with sister-project " //$NON-NLS-1$
                + "auto-scope when projectName is omitted.\n"); //$NON-NLS-1$
            sb.append("- **method_references** - find calls to a specific method by name; " //$NON-NLS-1$
                + "wholeWord=true is forced so prefix matches do not leak in.\n"); //$NON-NLS-1$
            sb.append("- **resolve_symbol** - go-to-definition for a method symbol " //$NON-NLS-1$
                + "(e.g. ОбщегоНазначения.СообщитьПользователю).\n"); //$NON-NLS-1$
            sb.append("- **call_hierarchy** - direct incoming (callers) or outgoing (callees) calls of a method.\n"); //$NON-NLS-1$
            sb.append("- **symbol_info** - type / hover information at a code position " //$NON-NLS-1$
                + "(filePath + line + column).\n"); //$NON-NLS-1$
            sb.append("- **content_assist** - code-completion proposals at a code position " //$NON-NLS-1$
                + "(filePath + line + column).\n"); //$NON-NLS-1$
            sb.append("- **outgoing_structures** - a metadata object's outbound structural " //$NON-NLS-1$
                + "references (the metadata it points at).\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the " //$NON-NLS-1$
                + "operation-picker guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        switch (topic)
        {
            case "workflow": //$NON-NLS-1$
                sb.append("# code_search - operation picker\n\n"); //$NON-NLS-1$
                sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
                sb.append("|------|-----------|\n"); //$NON-NLS-1$
                sb.append("| Find a string anywhere in BSL | text_search |\n"); //$NON-NLS-1$
                sb.append("| Where is `Catalog.Контрагенты` used | object_references |\n"); //$NON-NLS-1$
                sb.append("| Where is `СообщитьПользователю` called | method_references |\n"); //$NON-NLS-1$
                sb.append("| Open the source of a symbol | resolve_symbol |\n"); //$NON-NLS-1$
                sb.append("| Map calls in/out of a method | call_hierarchy |\n"); //$NON-NLS-1$
                sb.append("| Type / hover at a position | symbol_info |\n"); //$NON-NLS-1$
                sb.append("| Completions at a position | content_assist |\n"); //$NON-NLS-1$
                sb.append("| What metadata does object X point at | outgoing_structures |\n"); //$NON-NLS-1$
                return sb.toString();
            case "text_search": //$NON-NLS-1$
                sb.append("# code_search operation=text_search\n\nDelegates to search_in_code. " //$NON-NLS-1$
                    + "Parameters: projectName, query, caseSensitive, isRegex, wholeWord, " //$NON-NLS-1$
                    + "compact, maxResults, contextLines, fileMask, metadataType, " //$NON-NLS-1$
                    + "outputMode (full/count/files).\n"); //$NON-NLS-1$
                return sb.toString();
            case "object_references": //$NON-NLS-1$
                sb.append("# code_search operation=object_references\n\nDelegates to find_references. " //$NON-NLS-1$
                    + "Parameters: projectName (optional - 1.42 auto-scope walks the " //$NON-NLS-1$
                    + "Eclipse project graph and includes the configuration plus all " //$NON-NLS-1$
                    + "extensions / externals attached to it; or the parent " //$NON-NLS-1$
                    + "configuration's siblings when the owner is itself an extension), " //$NON-NLS-1$
                    + "objectName / objectFqn, limit, deep, skipBsl, bslOnly, categories, " //$NON-NLS-1$
                    + "timeoutSeconds, runKey.\n"); //$NON-NLS-1$
                return sb.toString();
            case "method_references": //$NON-NLS-1$
                sb.append("# code_search operation=method_references\n\nDelegates to search_in_code " //$NON-NLS-1$
                    + "with wholeWord=true. Pass methodName (or query) plus projectName. " //$NON-NLS-1$
                    + "fileMask narrows to a metadata folder if needed.\n"); //$NON-NLS-1$
                return sb.toString();
            case "resolve_symbol": //$NON-NLS-1$
                sb.append("# code_search operation=resolve_symbol\n\nDelegates to go_to_definition. " //$NON-NLS-1$
                    + "Pass projectName plus symbol (e.g. ОбщегоНазначения.СообщитьПользователю). " //$NON-NLS-1$
                    + "Returns module path, line range, signature and source.\n"); //$NON-NLS-1$
                return sb.toString();
            case "call_hierarchy": //$NON-NLS-1$
                sb.append("# code_search operation=call_hierarchy\n\nDelegates to " //$NON-NLS-1$
                    + "get_method_call_hierarchy. Pass projectName, modulePath (the module " //$NON-NLS-1$
                    + "the method lives in - src/ path or module FQN), methodName, and " //$NON-NLS-1$
                    + "direction=incoming|callers (default) or outgoing|callees. Returns the " //$NON-NLS-1$
                    + "direct callers/callees (single level - no recursion depth).\n"); //$NON-NLS-1$
                return sb.toString();
            case "symbol_info": //$NON-NLS-1$
                sb.append("# code_search operation=symbol_info\n\nDelegates to get_symbol_info. " //$NON-NLS-1$
                    + "Pass projectName, filePath (src/-relative BSL file), line and column " //$NON-NLS-1$
                    + "(both 1-based). Returns the type / hover at that position.\n"); //$NON-NLS-1$
                return sb.toString();
            case "content_assist": //$NON-NLS-1$
                sb.append("# code_search operation=content_assist\n\nDelegates to " //$NON-NLS-1$
                    + "get_content_assist. Pass projectName, filePath, line and column " //$NON-NLS-1$
                    + "(1-based); optional contains / limit / offset / extendedDocumentation " //$NON-NLS-1$
                    + "narrow the proposal list.\n"); //$NON-NLS-1$
                return sb.toString();
            case "outgoing_structures": //$NON-NLS-1$
                sb.append("# code_search operation=outgoing_structures\n\nDelegates to " //$NON-NLS-1$
                    + "get_outgoing_structures. Pass projectName plus objectName / objectFqn. " //$NON-NLS-1$
                    + "Returns the metadata the object points at (its outbound references).\n"); //$NON-NLS-1$
                return sb.toString();
            default:
                return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "text_search, object_references, method_references, resolve_symbol, " //$NON-NLS-1$
                    + "call_hierarchy, symbol_info, content_assist, outgoing_structures.\n"; //$NON-NLS-1$
        }
    }
}
