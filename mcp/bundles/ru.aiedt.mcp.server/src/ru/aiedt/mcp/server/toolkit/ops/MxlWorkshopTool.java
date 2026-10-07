/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmTemplateHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.support.TemplatePrintWidth;
import ru.aiedt.mcp.server.support.ToolGate;

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

    /**
     * The capability name the write operations of this facade are preset-gated by. Not a tool:
     * nothing registers it and no group lists it - {@code ToolProfile.writersOutsideWriteGroups()}
     * carries it, and a preset that blocks writing disables it, which the gate in {@code execute}
     * asks about before the first action of a writing call.
     */
    static final String WRITE_DOOR = "mxl_workshop_writes"; //$NON-NLS-1$

    /**
     * The operations of this facade that read the template and write nothing, so a
     * write-blocking preset lets them run. {@code help} is answered above, before the catalog is
     * consulted.
     */
    private static final java.util.Set<String> READ_OPERATIONS = java.util.Set.of(
        "read_template", //$NON-NLS-1$
        "list_named_areas", //$NON-NLS-1$
        "check_print_width"); //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    /** Creates a top-level common template. Object-owned templates stay on the Templates collection. */
    private final ObjectOps objectOps = new ObjectOps();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "MXL spreadsheet template constructor. 18 operations: create_template, " //$NON-NLS-1$
            + "set_cell, format_cells, merge_cells, draw, add_drawing, remove_drawing, " //$NON-NLS-1$
            + "read_template, add_named_area, list_named_areas, remove_named_area, " //$NON-NLS-1$
            + "insert_rows, delete_rows, copy_rows, insert_columns, delete_columns, " //$NON-NLS-1$
            + "copy_columns, check_print_width. " //$NON-NLS-1$
            + "They manipulate (or, for read_template, read back) the moxel " //$NON-NLS-1$
            + "SpreadsheetDocument model directly. Coordinates are 1-based. " //$NON-NLS-1$
            + "check_print_width reads the model alone: whether the print area fits the sheet " //$NON-NLS-1$
            + "by width, and which page defaults the answer took. It changes nothing."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "create_template / set_cell / format_cells / merge_cells / draw / add_drawing / remove_drawing / read_template / add_named_area / list_named_areas / remove_named_area / insert_rows / delete_rows / copy_rows / insert_columns / delete_columns / copy_columns / check_print_width / help", //$NON-NLS-1$
                true)
            .stringProperty("projectName", "Name of the EDT project to work in") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "Template owner FQN (Catalog.X / Document.X), or CommonTemplate.X itself") //$NON-NLS-1$
            .stringProperty("templateName", "Template name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("templateType", //$NON-NLS-1$
                "SpreadsheetDocument (default) / TextDocument / DataCompositionSchema / etc.") //$NON-NLS-1$
            .integerProperty("row", //$NON-NLS-1$
                "Cell row (1-based); insert_rows puts the new rows before it, delete_rows " //$NON-NLS-1$
                    + "removes from it") //$NON-NLS-1$
            .integerProperty("col", //$NON-NLS-1$
                "Cell column (1-based); insert_columns puts the new columns before it, " //$NON-NLS-1$
                    + "delete_columns removes from it") //$NON-NLS-1$
            .stringProperty("text", "Cell text content") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fillType", //$NON-NLS-1$
                "set_cell: how the cell is filled - text, parameter or template. " //$NON-NLS-1$
                    + "A parameter name without fillType is read as parameter.") //$NON-NLS-1$
            .stringProperty("parameter", //$NON-NLS-1$
                "set_cell: template parameter name. BSL fills it through the area's parameters.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language tag for the LocalString content (default 'ru')") //$NON-NLS-1$
            .stringProperty("areaName", //$NON-NLS-1$
                "Named area name for the named-area operations. The mechanism that loads data from a " //$NON-NLS-1$
                + "file reads a template by these: the area name becomes the loaded column name.") //$NON-NLS-1$
            .stringProperty("areaKind", //$NON-NLS-1$
                "add_named_area: columns (default for a load template), rows, or rect. Bounds come " //$NON-NLS-1$
                + "from fromRow/fromCol/toRow/toCol; columns reads the column pair, rows the row pair.") //$NON-NLS-1$
            .integerProperty("fromRow", "Merge range from-row; copy_rows: first source row") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("fromCol", //$NON-NLS-1$
                "Merge range from-col; copy_columns: first source column") //$NON-NLS-1$
            .integerProperty("toRow", "Merge range to-row; copy_rows: first target row") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("toCol", //$NON-NLS-1$
                "Merge range to-col; copy_columns: first target column") //$NON-NLS-1$
            .integerProperty("count", //$NON-NLS-1$
                "insert_rows / delete_rows / copy_rows / insert_columns / delete_columns / " //$NON-NLS-1$
                    + "copy_columns: how many rows or columns (default 1)") //$NON-NLS-1$
            .stringProperty("formatFrom", //$NON-NLS-1$
                "insert_rows: where the new rows take the row format and cell formats from - " //$NON-NLS-1$
                    + "above (default, and none at row 1), below or none; insert_columns: where " //$NON-NLS-1$
                    + "the new columns take the column format and cell formats from - left " //$NON-NLS-1$
                    + "(default, and none at col 1), right or none. Text and parameters " //$NON-NLS-1$
                    + "are not copied") //$NON-NLS-1$
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
                "format_cells: text rotation in degrees, from 0 to 360. The template stores " //$NON-NLS-1$
                    + "tenths of a degree, so 90 is written as 900.") //$NON-NLS-1$
            .integerProperty("rowHeight", //$NON-NLS-1$
                "format_cells: explicit row height. There is no auto-height flag in the model - a " //$NON-NLS-1$
                    + "row with no explicit height whose cells wrap is what the platform grows to " //$NON-NLS-1$
                    + "fit the text.") //$NON-NLS-1$
            .booleanProperty("autoColumnWidth", //$NON-NLS-1$
                "format_cells: let the column width follow its content.") //$NON-NLS-1$
            .integerProperty("columnWidth", "format_cells: explicit column width.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("columnWidthWeight", //$NON-NLS-1$
                "format_cells: this column's share when the available width is distributed.") //$NON-NLS-1$
            .stringProperty("border", //$NON-NLS-1$
                "format_cells: line style on every side - None, Solid, Dotted, Double, " //$NON-NLS-1$
                    + "ThinDashed, ThickDashed, LargeDashed.") //$NON-NLS-1$
            .integerProperty("borderWidth", //$NON-NLS-1$
                "format_cells: width of the borders this call adds. Defaults to 1.") //$NON-NLS-1$
            .stringProperty("leftBorder", "format_cells: line style of the left side.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("topBorder", "format_cells: line style of the top side.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("rightBorder", "format_cells: line style of the right side.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("bottomBorder", "format_cells: line style of the bottom side.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fontName", "format_cells: font face. A cell with no font yet starts from Arial.") //$NON-NLS-1$ //$NON-NLS-2$
            .numberProperty("fontSize", "format_cells: font height in points, greater than 0.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("fontBold", "format_cells: bold.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("fontItalic", "format_cells: italic.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("fontUnderline", "format_cells: underline.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("fontStrikeout", "format_cells: strikeout.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("textColor", "format_cells: text color as #RRGGBB.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("backColor", "format_cells: background color as #RRGGBB.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("borderColor", "format_cells: border color as #RRGGBB.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("patternColor", "format_cells: pattern color as #RRGGBB.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("pattern", //$NON-NLS-1$
                "format_cells: fill pattern - WithoutPattern, Solid, or Pattern1 through Pattern17.") //$NON-NLS-1$
            .stringProperty("pageOrientation", //$NON-NLS-1$
                "format_cells: page orientation, Portrait or Landscape. Applies to the whole template.") //$NON-NLS-1$
            .integerProperty("scale", //$NON-NLS-1$
                "format_cells: print scale in percent. Applies to the whole template.") //$NON-NLS-1$
            .integerProperty("copies", "format_cells: number of copies. Applies to the whole template.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("perPage", //$NON-NLS-1$
                "format_cells: pages per sheet. Applies to the whole template.") //$NON-NLS-1$
            .booleanProperty("fitToPage", //$NON-NLS-1$
                "format_cells: scale the sheet to fit the page. Applies to the whole template.") //$NON-NLS-1$
            .numberProperty("topMargin", //$NON-NLS-1$
                "format_cells: top margin in millimetres. Applies to the whole template.") //$NON-NLS-1$
            .numberProperty("leftMargin", //$NON-NLS-1$
                "format_cells: left margin in millimetres. Applies to the whole template.") //$NON-NLS-1$
            .numberProperty("bottomMargin", //$NON-NLS-1$
                "format_cells: bottom margin in millimetres. Applies to the whole template.") //$NON-NLS-1$
            .numberProperty("rightMargin", //$NON-NLS-1$
                "format_cells: right margin in millimetres. Applies to the whole template.") //$NON-NLS-1$
            .integerProperty("smallScalePercent", //$NON-NLS-1$
                "check_print_width: print scale below which the answer warns of unreadable type " //$NON-NLS-1$
                    + "(10..100, default 75). help topic=printWidth says where 75 comes from.") //$NON-NLS-1$
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

        // A preset that blocks writing is asked before the project is read or a BM transaction
        // opened. The three reading operations and a dryRun preview (which rolls its transaction
        // back and persists nothing) go through; every other operation writes the template.
        if (!READ_OPERATIONS.contains(op)
            && !JsonUtils.extractBooleanArgument(params, "dryRun", false)) //$NON-NLS-1$
        {
            String gate = ToolGate.gateWriteDoor(WRITE_DOOR);
            if (gate != null)
            {
                return ToolResult.error(gate).put("operation", op).toJson(); //$NON-NLS-1$
            }
        }

        // Readiness is asked for every content operation, including create_template, and only for
        // a project that is there. A name that resolves to nothing belongs to the operation's own
        // argument validation and not-found answer, so the guard does not answer in its place.
        // A write stops on every state short of ready. A read stops while the project is building,
        // while its build state cannot be determined, or while it is closed.
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (gatesOnReadiness(op))
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project != null)
            {
                ProjectStateGuard.ProjectStateResult state = ProjectStateGuard.checkProjectState(project);
                if (readinessBlocks(op, state))
                {
                    String notReady = ProjectStateGuard.checkReadyOrError(project);
                    if (notReady != null)
                    {
                        return ToolResult.error(notReady).put("operation", op).toJson(); //$NON-NLS-1$
                    }
                }
            }
        }

        String modelFileMismatch = templateModelFileMismatch(params, op);
        String writeRefusal = writeRefusal(op, modelFileMismatch);
        if (writeRefusal != null)
        {
            return ToolResult.error(writeRefusal)
                .put("operation", op) //$NON-NLS-1$
                .put("templateModelFileMismatch", writeRefusal) //$NON-NLS-1$
                .toJson();
        }

        String answer;
        switch (op)
        {
            case "create_template": //$NON-NLS-1$
                answer = opCreateTemplate(params);
                break;
            case "set_cell": //$NON-NLS-1$
                answer = opSetCell(params);
                break;
            case "format_cells": //$NON-NLS-1$
                answer = opFormatCells(params);
                break;
            case "merge_cells": //$NON-NLS-1$
                answer = opMergeCells(params);
                break;
            case "draw": //$NON-NLS-1$
                answer = opDraw(params);
                break;
            case "add_drawing": //$NON-NLS-1$
                answer = opAddDrawing(params);
                break;
            case "remove_drawing": //$NON-NLS-1$
                answer = opRemoveDrawing(params);
                break;
            case "add_named_area": //$NON-NLS-1$
                answer = opAddNamedArea(params);
                break;
            case "list_named_areas": //$NON-NLS-1$
                answer = opListNamedAreas(params);
                break;
            case "remove_named_area": //$NON-NLS-1$
                answer = opRemoveNamedArea(params);
                break;
            case "insert_rows": //$NON-NLS-1$
                answer = opInsertRows(params);
                break;
            case "delete_rows": //$NON-NLS-1$
                answer = opDeleteRows(params);
                break;
            case "copy_rows": //$NON-NLS-1$
                answer = opCopyRows(params);
                break;
            case "insert_columns": //$NON-NLS-1$
                answer = opInsertColumns(params);
                break;
            case "delete_columns": //$NON-NLS-1$
                answer = opDeleteColumns(params);
                break;
            case "copy_columns": //$NON-NLS-1$
                answer = opCopyColumns(params);
                break;
            case "read_template": //$NON-NLS-1$
                answer = opReadTemplate(params);
                break;
            case "check_print_width": //$NON-NLS-1$
                answer = opCheckPrintWidth(params);
                break;
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
        return modelFileMismatch == null ? answer
            : addMismatchWarning(answer, modelFileMismatch);
    }

    /**
     * Whether the operation consults project readiness before it runs. Every real operation does.
     * Help and an unknown name are answered before this is asked.
     *
     * @param operation the operation name
     * @return <code>true</code> when a resolved project must be ready
     */
    static boolean gatesOnReadiness(String operation)
    {
        return operation != null && OPS.containsKey(operation);
    }

    /**
     * Whether this operation stops on the readiness state in front of it.
     * <p>
     * A write stops on every state short of ready. A read stops while derived data is still being
     * computed, while that state cannot be determined, and while the project is closed. A project
     * that is not an EDT project, and a workbench whose DtProjectManager cannot be reached, lets a
     * read continue to the model entry.
     * </p>
     *
     * @param operation the operation name
     * @param state the project state; may be <code>null</code>
     * @return <code>true</code> when the call is answered with the readiness refusal
     */
    static boolean readinessBlocks(String operation, ProjectStateGuard.ProjectStateResult state)
    {
        if (state == null || state.isReady())
        {
            return false;
        }
        if (!isReadOperation(operation))
        {
            return true;
        }
        if (state.getState() == ProjectStateGuard.ProjectState.BUILDING)
        {
            return true;
        }
        String message = state.getMessage();
        if (message == null)
        {
            return false;
        }
        if (state.getState() == ProjectStateGuard.ProjectState.UNKNOWN)
        {
            return message.startsWith("Build state cannot be determined"); //$NON-NLS-1$
        }
        return "The project is closed".equals(message); //$NON-NLS-1$
    }

    /**
     * The mismatch refusal a writing operation returns, or <code>null</code> when the call may
     * proceed. A read keeps the mismatch as a note on its answer instead of a refusal.
     *
     * @param operation the operation name
     * @param mismatch the model/file divergence, or <code>null</code>
     * @return the refusal text, or <code>null</code>
     */
    static String writeRefusal(String operation, String mismatch)
    {
        if (mismatch == null || isReadOperation(operation))
        {
            return null;
        }
        return mismatch;
    }

    /**
     * Whether the operation only reads the template. A read does not get the mismatch refusal -
     * it answers what the model holds and names the divergence beside it.
     *
     * @param operation the operation name
     * @return <code>true</code> for the reading operations
     */
    private static boolean isReadOperation(String operation)
    {
        return "read_template".equals(operation) //$NON-NLS-1$
            || "list_named_areas".equals(operation) //$NON-NLS-1$
            || "check_print_width".equals(operation); //$NON-NLS-1$
    }

    /**
     * The divergence between the template's file and what the model currently holds for it.
     * <p>
     * Asked before every content operation, so a write against a model that has not loaded the
     * file yet is refused before it serializes the empty model over the real template. The probe
     * runs in a read transaction that rolls back, so the {@code getOrCreateSpreadsheet} attachment
     * of an empty document it may do reaches nothing. Anything the probe cannot establish - a
     * missing argument, an unresolvable project, a template that is not there - answers
     * <code>null</code> and the operation's own validation reports it.
     * </p>
     *
     * @param params the call's arguments
     * @param operation the operation being dispatched
     * @return the mismatch description, or <code>null</code> when model and file do not diverge
     *         or the question cannot be asked
     */
    private static String templateModelFileMismatch(Map<String, String> params, String operation)
    {
        if ("create_template".equals(operation)) //$NON-NLS-1$
        {
            return null;
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return null;
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return null;
        }
        final String[] mismatch = { null };
        BmObjectHelper.Result inspected = BmObjectHelper.executeReadOnObject(project, ownerFqn,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                mismatch[0] = BmTemplateHelper.modelFileMismatch(project, ownerFqn,
                    templateName, doc);
                return templateName;
            });
        return inspected.ok ? mismatch[0] : null;
    }

    /**
     * Adds the model/file mismatch note to a reading operation's answer. An answer that is not a
     * JSON object is returned as it came - the note is information, not a reason to break the call.
     *
     * @param answer the operation's JSON answer
     * @param mismatch the mismatch description
     * @return the answer carrying {@code templateModelFileMismatch}
     */
    static String addMismatchWarning(String answer, String mismatch)
    {
        try
        {
            JsonObject object = JsonParser.parseString(answer).getAsJsonObject();
            object.addProperty("templateModelFileMismatch", mismatch); //$NON-NLS-1$
            return object.toString();
        }
        catch (RuntimeException malformedAnswer)
        {
            return answer;
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
        // A common template is the template itself. A name that does not match is the same
        // refusal resolveTemplate gives, and it is answered before any project is opened.
        String nameMismatch = commonTemplateNameMismatch(ownerFqn, templateName);
        if (nameMismatch != null)
        {
            return ToolResult.error("create_template failed: " + nameMismatch) //$NON-NLS-1$
                .put("operation", "create_template") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", ownerFqn) //$NON-NLS-1$
                .toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        // 1.42.3: route the alias through canonicalTemplateType (same path
        // as edit_metadata add_template) so RU/EN aliases work and an empty
        // input falls back to the upstream default. Without this, a sloppy
        // alias would surface as "No enum constant TemplateType.<X>" later.
        final String canonicalType = BmTemplateHelper.canonicalTemplateType(templateType);
        if (commonTemplateAddress(ownerFqn, templateName) != null)
        {
            return createCommonTemplate(projectName, project, ownerFqn, templateName,
                canonicalType, dryRun);
        }
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
     * Creates a common template that does not exist yet, or reports that it already does.
     * <p>
     * An existing common template has no Templates collection, so the object-owned path would
     * answer "no Templates collection" instead of {@code alreadyExists}. A missing one is created
     * through {@code create_object}, which writes the spreadsheet file for
     * {@code SpreadsheetDocument}. Any other read error is returned and nothing is created.
     * </p>
     *
     * @param projectName the project name the caller passed
     * @param project the project that name resolved to
     * @param ownerFqn {@code CommonTemplate.<Name>}
     * @param templateName the same name
     * @param canonicalType the template type to set
     * @param dryRun whether the create is rolled back
     * @return the {@code create_template} answer
     */
    private String createCommonTemplate(String projectName, IProject project, String ownerFqn,
        String templateName, String canonicalType, boolean dryRun)
    {
        String refusal = BmTemplateHelper.templateDirRefusal(ownerFqn, templateName);
        if (refusal != null)
        {
            return ToolResult.error("create_template failed: " + refusal) //$NON-NLS-1$
                .put("operation", "create_template") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", ownerFqn) //$NON-NLS-1$
                .toJson();
        }
        BmObjectHelper.Result existing = BmObjectHelper.executeReadOnObject(project, ownerFqn,
            (tx, owner) -> {
                throw BmObjectHelper.alreadyExists(templateName, ownerFqn, "template"); //$NON-NLS-1$
            });
        if (existing.error == null || !existing.error.startsWith("Owner not found")) //$NON-NLS-1$
        {
            return formatResult(existing, "create_template"); //$NON-NLS-1$
        }
        Map<String, String> create = new LinkedHashMap<>();
        create.put("projectName", projectName); //$NON-NLS-1$
        create.put("objectType", "CommonTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        create.put("name", templateName); //$NON-NLS-1$
        create.put("dryRun", Boolean.toString(dryRun)); //$NON-NLS-1$
        JsonObject properties = new JsonObject();
        properties.addProperty("templateType", canonicalType); //$NON-NLS-1$
        create.put("properties", properties.toString()); //$NON-NLS-1$
        return reshapeCommonTemplateAnswer(objectOps.opCreateObject(create), ownerFqn, templateName);
    }

    /**
     * Turns a {@code create_object} answer into the {@code create_template} answer. The object
     * type, the content flags and any warning stay; the operation name and the two addresses the
     * caller passed are written over them.
     *
     * @param createObjectAnswer the JSON {@code create_object} returned
     * @param ownerFqn the owner FQN
     * @param templateName the template name
     * @return the reshaped JSON, or the original text when it is not an object
     */
    static String reshapeCommonTemplateAnswer(String createObjectAnswer, String ownerFqn,
        String templateName)
    {
        try
        {
            JsonObject object = JsonParser.parseString(createObjectAnswer).getAsJsonObject();
            object.addProperty("operation", "create_template"); //$NON-NLS-1$ //$NON-NLS-2$
            object.addProperty("ownerFqn", ownerFqn); //$NON-NLS-1$
            object.addProperty("templateName", templateName); //$NON-NLS-1$
            return object.toString();
        }
        catch (RuntimeException malformed)
        {
            return createObjectAnswer;
        }
    }

    /**
     * A common template addressed by {@code ownerFqn}, or <code>null</code> when the FQN names
     * some other owner. The name match is exact case. A name that is not a single path segment is
     * still returned; creating it is refused later by the folder rule.
     *
     * @param ownerFqn the owner FQN
     * @param templateName the template name; <code>null</code> does not match
     * @return the address, or <code>null</code>
     */
    static CommonTemplateAddress commonTemplateAddress(String ownerFqn, String templateName)
    {
        int dot = ownerFqn == null ? -1 : ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1)
        {
            return null;
        }
        String typePrefix = ownerFqn.substring(0, dot);
        if (MetadataTypeCatalog.MetadataTypeInfo.COMMON_TEMPLATE
            != MetadataTypeCatalog.resolve(typePrefix))
        {
            return null;
        }
        String name = ownerFqn.substring(dot + 1);
        return new CommonTemplateAddress(name, name.equals(templateName));
    }

    /**
     * The exact-case refusal {@link #resolveTemplate} gives when {@code templateName} is not the
     * common template {@code ownerFqn} addresses.
     *
     * @param ownerFqn the owner FQN
     * @param templateName the template name
     * @return the refusal, or <code>null</code> when the address is not a common template or the
     *         names match
     */
    static String commonTemplateNameMismatch(String ownerFqn, String templateName)
    {
        CommonTemplateAddress address = commonTemplateAddress(ownerFqn, templateName);
        if (address == null || address.nameMatches)
        {
            return null;
        }
        return "ownerFqn addresses the common template '" + address.name //$NON-NLS-1$
            + "', which is the template itself: pass templateName='" + address.name //$NON-NLS-1$
            + "', got '" + templateName + "'."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code ownerFqn=CommonTemplate.&lt;Name&gt;} read as the template itself.
     */
    static final class CommonTemplateAddress
    {
        final String name;

        final boolean nameMatches;

        CommonTemplateAddress(String name, boolean nameMatches)
        {
            this.name = name;
            this.nameMatches = nameMatches;
        }
    }

    /**
     * Writes a cell's text, its template parameter, and how the cell is filled.
     * <p>
     * A parameter is what BSL assigns through the area. The fill says whether the cell is plain
     * text, that parameter, or a template string with placeholders. A name without a fill is read
     * as a parameter. The refusal for a parameter with no name happens before the project is
     * opened, so the call cannot report success and leave the cell as it was.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
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
        String fillType = JsonUtils.extractStringArgument(params, "fillType"); //$NON-NLS-1$
        String parameter = JsonUtils.extractStringArgument(params, "parameter"); //$NON-NLS-1$
        boolean textPassed = params.containsKey("text"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String fillError = BmTemplateHelper.fillProblem(fillType, parameter, textPassed);
        if (fillError != null)
        {
            return ToolResult.error(fillError).put("operation", "set_cell").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (projectName == null || ownerFqn == null || templateName == null
            || row < 1 || col < 1)
        {
            return ToolResult.error("projectName, ownerFqn, templateName, row (>=1), col (>=1) are required") //$NON-NLS-1$
                .toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int rowF = row;
        final int colF = col;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                String contentError = BmTemplateHelper.setCellContent(doc, rowF, colF, text,
                    textPassed, language, fillType, parameter);
                if (contentError != null)
                {
                    contentErrorRef[0] = contentError;
                    return contentError;
                }
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
                String shown = textPassed ? (text == null ? "" : text) //$NON-NLS-1$
                    : (parameter == null ? "" : parameter); //$NON-NLS-1$
                return "(" + rowF + "," + colF + ")=" + shown; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "set_cell").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        return formatResult(r, "set_cell"); //$NON-NLS-1$
    }

    /**
     * Names an area of a template.
     * <p>
     * The mechanism that loads data from a file reads a template by its named areas: an area name
     * becomes the name of a loaded column. Without them a template is unusable for loading, so a
     * template built through this tool had to be finished by editing the .mxlx by hand.
     * </p>
     *
     * @param params the call parameters
     * @return the answer as JSON
     */
    private String opAddNamedArea(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("add_named_area"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String areaName = JsonUtils.extractStringArgument(params, "areaName"); //$NON-NLS-1$
        String kind = JsonUtils.extractStringArgument(params, "areaKind"); //$NON-NLS-1$
        int fromRow = JsonUtils.extractIntArgument(params, "fromRow", -1); //$NON-NLS-1$
        int fromCol = JsonUtils.extractIntArgument(params, "fromCol", -1); //$NON-NLS-1$
        int toRow = JsonUtils.extractIntArgument(params, "toRow", -1); //$NON-NLS-1$
        int toCol = JsonUtils.extractIntArgument(params, "toCol", -1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null || areaName == null)
        {
            return ToolResult.error("projectName, ownerFqn, templateName and areaName " //$NON-NLS-1$
                + "are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final String kindF = kind;
        final String areaNameF = areaName;
        final int fromRowF = fromRow;
        final int fromColF = fromCol;
        final int toRowF = toRow;
        final int toColF = toCol;
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.addNamedArea(doc, areaNameF, kindF, fromRowF, fromColF, toRowF,
                    toColF);
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return areaNameF;
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        return formatResult(r, "add_named_area"); //$NON-NLS-1$
    }

    /**
     * Removes a named area.
     *
     * @param params the call parameters
     * @return the answer as JSON
     */
    private String opRemoveNamedArea(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("remove_named_area"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String areaName = JsonUtils.extractStringArgument(params, "areaName"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null || areaName == null)
        {
            return ToolResult.error("projectName, ownerFqn, templateName and areaName " //$NON-NLS-1$
                + "are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final String areaNameF = areaName;
        final boolean[] removedRef = { false };
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                removedRef[0] = BmTemplateHelper.removeNamedArea(doc, areaNameF);
                if (!removedRef[0])
                {
                    // Removing what is not there is not a write, and reporting it as one would be
                    // the silent success this project keeps paying for.
                    throw new IllegalArgumentException("no named area called " + areaNameF //$NON-NLS-1$
                        + " in template " + templateName); //$NON-NLS-1$
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return areaNameF;
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        return formatResult(r, "remove_named_area"); //$NON-NLS-1$
    }

    /**
     * Inserts rows with a shift: everything from the named row down moves by the count.
     * <p>
     * The bounds, the extent check and the formatFrom word are refused before the model is opened,
     * so a bad call cannot leave a half-shifted template behind. What shifted, and what the shift
     * resized, is answered in the counters of the row outcome.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
     */
    private String opInsertRows(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("insert_rows"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int row = JsonUtils.extractIntArgument(params, "row", -1); //$NON-NLS-1$
        int count = JsonUtils.extractIntArgument(params, "count", 1); //$NON-NLS-1$
        String formatFrom = JsonUtils.extractStringArgument(params, "formatFrom"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String canonicalFormat = BmTemplateHelper.canonicalFormatFrom(formatFrom);
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (row < 1)
        {
            return ToolResult.error("row (>=1) is required").put("operation", "insert_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (count < 1)
        {
            return ToolResult.error("count must be 1 or greater - got: " + count) //$NON-NLS-1$
                .put("operation", "insert_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (canonicalFormat == null)
        {
            return ToolResult.error("formatFrom must be one of above, below, none - got: " //$NON-NLS-1$
                + formatFrom).put("operation", "insert_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int rowF = row;
        final int countF = count;
        final String formatF = canonicalFormat;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        final BmTemplateHelper.RowOutcome[] outcomeRef = new BmTemplateHelper.RowOutcome[] { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, rowF, countF,
                    formatF);
                outcomeRef[0] = outcome;
                if (outcome.error != null)
                {
                    contentErrorRef[0] = outcome.error;
                    abortOnRefusal(outcome.error);
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "inserted " + countF + " row(s) before row " + rowF; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "insert_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        if (r.ok && r.tags != null && outcomeRef[0] != null)
        {
            r.tags.put("row", Integer.valueOf(row)); //$NON-NLS-1$
            applyRowOutcome(r.tags, outcomeRef[0], count);
        }
        return formatResult(r, "insert_rows"); //$NON-NLS-1$
    }

    /**
     * Deletes rows with a shift: the rows below move up by the count.
     * <p>
     * A range that runs past the end of the document is refused before anything moves. Merges,
     * named areas and drawings the range swallows are removed and named in the answer - a removal
     * that is not an error still has to be visible.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
     */
    private String opDeleteRows(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("delete_rows"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int row = JsonUtils.extractIntArgument(params, "row", -1); //$NON-NLS-1$
        int count = JsonUtils.extractIntArgument(params, "count", 1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (row < 1)
        {
            return ToolResult.error("row (>=1) is required").put("operation", "delete_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (count < 1)
        {
            return ToolResult.error("count must be 1 or greater - got: " + count) //$NON-NLS-1$
                .put("operation", "delete_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int rowF = row;
        final int countF = count;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        final BmTemplateHelper.RowOutcome[] outcomeRef = new BmTemplateHelper.RowOutcome[] { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.deleteRows(doc, rowF, countF);
                outcomeRef[0] = outcome;
                if (outcome.error != null)
                {
                    contentErrorRef[0] = outcome.error;
                    abortOnRefusal(outcome.error);
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "deleted " + countF + " row(s) from row " + rowF; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "delete_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        if (r.ok && r.tags != null && outcomeRef[0] != null)
        {
            r.tags.put("row", Integer.valueOf(row)); //$NON-NLS-1$
            applyRowOutcome(r.tags, outcomeRef[0], count);
        }
        return formatResult(r, "delete_rows"); //$NON-NLS-1$
    }

    /**
     * Replaces rows with a copy of other rows: no shift, the target becomes what the source is.
     * <p>
     * The source range is checked against the document and both ranges against each other before
     * anything is written. The target may run past the current end - the document grows to hold it.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
     */
    private String opCopyRows(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("copy_rows"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int fromRow = JsonUtils.extractIntArgument(params, "fromRow", -1); //$NON-NLS-1$
        int toRow = JsonUtils.extractIntArgument(params, "toRow", -1); //$NON-NLS-1$
        int count = JsonUtils.extractIntArgument(params, "count", 1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (fromRow < 1 || toRow < 1)
        {
            return ToolResult.error("fromRow (>=1) and toRow (>=1) are required") //$NON-NLS-1$
                .put("operation", "copy_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (count < 1)
        {
            return ToolResult.error("count must be 1 or greater - got: " + count) //$NON-NLS-1$
                .put("operation", "copy_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int fromRowF = fromRow;
        final int toRowF = toRow;
        final int countF = count;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        final BmTemplateHelper.RowOutcome[] outcomeRef = new BmTemplateHelper.RowOutcome[] { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, fromRowF,
                    toRowF, countF);
                outcomeRef[0] = outcome;
                if (outcome.error != null)
                {
                    contentErrorRef[0] = outcome.error;
                    abortOnRefusal(outcome.error);
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "copied " + countF + " row(s) from row " + fromRowF + " to row " + toRowF; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "copy_rows").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        if (r.ok && r.tags != null && outcomeRef[0] != null)
        {
            r.tags.put("fromRow", Integer.valueOf(fromRow)); //$NON-NLS-1$
            r.tags.put("toRow", Integer.valueOf(toRow)); //$NON-NLS-1$
            applyRowOutcome(r.tags, outcomeRef[0], count);
        }
        return formatResult(r, "copy_rows"); //$NON-NLS-1$
    }

    /**
     * Inserts columns with a shift: everything from the named column right moves by the count.
     * <p>
     * The bounds, the extent check and the formatFrom word are refused before the model is opened,
     * and a word of the row axis - above, below - is refused the same way, so a bad call cannot
     * leave a half-shifted template behind. What shifted, and what the shift resized, is answered
     * in the counters of the column outcome.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
     */
    private String opInsertColumns(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("insert_columns"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int col = JsonUtils.extractIntArgument(params, "col", -1); //$NON-NLS-1$
        int count = JsonUtils.extractIntArgument(params, "count", 1); //$NON-NLS-1$
        String formatFrom = JsonUtils.extractStringArgument(params, "formatFrom"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String canonicalFormat = BmTemplateHelper.canonicalColumnFormatFrom(formatFrom);
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (col < 1)
        {
            return ToolResult.error("col (>=1) is required").put("operation", "insert_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (count < 1)
        {
            return ToolResult.error("count must be 1 or greater - got: " + count) //$NON-NLS-1$
                .put("operation", "insert_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (canonicalFormat == null)
        {
            return ToolResult.error("formatFrom must be one of left, right, none - got: " //$NON-NLS-1$
                + formatFrom).put("operation", "insert_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int colF = col;
        final int countF = count;
        final String formatF = canonicalFormat;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        final BmTemplateHelper.ColumnOutcome[] outcomeRef =
            new BmTemplateHelper.ColumnOutcome[] { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, colF,
                    countF, formatF);
                outcomeRef[0] = outcome;
                if (outcome.error != null)
                {
                    contentErrorRef[0] = outcome.error;
                    abortOnRefusal(outcome.error);
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "inserted " + countF + " column(s) before column " + colF; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "insert_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        if (r.ok && r.tags != null && outcomeRef[0] != null)
        {
            r.tags.put("col", Integer.valueOf(col)); //$NON-NLS-1$
            applyColumnOutcome(r.tags, outcomeRef[0], count);
        }
        return formatResult(r, "insert_columns"); //$NON-NLS-1$
    }

    /**
     * Deletes columns with a shift: the columns to the right move left by the count.
     * <p>
     * A range that runs past the end of the document is refused before anything moves. Merges,
     * named areas and drawings the range swallows are removed and named in the answer - a removal
     * that is not an error still has to be visible.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
     */
    private String opDeleteColumns(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("delete_columns"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int col = JsonUtils.extractIntArgument(params, "col", -1); //$NON-NLS-1$
        int count = JsonUtils.extractIntArgument(params, "count", 1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (col < 1)
        {
            return ToolResult.error("col (>=1) is required").put("operation", "delete_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (count < 1)
        {
            return ToolResult.error("count must be 1 or greater - got: " + count) //$NON-NLS-1$
                .put("operation", "delete_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int colF = col;
        final int countF = count;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        final BmTemplateHelper.ColumnOutcome[] outcomeRef =
            new BmTemplateHelper.ColumnOutcome[] { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.deleteColumns(doc, colF,
                    countF);
                outcomeRef[0] = outcome;
                if (outcome.error != null)
                {
                    contentErrorRef[0] = outcome.error;
                    abortOnRefusal(outcome.error);
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "deleted " + countF + " column(s) from column " + colF; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "delete_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        if (r.ok && r.tags != null && outcomeRef[0] != null)
        {
            r.tags.put("col", Integer.valueOf(col)); //$NON-NLS-1$
            applyColumnOutcome(r.tags, outcomeRef[0], count);
        }
        return formatResult(r, "delete_columns"); //$NON-NLS-1$
    }

    /**
     * Replaces columns with a copy of other columns: no shift, the target becomes what the source
     * is.
     * <p>
     * The source range is checked against the document and both ranges against each other before
     * anything is written. The target may run past the current end - the document grows to hold it.
     * </p>
     *
     * @param params the call's arguments
     * @return the result as JSON
     */
    private String opCopyColumns(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("copy_columns"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        int fromCol = JsonUtils.extractIntArgument(params, "fromCol", -1); //$NON-NLS-1$
        int toCol = JsonUtils.extractIntArgument(params, "toCol", -1); //$NON-NLS-1$
        int count = JsonUtils.extractIntArgument(params, "count", 1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (fromCol < 1 || toCol < 1)
        {
            return ToolResult.error("fromCol (>=1) and toCol (>=1) are required") //$NON-NLS-1$
                .put("operation", "copy_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (count < 1)
        {
            return ToolResult.error("count must be 1 or greater - got: " + count) //$NON-NLS-1$
                .put("operation", "copy_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int fromColF = fromCol;
        final int toColF = toCol;
        final int countF = count;
        final String[] persistErrorRef = { null };
        final String[] contentErrorRef = { null };
        final BmTemplateHelper.ColumnOutcome[] outcomeRef =
            new BmTemplateHelper.ColumnOutcome[] { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, fromColF,
                    toColF, countF);
                outcomeRef[0] = outcome;
                if (outcome.error != null)
                {
                    contentErrorRef[0] = outcome.error;
                    abortOnRefusal(outcome.error);
                }
                if (!dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                return "copied " + countF + " column(s) from column " + fromColF + " to column " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + toColF;
            });
        if (contentErrorRef[0] != null)
        {
            return ToolResult.error(contentErrorRef[0]).put("operation", "copy_columns").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
        }
        if (r.ok && r.tags != null && outcomeRef[0] != null)
        {
            r.tags.put("fromCol", Integer.valueOf(fromCol)); //$NON-NLS-1$
            r.tags.put("toCol", Integer.valueOf(toCol)); //$NON-NLS-1$
            applyColumnOutcome(r.tags, outcomeRef[0], count);
        }
        return formatResult(r, "copy_columns"); //$NON-NLS-1$
    }

    /**
     * Puts what a row operation did into the answer, beside the operation's own arguments.
     *
     * @param tags the answer's tags
     * @param outcome what the operation did
     * @param count the count the call named
     */
    static void applyRowOutcome(Map<String, Object> tags, BmTemplateHelper.RowOutcome outcome,
        int count)
    {
        tags.put("count", Integer.valueOf(count)); //$NON-NLS-1$
        tags.put("shiftedRows", Integer.valueOf(outcome.shiftedRows)); //$NON-NLS-1$
        tags.put("resizedMerges", Integer.valueOf(outcome.resizedMerges)); //$NON-NLS-1$
        tags.put("removedMerges", Integer.valueOf(outcome.removedMerges)); //$NON-NLS-1$
        tags.put("resizedNamedAreas", outcome.resizedNamedAreas); //$NON-NLS-1$
        tags.put("removedNamedAreas", outcome.removedNamedAreas); //$NON-NLS-1$
        tags.put("removedDrawings", outcome.removedDrawings); //$NON-NLS-1$
        tags.put("removedDataSources", Integer.valueOf(outcome.removedDataSources)); //$NON-NLS-1$
        tags.put("lastRow", Integer.valueOf(outcome.lastRow)); //$NON-NLS-1$
    }

    /**
     * Puts what a column operation did into the answer, beside the operation's own arguments.
     *
     * @param tags the answer's tags
     * @param outcome what the operation did
     * @param count the count the call named
     */
    static void applyColumnOutcome(Map<String, Object> tags, BmTemplateHelper.ColumnOutcome outcome,
        int count)
    {
        tags.put("count", Integer.valueOf(count)); //$NON-NLS-1$
        tags.put("shiftedColumns", Integer.valueOf(outcome.shiftedColumns)); //$NON-NLS-1$
        tags.put("resizedMerges", Integer.valueOf(outcome.resizedMerges)); //$NON-NLS-1$
        tags.put("removedMerges", Integer.valueOf(outcome.removedMerges)); //$NON-NLS-1$
        tags.put("resizedNamedAreas", outcome.resizedNamedAreas); //$NON-NLS-1$
        tags.put("removedNamedAreas", outcome.removedNamedAreas); //$NON-NLS-1$
        tags.put("removedDrawings", outcome.removedDrawings); //$NON-NLS-1$
        tags.put("removedDataSources", Integer.valueOf(outcome.removedDataSources)); //$NON-NLS-1$
        tags.put("lastColumn", Integer.valueOf(outcome.lastColumn)); //$NON-NLS-1$
    }

    /**
     * Aborts the write transaction when an operation refused the change.
     * <p>
     * A refusal is decided once the model is open, and a callback that returned its text would
     * leave the transaction to commit: opening a template without a spreadsheet attaches one on
     * the way in, so the caller would read an error about a template the refused call replaced
     * anyway. The exception unwinds the callback and rolls the transaction back, the way every
     * other failed write of this facade does.
     * </p>
     *
     * @param refusal the operation's refusal text, or <code>null</code> when it applied the change
     */
    static void abortOnRefusal(String refusal)
    {
        if (refusal != null)
        {
            throw new IllegalArgumentException(refusal);
        }
    }

    /**
     * The named areas a template carries.
     * <p>
     * Read-only, and the same reading read_template now reports. Without it a template written
     * elsewhere cannot be read back and repeated.
     * </p>
     *
     * @param params the call parameters
     * @return the answer as JSON
     */
    private String opListNamedAreas(Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("list_named_areas"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>>[] areasRef = new List[] { null };
        // The read entry asks no support question: a template of a closed object stays readable.
        // Its transaction still rolls back, because reading a template without a spreadsheet model
        // touches the model to build the answer.
        BmObjectHelper.Result r = BmObjectHelper.executeReadOnObject(project, ownerFqn,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                areasRef[0] = BmTemplateHelper.listNamedAreas(doc);
                return templateName;
            });
        if (!r.ok)
        {
            return formatResult(r, "list_named_areas"); //$NON-NLS-1$
        }
        List<Map<String, Object>> areas =
            areasRef[0] == null ? new ArrayList<>() : areasRef[0];
        return ToolResult.success()
            .put("operation", "list_named_areas") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", ownerFqn) //$NON-NLS-1$
            .put("templateName", templateName) //$NON-NLS-1$
            .put("count", areas.size()) //$NON-NLS-1$
            .put("namedAreas", areas) //$NON-NLS-1$
            .toJson();
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
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int fromRowF = fromRow;
        final int fromColF = fromCol;
        final int toRowF = toRow;
        final int toColF = toCol;
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
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
            failOnPersist(r, persistErrorRef[0]);
        }
        return formatResult(r, "merge_cells"); //$NON-NLS-1$
    }

    /** String arguments of {@code format_cells} read together, so each one is a read. */
    private static final String[] FORMAT_STRINGS = {
        "border", "leftBorder", "topBorder", "rightBorder", "bottomBorder", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "fontName", "textColor", "backColor", "borderColor", "patternColor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "pattern", "pageOrientation" //$NON-NLS-1$ //$NON-NLS-2$
    };

    /** Integer arguments of {@code format_cells} read together. */
    private static final String[] FORMAT_INTS = {
        "borderWidth", "scale", "copies", "perPage" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    /** Margin arguments of {@code format_cells}, read together: millimetres, fractions allowed. */
    private static final String[] FORMAT_MARGINS = {
        "topMargin", "leftMargin", "bottomMargin", "rightMargin" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    /** Boolean arguments of {@code format_cells} read together. Absent is not false. */
    private static final String[] FORMAT_FLAGS = {
        "fontBold", "fontItalic", "fontUnderline", "fontStrikeout", "fitToPage" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    /**
     * Applies presentation properties to a rectangle of cells and the columns under it.
     * <p>
     * Every property is optional and only what is passed is touched: this is a formatter, not a
     * style reset, and a template arrives with a look somebody chose. Passing none of them is
     * refused rather than treated as a no-op, because a call that changes nothing and reports
     * success reads as a call that worked. Print settings belong to the document, so a call that
     * only sets those does not need a cell range. {@code textOrientation} is degrees from 0 to 360.
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
        if (params.containsKey("fillType") || params.containsKey("parameter")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("fillType and parameter are set_cell arguments - format_cells " //$NON-NLS-1$
                + "has no fill of its own").put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String orientationRaw = JsonUtils.extractStringArgument(params, "textOrientation"); //$NON-NLS-1$
        Integer orientation = null;
        if (orientationRaw != null && !orientationRaw.isEmpty())
        {
            try
            {
                orientation = Integer.valueOf(orientationRaw.trim());
            }
            catch (NumberFormatException notANumber)
            {
                return ToolResult.error("textOrientation must be a whole number of degrees - got: " //$NON-NLS-1$
                    + orientationRaw).put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        Integer rowHeight = optionalInt(params, "rowHeight"); //$NON-NLS-1$
        Integer columnWidth = optionalInt(params, "columnWidth"); //$NON-NLS-1$
        Integer widthWeight = optionalInt(params, "columnWidthWeight"); //$NON-NLS-1$
        Boolean autoColumnWidth = params.containsKey("autoColumnWidth") //$NON-NLS-1$
            ? Boolean.valueOf(JsonUtils.extractBooleanArgument(params, "autoColumnWidth", false)) //$NON-NLS-1$
            : null;
        Map<String, String> strings = new LinkedHashMap<>();
        for (String name : FORMAT_STRINGS)
        {
            strings.put(name, JsonUtils.extractStringArgument(params, name));
        }
        Map<String, Integer> numbers = new LinkedHashMap<>();
        for (String name : FORMAT_INTS)
        {
            String raw = JsonUtils.extractStringArgument(params, name);
            Integer parsed = null;
            if (raw != null && !raw.isEmpty())
            {
                try
                {
                    parsed = Integer.valueOf(raw.trim());
                }
                catch (NumberFormatException notAWholeNumber)
                {
                    // Includes a value past the int the model stores. Skipping it here would
                    // answer success for a setting the template never got.
                    return ToolResult.error(name + " must be a whole number - got: " + raw) //$NON-NLS-1$
                        .put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            numbers.put(name, parsed);
        }
        Map<String, Float> margins = new LinkedHashMap<>();
        for (String name : FORMAT_MARGINS)
        {
            String raw = JsonUtils.extractStringArgument(params, name);
            Float parsed = null;
            if (raw != null && !raw.isEmpty())
            {
                try
                {
                    parsed = Float.valueOf(raw.trim());
                }
                catch (NumberFormatException notANumber)
                {
                    return ToolResult.error(name + " must be a number of millimetres - got: " + raw) //$NON-NLS-1$
                        .put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            margins.put(name, parsed);
        }
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String name : FORMAT_FLAGS)
        {
            if (params.containsKey(name))
            {
                flags.put(name, Boolean.valueOf(
                    JsonUtils.extractBooleanArgument(params, name, false)));
            }
        }
        Float fontSize = null;
        String fontSizeRaw = JsonUtils.extractStringArgument(params, "fontSize"); //$NON-NLS-1$
        if (fontSizeRaw != null && !fontSizeRaw.isEmpty())
        {
            try
            {
                fontSize = Float.valueOf(fontSizeRaw.trim());
            }
            catch (NumberFormatException notANumber)
            {
                return ToolResult.error("fontSize must be a number of points - got: " + fontSizeRaw) //$NON-NLS-1$
                    .put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        BmTemplateHelper.CellLook look = cellLook(strings, numbers, margins, flags, fontSize);
        boolean cellProperties = placement != null || orientation != null || rowHeight != null
            || columnWidth != null || widthWeight != null || autoColumnWidth != null
            || look.changesCells();
        boolean printProperties = look.changesPrint();
        if (!cellProperties && !printProperties)
        {
            return ToolResult.error("nothing to apply: pass at least one of textPlacement, " //$NON-NLS-1$
                + "textOrientation, rowHeight, autoColumnWidth, columnWidth, " //$NON-NLS-1$
                + "columnWidthWeight, border, font, color or print settings").toJson(); //$NON-NLS-1$
        }
        String problem = BmTemplateHelper.presentationProblem(orientation, look);
        if (problem != null)
        {
            return ToolResult.error(problem).put("operation", "format_cells").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        if (cellProperties && (fromRow < 1 || fromCol < 1 || toRow < fromRow || toCol < fromCol))
        {
            return ToolResult.error("a cell range is required: row/col for one cell, or " //$NON-NLS-1$
                + "fromRow/fromCol/toRow/toCol for a rectangle").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int fromRowF = fromRow;
        final int fromColF = fromCol;
        final int toRowF = toRow;
        final int toColF = toCol;
        final Integer orientationF = orientation;
        final boolean cellsF = cellProperties;
        final boolean printF = printProperties;
        final String[] persistErrorRef = { null };
        final String[] formatErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
                String written = ""; //$NON-NLS-1$
                if (cellsF)
                {
                    BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc,
                        fromRowF, fromColF, toRowF, toColF, placement, orientationF, rowHeight,
                        autoColumnWidth, columnWidth, widthWeight, look);
                    if (outcome.error != null)
                    {
                        formatErrorRef[0] = outcome.error;
                        return outcome.error;
                    }
                    written = outcome.cellsChanged + " cells, " + outcome.columnsChanged //$NON-NLS-1$
                        + " columns"; //$NON-NLS-1$
                }
                if (printF)
                {
                    String printError = BmTemplateHelper.applyPrintSettings(doc, look);
                    if (printError != null)
                    {
                        formatErrorRef[0] = printError;
                        return printError;
                    }
                    written = written.isEmpty() ? "print settings" //$NON-NLS-1$
                        : written + "; print settings"; //$NON-NLS-1$
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
                return written;
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
            failOnPersist(r, persistErrorRef[0]);
        }
        return formatResult(r, "format_cells"); //$NON-NLS-1$
    }

    /**
     * The appearance and print request carried by the arguments {@code format_cells} read.
     *
     * @param strings the string arguments, missing ones null
     * @param numbers the integer arguments, missing ones null
     * @param margins the margin arguments in millimetres, missing ones null
     * @param flags the boolean arguments that were actually passed
     * @param fontSize the parsed font height, or {@code null}
     * @return the request
     */
    private static BmTemplateHelper.CellLook cellLook(Map<String, String> strings,
        Map<String, Integer> numbers, Map<String, Float> margins, Map<String, Boolean> flags,
        Float fontSize)
    {
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.border = strings.get("border"); //$NON-NLS-1$
        look.leftBorder = strings.get("leftBorder"); //$NON-NLS-1$
        look.topBorder = strings.get("topBorder"); //$NON-NLS-1$
        look.rightBorder = strings.get("rightBorder"); //$NON-NLS-1$
        look.bottomBorder = strings.get("bottomBorder"); //$NON-NLS-1$
        look.fontName = strings.get("fontName"); //$NON-NLS-1$
        look.fontSize = fontSize;
        look.textColor = strings.get("textColor"); //$NON-NLS-1$
        look.backColor = strings.get("backColor"); //$NON-NLS-1$
        look.borderColor = strings.get("borderColor"); //$NON-NLS-1$
        look.patternColor = strings.get("patternColor"); //$NON-NLS-1$
        look.pattern = strings.get("pattern"); //$NON-NLS-1$
        look.pageOrientation = strings.get("pageOrientation"); //$NON-NLS-1$
        look.borderWidth = numbers.get("borderWidth"); //$NON-NLS-1$
        look.scale = numbers.get("scale"); //$NON-NLS-1$
        look.copies = numbers.get("copies"); //$NON-NLS-1$
        look.perPage = numbers.get("perPage"); //$NON-NLS-1$
        look.topMargin = margins.get("topMargin"); //$NON-NLS-1$
        look.leftMargin = margins.get("leftMargin"); //$NON-NLS-1$
        look.bottomMargin = margins.get("bottomMargin"); //$NON-NLS-1$
        look.rightMargin = margins.get("rightMargin"); //$NON-NLS-1$
        look.fontBold = flags.get("fontBold"); //$NON-NLS-1$
        look.fontItalic = flags.get("fontItalic"); //$NON-NLS-1$
        look.fontUnderline = flags.get("fontUnderline"); //$NON-NLS-1$
        look.fontStrikeout = flags.get("fontStrikeout"); //$NON-NLS-1$
        look.fitToPage = flags.get("fitToPage"); //$NON-NLS-1$
        return look;
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
            return ProjectResolver.notFound(projectName).toJson();
        }
        final DrawLayout layoutF = layout;
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                SpreadsheetDocument doc = writableDocument(owner, templateName);
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
            failOnPersist(r, persistErrorRef[0]);
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
            return ProjectResolver.notFound(projectName).toJson();
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
                SpreadsheetDocument doc = writableDocument(owner, templateName);
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
            failOnPersist(r, persistErrorRef[0]);
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
            return ProjectResolver.notFound(projectName).toJson();
        }
        final int drawingIdF = drawingId;
        final boolean[] removedRef = { false };
        final String[] persistErrorRef = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                // Resolved without the guard: a removal that matches nothing changes nothing and
                // writes nothing, and the guard inside asks only the removal that will.
                DrawingRemoval removal = removeDrawingIn(owner, templateName, drawingIdF);
                removedRef[0] = removal.removed;
                if (removal.removed && !dryRun)
                {
                    String pErr = BmTemplateHelper.persistTemplateMxlx(project, ownerFqn,
                        templateName, removal.doc);
                    if (pErr != null)
                    {
                        persistErrorRef[0] = pErr;
                    }
                }
                // The unchanged marker for a removal that moved nothing, so the write entry owes
                // the owner no export either: the operation promises nothing was written.
                return removal.actionAnswer(drawingIdF);
            });
        if (persistErrorRef[0] != null && r.tags != null)
        {
            failOnPersist(r, persistErrorRef[0]);
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
            return ProjectResolver.notFound(projectName).toJson();
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object>[] dataRef = new Map[] { null };
        // Read-only: the read entry asks no support question, and its transaction rolls the
        // (get-or-create) model touch back, so nothing is persisted.
        BmObjectHelper.Result r = BmObjectHelper.executeReadOnObject(project, ownerFqn,
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
     * Whether the template's print area fits the sheet by width, from the model alone.
     * <p>
     * Read-only, and deliberately so: the width depends on a paper the model often does not name,
     * so this is an operation a caller runs on demand rather than a check the platform runs on
     * every save. Nothing is written back, not even by a get-or-create touch.
     * </p>
     *
     * @param params the call parameters: projectName, ownerFqn, templateName and the optional
     *            smallScalePercent.
     * @return the answer as JSON, or a failure when the project or the template cannot be reached.
     */
    private String opCheckPrintWidth(Map<String, String> params)
    {
        boolean smallScaleSupplied = params != null && params.containsKey("smallScalePercent"); //$NON-NLS-1$
        Integer smallScalePercent = JsonUtils.extractIntegerArgument(params, "smallScalePercent"); //$NON-NLS-1$
        // Not a number, not a whole number, or outside 10..100: the same refusal for each. Taking
        // the default silently instead would turn a caller's typo into a warning threshold nobody
        // picked.
        boolean refused = smallScalePercent == null ? smallScaleSupplied
            : smallScalePercent.intValue() < 10 || smallScalePercent.intValue() > 100;
        if (refused)
        {
            return ToolResult.error("smallScalePercent must be between 10 and 100") //$NON-NLS-1$
                .toJson();
        }
        int threshold = smallScalePercent == null ? TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT
            : smallScalePercent.intValue();

        if (!BmTemplateHelper.cellOpsAvailable())
        {
            return mxlApiNotFound("check_print_width"); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$

        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult.error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        // The font measurement starts AWT, which has no business running inside a model
        // transaction: it is taken here, before the task, and handed in.
        final TemplatePrintWidth.CharMetrics metrics = TemplatePrintWidth.resolveCharMetrics();
        @SuppressWarnings("unchecked")
        final Map<String, Object>[] widthRef = new Map[] { null };
        // Read-only: the read entry asks no support question and rolls the model touch back.
        BmObjectHelper.Result r = BmObjectHelper.executeReadOnObject(project, ownerFqn,
            (tx, owner) -> {
                MdObject template = resolveTemplate(owner, templateName);
                SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
                widthRef[0] = TemplatePrintWidth.check(doc, threshold, metrics);
                return templateName;
            });
        if (!r.ok)
        {
            return formatResult(r, "check_print_width"); //$NON-NLS-1$
        }
        ToolResult ok = ToolResult.success()
            .put("operation", "check_print_width") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", ownerFqn) //$NON-NLS-1$
            .put("templateName", templateName); //$NON-NLS-1$
        Map<String, Object> width = widthRef[0];
        if (width != null)
        {
            for (Map.Entry<String, Object> e : width.entrySet())
            {
                ok.put(e.getKey(), e.getValue());
            }
        }
        return ok.toJson();
    }

    /**
     * Turns a write that never reached the file into a failure.
     * <p>
     * The mutation lands in the in-memory moxel model first and is then written to
     * {@code Template.mxlx}. When that write fails the model still carries the change, so the
     * operation looked successful and carried the reason as a note beside it. The file is what
     * survives a restart and what reaches version control, so a change that did not get there did
     * not happen: reporting it as done is the failure this codebase keeps meeting.
     * </p>
     *
     * @param r the result to demote; may be <code>null</code>
     * @param persistError the reason the file write failed, or <code>null</code> when it did not
     */
    static void failOnPersist(BmObjectHelper.Result r, String persistError)
    {
        if (r == null || persistError == null)
        {
            return;
        }
        r.ok = false;
        r.error = "the template was changed in memory but the change did not reach " //$NON-NLS-1$
            + "Template.mxlx, so it will not survive a restart and will not reach version " //$NON-NLS-1$
            + "control: " + persistError; //$NON-NLS-1$
        if (r.tags != null)
        {
            r.tags.put("templateMutationPersistFailed", persistError); //$NON-NLS-1$
        }
    }

    /**
     * The document a writing operation of this tool changes, refused before anything changes when
     * the template holds picture references the project cannot resolve.
     * <p>
     * Such a template cannot reach Template.mxlx: the serializer would write each unresolved
     * reference as {@code ref="v8ui:/"}, which the platform refuses to load. The guard runs inside
     * the write transaction ahead of the mutation and throws, so the transaction rolls back with
     * the document exactly as it was and the file untouched - a refused write cannot leave the
     * model and the file diverging. A dry run answers the same refusal: it previews a write that
     * would not persist. The reading operations resolve the document without this guard, so a
     * template that already holds such references stays readable, and a removal that would change
     * nothing asks the guard only when it finds something to remove.
     * </p>
     *
     * @param owner the object {@code ownerFqn} resolved to
     * @param templateName the template's name
     * @return the template's spreadsheet document
     */
    static SpreadsheetDocument writableDocument(MdObject owner, String templateName)
    {
        SpreadsheetDocument doc = resolvedDocument(owner, templateName);
        BmTemplateHelper.requireResolvablePictures(doc);
        return doc;
    }

    /**
     * The document behind a template name, with no guard asked of it.
     *
     * @param owner the object {@code ownerFqn} resolved to
     * @param templateName the template's name
     * @return the template's spreadsheet document
     */
    static SpreadsheetDocument resolvedDocument(MdObject owner, String templateName)
    {
        MdObject template = resolveTemplate(owner, templateName);
        return BmTemplateHelper.getOrCreateSpreadsheet(template);
    }

    /**
     * Removes a drawing the way a write does, with the unresolved-picture guard asking only a
     * removal that would really change the document.
     * <p>
     * A drawing that is not there is the idempotent skip it always was - neither the document nor
     * Template.mxlx would move - and trading that no-op success for the guard's refusal would tell
     * the caller a write was stopped when none was ever going to happen. The guard asks exactly
     * the removal that will change something, so a template with unresolved references still
     * refuses to lose a drawing it has.
     * </p>
     *
     * @param doc the document the removal addresses
     * @param drawingId the drawing to remove
     * @return whether a drawing was removed
     */
    static boolean guardedRemoveDrawing(SpreadsheetDocument doc, int drawingId)
    {
        if (!BmTemplateHelper.hasDrawing(doc, drawingId))
        {
            return false;
        }
        BmTemplateHelper.requireResolvablePictures(doc);
        return BmTemplateHelper.removeDrawing(doc, drawingId);
    }

    /**
     * What one drawing removal did to the template's document, and whether the document was
     * already attached: the difference between a model that did not move and a get-or-create that
     * attached a fresh document the removal itself would not have justified.
     */
    static final class DrawingRemoval
    {
        /** Whether a drawing left the document. */
        final boolean removed;

        /**
         * Whether the document was attached before the call. A template whose document this very
         * call attached changed, whatever the removal did.
         */
        final boolean documentExisted;

        /** The document the removal addressed. */
        final SpreadsheetDocument doc;

        DrawingRemoval(boolean removed, boolean documentExisted, SpreadsheetDocument doc)
        {
            this.removed = removed;
            this.documentExisted = documentExisted;
            this.doc = doc;
        }

        /**
         * The write action's answer. A removal that matched nothing on a template that already
         * held its document is the unchanged marker: neither the document nor the owner moved, so
         * the owner export has nothing to carry and the operation keeps its idempotent wording.
         *
         * @param drawingId the drawing the call named
         * @return the answer message, or the marker the write entry skips the export for
         */
        Object actionAnswer(int drawingId)
        {
            if (removed)
            {
                return "removed drawing #" + drawingId; //$NON-NLS-1$
            }
            String skip = "no drawing with id " + drawingId + " (idempotent skip)"; //$NON-NLS-1$ //$NON-NLS-2$
            return documentExisted ? BmObjectHelper.Unchanged.of(skip) : skip;
        }
    }

    /**
     * Removes a drawing the way the remove_drawing action does: the template is resolved without
     * the unresolved-picture guard, and the guard asks only the removal that will change
     * something, so a removal that matches nothing neither refuses nor moves the document.
     *
     * @param owner the object {@code ownerFqn} resolved to
     * @param templateName the template's name
     * @param drawingId the drawing to remove
     * @return what the removal did
     */
    static DrawingRemoval removeDrawingIn(MdObject owner, String templateName, int drawingId)
    {
        MdObject template = resolveTemplate(owner, templateName);
        boolean documentExisted = BmTemplateHelper.existingSpreadsheetOf(template) != null;
        SpreadsheetDocument doc = BmTemplateHelper.getOrCreateSpreadsheet(template);
        boolean removed = guardedRemoveDrawing(doc, drawingId);
        return new DrawingRemoval(removed, documentExisted, doc);
    }

    /**
     * Locates the template a call addresses.
     * <p>
     * A common template is a top-level object that is the template itself: it has no owner and no
     * Templates collection, so {@code ownerFqn=CommonTemplate.X} with {@code templateName=X}
     * addresses the object the FQN resolved to. Every other owner holds its templates in a
     * collection, and the name is looked up there.
     * </p>
     *
     * @param owner the object {@code ownerFqn} resolved to
     * @param templateName the template's name
     * @return the template
     * @throws RuntimeException when the owner holds no templates, the named template is missing,
     *         or the name does not match the common template the FQN addresses
     */
    static MdObject resolveTemplate(MdObject owner, String templateName)
    {
        if (owner instanceof CommonTemplate)
        {
            // Exact case: the write operations build the template folder from the caller's name.
            if (!owner.getName().equals(templateName))
            {
                throw new RuntimeException("ownerFqn addresses the common template '" //$NON-NLS-1$
                    + owner.getName() + "', which is the template itself: pass templateName='" //$NON-NLS-1$
                    + owner.getName() + "', got '" + templateName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return owner;
        }
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
            // No count here: the list below is the operations worth naming at a glance, and a
            // number beside it counts something else.
            sb.append("MXL spreadsheet template constructor.\n\n"); //$NON-NLS-1$
            sb.append("**Operations:**\n"); //$NON-NLS-1$
            sb.append("- create_template - creates the Template MdObject " //$NON-NLS-1$
                + "(templateType=SpreadsheetDocument by default). " //$NON-NLS-1$
                + "ownerFqn=CommonTemplate.X with templateName=X creates that common template.\n"); //$NON-NLS-1$
            sb.append("- set_cell - sets a cell's text or template parameter. Args: row, col, " //$NON-NLS-1$
                + "text, language (default 'ru'), fillType (text / parameter / template), " //$NON-NLS-1$
                + "parameter\n"); //$NON-NLS-1$
            sb.append("- format_cells - placement, rotation in degrees (0..360, stored as tenths), " //$NON-NLS-1$
                + "row height, column width, borders, font, colors and print settings. " //$NON-NLS-1$
                + "A cell range is row/col or fromRow/fromCol/toRow/toCol. Print settings " //$NON-NLS-1$
                + "(pageOrientation, scale, copies, perPage, fitToPage, margins in millimetres) " //$NON-NLS-1$
                + "apply to the whole template, not to the cells in the range, and need no range.\n"); //$NON-NLS-1$
            sb.append("- merge_cells - merges a rectangle. Args: fromRow, fromCol, toRow, toCol (1-based, both inclusive)\n"); //$NON-NLS-1$
            sb.append("- draw - batch: layout='{\"cells\":[{row,col,text,language}],\"merges\":[{fromRow,fromCol,toRow,toCol}]}'\n"); //$NON-NLS-1$
            sb.append("- add_drawing - places a graphic. Args: drawingType (Line/Rectangle/Ellipse/Text), beginRow, beginColumn, endRow, endColumn, [*Offset], [formatIndex], [zOrder], text (for Text). Returns drawingId\n"); //$NON-NLS-1$
            sb.append("- remove_drawing - removes a graphic by drawingId (idempotent)\n"); //$NON-NLS-1$
            sb.append("- read_template - reads a SpreadsheetDocument back (read-only): rowCount, " //$NON-NLS-1$
                + "colCount, cellCount, cells[{row,col,text}], merges[{fromRow,fromCol,toRow,toCol}], " //$NON-NLS-1$
                + "drawings[{id}]. Indices are 1-based (row 1 = top, col 1 = left), the inverse of " //$NON-NLS-1$
                + "set_cell/merge_cells - safe to round-trip a read result back into a write. " //$NON-NLS-1$
                + "Args: ownerFqn, templateName, [language default 'ru']\n\n"); //$NON-NLS-1$
            sb.append("- insert_rows - inserts count rows before row (1-based; the last row + 1 " //$NON-NLS-1$
                + "appends). formatFrom: above (default, and none at row 1) / below / none - the " //$NON-NLS-1$
                + "new rows take the row format and the cell formats, never text or parameters. " //$NON-NLS-1$
                + "Everything at row and below shifts down: rows, merges, named areas, row " //$NON-NLS-1$
                + "groups, drawings, the print and repeat areas\n"); //$NON-NLS-1$
            sb.append("- delete_rows - removes count rows from row (1-based); the rows below shift " //$NON-NLS-1$
                + "up. Merges, named areas and drawings lying entirely inside the range go with " //$NON-NLS-1$
                + "them and are named in the answer; partial overlaps shrink\n"); //$NON-NLS-1$
            sb.append("- copy_rows - replaces count rows at toRow with a copy of the rows at " //$NON-NLS-1$
                + "fromRow: row format, cells with text, parameter, detail and format, and the " //$NON-NLS-1$
                + "merges inside the source. No shift; source and target ranges must not overlap; " //$NON-NLS-1$
                + "named areas are neither copied nor moved\n"); //$NON-NLS-1$
            sb.append("- insert_columns - inserts count columns before col (1-based; the last " //$NON-NLS-1$
                + "column + 1 appends). formatFrom: left (default, and none at col 1) / right / " //$NON-NLS-1$
                + "none - the new columns take the column format and the cell formats, never text " //$NON-NLS-1$
                + "or parameters. Everything at col and to the right shifts: the cells of every " //$NON-NLS-1$
                + "row, the column sets, merges, named areas, column groups, drawings, the print " //$NON-NLS-1$
                + "and repeat areas\n"); //$NON-NLS-1$
            sb.append("- delete_columns - removes count columns from col (1-based); the columns " //$NON-NLS-1$
                + "to the right shift left. Merges, named areas and drawings lying entirely inside " //$NON-NLS-1$
                + "the range go with them and are named in the answer; partial overlaps shrink\n"); //$NON-NLS-1$
            sb.append("- copy_columns - replaces count columns at toCol with a copy of the " //$NON-NLS-1$
                + "columns at fromCol: column width and format, cells with text, parameter, detail " //$NON-NLS-1$
                + "and format, notes, and the merges inside the source. No shift; source and target " //$NON-NLS-1$
                + "ranges must not overlap; named areas are neither copied nor moved\n"); //$NON-NLS-1$
            sb.append("- check_print_width - reads the model alone (read-only): whether the print area " //$NON-NLS-1$
                + "fits the sheet by width. Args: ownerFqn, templateName, " //$NON-NLS-1$
                + "[smallScalePercent 10..100 default 75]. Answers verdict fits / borderline / " //$NON-NLS-1$
                + "overflows / smallPrint / empty, the widths in millimetres, and `assumed` - the " //$NON-NLS-1$
                + "page defaults the answer took. Rule: help topic=printWidth\n\n"); //$NON-NLS-1$
            sb.append("**Coordinates are 1-based.** Row 1 = top row, Col 1 = leftmost column.\n\n"); //$NON-NLS-1$
            sb.append("**API discovery:**\n"); //$NON-NLS-1$
            sb.append("- SpreadsheetDocument: ").append(BmTemplateHelper.resolvedSpreadsheetClass()) //$NON-NLS-1$
                .append("\n"); //$NON-NLS-1$
            sb.append("- Factory: ").append(BmTemplateHelper.resolvedFactoryClass()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("- Cell ops: ").append(BmTemplateHelper.cellOpsAvailable() //$NON-NLS-1$
                ? "available (moxel)" : "unavailable - mxlApiNotFound tag will be returned") //$NON-NLS-1$ //$NON-NLS-2$
                .append("\n\n"); //$NON-NLS-1$
            sb.append("Topics: workflow, errorTags, printWidth\n"); //$NON-NLS-1$
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
                        + "(batch mode - one BM transaction)\n" //$NON-NLS-1$
                        + "5. add_named_area areaName=Nomenclature areaKind=columns fromCol=1 " //$NON-NLS-1$
                        + "toCol=1 - a template the load-data-from-a-file mechanism reads is read " //$NON-NLS-1$
                        + "by these: the area name becomes the loaded column name\n" //$NON-NLS-1$
                        + "6. list_named_areas reads them back; read_template reports them too\n") //$NON-NLS-1$
                    .toJson();
            case "printwidth": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", "check_print_width - does the print area fit the sheet by width?\n" //$NON-NLS-1$
                        + "Read from the moxel model alone; the platform is not run and nothing is " //$NON-NLS-1$
                        + "written.\n\n" //$NON-NLS-1$
                        + "Widths. Content width is the width of the column set the platform's " //$NON-NLS-1$
                        + "paginator prints: the document's columns and every set a row carries " //$NON-NLS-1$
                        + "are each measured over their first `size` columns - a set whose " //$NON-NLS-1$
                        + "declared size runs past the last cell counts to the size - and the " //$NON-NLS-1$
                        + "widest set wins. A columns print area is measured over begin..end " //$NON-NLS-1$
                        + "with the columns of row 0, the row the paginator reads, not the set " //$NON-NLS-1$
                        + "stored on the area. A rectangular print area is measured over " //$NON-NLS-1$
                        + "x..x+width-1, the span the fit-to-width scale uses; when the platform " //$NON-NLS-1$
                        + "paginates that rectangle it takes one column more. A column's width " //$NON-NLS-1$
                        + "comes from its own format, then the " //$NON-NLS-1$
                        + "format of its set of columns, then the document default, then 72 (the " //$NON-NLS-1$
                        + "platform's default column, 9 characters). Format.width is held in " //$NON-NLS-1$
                        + "eighths of a character; one character is measured as the advance of " //$NON-NLS-1$
                        + "Arial 8 'X'. EDT measures that through SWT and gets the font the " //$NON-NLS-1$
                        + "machine has; this measures the same character through the JDK - once " //$NON-NLS-1$
                        + "per process, off any model transaction - and `charWidthSource` says " //$NON-NLS-1$
                        + "which way it went (charWidthMm carries the number).\n\n" //$NON-NLS-1$
                        + "Page. Printable width = the sheet (A4: 210 mm portrait, 297 landscape) " //$NON-NLS-1$
                        + "minus the left and right margins. Every parameter the model leaves unset " //$NON-NLS-1$
                        + "is taken the way EDT's fillMissingPrintSettings takes it - A4, portrait, " //$NON-NLS-1$
                        + "10 mm margins, 100% scale - and each one taken is named in `assumed`. " //$NON-NLS-1$
                        + "A paper declared as its own dimensions (code -1 with pageWidth and " //$NON-NLS-1$
                        + "pageHeight, millimetres) is measured by those, orientation picking the " //$NON-NLS-1$
                        + "side; another paper code is measured as A4 and `assumed` says so.\n\n" //$NON-NLS-1$
                        + "Verdicts. Content within the printable width - fits. Wider by up to 5% - " //$NON-NLS-1$
                        + "borderline: it prints, but a layout that close to the edge flips " //$NON-NLS-1$
                        + "between fits and borderline with the font the machine has, so read " //$NON-NLS-1$
                        + "marginMm rather than the single word. Wider still - " //$NON-NLS-1$
                        + "overflows. No cells - empty. With fitToPage=true there is no overflow " //$NON-NLS-1$
                        + "verdict: `requiredScalePercent` is min(100, printable/content*100) and " //$NON-NLS-1$
                        + "`fontSizeAfterScale` is 8 points shrunk by it, and a scale below " //$NON-NLS-1$
                        + "smallScalePercent reads as smallPrint. 75 is where that warning starts, " //$NON-NLS-1$
                        + "not a platform norm - the platform publishes no minimum, so treat it as a " //$NON-NLS-1$
                        + "judgement call and move it with the argument.\n\n" //$NON-NLS-1$
                        + "What it does not do. A scale the template declares (printScalePercent) is " //$NON-NLS-1$
                        + "reported but not applied to the width: the platform compares content " //$NON-NLS-1$
                        + "against the printable width as it stands and scales later, at print " //$NON-NLS-1$
                        + "time. Auto column width is read as the width stored in the file, not " //$NON-NLS-1$
                        + "recomputed from the text. This is a warning at any verdict - the call " //$NON-NLS-1$
                        + "succeeds whatever it answers.\n") //$NON-NLS-1$
                    .toJson();
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
            "add_drawing", "remove_drawing", "read_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "add_named_area", "list_named_areas", "remove_named_area", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "insert_rows", "delete_rows", "copy_rows", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "insert_columns", "delete_columns", "copy_columns", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "check_print_width")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
