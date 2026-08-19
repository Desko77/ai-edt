/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import org.eclipse.core.resources.IProject;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.SystemEnumValues;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified documentation-lookup facade with two operations.
 *
 * <p>Collapses the two documentation-reading tools under one name:
 * <ul>
 *   <li>{@code get_platform_documentation} - platform type/method/property documentation
 *       (delegates to {@link PlatformDocReader})</li>
 *   <li>{@code get_object_help} - an object's built-in help pages, the same content shown
 *       by pressing F1 in the 1C UI (delegates to {@link GetObjectHelpTool})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass through as-is,
 * and the standalone tools stay registered for back-compat. Both absorbed standalones
 * already answer as MARKDOWN, matching this facade's own response type, so nothing is
 * lost by calling through it. An agent that needs the structured variant of either -
 * neither currently has one - would call the standalone directly, the same tradeoff the
 * other facades accept. Both operations are read-only: this facade needs no
 * preset-gating.
 */
public class DocsLookupFacadeTool implements IMcpTool
{
    public static final String NAME = "docs_lookup"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Documentation lookup - platform type/method docs and an object's " //$NON-NLS-1$
            + "built-in help. Operations: get_platform_documentation, get_object_help, " //$NON-NLS-1$
            + "help. Pass operation=<name> (snake_case canonical; camelCase like " //$NON-NLS-1$
            + "getPlatformDocumentation is also accepted); remaining parameters follow " //$NON-NLS-1$
            + "the per-operation contracts (call operation=help for the catalog). Both " //$NON-NLS-1$
            + "operations are read-only. The standalone tools remain available for " //$NON-NLS-1$
            + "back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "get_platform_documentation / get_object_help / system_enum_values / help " //$NON-NLS-1$
                    + "(snake_case " //$NON-NLS-1$
                    + "canonical; camelCase like getPlatformDocumentation is also " //$NON-NLS-1$
                    + "accepted). Pass operation=help without other params for the " //$NON-NLS-1$
                    + "operation catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "get_platform_documentation: optional, used only to resolve the platform " //$NON-NLS-1$
                    + "version. get_object_help: EDT project name, required for that " //$NON-NLS-1$
                    + "operation.") //$NON-NLS-1$
            .stringProperty("typeName", //$NON-NLS-1$
                "get_platform_documentation: name of the platform type or symbol to look " //$NON-NLS-1$
                    + "up, e.g. 'ValueTable', 'Array', 'Structure'. English or Russian. " //$NON-NLS-1$
                    + "Required for that operation.") //$NON-NLS-1$
            .stringProperty("category", //$NON-NLS-1$
                "get_platform_documentation: 'type' (platform types such as ValueTable) or " //$NON-NLS-1$
                    + "'builtin' (global built-in functions). Defaults to 'type'.") //$NON-NLS-1$
            .stringProperty("memberName", //$NON-NLS-1$
                "get_platform_documentation: keep only members whose name matches this " //$NON-NLS-1$
                    + "text, case-insensitive substring, e.g. 'Add', 'Count'.") //$NON-NLS-1$
            .stringProperty("memberType", //$NON-NLS-1$
                "get_platform_documentation: limit results to one member kind - 'method', " //$NON-NLS-1$
                    + "'property', 'constructor', 'event', 'all'. Defaults to 'all'.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "get_platform_documentation: upper bound on results returned. Defaults to " //$NON-NLS-1$
                    + "50.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "get_object_help: object FQN, e.g. 'Document.SalesOrder', " //$NON-NLS-1$
                    + "'Catalog.Products', 'CommonModule.Common'. Russian type names " //$NON-NLS-1$
                    + "supported. Required for that operation.") //$NON-NLS-1$
            .stringProperty("format", //$NON-NLS-1$
                "get_object_help: 'markdown' (default), 'html', 'text'.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "get_platform_documentation: 'en' (default) or 'ru'. get_object_help: 'ru' " //$NON-NLS-1$
                    + "/ 'en' / 'auto' (default, concatenates every available page). Same " //$NON-NLS-1$
                    + "key, different accepted values and default per operation.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isBlank())
        {
            return ToolResult.error("operation is required. Allowed: " //$NON-NLS-1$
                + "get_platform_documentation / get_object_help / help.").toJson(); //$NON-NLS-1$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        if ("help".equals(operation)) //$NON-NLS-1$
        {
            return buildHelp(JsonUtils.extractStringArgument(params, "topic")); //$NON-NLS-1$
        }
        if (!OPS.containsKey(operation))
        {
            return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                + "'. Allowed: " + String.join(" / ", OPS.keySet()) //$NON-NLS-1$ //$NON-NLS-2$
                + " / help.").toJson(); //$NON-NLS-1$
        }
        // One gate for every operation this facade folds in. Reaching a tool through a facade is
        // still reaching that tool, and a preset that switched it off means it. Keyed on the
        // operation name because that IS the folded tool's name; an operation with no tool of its
        // own is in nobody's disabled set and passes straight through.
        String presetGate = ToolGate.gateIfPresetDisabled(operation);
        if (presetGate != null)
        {
            return ToolResult.error(presetGate).put("operation", operation).toJson(); //$NON-NLS-1$
        }
        switch (operation)
        {
            case "get_platform_documentation": //$NON-NLS-1$
                return new PlatformDocReader().execute(params);
            case "get_object_help": //$NON-NLS-1$
                return new GetObjectHelpTool().execute(params);
            case "system_enum_values": //$NON-NLS-1$
                return systemEnumValues(params);
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# docs_lookup - operations\n\n"); //$NON-NLS-1$
            sb.append("- **get_platform_documentation** - platform type / method / " //$NON-NLS-1$
                + "property documentation, keyed by type name.\n"); //$NON-NLS-1$
            sb.append("- **get_object_help** - an object's own built-in help pages (F1 in " //$NON-NLS-1$
                + "the 1C UI), keyed by object FQN.\n"); //$NON-NLS-1$
            sb.append("- **system_enum_values** - the values a system enumeration can take, " //$NON-NLS-1$
                + "under both names. Ask before writing after the dot: an unknown member is a " //$NON-NLS-1$
                + "run-time error, not one the editor catches.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the " //$NON-NLS-1$
                + "operation-picker guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# docs_lookup - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| What methods/properties does platform type X have | " //$NON-NLS-1$
                + "get_platform_documentation |\n"); //$NON-NLS-1$
            sb.append("| What does this specific configuration object's own help say | " //$NON-NLS-1$
                + "get_object_help |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Lists what one system enumeration may hold.
     * <p>
     * The values are read from the platform's own type register - the same one the editor completes
     * from - not from prose. An agent writing {@code ВидДвиженияНакопления.Приход} otherwise has to
     * guess, and a wrong member of a system enumeration is a run-time error, not one the editor
     * catches.
     * </p>
     *
     * @param params the call's arguments.
     * @return the values, or the reason they could not be read
     */
    private static String systemEnumValues(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || typeName == null || typeName.isEmpty())
        {
            return ToolResult.error("projectName and typeName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        SystemEnumValues.Lookup lookup = SystemEnumValues.of(typeName, project);
        if (lookup.cannotTell != null)
        {
            // A failure, not an empty list. An empty list would read as "this enumeration has no
            // values", which is never true of a real one, and would send the caller looking for
            // the mistake in their own code.
            return ToolResult.error(lookup.cannotTell)
                .put("typeName", typeName) //$NON-NLS-1$
                .put("isSystemEnum", lookup.isSystemEnum) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("typeName", typeName) //$NON-NLS-1$
            .put("isSystemEnum", true) //$NON-NLS-1$
            // Named because it is usually NOT the name that was asked for: the values live on a
            // second type, and saying which one keeps the answer checkable.
            .put("valuesFrom", lookup.valuesFrom) //$NON-NLS-1$
            .put("valueCount", lookup.values.size()) //$NON-NLS-1$
            .put("values", lookup.values) //$NON-NLS-1$
            .toJson();
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "get_platform_documentation", "get_object_help", //$NON-NLS-1$ //$NON-NLS-2$
            "system_enum_values")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
