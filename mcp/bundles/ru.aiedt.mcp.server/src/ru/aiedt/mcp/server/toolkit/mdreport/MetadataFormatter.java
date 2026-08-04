/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.CharacteristicsDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DbObjectAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.Activator;

/**
 * Writes a metadata object out as the markdown an agent reads.
 * <p>
 * There is one formatter and it handles every type, because it does not know any of them. It walks the
 * EMF model of whatever it is handed - the features of the class, in the order the model declares them -
 * and renders what it finds. A handful of collections earn a table built for them, and everything else
 * falls through to a generic one. Nothing is sorted, nothing is deduplicated, and the order of the
 * sections is the order of the features: that order is part of what the agent is being told.
 * </p>
 * <p>
 * Two decisions in here look like bugs and are not. The full property dump does NOT ask whether a feature
 * was ever set, so a catalog that never wrote a code length still reports the model's default of 9 - the
 * agent is being shown what the object will behave as, not what its file happens to say. The walk over
 * the child collections DOES ask, because an unset collection is not an empty one worth a heading. The
 * asymmetry is the contract. So is the truncation of an inline list at five items to {@code [N items]}:
 * that is a place where the agent stops being told things, and both the threshold and the wording are
 * relied upon.
 * </p>
 * <p>
 * Stateless, and static so that it stays that way. Every intermediate is a local, so two agents may be
 * answered at once; the model underneath them is not thread safe, which is the caller's problem and is
 * why the caller marshals onto the UI thread. Do not give this class a field.
 * </p>
 */
final class MetadataFormatter
{
    /** What the caller gets when there is nothing to format. */
    private static final String NULL_OBJECT_ERROR = "Error: no metadata object was passed in"; //$NON-NLS-1$

    private static final String YES = "Yes"; //$NON-NLS-1$

    private static final String NO = "No"; //$NON-NLS-1$

    /** What an absent value looks like. Not the same as an empty cell, anywhere in here. */
    private static final String DASH = "-"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    /** Longer than this, an inline list is replaced by its size and the agent never sees the contents. */
    private static final int MAX_INLINE_ITEMS = 5;

    private static final String PROPERTY_TABLE_HEADER = "Property"; //$NON-NLS-1$

    private static final String VALUE_TABLE_HEADER = "Value"; //$NON-NLS-1$

