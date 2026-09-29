/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmTemplateHelper;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Template operations (add_template / set_template_content / get_template_content and the
 * cell-level set_template_cell / merge_template_cells / draw_template), extracted from
 * {@link EditMetadataTool} as the second cluster of the god-class split (Inc4). The handlers are
 * thin: they validate parameters with the shared EditMetadataTool helpers and delegate every BM
 * mutation and disk write to {@link BmObjectHelper} / {@link BmTemplateHelper}.
 */
final class TemplateOps
{
    /**
     * Why a data composition schema is not made here.
     * <p>
     * This operation creates the template object and nothing inside it. For every other type that
     * is right - the content arrives through its own workshop afterwards. For a schema it is not:
     * the template comes out empty, this call reports success, and the schema workshop then
     * answers that no schema exists by that FQN. One operation makes both halves, so the caller is
     * sent there instead of being handed a shell.
     * </p>
     *
     * @param canonicalType the resolved template type; may be <code>null</code>
     * @return the refusal text, or <code>null</code> when this type is made here
     */
    static String refusalForSchemaTemplate(String canonicalType)
    {
        if (!"DataCompositionSchema".equals(canonicalType)) //$NON-NLS-1$
        {
            return null;
        }
        return "add_template does not make a data composition schema: it would create the template " //$NON-NLS-1$
            + "object with nothing inside, and dcs_workshop would then answer that no schema " //$NON-NLS-1$
            + "exists by that FQN. Use dcs_workshop operation=create_schema - it makes the " //$NON-NLS-1$
            + "template and the schema together."; //$NON-NLS-1$
    }

    /**
     * The template type the model holds on the object, as its literal name.
     * <p>
     * Read through {@code Enumerator.getLiteral()} rather than {@code Enum.name()}: measured on EDT
     * 2025.2.3, the model's values carry {@code SpreadsheetDocument} as their literal while their
     * Java constant is {@code SPREADSHEET_DOCUMENT}, and it is the literal a caller writes and the
     * model answers to.
     * </p>
     *
     * @param template the template object, just written to
     * @return the literal, or <code>null</code> when the model carries no type for it
     */
    private static String typeOf(MdObject template)
    {
        org.eclipse.emf.ecore.EStructuralFeature feature =
            template.eClass().getEStructuralFeature("templateType"); //$NON-NLS-1$
        return typeLiteralOf(feature == null ? null : template.eGet(feature));
    }

    /**
     * The literal a value the model holds for a template type is written as.
     *
     * @param modelValue the value read back from the template; may be <code>null</code>
     * @return the literal, or <code>null</code> when there is no value
     */
    static String typeLiteralOf(Object modelValue)
    {
        if (modelValue == null)
        {
            return null;
        }
        return modelValue instanceof Enumerator ? ((Enumerator)modelValue).getLiteral()
            : modelValue.toString();
    }

