/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.MainAttributes;

/**
 * What an ordinary form is laid out from: the owner's attributes, tabular sections, standard
 * attributes and the produced types the main attribute is typed with.
 *
 * <p>Built by hand in tests and by {@link OwnerModelReader} from the EDT model. The composer
 * reads only this and never the model, so a layout test needs no project. Uuids stay here;
 * {@link #describe()} speaks in names.</p>
 */
public final class OwnerModel
{
    /** One attribute, dimension, resource or standard attribute. */
    public static final class Attribute
    {
        public final String name;

        public final String synonym;

        /** The member uuid, or {@code null} for a standard attribute. */
        public final String uuid;

        /** The negative code of a standard attribute, or {@code null} for a member. */
        public final Integer standardCode;

        /** A folder form's own attribute (Родитель), kept off an item form. */
        public final boolean folderOnly;

        /** The type pattern the layout records, or {@code null} when the attribute is only described. */
        public final ListNode pattern;

        /** The type in words, never a uuid. */
        public final String typeText;

        public Attribute(String name, String synonym, String uuid, Integer standardCode, boolean folderOnly,
            ListNode pattern, String typeText)
        {
            this.name = name;
            this.synonym = synonym;
            this.uuid = uuid;
            this.standardCode = standardCode;
            this.folderOnly = folderOnly;
            this.pattern = pattern;
            this.typeText = typeText == null ? "Any" : typeText; //$NON-NLS-1$
        }

        /** Whether a field or a column can be bound to it. */
        public boolean placeable()
        {
            return uuid != null || standardCode != null;
        }
    }

    /** One tabular section and the attributes of its rows. */
    public static final class TabularSection
    {
        public final String name;

        public final String synonym;

        public final String uuid;

        /** The produced type of the section's rows, what a table over it is typed with. */
        public final String rowTypeUuid;

        /** The type name {@link ru.aiedt.mcp.oform.layout.TableColumns#settingsBlock} matches on. */
        public final String rowTypeName;

        public final List<Attribute> attributes;

        public TabularSection(String name, String synonym, String uuid, String rowTypeUuid, String rowTypeName,
            List<Attribute> attributes)
        {
            this.name = name;
            this.synonym = synonym;
            this.uuid = uuid;
            this.rowTypeUuid = rowTypeUuid;
            this.rowTypeName = rowTypeName;
            this.attributes = List.copyOf(attributes);
        }
    }

    public final String kind;

    public final String name;

    public final String fqn;

    public final String objectTypeUuid;

    public final String objectTypeName;

    public final String listTypeUuid;

    public final String listTypeName;

    public final String recordTypeUuid;

    public final String recordTypeName;

    public final String recordSetTypeUuid;

    public final String recordSetTypeName;

    public final String refTypeUuid;

    public final String refTypeName;

    /** The owner's own attributes, in metadata order. */
    public final List<Attribute> attributes;

    public final List<TabularSection> tabularSections;

    /** Standard attributes of the kind, placeable ones and described-only ones, in display order. */
    public final List<Attribute> standards;

    public final List<Attribute> dimensions;

    public final List<Attribute> resources;

    private OwnerModel(Builder builder)
    {
        this.kind = builder.kind;
        this.name = builder.name;
        this.fqn = builder.fqn != null ? builder.fqn : builder.kind + "." + builder.name; //$NON-NLS-1$
        this.objectTypeUuid = builder.objectTypeUuid;
        this.objectTypeName = builder.objectTypeName;
        this.listTypeUuid = builder.listTypeUuid;
        this.listTypeName = builder.listTypeName;
        this.recordTypeUuid = builder.recordTypeUuid;
        this.recordTypeName = builder.recordTypeName;
        this.recordSetTypeUuid = builder.recordSetTypeUuid;
        this.recordSetTypeName = builder.recordSetTypeName;
        this.refTypeUuid = builder.refTypeUuid;
        this.refTypeName = builder.refTypeName;
        this.attributes = List.copyOf(builder.attributes);
        this.tabularSections = List.copyOf(builder.tabularSections);
        this.standards = List.copyOf(builder.standards);
        this.dimensions = List.copyOf(builder.dimensions);
        this.resources = List.copyOf(builder.resources);
    }

    /** @return a builder of a description */
    public static Builder builder(String kind, String name)
    {
        return new Builder(kind, name);
    }

