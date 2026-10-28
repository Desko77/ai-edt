/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.ArrayList;
import java.util.List;

import ru.aiedt.mcp.oform.OwnerModel;

/**
 * The owner of a form in words, in the session's own types for the dialogs of the editor: what
 * {@code describe_owner} answers - the owner's attributes, its tabular sections with their own
 * attributes, its standard attributes and the columns of its list - without a uuid and without a
 * map the panel would have to read blindly.
 */
public final class OwnerWords
{
    /** The owner's FQN, {@code Type.Object}. */
    public final String fqn;

    /** The owner's kind in EDT's English spelling. */
    public final String kind;

    /** The attributes a field can be bound to. */
    public final List<Word> attributes;

    /** The tabular sections, each with the attributes of its rows. */
    public final List<Section> tabularSections;

    /** The standard attributes of the owner's object. */
    public final List<Word> standardAttributes;

    /** The columns a list of the owner shows. */
    public final List<Word> listColumns;

    private OwnerWords(String fqn, String kind, List<Word> attributes, List<Section> tabularSections,
        List<Word> standardAttributes, List<Word> listColumns)
    {
        this.fqn = fqn;
        this.kind = kind;
        this.attributes = attributes;
        this.tabularSections = tabularSections;
        this.standardAttributes = standardAttributes;
        this.listColumns = listColumns;
    }

    /**
     * Reads a model's own words into these types.
     *
     * @param model the owner as {@code describe_owner} reads it
     * @return the words
     */
    public static OwnerWords of(OwnerModel model)
    {
        List<Word> attributes = new ArrayList<>();
        for (OwnerModel.Attribute attribute : model.attributes)
        {
            if (attribute.placeable() && !attribute.folderOnly)
            {
                attributes.add(new Word(attribute.name, attribute.synonym, attribute.typeText));
            }
        }
        List<Section> sections = new ArrayList<>();
        for (OwnerModel.TabularSection section : model.tabularSections)
        {
            List<Word> rows = new ArrayList<>();
            for (OwnerModel.Attribute attribute : section.attributes)
            {
                rows.add(new Word(attribute.name, attribute.synonym, attribute.typeText));
            }
            sections.add(new Section(section.name, section.synonym, rows));
        }
        List<Word> standards = new ArrayList<>();
        for (OwnerModel.Attribute standard : model.standards)
        {
            if (standard.placeable() && !standard.folderOnly)
            {
                standards.add(new Word(standard.name, standard.synonym, standard.typeText));
            }
        }
        List<Word> columns = new ArrayList<>();
        for (OwnerModel.Attribute column : model.listColumns())
        {
            columns.add(new Word(column.name, column.synonym, column.typeText));
        }
        return new OwnerWords(model.fqn, model.kind, List.copyOf(attributes), List.copyOf(sections),
            List.copyOf(standards), List.copyOf(columns));
    }

    /** One named, typed thing of the owner. */
    public static final class Word
    {
        /** The name a data path spells. */
        public final String name;

        /** The synonym the dialog shows. */
        public final String synonym;

        /** The type in words. */
        public final String type;

        Word(String name, String synonym, String type)
        {
            this.name = name;
            this.synonym = synonym;
            this.type = type;
        }
    }

    /** One tabular section with its own attributes. */
    public static final class Section
    {
        /** The section's name. */
        public final String name;

        /** The section's synonym. */
        public final String synonym;

        /** The attributes of its rows. */
        public final List<Word> attributes;

        Section(String name, String synonym, List<Word> attributes)
        {
            this.name = name;
            this.synonym = synonym;
            this.attributes = attributes;
        }
    }
}
