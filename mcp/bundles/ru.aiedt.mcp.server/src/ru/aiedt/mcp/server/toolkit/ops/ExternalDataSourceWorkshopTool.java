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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataSource;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataSourceTableDataType;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataSourceTableType;
import com._1c.g5.v8.dt.metadata.mdclass.Field;
import com._1c.g5.v8.dt.metadata.mdclass.Function;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Table;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * External data source (ВнешнийИсточникДанных) content constructor. Builds the
 * relational content of an {@code ExternalDataSource} - Tables, their Fields,
 * and Functions - which had no dedicated construction operations elsewhere (only
 * read support existed; content previously required hand-editing {@code .mdo}
 * files).
 *
 * <p>Create the {@code ExternalDataSource} itself first via
 * {@code edit_metadata create_object objectType=ExternalDataSource}, then use
 * {@code add_table} / {@code add_field} / {@code add_function} (+ {@code remove_field},
 * {@code list}). This mirrors {@code xdto_workshop}, where the {@code XDTOPackage}
 * is created first and the workshop only authors its content.
 *
 * <p>Persistence follows the metadata model on disk: a Table is a separate BM
 * top-object (its own {@code .mdo} under {@code Tables/<name>/}), referenced from
 * the parent as {@code <tables>ExternalDataSource.<eds>.Table.<name></tables>} - a
 * non-containment reference, so it is attached via {@code attachTopObject} and
 * force-exported. A Field (inside a Table) and a Function (inside the
 * ExternalDataSource) are CONTAINMENT features, serialized inline in the owner's
 * {@code .mdo}; they are added to the owner's list and ride the owner's export
 * (no {@code attachTopObject}).
 *
 * <p>OLAP Cubes (dimensions / resources / dimension tables) are not yet
 * supported. Remove a whole Table with {@code delete_metadata_object} (a
 * top-object); remove an inline Function with {@code remove_function}.
 */