    /**
     * The produced type a main attribute of this feature is typed with.
     *
     * @param feature {@code objectType}, {@code listType}, {@code recordManagerType} or
     *        {@code recordSetType}
     * @return the uuid, or {@code null}
     */
    public String producedUuid(String feature)
    {
        if ("objectType".equals(feature)) //$NON-NLS-1$
        {
            return objectTypeUuid;
        }
        if ("listType".equals(feature)) //$NON-NLS-1$
        {
            return listTypeUuid;
        }
        if ("recordManagerType".equals(feature)) //$NON-NLS-1$
        {
            return recordTypeUuid;
        }
        if ("recordSetType".equals(feature)) //$NON-NLS-1$
        {
            return recordSetTypeUuid;
        }
        return null;
    }

    /**
     * The type name of that produced type, as the settings block of a table matches it.
     *
     * @param feature the produced-type feature
     * @return the name, or {@code null}
     */
    public String producedName(String feature)
    {
        if ("objectType".equals(feature)) //$NON-NLS-1$
        {
            return objectTypeName;
        }
        if ("listType".equals(feature)) //$NON-NLS-1$
        {
            return listTypeName;
        }
        if ("recordManagerType".equals(feature)) //$NON-NLS-1$
        {
            return recordTypeName;
        }
        if ("recordSetType".equals(feature)) //$NON-NLS-1$
        {
            return recordSetTypeName;
        }
        return null;
    }

