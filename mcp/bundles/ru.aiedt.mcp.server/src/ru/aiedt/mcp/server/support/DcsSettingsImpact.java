/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

/**
 * Lists the settings that still point at a composition element about to be removed.
 * <p>
 * The walk reads every settings variant on the schema, not only the first one, and the selection,
 * order, filter, structure, conditional appearance, data parameters (their entries and the values
 * they hold) and user fields of each. It also reads the schema's dataset links, which belong to no
 * variant: their hits are stamped with the pseudo-variant {@code schema}. It does not create
 * a variant, does not change a setting and does not record the settings as written: a removal that
 * asks for the list is still only a removal.
 * </p>
 * <p>
 * A stored field path matches an identifier when the two are equal ignoring case, or when the
 * stored path continues the identifier by whole segments ({@code Sales.Amount.Code} continues
 * {@code Sales.Amount}). A parameter matches a {@code DataCompositionParameter} of that name, or a
 * field path whose last step is the name and whose previous step is {@code ПараметрыДанных} or
 * {@code DataParameters}. An expression (a user field's detail expression, a link condition or
 * start expression) matches when it mentions an identifier as a whole word ignoring case.
 * </p>
 */
public final class DcsSettingsImpact
{
    /** Section of a hit that lives in the variant's selected fields. */
    static final String SELECTION = "selection"; //$NON-NLS-1$

    /** Section of a hit that lives in the variant's order. */
    static final String ORDER = "order"; //$NON-NLS-1$

    /** Section of a hit that lives in a filter. */
    static final String FILTER = "filter"; //$NON-NLS-1$

    /** Section of a hit that lives in the report structure, including a nested group. */
    static final String STRUCTURE = "structure"; //$NON-NLS-1$

    /** Section of a hit that lives in the variant's conditional appearance. */
    static final String CONDITIONAL_APPEARANCE = "conditionalAppearance"; //$NON-NLS-1$

    /** Section of a hit that lives in the variant's data parameters. */
    static final String DATA_PARAMETERS = "dataParameters"; //$NON-NLS-1$

    /** Section of a hit that lives in the variant's user fields. */
    static final String USER_FIELDS = "userFields"; //$NON-NLS-1$

    /** Section of a hit that lives in the schema's dataset links. */
    static final String DATA_SET_LINKS = "dataSetLinks"; //$NON-NLS-1$

    /** The pseudo-variant a dataset-link hit is stamped with: links belong to no variant. */
    static final String SCHEMA_VARIANT = "schema"; //$NON-NLS-1$

    /** The step that names a data-parameter field in a Russian schema. */
    private static final String PARAMETERS_RU = "ПараметрыДанных"; //$NON-NLS-1$

    /** The same step in an English schema. */
    private static final String PARAMETERS_EN = "DataParameters"; //$NON-NLS-1$

    /**
     * No instances: the walk is a function of the schema and the identifiers.
     */
    private DcsSettingsImpact()
    {
    }

    /**
     * Collects every settings reference to the given field paths and parameter names.
     *
     * @param schema the composition schema whose variants are read, or <code>null</code>
     * @param fieldPaths data paths of the element being removed; a dataset contributes every
     *            field. Compared ignoring case, and a longer stored path matches when it extends
     *            one of these by whole segments
     * @param parameterNames names of the parameters being removed, or an empty collection
     * @return the hits, in walk order, each with {@code variant}, {@code section}, {@code path}
     *         and {@code item}; empty when nothing matches or there is nothing to match
     */
    public static List<Map<String, Object>> collect(EObject schema, Collection<String> fieldPaths,
        Collection<String> parameterNames)
    {
        return collect(schema, fieldPaths, parameterNames, null);
    }

