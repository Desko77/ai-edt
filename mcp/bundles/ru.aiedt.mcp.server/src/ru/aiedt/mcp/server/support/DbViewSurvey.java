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

import com._1c.g5.v8.dt.metadata.dbview.BasicDbViewDefs;
import com._1c.g5.v8.dt.metadata.dbview.ChangeableDbViewDefs;
import com._1c.g5.v8.dt.metadata.dbview.DbViewDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewSelectDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewSelectParamDef;
import com._1c.g5.v8.dt.metadata.dbview.Table;
import com._1c.g5.v8.dt.metadata.dbview.util.DbViewUtil;
import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Recalculation;

import ru.aiedt.mcp.server.Activator;

/**
 * The database tables a metadata object turns into, and the fields of each.
 * <p>
 * Metadata says what an object is; the platform decides what it becomes in the database. A catalog
 * gains a table of its own with standard fields beside its attributes, every tabular section gains
 * another, and a register gains virtual ones - turnovers, balances, a slice - that exist only as
 * something a query may select from. None of that is written in the metadata, and a caller working
 * it out from the metadata alone is reimplementing a part of the platform.
 * </p>
 * <p>
 * The environment already derives all of it, so this walks that derivation rather than repeating it.
 * The walk is deliberately over the model and not over a list of types: a type this code has never
 * heard of still yields its tables, because it is the environment that knows them.
 * </p>
 * <p>
 * Both names are always reported. A query is written in one language or the other, and a caller
 * given only the English name cannot write {@code РегистрНакопления.Продажи.Обороты} without
 * translating - which is the guesswork this exists to remove.
 * </p>
 */
public final class DbViewSurvey
{
    /**
     * How deep a table may sit inside another.
     * <p>
     * Tabular sections are one level down and nothing in the platform goes further, so this is a
     * guard against a cycle in the model rather than a real limit on the answer.
     * </p>
     */
    private static final int MAX_DEPTH = 3;

    /** One field of one table. */
    public static final class Field
    {
        /** The English name, as a query written in English would spell it. */
        public final String name;

        /** The Russian name, as a query written in Russian would spell it. */
        public final String nameRu;

        /** The type names, empty when the field carries no resolvable type. */
        public final Set<String> types;

        /** The full name of the table this field is, or <code>null</code> when it is not one. */
        public final String nestedTable;

        Field(String name, String nameRu, Set<String> types, String nestedTable)
        {
            this.name = name;
            this.nameRu = nameRu;
            this.types = types;
            this.nestedTable = nestedTable;
        }