    /**
     * Whether an ordinary form of this kind was measured.
     *
     * @param kind the owner's kind, any case
     * @return {@code false} when the only purpose is an empty form
     */
    public static boolean admitsOrdinaryForms(String kind)
    {
        for (MainAttributes.Purpose purpose : MainAttributes.purposesOf(kind))
        {
            if (purpose != MainAttributes.Purpose.NONE)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The header of a form of a purpose, in the order the fields are laid out.
     *
     * @param purpose the purpose
     * @return the attributes, possibly empty
     */
    public List<Attribute> header(MainAttributes.Purpose purpose)
    {
        List<Attribute> out = new ArrayList<>();
        if (purpose == MainAttributes.Purpose.FOLDER)
        {
            return named(standards, "Код", "Наименование", "Родитель"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (purpose == MainAttributes.Purpose.RECORD)
        {
            out.addAll(dimensions);
            out.addAll(resources);
            out.addAll(attributes);
            return out;
        }
        if (purpose == MainAttributes.Purpose.OBJECT)
        {
            for (Attribute standard : standards)
            {
                if (standard.placeable() && !standard.folderOnly)
                {
                    out.add(standard);
                }
            }
            out.addAll(attributes);
        }
        return out;
    }

    /**
     * The tabular sections a form of a purpose shows. A folder form shows none.
     *
     * @param purpose the purpose
     * @return the sections
     */
    public List<TabularSection> tables(MainAttributes.Purpose purpose)
    {
        return purpose == MainAttributes.Purpose.OBJECT ? tabularSections : List.of();
    }

    /**
     * The columns of a list form: a catalog's code and description, a document's date and
     * number, a register's dimensions and then its resources.
     *
     * @return the columns, in order
     */
    public List<Attribute> listColumns()
    {
        String key = kind == null ? "" : kind.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if ("catalog".equals(key) || "chartofcharacteristictypes".equals(key) || "exchangeplan".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return named(standards, "Код", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("document".equals(key) || "businessprocess".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return named(standards, "Дата", "Номер"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("task".equals(key)) //$NON-NLS-1$
        {
            return named(standards, "Дата", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Attribute> out = new ArrayList<>();
        out.addAll(dimensions);
        out.addAll(resources);
        return out;
    }

    /**
     * The columns of a record set: dimensions, resources, then the record's own attributes.
     *
     * @return the columns, in order
     */
    public List<Attribute> recordColumns()
    {
        List<Attribute> out = new ArrayList<>();
        out.addAll(dimensions);
        out.addAll(resources);
        out.addAll(attributes);
        return out;
    }

    /**
     * The description in words: names, synonyms and types, no uuid.
     *
     * @return the answer of {@code describe_owner}
     */
    public Map<String, Object> describe()
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation", "describe_owner"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("success", Boolean.TRUE); //$NON-NLS-1$
        map.put("ownerFqn", fqn); //$NON-NLS-1$
        map.put("kind", kind); //$NON-NLS-1$
        map.put("attributes", words(attributes)); //$NON-NLS-1$
        List<Object> sections = new ArrayList<>();
        for (TabularSection section : tabularSections)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", section.name); //$NON-NLS-1$
            one.put("synonym", section.synonym); //$NON-NLS-1$
            one.put("attributes", words(section.attributes)); //$NON-NLS-1$
            sections.add(one);
        }
        map.put("tabularSections", sections); //$NON-NLS-1$
        map.put("standardAttributes", words(standards)); //$NON-NLS-1$
        map.put("listColumns", words(listColumns())); //$NON-NLS-1$
        if (!dimensions.isEmpty())
        {
            map.put("dimensions", words(dimensions)); //$NON-NLS-1$
        }
        if (!resources.isEmpty())
        {
            map.put("resources", words(resources)); //$NON-NLS-1$
        }
        return map;
    }

    private static List<Attribute> named(List<Attribute> from, String... names)
    {
        List<Attribute> out = new ArrayList<>();
        for (String wanted : names)
        {
            for (Attribute attribute : from)
            {
                if (attribute.placeable() && wanted.equalsIgnoreCase(attribute.name))
                {
                    out.add(attribute);
                    break;
                }
            }
        }
        return out;
    }

    private static List<Object> words(List<Attribute> attributes)
    {
        List<Object> out = new ArrayList<>();
        for (Attribute attribute : attributes)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", attribute.name); //$NON-NLS-1$
            one.put("synonym", attribute.synonym); //$NON-NLS-1$
            one.put("type", attribute.typeText); //$NON-NLS-1$
            out.add(one);
        }
        return out;
    }

    /** Builds an {@link OwnerModel}. */
    public static final class Builder
    {
        private final String kind;

        private final String name;

        private String fqn;

        private String objectTypeUuid;

        private String objectTypeName;

        private String listTypeUuid;

        private String listTypeName;

        private String recordTypeUuid;

        private String recordTypeName;

        private String recordSetTypeUuid;

        private String recordSetTypeName;

        private String refTypeUuid;

        private String refTypeName;

        private final List<Attribute> attributes = new ArrayList<>();

        private final List<TabularSection> tabularSections = new ArrayList<>();

        private final List<Attribute> standards = new ArrayList<>();

        private final List<Attribute> dimensions = new ArrayList<>();

        private final List<Attribute> resources = new ArrayList<>();

        private Builder(String kind, String name)
        {
            this.kind = kind;
            this.name = name;
        }

        /** @return this builder */
        public Builder fqn(String value)
        {
            this.fqn = value;
            return this;
        }

        /** @return this builder */
        public Builder objectType(String uuid, String typeName)
        {
            this.objectTypeUuid = uuid;
            this.objectTypeName = typeName;
            return this;
        }

        /** @return this builder */
        public Builder listType(String uuid, String typeName)
        {
            this.listTypeUuid = uuid;
            this.listTypeName = typeName;
            return this;
        }

        /** @return this builder */
        public Builder recordType(String uuid, String typeName)
        {
            this.recordTypeUuid = uuid;
            this.recordTypeName = typeName;
            return this;
        }

        /** @return this builder */
        public Builder recordSetType(String uuid, String typeName)
        {
            this.recordSetTypeUuid = uuid;
            this.recordSetTypeName = typeName;
            return this;
        }

        /** @return this builder */
        public Builder refType(String uuid, String typeName)
        {
            this.refTypeUuid = uuid;
            this.refTypeName = typeName;
            return this;
        }

        /** @return this builder */
        public Builder attribute(Attribute attribute)
        {
            attributes.add(attribute);
            return this;
        }

        /** @return this builder */
        public Builder standard(Attribute attribute)
        {
            standards.add(attribute);
            return this;
        }

        /** @return this builder */
        public Builder dimension(Attribute attribute)
        {
            dimensions.add(attribute);
            return this;
        }

        /** @return this builder */
        public Builder resource(Attribute attribute)
        {
            resources.add(attribute);
            return this;
        }

        /** @return this builder */
        public Builder section(TabularSection section)
        {
            tabularSections.add(section);
            return this;
        }

        /** @return the description */
        public OwnerModel build()
        {
            return new OwnerModel(this);
        }
    }
}
