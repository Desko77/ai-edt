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
import com._1c.g5.v8.dt.metadata.dbview.DbViewDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewElement;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.Table;
import com._1c.g5.v8.dt.ql.model.AbstractExpression;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaQuery;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaQuerySourceJoin;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaSource;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaTable;
import com._1c.g5.v8.dt.ql.model.CommonExpression;
import com._1c.g5.v8.dt.ql.model.DbViewFromQuery;
import com._1c.g5.v8.dt.ql.model.NestedTableAllFieldsExpression;
import com._1c.g5.v8.dt.ql.model.NestedTableExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchema;
import com._1c.g5.v8.dt.ql.model.QuerySchemaExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaOperator;
import com._1c.g5.v8.dt.ql.model.QuerySchemaQuerySourceJoin;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSource;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTable;
import com._1c.g5.v8.dt.ql.model.StarExpression;
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
 * An asterisk is expanded rather than passed through. {@code ВЫБРАТЬ *} names no columns in the
 * text at all, so reporting it as one nameless column of type "star" told a caller nothing it could
 * not already see; the fields come from the same model that {@code describe_db_tables} reads.
 * </p>
 * <p>
 * Types are reported at the confidence they were obtained with, and a column whose type cannot be
 * told carries no type at all. Guessing one would be worse than the silence: a caller trusts what it
 * is told, and a confident wrong type is acted on.
 * </p>
 */
public final class QueryResultSchema
{
    /** How a qualified asterisk ends, as it is written. */
    private static final String ALL_FIELDS = ".*"; //$NON-NLS-1$

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
            boolean russian = writtenInRussian(queryText);

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
                List<Column> columns = columnsOf(select, typeChecker, russian);
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
     * @param russian whether the query is written in Russian
     * @return the columns in select order
     */
    private static List<Column> columnsOf(QuerySchemaSelectQuery select,
        IExpressionTypeChecker typeChecker, boolean russian)
    {
        List<Column> columns = new ArrayList<>();
        for (QuerySchemaOperator operator : select.getOperators())
        {
            for (QuerySchemaExpression field : operator.getSelectFields())
            {
                List<Column> starred = expandAllFields(field, operator, russian);
                if (starred != null && !starred.isEmpty())
                {
                    columns.addAll(starred);
                    continue;
                }
                columns.add(new Column(nameOf(field, columns.size()), typesOf(field, typeChecker)));
            }
            // The first operator carries the shape of the whole result.
            break;
        }
        return columns;
    }

    /**
     * The columns an asterisk stands for, or <code>null</code> when the field is not one.
     * <p>
     * An asterisk is the one place where the query text does not say what comes back, so reading
     * the columns off the text is not merely awkward but impossible - which is why it used to be
     * reported as a single nameless column. The environment knows: every source in the statement
     * resolves to a table of the model, and that table lists its fields.
     * </p>
     * <p>
     * Two spellings reach here. A bare {@code *} stands for every field of every source, joined
     * ones included, in source order. A qualified {@code T.*} stands for the fields of whatever
     * {@code T} names - a source, or a tabular section reached through one.
     * </p>
     *
     * @param field the select field
     * @param operator the statement it belongs to
     * @param russian whether to name the columns in Russian
     * @return the columns, or <code>null</code> when this field is not an asterisk
     */
    private static List<Column> expandAllFields(QuerySchemaExpression field,
        QuerySchemaOperator operator, boolean russian)
    {
        AbstractExpression expression = field.getExpression();
        if (expression instanceof StarExpression)
        {
            List<Column> columns = new ArrayList<>();
            // All or nothing. A statement that joins a metadata table to a nested query expands
            // only half, and half of an asterisk is worse than none of it: the caller is handed a
            // column list that looks complete and silently lacks everything the nested query
            // contributes.
            return addSourceFields(columns, operator.getSources(), russian) ? columns : null;
        }
        CommonExpression qualifier = qualifierOfAllFields(expression);
        if (qualifier != null)
        {
            List<Column> named = fieldsOf(qualifier.getItemDbView(), russian);
            return named != null ? named : followQualifier(qualifier.getContent(), operator, russian);
        }
        return null;
    }

