/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.DbViewSurvey;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Lists the database tables a metadata object turns into, with the fields of each.
 * <p>
 * Query validation answers "is this text correct" and so only catches what a caller invented; it
 * cannot catch what a caller never thought to ask about. This answers the other half: here are the
 * tables, here are their fields, in both languages, taken from the environment's own derivation
 * rather than from rules about how the platform is supposed to name things.
 * </p>
 *
 * @see DbViewSurvey
 */
public class DbTablesReader implements IMcpTool
{
    public static final String NAME = "describe_db_tables"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=describe_db_tables`; prefer the facade " //$NON-NLS-1$
            + "for new prompts. Lists the database tables a metadata object turns into and the " //$NON-NLS-1$
            + "fields of each: the main table, one per tabular section, the virtual tables of a " //$NON-NLS-1$
            + "register (turnovers, balances, slices) with their parameters, the change table, " //$NON-NLS-1$
            + "and a calculation register's recalculations. Every table and field is named in " //$NON-NLS-1$
            + "both English and Russian, ready to write after FROM / ИЗ, with field types. Use " //$NON-NLS-1$
            + "it before writing a query to learn what may be selected, instead of guessing " //$NON-NLS-1$
            + "field names and checking them one by one with validate_query."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required.", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object (e.g. 'Catalog.Products', 'AccumulationRegister.Sales'). " //$NON-NLS-1$
                    + "Russian type names supported. An object that has no database tables is " //$NON-NLS-1$
                    + "answered with a reason rather than an empty list, which would read as a " //$NON-NLS-1$
                    + "loss.", true) //$NON-NLS-1$
            .booleanProperty("includeFields", //$NON-NLS-1$
                "Include the fields and virtual-table parameters. Default: true. Pass false for " //$NON-NLS-1$
                    + "the table names and field counts alone, which is much smaller on an " //$NON-NLS-1$
                    + "object with many tabular sections.") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("projectName and objectFqn are required.").toJson(); //$NON-NLS-1$
        }
        boolean includeFields = JsonUtils.extractBooleanArgument(params, "includeFields", true); //$NON-NLS-1$
        objectFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        Activator activator = Activator.getDefault();
        IConfigurationProvider provider =
            activator == null ? null : activator.getConfigurationProvider();
        Configuration configuration = provider == null ? null : provider.getConfiguration(project);
        if (configuration == null)
        {
            return ToolResult.error("Configuration not loaded for: " + projectName).toJson(); //$NON-NLS-1$
        }
        String[] parts = objectFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return ToolResult.error("Expected two-segment FQN like 'Catalog.Products'.").toJson(); //$NON-NLS-1$
        }
        MdObject object = MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]);
        if (object == null)
        {
            return ToolResult.error("No such object: " + objectFqn).toJson(); //$NON-NLS-1$
        }

        DbViewSurvey.Survey survey = DbViewSurvey.of(object);
        if (survey.error != null)
        {
            return ToolResult.error(objectFqn + ": " + survey.error).toJson(); //$NON-NLS-1$
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objectFqn", objectFqn); //$NON-NLS-1$
        body.put("projectName", projectName); //$NON-NLS-1$
        body.put("tableCount", survey.tables.size()); //$NON-NLS-1$
        body.put("fieldCount", survey.fieldCount()); //$NON-NLS-1$
        List<Object> tables = new ArrayList<>();
        for (DbViewSurvey.DbTable table : survey.tables)
        {
            tables.add(table.toMap(includeFields));
        }
        body.put("tables", tables); //$NON-NLS-1$
        return ToolResult.success().put("dbTables", body).toJson(); //$NON-NLS-1$
    }
}
