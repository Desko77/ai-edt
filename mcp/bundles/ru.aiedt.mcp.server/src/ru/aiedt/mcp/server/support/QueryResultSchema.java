/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaQuery;
import com._1c.g5.v8.dt.ql.model.QuerySchema;
import com._1c.g5.v8.dt.ql.model.QuerySchemaExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaOperator;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery;
import com._1c.g5.v8.dt.ql.typesystem.IExpressionTypeChecker;
import com._1c.g5.v8.dt.qw.ui.utils.QuerySchemaBuilder;
import com._1c.g5.v8.dt.qw.ui.utils.QueryWizardSource;

import ru.aiedt.mcp.server.Activator;

/**
 * What a query returns: the columns of each result table, and their types where they can be told.
 * <p>
 * Validation answers "is this query correct". It does not answer "what comes back", and a caller
 * that cannot ask reads the column names off the query text instead - which is guesswork the moment
 * an alias is computed, a field comes from a virtual table, or a package returns several results.
 * The mistakes that produces (a column that does not exist, a reference compared against a string)
 * survive every check the plugin has and surface at run time on somebody's machine.
 * </p>
 * <p>
 * The answer comes from EDT's own query-wizard model rather than from parsing: it already
 * understands metadata, aliases, temporary tables and the type system, including the extension
 * context of the project it is given.
 * </p>
 * <p>
 * Types are reported at the confidence they were obtained with, and a column whose type cannot be
 * told carries no type at all. Guessing one would be worse than the silence: a caller trusts what it
 * is told, and a confident wrong type is acted on.
 * </p>
 */
public final class QueryResultSchema
{
    /** One column of a result table. */
    public static final class Column
    {
        /** The column name - the alias when there is one. */
        public final String name;

        /** The type names, empty when the type could not be told. */
        public final Set<String> types;

        Column(String name, Set<String> types)
        {
            this.name = name;
            this.types = types;
        }