    private static final String[] STANDARD_ATTRIBUTE_COLUMNS = { "Name", "Synonym", "Fill Checking", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Full Text Search", "Password Mode", "Multi Line", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Quick Choice", "Create On Input", "Data History" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String[] COMPACT_ATTRIBUTE_COLUMNS = { "Name", "Synonym", "Type" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String[] FULL_ATTRIBUTE_COLUMNS = { "Name", "Synonym", "Type", "Indexing", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "Fill Checking", "Full Text Search", "Password Mode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Multi Line", "Quick Choice", "Create On Input" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String[] CHARACTERISTIC_COLUMNS = { "Index", "Characteristic Types", "Key Field", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Types Filter Field", "Types Filter Value", "Characteristic Values", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Object Field", "Type Field", "Value Field" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * The type names that carry a qualifier, in both languages a configuration may be written in.
     * <p>
     * Cyrillic as escapes, so that the file stays ASCII and no editor guessing an encoding can quietly
     * turn one of these into a literal nothing ever matches.
     * </p>
     */
    private static final String STRING_TYPE = "string"; //$NON-NLS-1$

    /** The Russian for "string", lowercased. */
    private static final String STRING_TYPE_RU = "\u0441\u0442\u0440\u043e\u043a\u0430"; //$NON-NLS-1$

    private static final String NUMBER_TYPE = "number"; //$NON-NLS-1$

    /** The Russian for "number", lowercased. */
    private static final String NUMBER_TYPE_RU = "\u0447\u0438\u0441\u043b\u043e"; //$NON-NLS-1$

    private static final String DATE_TYPE = "date"; //$NON-NLS-1$

    /** The Russian for "date", lowercased. */
    private static final String DATE_TYPE_RU = "\u0434\u0430\u0442\u0430"; //$NON-NLS-1$

    private static final String STANDARD_ATTRIBUTES_METHOD = "getStandardAttributes"; //$NON-NLS-1$

    private static final String ATTRIBUTES_FEATURE = "attributes"; //$NON-NLS-1$

    private static final String TYPE_FEATURE = "type"; //$NON-NLS-1$

    private MetadataFormatter()
    {
        // utility
    }

    /**
     * Writes an object out.
     *
     * @param object the metadata object; may be <code>null</code>
     * @param full <code>true</code> to dump every property the model holds and widen the attribute
     *            tables, <code>false</code> for the name, the synonym and the comment
     * @param language which language to prefer out of a synonym; may be <code>null</code> or unknown, in
     *            which case the first synonym there is wins
     * @return the markdown, never <code>null</code>. It opens at heading level 2: both callers put it
     *         under a heading of their own
     */
    static String format(MdObject object, boolean full, String language)
    {
        if (object == null)
        {
            return NULL_OBJECT_ERROR;
        }

        MarkdownWriter out = new MarkdownWriter();
        // The MODEL name of the type - Catalog, InformationRegister - never the name of the Java class
        // that implements it.
        out.mainHeader(object.eClass().getName(), object.getName());

        if (full)
        {
            appendAllProperties(out, object, language);
        }
        else
        {
            appendBasicProperties(out, object, language);
        }
        appendStandardAttributes(out, object, language);
        appendContainmentSections(out, object, full, language);
        appendSubsystems(out, object);

        return out.toString();
    }

    /**
     * Writes the three things worth knowing about any object.
     *
     * @param out where to write
     * @param object the object, not <code>null</code>
     * @param language the preferred language
     */
    private static void appendBasicProperties(MarkdownWriter out, MdObject object, String language)
    {
        out.sectionHeader("Basic Properties"); //$NON-NLS-1$
        out.tableHeader(PROPERTY_TABLE_HEADER, VALUE_TABLE_HEADER);
        out.row("Name", object.getName()); //$NON-NLS-1$
        // Always, even when there is none: an empty cell says the synonym is missing, which is worth
        // saying. The standard attributes below print a dash for the same thing. They disagree, and both
        // spellings are on the wire.
        out.row("Synonym", getSynonym(object.getSynonym(), language)); //$NON-NLS-1$
        String comment = object.getComment();
        if (comment != null && !comment.isEmpty())
        {
            out.row("Comment", comment); //$NON-NLS-1$
        }
    }

    /**
     * Dumps every property the model stores.
     * <p>
     * Children are left out - they get their own sections further down - and so is anything the model
     * computes rather than keeps. What is left is read whether or not it was ever set, so the defaults
     * show up too: that is the point, an agent asking what an object is needs to know how it will behave,
     * not which lines its file happens to carry.
     * </p>
     *
     * @param out where to write
     * @param object the object, not <code>null</code>
     * @param language the preferred language
     */
    private static void appendAllProperties(MarkdownWriter out, EObject object, String language)
    {
        out.sectionHeader("All Properties"); //$NON-NLS-1$
        out.tableHeader(PROPERTY_TABLE_HEADER, VALUE_TABLE_HEADER);

        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
        {
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
            {
                continue;
            }
            if (feature instanceof EReference && ((EReference)feature).isContainment())
            {
                // Children, and that includes every localized map: a synonym is a containment reference to
                // map entries. They come back as sections of their own.
                continue;
            }

            Object value = object.eGet(feature);
            String rendered = formatDynamicValue(value, language);

            if (rendered == null || DASH.equals(rendered))
            {
                if (value instanceof Collection && ((Collection<?>)value).isEmpty())
                {
                    // An empty list is a thing the object HAS and has nothing in: an empty cell.
                    rendered = EMPTY;
                }
                else if (value == null)
                {
                    // An unset single feature is a thing the object does not have: no row at all.
                    continue;
                }
                // Anything else that came out as a dash keeps it - a type with no types in it, say.
            }
            out.row(formatFeatureName(feature.getName()), rendered);
        }
    }

    /**
     * Writes the standard attributes - the ones the platform gives an object rather than the ones it was
     * given.
     * <p>
     * Found by asking the Java class for the getter, because the common base does not have one and most
     * kinds of object have no standard attributes at all. A missing getter is that, and nothing is
     * written. A getter that fails is a different matter and is logged - but still writes nothing, rather
     * than leaving the agent half a table.
     * </p>
     *
     * @param out where to write
     * @param object the object, not <code>null</code>
     * @param language the preferred language
     */
    private static void appendStandardAttributes(MarkdownWriter out, MdObject object, String language)
    {
        Method getter;
        try
        {
            getter = object.getClass().getMethod(STANDARD_ATTRIBUTES_METHOD);
        }
        catch (NoSuchMethodException e)
        {
            // Normal: this kind of object simply has none.
            return;
        }

        List<String[]> rows;
        try
        {
            rows = standardAttributeRows(getter.invoke(object), language);
        }
        catch (Exception e)
        {
            Activator.logError("Could not read the standard attributes of " + object.getName(), e); //$NON-NLS-1$
            return;
        }
        if (rows.isEmpty())
        {
            return;
        }

        out.sectionHeader("StandardAttributes"); //$NON-NLS-1$
        out.tableHeader(STANDARD_ATTRIBUTE_COLUMNS);
        for (String[] row : rows)
        {
            out.row(row);
        }
    }

    /**
     * Turns the standard attributes into rows, before any of them is written.
     *
     * @param attributes whatever the getter returned; may be <code>null</code> or not a collection
     * @param language the preferred language
     * @return the rows, never <code>null</code>
     */
    private static List<String[]> standardAttributeRows(Object attributes, String language)
    {
        List<String[]> rows = new ArrayList<>();
        if (!(attributes instanceof Collection))
        {
            return rows;
        }
        for (Object item : (Collection<?>)attributes)
        {
            if (!(item instanceof StandardAttribute))
            {
                continue;
            }
            StandardAttribute attribute = (StandardAttribute)item;
            rows.add(new String[] { attribute.getName(),
                // A dash here, an empty cell in the basic properties. Both are on the wire.
                dashIfEmpty(getSynonym(attribute.getSynonym(), language)),
                formatEnum(attribute.getFillChecking()), formatEnum(attribute.getFullTextSearch()),
                formatBoolean(attribute.isPasswordMode()), formatBoolean(attribute.isMultiLine()),
                formatEnum(attribute.getQuickChoice()), formatEnum(attribute.getCreateOnInput()),
                formatEnum(attribute.getDataHistory()) });
        }
        return rows;
    }

    /**
     * Writes a section for every collection of children the object actually has.
     *
     * @param out where to write
     * @param object the object, not <code>null</code>
     * @param full whether the wide tables were asked for
     * @param language the preferred language
     */
    private static void appendContainmentSections(MarkdownWriter out, MdObject object, boolean full,
        String language)
    {
        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
        {
            if (!(feature instanceof EReference))
            {
                continue;
            }
            EReference reference = (EReference)feature;
            if (!reference.isContainment() || !reference.isMany())
            {
                continue;
            }
            if (reference.isDerived() || reference.isTransient() || reference.isVolatile())
            {
                continue;
            }
            // Asked here, and deliberately not asked in the property dump above: a collection nobody ever
            // touched is not an empty one worth a heading.
            if (!object.eIsSet(reference))
            {
                continue;
            }
            Object value = object.eGet(reference);
            if (!(value instanceof Collection) || ((Collection<?>)value).isEmpty())
            {
                continue;
            }
            appendCollection(out, formatFeatureName(reference.getName()), (Collection<?>)value, full, language);
        }
    }

    /**
     * Picks the table a collection deserves, by looking at what its first item is.
     * <p>
     * THE ORDER OF THESE TESTS IS THE CONTRACT. Forms, commands, tabular sections and attributes are all
     * metadata objects; move the plain metadata test above them and every one of those tables silently
     * collapses to a bare name and synonym, taking the types, the indexing and the fill checking with it.
     * A characteristic is a plain EMF object and has to be caught before the plain EMF test for the same
     * reason. A standard attribute is NOT a metadata object, which is why it needs its own line: without
     * it, it would fall to the bottom and be printed a second time, as a nameless dump, under the table
     * that already showed it properly.
     * </p>
     * <p>
     * Only the first item is looked at. A collection of mixed kinds is rendered with the table its first
     * item asked for, and the ones that do not fit it are dropped.
     * </p>
     *
     * @param out where to write
     * @param name the heading, from the name of the feature holding the collection
     * @param items the children, never empty
     * @param full whether the wide tables were asked for
     * @param language the preferred language
     */
    private static void appendCollection(MarkdownWriter out, String name, Collection<?> items, boolean full,
        String language)
    {
        Object first = items.iterator().next();

        if (first instanceof BasicForm)
        {
            appendForms(out, name, items, language);
        }
        else if (first instanceof BasicCommand)
        {
            appendCommands(out, name, items, language);
        }
        else if (first instanceof StandardAttribute)
        {
            // Already written, by the section above.
            return;
        }
        else if (first instanceof CharacteristicsDescription)
        {
            appendCharacteristics(out, name, items);
        }
        else if (first instanceof BasicTabularSection)
        {
            appendTabularSections(out, name, items, full, language);
        }
        else if (first instanceof BasicFeature)
        {
            appendAttributes(out, name, items, full, language);
        }
        else if (first instanceof Map.Entry)
        {
            appendLocalizedMap(out, name, items);
        }
        else if (first instanceof MdObject)
        {
            appendMdObjects(out, name, items, full, language);
        }
        else if (first instanceof EObject)
        {
            appendEObjects(out, name, items);
        }
        // A collection of strings or numbers says nothing worth a table, and nothing is written.
    }

    /**
     * Writes the forms.
     *
     * @param out where to write
     * @param name the heading
     * @param items the forms
     * @param language the preferred language
     */
    private static void appendForms(MarkdownWriter out, String name, Collection<?> items, String language)
    {
        out.sectionHeader(name);
        out.tableHeader("Name", "Synonym", "Form Type"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Object item : items)
        {
            if (!(item instanceof BasicForm))
            {
                continue;
            }
            BasicForm form = (BasicForm)item;
            out.row(form.getName(), getSynonym(form.getSynonym(), language), formatEnum(form.getFormType()));
        }
    }

    /**
     * Writes the commands.
     *
     * @param out where to write
     * @param name the heading
     * @param items the commands
     * @param language the preferred language
     */
    private static void appendCommands(MarkdownWriter out, String name, Collection<?> items, String language)
    {
        out.sectionHeader(name);
        out.tableHeader("Name", "Synonym", "Group"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Object item : items)
        {
            if (!(item instanceof BasicCommand))
            {
                continue;
            }
            BasicCommand command = (BasicCommand)item;
            out.row(command.getName(), getSynonym(command.getSynonym(), language),
                formatCommandGroup(command.getGroup()));
        }
    }

    /**
     * Writes the characteristics - how a chart of characteristic types is wired to the objects carrying
     * them.
     *
     * @param out where to write
     * @param name the heading
     * @param items the characteristics
     */
    private static void appendCharacteristics(MarkdownWriter out, String name, Collection<?> items)
    {
        out.sectionHeader(name);
        out.tableHeader(CHARACTERISTIC_COLUMNS);
        int index = 0;
        for (Object item : items)
        {
            if (!(item instanceof CharacteristicsDescription))
            {
                continue;
            }
            CharacteristicsDescription characteristic = (CharacteristicsDescription)item;
            out.row(String.valueOf(index), formatEObjectReference(characteristic.getCharacteristicTypes()),
                formatEObjectReference(characteristic.getKeyField()),
                formatEObjectReference(characteristic.getTypesFilterField()),
                formatEObjectReference(characteristic.getTypesFilterValue()),
                formatEObjectReference(characteristic.getCharacteristicValues()),
                formatEObjectReference(characteristic.getObjectField()),
                formatEObjectReference(characteristic.getTypeField()),
                formatEObjectReference(characteristic.getValueField()));
            index++;
        }
    }

    /**
     * Writes the tabular sections, each with its own properties and its own attributes under it.
     *
     * @param out where to write
     * @param name the heading
     * @param items the tabular sections
     * @param full whether the wide tables were asked for
     * @param language the preferred language
     */
    private static void appendTabularSections(MarkdownWriter out, String name, Collection<?> items, boolean full,
        String language)
    {
        out.sectionHeader(name);
        for (Object item : items)
        {
            if (!(item instanceof BasicTabularSection))
            {
                continue;
            }
            BasicTabularSection section = (BasicTabularSection)item;

            out.subsectionHeader(section.getName());
            out.tableHeader(PROPERTY_TABLE_HEADER, VALUE_TABLE_HEADER);
            out.row("Name", section.getName()); //$NON-NLS-1$
            out.row("Synonym", getSynonym(section.getSynonym(), language)); //$NON-NLS-1$
            String comment = section.getComment();
            if (comment != null && !comment.isEmpty())
            {
                out.row("Comment", comment); //$NON-NLS-1$
            }
            String toolTip = getSynonym(section.getToolTip(), language);
            if (!toolTip.isEmpty())
            {
                out.row("Tool Tip", toolTip); //$NON-NLS-1$
            }
            out.row("Fill Checking", formatEnum(section.getFillChecking())); //$NON-NLS-1$

            // Neither getter is on the common interface - only some kinds of tabular section have them -
            // so they are asked for by name and simply left out when they are not there.
            Object use = invokeNoArg(section, "getUse"); //$NON-NLS-1$
            if (use != null)
            {
                out.row("Use", formatEnum(use)); //$NON-NLS-1$
            }
            // This one returns a primitive, so reflection boxes it and it is never null: where the getter
            // exists the row is always written, a length of zero included.
            Object lineNumberLength = invokeNoArg(section, "getLineNumberLength"); //$NON-NLS-1$
            if (lineNumberLength != null)
            {
                out.row("Line Number Length", lineNumberLength.toString()); //$NON-NLS-1$
            }

            appendTabularSectionAttributes(out, section, full, language);
        }
    }

    /**
     * Writes the attributes of one tabular section, under the section's own properties.
     * <p>
     * Nothing is written unless all of it can be: a failure here leaves the tabular section as it was
     * rather than trailing off into half a table.
     * </p>
     *
     * @param out where to write
     * @param section the tabular section, not <code>null</code>
     * @param full whether the wide tables were asked for
     * @param language the preferred language
     */
    private static void appendTabularSectionAttributes(MarkdownWriter out, BasicTabularSection section,
        boolean full, String language)
    {
        List<String[]> rows;
        try
        {
            EStructuralFeature feature = section.eClass().getEStructuralFeature(ATTRIBUTES_FEATURE);
            if (feature == null)
            {
                return;
            }
            Object value = section.eGet(feature);
            if (!(value instanceof Collection) || ((Collection<?>)value).isEmpty())
            {
                return;
            }
            rows = attributeRows((Collection<?>)value, full, language);
        }
        catch (Exception e)
        {
            Activator.logError("Could not read the attributes of tabular section " + section.getName(), e); //$NON-NLS-1$
            return;
        }

        out.literal("\n**Attributes:**\n\n"); //$NON-NLS-1$
        out.tableHeader(attributeColumns(full));
        for (String[] row : rows)
        {
            out.row(row);
        }
    }

    /**
     * Writes the attributes of an object.
     *
     * @param out where to write
     * @param name the heading, or empty to write the table without one - which is what the tabular
     *            sections want, having written a heading of their own
     * @param items the attributes
     * @param full whether the wide tables were asked for
     * @param language the preferred language
     */
    private static void appendAttributes(MarkdownWriter out, String name, Collection<?> items, boolean full,
        String language)
    {
        List<String[]> rows = attributeRows(items, full, language);
        if (name != null && !name.isEmpty())
        {
            out.sectionHeader(name);
        }
        out.tableHeader(attributeColumns(full));
        for (String[] row : rows)
        {
            out.row(row);
        }
    }

    /**
     * Turns attributes into rows.
     *
     * @param items the attributes
     * @param full whether the wide tables were asked for
     * @param language the preferred language
     * @return the rows, never <code>null</code>
     */
    private static List<String[]> attributeRows(Collection<?> items, boolean full, String language)
    {
        List<String[]> rows = new ArrayList<>();
        for (Object item : items)
        {
            if (!(item instanceof BasicFeature))
            {
                continue;
            }
            BasicFeature attribute = (BasicFeature)item;
            String synonym = getSynonym(attribute.getSynonym(), language);
            String type = formatType(attribute.getType());

            if (!full)
            {
                rows.add(new String[] { attribute.getName(), synonym, type });
                continue;
            }

            // Indexing and full text search belong to attributes that live in the database. The rest of
            // them - a tabular section's, say - have neither, and say so with a dash.
            String indexing = DASH;
            String fullTextSearch = DASH;
            if (attribute instanceof DbObjectAttribute)
            {
                DbObjectAttribute dbAttribute = (DbObjectAttribute)attribute;
                indexing = formatEnum(dbAttribute.getIndexing());
                fullTextSearch = formatEnum(dbAttribute.getFullTextSearch());
            }
            // These two are asked for by name, and an attribute kind that does not answer is reported as
            // No rather than as unknown - which is what it behaves as.
            String passwordMode = formatBoolean(booleanByReflection(attribute, "isPasswordMode")); //$NON-NLS-1$
            String multiLine = formatBoolean(booleanByReflection(attribute, "isMultiLine")); //$NON-NLS-1$

            rows.add(new String[] { attribute.getName(), synonym, type, indexing,
                formatEnum(attribute.getFillChecking()), fullTextSearch, passwordMode, multiLine,
                formatEnum(attribute.getQuickChoice()), formatEnum(attribute.getCreateOnInput()) });
        }
        return rows;
    }

    /**
     * Returns the columns of an attribute table.
     *
     * @param full whether the wide table was asked for
     * @return the column titles
     */
    private static String[] attributeColumns(boolean full)
    {
        return full ? FULL_ATTRIBUTE_COLUMNS : COMPACT_ATTRIBUTE_COLUMNS;
    }

    /**
     * Writes a localized map - a synonym, a tooltip, an explanation - as one row per language.
     *
     * @param out where to write
     * @param name the heading
     * @param items the map entries
     */
    private static void appendLocalizedMap(MarkdownWriter out, String name, Collection<?> items)
    {
        out.sectionHeader(name);
        out.tableHeader("Language", VALUE_TABLE_HEADER); //$NON-NLS-1$
        for (Object item : items)
        {
            if (!(item instanceof Map.Entry))
            {
                continue;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>)item;
            out.row(asString(entry.getKey()), asString(entry.getValue()));
        }
    }

    /**
     * Writes a collection of metadata objects that has no table of its own.
     *
     * @param out where to write
     * @param name the heading
     * @param items the objects
     * @param full whether the wide table was asked for
     * @param language the preferred language
     */
    private static void appendMdObjects(MarkdownWriter out, String name, Collection<?> items, boolean full,
        String language)
    {
        boolean headerWritten = false;
        for (Object item : items)
        {
            if (!(item instanceof MdObject))
            {
                continue;
            }
            if (!headerWritten)
            {
                out.sectionHeader(name);
                if (full)
                {
                    out.tableHeader("Name", "Synonym", "Type"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                else
                {
                    out.tableHeader("Name", "Synonym"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                headerWritten = true;
            }
            MdObject object = (MdObject)item;
            String synonym = getSynonym(object.getSynonym(), language);
            if (full)
            {
                out.row(object.getName(), synonym, typeOfMdObject(object));
            }
            else
            {
                out.row(object.getName(), synonym);
            }
        }
    }

    /**
     * Writes a collection of plain model objects - whatever they turn out to be.
     * <p>
     * The columns say Name and Value and hold a reference and a type. They are mislabeled, they have
     * always been mislabeled, and the agent has learned to read them that way.
     * </p>
     *
     * @param out where to write
     * @param name the heading
     * @param items the objects
     */
    private static void appendEObjects(MarkdownWriter out, String name, Collection<?> items)
    {
        out.sectionHeader(name);
        out.tableHeader("Name", VALUE_TABLE_HEADER); //$NON-NLS-1$
        for (Object item : items)
        {
            if (!(item instanceof EObject))
            {
                continue;
            }
            EObject object = (EObject)item;
            out.row(EObjectProbe.formatReference(object), object.eClass().getName());
        }
    }

    /**
     * Writes where in the configuration an object is filed.
     * <p>
     * Nothing at all when the object is not in a configuration - an external report has no subsystems to
     * be in - and nothing when it is in none of them.
     * </p>
     *
     * @param out where to write
     * @param object the object, not <code>null</code>
     */
    private static void appendSubsystems(MarkdownWriter out, MdObject object)
    {
        List<String> paths = new ArrayList<>();
        try
        {
            Configuration configuration = configurationOf(object);
            if (configuration == null)
            {
                return;
            }
            for (Subsystem subsystem : configuration.getSubsystems())
            {
                collectSubsystemPaths(subsystem, null, object, paths);
            }
        }
        catch (Exception e)
        {
            // This used to be swallowed, and the whole section went with it. It is still not worth
            // failing the answer over, but it is worth saying so somewhere.
            Activator.logError("Could not resolve the subsystems of " + object.getName(), e); //$NON-NLS-1$
            return;
        }

        if (paths.isEmpty())
        {
            return;
        }
        out.sectionHeader("Subsystems"); //$NON-NLS-1$
        for (String path : paths)
        {
            out.bullet(path);
        }
        out.blankLine();
    }

    /**
     * Collects the dotted paths of every subsystem holding the object, a parent before its children.
     *
     * @param subsystem the subsystem to look in, not <code>null</code>
     * @param parentPath the path of its parent, or <code>null</code> when it is a root
     * @param target the object being looked for, not <code>null</code>
     * @param paths where to collect
     */
    private static void collectSubsystemPaths(Subsystem subsystem, String parentPath, MdObject target,
        List<String> paths)
    {
        String path = parentPath == null ? subsystem.getName() : parentPath + "." + subsystem.getName(); //$NON-NLS-1$
        if (subsystemContains(subsystem, target))
        {
            paths.add(path);
        }
        // Into the children either way: a subsystem holding an object says nothing about whether its
        // children do.
        for (Subsystem child : subsystem.getSubsystems())
        {
            collectSubsystemPaths(child, path, target, paths);
        }
    }

    /**
     * Tells whether a subsystem holds an object.
     * <p>
     * By identity, and failing that by name and type: the same object can be reached as two instances,
     * one through the configuration and one through an extension. A member with no name is skipped. It
     * used to throw, and the exception took the entire Subsystems section down with it, silently, leaving
     * the agent to conclude the object was filed nowhere.
     * </p>
     *
     * @param subsystem the subsystem, not <code>null</code>
     * @param target the object, not <code>null</code>
     * @return <code>true</code> when the subsystem holds it
     */
    private static boolean subsystemContains(Subsystem subsystem, MdObject target)
    {
        for (MdObject member : subsystem.getContent())
        {
            if (member == target)
            {
                return true;
            }
            if (member == null)
            {
                continue;
            }
            String memberName = member.getName();
            if (memberName != null && memberName.equals(target.getName()) && member.eClass().equals(target.eClass()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the configuration an object belongs to.
     *
     * @param object the object, not <code>null</code>
     * @return the configuration, or <code>null</code> when the object is not in one - which is the case
     *         for an external data processor or an external report
     */
    private static Configuration configurationOf(EObject object)
    {
        for (EObject container = object.eContainer(); container != null; container = container.eContainer())
        {
            if (container instanceof Configuration)
            {
                return (Configuration)container;
            }
        }
        return null;
    }

    /**
     * Renders whatever a feature turned out to hold.
     *
     * @param value the value; may be <code>null</code>
     * @param language the preferred language
     * @return the cell text, never <code>null</code>
     */
    private static String formatDynamicValue(Object value, String language)
    {
        if (value == null)
        {
            return DASH;
        }
        if (value instanceof EMap)
        {
            // Defensive: the live walk never gets here, because every localized map is a containment
            // reference and those are skipped before this is called. If one ever does arrive, its text is
            // more use than its entries. Falling through is deliberate - a map with no text in it is
            // still a list, and the collection branch below knows what to do with an empty one.
            String text = getSynonym((EMap<?, ?>)value, language);
            if (!text.isEmpty())
            {
                return text;
            }
        }
        if (value instanceof TypeDescription)
        {
            return formatType((TypeDescription)value);
        }
        if (value instanceof Boolean)
        {
            return formatBoolean((Boolean)value);
        }
        if (value.getClass().isEnum())
        {
            // toString, and never name: an EMF enum stringifies to the literal the model declares
            // (ShowError, DontIndex, Managed) where the Java constant is SHOW_ERROR. The literal is what
            // the agent is being shown and what 1C itself calls it.
            return value.toString();
        }
        if (value instanceof EObject)
        {
            return EObjectProbe.render((EObject)value);
        }
        if (value instanceof Collection)
        {
            return formatCollection((Collection<?>)value);
        }
        return value.toString();
    }

    /**
     * Renders a collection inline, or gives up and says how big it is.
     *
     * @param items the collection, not <code>null</code>
     * @return the cell text, never <code>null</code>
     */
    private static String formatCollection(Collection<?> items)
    {
        if (items.isEmpty())
        {
            return DASH;
        }
        if (items.size() > MAX_INLINE_ITEMS)
        {
            // The agent is told the size and nothing else. This is where it stops being told things, so
            // the wording is worth keeping recognizable.
            return "[" + items.size() + " items]"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<String> pieces = new ArrayList<>();
        for (Object item : items)
        {
            String piece = formatInlineItem(item);
            if (piece != null)
            {
                pieces.add(piece);
            }
        }
        // Joined, not appended one separator at a time: an item that renders to nothing used to leave its
        // separator behind, and the agent got "A, , B".
        return String.join(", ", pieces); //$NON-NLS-1$
    }

    /**
     * Renders one item of an inline collection.
     *
     * @param item the item; may be <code>null</code>
     * @return the text, or <code>null</code> when the item has nothing to contribute and should not even
     *         take a separator with it
     */
    private static String formatInlineItem(Object item)
    {
        if (item == null)
        {
            return null;
        }
        if (item instanceof Map.Entry)
        {
            // Before the model tests below: a map entry is an EMF object too, and the value is the point
            // of it.
            Object value = ((Map.Entry<?, ?>)item).getValue();
            return value == null ? null : value.toString();
        }
        if (item instanceof MdObject)
        {
            return ((MdObject)item).eClass().getName() + "." + ((MdObject)item).getName(); //$NON-NLS-1$
        }
        if (item instanceof EObject)
        {
            return EObjectProbe.formatReference((EObject)item);
        }
        return item.toString();
    }

    /**
     * Renders a type - which in 1C may be several types at once, each with the length or the precision
     * the description gives it.
     *
     * @param typeDescription the description; may be <code>null</code>
     * @return the type, never <code>null</code>; a dash when there is none
     */
    private static String formatType(TypeDescription typeDescription)
    {
        if (typeDescription == null)
        {
            return DASH;
        }
        List<TypeItem> types = typeDescription.getTypes();
        if (types == null || types.isEmpty())
        {
            return DASH;
        }

        StringBuilder rendered = new StringBuilder();
        for (TypeItem type : types)
        {
            if (rendered.length() > 0)
            {
                rendered.append(", "); //$NON-NLS-1$
            }
            String name = McoreUtil.getTypeName(type);
            if (name == null || name.isEmpty())
            {
                name = McoreUtil.getTypeNameRu(type);
            }
            if (name == null || name.isEmpty())
            {
                // Nothing else left to call it by. The Java class, this once.
                name = type.getClass().getSimpleName();
            }
            rendered.append(name).append(qualifierSuffix(name, typeDescription));
        }
        return rendered.length() == 0 ? DASH : rendered.toString();
    }

    /**
     * Returns the length, precision or date parts to write after a type name.
     * <p>
     * The qualifiers hang off the description, not off the individual type, so a composite of a string and
     * a number comes out as {@code String(10), Number(15, 2)} - each type taking the qualifier that is
     * about it. That is how 1C means it.
     * </p>
     *
     * @param typeName the type as it was rendered, in whichever language the configuration is written in
     * @param typeDescription the description holding the qualifiers, not <code>null</code>
     * @return the suffix, or the empty string when the type takes none or the description gives none
     */
    private static String qualifierSuffix(String typeName, TypeDescription typeDescription)
    {
        if (typeName == null)
        {
            return EMPTY;
        }
        String name = typeName.toLowerCase(Locale.ROOT).trim();

        if (STRING_TYPE.equals(name) || STRING_TYPE_RU.equals(name))
        {
            StringQualifiers qualifiers = typeDescription.getStringQualifiers();
            // A length of zero means unlimited, and is still written as (0): that is what the model says.
            return qualifiers == null ? EMPTY : "(" + qualifiers.getLength() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (NUMBER_TYPE.equals(name) || NUMBER_TYPE_RU.equals(name))
        {
            NumberQualifiers qualifiers = typeDescription.getNumberQualifiers();
            if (qualifiers == null)
            {
                return EMPTY;
            }
            if (qualifiers.getScale() > 0)
            {
                return "(" + qualifiers.getPrecision() + ", " + qualifiers.getScale() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return "(" + qualifiers.getPrecision() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (DATE_TYPE.equals(name) || DATE_TYPE_RU.equals(name))
        {
            DateQualifiers qualifiers = typeDescription.getDateQualifiers();
            if (qualifiers == null || qualifiers.getDateFractions() == null)
            {
                return EMPTY;
            }
            // getName of the EMF enumerator - the literal, DateTime, not the Java constant DATE_TIME.
            return "(" + qualifiers.getDateFractions().getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return EMPTY;
    }

    /**
     * Renders the type of a metadata object that happens to have one.
     *
     * @param object the object, not <code>null</code>
     * @return the type, or a dash when the object does not carry one
     */
    private static String typeOfMdObject(MdObject object)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(TYPE_FEATURE);
        if (feature == null)
        {
            return DASH;
        }
        Object value = object.eGet(feature);
        return value instanceof TypeDescription ? formatType((TypeDescription)value) : DASH;
    }

    /**
     * Reads the text of a localized map in the language asked for.
     *
     * @param map the map; may be <code>null</code> or empty
     * @param language the language wanted; may be <code>null</code> or unknown, in which case the first
     *            text there is wins - a configuration written in one language should still answer someone
     *            asking in another
     * @return the text, never <code>null</code>; the empty string when the map holds none
     */
    private static String getSynonym(EMap<?, ?> map, String language)
    {
        if (map == null || map.isEmpty())
        {
            return EMPTY;
        }
        Object wanted = map.get(language);
        if (wanted != null && !wanted.toString().isEmpty())
        {
            return wanted.toString();
        }
        for (Object value : map.values())
        {
            if (value != null && !value.toString().isEmpty())
            {
                return value.toString();
            }
        }
        return EMPTY;
    }

    /**
     * Renders the group a command sits in.
     *
     * @param group the group; may be <code>null</code>
     * @return the group, never <code>null</code>; a dash when the command is in none
     */
    private static String formatCommandGroup(Object group)
    {
        if (group == null)
        {
            return DASH;
        }
        if (group instanceof EObject)
        {
            // A standard command group is a wrapper around the category that IS the group, so it is the
            // category the agent wants to see, not a reference to the wrapper.
            return EObjectProbe.primaryValueAsString((EObject)group);
        }
        return group.toString();
    }

    /**
     * Renders a reference to a model object, for a cell that should show a dash when there is none.
     *
     * @param object the object; may be <code>null</code>
     * @return the reference, or a dash. Note that the inspector answers the empty string for the same
     *         question - the dash is the cell's idea of nothing, not the reference's
     */
    private static String formatEObjectReference(EObject object)
    {
        return object == null ? DASH : EObjectProbe.formatReference(object);
    }

    /**
     * Renders an enum of the model.
     *
     * @param value the enum; may be <code>null</code>
     * @return the literal the model declares - {@code ShowError}, not {@code SHOW_ERROR} - or a dash
     */
    private static String formatEnum(Object value)
    {
        return value == null ? DASH : value.toString();
    }

    /**
     * Renders a flag. Always one word or the other, never a dash: a flag is never absent.
     *
     * @param value the flag
     * @return {@code Yes} or {@code No}
     */
    private static String formatBoolean(boolean value)
    {
        return value ? YES : NO;
    }

    /**
     * Turns the name of a model feature into a column title: {@code codeLength} into {@code Code Length}.
     * <p>
     * Every capital starts a word, which spells an acronym out one letter at a time - {@code URLTemplates}
     * becomes {@code U R L Templates}. It reads oddly and it is what the agent has been given all along.
     * </p>
     *
     * @param name the feature name; may be <code>null</code>
     * @return the title, or <code>null</code> when there was no name - which a cell renders as a dash
     */
    private static String formatFeatureName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return name;
        }
        StringBuilder title = new StringBuilder();
        title.append(Character.toUpperCase(name.charAt(0)));
        for (int index = 1; index < name.length(); index++)
        {
            char character = name.charAt(index);
            if (Character.isUpperCase(character))
            {
                title.append(' ');
            }
            title.append(character);
        }
        return title.toString();
    }

    /**
     * Returns a dash for text that is not there, for the cells that spell it that way.
     *
     * @param value the text; may be <code>null</code> or empty
     * @return the text, or a dash
     */
    private static String dashIfEmpty(String value)
    {
        return value == null || value.isEmpty() ? DASH : value;
    }

    /**
     * Renders anything at all, or nothing.
     *
     * @param value the value; may be <code>null</code>
     * @return its text, or <code>null</code> - which a cell renders as a dash
     */
    private static String asString(Object value)
    {
        return value == null ? null : value.toString();
    }

    /**
     * Calls a getter that only some kinds of object have.
     *
     * @param target the object, not <code>null</code>
     * @param methodName the getter
     * @return what it returned, or <code>null</code> when this kind of object does not have it or would
     *         not say
     */
    private static Object invokeNoArg(Object target, String methodName)
    {
        try
        {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        }
        catch (Exception e)
        {
            // The getter is not on this kind of object: the property is simply absent, and that is not
            // worth a word in the log - most objects are missing most of these.
            return null;
        }
    }

    /**
     * Calls a flag getter that only some kinds of object have.
     *
     * @param target the object, not <code>null</code>
     * @param methodName the getter
     * @return what it returned, or <code>false</code> when this kind of object does not have it - which is
     *         what such an object behaves as
     */
    private static boolean booleanByReflection(Object target, String methodName)
    {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Boolean && ((Boolean)value).booleanValue();
    }
}
