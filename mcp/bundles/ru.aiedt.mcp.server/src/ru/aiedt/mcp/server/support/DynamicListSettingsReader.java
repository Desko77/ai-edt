/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.List;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Reads a dynamic list's composition settings back.
 * <p>
 * Writing them was reachable before reading them was, which left one way to check what a write did:
 * open the form file and read the XML. That is not something an agent should have to do, and it is
 * not something it can do when the change is still in the model and not yet on disk.
 * </p>
 * <p>
 * Flat values are named the way the write operations name their arguments - a filter item reports
 * {@code field}, {@code comparisonType} and {@code value} because {@code add_filter} takes those.
 * Containers keep the name the model gives them. A shared label for two containers makes them
 * collide, and a JSON member keyed by name replaces rather than merges, so the collision is silent:
 * naming both a grouping's own fields and its selection {@code fields} lost the grouping field
 * entirely.
 * </p>
 * <p>
 * Written against the meta-model, and careful about the difference between things that are easy to
 * conflate: this list has none of that setting, this model has no such section, and this container
 * is shaped in a way this reader does not know. Only the first is an empty array. An enumeration is
 * rendered by its literal name, never by {@code toString}.
 * </p>
 */
public final class DynamicListSettingsReader
{
    /**
     * The sections read, and what each is called in the answer.
     * <p>
     * One rename, and it earns itself: the settings call their structure {@code items}, which says
     * nothing beside three other item lists. Every section is read, including the ones a caller
     * rarely asks for - answering {@code settingsRead} while quietly leaving four of them out would
     * be a claim about settings nobody looked at.
     * </p>
     */
    private static final String[][] SECTIONS = {
        {"items", "structure"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"order", "order"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"filter", "filter"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"conditionalAppearance", "conditionalAppearance"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"selection", "selection"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"userFields", "userFields"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"dataParameters", "dataParameters"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"outputParameters", "outputParameters"}}; //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * The flat features an item is reported by, and the name each is reported under.
     * <p>
     * A pair per row: the feature as the model names it, then the word the write operations use.
     * {@code left} and {@code right} are what a filter item calls the field and the value it is
     * compared with; {@code add_filter} calls them {@code field} and {@code value}.
     * </p>
     */
    private static final String[][] REPORTED = {
        {"field", "field"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"left", "field"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"right", "value"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"orderType", "direction"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"comparisonType", "comparisonType"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"groupType", "groupType"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"parameter", "parameter"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"values", "value"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"dataPath", "dataPath"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"detailExpression", "detailExpression"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"expression", "expression"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"title", "title"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"presentation", "presentation"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"userSettingPresentation", "userSettingPresentation"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"periodAdditionType", "periodAdditionType"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"periodAdditionBegin", "periodAdditionBegin"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"periodAdditionEnd", "periodAdditionEnd"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"userSettingID", "userSettingID"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"name", "name"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"use", "use"}, //$NON-NLS-1$ //$NON-NLS-2$
        {"viewMode", "viewMode"}}; //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Child containers an item is followed into, each reported under its own name.
     * <p>
     * A grouping keeps the field it groups by in its own field list; a conditional appearance keeps
     * the fields it decorates, the condition it decorates them on and the formatting itself in
     * three separate containers; a filter group keeps its predicates in its own item list; a
     * grouping carries an order and an appearance of its own. Reporting the item without them says
     * an appearance exists and nothing about what it does, which cannot check a write.
     * </p>
     */
    private static final String[] FOLLOWED = {"groupFields", "selection", "filter", "order", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "conditionalAppearance", "appearance", "items"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * How many levels of children an item is followed through, so a cycle in the model cannot spin
     * here. Counted in descents from a section's own item: at zero the children are counted but not
     * described, and the item says {@code truncated}.
     */
    private static final int DEPTH = 3;

    private DynamicListSettingsReader()
    {
        // utility
    }

    /**
     * Reads what a list's settings hold.
     *
     * @param settings the settings object, or {@code null} when the list has none.
     * @return an object with a section per kind of setting, or one saying why nothing was read
     */
    public static JsonObject read(Object settings)
    {
        return read(settings, null);
    }

    /**
     * Reads what a list's settings hold, with a caller-supplied reason for there being none.
     *
     * @param settings the settings object, or {@code null}.
     * @param whyAbsent what to say when there are none; {@code null} for the ordinary reason.
     * @return an object with a section per kind of setting, or one saying why nothing was read
     */
    public static JsonObject read(Object settings, String whyAbsent)
    {
        JsonObject read = new JsonObject();
        if (!(settings instanceof EObject))
        {
            read.addProperty("settingsRead", false); //$NON-NLS-1$
            read.addProperty("why", why(settings, whyAbsent)); //$NON-NLS-1$
            return read;
        }
        EObject held = (EObject)settings;
        if (held.eIsProxy())
        {
            // The settings are a top object of their own and are loaded on demand. An unresolved
            // proxy is not an empty setting: reporting it as empty would say the list is unsorted
            // and unfiltered when nobody has looked.
            read.addProperty("settingsRead", false); //$NON-NLS-1$
            read.addProperty("why", "the settings are not loaded in this model"); //$NON-NLS-1$ //$NON-NLS-2$
            return read;
        }
        read.addProperty("settingsRead", true); //$NON-NLS-1$
        JsonObject notRead = new JsonObject();
        for (String[] section : SECTIONS)
        {
            if (held.eClass().getEStructuralFeature(section[0]) == null)
            {
                // This model has no such section. Absent from the answer, because an empty array
                // would say the list has none of that when the question cannot be asked here.
                continue;
            }
            Object container = valueOf(held, section[0]);
            if (container == null)
            {
                // Unset, which fresh settings leave it. That is an answer: no order, no filter.
                read.add(section[1], new JsonArray());
                continue;
            }
            List<?> items = listOf(container);
            if (items == null)
            {
                notRead.addProperty(section[1],
                    "the section holds no item list this reader recognises"); //$NON-NLS-1$
                continue;
            }
            read.add(section[1], describeAll(items, DEPTH));
        }
        if (notRead.size() > 0)
        {
            read.add("sectionsNotRead", notRead); //$NON-NLS-1$
        }
        return read;
    }

    private static String why(Object settings, String whyAbsent)
    {
        if (settings != null)
        {
            return "what the list holds is not a model object"; //$NON-NLS-1$
        }
        return whyAbsent != null ? whyAbsent : "the list has no settings object"; //$NON-NLS-1$
    }

    /**
     * Describes every item of a list.
     *
     * @param items the items.
     * @param depth how much further to follow child containers.
     * @return one object per item that is a model object
     */
    private static JsonArray describeAll(List<?> items, int depth)
    {
        JsonArray described = new JsonArray();
        for (Object item : items)
        {
            if (item instanceof EObject)
            {
                described.add(describe((EObject)item, depth));
            }
        }
        return described;
    }

    /**
     * What a container holds, whether it is the list itself or an object around one.
     *
     * @param container the section or child value.
     * @return the items, or {@code null} when this is not a shape with an item list
     */
    private static List<?> listOf(Object container)
    {
        if (container instanceof List)
        {
            return (List<?>)container;
        }
        if (container instanceof EObject)
        {
            EObject held = (EObject)container;
            EStructuralFeature items = held.eClass().getEStructuralFeature("items"); //$NON-NLS-1$
            if (items == null)
            {
                return null;
            }
            Object inner = held.eGet(items);
            if (inner instanceof List)
            {
                return (List<?>)inner;
            }
        }
        return null;
    }

    /**
     * One item, by the words the write operations use, followed into what it contains.
     *
     * @param item the item.
     * @param depth how much further to follow child containers.
     * @return what it holds
     */
    private static JsonObject describe(EObject item, int depth)
    {
        JsonObject described = new JsonObject();
        // The kind is always reported: one settings item list holds groups, tables and charts, and
        // a caller reading a structure has no other way to tell which is which.
        described.addProperty("kind", item.eClass().getName()); //$NON-NLS-1$
        for (String[] pair : REPORTED)
        {
            Object value = valueOf(item, pair[0]);
            if (value == null || described.has(pair[1]))
            {
                continue;
            }
            if (value instanceof Boolean)
            {
                described.addProperty(pair[1], (Boolean)value);
                continue;
            }
            if (value instanceof List)
            {
                // An array, not a joined string. Joined, one value holding a comma reads exactly
                // like two values, and a filter written with either cannot be told from the other.
                JsonArray many = new JsonArray();
                for (Object each : (List<?>)value)
                {
                    String rendered = asText(each);
                    if (rendered != null)
                    {
                        many.add(rendered);
                    }
                }
                if (many.size() > 0)
                {
                    described.add(pair[1], many);
                }
                continue;
            }
            String text = asText(value);
            if (text != null && !text.isEmpty())
            {
                described.addProperty(pair[1], text);
            }
        }
        if (described.has("groupType") //$NON-NLS-1$
            && item.eClass().getEStructuralFeature("field") != null) //$NON-NLS-1$
        {
            // A grouping field carries a field and a group type, and add_grouping calls the second
            // groupingType. A filter group carries the type alone, and add_settings_filter_group
            // calls it groupType. Told apart by the model rather than by a class name.
            described.add("groupingType", described.remove("groupType")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        follow(item, depth, described);
        return described;
    }

    /**
     * Follows an item into what it contains, each container under its own name.
     *
     * @param item the item.
     * @param depth how much further to go.
     * @param described what is being built.
     */
    private static void follow(EObject item, int depth, JsonObject described)
    {
        JsonObject notRead = new JsonObject();
        boolean anyChild = false;
        for (String child : FOLLOWED)
        {
            if (item.eClass().getEStructuralFeature(child) == null)
            {
                continue;
            }
            Object container = valueOf(item, child);
            if (container == null)
            {
                // The feature is there and holds nothing. An empty array, the same answer the
                // sections give: a group with no local filter is not a group that cannot have one.
                described.add(child, new JsonArray());
                continue;
            }
            List<?> inner = listOf(container);
            if (inner == null)
            {
                // Shaped in a way this reader does not know. Left out silently, it would read as
                // an appearance with nothing configured rather than one nobody could read.
                notRead.addProperty(child,
                    "the container holds no item list this reader recognises"); //$NON-NLS-1$
                continue;
            }
            if (inner.isEmpty())
            {
                described.add(child, new JsonArray());
                continue;
            }
            anyChild = true;
            if (depth > 0)
            {
                described.add(child, describeAll(inner, depth - 1));
            }
        }
        if (depth <= 0 && anyChild)
        {
            // Said outright: a tree deeper than this reader goes is not a tree that ends here.
            described.addProperty("truncated", true); //$NON-NLS-1$
        }
        if (notRead.size() > 0)
        {
            described.add("notRead", notRead); //$NON-NLS-1$
        }
    }

    /**
     * Renders a value by what it is.
     * <p>
     * An enumeration by its literal name: {@code toString} on a model object gives the object's
     * own printout, and that reached a written file once already.
     * </p>
     *
     * @param value the value.
     * @return its text, or {@code null} when it has none worth reporting
     */
    private static String asText(Object value)
    {
        if (value instanceof String)
        {
            return (String)value;
        }
        if (value instanceof Enumerator)
        {
            return ((Enumerator)value).getName();
        }
        if (value instanceof List)
        {
            StringBuilder joined = new StringBuilder();
            for (Object each : (List<?>)value)
            {
                String text = asText(each);
                if (text != null && !text.isEmpty())
                {
                    joined.append(joined.length() == 0 ? "" : ", ").append(text); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            return joined.toString();
        }
        if (value instanceof EObject)
        {
            EObject held = (EObject)value;
            if (held.eIsProxy())
            {
                return null;
            }
            for (String naming : new String[] {"value", "name", "path"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object named = valueOf(held, naming);
                if (named instanceof String && !((String)named).isEmpty())
                {
                    return (String)named;
                }
                if (named instanceof EObject && named != held)
                {
                    // A colour or a font is a wrapper around the object that holds the parts. One
                    // step further in is where they are; stopping at the wrapper reports that a
                    // colour was set and not which.
                    String inner = asText(named);
                    if (inner != null && !inner.isEmpty())
                    {
                        return inner;
                    }
                }
            }
            return contentsOf(held);
        }
        return String.valueOf(value);
    }

    /**
     * What a value object holds, when it names itself by nothing.
     * <p>
     * A colour or a font is a small object of parts. Reporting its class name says a colour was set
     * and not which, which is not enough to check the write that set it.
     * </p>
     *
     * @param held the object.
     * @return its parts as {@code name=value}, or its class name when it has no readable parts
     */
    private static String contentsOf(EObject held)
    {
        StringBuilder parts = new StringBuilder();
        for (EAttribute attribute : held.eClass().getEAllAttributes())
        {
            if (!held.eIsSet(attribute))
            {
                // What was set is what is reported, and the model already knows which is which.
                // Deciding by the text instead threw away two things that were set: zero, which
                // lost black entirely, and the empty string, which a filter can be written with.
                continue;
            }
            Object value = held.eGet(attribute);
            if (value == null)
            {
                continue;
            }
            String text = value instanceof Enumerator ? ((Enumerator)value).getName()
                : String.valueOf(value);
            parts.append(parts.length() == 0 ? "" : ", ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(attribute.getName()).append('=').append(text);
        }
        return parts.length() == 0 ? held.eClass().getName() : parts.toString();
    }

    /**
     * One feature's value, when this runtime's model has that feature.
     *
     * @param held the object.
     * @param feature the feature name.
     * @return the value, or {@code null} when there is no such feature or it is unset
     */
    private static Object valueOf(EObject held, String feature)
    {
        EStructuralFeature found = held.eClass().getEStructuralFeature(feature);
        if (found == null)
        {
            return null;
        }
        return held.eGet(found);
    }
}