    /**
     * add_template - creates a Template (mdclass Template) under an owner metadata object. Fills
     * SpreadsheetDocument .mxlx and TextDocument/HTMLDocument content files post-commit so the
     * template is usable right away. Honors dryRun. A templateType with no literal in this model,
     * and one the model refuses to set, both fail the operation instead of leaving the template
     * without a type.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opAddTemplate(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (templateName == null || templateName.isEmpty())
        {
            templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        }
        String templateTypeAlias = JsonUtils.extractStringArgument(params, "templateType"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(templateName, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        String canonicalType = BmTemplateHelper.canonicalTemplateType(templateTypeAlias);
        // A type the model does not have is refused before anything is created: the reflection
        // setter reports it as an error rather than throwing, and the error was logged and dropped,
        // so the template came out with no type at all while the answer named the one asked for.
        if (BmTemplateHelper.resolveTemplateTypeLiteral(canonicalType) == null)
        {
            return ToolResult.error("templateType '" + templateTypeAlias //$NON-NLS-1$
                + "' is not a template type this EDT model has. Known types: " //$NON-NLS-1$
                + BmTemplateHelper.templateTypeValues() + ".").toJson(); //$NON-NLS-1$
        }
        String schemaRefusal = refusalForSchemaTemplate(canonicalType);
        if (schemaRefusal != null)
        {
            return ToolResult.error(schemaRefusal).toJson();
        }
        final String resolvedTemplateName = templateName;
        final String resolvedTemplateType = canonicalType;
        final String[] installedType = new String[1];
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) EditMetadataTool.invokeListGetter(owner, "getTemplates"); //$NON-NLS-1$
                if (templates == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName()
                        + "' has no Templates collection.");
                }
                if (BmObjectHelper.findByName(templates, resolvedTemplateName) != null)
                {
                    throw BmObjectHelper.alreadyExists(resolvedTemplateName, ownerFqn, "template");
                }
                MdObject template = BmObjectHelper.createGenericObject("Template");
                if (template == null)
                {
                    throw new RuntimeException("Cannot create template: "
                        + "MdClassFactory.createTemplate() and MdClassPackage "
                        + "lookup both unavailable on this EDT runtime.");
                }
                template.setName(resolvedTemplateName);
                String setErr = BmObjectHelper.setProperty(template, "templateType", //$NON-NLS-1$
                    resolvedTemplateType);
                if (setErr != null)
                {
                    // The type is what the template is: a template without one is not the template
                    // the caller asked for, and answering success would have left it to be found
                    // later, by whatever reads the template and finds a different format inside.
                    throw new RuntimeException("Template type '" + resolvedTemplateType //$NON-NLS-1$
                        + "' was refused by the model: " + setErr); //$NON-NLS-1$
                }
                installedType[0] = typeOf(template);
                templates.add(template);
                // Note: content slot initialization happens AFTER this BM
                // transaction commits - see post-commit block below.
                // BasicTemplate.template is a non-containment EReference,
                // so we cannot attach a SpreadsheetDocumentImpl in this
                // transaction (BM rejects with "Failed to persist reference
                // value..."). Instead we write Template.mxlx directly to
                // disk and let EDT's validator pick it up.
                return resolvedTemplateName + " (type=" + resolvedTemplateType + ")";
            });
        // Post-commit: write empty Template.mxlx for SpreadsheetDocument
        // templates so subsequent set_cell / merge_cells / draw work
        // without requiring a manual EDT GUI open-and-save first.
        if (r.ok && !dryRun && "SpreadsheetDocument".equals(resolvedTemplateType)) //$NON-NLS-1$
        {
            String mxlxErr = BmTemplateHelper.writeEmptyMxlxFile(project, ownerFqn,
                resolvedTemplateName, resolvedTemplateType);
            if (mxlxErr != null)
            {
                Activator.logWarning("addTemplate Template.mxlx write for " //$NON-NLS-1$
                    + ownerFqn + "/" + resolvedTemplateName + ": " + mxlxErr); //$NON-NLS-1$ //$NON-NLS-2$
                r.tags.put("templateContentInitWarning", mxlxErr); //$NON-NLS-1$
            }
        }
        // Post-commit: for text templates (TextDocument / HTMLDocument) write the
        // content file (Template.txt / Template.htmldoc) so the template is usable
        // right away. Fills it with the `content` param when provided, else empty.
        if (r.ok && !dryRun && BmTemplateHelper.templateContentFileName(resolvedTemplateType) != null)
        {
            String content = JsonUtils.extractStringArgument(params, "content"); //$NON-NLS-1$
            String txtErr = BmTemplateHelper.writeTextTemplateContent(project, ownerFqn,
                resolvedTemplateName, resolvedTemplateType, content);
            if (txtErr != null)
            {
                Activator.logWarning("addTemplate text content write for " //$NON-NLS-1$
                    + ownerFqn + "/" + resolvedTemplateName + ": " + txtErr); //$NON-NLS-1$ //$NON-NLS-2$
                r.tags.put("templateContentInitWarning", txtErr); //$NON-NLS-1$
            }
            else if (content != null && !content.isEmpty())
            {
                r.tags.put("contentWritten", //$NON-NLS-1$
                    BmTemplateHelper.templateContentFileName(resolvedTemplateType));
            }
        }
        // The answer names the type the model holds, not the one that was asked for: the two differ
        // exactly when the write did not land the way it was meant to, and that is what a caller
        // reads this field to find out.
        String reportedType = installedType[0] != null ? installedType[0] : resolvedTemplateType;
        ToolResult tool = r.ok ? ToolResult.success() : ToolResult.error(r.error != null ? r.error : "addTemplate failed");
        tool.put("operation", "add_template")
            .put("ownerFqn", ownerFqn)
            .put("templateName", resolvedTemplateName)
            .put("templateType", reportedType);
        if (r.ok && !reportedType.equals(resolvedTemplateType))
        {
            tool.put("requestedTemplateType", resolvedTemplateType); //$NON-NLS-1$
        }
        if (r.message != null)
        {
            tool.put("message", r.message);
        }
        EditMetadataTool.applyTags(tool, r.tags);
        return tool.toJson();
    }

    /**
     * set_template_content - replaces the plain-text content of a TextDocument (Template.txt) or
     * HTMLDocument (Template.htmldoc) template. The type is taken from the templateType param, else
     * auto-detected from an existing content file (default TextDocument). Non-text templates
     * (spreadsheet / DCS / binary) are rejected with a pointer to the right tool, and so is a
     * template that is not on disk at all: the file is only ever written into a template's own
     * folder, never into one built on the way.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetTemplateContent(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (templateName == null || templateName.isEmpty())
        {
            templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        }
        String content = JsonUtils.extractStringArgument(params, "content"); //$NON-NLS-1$
        String templateTypeAlias = JsonUtils.extractStringArgument(params, "templateType"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(templateName, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        if (content == null)
        {
            return ToolResult.error("set_template_content requires 'content' " //$NON-NLS-1$
                + "(pass an empty string to clear the template).").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        // Resolve the target text-template kind. An explicit templateType wins;
        // otherwise detect it from the on-disk content file so we never write a
        // stray Template.txt into a non-text (spreadsheet / DCS / binary) template
        // or a mistyped template name.
        String canonicalType;
        if (templateTypeAlias != null && !templateTypeAlias.isEmpty())
        {
            canonicalType = BmTemplateHelper.canonicalTemplateType(templateTypeAlias);
            // An explicit type says what the template is, not that it is there. Without this the
            // write went to a folder built on the way - src/<Type>/<Owner>/Templates/<Name>/
            // Template.txt - so a mistyped name left a template file behind a name nothing
            // declares, and the call answered success.
            if (!BmTemplateHelper.templateExists(project, ownerFqn, templateName))
            {
                String reason = BmTemplateHelper.templateDirRefusal(ownerFqn, templateName);
                return ToolResult.error("Template '" + templateName + "' under '" + ownerFqn //$NON-NLS-1$ //$NON-NLS-2$
                    + "' does not exist" + (reason == null ? "" : ": " + reason) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " - create it first with add_template.").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            String existing = BmTemplateHelper.existingContentFileName(project, ownerFqn, templateName);
            if ("Template.txt".equals(existing)) //$NON-NLS-1$
            {
                canonicalType = "TextDocument"; //$NON-NLS-1$
            }
            else if ("Template.htmldoc".equals(existing)) //$NON-NLS-1$
            {
                canonicalType = "HTMLDocument"; //$NON-NLS-1$
            }
            else if (existing != null)
            {
                return ToolResult.error("Template '" + templateName + "' under '" + ownerFqn //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is a non-text template (" + existing + "). Use mxl_workshop for " //$NON-NLS-1$ //$NON-NLS-2$
                    + "SpreadsheetDocument, dcs_workshop for DataCompositionSchema, or pass " //$NON-NLS-1$
                    + "templateType explicitly for a text type.").toJson(); //$NON-NLS-1$
            }
            else
            {
                return ToolResult.error("Cannot determine the text-template type for '" //$NON-NLS-1$
                    + templateName + "' under '" + ownerFqn + "' - no existing Template.txt / " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Template.htmldoc found. Pass templateType (TextDocument / HTMLDocument), " //$NON-NLS-1$
                    + "or create it first with add_template.").toJson(); //$NON-NLS-1$
            }
        }
        String contentFile = BmTemplateHelper.templateContentFileName(canonicalType);
        if (contentFile == null)
        {
            return ToolResult.error("set_template_content supports only TextDocument (Template.txt) " //$NON-NLS-1$
                + "and HTMLDocument (Template.htmldoc); got templateType '" + canonicalType //$NON-NLS-1$
                + "'. Use mxl_workshop for SpreadsheetDocument, dcs_workshop for DataCompositionSchema.") //$NON-NLS-1$
                .toJson();
        }
        int bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // The same guard for a template that is there but holds another format: a text file written
        // beside a spreadsheet template makes the folder hold two contents, which is not a template
        // EDT can open.
        String onDisk = BmTemplateHelper.existingContentFileName(project, ownerFqn, templateName);
        if (onDisk != null && !onDisk.equals(contentFile))
        {
            return ToolResult.error("Template '" + templateName + "' under '" + ownerFqn //$NON-NLS-1$ //$NON-NLS-2$
                + "' holds " + onDisk + ", not " + contentFile + ": pass the type that matches the " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "file on disk, or use the workshop that owns that format - mxl_workshop for " //$NON-NLS-1$
                + "SpreadsheetDocument, dcs_workshop for DataCompositionSchema.").toJson(); //$NON-NLS-1$
        }
        if (dryRun)
        {
            return ToolResult.success()
                .put("operation", "set_template_content") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", ownerFqn) //$NON-NLS-1$
                .put("templateName", templateName) //$NON-NLS-1$
                .put("contentFile", contentFile) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("bytes", bytes) //$NON-NLS-1$
                .put("message", "Preview: would write " + contentFile + " (no changes applied).") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        String writeErr = BmTemplateHelper.writeTextTemplateContent(project, ownerFqn,
            templateName, canonicalType, content);
        if (writeErr != null)
        {
            return ToolResult.error(writeErr).toJson();
        }
        return ToolResult.success()
            .put("operation", "set_template_content") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", ownerFqn) //$NON-NLS-1$
            .put("templateName", templateName) //$NON-NLS-1$
            .put("contentFile", contentFile) //$NON-NLS-1$
            .put("bytes", bytes) //$NON-NLS-1$
            .put("message", "Template content written.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * get_template_content - reads the plain-text content of a TextDocument / HTMLDocument template
     * (auto-detecting Template.txt / Template.htmldoc). Non-text templates return a clear error.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opGetTemplateContent(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (templateName == null || templateName.isEmpty())
        {
            templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        }
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(templateName, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        BmTemplateHelper.TemplateContent tc =
            BmTemplateHelper.readTextTemplateContent(project, ownerFqn, templateName);
        if (tc.error != null)
        {
            return ToolResult.error(tc.error).toJson();
        }
        return ToolResult.success()
            .put("operation", "get_template_content") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", ownerFqn) //$NON-NLS-1$
            .put("templateName", templateName) //$NON-NLS-1$
            .put("contentFile", tc.fileName) //$NON-NLS-1$
            .put("content", tc.content) //$NON-NLS-1$
            .toJson();
    }

    /**
     * set_template_cell / merge_template_cells / draw_template - cell-level MXL mutation. Requires
     * the EDT layout service. When unavailable, returns a graceful {@code mxlApiNotFound} error tag
     * with a GUI-fallback hint.
     *
     * @param op the concrete operation name (set_template_cell / merge_template_cells / draw_template)
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opTemplateCellOp(String op, Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            try
            {
                throw BmTemplateHelper.mxlApiNotFound(op);
            }
            catch (MetadataGuards.BlockedGuardException blocked)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                ToolResult result = ToolResult.error(v.error)
                    .put("operation", op)
                    .put("hint", v.hint != null ? v.hint : "");
                if (v.tag != null)
                {
                    result.put(v.tag.name, v.tag.data);
                }
                return result.toJson();
            }
        }
        // These three names never wrote anything. They answered success with a note saying the
        // write would arrive in a later build, so a caller was told the template had changed when
        // it had not - and no parameters were ever declared for them, so there was no way to say
        // which cell to write either. Cell writing lives in mxl_workshop, works, and takes the
        // coordinates; the caller is sent there rather than handed another success that is not one.
        return ToolResult.error("edit_metadata " + op + " does not change a template: it never " //$NON-NLS-1$ //$NON-NLS-2$
            + "wrote anything and declares no coordinates to write by. Use mxl_workshop - " //$NON-NLS-1$
            + "operation=set_cell (row, col, text), operation=merge_cells (fromRow, fromCol, " //$NON-NLS-1$
            + "toRow, toCol) or operation=draw (layout).") //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .toJson();
    }
}