public class ExternalDataSourceWorkshopTool implements IMcpTool
{
    public static final String NAME = "external_data_source_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOps();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "External data source (ВнешнийИсточникДанных) content constructor. " //$NON-NLS-1$
            + "Builds Tables, their Fields and Functions of an ExternalDataSource. " //$NON-NLS-1$
            + "Create the ExternalDataSource first via edit_metadata create_object " //$NON-NLS-1$
            + "objectType=ExternalDataSource, then add_table / add_field / add_function " //$NON-NLS-1$
            + "(+ remove_field, list). A Table is a separate top-object; a Field is " //$NON-NLS-1$
            + "inline in the Table; a Function is inline in the ExternalDataSource. Pass " //$NON-NLS-1$
            + "ownerFqn=ExternalDataSource.<name> for tables/functions, " //$NON-NLS-1$
            + "ownerFqn=ExternalDataSource.<eds>.Table.<t> for fields. Delete a whole " //$NON-NLS-1$
            + "Table with delete_metadata_object; OLAP cubes are not yet supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "add_table / add_field / add_function / remove_field / remove_function / list / help", //$NON-NLS-1$
                true)
            .stringProperty("projectName", "Name of the EDT project to work in") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "ExternalDataSource.<name> for add_table/add_function/list; " //$NON-NLS-1$
                    + "ExternalDataSource.<eds>.Table.<t> for add_field/remove_field") //$NON-NLS-1$
            .stringProperty("dataSourceName", //$NON-NLS-1$
                "ExternalDataSource name (alternative to ownerFqn)") //$NON-NLS-1$
            .stringProperty("tableName", //$NON-NLS-1$
                "add_field/remove_field: table name (with dataSourceName, alternative to ownerFqn)") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name of the table / field / function to add or remove") //$NON-NLS-1$
            .stringProperty("synonym", "Synonym (localized, stored under ru)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("nameInDataSource", //$NON-NLS-1$
                "add_table/add_field: name of the object in the external DB (default = name)") //$NON-NLS-1$
            .stringProperty("tableType", //$NON-NLS-1$
                "add_table: Table (default, real DB table/view) or Expression") //$NON-NLS-1$
            .stringProperty("tableDataType", //$NON-NLS-1$
                "add_table: Nonobject (default) or Object. Object data needs key fields, " //$NON-NLS-1$
                    + "which this tool cannot set yet - use Nonobject or mark keys manually") //$NON-NLS-1$
            .stringProperty("expressionInDataSource", //$NON-NLS-1$
                "add_function (and Expression tables): the DB expression/query text") //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "add_field / add_function: 1C type (String, Number, Date, Boolean, " //$NON-NLS-1$
                    + "CatalogRef.X, ...); comma-separated for a composite type") //$NON-NLS-1$
            .integerProperty("length", "add_field / add_function: String/BinaryData length (0 = unlimited)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("precision", "add_field / add_function: Number total digits") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("fractionDigits", "add_field / add_function: Number fraction digits") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("dateFractions", "add_field / add_function: Date / DateTime / Time") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("allowedLength", "add_field / add_function: Variable / Fixed (String)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("nonNegative", "add_field / add_function: Number sign restriction") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("dryRun", "Preview without writing (default false)") //$NON-NLS-1$ //$NON-NLS-2$
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
            return ToolResult.error("operation is required. Available: " //$NON-NLS-1$
                + String.join(", ", OPS.keySet()) + ", help").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp();
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation: " + op //$NON-NLS-1$
                + ". Available: " + String.join(", ", OPS.keySet()) + ", help").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        switch (op)
        {
            case "add_table": //$NON-NLS-1$
                return opAddTable(params);
            case "add_field": //$NON-NLS-1$
                return opAddField(params);
            case "add_function": //$NON-NLS-1$
                return opAddFunction(params);
            case "remove_field": //$NON-NLS-1$
                return opRemoveField(params);
            case "remove_function": //$NON-NLS-1$
                return opRemoveFunction(params);
            case "list": //$NON-NLS-1$
                return opList(params);
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
    }

    // ================================================================= add_table

    private String opAddTable(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String nameInDataSource = JsonUtils.extractStringArgument(params, "nameInDataSource"); //$NON-NLS-1$
        String expression = JsonUtils.extractStringArgument(params, "expressionInDataSource"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String edsName = resolveDataSourceName(params);
        if (edsName == null)
        {
            return ToolResult.error("ownerFqn=ExternalDataSource.<name> or dataSourceName is required").toJson(); //$NON-NLS-1$
        }
        final ExternalDataSourceTableType tableType = parseTableType(
            JsonUtils.extractStringArgument(params, "tableType")); //$NON-NLS-1$
        final ExternalDataSourceTableDataType dataType = parseTableDataType(
            JsonUtils.extractStringArgument(params, "tableDataType")); //$NON-NLS-1$
        // An Expression table is defined by its DB expression; without it the
        // table is invalid and this tool has no later op to set it (only
        // add_table / remove_field / delete_metadata_object).
        if (tableType == ExternalDataSourceTableType.EXPRESSION
            && (expression == null || expression.isEmpty()))
        {
            return ToolResult.error("tableType=Expression requires expressionInDataSource " //$NON-NLS-1$
                + "(the DB query/expression text)").toJson(); //$NON-NLS-1$
        }

        Ctx c = resolve(projectName);
        if (c.error != null)
        {
            return ToolResult.error(c.error).toJson();
        }
        final String edsFqn = edsFqn(edsName);
        final String tableFqn = edsFqn + ".Table." + name; //$NON-NLS-1$
        final boolean[] created = { false };

        String txErr = runInTx(c.bmModel, "eds.add_table", dryRun, tx -> { //$NON-NLS-1$
            Object ownerObj = tx.getTopObjectByFqn(edsFqn);
            if (!(ownerObj instanceof ExternalDataSource))
            {
                throw new RuntimeException("ExternalDataSource not found: " + edsFqn //$NON-NLS-1$
                    + " (create it first via edit_metadata create_object objectType=ExternalDataSource)"); //$NON-NLS-1$
            }
            ExternalDataSource eds = (ExternalDataSource)ownerObj;
            for (Table existing : eds.getTables())
            {
                if (existing != null && name.equalsIgnoreCase(existing.getName()))
                {
                    return; // idempotent: table already present
                }
            }
            Table table = (Table)BmObjectHelper.createGenericObject("Table"); //$NON-NLS-1$
            if (table == null)
            {
                throw new RuntimeException("MdClassFactory.createTable() unavailable on this EDT runtime"); //$NON-NLS-1$
            }
            table.setName(name);
            putSynonym(table, synonym, name);
            table.setNameInDataSource(nameInDataSource != null && !nameInDataSource.isEmpty()
                ? nameInDataSource : name);
            if (expression != null && !expression.isEmpty())
            {
                table.setExpressionInDataSource(expression);
            }
            table.setTableType(tableType);
            table.setTableDataType(dataType);
            table.setParentDataSource(eds);
            eds.getTables().add(table);
            tx.attachTopObject((IBmObject)table, tableFqn);
            created[0] = true;
        });
        if (txErr != null)
        {
            return ToolResult.error(txErr).toJson();
        }
        String persistErr = !dryRun && created[0] ? forceExport(c, edsFqn, tableFqn) : null;
        ToolResult r = ToolResult.success()
            .put("operation", "add_table") //$NON-NLS-1$ //$NON-NLS-2$
            .put("tableFqn", tableFqn) //$NON-NLS-1$
            .put("tableType", tableType.getName()) //$NON-NLS-1$
            .put("tableDataType", dataType.getName()) //$NON-NLS-1$
            .put("created", created[0]) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", created[0] ? "Table added." : "Table already exists."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (persistErr != null)
        {
            r.put("persistFailed", persistErr); //$NON-NLS-1$
        }
        if (c.flushPending)
        {
            // Row 42: BM committed but the .mdo disk flush did not confirm in
            // time (was log-only) - surface it so the agent can re-drive it.
            r.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
        }
        return r.toJson();
    }

    // ================================================================= add_field

    private String opAddField(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String nameInDataSource = JsonUtils.extractStringArgument(params, "nameInDataSource"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final String tableFqn = resolveTableFqn(params);
        if (tableFqn == null)
        {
            return ToolResult.error("ownerFqn=ExternalDataSource.<eds>.Table.<t> " //$NON-NLS-1$
                + "(or dataSourceName + tableName) is required").toJson(); //$NON-NLS-1$
        }
        final BmDefinedTypeHelper.QualifierOptions qualifiers = readQualifiers(params);

        Ctx c = resolve(projectName);
        if (c.error != null)
        {
            return ToolResult.error(c.error).toJson();
        }
        if (type != null && !type.isEmpty() && c.config == null)
        {
            return ToolResult.error("Configuration not available for project: " + projectName //$NON-NLS-1$
                + " (required to resolve the field type '" + type + "')").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        final Configuration config = c.config;
        final IProject project = c.project;
        final String fieldType = type;
        final List<String> resolvedTypes = new ArrayList<>();
        final List<String> unresolvedTypes = new ArrayList<>();
        final boolean[] created = { false };
        final boolean[] typeApplied = { false };

        String txErr = runInTx(c.bmModel, "eds.add_field", dryRun, tx -> { //$NON-NLS-1$
            Object ownerObj = tx.getTopObjectByFqn(tableFqn);
            if (!(ownerObj instanceof Table))
            {
                throw new RuntimeException("Table not found: " + tableFqn //$NON-NLS-1$
                    + " (add it first with add_table)"); //$NON-NLS-1$
            }
            Table table = (Table)ownerObj;
            for (Field existing : table.getTableFields())
            {
                if (existing != null && name.equalsIgnoreCase(existing.getName()))
                {
                    return; // idempotent
                }
            }
            Field field = (Field)BmObjectHelper.createGenericObject("Field"); //$NON-NLS-1$
            if (field == null)
            {
                throw new RuntimeException("MdClassFactory.createField() unavailable on this EDT runtime"); //$NON-NLS-1$
            }
            field.setName(name);
            putSynonym(field, synonym, name);
            field.setNameInDataSource(nameInDataSource != null && !nameInDataSource.isEmpty()
                ? nameInDataSource : name);
            table.getTableFields().add(field);
            if (fieldType != null && !fieldType.isEmpty())
            {
                BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypes(
                    field, project, config, Collections.singletonList(fieldType), qualifiers);
                typeApplied[0] = tr.ok;
                if (tr.resolved != null)
                {
                    resolvedTypes.addAll(tr.resolved);
                }
                if (tr.unresolved != null)
                {
                    unresolvedTypes.addAll(tr.unresolved);
                }
                if (!tr.ok)
                {
                    throw new RuntimeException("type not applied: " //$NON-NLS-1$
                        + (tr.error != null ? tr.error : "no TypeDescription on Field")); //$NON-NLS-1$
                }
            }
            created[0] = true;
        });
        if (txErr != null)
        {
            return ToolResult.error(txErr).toJson();
        }
        String persistErr = !dryRun && created[0] ? forceExport(c, tableFqn) : null;
        ToolResult r = ToolResult.success()
            .put("operation", "add_field") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fieldFqn", tableFqn + ".Field." + name) //$NON-NLS-1$ //$NON-NLS-2$
            .put("created", created[0]) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", created[0] ? "Field added." : "Field already exists."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (persistErr != null)
        {
            r.put("persistFailed", persistErr); //$NON-NLS-1$
        }
        if (c.flushPending)
        {
            // Row 42: BM committed but the .mdo disk flush did not confirm in
            // time (was log-only) - surface it so the agent can re-drive it.
            r.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
        }
        if (type != null && !type.isEmpty())
        {
            Map<String, Object> typeApply = new LinkedHashMap<>();
            typeApply.put("requested", type); //$NON-NLS-1$
            typeApply.put("applied", typeApplied[0]); //$NON-NLS-1$
            if (!resolvedTypes.isEmpty())
            {
                typeApply.put("resolved", resolvedTypes); //$NON-NLS-1$
            }
            if (!unresolvedTypes.isEmpty())
            {
                typeApply.put("unresolved", unresolvedTypes); //$NON-NLS-1$
            }
            r.put("typeApplication", typeApply); //$NON-NLS-1$
        }
        return r.toJson();
    }

    // ============================================================== add_function

    private String opAddFunction(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String expression = JsonUtils.extractStringArgument(params, "expressionInDataSource"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String edsName = resolveDataSourceName(params);
        if (edsName == null)
        {
            return ToolResult.error("ownerFqn=ExternalDataSource.<name> or dataSourceName is required").toJson(); //$NON-NLS-1$
        }
        final BmDefinedTypeHelper.QualifierOptions qualifiers = readQualifiers(params);

        Ctx c = resolve(projectName);
        if (c.error != null)
        {
            return ToolResult.error(c.error).toJson();
        }
        if (type != null && !type.isEmpty() && c.config == null)
        {
            return ToolResult.error("Configuration not available for project: " + projectName //$NON-NLS-1$
                + " (required to resolve the return type '" + type + "')").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        final Configuration config = c.config;
        final IProject project = c.project;
        final String returnType = type;
        final String edsFqn = edsFqn(edsName);
        final String functionFqn = edsFqn + ".Function." + name; //$NON-NLS-1$
        final boolean[] created = { false };
        final boolean[] typeApplied = { false };

        String txErr = runInTx(c.bmModel, "eds.add_function", dryRun, tx -> { //$NON-NLS-1$
            Object ownerObj = tx.getTopObjectByFqn(edsFqn);
            if (!(ownerObj instanceof ExternalDataSource))
            {
                throw new RuntimeException("ExternalDataSource not found: " + edsFqn //$NON-NLS-1$
                    + " (create it first via edit_metadata create_object objectType=ExternalDataSource)"); //$NON-NLS-1$
            }
            ExternalDataSource eds = (ExternalDataSource)ownerObj;
            for (Function existing : eds.getFunctions())
            {
                if (existing != null && name.equalsIgnoreCase(existing.getName()))
                {
                    return; // idempotent
                }
            }
            Function fn = (Function)BmObjectHelper.createGenericObject("Function"); //$NON-NLS-1$
            if (fn == null)
            {
                throw new RuntimeException("MdClassFactory.createFunction() unavailable on this EDT runtime"); //$NON-NLS-1$
            }
            fn.setName(name);
            putSynonym(fn, synonym, name);
            if (expression != null && !expression.isEmpty())
            {
                fn.setExpressionInDataSource(expression);
            }
            // ExternalDataSource.functions is a CONTAINMENT reference (unlike
            // tables/cubes, which are non-containment top-objects): a Function is
            // serialized inline in the EDS .mdo, so it is attached by adding it to
            // the list - NOT via attachTopObject (that throws "already attached").
            eds.getFunctions().add(fn);
            if (returnType != null && !returnType.isEmpty())
            {
                BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypes(
                    fn, project, config, Collections.singletonList(returnType), qualifiers);
                typeApplied[0] = tr.ok;
                if (!tr.ok)
                {
                    throw new RuntimeException("return type not applied: " //$NON-NLS-1$
                        + (tr.error != null ? tr.error : "no TypeDescription on Function")); //$NON-NLS-1$
                }
            }
            created[0] = true;
        });
        if (txErr != null)
        {
            return ToolResult.error(txErr).toJson();
        }
        String persistErr = !dryRun && created[0] ? forceExport(c, edsFqn) : null;
        ToolResult r = ToolResult.success()
            .put("operation", "add_function") //$NON-NLS-1$ //$NON-NLS-2$
            .put("functionFqn", functionFqn) //$NON-NLS-1$
            .put("created", created[0]) //$NON-NLS-1$
            .put("typeApplied", typeApplied[0]) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", created[0] ? "Function added." : "Function already exists."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (persistErr != null)
        {
            r.put("persistFailed", persistErr); //$NON-NLS-1$
        }
        if (c.flushPending)
        {
            // Row 42: BM committed but the .mdo disk flush did not confirm in
            // time (was log-only) - surface it so the agent can re-drive it.
            r.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
        }
        return r.toJson();
    }

    // ============================================================== remove_field

    private String opRemoveField(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final String tableFqn = resolveTableFqn(params);
        if (tableFqn == null)
        {
            return ToolResult.error("ownerFqn=ExternalDataSource.<eds>.Table.<t> " //$NON-NLS-1$
                + "(or dataSourceName + tableName) is required").toJson(); //$NON-NLS-1$
        }
        Ctx c = resolve(projectName);
        if (c.error != null)
        {
            return ToolResult.error(c.error).toJson();
        }
        final boolean[] removed = { false };

        String txErr = runInTx(c.bmModel, "eds.remove_field", dryRun, tx -> { //$NON-NLS-1$
            Object ownerObj = tx.getTopObjectByFqn(tableFqn);
            if (!(ownerObj instanceof Table))
            {
                throw new RuntimeException("Table not found: " + tableFqn); //$NON-NLS-1$
            }
            Table table = (Table)ownerObj;
            Field target = null;
            for (Field f : table.getTableFields())
            {
                if (f != null && name.equalsIgnoreCase(f.getName()))
                {
                    target = f;
                    break;
                }
            }
            if (target != null)
            {
                table.getTableFields().remove(target);
                removed[0] = true;
            }
        });
        if (txErr != null)
        {
            return ToolResult.error(txErr).toJson();
        }
        String persistErr = !dryRun && removed[0] ? forceExport(c, tableFqn) : null;
        ToolResult r = ToolResult.success()
            .put("operation", "remove_field") //$NON-NLS-1$ //$NON-NLS-2$
            .put("removed", removed[0]) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", removed[0] ? "Field removed." : "Field not found."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (persistErr != null)
        {
            r.put("persistFailed", persistErr); //$NON-NLS-1$
        }
        if (c.flushPending)
        {
            // Row 42: BM committed but the .mdo disk flush did not confirm in
            // time (was log-only) - surface it so the agent can re-drive it.
            r.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
        }
        return r.toJson();
    }

    // =========================================================== remove_function

    private String opRemoveFunction(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String edsName = resolveDataSourceName(params);
        if (edsName == null)
        {
            return ToolResult.error("ownerFqn=ExternalDataSource.<name> or dataSourceName is required").toJson(); //$NON-NLS-1$
        }
        Ctx c = resolve(projectName);
        if (c.error != null)
        {
            return ToolResult.error(c.error).toJson();
        }
        final String edsFqn = edsFqn(edsName);
        final boolean[] removed = { false };

        String txErr = runInTx(c.bmModel, "eds.remove_function", dryRun, tx -> { //$NON-NLS-1$
            Object ownerObj = tx.getTopObjectByFqn(edsFqn);
            if (!(ownerObj instanceof ExternalDataSource))
            {
                throw new RuntimeException("ExternalDataSource not found: " + edsFqn); //$NON-NLS-1$
            }
            ExternalDataSource eds = (ExternalDataSource)ownerObj;
            Function target = null;
            for (Function fn : eds.getFunctions())
            {
                if (fn != null && name.equalsIgnoreCase(fn.getName()))
                {
                    target = fn;
                    break;
                }
            }
            if (target != null)
            {
                eds.getFunctions().remove(target);
                removed[0] = true;
            }
        });
        if (txErr != null)
        {
            return ToolResult.error(txErr).toJson();
        }
        String persistErr = !dryRun && removed[0] ? forceExport(c, edsFqn) : null;
        ToolResult r = ToolResult.success()
            .put("operation", "remove_function") //$NON-NLS-1$ //$NON-NLS-2$
            .put("removed", removed[0]) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", removed[0] ? "Function removed." : "Function not found."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (persistErr != null)
        {
            r.put("persistFailed", persistErr); //$NON-NLS-1$
        }
        if (c.flushPending)
        {
            // Row 42: BM committed but the .mdo disk flush did not confirm in
            // time (was log-only) - surface it so the agent can re-drive it.
            r.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
        }
        return r.toJson();
    }

    // ===================================================================== list

    private String opList(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String err = requireNonEmpty(projectName, "projectName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String edsName = resolveDataSourceName(params);
        if (edsName == null)
        {
            return ToolResult.error("ownerFqn=ExternalDataSource.<name> or dataSourceName is required").toJson(); //$NON-NLS-1$
        }
        Ctx c = resolve(projectName);
        if (c.error != null)
        {
            return ToolResult.error(c.error).toJson();
        }
        if (c.config == null)
        {
            return ToolResult.error("Configuration not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }
        MdObject obj = MetadataTypeCatalog.findObject(c.config, "ExternalDataSource", edsName); //$NON-NLS-1$
        if (!(obj instanceof ExternalDataSource))
        {
            return ToolResult.error("ExternalDataSource not found: " + edsName).toJson(); //$NON-NLS-1$
        }
        ExternalDataSource eds = (ExternalDataSource)obj;

        List<Object> tables = new ArrayList<>();
        for (Table t : eds.getTables())
        {
            if (t == null)
            {
                continue;
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", t.getName()); //$NON-NLS-1$
            tm.put("nameInDataSource", t.getNameInDataSource()); //$NON-NLS-1$
            if (t.getTableType() != null)
            {
                tm.put("tableType", t.getTableType().getName()); //$NON-NLS-1$
            }
            if (t.getTableDataType() != null)
            {
                tm.put("tableDataType", t.getTableDataType().getName()); //$NON-NLS-1$
            }
            if (t.getExpressionInDataSource() != null && !t.getExpressionInDataSource().isEmpty())
            {
                tm.put("expressionInDataSource", t.getExpressionInDataSource()); //$NON-NLS-1$
            }
            List<Object> fields = new ArrayList<>();
            for (Field f : t.getTableFields())
            {
                if (f == null)
                {
                    continue;
                }
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.getName()); //$NON-NLS-1$
                fm.put("nameInDataSource", f.getNameInDataSource()); //$NON-NLS-1$
                fm.put("type", typeNames(f)); //$NON-NLS-1$
                fields.add(fm);
            }
            tm.put("fields", fields); //$NON-NLS-1$
            tables.add(tm);
        }
        List<Object> functions = new ArrayList<>();
        for (Function fn : eds.getFunctions())
        {
            if (fn == null)
            {
                continue;
            }
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", fn.getName()); //$NON-NLS-1$
            fm.put("type", typeNames(fn)); //$NON-NLS-1$
            if (fn.getExpressionInDataSource() != null && !fn.getExpressionInDataSource().isEmpty())
            {
                fm.put("expressionInDataSource", fn.getExpressionInDataSource()); //$NON-NLS-1$
            }
            functions.add(fm);
        }
        return ToolResult.success()
            .put("operation", "list") //$NON-NLS-1$ //$NON-NLS-2$
            .put("dataSource", edsName) //$NON-NLS-1$
            .put("tableCount", tables.size()) //$NON-NLS-1$
            .put("functionCount", functions.size()) //$NON-NLS-1$
            .put("cubeCount", eds.getCubes().size()) //$NON-NLS-1$
            .put("tables", tables) //$NON-NLS-1$
            .put("functions", functions) //$NON-NLS-1$
            .toJson();
    }

    // ================================================================== helpers

    /** Read-model context: project + configuration + BM model, or an error. */
    private static final class Ctx
    {
        IProject project;
        Configuration config;
        IBmModel bmModel;
        IBmModelManager bmManager;
        String error;
        /** Row 42: set by {@link #forceExport} when the on-disk flush is pending. */
        boolean flushPending;
    }

    private Ctx resolve(String projectName)
    {
        Ctx c = new Ctx();
        c.project = ProjectResolver.resolve(projectName);
        if (c.project == null)
        {
            c.error = ProjectResolver.describeNotFound(projectName);
            return c;
        }
        IConfigurationProvider cfgProvider = Activator.getDefault().getConfigurationProvider();
        c.config = cfgProvider != null ? cfgProvider.getConfiguration(c.project) : null;
        c.bmManager = Activator.getDefault().getBmModelManager();
        c.bmModel = c.bmManager != null ? c.bmManager.getModel(c.project) : null;
        if (c.bmModel == null)
        {
            c.error = "object model not loaded for project: " + projectName; //$NON-NLS-1$
        }
        return c;
    }

    /**
     * Runs a mutation inside a BM global-editing-context transaction. The global
     * context is required so a newly created top-object can be registered with
     * {@code attachTopObject} (a plain {@code bmModel.execute} fails to persist
     * top-object references in extension projects). {@code dryRun} aborts the
     * transaction after the action so nothing is written.
     *
     * @return {@code null} on success (or a completed dry run), or an error message.
     */
    private static String runInTx(IBmModel bmModel, String taskName, boolean dryRun, TxAction action)
    {
        final String[] errBox = { null };
        try
        {
            bmModel.getGlobalContext().execute((IBmTask)new AbstractBmTask<Void>(taskName)
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        action.run(tx);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName(), e);
                    }
                    if (dryRun)
                    {
                        throw new DryRunSignal();
                    }
                    return null;
                }
            });
        }
        catch (DryRunSignal dra)
        {
            return null; // expected: transaction rolled back, nothing written
        }
        catch (Exception e)
        {
            if (containsDryRunSignal(e))
            {
                return null;
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            errBox[0] = cause.getMessage() != null ? cause.getMessage()
                : cause.getClass().getSimpleName();
            Activator.logWarning("external_data_source_workshop " + taskName //$NON-NLS-1$
                + " failed: " + errBox[0]); //$NON-NLS-1$
        }
        return errBox[0];
    }

    private static boolean containsDryRunSignal(Throwable t)
    {
        for (Throwable cur = t; cur != null; cur = cur.getCause())
        {
            if (cur instanceof DryRunSignal)
            {
                return true;
            }
        }
        return false;
    }

    /** Marker thrown to roll back a dry-run transaction. */
    private static final class DryRunSignal extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    @FunctionalInterface
    private interface TxAction
    {
        void run(IBmTransaction tx) throws Exception;
    }

    /**
     * Forces the given top-object FQNs to disk and waits for the save to flush.
     *
     * @return {@code null} on success, or an error message when the export did
     *     not complete. On failure the object exists in the BM model but the
     *     {@code .mdo} on disk may be missing or stale, so the caller MUST
     *     surface this (mirrors the {@code persistFailed} signal used by
     *     {@code EditMetadataTool}) rather than report an unqualified success.
     */
    private String forceExport(Ctx c, String... fqns)
    {
        try
        {
            BmExportHelper.Result exp = BmExportHelper.forceExportAndWait(
                c.bmManager, c.project, java.util.Arrays.asList(fqns), 10_000L);
            if (exp == null)
            {
                return "forceExport returned no result"; //$NON-NLS-1$
            }
            if (!exp.isOk())
            {
                String detail = exp.error != null ? exp.error : "forceExport did not complete"; //$NON-NLS-1$
                Activator.logWarning("external_data_source_workshop forceExport(" //$NON-NLS-1$
                    + String.join(", ", fqns) + "): " + detail); //$NON-NLS-1$ //$NON-NLS-2$
                return detail;
            }
            if (exp.syncFlushPending)
            {
                // Row 42: committed to BM, disk flush pending. Not a failure - do
                // NOT return a non-null string (callers treat that as failure);
                // flag it on the Ctx so the op can surface a diskFlushPending tag
                // (and log so a stale .mdo on a large config stays traceable).
                c.flushPending = true;
                Activator.logWarning("external_data_source_workshop forceExport(" //$NON-NLS-1$
                    + String.join(", ", fqns) //$NON-NLS-1$
                    + "): committed to BM, disk flush pending"); //$NON-NLS-1$
            }
            return null;
        }
        catch (Exception e)
        {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Activator.logWarning("external_data_source_workshop forceExport failed: " + detail); //$NON-NLS-1$
            return detail;
        }
    }

    private static BmDefinedTypeHelper.QualifierOptions readQualifiers(Map<String, String> params)
    {
        BmDefinedTypeHelper.QualifierOptions q = new BmDefinedTypeHelper.QualifierOptions();
        q.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        q.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        q.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            q.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        q.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        q.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$
        return q;
    }

    /**
     * Resolves the ExternalDataSource name from {@code ownerFqn}
     * ({@code ExternalDataSource.<name>} or a deeper table FQN whose EDS segment
     * is used) or from {@code dataSourceName}.
     */
    private static String resolveDataSourceName(Map<String, String> params)
    {
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        if (ownerFqn != null && !ownerFqn.isEmpty())
        {
            String[] segs = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\."); //$NON-NLS-1$
            if (segs.length >= 2 && "ExternalDataSource".equals(segs[0])) //$NON-NLS-1$
            {
                return segs[1];
            }
        }
        String dsn = JsonUtils.extractStringArgument(params, "dataSourceName"); //$NON-NLS-1$
        return dsn != null && !dsn.isEmpty() ? dsn : null;
    }

    /**
     * Resolves the Table FQN for add_field / remove_field from
     * {@code ownerFqn=ExternalDataSource.<eds>.Table.<t>} or from
     * {@code dataSourceName} + {@code tableName}.
     */
    private static String resolveTableFqn(Map<String, String> params)
    {
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        if (ownerFqn != null && !ownerFqn.isEmpty())
        {
            String[] segs = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\."); //$NON-NLS-1$
            if (segs.length >= 4 && "ExternalDataSource".equals(segs[0]) //$NON-NLS-1$
                && "Table".equals(segs[2])) //$NON-NLS-1$
            {
                return segs[0] + "." + segs[1] + ".Table." + segs[3]; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String dsn = JsonUtils.extractStringArgument(params, "dataSourceName"); //$NON-NLS-1$
        String tableName = JsonUtils.extractStringArgument(params, "tableName"); //$NON-NLS-1$
        if (dsn != null && !dsn.isEmpty() && tableName != null && !tableName.isEmpty())
        {
            return "ExternalDataSource." + dsn + ".Table." + tableName; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static String edsFqn(String edsName)
    {
        return "ExternalDataSource." + edsName; //$NON-NLS-1$
    }

    private static void putSynonym(MdObject obj, String synonym, String fallbackName)
    {
        String value = synonym != null && !synonym.isEmpty() ? synonym : fallbackName;
        if (value != null && !value.isEmpty() && obj.getSynonym() != null)
        {
            obj.getSynonym().put("ru", value); //$NON-NLS-1$
        }
    }

    private static ExternalDataSourceTableType parseTableType(String s)
    {
        if (s != null)
        {
            String v = s.trim().toLowerCase();
            if (v.startsWith("expr") || v.startsWith("выр")) // выр(ажение) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return ExternalDataSourceTableType.EXPRESSION;
            }
        }
        return ExternalDataSourceTableType.TABLE;
    }

    private static ExternalDataSourceTableDataType parseTableDataType(String s)
    {
        if (s != null)
        {
            String v = s.trim().toLowerCase();
            // "Object" / "ObjectData" / "Объектные"; everything else -> Nonobject default
            if (v.equals("object") || v.equals("objectdata") //$NON-NLS-1$ //$NON-NLS-2$
                || v.startsWith("объект")) // объект(ные) //$NON-NLS-1$
            {
                return ExternalDataSourceTableDataType.OBJECT_DATA;
            }
        }
        return ExternalDataSourceTableDataType.NONOBJECT_DATA;
    }

    private static List<String> typeNames(MdObject typed)
    {
        try
        {
            return new ArrayList<>(BmDefinedTypeHelper.readExistingTypeNames(typed));
        }
        catch (Exception e)
        {
            return Collections.emptyList();
        }
    }

    private static String requireNonEmpty(String value, String paramName)
    {
        return value == null || value.trim().isEmpty() ? paramName + " is required. " : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOps()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("add_table", //$NON-NLS-1$
            "Add a Table to an ExternalDataSource (separate top-object). " //$NON-NLS-1$
                + "ownerFqn=ExternalDataSource.<name> (or dataSourceName). Params: name, " //$NON-NLS-1$
                + "nameInDataSource, tableType (Table|Expression), tableDataType " //$NON-NLS-1$
                + "(Nonobject default|Object), synonym."); //$NON-NLS-1$
        m.put("add_field", //$NON-NLS-1$
            "Add a Field to a Table (inline). ownerFqn=ExternalDataSource.<eds>.Table.<t> " //$NON-NLS-1$
                + "(or dataSourceName+tableName). Params: name, type, nameInDataSource, " //$NON-NLS-1$
                + "length/precision/fractionDigits/dateFractions/allowedLength/nonNegative, synonym."); //$NON-NLS-1$
        m.put("add_function", //$NON-NLS-1$
            "Add a Function to an ExternalDataSource. ownerFqn=ExternalDataSource.<name>. " //$NON-NLS-1$
                + "Params: name, type (return type, same qualifiers as add_field), " //$NON-NLS-1$
                + "expressionInDataSource, synonym."); //$NON-NLS-1$
        m.put("remove_field", //$NON-NLS-1$
            "Remove a Field from a Table by name. ownerFqn=Table FQN, name=field."); //$NON-NLS-1$
        m.put("remove_function", //$NON-NLS-1$
            "Remove a Function from an ExternalDataSource by name. " //$NON-NLS-1$
                + "ownerFqn=ExternalDataSource.<name>, name=function."); //$NON-NLS-1$
        m.put("list", //$NON-NLS-1$
            "List tables (with fields) and functions of an ExternalDataSource. " //$NON-NLS-1$
                + "ownerFqn=ExternalDataSource.<name>."); //$NON-NLS-1$
        return m;
    }

    private String handleHelp()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("external_data_source_workshop - ExternalDataSource content constructor.\n\n"); //$NON-NLS-1$
        sb.append("Create the ExternalDataSource first:\n"); //$NON-NLS-1$
        sb.append("  edit_metadata create_object objectType=ExternalDataSource name=<eds>\n\n"); //$NON-NLS-1$
        sb.append("Operations:\n"); //$NON-NLS-1$
        for (Map.Entry<String, String> e : OPS.entrySet())
        {
            sb.append("  ").append(e.getKey()).append(" - ").append(e.getValue()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("\nFQN scheme: ExternalDataSource.<eds> -> .Table.<t> -> .Table.<t>.Field.<f>; ") //$NON-NLS-1$
            .append(".Function.<fn>.\n"); //$NON-NLS-1$
        sb.append("Persistence: Tables/Functions are separate .mdo top-objects; Fields are ") //$NON-NLS-1$
            .append("inline in the Table .mdo.\n"); //$NON-NLS-1$
        sb.append("Tables are separate top-objects (delete a whole one via ") //$NON-NLS-1$
            .append("delete_metadata_object); Functions are inline (remove via remove_function).\n"); //$NON-NLS-1$
        sb.append("Not covered: OLAP Cubes (dimensions/resources); key fields for Object-data ") //$NON-NLS-1$
            .append("tables (use tableDataType=Nonobject, the default).\n"); //$NON-NLS-1$
        return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
    }
}
