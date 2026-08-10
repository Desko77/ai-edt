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
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified security and access audit facade with three operations.
 *
 * <p>Collapses the rights, row-level-security and sensitive-data tools under one name:
 * <ul>
 *   <li>{@code audit_role_rights} - role rights by object, missing rights, conflicts
 *       between roles, or the impact of a right (delegates to {@link AuditRoleRightsTool})</li>
 *   <li>{@code find_rls_violations} - row-level-security checks that reference an
 *       undefined field, always-false conditions, or missing coverage (delegates to
 *       {@link FindRlsViolationsTool})</li>
 *   <li>{@code sensitive_data_scan} - attribute names, hardcoded secrets, comment leaks
 *       and logged sensitive values (delegates to {@link SensitiveDataScanTool})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass through as-is,
 * and the standalone tools stay registered for back-compat. Every absorbed standalone
 * answers as JSON; this facade always answers as MARKDOWN, the safest wrapper: it
 * carries any string body regardless of the routed tool's own native response type. An
 * agent that needs a JSON-typed result (structuredContent) should call the standalone
 * directly - the same tradeoff {@code code_search} and the other facades accept. All
 * three operations are read-only: this facade needs no preset-gating.
 */
public class SecurityAuditFacadeTool implements IMcpTool
{
    public static final String NAME = "security_audit"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Security and access audit - role rights audit, RLS violation scan, " //$NON-NLS-1$
            + "sensitive-data scan. Operations: audit_role_rights, find_rls_violations, " //$NON-NLS-1$
            + "sensitive_data_scan, help. Pass operation=<name> (snake_case canonical; " //$NON-NLS-1$
            + "camelCase like auditRoleRights is also accepted); remaining parameters " //$NON-NLS-1$
            + "follow the per-operation contracts (call operation=help for the catalog). " //$NON-NLS-1$
            + "All three operations are read-only. The standalone tools remain available " //$NON-NLS-1$
            + "for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "audit_role_rights / find_rls_violations / sensitive_data_scan / help " //$NON-NLS-1$
                    + "(snake_case canonical; camelCase like auditRoleRights is also " //$NON-NLS-1$
                    + "accepted). Pass operation=help without other params for the " //$NON-NLS-1$
                    + "operation catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for all three operations.") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "audit_role_rights: rights / missing / conflicts / impact (default rights).") //$NON-NLS-1$
            .stringProperty("roleName", //$NON-NLS-1$
                "audit_role_rights: role name, required for mode=rights / mode=missing. " //$NON-NLS-1$
                    + "find_rls_violations: optional, limit checks to RLS of this role " //$NON-NLS-1$
                    + "(default: any RLS).") //$NON-NLS-1$
            .stringProperty("roleNames", //$NON-NLS-1$
                "audit_role_rights: comma-separated role names, for mode=conflicts / " //$NON-NLS-1$
                    + "mode=impact.") //$NON-NLS-1$
            .stringProperty("objectType", //$NON-NLS-1$
                "audit_role_rights: Catalog / Document / Register / Report / all (default " //$NON-NLS-1$
                    + "all).") //$NON-NLS-1$
            .booleanProperty("includeRls", //$NON-NLS-1$
                "audit_role_rights: include a hasRls flag in the output (default false).") //$NON-NLS-1$
            .stringProperty("checks", //$NON-NLS-1$
                "sensitive_data_scan: comma-separated check names (default all): " //$NON-NLS-1$
                    + "ATTRIBUTE_NAME, HARDCODED_SECRET, COMMENT_LEAK, LOG_SENSITIVE.") //$NON-NLS-1$
            .stringProperty("customPatterns", //$NON-NLS-1$
                "sensitive_data_scan: comma-separated additional regex patterns for the " //$NON-NLS-1$
                    + "ATTRIBUTE_NAME check.") //$NON-NLS-1$
            .stringProperty("severity_filter", //$NON-NLS-1$
                "find_rls_violations and sensitive_data_scan: info / warning / error / all " //$NON-NLS-1$
                    + "(default warning).") //$NON-NLS-1$
            .stringProperty("format", //$NON-NLS-1$
                "All three operations: json (default) or markdown.") //$NON-NLS-1$
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
            return ToolResult.error("operation is required. Allowed: audit_role_rights / " //$NON-NLS-1$
                + "find_rls_violations / sensitive_data_scan / help.").toJson(); //$NON-NLS-1$
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
            case "audit_role_rights": //$NON-NLS-1$
                return new AuditRoleRightsTool().execute(params);
            case "find_rls_violations": //$NON-NLS-1$
                return new FindRlsViolationsTool().execute(params);
            case "sensitive_data_scan": //$NON-NLS-1$
                return new SensitiveDataScanTool().execute(params);
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
            sb.append("# security_audit - operations\n\n"); //$NON-NLS-1$
            sb.append("- **audit_role_rights** - role rights by object, missing rights, " //$NON-NLS-1$
                + "conflicts between roles, or impact of a role change.\n"); //$NON-NLS-1$
            sb.append("- **find_rls_violations** - row-level-security checks that " //$NON-NLS-1$
                + "reference an undefined field, are always false, or are missing.\n"); //$NON-NLS-1$
            sb.append("- **sensitive_data_scan** - suspicious attribute names, hardcoded " //$NON-NLS-1$
                + "secrets, comment leaks and logged sensitive values.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the " //$NON-NLS-1$
                + "operation-picker guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# security_audit - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| What can this role do, or what is missing | audit_role_rights |\n"); //$NON-NLS-1$
            sb.append("| Do two roles conflict, or what breaks if I remove one | " //$NON-NLS-1$
                + "audit_role_rights (mode=conflicts / mode=impact) |\n"); //$NON-NLS-1$
            sb.append("| Is row-level security broken or missing somewhere | " //$NON-NLS-1$
                + "find_rls_violations |\n"); //$NON-NLS-1$
            sb.append("| Is a password/token/PII leaking into an attribute name, a " //$NON-NLS-1$
                + "comment or a log | sensitive_data_scan |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "audit_role_rights", "find_rls_violations", //$NON-NLS-1$ //$NON-NLS-2$
            "sensitive_data_scan")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