    /**
     * Collects every settings reference to the given field paths, parameter names and dataset
     * names.
     *
     * @param schema the composition schema whose variants are read, or <code>null</code>
     * @param fieldPaths data paths of the element being removed
     * @param parameterNames names of the parameters being removed, or an empty collection
     * @param dataSetNames names of the datasets being removed; a link endpoint naming one of
     *            them is a reference. Possibly <code>null</code>
     * @return the hits, in walk order
     */
    public static List<Map<String, Object>> collect(EObject schema, Collection<String> fieldPaths,
        Collection<String> parameterNames, Collection<String> dataSetNames)
    {
        Probe probe = new Probe(fieldPaths, parameterNames, dataSetNames);
        if (schema == null || (probe.fieldPaths.isEmpty() && probe.parameterNames.isEmpty()
            && probe.dataSetNames.isEmpty()))
        {
            return probe.found;
        }
        readDataSetLinks(schema, probe);
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            return probe.found;
        }
        for (EObject variant : variants)
        {
            Object name = read(variant, "getName"); //$NON-NLS-1$
            String variantName = name == null ? "" : name.toString(); //$NON-NLS-1$
            Object settings = read(variant, "getSettings"); //$NON-NLS-1$
            readSettings(settings, variantName, false, "", probe); //$NON-NLS-1$
            readUserFields(settings, variantName, probe);
        }
        return probe.found;
    }

    /**
     * Reads the schema's dataset links, which belong to no variant.
     * <p>
     * A link is a reference to what was removed when one of its dataset names is a removed
     * dataset, when its source or destination, condition or start expression mentions the removed
     * identifier, or when the parameter it passes is a removed parameter. A dataset name matches
     * only a removed dataset, and an expression slot is read as an expression, not as one path.
     * </p>
     *
     * @param schema the schema whose links are read
     * @param probe the identifiers and the hits collected so far
     */
    private static void readDataSetLinks(EObject schema, Probe probe)
    {
        EList<EObject> links = BmDcsHelper.getEObjectList(schema, "getDataSetLinks"); //$NON-NLS-1$
        if (links == null)
        {
            return;
        }
        for (int i = 0; i < links.size(); i++)
        {
            String here = DATA_SET_LINKS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            considerDataSetName(read(links.get(i), "getSourceDataSet"), here + ".sourceDataSet", //$NON-NLS-1$ //$NON-NLS-2$
                probe);
            considerDataSetName(read(links.get(i), "getDestinationDataSet"), //$NON-NLS-1$
                here + ".destinationDataSet", probe); //$NON-NLS-1$
            considerParameterName(read(links.get(i), "getParameter"), here + ".parameter", probe); //$NON-NLS-1$ //$NON-NLS-2$
            considerLinkExpression(read(links.get(i), "getSourceExpression"), here + ".sourceExpression", //$NON-NLS-1$ //$NON-NLS-2$
                probe);
            considerLinkExpression(read(links.get(i), "getDestinationExpression"), //$NON-NLS-1$
                here + ".destinationExpression", probe); //$NON-NLS-1$
            considerLinkExpression(read(links.get(i), "getLinkConditionExpression"), //$NON-NLS-1$
                here + ".linkConditionExpression", probe); //$NON-NLS-1$
            considerLinkExpression(read(links.get(i), "getStartExpression"), //$NON-NLS-1$
                here + ".startExpression", probe); //$NON-NLS-1$
        }
    }

    /**
     * Records a link endpoint that names a removed dataset.
     * <p>
     * A dataset name matches only a removed dataset of that name: a removed field or parameter
     * that happens to share the name does not make the link a reference to it.
     * </p>
     *
     * @param value the stored name, or null
     * @param path where the name sits
     * @param probe the identifiers and the hits
     */
    private static void considerDataSetName(Object value, String path, Probe probe)
    {
        String text = textOf(value);
        if (text != null && probe.dataSetMatches(text))
        {
            probe.add(SCHEMA_VARIANT, DATA_SET_LINKS, path, text);
        }
    }

    /**
     * Records a link property that names a removed parameter.
     *
     * @param value the stored name, or null
     * @param path where the name sits
     * @param probe the identifiers and the hits
     */
    private static void considerParameterName(Object value, String path, Probe probe)
    {
        String text = textOf(value);
        if (text != null && probe.parameterMatches(text))
        {
            probe.add(SCHEMA_VARIANT, DATA_SET_LINKS, path, text);
        }
    }

    /**
     * Records a link expression that mentions one of the identifiers.
     *
     * @param value the stored expression, or null
     * @param path where the expression sits
     * @param probe the identifiers and the hits
     */
    private static void considerLinkExpression(Object value, String path, Probe probe)
    {
        String text = textOf(value);
        if (text != null && probe.expressionMentions(text))
        {
            probe.add(SCHEMA_VARIANT, DATA_SET_LINKS, path, text);
        }
    }

    /**
     * Reads the variant's user fields.
     * <p>
     * A user field is a reference to what was removed when its own data path is the removed
     * path, when its detail or total expression mentions the removed identifier, or when the
     * filter of one of its case variants selects by the removed identifier - the expression is
     * what the user field computes from, so a field gone from the dataset breaks it.
     * </p>
     *
     * @param settings the variant's settings, possibly <code>null</code>
     * @param variant the variant name to stamp on every hit
     * @param probe the identifiers and the hits collected so far
     */
    private static void readUserFields(Object settings, String variant, Probe probe)
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(read(settings, "getUserFields"), //$NON-NLS-1$
            "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return;
        }
        for (int i = 0; i < items.size(); i++)
        {
            EObject item = items.get(i);
            String dataPath = textOf(read(item, "getDataPath")); //$NON-NLS-1$
            if (dataPath == null || dataPath.isEmpty())
            {
                continue;
            }
            String here = USER_FIELDS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            if (probe.fieldMatches(dataPath))
            {
                probe.add(variant, USER_FIELDS, here, dataPath);
            }
            String detail = textOf(read(item, "getDetailExpression")); //$NON-NLS-1$
            if (detail != null && probe.expressionMentions(detail))
            {
                probe.add(variant, USER_FIELDS, here + ".detailExpression", dataPath); //$NON-NLS-1$
            }
            String total = textOf(read(item, "getTotalExpression")); //$NON-NLS-1$
            if (total != null && probe.expressionMentions(total))
            {
                probe.add(variant, USER_FIELDS, here + ".totalExpression", dataPath); //$NON-NLS-1$
            }
            EList<EObject> variants = BmDcsHelper.getEObjectList(
                read(item, "getVariants"), "getItems"); //$NON-NLS-1$ //$NON-NLS-2$
            if (variants == null)
            {
                continue;
            }
            for (int c = 0; c < variants.size(); c++)
            {
                readFilter(read(variants.get(c), "getFilter"), variant, USER_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
                    here + ".variants[" + c + "].filter", probe); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Reads one settings object or one structure item.
     *
     * @param node the settings, group, table, chart or nested group, possibly <code>null</code>
     * @param variant the variant name to stamp on every hit
     * @param nested whether {@code node} sits inside the report structure, so its own selection
     *            and filter are reported under {@code structure} rather than at the root
     * @param path the path of {@code node} itself, empty at the root of a variant
     * @param probe the identifiers and the hits collected so far
     */
    private static void readSettings(Object node, String variant, boolean nested, String path,
        Probe probe)
    {
        if (node == null)
        {
            return;
        }
        readFieldList(read(node, "getSelection"), variant, section(nested, SELECTION), //$NON-NLS-1$
            join(path, SELECTION), probe);
        readFieldList(read(node, "getOrder"), variant, section(nested, ORDER), //$NON-NLS-1$
            join(path, ORDER), probe);
        readFilter(read(node, "getFilter"), variant, section(nested, FILTER), join(path, FILTER), //$NON-NLS-1$
            probe);
        readAppearance(node, variant, nested, path, probe);
        readDataParameters(node, variant, nested, path, probe);
        if (hasGetter(node, "getGroupFields")) //$NON-NLS-1$
        {
            readFieldList(read(node, "getGroupFields"), variant, STRUCTURE, //$NON-NLS-1$
                join(path, "groupFields"), probe); //$NON-NLS-1$
        }
        if (hasGetter(node, "getRows")) //$NON-NLS-1$
        {
            readStructure(BmDcsHelper.getEObjectList(node, "getRows"), variant, //$NON-NLS-1$
                join(path, "rows"), probe); //$NON-NLS-1$
            readStructure(BmDcsHelper.getEObjectList(node, "getColumns"), variant, //$NON-NLS-1$
                join(path, "columns"), probe); //$NON-NLS-1$
        }
        if (hasGetter(node, "getSeries")) //$NON-NLS-1$
        {
            readStructure(BmDcsHelper.getEObjectList(node, "getSeries"), variant, //$NON-NLS-1$
                join(path, "series"), probe); //$NON-NLS-1$
            readStructure(BmDcsHelper.getEObjectList(node, "getPoints"), variant, //$NON-NLS-1$
                join(path, "points"), probe); //$NON-NLS-1$
        }
        // A table and a chart hold their groups in rows, columns, series and points. A settings
        // object and a group hold theirs in items. Walking items on a table would look for a
        // collection it does not have; skipping items on a group would miss the groups inside it.
        if (hasGetter(node, "getItems") && !hasGetter(node, "getRows") //$NON-NLS-1$ //$NON-NLS-2$
            && !hasGetter(node, "getSeries")) //$NON-NLS-1$
        {
            String child = nested ? join(path, "items") : STRUCTURE; //$NON-NLS-1$
            readStructure(BmDcsHelper.getEObjectList(node, "getItems"), variant, child, probe); //$NON-NLS-1$
        }
    }

    /**
     * Reads selected fields, order items, group fields or appearance fields.
     * <p>
     * A group of selected fields carries its own field and a nested list. An automatic item carries
     * neither, and is skipped.
     * </p>
     *
     * @param container the list holder, possibly <code>null</code>
     * @param variant the variant name
     * @param section the section stamped on a hit
     * @param path the path of the list, without the item index
     * @param probe the identifiers and the hits
     */
    private static void readFieldList(Object container, String variant, String section, String path,
        Probe probe)
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return;
        }
        for (int i = 0; i < items.size(); i++)
        {
            EObject item = items.get(i);
            String here = path + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            consider(read(item, "getField"), variant, section, here, probe); //$NON-NLS-1$
            if (hasGetter(item, "getItems")) //$NON-NLS-1$
            {
                readFieldList(item, variant, section, here + ".items", probe); //$NON-NLS-1$
            }
        }
    }

    /**
     * Reads a filter, including a group of filter items nested inside it.
     *
     * @param container the filter or a filter group, possibly <code>null</code>
     * @param variant the variant name
     * @param section the section stamped on a hit
     * @param path the path of the list, without the item index
     * @param probe the identifiers and the hits
     */
    private static void readFilter(Object container, String variant, String section, String path,
        Probe probe)
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return;
        }
        for (int i = 0; i < items.size(); i++)
        {
            EObject item = items.get(i);
            String here = path + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            // A group holds items and has no comparison. Told apart by the feature, the same way
            // the completeness check does: a missing method would look the same as an empty left.
            if (item.eClass().getEStructuralFeature("right") == null) //$NON-NLS-1$
            {
                readFilter(item, variant, section, here + ".items", probe); //$NON-NLS-1$
                continue;
            }
            consider(read(item, "getLeft"), variant, section, here, probe); //$NON-NLS-1$
            EList<EObject> right = BmDcsHelper.getEObjectList(item, "getRight"); //$NON-NLS-1$
            if (right == null)
            {
                continue;
            }
            for (int r = 0; r < right.size(); r++)
            {
                consider(right.get(r), variant, section, here + ".right[" + r + "]", probe); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Reads the selection and the filter of each conditional-appearance item.
     *
     * @param node the settings or structure item that may carry an appearance
     * @param variant the variant name
     * @param nested whether the appearance belongs to a structure item
     * @param path the path of {@code node}
     * @param probe the identifiers and the hits
     */
    private static void readAppearance(Object node, String variant, boolean nested, String path,
        Probe probe)
    {
        Object container = read(node, "getConditionalAppearance"); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return;
        }
        String section = section(nested, CONDITIONAL_APPEARANCE);
        String base = join(path, CONDITIONAL_APPEARANCE);
        for (int i = 0; i < items.size(); i++)
        {
            EObject item = items.get(i);
            String here = base + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            readFieldList(read(item, "getSelection"), variant, section, here + "." + SELECTION, //$NON-NLS-1$ //$NON-NLS-2$
                probe);
            readFilter(read(item, "getFilter"), variant, section, here + "." + FILTER, probe); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Reads data-parameter values, including values nested under another parameter.
     *
     * @param node the settings object that may carry data parameters
     * @param variant the variant name
     * @param nested whether the parameters belong to a structure item
     * @param path the path of {@code node}
     * @param probe the identifiers and the hits
     */
    private static void readDataParameters(Object node, String variant, boolean nested, String path,
        Probe probe)
    {
        Object container = read(node, "getDataParameters"); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return;
        }
        String section = section(nested, DATA_PARAMETERS);
        String base = join(path, DATA_PARAMETERS);
        for (int i = 0; i < items.size(); i++)
        {
            EObject item = items.get(i);
            String here = base + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            consider(read(item, "getParameter"), variant, section, here, probe); //$NON-NLS-1$
            readParameterValues(item, variant, section, here, probe);
            EList<EObject> nestedValues = BmDcsHelper.getEObjectList(item,
                "getNestedParameterValues"); //$NON-NLS-1$
            if (nestedValues == null)
            {
                continue;
            }
            for (int n = 0; n < nestedValues.size(); n++)
            {
                String nestedHere = here + ".nested[" + n + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                consider(read(nestedValues.get(n), "getParameter"), variant, section, nestedHere, //$NON-NLS-1$
                    probe);
                readParameterValues(nestedValues.get(n), variant, section, nestedHere, probe);
            }
        }
    }

    /**
     * Reads the values one data-parameter entry holds.
     * <p>
     * A value that is the removed field path is a reference to it: the entry passes the field
     * where a literal was expected, and the field's removal leaves the parameter holding nothing.
     * </p>
     *
     * @param entry the data-parameter entry, possibly <code>null</code>
     * @param variant the variant name to stamp on every hit
     * @param section the section stamped on a hit
     * @param path the path of the entry, without the value index
     * @param probe the identifiers and the hits
     */
    private static void readParameterValues(Object entry, String variant, String section,
        String path, Probe probe)
    {
        EList<EObject> values = BmDcsHelper.getEObjectList(entry, "getValues"); //$NON-NLS-1$
        if (values == null)
        {
            return;
        }
        for (int v = 0; v < values.size(); v++)
        {
            EObject value = values.get(v);
            if (!"DataCompositionField".equals(value.eClass().getName())) //$NON-NLS-1$
            {
                continue;
            }
            String text = textOf(read(value, "getValue")); //$NON-NLS-1$
            if (text != null && probe.fieldMatches(text))
            {
                probe.add(variant, section, path + ".values[" + v + "]", text); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Reads each structure item as a settings object of its own.
     *
     * @param items the groups, tables or charts, possibly <code>null</code>
     * @param variant the variant name
     * @param path the path of the list, without the item index
     * @param probe the identifiers and the hits
     */
    private static void readStructure(EList<EObject> items, String variant, String path, Probe probe)
    {
        if (items == null)
        {
            return;
        }
        for (int i = 0; i < items.size(); i++)
        {
            readSettings(items.get(i), variant, true, path + "[" + i + "]", probe); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Records a field or a parameter when the value is one of the identifiers.
     *
     * @param value a {@code DataCompositionField} or {@code DataCompositionParameter}, or anything
     *            else, which is ignored
     * @param variant the variant name
     * @param section the section of the hit
     * @param path where the value sits
     * @param probe the identifiers and the hits
     */
    private static void consider(Object value, String variant, String section, String path,
        Probe probe)
    {
        if (!(value instanceof EObject))
        {
            return;
        }
        String kind = ((EObject)value).eClass().getName();
        String text = textOf(read(value, "getValue")); //$NON-NLS-1$
        if (text == null || text.isEmpty())
        {
            return;
        }
        if ("DataCompositionField".equals(kind) && probe.fieldMatches(text)) //$NON-NLS-1$
        {
            probe.add(variant, section, path, text);
        }
        boolean parameter = "DataCompositionParameter".equals(kind) && probe.parameterMatches(text); //$NON-NLS-1$
        if (!parameter && "DataCompositionField".equals(kind)) //$NON-NLS-1$
        {
            parameter = probe.parameterPathMatches(text);
        }
        if (parameter)
        {
            probe.add(variant, section, path, text);
        }
    }

    /**
     * The section a container is reported under.
     *
     * @param nested whether the container sits inside the report structure
     * @param own the section it has at the root of a variant
     * @return {@code structure} when nested, otherwise {@code own}
     */
    private static String section(boolean nested, String own)
    {
        return nested ? STRUCTURE : own;
    }

    /**
     * Joins a path step onto the path so far.
     *
     * @param prefix the path so far, empty at the root
     * @param child the next step
     * @return the joined path
     */
    private static String join(String prefix, String child)
    {
        if (prefix == null || prefix.isEmpty())
        {
            return child;
        }
        return prefix + "." + child; //$NON-NLS-1$
    }

    /**
     * Whether the object offers a no-argument method of this name.
     * <p>
     * Settings, groups, tables and charts share some children and not others. The missing getter is
     * how one shape is told from the next.
     * </p>
     *
     * @param target the object, possibly <code>null</code>
     * @param name the getter
     * @return <code>true</code> when the getter is there
     */
    private static boolean hasGetter(Object target, String name)
    {
        if (target == null)
        {
            return false;
        }
        try
        {
            target.getClass().getMethod(name);
            return true;
        }
        catch (NoSuchMethodException absent)
        {
            // A different settings shape. The walk uses the absence as the distinction and reads
            // the children that shape does have.
            return false;
        }
    }

    /**
     * Calls a no-argument getter, or answers nothing when the object has no such getter.
     *
     * @param target the object, possibly <code>null</code>
     * @param name the getter
     * @return the value, or <code>null</code> when there is nothing to read
     */
    private static Object read(Object target, String name)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            return target.getClass().getMethod(name).invoke(target);
        }
        catch (ReflectiveOperationException absent)
        {
            // NoSuchMethodException: this shape has no such child. IllegalAccessException and
            // InvocationTargetException: the child is not readable from here. Either way the walk
            // has nothing to match at this step.
            return null;
        }
    }

    /**
     * The text a value carrier holds.
     *
     * @param value whatever {@code getValue} returned
     * @return the text, or <code>null</code>
     */
    private static String textOf(Object value)
    {
        return value == null ? null : value.toString();
    }

    /**
     * The identifiers of one removal and the hits found for them.
     */
    private static final class Probe
    {
        /** Field paths, in the order they were given, blanks dropped. */
        private final List<String> fieldPaths = new ArrayList<>();

        /** Parameter names, in the order they were given, blanks dropped. */
        private final List<String> parameterNames = new ArrayList<>();

        /** Dataset names, in the order they were given, blanks dropped. */
        private final List<String> dataSetNames = new ArrayList<>();

        /** Hits, in the order the walk met them. */
        private final List<Map<String, Object>> found = new ArrayList<>();

        /**
         * Keeps the identifiers that carry text.
         *
         * @param fields the field paths, possibly <code>null</code>
         * @param parameters the parameter names, possibly <code>null</code>
         */
        Probe(Collection<String> fields, Collection<String> parameters,
            Collection<String> dataSets)
        {
            copy(fields, this.fieldPaths);
            copy(parameters, this.parameterNames);
            copy(dataSets, this.dataSetNames);
        }

        /**
         * Whether a stored dataset name is one of the removed datasets.
         *
         * @param stored the name the link holds
         * @return <code>true</code> when it names a removed dataset
         */
        boolean dataSetMatches(String stored)
        {
            return contains(this.dataSetNames, stored);
        }

        /**
         * Copies the texts that are not blank, keeping the first spelling of each.
         *
         * @param source the texts, possibly <code>null</code>
         * @param target where they are kept
         */
        private static void copy(Collection<String> source, List<String> target)
        {
            if (source == null)
            {
                return;
            }
            for (String one : source)
            {
                if (one == null || one.isEmpty() || contains(target, one))
                {
                    continue;
                }
                target.add(one);
            }
        }

        /**
         * Whether a stored field path is one of the field identifiers, or extends one of them.
         *
         * @param stored the path the settings hold
         * @return <code>true</code> when it refers to a removed field
         */
        boolean fieldMatches(String stored)
        {
            for (String id : this.fieldPaths)
            {
                if (stored.equalsIgnoreCase(id))
                {
                    return true;
                }
                if (stored.length() > id.length() && stored.charAt(id.length()) == '.'
                    && stored.regionMatches(true, 0, id, 0, id.length()))
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether an expression mentions any of the identifiers.
         * <p>
         * A mention is a whole word: the character on each side of it is not a letter, a digit or
         * an underscore. Matching like this keeps {@code ИтогСумма} apart from {@code Сумма},
         * which an operator reading the report would not call a reference to {@code Сумма}.
         * </p>
         *
         * @param expression the expression text
         * @return <code>true</code> when one of the identifiers is mentioned
         */
        boolean expressionMentions(String expression)
        {
            for (String id : this.fieldPaths)
            {
                if (mentionsWord(expression, id))
                {
                    return true;
                }
            }
            for (String id : this.parameterNames)
            {
                if (mentionsWord(expression, id))
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the text holds the identifier as a whole word, ignoring case.
         *
         * @param text the text to read
         * @param id the identifier to look for
         * @return <code>true</code> when it is mentioned
         */
        private static boolean mentionsWord(String text, String id)
        {
            if (text.length() < id.length())
            {
                return false;
            }
            int limit = text.length() - id.length();
            for (int i = 0; i <= limit; i++)
            {
                if (!text.regionMatches(true, i, id, 0, id.length()))
                {
                    continue;
                }
                int end = i + id.length();
                boolean freeBefore = i == 0 || !isNameChar(text.charAt(i - 1));
                boolean freeAfter = end == text.length() || !isNameChar(text.charAt(end));
                if (freeBefore && freeAfter)
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the character can be part of an identifier's word.
         *
         * @param c the character
         * @return <code>true</code> when it is a letter, a digit or an underscore
         */
        private static boolean isNameChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        /**
         * Whether a parameter carrier holds one of the removed names.
         *
         * @param stored the parameter name the settings hold
         * @return <code>true</code> when it is one of the names
         */
        boolean parameterMatches(String stored)
        {
            return contains(this.parameterNames, stored);
        }

        /**
         * Whether a field path addresses one of the removed parameters.
         * <p>
         * Only a path whose previous step is the data-parameters step. A field that merely shares
         * the parameter's name is a different field.
         * </p>
         *
         * @param stored the field path
         * @return <code>true</code> when the path is a data parameter of a removed name
         */
        boolean parameterPathMatches(String stored)
        {
            int dot = stored.lastIndexOf('.');
            if (dot <= 0 || dot >= stored.length() - 1)
            {
                return false;
            }
            int previous = stored.lastIndexOf('.', dot - 1);
            String container = stored.substring(previous + 1, dot);
            if (!container.equalsIgnoreCase(PARAMETERS_RU)
                && !container.equalsIgnoreCase(PARAMETERS_EN))
            {
                return false;
            }
            return contains(this.parameterNames, stored.substring(dot + 1));
        }

        /**
         * Appends one hit.
         *
         * @param variant the variant name
         * @param section the section
         * @param path where the reference sits
         * @param item the path or parameter name the settings hold
         */
        void add(String variant, String section, String path, String item)
        {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("variant", variant); //$NON-NLS-1$
            hit.put("section", section); //$NON-NLS-1$
            hit.put("path", path); //$NON-NLS-1$
            hit.put("item", item); //$NON-NLS-1$
            this.found.add(hit);
        }

        /**
         * Whether the list already holds the text, ignoring case.
         *
         * @param texts the texts already kept
         * @param candidate the text to look for
         * @return <code>true</code> when it is there
         */
        private static boolean contains(List<String> texts, String candidate)
        {
            for (String one : texts)
            {
                if (one.equalsIgnoreCase(candidate))
                {
                    return true;
                }
            }
            return false;
        }
    }
}
