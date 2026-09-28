/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmRightsHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;

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
     * {@link #opSetRoleRight(Map)} with the caller's own answers for the role, the object and the
     * right. A {@code null} gate reads the project model.
     * <p>
     * A role, object or right the configuration does not have is refused before the file is
     * written, and that refusal covers the dependency cascade as well: the prerequisites are not
     * written when the requested right itself was refused.
     * </p>
     *
     * @param params the tool parameters
     * @param gate the answers, or {@code null} to read the project model
     * @return the JSON result document
     */
    String opSetRoleRight(Map<String, String> params, BmRightsHelper.RightsGate gate)
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
        String roleName = roleNameOf(roleFqn);
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
        // The same gate is handed to every write, including the cascade, so a missing
        // object or an inapplicable right stops before the first byte is written.
        BmRightsHelper.RightsGate effective = gate != null
            ? gate : BmRightsHelper.RightsGate.forProject(project);
        BmRightsHelper.FileRightResult fr = BmRightsHelper.applyRightToFile(
            project, roleName, targetFqn, canonical, granted, dryRun, effective);
        if (!fr.ok)
        {
            return refused(fr.error, "set_role_right failed", fr.failureKind, fr.subject, //$NON-NLS-1$
                fr.subjectName, fr.objectKind, fr.applicableRights, projectName);
        }
        // J5: dependency cascade (opt-in, GRANT direction only). Granting a right whose
        // platform definition requires prerequisites (Update->Read, Posting->Read+Update)
        // otherwise leaves the role internally inconsistent. With cascadeDependencies=true
        // AND value=true, also grant each prerequisite (excluding the target right itself).
        // Never runs on revoke (value=false): the platform dependency map is grant-direction
        // only, so a cascade can neither over-grant nor auto-revoke. Each prerequisite uses
        // the same idempotent file writer; outcomes surface in cascadedRights.
        List<Map<String, Object>> cascaded = new java.util.ArrayList<>();
        if (cascade && granted)
        {
            for (String prereq : BmRightsHelper.requiredRightNames(canonical))
            {
                BmRightsHelper.FileRightResult cr = BmRightsHelper.applyRightToFile(
                    project, roleName, targetFqn, prereq, true, dryRun, effective);
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
            .put("persistedTo", persistedTo(effective, roleName)); //$NON-NLS-1$
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
     * set_role_right - grants or revokes a single right of a metadata object on a role, by editing the
     * role's Rights.rights file. Honors dryRun. With cascadeDependencies=true AND value=true, also grants
     * every prerequisite right the platform dependency model requires (grant-direction only, never revokes).
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetRoleRight(Map<String, String> params)
    {
        return opSetRoleRight(params, null);
    }

    /**
     * {@link #opRestrictionTemplate(Map, boolean)} with the caller's own answers. A {@code null}
     * gate reads the project model. Only the role is checked: a template has no object and no right.
     * A role the configuration does not have is refused before the file is written.
     *
     * @param params the tool parameters
     * @param remove true to remove the named template, false to add or update it
     * @param gate the answers, or {@code null} to read the project model
     * @return the JSON result document
     */
    String opRestrictionTemplate(Map<String, String> params, boolean remove,
        BmRightsHelper.RightsGate gate)
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
        String roleName = roleNameOf(roleFqn);
        if (roleName.isEmpty())
        {
            return ToolResult.error("ownerFqn must be Role.<name>").toJson();
        }
        BmRightsHelper.RightsGate effective = gate != null
            ? gate : BmRightsHelper.RightsGate.forProject(project);
        BmRightsHelper.FileTemplateResult fr = BmRightsHelper.applyRestrictionTemplateToFile(
            project, roleName, templateName, condition, remove, dryRun, effective);
        if (!fr.ok)
        {
            return refused(fr.error, opLabel + " failed", fr.failureKind, fr.subject, //$NON-NLS-1$
                fr.subjectName, fr.objectKind, fr.applicableRights, projectName);
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
            .put("persistedTo", persistedTo(effective, roleName)); //$NON-NLS-1$
        if (!dryRun && !fr.idempotent)
        {
            tool.put("note", "Rights.rights written; the in-memory model syncs on EDT re-read " //$NON-NLS-1$ //$NON-NLS-2$
                + "(workspace refreshed). Run revalidate_objects on the role if a tool still " //$NON-NLS-1$
                + "shows the old templates this session."); //$NON-NLS-1$
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
     * @param params the tool parameters
     * @param remove true to remove the named template, false to add or update it
     * @return the JSON result document
     */
    String opRestrictionTemplate(Map<String, String> params, boolean remove)
    {
        return opRestrictionTemplate(params, remove, null);
    }

    /**
     * {@link #opRoleRestriction(Map, boolean)} with the caller's own answers. A {@code null} gate
     * reads the project model. A role, object or right the configuration does not have is refused
     * before the file is written.
     *
     * @param params the tool parameters
     * @param remove true to strip the restriction, false to add or update it
     * @param gate the answers, or {@code null} to read the project model
     * @return the JSON result document
     */
    String opRoleRestriction(Map<String, String> params, boolean remove, BmRightsHelper.RightsGate gate)
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
        String roleName = roleNameOf(roleFqn);
        if (roleName.isEmpty())
        {
            return ToolResult.error("ownerFqn must be Role.<name>").toJson();
        }
        BmRightsHelper.RightsGate effective = gate != null
            ? gate : BmRightsHelper.RightsGate.forProject(project);
        BmRightsHelper.FileRestrictionResult fr = BmRightsHelper.applyRoleRestrictionToFile(
            project, roleName, targetFqn, canonical, condition, remove, dryRun, effective);
        if (!fr.ok)
        {
            return refused(fr.error, opLabel + " failed", fr.failureKind, fr.subject, //$NON-NLS-1$
                fr.subjectName, fr.objectKind, fr.applicableRights, projectName);
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
            .put("persistedTo", persistedTo(effective, roleName)); //$NON-NLS-1$
        if (!dryRun && !fr.idempotent)
        {
            tool.put("note", "Rights.rights written; the in-memory model syncs on EDT re-read " //$NON-NLS-1$ //$NON-NLS-2$
                + "(workspace refreshed). Run revalidate_objects on the role if a tool still " //$NON-NLS-1$
                + "shows the old RLS this session."); //$NON-NLS-1$
        }
        return tool.toJson();
    }

    /**
     * Adds, updates or removes a per-object, per-right row-level RLS condition on a role -
     * {@code <right>..<restrictionByCondition><condition>..</condition></restrictionByCondition>}
     * under the {@code <object>} in the role's {@code Rights.rights}. Complements a named
     * restriction template: this writes the condition on a specific object's right (the text may
     * reference a template via {@code #<name>(...)}). Condition-only - field-level restriction is
     * not supported.
     *
     * @param params the tool parameters
     * @param remove true to strip the restriction, false to add or update it
     * @return the JSON result document
     */
    String opRoleRestriction(Map<String, String> params, boolean remove)
    {
        return opRoleRestriction(params, remove, null);
    }

    /**
     * The simple role name from an owner address. A prefix whose English singular is Role is
     * removed, in either language and any case. Any other address is returned unchanged, and a
     * dotted remainder is still refused by the rights writer.
     *
     * @param ownerFqn the owner the caller named
     * @return the name used as the role, or an empty string when the prefix was the whole address
     */
    private static String roleNameOf(String ownerFqn)
    {
        if (ownerFqn == null)
        {
            return ""; //$NON-NLS-1$
        }
        int dot = ownerFqn.indexOf('.');
        if (dot <= 0)
        {
            return ownerFqn;
        }
        String head = ownerFqn.substring(0, dot);
        if ("Role".equals(MetadataTypeCatalog.toEnglishSingular(head))) //$NON-NLS-1$
        {
            return ownerFqn.substring(dot + 1);
        }
        return ownerFqn;
    }

    /**
     * The path the writer used, under the role name the model stores.
     *
     * @param gate the gate that resolved the role
     * @param roleName the simple name after the type prefix was removed
     * @return the path relative to the project
     */
    private static String persistedTo(BmRightsHelper.RightsGate gate, String roleName)
    {
        return "src/Roles/" + gate.roleDirectoryName(roleName) + "/Rights.rights"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A refused write, with the wire tag the helper chose. The tag object names what was missing
     * or which right does not apply, and the applicable rights are repeated at the top level so a
     * caller does not have to open the tag to read them.
     *
     * @param error the sentence from the helper, or {@code null}
     * @param fallback the sentence used when the helper gave none
     * @param failureKind the wire tag, or {@code null} when the refusal has none
     * @param subject {@code role}, {@code object} or {@code right}
     * @param subjectName the name that failed
     * @param objectKind the metadata kind, for an inapplicable right
     * @param applicable the rights that do apply, or {@code null}
     * @param projectName the project the call named
     * @return the JSON error document
     */
    private static String refused(String error, String fallback, String failureKind, String subject,
        String subjectName, String objectKind, List<String> applicable, String projectName)
    {
        ToolResult result = ToolResult.error(error != null ? error : fallback);
        if (failureKind != null)
        {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("kind", subject); //$NON-NLS-1$
            tag.put("name", subjectName); //$NON-NLS-1$
            if (objectKind != null)
            {
                tag.put("objectKind", objectKind); //$NON-NLS-1$
            }
            if (projectName != null)
            {
                tag.put("projectName", projectName); //$NON-NLS-1$
            }
            if (applicable != null)
            {
                tag.put("applicableRights", applicable); //$NON-NLS-1$
            }
            result.put(failureKind, tag);
        }
        if (applicable != null)
        {
            result.put("applicableRights", applicable); //$NON-NLS-1$
        }
        return result.toJson();
    }
}