    /**
     * What stands to the left of the dot in a qualified asterisk, or <code>null</code> when this is
     * not one.
     * <p>
     * The environment has more than one shape for {@code T.*} depending on what {@code T} turns out
     * to be, and which one arrives is not worth depending on - all of them carry the same thing,
     * the expression naming the table.
     * </p>
     *
     * @param expression the select expression
     * @return the qualifier, or <code>null</code>
     */
    private static CommonExpression qualifierOfAllFields(AbstractExpression expression)
    {
        if (expression instanceof NestedTableAllFieldsExpression)
        {
            return ((NestedTableAllFieldsExpression)expression).getTable();
        }
        if (expression instanceof NestedTableExpression
            && namesOnlyTheAsterisk(((NestedTableExpression)expression).getFieldsName()))
        {
            return ((NestedTableExpression)expression).getTable();
        }
        if (expression instanceof CommonExpression
            && ((CommonExpression)expression).getContent() != null
            && ((CommonExpression)expression).getContent().endsWith(ALL_FIELDS))
        {
            return (CommonExpression)expression;
        }
        return null;
    }

    /**
     * Whether a nested table's field list is an asterisk and nothing else.
     * <p>
     * {@code T.*} keeps the asterisk as the one entry of that list rather than leaving it empty,
     * which is how it slipped past the first attempt at this. A list naming actual fields is not an
     * asterisk and must be left alone - those columns are already reported one by one.
     * </p>
     *
     * @param fields the field list
     * @return <code>true</code> when the list is empty or holds only asterisks
     */
    private static boolean namesOnlyTheAsterisk(List<QuerySchemaExpression> fields)
    {
        for (QuerySchemaExpression field : fields)
        {
            if (field == null || !(field.getExpression() instanceof StarExpression))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks a written qualifier like {@code T.Goods} down to the table it names.
     * <p>
     * Needed when the qualifier did not resolve to a table on its own, which is the usual case: an
     * alias is a name the statement gives a source, not an element of the model, and where the
     * asterisk is part of the written text the environment has no field called {@code *} to resolve
     * against either. So the first segment is matched against the statement's sources and each
     * further segment against the fields of what came before.
     * </p>
     *
     * @param content the written text, with or without the trailing asterisk
     * @param operator the statement
     * @param russian whether to name the columns in Russian
     * @return the columns, or <code>null</code> when the qualifier names nothing known
     */
    private static List<Column> followQualifier(String content, QuerySchemaOperator operator,
        boolean russian)
    {
        if (content == null)
        {
            return null;
        }
        String path = content.endsWith(ALL_FIELDS)
            ? content.substring(0, content.length() - ALL_FIELDS.length()) : content;
        String[] segments = path.split("\\."); //$NON-NLS-1$
        if (segments.length == 0 || segments[0].isBlank())
        {
            return null;
        }
        DbViewElement element = sourceNamed(segments[0], operator.getSources());
        for (int i = 1; i < segments.length && element instanceof Table; i++)
        {
            element = fieldNamed((Table)element, segments[i]);
        }
        return fieldsOf(element, russian);
    }

    /**
     * The table a source alias stands for, joins included.
     *
     * @param alias the alias as written
     * @param sources the statement's sources
     * @return the model element, or <code>null</code> when no source carries that alias
     */
    private static DbViewElement sourceNamed(String alias, List<QuerySchemaSource> sources)
    {
        for (QuerySchemaSource source : sources)
        {
            if (source == null)
            {
                continue;
            }
            AbstractQuerySchemaSource own = source.getSource();
            if (own != null && alias.equalsIgnoreCase(own.getAlias()))
            {
                return dbViewOf(own);
            }
            DbViewElement joined = sourceNamed(alias, joinedSources(source));
            if (joined != null)
            {
                return joined;
            }
        }
        return null;
    }

    /**
     * The field of a table under either of its names.
     *
     * @param table the table
     * @param name the name as written
     * @return the field, or <code>null</code> when the table has no such field
     */
    private static DbViewElement fieldNamed(Table table, String name)
    {
        for (DbViewFieldDef field : table.getFields())
        {
            if (field != null
                && (name.equalsIgnoreCase(field.getName()) || name.equalsIgnoreCase(field.getNameRu())))
            {
                return field;
            }
        }
        return null;
    }

    /**
     * Adds the fields of every source and of everything joined to them.
     *
     * @param columns where to add them
     * @param sources the sources
     * @param russian whether to name the columns in Russian
     * @return <code>true</code> when every source resolved to a table of the model
     */
    private static boolean addSourceFields(List<Column> columns, List<QuerySchemaSource> sources,
        boolean russian)
    {
        for (QuerySchemaSource source : sources)
        {
            if (source == null)
            {
                continue;
            }
            List<Column> own = fieldsOf(dbViewOf(source.getSource()), russian);
            if (own == null)
            {
                return false;
            }
            columns.addAll(own);
            if (!addSourceFields(columns, joinedSources(source), russian))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The sources joined to one source.
     *
     * @param source the source
     * @return the joined sources, never <code>null</code>
     */
    private static List<QuerySchemaSource> joinedSources(QuerySchemaSource source)
    {
        List<QuerySchemaSource> joined = new ArrayList<>();
        for (AbstractQuerySchemaQuerySourceJoin join : source.getJoins())
        {
            if (join instanceof QuerySchemaQuerySourceJoin)
            {
                QuerySchemaSource next = ((QuerySchemaQuerySourceJoin)join).getSource();
                if (next != null)
                {
                    joined.add(next);
                }
            }
        }
        return joined;
    }

    /**
     * The model table a source stands on, or <code>null</code> when it does not stand on one.
     * <p>
     * A nested query or a temporary table has no table of the model behind it, and an asterisk over
     * one of those stays unexpanded rather than being answered wrongly.
     * </p>
     *
     * @param source the source, may be <code>null</code>
     * @return the model element, or <code>null</code>
     */
    private static DbViewElement dbViewOf(AbstractQuerySchemaSource source)
    {
        if (!(source instanceof QuerySchemaTable))
        {
            return null;
        }
        AbstractQuerySchemaTable table = ((QuerySchemaTable)source).getTable();
        return table == null ? null : table.getTableDbView();
    }

    /**
     * The real table behind the one a statement holds under an alias.
     * <p>
     * Resolving {@code T} gives back a table that belongs to the query rather than to the
     * configuration: it stands for the source under its alias, keeps only the fields the query
     * actually touched, and points at the real one. Asked through it directly, {@code T.*} answered
     * with an empty list - which reads as "nothing to expand" and is why the qualified asterisk
     * survived three attempts at this.
     * </p>
     *
     * @param table the table as resolved
     * @return the table its fields should be read from, never <code>null</code>
     */
    private static Table unwrap(Table table)
    {
        if (table instanceof DbViewFromQuery)
        {
            DbViewDef original = ((DbViewFromQuery)table).getOriginalDbView();
            if (original != null)
            {
                return original;
            }
        }
        return table;
    }

    /**
     * The fields of one model table as result columns.
     *
     * @param element the model element, may be <code>null</code> or not a table
     * @param russian whether to name the columns in Russian
     * @return the columns, or <code>null</code> when there is no table to read
     */
    private static List<Column> fieldsOf(DbViewElement element, boolean russian)
    {
        if (!(element instanceof Table))
        {
            return null;
        }
        Table table = unwrap((Table)element);
        List<Column> columns = new ArrayList<>();
        for (DbViewFieldDef fieldDef : table.getFields())
        {
            if (fieldDef == null)
            {
                continue;
            }
            String name = russian ? fieldDef.getNameRu() : fieldDef.getName();
            if (name == null || name.isBlank())
            {
                name = fieldDef.getName();
            }
            columns.add(new Column(name, new TreeSet<>(
                BmDefinedTypeHelper.readTypeDescriptionNames(fieldDef.getType()))));
        }
        // A table with no fields is not an answer, it is a table that could not be read - and
        // handing back an empty list says "expanded successfully, to nothing". That is what the
        // query-scoped wrapper standing in for an alias returns, and accepting it silently is what
        // kept the qualified asterisk from ever reaching the path that resolves it.
        return columns.isEmpty() ? null : columns;
    }

    /**
     * Whether the query is written in Russian.
     * <p>
     * It decides which of a field's two names an expanded asterisk is reported under, because that
     * is the one the platform puts on the column at run time. The query's own opening keyword
     * settles it - {@code ВЫБРАТЬ} or {@code SELECT} - rather than the names of its tables: an
     * object called {@code Валюты} is Russian in either language, and reading the language off it
     * answered an English query in Russian.
     * </p>
     *
     * @param queryText the query as written
     * @return <code>true</code> when it is a Russian query
     */
    private static boolean writtenInRussian(String queryText)
    {
        String upper = queryText.toUpperCase();
        int russian = upper.indexOf("ВЫБРАТЬ"); //$NON-NLS-1$
        int english = upper.indexOf("SELECT"); //$NON-NLS-1$
        if (russian < 0 && english < 0)
        {
            // Neither keyword is in a query that reached this far only if it is not a SELECT at
            // all. Russian is the answer a Russian configuration expects when nothing says
            // otherwise.
            return true;
        }
        if (russian < 0 || english < 0)
        {
            return english < 0;
        }
        // Both words appear when one of them is inside a subquery or a string literal. The
        // statement's own language is the one that opens it.
        return russian < english;
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
