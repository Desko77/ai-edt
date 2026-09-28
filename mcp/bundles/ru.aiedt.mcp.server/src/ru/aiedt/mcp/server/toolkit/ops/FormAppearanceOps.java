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
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Conditional appearance of a managed form in {@code edit_metadata}: add a rule, list the rules,
 * remove a rule.
 * <p>
 * A rule is the conditional-appearance item of the composition model, held by
 * {@code Form.getConditionalAppearance()}: the form items it styles, a condition over the form's
 * data and the appearance. The condition and the appearance are built by the same builder as
 * {@code dcs_workshop add_appearance}.
 * </p>
 */
final class FormAppearanceOps
{
    /**
     * Adds a conditional-appearance rule to a form.
     * <p>
     * {@code itemNames} lists the form items the rule styles, comma-separated; omitted, the rule
     * styles the whole form. A name that is not an item of the form refuses the call before anything
     * is written, and so does a condition or an appearance the builder refuses.
     * </p>
     *
     * @param params projectName, formFqn, itemNames, field, conditionType, conditionValue,
     *            appearance and dryRun
     * @return the answer: the index of the new rule, what it styles and its condition
     */
    String opAddFormAppearanceRule(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemNames = JsonUtils.extractStringArgument(params, "itemNames"); //$NON-NLS-1$
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        String conditionType = JsonUtils.extractStringArgument(params, "conditionType"); //$NON-NLS-1$
        String conditionValue = JsonUtils.extractStringArgument(params, "conditionValue"); //$NON-NLS-1$
        String appearance = JsonUtils.extractStringArgument(params, "appearance"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        DcsWorkshopTool.AppearanceItem built;
        try
        {
            built = new DcsWorkshopTool().newAppearanceItem(field, conditionType, conditionValue,
                appearance);
        }
        catch (MetadataGuards.BlockedGuardException blocked)
        {
            throw blocked;
        }
        catch (RuntimeException refused)
        {
            return ToolResult.error(refused.getMessage() + " Nothing was written.").toJson(); //$NON-NLS-1$
        }
        List<String> names = splitNames(itemNames);
        int[] index = { -1 };
        String outcome = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) -> {
            try
            {
                List<String> missing = new ArrayList<>();
                for (String name : names)
                {
                    if (helper.findItemByName(form, name) == null)
                    {
                        missing.add(name);
                    }
                }
                if (!missing.isEmpty())
                {
                    return "Error: the form has no item " + String.join(", ", missing) //$NON-NLS-1$
                        + ". Nothing was written."; //$NON-NLS-1$
                }
                selectItems(built.item, names);
                index[0] = addToForm(form, built.item);
                return null;
            }
            catch (Exception e)
            {
                return "Error: the rule was not added - " + e.getMessage(); //$NON-NLS-1$
            }
        });
        if (outcome != null && outcome.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(outcome.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        ToolResult answer = ToolResult.success()
            .put("operation", "add_form_appearance_rule") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("index", index[0]) //$NON-NLS-1$
            .put("itemNames", names) //$NON-NLS-1$
            .put("summary", built.describe()); //$NON-NLS-1$
        if (!built.skipped.isEmpty())
        {
            answer.put("styleRefNotSupported", built.skipped); //$NON-NLS-1$
        }
        if (dryRun)
        {
            answer.put("dryRun", true); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * Lists the conditional-appearance rules of a form.
     *
     * @param params projectName and formFqn
     * @return the answer: the rules in their order, see {@link #describe}
     */
    String opListFormAppearanceRules(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        List<List<Map<String, Object>>> read = new ArrayList<>();
        String outcome = helper.executeFormOperation(project, formFqn, (tx, form) -> {
            read.add(describe(form));
            return null;
        });
        if (outcome != null && outcome.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(outcome.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        List<Map<String, Object>> rules = read.isEmpty() ? new ArrayList<>() : read.get(0);
        return ToolResult.success()
            .put("operation", "list_form_appearance_rules") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("count", rules.size()) //$NON-NLS-1$
            .put("rules", rules) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Removes conditional-appearance rules from a form: the one at {@code index}, or every rule
     * whose condition reads {@code field}.
     *
     * @param params projectName, formFqn, index or field, and dryRun
     * @return the answer: the indexes removed
     */
    String opRemoveFormAppearanceRule(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        Integer index = JsonUtils.extractIntegerArgument(params, "index"); //$NON-NLS-1$
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if ((index == null) == (field == null || field.isEmpty()))
        {
            err = err + "Pass either index or field. "; //$NON-NLS-1$
        }
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        List<Integer> removed = new ArrayList<>();
        String outcome = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) -> {
            try
            {
                removed.addAll(removeFromForm(form, index, field));
                return null;
            }
            catch (RuntimeException refused)
            {
                return "Error: " + refused.getMessage(); //$NON-NLS-1$
            }
        });
        if (outcome != null && outcome.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(outcome.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        ToolResult answer = ToolResult.success()
            .put("operation", "remove_form_appearance_rule") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("removed", removed); //$NON-NLS-1$
        if (dryRun)
        {
            answer.put("dryRun", true); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * Splits a comma-separated list of item names.
     *
     * @param csv the names, or <code>null</code>
     * @return the trimmed, non-empty names in their order
     */
    static List<String> splitNames(String csv)
    {
        List<String> names = new ArrayList<>();
        if (csv == null)
        {
            return names;
        }
        for (String part : csv.split(",")) //$NON-NLS-1$
        {
            String name = part.trim();
            if (!name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Makes a rule style the named form items. No names leaves the selection empty: the rule then
     * styles the whole form.
     *
     * @param item the conditional-appearance item
     * @param names the form item names
     * @throws IllegalStateException when the composition model cannot build the selection
     */
    static void selectItems(Object item, List<String> names)
    {
        if (names.isEmpty())
        {
            return;
        }
        Object selection = invoke(item, "getSelection"); //$NON-NLS-1$
        if (selection == null)
        {
            selection = BmDcsHelper.createElement("createDataCompositionAppearanceFields"); //$NON-NLS-1$
            if (selection == null || BmDcsHelper.setProperty(item, "selection", selection) != null) //$NON-NLS-1$
            {
                throw new IllegalStateException("the rule's field list could not be created"); //$NON-NLS-1$
            }
        }
        EList<EObject> fields = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (fields == null)
        {
            throw new IllegalStateException("the rule's field list has no items"); //$NON-NLS-1$
        }
        for (String name : names)
        {
            Object entry = BmDcsHelper.createElement("createDataCompositionAppearanceField"); //$NON-NLS-1$
            Object path = BmDcsHelper.createDataCompositionField(name);
            if (entry == null || path == null || BmDcsHelper.setProperty(entry, "field", path) != null) //$NON-NLS-1$
            {
                throw new IllegalStateException("the field '" + name + "' could not be added to the rule"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            BmDcsHelper.setProperty(entry, "use", Boolean.TRUE); //$NON-NLS-1$
            fields.add((EObject)entry);
        }
    }

    /**
     * Appends a rule to the form's conditional appearance, creating the container when the form has
     * none.
     *
     * @param form the form
     * @param item the rule
     * @return the index of the rule in the form's list
     * @throws IllegalStateException when the container cannot be read or created
     */
    static int addToForm(Object form, Object item)
    {
        Object container = invoke(form, "getConditionalAppearance"); //$NON-NLS-1$
        if (container == null)
        {
            container = BmDcsHelper.createElement("createDataCompositionConditionalAppearance"); //$NON-NLS-1$
            if (container == null
                || BmDcsHelper.setProperty(form, "conditionalAppearance", container) != null) //$NON-NLS-1$
            {
                throw new IllegalStateException("the form's conditional appearance could not be created"); //$NON-NLS-1$
            }
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new IllegalStateException("the form's conditional appearance has no items"); //$NON-NLS-1$
        }
        items.add((EObject)item);
        return items.size() - 1;
    }

    /**
     * Removes rules from the form's conditional appearance.
     *
     * @param form the form
     * @param index the rule to remove, or <code>null</code> to remove by field
     * @param field the data path whose rules are removed, used when {@code index} is <code>null</code>
     * @return the indexes removed, as they were before the removal
     * @throws IllegalArgumentException when the index is out of range or no rule reads the field
     */
    static List<Integer> removeFromForm(Object form, Integer index, String field)
    {
        Object container = invoke(form, "getConditionalAppearance"); //$NON-NLS-1$
        EList<EObject> items = container == null ? null : BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        int size = items == null ? 0 : items.size();
        List<Integer> removed = new ArrayList<>();
        if (index != null)
        {
            if (index.intValue() < 0 || index.intValue() >= size)
            {
                throw new IllegalArgumentException("index " + index + " is out of range: the form has " //$NON-NLS-1$ //$NON-NLS-2$
                    + size + " rules. Nothing was removed."); //$NON-NLS-1$
            }
            items.remove(index.intValue());
            removed.add(index);
            return removed;
        }
        for (int i = size - 1; i >= 0; i--)
        {
            if (conditionFields(items.get(i)).stream().anyMatch(field::equalsIgnoreCase))
            {
                items.remove(i);
                removed.add(0, Integer.valueOf(i));
            }
        }
        if (removed.isEmpty())
        {
            throw new IllegalArgumentException("no rule of the form has a condition on " + field //$NON-NLS-1$
                + ". Nothing was removed."); //$NON-NLS-1$
        }
        return removed;
    }

    /**
     * Describes the conditional-appearance rules of a form.
     * <p>
     * Each rule is {@code index}, {@code use}, {@code itemNames} (the styled items; empty means the
     * whole form), {@code condition} (each comparison as {@code field}, {@code comparisonType},
     * {@code value}) and {@code appearance} (parameter name to value).
     * </p>
     *
     * @param form the form
     * @return the rules in their order; empty when the form has none
     */
    static List<Map<String, Object>> describe(Object form)
    {
        List<Map<String, Object>> rules = new ArrayList<>();
        Object container = invoke(form, "getConditionalAppearance"); //$NON-NLS-1$
        EList<EObject> items = container == null ? null : BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return rules;
        }
        for (int i = 0; i < items.size(); i++)
        {
            EObject item = items.get(i);
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("index", Integer.valueOf(i)); //$NON-NLS-1$
            rule.put("use", invoke(item, "isUse")); //$NON-NLS-1$ //$NON-NLS-2$
            rule.put("itemNames", selectedNames(item)); //$NON-NLS-1$
            rule.put("condition", conditions(item)); //$NON-NLS-1$
            rule.put("appearance", appearance(item)); //$NON-NLS-1$
            rules.add(rule);
        }
        return rules;
    }

    /**
     * @param item a rule
     * @return the names of the form items the rule styles
     */
    private static List<String> selectedNames(Object item)
    {
        List<String> names = new ArrayList<>();
        Object selection = invoke(item, "getSelection"); //$NON-NLS-1$
        EList<EObject> fields = selection == null ? null : BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (fields != null)
        {
            for (EObject entry : fields)
            {
                names.add(fieldPath(invoke(entry, "getField"))); //$NON-NLS-1$
            }
        }
        return names;
    }

    /**
     * @param item a rule
     * @return the fields the comparisons of the rule's condition read
     */
    private static List<String> conditionFields(Object item)
    {
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> comparison : conditions(item))
        {
            Object field = comparison.get("field"); //$NON-NLS-1$
            if (field != null)
            {
                fields.add(field.toString());
            }
        }
        return fields;
    }

    /**
     * @param item a rule
     * @return the comparisons of the rule's condition, each as field, comparisonType and value
     */
    private static List<Map<String, Object>> conditions(Object item)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        Object filter = invoke(item, "getFilter"); //$NON-NLS-1$
        EList<EObject> comparisons = filter == null ? null : BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        if (comparisons == null)
        {
            return out;
        }
        for (EObject comparison : comparisons)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("field", fieldPath(invoke(comparison, "getLeft"))); //$NON-NLS-1$ //$NON-NLS-2$
            Object type = invoke(comparison, "getComparisonType"); //$NON-NLS-1$
            one.put("comparisonType", type == null ? null : type.toString()); //$NON-NLS-1$
            List<String> values = new ArrayList<>();
            EList<EObject> right = BmDcsHelper.getEObjectList(comparison, "getRight"); //$NON-NLS-1$
            if (right != null)
            {
                for (EObject value : right)
                {
                    values.add(valueText(value));
                }
            }
            one.put("value", values.size() == 1 ? values.get(0) : values); //$NON-NLS-1$
            out.add(one);
        }
        return out;
    }

    /**
     * @param item a rule
     * @return the appearance parameters the rule sets, name to value
     */
    private static Map<String, Object> appearance(Object item)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Object appearance = invoke(item, "getAppearance"); //$NON-NLS-1$
        EList<EObject> parameters = appearance == null ? null : BmDcsHelper.getEObjectList(appearance, "getItems"); //$NON-NLS-1$
        if (parameters == null)
        {
            return out;
        }
        for (EObject parameter : parameters)
        {
            if (Boolean.FALSE.equals(invoke(parameter, "isUse"))) //$NON-NLS-1$
            {
                continue;
            }
            Object key = invoke(invoke(parameter, "getParameter"), "getValue"); //$NON-NLS-1$ //$NON-NLS-2$
            EList<EObject> values = BmDcsHelper.getEObjectList(parameter, "getValues"); //$NON-NLS-1$
            String text = values == null || values.isEmpty() ? null : valueText(values.get(0));
            out.put(String.valueOf(key), text);
        }
        return out;
    }

    /**
     * @param field a composition field, or <code>null</code>
     * @return its path, or <code>null</code>
     */
    private static String fieldPath(Object field)
    {
        if (field == null)
        {
            return null;
        }
        Object path = invoke(field, "getValue"); //$NON-NLS-1$
        return path != null ? path.toString() : null;
    }

    /**
     * The text of a model value: the value it carries, or the class of the value when it carries an
     * object (a color, a font).
     *
     * @param value an mcore value
     * @return the text
     */
    private static String valueText(Object value)
    {
        Object carried = invoke(value, "getValue"); //$NON-NLS-1$
        if (carried == null)
        {
            carried = invoke(value, "isValue"); //$NON-NLS-1$
        }
        if (carried instanceof EObject)
        {
            return ((EObject)carried).eClass().getName();
        }
        if (carried != null)
        {
            return carried.toString();
        }
        return value instanceof EObject ? ((EObject)value).eClass().getName() : String.valueOf(value);
    }

    /**
     * Calls a getter by name.
     *
     * @param target the receiver, or <code>null</code>
     * @param getter the getter name
     * @return what it returns, or <code>null</code> when the receiver has no such getter or it failed
     */
    private static Object invoke(Object target, String getter)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            return target.getClass().getMethod(getter).invoke(target);
        }
        catch (ReflectiveOperationException | RuntimeException absent)
        {
            // A model object of another shape has no such member; the reader treats that as empty.
            return null;
        }
    }
}
