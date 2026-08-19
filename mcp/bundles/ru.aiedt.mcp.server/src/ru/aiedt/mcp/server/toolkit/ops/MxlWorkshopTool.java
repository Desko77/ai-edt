/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com.google.gson.JsonSyntaxException;

import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmTemplateHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * MXL spreadsheet template constructor.
 * <p>
 * <b>1.42.2 status:</b> all four operations are natively implemented.
 * {@code create_template} writes a Template MdObject; cell-level ops
 * ({@code set_cell}, {@code merge_cells}, {@code draw}) mutate the
 * underlying {@code com._1c.g5.v8.dt.moxel.SpreadsheetDocument} directly
 * via {@link BmTemplateHelper#setCellText} / {@link BmTemplateHelper#mergeCells}.
 * When the moxel API is unreachable (very old EDT runtime) the tool still
 * returns a structured {@code mxlApiNotFound} tag.
 */
public class MxlWorkshopTool implements IMcpTool
{
    public static final String NAME = "mxl_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "MXL spreadsheet template constructor. 7 operations: create_template, " //$NON-NLS-1$
            + "set_cell, format_cells, merge_cells, draw, add_drawing, remove_drawing, " //$NON-NLS-1$
            + "read_template. " //$NON-NLS-1$
            + "They manipulate (or, for read_template, read back) the moxel " //$NON-NLS-1$
            + "SpreadsheetDocument model directly. read_template returns dimensions, " //$NON-NLS-1$
            + "the populated cell map, merged ranges and drawing ids. " //$NON-NLS-1$
            + "Coordinates are 1-based. set_cell takes row/col/text; " //$NON-NLS-1$
            + "merge_cells takes fromRow/fromCol/toRow/toCol; draw takes a " //$NON-NLS-1$
            + "JSON layout with cells/merges arrays; add_drawing places a " //$NON-NLS-1$
            + "Line/Rectangle/Ellipse/Text graphic anchored to begin/end cells."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "create_template / set_cell / merge_cells / draw / add_drawing / remove_drawing / read_template / help", //$NON-NLS-1$
                true)
            .stringProperty("projectName", "Name of the EDT project to work in") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "Object FQN that owns the template (Catalog.X / Document.X / DataProcessor.X)") //$NON-NLS-1$
            .stringProperty("templateName", "Template name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("templateType", //$NON-NLS-1$
                "SpreadsheetDocument (default) / TextDocument / DataCompositionSchema / etc.") //$NON-NLS-1$
            .integerProperty("row", "Cell row (1-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("col", "Cell column (1-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("text", "Cell text content") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", //$NON-NLS-1$
                "Language tag for the LocalString content (default 'ru')") //$NON-NLS-1$
            .integerProperty("fromRow", "Merge range from-row") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("fromCol", "Merge range from-col") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("toRow", "Merge range to-row") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("toCol", "Merge range to-col") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("layout", //$NON-NLS-1$
                "JSON layout for draw: {cells:[{row,col,text}],merges:[{from,to}]}") //$NON-NLS-1$
            .stringProperty("drawingType", //$NON-NLS-1$
                "add_drawing: Line / Rectangle / Ellipse / Text (RU aliases ok)") //$NON-NLS-1$
            .integerProperty("beginRow", "add_drawing: top-left anchor row (1-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("beginColumn", "add_drawing: top-left anchor column (1-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("endRow", "add_drawing: bottom-right anchor row (>= beginRow)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("endColumn", //$NON-NLS-1$
                "add_drawing: bottom-right anchor column (>= beginColumn)") //$NON-NLS-1$
            .integerProperty("beginRowOffset", //$NON-NLS-1$
                "add_drawing: intra-cell offset for begin row (default 0)") //$NON-NLS-1$
            .integerProperty("beginColumnOffset", //$NON-NLS-1$
                "add_drawing: intra-cell offset for begin column (default 0)") //$NON-NLS-1$
            .integerProperty("endRowOffset", //$NON-NLS-1$
                "add_drawing: intra-cell offset for end row (default 0)") //$NON-NLS-1$
            .integerProperty("endColumnOffset", //$NON-NLS-1$
                "add_drawing: intra-cell offset for end column (default 0)") //$NON-NLS-1$
            .integerProperty("formatIndex", //$NON-NLS-1$
                "add_drawing: format-table index for stroke/fill (default: a fresh empty format)") //$NON-NLS-1$
            .integerProperty("zOrder", "add_drawing: explicit z-order (default = drawing id)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("drawingId", "remove_drawing: id of the drawing to remove") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("textPlacement", //$NON-NLS-1$
                "format_cells: how text behaves when it does not fit - auto / cut / block / wrap.") //$NON-NLS-1$
            .integerProperty("textOrientation", //$NON-NLS-1$
                "format_cells: text rotation in degrees.") //$NON-NLS-1$
            .integerProperty("rowHeight", //$NON-NLS-1$
                "format_cells: explicit row height. There is no auto-height flag in the model - a " //$NON-NLS-1$
                    + "row with no explicit height whose cells wrap is what the platform grows to " //$NON-NLS-1$
                    + "fit the text.") //$NON-NLS-1$
            .booleanProperty("autoColumnWidth", //$NON-NLS-1$
                "format_cells: let the column width follow its content.") //$NON-NLS-1$
            .integerProperty("columnWidth", "format_cells: explicit column width.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("columnWidthWeight", //$NON-NLS-1$
                "format_cells: this column's share when the available width is distributed.") //$NON-NLS-1$
            .booleanProperty("dryRun", "Preview without applying (default false)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations.") //$NON-NLS-1$
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
        String op = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (op == null || op.isEmpty())
        {
            return ToolResult.error("operation is required").toJson(); //$NON-NLS-1$
        }
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation: " + op //$NON-NLS-1$
                + ". Available: " + String.join(", ", OPS.keySet())).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        switch (op)
        {
            case "create_template": //$NON-NLS-1$
                return opCreateTemplate(params);
            case "set_cell": //$NON-NLS-1$
                return opSetCell(params);
            case "format_cells": //$NON-NLS-1$
                return opFormatCells(params);
            case "merge_cells": //$NON-NLS-1$
                return opMergeCells(params);
            case "draw": //$NON-NLS-1$
                return opDraw(params);
            case "add_drawing": //$NON-NLS-1$
                return opAddDrawing(params);
            case "remove_drawing": //$NON-NLS-1$
                return opRemoveDrawing(params);
            case "read_template": //$NON-NLS-1$
                return opReadTemplate(params);
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
    }

    private String opCreateTemplate(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String templateType = JsonUtils.extractStringArgument(params, "templateType"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult
                .error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        // 1.42.3: route the alias through canonicalTemplateType (same path
        // as edit_metadata add_template) so RU/EN aliases work and an empty
        // input falls back to the upstream default. Without this, a sloppy
        // alias would surface as "No enum constant TemplateType.<X>" later.
        final String canonicalType = BmTemplateHelper.canonicalTemplateType(templateType);
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) invokeListGetter(owner,
                    "getTemplates"); //$NON-NLS-1$
                if (templates == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Templates collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(templates, templateName) != null)
                {
                    throw BmObjectHelper.alreadyExists(templateName, ownerFqn, "template"); //$NON-NLS-1$
                }
                MdObject template = BmObjectHelper.createGenericObject("Template"); //$NON-NLS-1$
                if (template == null)
                {
                    throw new RuntimeException("Cannot create template: " //$NON-NLS-1$
                        + "MdClassFactory.createTemplate() and MdClassPackage " //$NON-NLS-1$
                        + "lookup both unavailable on this EDT runtime."); //$NON-NLS-1$
                }
                template.setName(templateName);
                String setErr = BmObjectHelper.setProperty(template, "templateType", //$NON-NLS-1$
                    canonicalType);
                if (setErr != null)
                {
                    throw new RuntimeException("Cannot set templateType=" + canonicalType //$NON-NLS-1$
                        + ": " + setErr); //$NON-NLS-1$
                }
                templates.add(template);
                // See EditMetadataTool.opAddTemplate for the rationale.
                // We cannot attach SpreadsheetDocument inside the BM
                // transaction (non-containment EReference), so the empty
                // Template.mxlx is written to disk in the post-commit
                // block below.
                return templateName + " (type=" + canonicalType + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        // Post-commit: write empty Template.mxlx so subsequent
        // set_cell / merge_cells / draw can populate content without
        // requiring a manual EDT GUI open-and-save first.
        if (r.ok && !dryRun && "SpreadsheetDocument".equals(canonicalType)) //$NON-NLS-1$
        {
            String mxlxErr = BmTemplateHelper.writeEmptyMxlxFile(project, ownerFqn,
                templateName, canonicalType);
            if (mxlxErr != null)
            {
                ru.aiedt.mcp.server.Activator.logWarning(
                    "create_template Template.mxlx write for " + ownerFqn //$NON-NLS-1$
                        + "/" + templateName + ": " + mxlxErr); //$NON-NLS-1$ //$NON-NLS-2$
                r.tags.put("templateContentInitWarning", mxlxErr); //$NON-NLS-1$
            }
        }
        return formatResult(r, "create_template"); //$NON-NLS-1$
    }

    /**
     * 1.42.2: native cell-level set via the moxel SpreadsheetDocument model.
     */
    private String opSetCell(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("set_cell"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int row = JsonUtils.extractIntArgument(params, "row", -1); //$NON-NLS-1$
        int col = JsonUtils.extractIntArgument(params, "col", -1); //$NON-NLS-1$
        String text = JsonUtils.extractStringArgument(params, "text"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null
            || row < 1 || col < 1)
        {
            return ToolResult.error("projectName, ownerFqn, templateName, row (>=1), col (>=1) are required") //$NON-NLS-1$
                .toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final int rowF = row;
        final int colF = col;
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                BmTemplateHelper.setCellText(doc, rowF, colF, text, language);
                // Persist BM-memory snapshot to Template.mxlx so the change
                // survives an EDT restart. Without this, set_cell results
                // only live in the in-memory moxel model.
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project,
                        ownerFqn, templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "(" + rowF + "," + colF + ")=" + (text == null ? "" : text); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            r.tags.put("templateMutationPersistWarning", persistErrorRef[0]); //$NON-NLS-1$
        }
        return formatResult(r, "set_cell"); //$NON-NLS-1$
    }

    /**
     * 1.42.2: native cell merge via the moxel SpreadsheetDocument model.
     */
    private String opMergeCells(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("merge_cells"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int fromRow = JsonUtils.extractIntArgument(params, "fromRow", -1); //$NON-NLS-1$
        int fromCol = JsonUtils.extractIntArgument(params, "fromCol", -1); //$NON-NLS-1$
        int toRow = JsonUtils.extractIntArgument(params, "toRow", -1); //$NON-NLS-1$
        int toCol = JsonUtils.extractIntArgument(params, "toCol", -1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null
            || fromRow < 1 || fromCol < 1 || toRow < fromRow || toCol < fromCol)
        {
            return ToolResult.error("projectName, ownerFqn, templateName, fromRow (>=1), " //$NON-NLS-1$
                + "fromCol (>=1), toRow (>=fromRow), toCol (>=fromCol) are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final int fromRowF = fromRow;
        final int fromColF = fromCol;
        final int toRowF = toRow;
        final int toColF = toCol;
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                BmTemplateHelper.mergeCells(doc, fromRowF, fromColF, toRowF, toColF);
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project,
                        ownerFqn, templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "(" + fromRowF + "," + fromColF + ")-(" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + toRowF + "," + toColF + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            r.tags.put("templateMutationPersistWarning", persistErrorRef[0]); //$NON-NLS-1$
        }
        return formatResult(r, "merge_cells"); //$NON-NLS-1$
    }

    /**
     * Applies presentation properties to a rectangle of cells and the columns under it.
     * <p>
     * Every property is optional and only what is passed is touched: this is a formatter, not a
     * style reset, and a template arrives with a look somebody chose. Passing none of them is
     * refused rather than treated as a no-op, because a call that changes nothing and reports
     * success reads as a call that worked.
     * </p>
     *
     * @param params the call's arguments.
     * @return the result
     */
    private String opFormatCells(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("format_cells"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int fromRow = JsonUtils.extractIntArgument(params, "fromRow", //$NON-NLS-1$
            JsonUtils.extractIntArgument(params, "row", -1)); //$NON-NLS-1$
        int fromCol = JsonUtils.extractIntArgument(params, "fromCol", //$NON-NLS-1$
            JsonUtils.extractIntArgument(params, "col", -1)); //$NON-NLS-1$
        int toRow = JsonUtils.extractIntArgument(params, "toRow", fromRow); //$NON-NLS-1$
        int toCol = JsonUtils.extractIntArgument(params, "toCol", fromCol); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String placement = JsonUtils.extractStringArgument(params, "textPlacement"); //$NON-NLS-1$
        Integer orientation = optionalInt(params, "textOrientation"); //$NON-NLS-1$
        Integer rowHeight = optionalInt(params, "rowHeight"); //$NON-NLS-1$
        Integer columnWidth = optionalInt(params, "columnWidth"); //$NON-NLS-1$
        Integer widthWeight = optionalInt(params, "columnWidthWeight"); //$NON-NLS-1$
        Boolean autoColumnWidth = params.containsKey("autoColumnWidth") //$NON-NLS-1$
            ? Boolean.valueOf(JsonUtils.extractBooleanArgument(params, "autoColumnWidth", false)) //$NON-NLS-1$
            : null;

        if (projectName == null || ownerFqn == null || templateName == null
            || fromRow < 1 || fromCol < 1 || toRow < fromRow || toCol < fromCol)
        {
            return ToolResult.error("projectName, ownerFqn, templateName and a cell range are " //$NON-NLS-1$
                + "required: row/col for one cell, or fromRow/fromCol/toRow/toCol for a " //$NON-NLS-1$
                + "rectangle").toJson(); //$NON-NLS-1$
        }
        if (placement == null && orientation == null && rowHeight == null && columnWidth == null
            && widthWeight == null && autoColumnWidth == null)
        {
            return ToolResult.error("nothing to apply: pass at least one of textPlacement, " //$NON-NLS-1$
                + "textOrientation, rowHeight, autoColumnWidth, columnWidth, " //$NON-NLS-1$
                + "columnWidthWeight").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final int fromRowF = fromRow;
        final int fromColF = fromCol;
        final int toRowF = toRow;
        final int toColF = toCol;
        final String[] persistErrorRef = { null };
        final String[] formatErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc,
                    fromRowF, fromColF, toRowF, toColF, placement, orientation, rowHeight,
                    autoColumnWidth, columnWidth, widthWeight);
                if (outcome.error != null)
                {
                    formatErrorRef[0] = outcome.error;
                    return outcome.error;
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project,
                        ownerFqn, templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return outcome.cellsChanged + " cells, " + outcome.columnsChanged + " columns"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (formatErrorRef[0] != null)
        {
            // The transaction may well have "succeeded" without changing anything - the request was
            // rejected inside it. Reported as a failure so a bad textPlacement is not answered with
            // success and an unchanged template.
            return ToolResult.error(formatErrorRef[0]).put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            r.tags.put("templateMutationPersistWarning", persistErrorRef[0]); //$NON-NLS-1$
        }
        return formatResult(r, "format_cells"); //$NON-NLS-1$
    }

    /**
     * Reads an integer argument that is meaningfully absent.
     * <p>
     * A formatter needs the difference between "set this to zero" and "leave it alone", which a
     * default-valued read cannot express.
     * </p>
     *
     * @param params the arguments.
     * @param name the argument.
     * @return the value, or {@code null} when it was not passed
     */
    private static Integer optionalInt(Map<String, String> params, String name)
    {
        String raw = JsonUtils.extractStringArgument(params, name);
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.valueOf(raw.trim());
        }
        catch (NumberFormatException notANumber)
        {
            return null;
        }
    }

    /**
     * 1.42.2: batch draw - applies a JSON layout document containing arrays
     * of cell setters and merge ranges. Format:
     *
     * <pre>
     * {
     *   "cells": [{"row":1,"col":1,"text":"Header","language":"ru"}, ...],
     *   "merges":[{"fromRow":1,"fromCol":1,"toRow":1,"toCol":5}, ...]
     * }
     * </pre>
     */
    private String opDraw(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("draw"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String layoutJson = JsonUtils.extractStringArgument(params, "layout"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null
            || layoutJson == null || layoutJson.isEmpty())
        {
            return ToolResult
                .error("projectName, ownerFqn, templateName, layout are required").toJson(); //$NON-NLS-1$
        }
        DrawLayout layout;
        try
        {
            layout = GsonHolder.fromJson(layoutJson, DrawLayout.class);
        }
        catch (JsonSyntaxException jse)
        {
            return ToolResult.error("Invalid layout JSON: " + jse.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (layout == null)
        {
            return ToolResult.error("layout is empty").toJson(); //$NON-NLS-1$
        }
        // Pre-validate every entry before opening a BM write transaction.
        // Otherwise a bad row/col deep in the array would partially apply
        // earlier entries and then abort with an opaque exception.
        if (layout.cells != null)
        {
            for (int i = 0; i < layout.cells.size(); i++)
            {
                DrawCell dc = layout.cells.get(i);
                if (dc == null || dc.row < 1 || dc.col < 1)
                {
                    return ToolResult.error("layout.cells[" + i //$NON-NLS-1$
                        + "]: row and col must be 1-based positive integers " //$NON-NLS-1$
                        + "(got row=" + (dc == null ? "null" : dc.row) //$NON-NLS-1$ //$NON-NLS-2$
                        + ", col=" + (dc == null ? "null" : dc.col) + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        if (layout.merges != null)
        {
            for (int i = 0; i < layout.merges.size(); i++)
            {
                DrawMerge dm = layout.merges.get(i);
                if (dm == null || dm.fromRow < 1 || dm.fromCol < 1
                    || dm.toRow < dm.fromRow || dm.toCol < dm.fromCol)
                {
                    return ToolResult.error("layout.merges[" + i //$NON-NLS-1$
                        + "]: fromRow/fromCol must be >=1 and toRow/toCol >= " //$NON-NLS-1$
                        + "fromRow/fromCol (got " + (dm == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
                            : "from=(" + dm.fromRow + "," + dm.fromCol //$NON-NLS-1$ //$NON-NLS-2$
                                + ") to=(" + dm.toRow + "," + dm.toCol + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + ")").toJson(); //$NON-NLS-1$
                }
            }
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final DrawLayout layoutF = layout;
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                int cellCount = 0;
                int mergeCount = 0;
                if (layoutF.cells != null)
                {
                    for (DrawCell dc : layoutF.cells)
                    {
                        BmTemplateHelper.setCellText(doc, dc.row, dc.col, dc.text, dc.language);
                        cellCount++;
                    }
                }
                if (layoutF.merges != null)
                {
                    for (DrawMerge dm : layoutF.merges)
                    {
                        BmTemplateHelper.mergeCells(doc, dm.fromRow, dm.fromCol, dm.toRow,
                            dm.toCol);
                        mergeCount++;
                    }
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project,
                        ownerFqn, templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return cellCount + " cells, " + mergeCount + " merges"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            r.tags.put("templateMutationPersistWarning", persistErrorRef[0]); //$NON-NLS-1$
        }
        return formatResult(r, "draw"); //$NON-NLS-1$
    }

    /**
     * 1.43: places a geometric drawing (Line / Rectangle / Ellipse / Text) on
     * the spreadsheet, anchored by begin (top-left) and end (bottom-right)
     * cells. The drawing is a containment, non-transient model feature, so
     * {@code persistTemplateMxlx} serializes it to {@code Template.mxlx}.
     * Line / Rectangle / Ellipse stroke and fill come from the format-table
     * entry referenced by {@code formatIndex}; Text drawings take a caption.
     */
    private String opAddDrawing(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("add_drawing"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String drawingTypeIn = JsonUtils.extractStringArgument(params, "drawingType"); //$NON-NLS-1$
        int beginRow = JsonUtils.extractIntArgument(params, "beginRow", -1); //$NON-NLS-1$
        int beginCol = JsonUtils.extractIntArgument(params, "beginColumn", -1); //$NON-NLS-1$
        int endRow = JsonUtils.extractIntArgument(params, "endRow", -1); //$NON-NLS-1$
        int endCol = JsonUtils.extractIntArgument(params, "endColumn", -1); //$NON-NLS-1$
        int beginRowOffset = JsonUtils.extractIntArgument(params, "beginRowOffset", 0); //$NON-NLS-1$
        int beginColOffset = JsonUtils.extractIntArgument(params, "beginColumnOffset", 0); //$NON-NLS-1$
        int endRowOffset = JsonUtils.extractIntArgument(params, "endRowOffset", 0); //$NON-NLS-1$
        int endColOffset = JsonUtils.extractIntArgument(params, "endColumnOffset", 0); //$NON-NLS-1$
        int formatIndex = JsonUtils.extractIntArgument(params, "formatIndex", -1); //$NON-NLS-1$
        int zOrderRaw = JsonUtils.extractIntArgument(params, "zOrder", -1); //$NON-NLS-1$
        String text = JsonUtils.extractStringArgument(params, "text"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null
            || drawingTypeIn == null)
        {
            return ToolResult
                .error("projectName, ownerFqn, templateName, drawingType are required").toJson(); //$NON-NLS-1$
        }
        String canonicalType = BmTemplateHelper.canonicalDrawingType(drawingTypeIn);
        if (canonicalType == null)
        {
            return ToolResult.error("Unsupported drawingType '" + drawingTypeIn //$NON-NLS-1$
                + "'. Supported: Line, Rectangle, Ellipse, Text " //$NON-NLS-1$
                + "(RU aliases: Линия, Прямоугольник, Овал, Надпись)").toJson(); //$NON-NLS-1$
        }
        if (beginRow < 1 || beginCol < 1 || endRow < beginRow || endCol < beginCol)
        {
            return ToolResult.error("beginRow (>=1), beginColumn (>=1), endRow (>=beginRow), " //$NON-NLS-1$
                + "endColumn (>=beginColumn) are required").toJson(); //$NON-NLS-1$
        }
        if (beginRowOffset < 0 || beginColOffset < 0 || endRowOffset < 0 || endColOffset < 0)
        {
            return ToolResult.error("offsets (beginRowOffset / beginColumnOffset / " //$NON-NLS-1$
                + "endRowOffset / endColumnOffset) must be >= 0").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final String canonicalTypeF = canonicalType;
        final int beginRowF = beginRow;
        final int beginColF = beginCol;
        final int endRowF = endRow;
        final int endColF = endCol;
        final int beginRowOffsetF = beginRowOffset;
        final int beginColOffsetF = beginColOffset;
        final int endRowOffsetF = endRowOffset;
        final int endColOffsetF = endColOffset;
        final int formatIndexF = formatIndex;
        final Integer zOrderF = zOrderRaw >= 0 ? Integer.valueOf(zOrderRaw) : null;
        final String textF = text;
        final String languageF = language;
        final int[] drawingIdRef = { -1 };
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                int id = BmTemplateHelper.addDrawing(doc, canonicalTypeF, beginRowF, beginColF,
                    endRowF, endColF, beginRowOffsetF, beginColOffsetF, endRowOffsetF,
                    endColOffsetF, formatIndexF, zOrderF, textF, languageF);
                drawingIdRef[0] = id;
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return canonicalTypeF + " drawing #" + id + " at (" + beginRowF + "," //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + beginColF + ")-(" + endRowF + "," + endColF + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            r.tags.put("templateMutationPersistWarning", persistErrorRef[0]); //$NON-NLS-1$
        }
        if (r.ok && r.tags != null && drawingIdRef[0] > 0)
        {
            r.tags.put("drawingId", Integer.valueOf(drawingIdRef[0])); //$NON-NLS-1$
        }
        return formatResult(r, "add_drawing"); //$NON-NLS-1$
    }

    /**
     * 1.43: removes a drawing by its id (idempotent - a missing id is reported
     * via an {@code idempotentSkip} tag, not an error).
     */
    private String opRemoveDrawing(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("remove_drawing"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int drawingId = JsonUtils.extractIntArgument(params, "drawingId", -1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null || drawingId < 1)
        {
            return ToolResult
                .error("projectName, ownerFqn, templateName, drawingId (>=1) are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final int drawingIdF = drawingId;
        final boolean[] removedRef = { false };
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                boolean removed = BmTemplateHelper.removeDrawing(doc, drawingIdF);
                removedRef[0] = removed;
                if (removed && !dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return removed ? "removed drawing #" + drawingIdF //$NON-NLS-1$
                    : "no drawing with id " + drawingIdF + " (idempotent skip)"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            r.tags.put("templateMutationPersistWarning", persistErrorRef[0]); //$NON-NLS-1$
        }
        if (r.ok && r.tags != null && !removedRef[0])
        {
            r.tags.put("idempotentSkip", "no drawing with id " + drawingIdF); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return formatResult(r, "remove_drawing"); //$NON-NLS-1$
    }

    /**
     * 1.44: reads a SpreadsheetDocument template back into JSON - dimensions,
     * populated cells (row/col/text), merged ranges and drawing ids. Read-only:
     * runs under {@code dryRun=true} so the get-or-create model touch is rolled
     * back. Fills the gap where MXL edits were previously blind (no way to read
     * cells/merges back after set_cell / merge_cells).
     */
    private String opReadTemplate(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("read_template"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult
                .error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object>[] dataRef = new Map[] { null };
        // Read-only: dryRun=true executes the reader lambda then rolls the
        // (get-or-create) model touch back, so nothing is persisted.
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, true,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                dataRef[0] = BmTemplateHelper.readSpreadsheet(doc, language);
                return templateName;
            });
        if (!r.ok)
        {
            return formatResult(r, "read_template"); //$NON-NLS-1$
        }
        ToolResult ok = ToolResult.success()
            .put("operation", "read_template") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", ownerFqn) //$NON-NLS-1$
            .put("templateName", templateName); //$NON-NLS-1$
        Map<String, Object> data = dataRef[0];
        if (data != null)
        {
            for (Map.Entry<String, Object> e : data.entrySet())
            {
                ok.put(e.getKey(), e.getValue());
            }
        }
        return ok.toJson();
    }

    /**
     * Locates the named Template MdObject inside the owner. Throws when the
     * owner has no Templates collection or the named template is missing.
     */
    private static MdObject resolveTemplate(MdObject owner, String templateName)
    {
        @SuppressWarnings("unchecked")
        EList<MdObject> templates = (EList<MdObject>) invokeListGetter(owner, "getTemplates"); //$NON-NLS-1$
        if (templates == null)
        {
            throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                + "' has no Templates collection."); //$NON-NLS-1$
        }
        MdObject template = BmObjectHelper.findByName(templates, templateName);
        if (template == null)
        {
            throw BmObjectHelper.notFound(templateName, owner.eClass().getName(), "template"); //$NON-NLS-1$
        }
        return template;
    }

    /** JSON shape for {@code draw} layout parameter. */
    static final class DrawLayout
    {
        List<DrawCell> cells;
        List<DrawMerge> merges;
    }

    static final class DrawCell
    {
        int row;
        int col;
        String text;
        String language;
    }

    static final class DrawMerge
    {
        int fromRow;
        int fromCol;
        int toRow;
        int toCol;
    }

    private String mxlApiNotFound(String op)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", op); //$NON-NLS-1$
        data.put("discoveredSpreadsheetClass", BmTemplateHelper.resolvedSpreadsheetClass()); //$NON-NLS-1$
        data.put("discoveredFactoryClass", BmTemplateHelper.resolvedFactoryClass()); //$NON-NLS-1$
        data.put("discoveredLayoutServiceClass", //$NON-NLS-1$
            BmTemplateHelper.resolvedLayoutServiceClass());
        data.put("hint", //$NON-NLS-1$
            "Cell-level MXL editing requires the EDT layout service. " //$NON-NLS-1$
                + "If the discoveredLayoutServiceClass is null, open the project in EDT " //$NON-NLS-1$
                + "and edit the template via the GUI spreadsheet editor."); //$NON-NLS-1$
        return ToolResult.error("Spreadsheet layout service not reachable in this EDT runtime") //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .put(ErrorTags.MXL_API_NOT_FOUND.wire(), data)
            .toJson();
    }

    private String formatResult(BmObjectHelper.Result r, String op)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("ownerFqn", r.fqn) //$NON-NLS-1$
                .put("message", r.message != null ? r.message : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult
            .error(op + " failed: " + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", op) //$NON-NLS-1$
            .put("ownerFqn", r.fqn); //$NON-NLS-1$
        applyTags(err, r.tags);
        return err.toJson();
    }

    private static void applyTags(ToolResult result, Map<String, Object> tags)
    {
        if (tags == null || tags.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : tags.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static EList<MdObject> invokeListGetter(MdObject obj, String methodName)
    {
        try
        {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            if (v instanceof EList)
            {
                return (EList<MdObject>) v;
            }
        }
        catch (Exception ignored)
        {
            // missing collection
        }
        return null;
    }

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# mxl_workshop\n\n"); //$NON-NLS-1$
            sb.append("MXL spreadsheet template constructor. 7 native operations.\n\n"); //$NON-NLS-1$
            sb.append("**Operations:**\n"); //$NON-NLS-1$
            sb.append("- create_template - creates the Template MdObject (templateType=SpreadsheetDocument by default)\n"); //$NON-NLS-1$
            sb.append("- set_cell - sets a cell's text. Args: row, col, text, language (default 'ru')\n"); //$NON-NLS-1$
            sb.append("- merge_cells - merges a rectangle. Args: fromRow, fromCol, toRow, toCol (1-based, both inclusive)\n"); //$NON-NLS-1$
            sb.append("- draw - batch: layout='{\"cells\":[{row,col,text,language}],\"merges\":[{fromRow,fromCol,toRow,toCol}]}'\n"); //$NON-NLS-1$
            sb.append("- add_drawing - places a graphic. Args: drawingType (Line/Rectangle/Ellipse/Text), beginRow, beginColumn, endRow, endColumn, [*Offset], [formatIndex], [zOrder], text (for Text). Returns drawingId\n"); //$NON-NLS-1$
            sb.append("- remove_drawing - removes a graphic by drawingId (idempotent)\n"); //$NON-NLS-1$
            sb.append("- read_template - reads a SpreadsheetDocument back (read-only): rowCount, " //$NON-NLS-1$
                + "colCount, cellCount, cells[{row,col,text}], merges[{fromRow,fromCol,toRow,toCol}], " //$NON-NLS-1$
                + "drawings[{id}]. Indices are 1-based (row 1 = top, col 1 = left), the inverse of " //$NON-NLS-1$
                + "set_cell/merge_cells - safe to round-trip a read result back into a write. " //$NON-NLS-1$
                + "Args: ownerFqn, templateName, [language default 'ru']\n\n"); //$NON-NLS-1$
            sb.append("**Coordinates are 1-based.** Row 1 = top row, Col 1 = leftmost column.\n\n"); //$NON-NLS-1$
            sb.append("**API discovery:**\n"); //$NON-NLS-1$
            sb.append("- SpreadsheetDocument: ").append(BmTemplateHelper.resolvedSpreadsheetClass()) //$NON-NLS-1$
                .append("\n"); //$NON-NLS-1$
            sb.append("- Factory: ").append(BmTemplateHelper.resolvedFactoryClass()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("- Cell ops: ").append(BmTemplateHelper.cellOpsAvailable() //$NON-NLS-1$
                ? "available (moxel)" : "unavailable - mxlApiNotFound tag will be returned") //$NON-NLS-1$ //$NON-NLS-2$
                .append("\n\n"); //$NON-NLS-1$
            sb.append("Topics: workflow, errorTags\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", "1. create_template ownerFqn=Document.PrintForm " //$NON-NLS-1$ //$NON-NLS-2$
                        + "templateName=Print templateType=SpreadsheetDocument\n" //$NON-NLS-1$
                        + "2. set_cell row=1 col=1 text='Header'\n" //$NON-NLS-1$
                        + "3. merge_cells fromRow=1 fromCol=1 toRow=1 toCol=5\n" //$NON-NLS-1$
                        + "4. draw layout='{\"cells\":[{\"row\":1,\"col\":1," //$NON-NLS-1$
                        + "\"text\":\"Title\"}],\"merges\":[{\"fromRow\":1," //$NON-NLS-1$
                        + "\"fromCol\":1,\"toRow\":1,\"toCol\":5}]}' " //$NON-NLS-1$
                        + "(batch mode - one BM transaction)\n").toJson(); //$NON-NLS-1$
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", "Tags surfaced by mxl_workshop:\n" //$NON-NLS-1$ //$NON-NLS-2$
                        + "- alreadyExists { name, ownerFqn, kind=template } - template " //$NON-NLS-1$
                        + "with this name already exists.\n" //$NON-NLS-1$
                        + "- mxlApiNotFound { operation, discoveredSpreadsheetClass, " //$NON-NLS-1$
                        + "discoveredFactoryClass, discoveredLayoutServiceClass, hint } - " //$NON-NLS-1$
                        + "cell-level operation requested but EDT layout service not reachable.\n") //$NON-NLS-1$
                    .toJson();
            default:
                return ToolResult.error("Unknown topic: " + topic).toJson(); //$NON-NLS-1$
        }
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList("create_template", "set_cell", "format_cells", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "merge_cells", "draw", //$NON-NLS-1$ //$NON-NLS-2$
            "add_drawing", "remove_drawing", "read_template")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
