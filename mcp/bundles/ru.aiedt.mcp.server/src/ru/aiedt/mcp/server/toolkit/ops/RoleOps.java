/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmRightsHelper;

/**
 * Role / RLS operations (set_role_right, set / remove_restriction_template, set / remove_role_restriction),
 * extracted from {@link EditMetadataTool} as the third cluster of the god-class split (Inc4). The handlers
 * are fully self-contained: every rights mutation delegates to {@link BmRightsHelper}, which does a
 * file-level parse-merge-write on the role's {@code Rights.rights} resource (a fresh or factory-created role
 * cannot be persisted via a BM commit). No shared EditMetadataTool helpers are used.
 */
final class RoleOps
{
    /**
     * set_role_right - grants or revokes a single right of a metadata object on a role, by editing the
     * role's Rights.rights file. Honors dryRun. With cascadeDependencies=true AND value=true, also grants
     * every prerequisite right the platform dependency model requires (grant-direction only, never revokes).
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetRoleRight(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String roleFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String targetFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        String rightAlias = JsonUtils.extractStringArgument(params, "rightName"); //$NON-NLS-1$
        boolean granted = JsonUtils.extractBooleanArgument(params, "value", true); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean cascade = JsonUtils.extractBooleanArgument(params, "cascadeDependencies", false); //$NON-NLS-1$
        if (roleFqn == null || targetFqn == null || rightAlias == null)
        {
            return ToolResult.error("setRoleRight requires ownerFqn (Role.X), targetFqn, rightName").toJson();
        }
        if (targetFqn.indexOf('.') < 0)
        {
            return ToolResult.error("targetFqn must be a metadata FQN like Catalog.Goods").toJson(); //$NON-NLS-1$
        }
        String canonical = BmRightsHelper.canonicalRightName(rightAlias);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson();
        }
        String roleName = roleFqn.startsWith("Role.") //$NON-NLS-1$
            ? roleFqn.substring("Role.".length()) : roleFqn; //$NON-NLS-1$
        if (roleName.isEmpty())
        {
            return ToolResult.error("ownerFqn must be Role.<name>").toJson();
        }
        // Role rights persist in the role's SEPARATE Rights.rights resource (bound to
        // the role by path). We edit that file directly rather than via a BM mutation:
        // a fresh role has no loadable RoleDescription, and a factory-created one
        // cannot be persisted by a BM commit ("Failed to persist reference value
        // RoleDescriptionImpl"). The parse-merge-write preserves RLS / template blocks;
        // the workspace refresh inside writeRightsFile lets EDT re-read the role.
        BmRightsHelper.FileRightResult fr = BmRightsHelper.applyRightToFile(
            project, roleName, targetFqn, canonical, granted, dryRun);
        if (!fr.ok)
        {
            return ToolResult.error(fr.error != null ? fr.error : "set_role_right failed").toJson(); //$NON-NLS-1$
        }
        // J5: dependency cascade (opt-in, GRANT direction only). Granting a right whose
        // platform definition requires prerequisites (Update->Read, Posting->Read+Update)
        // otherwise leaves the role internally inconsistent. With cascadeDependencies=true
        // AND value=true, also grant each prerequisite (excluding the target right itself).
        // Never runs on revoke (value=false): the platform dependency map is grant-direction
        // only, so a cascade can neither over-grant nor auto-revoke. Each prerequisite uses
        // the same idempotent file writer; outcomes surface in cascadedRights.
        java.util.List<Map<String, Object>> cascaded = new java.util.ArrayList<>();
        if (cascade && granted)
        {
            for (String prereq : BmRightsHelper.requiredRightNames(canonical))
            {
                BmRightsHelper.FileRightResult cr = BmRightsHelper.applyRightToFile(
                    project, roleName, targetFqn, prereq, true, dryRun);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rightName", prereq); //$NON-NLS-1$
                row.put("ok", cr.ok); //$NON-NLS-1$
                row.put("idempotentSkip", cr.idempotent); //$NON-NLS-1$
                if (!cr.ok && cr.error != null)
                {
                    row.put("error", cr.error); //$NON-NLS-1$
                }
                cascaded.add(row);
            }
        }
        ToolResult tool = ToolResult.success()
            .put("operation", "set_role_right") //$NON-NLS-1$ //$NON-NLS-2$
            .put("roleFqn", roleFqn) //$NON-NLS-1$
            .put("targetFqn", targetFqn) //$NON-NLS-1$
            .put("rightName", rightAlias) //$NON-NLS-1$
            .put("canonicalRightName", canonical) //$NON-NLS-1$
            .put("requestedValue", granted) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("cascadeDependencies", cascade) //$NON-NLS-1$
            .put("idempotentSkip", fr.idempotent) //$NON-NLS-1$
            .put("objectRightsCreated", fr.objectCreated) //$NON-NLS-1$
            .put("rightCreated", fr.rightCreated) //$NON-NLS-1$
            .put("fileCreated", fr.fileCreated) //$NON-NLS-1$
            .put("persistedTo", "src/Roles/" + roleName + "/Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (fr.previousValue != null)
        {
            tool.put("previousValue", fr.previousValue); //$NON-NLS-1$
        }
        if (!cascaded.isEmpty())
        {
            tool.put("cascadedRights", cascaded); //$NON-NLS-1$
        }
        if (!dryRun && !fr.idempotent)
        {
            tool.put("note", "Rights.rights written; the in-memory model syncs on EDT re-read " //$NON-NLS-1$ //$NON-NLS-2$
                + "(workspace refreshed). Run revalidate_objects on the role if a tool still " //$NON-NLS-1$
                + "shows the old rights this session."); //$NON-NLS-1$
        }
        return tool.toJson();
    }

    /**
     * J5: adds/updates or removes a root-level named RLS restriction template in a role's
     * {@code Rights.rights}. A restriction template is a reusable named RLS condition
     * ({@code <restrictionTemplate><name>..</name><condition>..</condition>}) that object-level
     * RLS restrictions reference by name via {@code #<name>(...)}. File-level parse-merge-write
     * (same mechanism as {@link #opSetRoleRight}), preserving all other rights/RLS/templates.
     *
     * @param remove true -&gt; remove the named template, false -&gt; add/update it
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opRestrictionTemplate(Map<String, String> params, boolean remove)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String roleFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String condition = JsonUtils.extractStringArgument(params, "condition"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String opLabel = remove ? "remove_restriction_template" : "set_restriction_template"; //$NON-NLS-1$ //$NON-NLS-2$
        if (roleFqn == null || templateName == null)
        {
            return ToolResult.error(opLabel + " requires ownerFqn (Role.X) and templateName").toJson(); //$NON-NLS-1$
        }
        if (!remove && condition == null)
        {
            return ToolResult.error("set_restriction_template requires condition (the RLS template body).").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson();
        }
        String roleName = roleFqn.startsWith("Role.") //$NON-NLS-1$
            ? roleFqn.substring("Role.".length()) : roleFqn; //$NON-NLS-1$
        if (roleName.isEmpty())
        {
            return ToolResult.error("ownerFqn must be Role.<name>").toJson();
        }
        BmRightsHelper.FileTemplateResult fr = BmRightsHelper.applyRestrictionTemplateToFile(
            project, roleName, templateName, condition, remove, dryRun);
        if (!fr.ok)
        {
            return ToolResult.error(fr.error != null ? fr.error : opLabel + " failed").toJson(); //$NON-NLS-1$
        }
        ToolResult tool = ToolResult.success()
            .put("operation", opLabel) //$NON-NLS-1$
            .put("roleFqn", roleFqn) //$NON-NLS-1$
            .put("templateName", templateName) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("idempotentSkip", fr.idempotent) //$NON-NLS-1$
            .put("templateCreated", fr.created) //$NON-NLS-1$
            .put("templateUpdated", fr.updated) //$NON-NLS-1$
            .put("templateRemoved", fr.removed) //$NON-NLS-1$
            .put("fileCreated", fr.fileCreated) //$NON-NLS-1$
            .put("persistedTo", "src/Roles/" + roleName + "/Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!dryRun && !fr.idempotent)
        {
            tool.put("note", "Rights.rights written; the in-memory model syncs on EDT re-read " //$NON-NLS-1$ //$NON-NLS-2$
                + "(workspace refreshed). Run revalidate_objects on the role if a tool still " //$NON-NLS-1$
                + "shows the old templates this session."); //$NON-NLS-1$
        }
        return tool.toJson();
    }

    /**
     * J5: adds/updates or removes a per-object, per-right ROW-LEVEL RLS condition on a role -
     * {@code <right>..<restrictionByCondition><condition>..</condition></restrictionByCondition>}
     * under the {@code <object>} in the role's {@code Rights.rights}. Complements set_restriction_template
     * (reusable NAMED templates): this writes the actual condition on a specific object's right (the
     * condition text may reference a template via {@code #<name>(...)}). File-level parse-merge-write
     * (same mechanism as {@link #opSetRoleRight}), preserving all other rights/RLS/templates.
     * Condition-only - field-level restriction is not supported.
     *
     * @param remove true -&gt; strip the restriction from the right, false -&gt; add/update it
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opRoleRestriction(Map<String, String> params, boolean remove)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String roleFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String targetFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        String rightAlias = JsonUtils.extractStringArgument(params, "rightName"); //$NON-NLS-1$
        String condition = JsonUtils.extractStringArgument(params, "condition"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String opLabel = remove ? "remove_role_restriction" : "set_role_restriction"; //$NON-NLS-1$ //$NON-NLS-2$
        if (roleFqn == null || targetFqn == null || rightAlias == null)
        {
            return ToolResult.error(opLabel + " requires ownerFqn (Role.X), targetFqn, rightName").toJson(); //$NON-NLS-1$
        }
        if (targetFqn.indexOf('.') < 0)
        {
            return ToolResult.error("targetFqn must be a metadata FQN like Catalog.Goods").toJson(); //$NON-NLS-1$
        }
        if (!remove && condition == null)
        {
            return ToolResult.error("set_role_restriction requires condition (the RLS condition text).").toJson(); //$NON-NLS-1$
        }
        String canonical = BmRightsHelper.canonicalRightName(rightAlias);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson();
        }
        String roleName = roleFqn.startsWith("Role.") //$NON-NLS-1$
            ? roleFqn.substring("Role.".length()) : roleFqn; //$NON-NLS-1$
        if (roleName.isEmpty())
        {
            return ToolResult.error("ownerFqn must be Role.<name>").toJson();
        }
        BmRightsHelper.FileRestrictionResult fr = BmRightsHelper.applyRoleRestrictionToFile(
            project, roleName, targetFqn, canonical, condition, remove, dryRun);
        if (!fr.ok)
        {
            return ToolResult.error(fr.error != null ? fr.error : opLabel + " failed").toJson(); //$NON-NLS-1$
        }
        ToolResult tool = ToolResult.success()
            .put("operation", opLabel) //$NON-NLS-1$
            .put("roleFqn", roleFqn) //$NON-NLS-1$
            .put("targetFqn", targetFqn) //$NON-NLS-1$
            .put("rightName", rightAlias) //$NON-NLS-1$
            .put("canonicalRightName", canonical) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("idempotentSkip", fr.idempotent) //$NON-NLS-1$
            .put("restrictionCreated", fr.created) //$NON-NLS-1$
            .put("restrictionUpdated", fr.updated) //$NON-NLS-1$
            .put("restrictionRemoved", fr.removed) //$NON-NLS-1$
            .put("objectRightsCreated", fr.objectCreated) //$NON-NLS-1$
            .put("rightCreated", fr.rightCreated) //$NON-NLS-1$
            .put("fileCreated", fr.fileCreated) //$NON-NLS-1$
            .put("persistedTo", "src/Roles/" + roleName + "/Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!dryRun && !fr.idempotent)
        {
            tool.put("note", "Rights.rights written; the in-memory model syncs on EDT re-read " //$NON-NLS-1$ //$NON-NLS-2$
                + "(workspace refreshed). Run revalidate_objects on the role if a tool still " //$NON-NLS-1$
                + "shows the old RLS this session."); //$NON-NLS-1$
        }
        return tool.toJson();
    }
}