        /**
         * @return this column as a map for the JSON reply, with no type key when there is no type
         */
        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); //$NON-NLS-1$
            if (!types.isEmpty())
            {
                m.put("types", new ArrayList<>(types)); //$NON-NLS-1$
            }
            return m;
        }
    }

    /** One result the query produces. */
    public static final class ResultTable
    {
        /** Position in {@code ВыполнитьПакет()}, counting temporary-table producers. */
        public final int packageIndex;

        /** The columns, in select order. */
        public final List<Column> columns;

        ResultTable(int packageIndex, List<Column> columns)
        {
            this.packageIndex = packageIndex;
            this.columns = columns;
        }

        /**
         * @return this table as a map for the JSON reply
         */
        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("packageIndex", packageIndex); //$NON-NLS-1$
            List<Object> cols = new ArrayList<>();
            for (Column column : columns)
            {
                cols.add(column.toMap());
            }
            m.put("columns", cols); //$NON-NLS-1$
            return m;
        }
    }

    /** What {@link #describe} produced, including why it produced nothing. */
    public static final class Result
    {
        /** The result tables, in package order. */
        public final List<ResultTable> tables = new ArrayList<>();

        /** How many statements only built temporary tables. */
        public int temporaryTables;

        /** Why nothing could be described, or <code>null</code> when it could. */
        public String error;
    }

    private QueryResultSchema()
    {
        // utility
    }

    /**
     * Describes what the query returns.
     *
     * @param project the project whose metadata and extensions the query is read against
     * @param queryText the query, possibly a package
     * @param dcsMode whether to read the text as a data-composition query, matching how it was
     *            validated - the builder parses differently for the two, so describing a DCS query
     *            in plain mode reports the wrong shape or none at all
     * @return the description; check {@link Result#error} before the tables
     */
    public static Result describe(IProject project, String queryText, boolean dcsMode)
    {
        Result result = new Result();
        if (project == null || queryText == null || queryText.isBlank())
        {
            result.error = "a project and a query text are both required"; //$NON-NLS-1$
            return result;
        }

        Activator activator = Activator.getDefault();
        IV8ProjectManager projectManager = activator == null ? null : activator.getV8ProjectManager();
        IBmModelManager modelManager = activator == null ? null : activator.getBmModelManager();
        if (projectManager == null || modelManager == null)
        {
            result.error = "the EDT project and model services are not available"; //$NON-NLS-1$
            return result;
        }

        QuerySchema schema = null;
        try
        {
            QuerySchemaBuilder builder = new QuerySchemaBuilder(project, dcsMode);
            schema = builder.buildQuerySchema(queryText);
            if (schema == null)
            {
                result.error = "the query text could not be read as a query schema"; //$NON-NLS-1$
                return result;
            }
            QueryWizardSource source =
                new QueryWizardSource(schema, project, projectManager, modelManager);
            IExpressionTypeChecker typeChecker = source.getTypeChecker();

            int packageIndex = 0;
            for (AbstractQuerySchemaQuery query : schema.getQueries())
            {
                if (!(query instanceof QuerySchemaSelectQuery))
                {
                    // DROP produces nothing and takes no slot in the package result.
                    continue;
                }
                QuerySchemaSelectQuery select = (QuerySchemaSelectQuery)query;
                if (producesTemporaryTable(select))
                {
                    // A temporary-table producer returns no result, but it does occupy its slot in
                    // ВыполнитьПакет(). Skipping the index as well would misnumber every result
                    // after it, and the caller indexes into that array.
                    result.temporaryTables++;
                    packageIndex++;
                    continue;
                }
                List<Column> columns = columnsOf(select, typeChecker);
                if (!columns.isEmpty())
                {
                    result.tables.add(new ResultTable(packageIndex, columns));
                }
                packageIndex++;
            }
            return result;
        }
        catch (Exception e)
        {
            Activator.logError("Could not describe the query result", e); //$NON-NLS-1$
            result.error = "the query result could not be described: " + e.getMessage(); //$NON-NLS-1$
            return result;
        }
        finally
        {
            release(schema);
        }
    }

    /**
     * Lets go of the parsed query once its shape has been copied out.
     * <p>
     * The builder parses into a resource of its own, under a URI it stamps with the current time, so
     * nothing else holds the one we are given - dropping it cannot take anybody else's model with
     * it. Whether it would otherwise be collected is not something the builder says either way, and
     * this tool is called by an agent in a loop; the cost of being wrong about that only shows up
     * after hours of use, when it is hard to trace back.
     * </p>
     *
     * @param schema the schema to release, may be <code>null</code>
     */
    private static void release(QuerySchema schema)
    {
        if (schema == null)
        {
            return;
        }
        try
        {
            Resource resource = schema.eResource();
            if (resource == null)
            {
                return;
            }
            ResourceSet set = resource.getResourceSet();
            resource.unload();
            if (set != null)
            {
                set.getResources().remove(resource);
            }
        }
        catch (Exception e)
        {
            // Cleanup failing must not turn a good answer into an error - the caller already has
            // the schema by this point.
            Activator.logError("Could not release the parsed query resource", e); //$NON-NLS-1$
        }
    }

    /**
     * Whether the statement only fills a temporary table.
     *
     * @param select the statement
     * @return <code>true</code> when it places into or adds to a temporary table
     */
    private static boolean producesTemporaryTable(QuerySchemaSelectQuery select)
    {
        if (select.getPlacementTable() != null)
        {
            return true;
        }
        for (QuerySchemaOperator operator : select.getOperators())
        {
            if (operator.getPlacementTable() != null || operator.getAddTempTable() != null)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The columns of one result.
     * <p>
     * A union has one operator per branch and one shape; the first branch is the shape, matching
     * what the platform reports. Merging branch types is left undone rather than done badly - a
     * merged type that is wrong is harder to notice than a type taken from the first branch.
     * </p>
     *
     * @param select the statement
     * @param typeChecker the type checker from the wizard source
     * @return the columns in select order
     */
    private static List<Column> columnsOf(QuerySchemaSelectQuery select,
        IExpressionTypeChecker typeChecker)
    {
        List<Column> columns = new ArrayList<>();
        for (QuerySchemaOperator operator : select.getOperators())
        {
            for (QuerySchemaExpression field : operator.getSelectFields())
            {
                columns.add(new Column(nameOf(field, columns.size()), typesOf(field, typeChecker)));
            }
            // The first operator carries the shape of the whole result.
            break;
        }
        return columns;
    }

    /**
     * The column's name.
     *
     * @param field the select field
     * @param position its zero-based position, used only when there is no name to read
     * @return the name, never <code>null</code>
     */
    private static String nameOf(QuerySchemaExpression field, int position)
    {
        String alias = field.getAlias();
        if (alias != null && !alias.isBlank())
        {
            return alias;
        }
        // A field with no alias still occupies a column. Naming it by position is honest about not
        // knowing, where inventing a name from the expression text would not be.
        return "<column " + (position + 1) + ">"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The column's types, empty when they cannot be told.
     *
     * @param field the select field
     * @param typeChecker the type checker
     * @return the type names, sorted for a stable reply
     */
    private static Set<String> typesOf(QuerySchemaExpression field, IExpressionTypeChecker typeChecker)
    {
        if (typeChecker == null)
        {
            return new TreeSet<>();
        }
        try
        {
            Set<String> names =
                BmDefinedTypeHelper.readTypeDescriptionNames(field.getType(typeChecker));
            return new TreeSet<>(names);
        }
        catch (Exception e)
        {
            // One unresolvable column must not cost the caller the whole schema; the rest of the
            // table is still worth having, and an absent type already means "not known".
            return new TreeSet<>();
        }
    }
}
