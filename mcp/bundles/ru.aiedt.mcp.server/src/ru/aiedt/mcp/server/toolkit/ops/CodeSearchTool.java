/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.support.FacadeHelpSearch;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ToolGate;
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

    /**
     * What each parameter carries beyond the sentence in its schema.
     * <p>
     * Every client holds the schema for the whole conversation, so it says what the parameter is
     * for in one sentence and names the values a caller picks from. The rest - when a value is
     * refused, what it does to what is already there, what a measurement showed - is answered when
     * operation=help topic=parameters asks for it. The two continue each other rather than
     * repeating, so neither can drift out of step with the other.
     * </p>
     */
    private static final Map<String, String> PARAMETER_RULES = buildParameterRules();

    /**
     * Builds the rules map.
     *
     * @return parameter name to the rules its description no longer carries
     */
    private static Map<String, String> buildParameterRules()
    {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("operation", "Pass operation=help without other params for the topic catalog."); //$NON-NLS-1$
        rules.put("topic", "Topics: workflow, text_search, object_references, method_references, " //$NON-NLS-1$
            + "resolve_symbol, call_hierarchy, symbol_info, content_assist, " //$NON-NLS-1$
            + "outgoing_structures. Without topic - lists all operations with " //$NON-NLS-1$
            + "one-line summaries."); //$NON-NLS-1$
        rules.put("projectName", "Optional for object_references (auto-detect via owner walk + sister " //$NON-NLS-1$
            + "extensions/external scope) and for text_search (searches every open " //$NON-NLS-1$
            + "project with sources, and names them) - required for the other " //$NON-NLS-1$
            + "operations."); //$NON-NLS-1$
        rules.put("query", "Supports plain text and regex (isRegex=true). Wildcards * and ? work " //$NON-NLS-1$
            + "inside the regex form."); //$NON-NLS-1$
        rules.put("objectName", "Aliased to objectFqn for direct delegation. Russian and English type " //$NON-NLS-1$
            + "names supported."); //$NON-NLS-1$
        rules.put("positions", "Each entry is either \"line:column\" or {\"line\":N,\"column\":M}. " //$NON-NLS-1$
            + "The module is prepared once for the whole batch, which is where the " //$NON-NLS-1$
            + "cost is - so a batch costs about what one call costs."); //$NON-NLS-1$
        rules.put("computeTypes", "Set false to skip that step when only the name and documentation are " //$NON-NLS-1$
            + "wanted."); //$NON-NLS-1$
        rules.put("modulePath", "Required for call_hierarchy."); //$NON-NLS-1$
        rules.put("direction", "Both vocabularies accepted."); //$NON-NLS-1$
        rules.put("wholeWord", "Closes false positives like КурсыВалют matching КурсыВалютРасчетов."); //$NON-NLS-1$
        rules.put("timeoutSeconds", "On a huge configuration an unfiltered search returns partial results " //$NON-NLS-1$
            + "plus a narrow-with-metadataType/fileMask note instead of hanging."); //$NON-NLS-1$
        rules.put("fileMask", "Narrow a project-wide scan to a metadata folder to stay under the " //$NON-NLS-1$
            + "timeout on large configs."); //$NON-NLS-1$
        rules.put("metadataType", "More precise than fileMask."); //$NON-NLS-1$
        rules.put("outputMode", "Use count/files for a lightweight probe before a full scan."); //$NON-NLS-1$
        rules.put("skipBsl", "Much faster on large objects whose BSL phase can take 30-120s."); //$NON-NLS-1$
        rules.put("categories", "Empty = all enabled."); //$NON-NLS-1$
        return Collections.unmodifiableMap(rules);
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "Operation: text_search / object_references / method_references / " //$NON-NLS-1$
                    + "resolve_symbol / call_hierarchy / symbol_info / content_assist / " //$NON-NLS-1$
                    + "outgoing_structures / help (snake_case canonical; camelCase like " //$NON-NLS-1$
                    + "textSearch is also accepted). operation=help topic=<operation> " //$NON-NLS-1$
                    + "answers what that one takes.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help: workflow or the name of an operation. " //$NON-NLS-1$
                    + "Without it, every operation with a one-line summary.") //$NON-NLS-1$
            .stringProperty("find", FacadeHelpSearch.FIND_DESCRIPTION)
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name.") //$NON-NLS-1$
            .stringProperty("query", //$NON-NLS-1$
                "Search string for text_search / method_references.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "FQN of the metadata object for object_references and outgoing_structures.") //$NON-NLS-1$
            .stringProperty("filePath", //$NON-NLS-1$
                "BSL file (src/-relative path) for symbol_info / content_assist - the " //$NON-NLS-1$
                + "position whose type / completions you want.") //$NON-NLS-1$
            .integerProperty("line", //$NON-NLS-1$
                "1-based line for symbol_info / content_assist.") //$NON-NLS-1$
            .integerProperty("column", //$NON-NLS-1$
                "1-based column for symbol_info / content_assist.") //$NON-NLS-1$
            .arrayProperty("positions", //$NON-NLS-1$
                "symbol_info: several positions in the same module, answered in one call " //$NON-NLS-1$
                    + "and reported in the order given. Each is \"line:column\" or " //$NON-NLS-1$
                    + "{\"line\":N,\"column\":M}.") //$NON-NLS-1$
            .stringProperty("contains", //$NON-NLS-1$
                "content_assist: keep only proposals whose name contains this substring.") //$NON-NLS-1$
            .integerProperty("offset", //$NON-NLS-1$
                "content_assist: skip this many proposals (paging, with limit).") //$NON-NLS-1$
            .booleanProperty("computeTypes", //$NON-NLS-1$
                "symbol_info: resolve the module's cross-references first so the answer " //$NON-NLS-1$
                    + "names the type of the symbol (default true).") //$NON-NLS-1$
            .booleanProperty("extendedDocumentation", //$NON-NLS-1$
                "content_assist: include the full doc string for each proposal. Default: false.") //$NON-NLS-1$
            .stringProperty("symbol", //$NON-NLS-1$
                "Method symbol for resolve_symbol (e.g. ОбщегоНазначения.СообщитьПользователю).") //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Method name for call_hierarchy (case-insensitive).") //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "call_hierarchy: the module the method lives in - a path from src/ " //$NON-NLS-1$
                    + "(CommonModules/MyModule/Module.bsl) or a module FQN " //$NON-NLS-1$
                    + "(CommonModule.MyModule / Catalog.Products.ManagerModule).") //$NON-NLS-1$
            .stringProperty("direction", //$NON-NLS-1$
                "call_hierarchy direction: incoming / callers (default - who calls this " //$NON-NLS-1$
                    + "method) or outgoing / callees (what this method calls).") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum results. text_search: default 100 / max 500. " //$NON-NLS-1$
                + "object_references: default 100 / max 500. content_assist: caps the " //$NON-NLS-1$
                + "proposal list.") //$NON-NLS-1$
            .booleanProperty("caseSensitive", //$NON-NLS-1$
                "text_search / method_references case sensitivity. Default: false.") //$NON-NLS-1$
            .booleanProperty("isRegex", //$NON-NLS-1$
                "Treat query as regex. Default: false.") //$NON-NLS-1$
            .booleanProperty("wholeWord", //$NON-NLS-1$
                "Whole-word match for text_search / method_references.") //$NON-NLS-1$
            .booleanProperty("compact", //$NON-NLS-1$
                "Trim large text_search responses to first N matches plus stats " //$NON-NLS-1$
                + "and top-5 files by match count.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Soft scan budget for text_search / method_references (default 25, range " //$NON-NLS-1$
                    + "5-120).") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "text_search / method_references: filter by module path substring (e.g. " //$NON-NLS-1$
                    + "'CommonModules' or 'Documents/SalesOrder').") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "text_search: filter by metadata type (commonModules, documents, catalogs, " //$NON-NLS-1$
                    + "informationRegisters, ...).") //$NON-NLS-1$
            .stringProperty("outputMode", //$NON-NLS-1$
                "text_search: full (matches with context, default) / count (only the " //$NON-NLS-1$
                    + "total) / files (file list with match counts, no context).") //$NON-NLS-1$
            .integerProperty("contextLines", //$NON-NLS-1$
                "text_search: lines of context shown around each match (default 2, max 5).") //$NON-NLS-1$
            .integerProperty("linesBefore", //$NON-NLS-1$
                "text_search: context lines before each match (overrides contextLines).") //$NON-NLS-1$
            .integerProperty("linesAfter", //$NON-NLS-1$
                "text_search: context lines after each match (overrides contextLines).") //$NON-NLS-1$
            .booleanProperty("skipBsl", //$NON-NLS-1$
                "object_references: skip the BSL code search phase (metadata-only refs).") //$NON-NLS-1$
            .booleanProperty("bslOnly", //$NON-NLS-1$
                "object_references: search BSL code only, skip metadata back-references. " //$NON-NLS-1$
                    + "Inverse of skipBsl.") //$NON-NLS-1$
            .stringProperty("categories", //$NON-NLS-1$
                "object_references: comma-separated whitelist - back (direct back refs) / " //$NON-NLS-1$
                    + "produced (produced types) / predefined / fields (attribute/dimension " //$NON-NLS-1$
                    + "refs) / bsl (BSL code).") //$NON-NLS-1$
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

    /**
     * Only {@code object_references} polls a reference search.
     * <p>
     * {@code text_search} reaches {@code search_in_code}, which does not resume a
     * {@code find_references} key. Routing every operation of this facade at that tool would let
     * a text search start a new scan under a live reference key without a permit.
     * </p>
     *
     * @param domain the registry domain the key was found in
     * @param operation the operation argument; may be {@code null}
     * @return {@code find_references} when this call polls one, or {@code null}
     */
    @Override
    public String resumes(String domain, String operation)
    {
        if (!PendingWorkRegistry.REFERENCES.domain().equals(domain))
        {
            return null;
        }
        String normalized = JsonUtils.normalizeOperationToken(operation);
        return "object_references".equals(normalized) ? ReferenceLocator.NAME : null; //$NON-NLS-1$
    }

    /**
     * Where an operation sends the call, for the one operation that resumes a heavy search.
     * <p>
     * {@code object_references} is {@code find_references}. The other operations stay here: this
     * facade is already heavy under its own name, and routing {@code text_search} at
     * {@code find_references} would name a resumption that search does not perform.
     * </p>
     *
     * @param arguments the call arguments
     * @return {@code find_references} for {@code object_references}, or {@code null}
     */
    @Override
    public String routesTo(Map<String, String> arguments)
    {
        String operation = JsonUtils.normalizeOperationToken(
            JsonUtils.extractStringArgument(arguments, "operation")); //$NON-NLS-1$
        return "object_references".equals(operation) ? ReferenceLocator.NAME : null; //$NON-NLS-1$
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
        // Same rule as the other facades: reaching a tool through here is still reaching that tool,
        // and a preset switched it off by ITS name. This facade named its operations after the job
        // rather than the tool, so the name has to be translated back before the question is asked.
        String presetGate = ToolGate.gateIfPresetDisabled(foldedToolName(operation));
        if (presetGate != null)
        {
            return ToolResult.error(presetGate).put("operation", operation).toJson(); //$NON-NLS-1$
        }
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
                return buildHelp(JsonUtils.extractStringArgument(params, "topic"), //$NON-NLS-1$
                    JsonUtils.extractStringArgument(params, "find")); //$NON-NLS-1$
            default:
                return ToolResult.error(
                    "Unknown operation '" + operation + "'." //$NON-NLS-1$
                        + FacadeHelpSearch.closestMatches(operation,
                            FacadeHelpSearch.describe(buildHelp(null, null)).keySet(),
                            FacadeHelpSearch.describe(buildHelp(null, null)))
                        + "\n\nAllowed: text_search / object_references / method_references / " //$NON-NLS-1$
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
     * unchanged, {@code depth} among them: the delegate DOES walk callers of callers,
     * up to its own limit. The sentence that used to stand here - that it has no
     * multi-hop recursion - was left behind when the delegate grew one, and the help
     * repeated it while printing the delegate's own depth argument two lines below.
     * What the delegate still does not do is disambiguate by module type.
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
     * What one operation hands to its delegate, so the routing can be put to the test.
     *
     * @param operation the facade operation.
     * @param params what the caller passed.
     * @return the arguments the delegate receives
     */
    static Map<String, String> prepareForDelegate(String operation, Map<String, String> params)
    {
        return "call_hierarchy".equals(operation) ? rewriteForCallHierarchy(params) : params; //$NON-NLS-1$
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

    /**
     * Each operation against the tool it reaches, because none of them share a name.
     * <p>
     * The operations here are named after the job - {@code text_search}, {@code object_references} -
     * while a preset knows the tools that do it: {@code search_in_code}, {@code find_references}.
     * Asking the preset about the operation name would always come back "not switched off".
     * </p>
     */
    private static final Map<String, String> FOLDED_TOOLS = foldedTools();

    private static Map<String, String> foldedTools()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("text_search", "search_in_code"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("object_references", ReferenceLocator.NAME); //$NON-NLS-1$
        // Method references are a text search with the query rewritten, so they answer to that tool.
        m.put("method_references", "search_in_code"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("resolve_symbol", "go_to_definition"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("call_hierarchy", CallHierarchyReader.NAME); //$NON-NLS-1$
        m.put("symbol_info", SymbolInfoReader.NAME); //$NON-NLS-1$
        m.put("content_assist", "get_content_assist"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("outgoing_structures", "get_outgoing_structures"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(m);
    }

    /**
     * Returns the name a preset would know an operation's tool by.
     *
     * @param operation the operation asked for, already normalized; may be <code>null</code>
     * @return the folded tool's name, or the operation itself when it reaches no tool of its own
     */
    static String foldedToolName(String operation)
    {
        String mapped = operation == null ? null : FOLDED_TOOLS.get(operation);
        return mapped != null ? mapped : operation;
    }

    /**
     * The tool an operation routes to, for the help to describe its parameters.
     * <p>
     * The per-topic help names an operation's parameters and said nothing about any of them: a
     * caller who wanted to know what one meant had only the catalogue entry. What the tool behind
     * the operation declares is the fuller answer, and it is one this facade can hand over.
     * </p>
     *
     * @param operation the operation, already normalized
     * @return the tool it routes to, or <code>null</code> when it is handled here
     */
    private static IMcpTool routedTool(String operation)
    {
        switch (operation)
        {
            case "text_search": //$NON-NLS-1$
            case "method_references": //$NON-NLS-1$
                return new CodeTextSearcher();
            case "object_references": //$NON-NLS-1$
                return new ReferenceLocator();
            case "resolve_symbol": //$NON-NLS-1$
                return new DefinitionNavigator();
            case "call_hierarchy": //$NON-NLS-1$
                return new CallHierarchyReader();
            case "symbol_info": //$NON-NLS-1$
                return new SymbolInfoReader();
            case "content_assist": //$NON-NLS-1$
                return new ContentAssistReader();
            case "outgoing_structures": //$NON-NLS-1$
                return new OutgoingStructuresReader();
            default:
                return null;
        }
    }

    /**
     * What the tool behind an operation says about its parameters, or nothing when there is none.
     *
     * @param operation the operation, already normalized
     * @return markdown to append to the topic, never <code>null</code>
     */
    private static String parametersOf(String operation)
    {
        IMcpTool routed = routedTool(operation);
        if (routed == null)
        {
            return ""; //$NON-NLS-1$
        }
        return "\n" + ru.aiedt.mcp.server.support.ParameterHelp.render(routed.getName(), //$NON-NLS-1$
            routed.getInputSchema(), PARAMETER_RULES);
    }

    /**
     * The operations this facade dispatches, in the order the catalog names them: each is asked
     * for its parameters, so {@code find} searches it one argument at a time.
     */
    private static final List<String> HELP_OPERATIONS = helpOperations();

    /** Every help topic, in the order the catalog names them: operations, then named topics. */
    private static final List<String> HELP_TOPICS = helpTopics();

    /**
     * The topics that name one of the operations.
     *
     * @return the operation names, never <code>null</code>
     */
    private static List<String> helpOperations()
    {
        List<String> operations = new ArrayList<>();
        Collections.addAll(operations, "text_search", "object_references", //$NON-NLS-1$ //$NON-NLS-2$
            "method_references", "resolve_symbol", "call_hierarchy", "symbol_info", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "content_assist", "outgoing_structures"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableList(operations);
    }

    /**
     * The topics {@code find} searches, catalog first by the caller, these after.
     *
     * @return the topic names, never <code>null</code>
     */
    private static List<String> helpTopics()
    {
        List<String> topics = new ArrayList<>(HELP_OPERATIONS);
        topics.add("workflow"); //$NON-NLS-1$
        return Collections.unmodifiableList(topics);
    }

    private static String buildHelp(String topic, String find)
    {
        if (find != null && !find.isBlank())
        {
            return FacadeHelpSearch.search(NAME, find, topic, HELP_TOPICS, HELP_OPERATIONS,
                asked -> buildHelp(asked, null));
        }
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
                return sb.toString() + parametersOf(topic);
            case "text_search": //$NON-NLS-1$
                sb.append("# code_search operation=text_search\n\nDelegates to search_in_code. " //$NON-NLS-1$
                    + "Parameters: projectName (optional - omit to search every open " //$NON-NLS-1$
                    + "project with sources), query, caseSensitive, isRegex, wholeWord, " //$NON-NLS-1$
                    + "compact, maxResults, contextLines, fileMask, metadataType, " //$NON-NLS-1$
                    + "outputMode (full/count/files).\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "object_references": //$NON-NLS-1$
                sb.append("# code_search operation=object_references\n\nDelegates to find_references. " //$NON-NLS-1$
                    + "Parameters: projectName (optional - 1.42 auto-scope walks the " //$NON-NLS-1$
                    + "Eclipse project graph and includes the configuration plus all " //$NON-NLS-1$
                    + "extensions / externals attached to it; or the parent " //$NON-NLS-1$
                    + "configuration's siblings when the owner is itself an extension), " //$NON-NLS-1$
                    + "objectName / objectFqn, limit, deep, skipBsl, bslOnly, categories, " //$NON-NLS-1$
                    + "timeoutSeconds, runKey.\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "method_references": //$NON-NLS-1$
                sb.append("# code_search operation=method_references\n\nDelegates to search_in_code " //$NON-NLS-1$
                    + "with wholeWord=true. Pass methodName (or query) plus projectName. " //$NON-NLS-1$
                    + "fileMask narrows to a metadata folder if needed.\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "resolve_symbol": //$NON-NLS-1$
                sb.append("# code_search operation=resolve_symbol\n\nDelegates to go_to_definition. " //$NON-NLS-1$
                    + "Pass projectName plus symbol (e.g. ОбщегоНазначения.СообщитьПользователю). " //$NON-NLS-1$
                    + "Returns module path, line range, signature and source.\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "call_hierarchy": //$NON-NLS-1$
                sb.append("# code_search operation=call_hierarchy\n\nDelegates to " //$NON-NLS-1$
                    + "get_method_call_hierarchy. Pass projectName, modulePath (the module " //$NON-NLS-1$
                    + "the method lives in - src/ path or module FQN), methodName, and " //$NON-NLS-1$
                    + "direction=incoming|callers (default) or outgoing|callees. Returns the " //$NON-NLS-1$
                    + "direct callers or callees; depth walks callers of callers, 1 to 5.\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "symbol_info": //$NON-NLS-1$
                sb.append("# code_search operation=symbol_info\n\nDelegates to get_symbol_info. " //$NON-NLS-1$
                    + "Pass projectName, filePath (src/-relative BSL file), line and column " //$NON-NLS-1$
                    + "(both 1-based). Returns the type / hover at that position.\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "content_assist": //$NON-NLS-1$
                sb.append("# code_search operation=content_assist\n\nDelegates to " //$NON-NLS-1$
                    + "get_content_assist. Pass projectName, filePath, line and column " //$NON-NLS-1$
                    + "(1-based); optional contains / limit / offset / extendedDocumentation " //$NON-NLS-1$
                    + "narrow the proposal list.\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            case "outgoing_structures": //$NON-NLS-1$
                sb.append("# code_search operation=outgoing_structures\n\nDelegates to " //$NON-NLS-1$
                    + "get_outgoing_structures. Pass projectName plus objectName / objectFqn. " //$NON-NLS-1$
                    + "Returns the metadata the object points at (its outbound references).\n"); //$NON-NLS-1$
                return sb.toString() + parametersOf(topic);
            default:
                return "# Unknown topic '" + topic + "'." //$NON-NLS-1$
                    + FacadeHelpSearch.closestMatches(topic, HELP_TOPICS,
                        FacadeHelpSearch.describe(buildHelp(null, null)))
                    + "\n\nAvailable: workflow, " //$NON-NLS-1$
                    + "text_search, object_references, method_references, resolve_symbol, " //$NON-NLS-1$
                    + "call_hierarchy, symbol_info, content_assist, outgoing_structures.\n"; //$NON-NLS-1$
        }
    }
}
