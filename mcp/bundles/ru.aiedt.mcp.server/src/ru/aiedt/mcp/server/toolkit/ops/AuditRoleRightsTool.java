/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.RoleRightsAnalyzer;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Audit role rights: per-object grid of Read/Update/Insert/Delete/View/Use
 * verdicts; under-privileged gaps; conflicts between two or more roles;
 * impact analysis for role removal.
 */
public class AuditRoleRightsTool implements IMcpTool
{
    public static final String NAME = "audit_role_rights"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `security_audit` `operation=audit_role_rights`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Audit 1C role rights: rights grid per object, under-privileged gaps, conflicts " //$NON-NLS-1$
            + "between two or more roles, impact analysis (what user loses on role removal). " //$NON-NLS-1$
            + "Modes: rights | missing | conflicts | impact."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("roleName", "Role name (required for rights / missing modes)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("roleNames", //$NON-NLS-1$
                "Comma-separated role names (for conflicts / impact modes)") //$NON-NLS-1$
            .stringProperty("mode", "rights | missing | conflicts | impact (default rights)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectType", //$NON-NLS-1$
                "Catalog | Document | Register | Report | all (default all)") //$NON-NLS-1$
            .stringProperty("objectFqn", "Specific object FQN to focus on") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("includeRls", "Include hasRls flag in output (default false)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String mode = orDefault(JsonUtils.extractStringArgument(params, "mode"), "rights"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            return UiSync.call(() -> runMode(project, mode, params));
        }
        catch (Exception e)
        {
            Activator.logError("audit_role_rights error", e); //$NON-NLS-1$
            return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
        }
    }

    private String runMode(IProject project, String mode, Map<String, String> params)
    {
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return ToolResult.error("configuration provider is not published as a service").toJson(); //$NON-NLS-1$
        }
        Configuration config = provider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Configuration not available").toJson(); //$NON-NLS-1$
        }
        String objectType = JsonUtils.extractStringArgument(params, "objectType"); //$NON-NLS-1$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean includeRls = JsonUtils.extractBooleanArgument(params, "includeRls", false); //$NON-NLS-1$
        Collection<MdObject> objects = collectObjects(config, objectType);
        switch (mode.toLowerCase())
        {
            case "rights": //$NON-NLS-1$
                return runRights(project, config, objects, params, format, includeRls);
            case "missing": //$NON-NLS-1$
                return runMissing(project, config, objects, params, format);
            case "conflicts": //$NON-NLS-1$
                return runConflicts(project, config, objects, params, format);
            case "impact": //$NON-NLS-1$
                return runImpact(project, config, objects, params, format);
            default:
                return ToolResult.error("mode must be rights | missing | conflicts | impact") //$NON-NLS-1$
                    .toJson();
        }
    }

    private Collection<MdObject> collectObjects(Configuration config, String objectType)
    {
        List<MdObject> objects = new ArrayList<>();
        for (java.lang.reflect.Method m : config.getClass().getMethods())
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
            if (!java.util.List.class.isAssignableFrom(m.getReturnType()))
            {
                continue;
            }
            String type = name.substring(3);
            if (objectType != null && !"all".equalsIgnoreCase(objectType) //$NON-NLS-1$
                && !type.toLowerCase().startsWith(objectType.toLowerCase()))
            {
                continue;
            }
            try
            {
                Object value = m.invoke(config);
                if (value instanceof java.util.List)
                {
                    for (Object item : (java.util.List<?>) value)
                    {
                        if (item instanceof MdObject)
                        {
                            objects.add((MdObject) item);
                        }
                    }
                }
            }
            catch (Throwable ignored)
            {
                // skip inaccessible
            }
        }
        return objects;
    }

    private String runRights(IProject project, Configuration config, Collection<MdObject> objects,
        Map<String, String> params, String format, boolean includeRls)
    {
        String roleName = JsonUtils.extractStringArgument(params, "roleName"); //$NON-NLS-1$
        if (roleName == null || roleName.isEmpty())
        {
            return ToolResult.error("roleName is required for mode=rights").toJson(); //$NON-NLS-1$
        }
        Role role = RoleRightsAnalyzer.findRole(config, roleName);
        if (role == null)
        {
            return errorRoleNotFound(config, roleName);
        }
        RoleRightsAnalyzer.RightsTable table = RoleRightsAnalyzer.analyze(project, role, objects);
        if (!includeRls)
        {
            table.hasRls.clear();
        }
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return ToolResult.success()
                .put("mode", "rights") //$NON-NLS-1$ //$NON-NLS-2$
                .put("roleName", roleName) //$NON-NLS-1$
                .put("text", renderRightsMarkdown(table, includeRls)) //$NON-NLS-1$
                .toJson();
        }
        ToolResult tr = ToolResult.success().put("mode", "rights"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> tableMap = table.toMap();
        for (Map.Entry<String, Object> entry : tableMap.entrySet())
        {
            tr.put(entry.getKey(), entry.getValue());
        }
        return tr.toJson();
    }

    private String runMissing(IProject project, Configuration config, Collection<MdObject> objects,
        Map<String, String> params, String format)
    {
        String roleName = JsonUtils.extractStringArgument(params, "roleName"); //$NON-NLS-1$
        if (roleName == null || roleName.isEmpty())
        {
            return ToolResult.error("roleName is required for mode=missing").toJson(); //$NON-NLS-1$
        }
        Role role = RoleRightsAnalyzer.findRole(config, roleName);
        if (role == null)
        {
            return errorRoleNotFound(config, roleName);
        }
        RoleRightsAnalyzer.RightsTable table = RoleRightsAnalyzer.analyze(project, role, objects);
        String objectType = JsonUtils.extractStringArgument(params, "objectType"); //$NON-NLS-1$
        List<String> missing = RoleRightsAnalyzer.missingObjects(table, objectType);
        return ToolResult.success()
            .put("mode", "missing") //$NON-NLS-1$ //$NON-NLS-2$
            .put("roleName", roleName) //$NON-NLS-1$
            .put("missingCount", missing.size()) //$NON-NLS-1$
            .put("missing", missing) //$NON-NLS-1$
            .toJson();
    }

    private String runConflicts(IProject project, Configuration config, Collection<MdObject> objects,
        Map<String, String> params, String format)
    {
        String roleNames = JsonUtils.extractStringArgument(params, "roleNames"); //$NON-NLS-1$
        if (roleNames == null || roleNames.isEmpty())
        {
            return ToolResult.error("roleNames is required for mode=conflicts").toJson(); //$NON-NLS-1$
        }
        String[] names = roleNames.split("\\s*,\\s*"); //$NON-NLS-1$
        if (names.length < 2)
        {
            return ToolResult.error("conflicts mode requires at least 2 role names").toJson(); //$NON-NLS-1$
        }
        List<RoleRightsAnalyzer.RightsTable> tables = new ArrayList<>();
        for (String name : names)
        {
            Role role = RoleRightsAnalyzer.findRole(config, name.trim());
            if (role == null)
            {
                return errorRoleNotFound(config, name.trim());
            }
            tables.add(RoleRightsAnalyzer.analyze(project, role, objects));
        }
        List<Map<String, Object>> allConflicts = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++)
        {
            for (int j = i + 1; j < tables.size(); j++)
            {
                allConflicts.addAll(RoleRightsAnalyzer.conflicts(tables.get(i), tables.get(j)));
            }
        }
        return ToolResult.success()
            .put("mode", "conflicts") //$NON-NLS-1$ //$NON-NLS-2$
            .put("roleNames", roleNames) //$NON-NLS-1$
            .put("conflictCount", allConflicts.size()) //$NON-NLS-1$
            .put("conflicts", allConflicts) //$NON-NLS-1$
            .toJson();
    }

    private String runImpact(IProject project, Configuration config, Collection<MdObject> objects,
        Map<String, String> params, String format)
    {
        String roleNames = JsonUtils.extractStringArgument(params, "roleNames"); //$NON-NLS-1$
        if (roleNames == null || roleNames.isEmpty())
        {
            return ToolResult.error("roleNames is required for mode=impact").toJson(); //$NON-NLS-1$
        }
        String[] names = roleNames.split("\\s*,\\s*"); //$NON-NLS-1$
        // Per-object: count how many of the listed roles allow each right.
        Map<String, Map<String, Integer>> allowCounts = new LinkedHashMap<>();
        for (String name : names)
        {
            Role role = RoleRightsAnalyzer.findRole(config, name.trim());
            if (role == null)
            {
                continue;
            }
            RoleRightsAnalyzer.RightsTable table = RoleRightsAnalyzer.analyze(project, role, objects);
            for (Map.Entry<String, Map<String, RoleRightsAnalyzer.Verdict>> entry : table.rights
                .entrySet())
            {
                Map<String, Integer> rowCounts = allowCounts.computeIfAbsent(entry.getKey(),
                    k -> new LinkedHashMap<>());
                for (Map.Entry<String, RoleRightsAnalyzer.Verdict> rightEntry : entry.getValue()
                    .entrySet())
                {
                    if (rightEntry.getValue() == RoleRightsAnalyzer.Verdict.ALLOW)
                    {
                        rowCounts.merge(rightEntry.getKey(), 1, Integer::sum);
                    }
                }
            }
        }
        // Impact = rights that only one of the listed roles allows.
        List<Map<String, Object>> exclusive = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : allowCounts.entrySet())
        {
            for (Map.Entry<String, Integer> r : entry.getValue().entrySet())
            {
                if (r.getValue() == 1)
                {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("objectFqn", entry.getKey()); //$NON-NLS-1$
                    item.put("right", r.getKey()); //$NON-NLS-1$
                    exclusive.add(item);
                }
            }
        }
        return ToolResult.success()
            .put("mode", "impact") //$NON-NLS-1$ //$NON-NLS-2$
            .put("roleNames", roleNames) //$NON-NLS-1$
            .put("exclusiveCount", exclusive.size()) //$NON-NLS-1$
            .put("exclusive", exclusive) //$NON-NLS-1$
            .toJson();
    }

    private String errorRoleNotFound(Configuration config, String roleName)
    {
        List<String> available = new ArrayList<>();
        for (Role role : RoleRightsAnalyzer.listRoles(config))
        {
            available.add(role.getName());
        }
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("roleName", roleName); //$NON-NLS-1$
        tag.put("availableRoles", available); //$NON-NLS-1$
        return ToolResult.error("Role not found: " + roleName) //$NON-NLS-1$
            .put("roleNotFound", tag) //$NON-NLS-1$
            .toJson();
    }

    private static String renderRightsMarkdown(RoleRightsAnalyzer.RightsTable table,
        boolean includeRls)
    {
        StringBuilder sb = new StringBuilder("# Rights for role ").append(table.roleName) //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        sb.append("| Object | "); //$NON-NLS-1$
        for (String right : RoleRightsAnalyzer.STANDARD_RIGHTS)
        {
            sb.append(right).append(" | "); //$NON-NLS-1$
        }
        if (includeRls)
        {
            sb.append("RLS | "); //$NON-NLS-1$
        }
        sb.append("\n|---"); //$NON-NLS-1$
        for (int i = 0; i < RoleRightsAnalyzer.STANDARD_RIGHTS.size(); i++)
        {
            sb.append("|---"); //$NON-NLS-1$
        }
        if (includeRls)
        {
            sb.append("|---"); //$NON-NLS-1$
        }
        sb.append("|\n"); //$NON-NLS-1$
        for (Map.Entry<String, Map<String, RoleRightsAnalyzer.Verdict>> entry : table.rights
            .entrySet())
        {
            sb.append("| ").append(entry.getKey()).append(" |"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String right : RoleRightsAnalyzer.STANDARD_RIGHTS)
            {
                RoleRightsAnalyzer.Verdict v = entry.getValue().get(right);
                sb.append(" ").append(v != null ? abbreviate(v) : " ").append(" |"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (includeRls)
            {
                sb.append(" ").append(Boolean.TRUE.equals(table.hasRls.get(entry.getKey())) //$NON-NLS-1$
                    ? "yes" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" |"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String abbreviate(RoleRightsAnalyzer.Verdict v)
    {
        switch (v)
        {
            case ALLOW: return "+"; //$NON-NLS-1$
            case DENY: return "-"; //$NON-NLS-1$
            default: return "."; //$NON-NLS-1$
        }
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