        /**
         * @return this field as a map for the JSON reply, omitting what it does not have
         */
        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); //$NON-NLS-1$
            m.put("nameRu", nameRu); //$NON-NLS-1$
            if (!types.isEmpty())
            {
                m.put("types", new ArrayList<>(types)); //$NON-NLS-1$
            }
            if (nestedTable != null)
            {
                m.put("nestedTable", nestedTable); //$NON-NLS-1$
            }
            return m;
        }
    }

    /** One parameter of a virtual table. */
    public static final class Parameter
    {
        /** The English name. */
        public final String name;

        /** The Russian name. */
        public final String nameRu;

        /** The type names, empty when the parameter carries no resolvable type. */
        public final Set<String> types;

        Parameter(String name, String nameRu, Set<String> types)
        {
            this.name = name;
            this.nameRu = nameRu;
            this.types = types;
        }

        /**
         * @return this parameter as a map for the JSON reply
         */
        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); //$NON-NLS-1$
            m.put("nameRu", nameRu); //$NON-NLS-1$
            if (!types.isEmpty())
            {
                m.put("types", new ArrayList<>(types)); //$NON-NLS-1$
            }
            return m;
        }
    }

    /** One table the object turns into. */
    public static final class DbTable
    {
        /** The English name, ready to be written after {@code FROM}. */
        public final String name;

        /** The Russian name, ready to be written after {@code ИЗ}. */
        public final String nameRu;

        /**
         * What kind of table this is: {@code main}, {@code nested}, {@code virtual},
         * {@code change} or {@code auxiliary}.
         */
        public final String kind;

        /**
         * Whether the environment hides this table from the query builder.
         * <p>
         * Hidden is not the same as absent - the change tables are hidden and are queryable - so it
         * is reported rather than used to drop the table.
         * </p>
         */
        public final boolean hidden;

        /** The fields, in the order the environment holds them. */
        public final List<Field> fields = new ArrayList<>();

        /** The parameters, for a virtual table; empty for the rest. */
        public final List<Parameter> parameters = new ArrayList<>();

        DbTable(String name, String nameRu, String kind, boolean hidden)
        {
            this.name = name;
            this.nameRu = nameRu;
            this.kind = kind;
            this.hidden = hidden;
        }

        /**
         * @param withFields whether to include the fields and parameters
         * @return this table as a map for the JSON reply
         */
        public Map<String, Object> toMap(boolean withFields)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); //$NON-NLS-1$
            m.put("nameRu", nameRu); //$NON-NLS-1$
            m.put("kind", kind); //$NON-NLS-1$
            if (hidden)
            {
                m.put("hidden", true); //$NON-NLS-1$
            }
            m.put("fieldCount", fields.size()); //$NON-NLS-1$
            if (withFields)
            {
                List<Object> out = new ArrayList<>();
                for (Field field : fields)
                {
                    out.add(field.toMap());
                }
                m.put("fields", out); //$NON-NLS-1$
                if (!parameters.isEmpty())
                {
                    List<Object> params = new ArrayList<>();
                    for (Parameter parameter : parameters)
                    {
                        params.add(parameter.toMap());
                    }
                    m.put("parameters", params); //$NON-NLS-1$
                }
            }
            return m;
        }
    }

    /** What {@link #of} produced, including why it produced nothing. */
    public static final class Survey
    {
        /** The tables, main first. */
        public final List<DbTable> tables = new ArrayList<>();

        /** Why nothing could be surveyed, or <code>null</code> when it could. */
        public String error;

        /**
         * @return the total number of fields across every table
         */
        public int fieldCount()
        {
            int total = 0;
            for (DbTable table : tables)
            {
                total += table.fields.size();
            }
            return total;
        }
    }

    private DbViewSurvey()
    {
        // utility
    }

    /**
     * Surveys the tables one metadata object turns into.
     *
     * @param object the metadata object, may be <code>null</code>
     * @return the survey; check {@link Survey#error} before the tables
     */
    public static Survey of(MdObject object)
    {
        Survey survey = new Survey();
        if (object == null)
        {
            survey.error = "a metadata object is required"; //$NON-NLS-1$
            return survey;
        }
        try
        {
            BasicDbViewDefs defs = DbViewUtil.getBasicDbViewDefs(object);
            if (defs == null)
            {
                // A filter criterion keeps its one table outside the usual holder, so a caller
                // asking about one must not be told it has none. Everything else that lands here
                // genuinely never reaches the database - a common module, a form, a role - and
                // saying so is a better answer than an empty list, which reads like a loss.
                DbViewDef only = DbViewUtil.getMainTable(object);
                if (only == null)
                {
                    survey.error = "this kind of object has no database tables"; //$NON-NLS-1$
                    return survey;
                }
                collect(survey, only, "main", 0); //$NON-NLS-1$
                return survey;
            }
            // Told apart by name rather than by identity: these are model proxies, and two reads of
            // the same table are equal without being the same object - which labelled every main
            // table as something secondary. Compared against the holder's own main view rather than
            // against getMainTable, because for an accounting register that call answers with the
            // ext-dimension select instead.
            collectAll(survey, object, nameOf(defs.getMainView()));
            addChangeView(survey, defs);
            if (object instanceof CalculationRegister)
            {
                // A recalculation is a metadata object of its own with a table of its own, and it
                // is reached only through its register.
                for (Recalculation recalculation : ((CalculationRegister)object).getRecalculations())
                {
                    collectAll(survey, recalculation, null);
                    addChangeView(survey, DbViewUtil.getBasicDbViewDefs(recalculation));
                }
            }
            return survey;
        }
        catch (Exception e)
        {
            Activator.logError("Could not survey the object's database tables", e); //$NON-NLS-1$
            survey.error = "the database tables could not be read: " + e.getMessage(); //$NON-NLS-1$
            return survey;
        }
    }

    /**
     * Adds every table one metadata object has, marking the one that is its main table.
     *
     * @param survey what is being filled in
     * @param object the metadata object
     * @param mainName the name of its main table, or <code>null</code> when none is to be marked
     */
    private static void collectAll(Survey survey, MdObject object, String mainName)
    {
        for (DbViewDef def : DbViewUtil.getAllTables(object))
        {
            boolean isMain = mainName != null && mainName.equals(nameOf(def));
            collect(survey, def, isMain ? "main" : kindOf(def), 0); //$NON-NLS-1$
        }
    }

    /**
     * Adds the change table an object keeps outside its list of tables.
     * <p>
     * It is not one of "all tables" and is queryable all the same, so a caller reconciling its own
     * list against ours would otherwise read its own correct entry as an invention. Recalculations
     * have one too, which is why this is asked of them separately.
     * </p>
     *
     * @param survey what is being filled in
     * @param defs the object's view definitions, may be <code>null</code> or have no change table
     */
    private static void addChangeView(Survey survey, BasicDbViewDefs defs)
    {
        if (defs instanceof ChangeableDbViewDefs)
        {
            collect(survey, ((ChangeableDbViewDefs)defs).getChangeView(), "change", 0); //$NON-NLS-1$
        }
    }

    /**
     * A table's name, safe on a missing table.
     *
     * @param def the table, may be <code>null</code>
     * @return the name, or <code>null</code>
     */
    private static String nameOf(DbViewDef def)
    {
        return def == null ? null : def.getName();
    }

    /**
     * Adds one top-level table and, below it, every table that is one of its fields.
     *
     * @param survey what is being filled in
     * @param def the table, may be <code>null</code>
     * @param kind what kind of table it is
     * @param depth how deep this table sits
     */
    private static void collect(Survey survey, DbViewDef def, String kind, int depth)
    {
        if (def == null)
        {
            return;
        }
        collect(survey, def, def.getName(), def.getNameRu(), kind, def.isInvisible(), depth);
    }

    /**
     * Adds one table under names already worked out, then descends into its nested tables.
     * <p>
     * The names are passed in rather than read off the model because a nested table only carries
     * its own last segment: a tabular section knows it is called {@code Contacts} and not that it
     * is {@code Catalog.Partners.Contacts}, which is what a query has to say.
     * </p>
     *
     * @param survey what is being filled in
     * @param source the table in the model
     * @param name the full English name
     * @param nameRu the full Russian name
     * @param kind what kind of table it is
     * @param hidden whether the environment hides it from the query builder
     * @param depth how deep this table sits
     */
    private static void collect(Survey survey, Table source, String name, String nameRu, String kind,
        boolean hidden, int depth)
    {
        if (source == null || depth > MAX_DEPTH)
        {
            return;
        }
        DbTable table = new DbTable(name, nameRu, kind, hidden);
        survey.tables.add(table);

        List<DbViewFieldDef> nested = new ArrayList<>();
        for (DbViewFieldDef field : source.getFields())
        {
            if (field == null)
            {
                continue;
            }
            String nestedName = null;
            if (field instanceof Table)
            {
                // A tabular section is held as a field that is also a table. It is reported in
                // both places: as a field of its owner, because that is how a query reaches it
                // through a reference, and as a table of its own, because that is how a query
                // selects from it directly.
                nestedName = qualify(table.name, field.getName());
                nested.add(field);
            }
            table.fields.add(new Field(field.getName(), field.getNameRu(), typesOf(field), nestedName));
        }
        if (source instanceof DbViewSelectDef)
        {
            for (DbViewSelectParamDef parameter : ((DbViewSelectDef)source).getParams())
            {
                if (parameter != null)
                {
                    table.parameters.add(new Parameter(parameter.getName(), parameter.getNameRu(),
                        readTypes(parameter.getType())));
                }
            }
        }
        // Descended into after the owner is complete, so the reply reads owner-then-contents
        // rather than interleaving the two.
        for (DbViewFieldDef field : nested)
        {
            collect(survey, (Table)field, qualify(table.name, field.getName()),
                qualify(table.nameRu, field.getNameRu()), "nested", hidden, depth + 1); //$NON-NLS-1$
        }
    }

    /**
     * What kind of table this is, for everything that is not the main one.
     *
     * @param def the table
     * @return the kind name
     */
    private static String kindOf(DbViewDef def)
    {
        // A select def is what the query language calls a virtual table: it is computed on
        // selection and takes parameters, where a table def is stored.
        return def instanceof DbViewSelectDef ? "virtual" : "auxiliary"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Joins a nested name to the name of what holds it.
     *
     * @param prefix the owner's name, or <code>null</code> at the top
     * @param name the name, may be <code>null</code>
     * @return the full name
     */
    private static String qualify(String prefix, String name)
    {
        String own = name == null ? "" : name; //$NON-NLS-1$
        return prefix == null || prefix.isEmpty() ? own : prefix + "." + own; //$NON-NLS-1$
    }

    /**
     * The field's type names, empty when they cannot be told.
     *
     * @param field the field
     * @return the type names, sorted for a stable reply
     */
    private static Set<String> typesOf(DbViewFieldDef field)
    {
        return readTypes(field.getType());
    }

    /**
     * Reads a type description into names.
     *
     * @param typeDescription the description, may be <code>null</code>
     * @return the type names, sorted for a stable reply
     */
    private static Set<String> readTypes(Object typeDescription)
    {
        try
        {
            return new TreeSet<>(BmDefinedTypeHelper.readTypeDescriptionNames(typeDescription));
        }
        catch (Exception e)
        {
            // One field whose type will not resolve must not cost the caller the whole table; an
            // absent type already means "not known", which is the honest answer here.
            return new TreeSet<>();
        }
    }
}
