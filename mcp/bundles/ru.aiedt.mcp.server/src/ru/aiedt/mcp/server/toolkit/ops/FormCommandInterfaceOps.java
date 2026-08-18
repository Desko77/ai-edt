package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Form command-interface cluster of {@code edit_metadata}: add / remove / reproperty
 * a command reference in a form's navigation panel or command bar. Extracted verbatim
 * from {@link EditMetadataTool} (Inc4 god-class split); handlers are package-visible
 * and dispatched through the single-source op-registry. Shared stateless helpers live
 * on {@link EditMetadataTool} (qualified calls); cluster-local helpers
 * ({@link #validateCommandInterfaceGroup}, {@link #applyCommandInterfaceMutation},
 * {@link #findCommandInterfaceItemByFqn}, {@link #createCommandInterfaceItem},
 * {@link #setCommandInterfaceItemProperty}, {@link #coerceForSetter}) are private here.
 */
final class FormCommandInterfaceOps
{
    String opAddFormCommandInterfaceItem(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String panel = JsonUtils.extractStringArgument(params, "panel"); //$NON-NLS-1$
        String commandFqn = JsonUtils.extractStringArgument(params, "commandFqn"); //$NON-NLS-1$
        String group = JsonUtils.extractStringArgument(params, "group"); //$NON-NLS-1$
        Boolean visible = JsonUtils.extractBooleanArgumentNullable(params, "visible"); //$NON-NLS-1$
        Integer index;
        try
        {
            index = extractIntegerNullable(params, "index"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException iae)
        {
            return ToolResult.error(TextSuggest.safeMessage(iae)).toJson();
        }

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(panel, "panel") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(commandFqn, "commandFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String panelLc = panel.toLowerCase();
        if (!"navigation".equals(panelLc) && !"commandbar".equals(panelLc)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Unknown panel '" + panel //$NON-NLS-1$
                + "'. Allowed: 'navigation' (FormNavigationPanel*), " //$NON-NLS-1$
                + "'commandBar' (FormCommandBar*).").toJson(); //$NON-NLS-1$
        }
        if (group != null && !group.isEmpty())
        {
            String groupErr = validateCommandInterfaceGroup(panelLc, group);
            if (groupErr != null)
            {
                return ToolResult.error(groupErr).toJson();
            }
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final String panelLcFinal = panelLc;
        final String groupFinal = group;
        final Boolean visibleFinal = visible;
        final Integer indexFinal = index;
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) ->
            applyCommandInterfaceMutation(form, panelLcFinal, commandFqn,
                "add", groupFinal, visibleFinal, indexFinal, null)); //$NON-NLS-1$
        return EditMetadataTool.formatFormResultWithApiTag(result, "add_form_command_interface_item", formFqn); //$NON-NLS-1$
    }

    /**
     * 1.42: removes a command reference from a form's command interface. The
     * actual command (object or common) is not touched - only its entry in
     * the requested panel disappears.
     */
    String opRemoveFormCommandInterfaceItem(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String panel = JsonUtils.extractStringArgument(params, "panel"); //$NON-NLS-1$
        String commandFqn = JsonUtils.extractStringArgument(params, "commandFqn"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(panel, "panel") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(commandFqn, "commandFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final String panelLc = panel.toLowerCase();
        if (!"navigation".equals(panelLc) && !"commandbar".equals(panelLc)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Unknown panel '" + panel //$NON-NLS-1$
                + "'. Allowed: 'navigation' (FormNavigationPanel*), " //$NON-NLS-1$
                + "'commandBar' (FormCommandBar*).").toJson(); //$NON-NLS-1$
        }
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) ->
            applyCommandInterfaceMutation(form, panelLc, commandFqn,
                "remove", null, null, null, null)); //$NON-NLS-1$
        return EditMetadataTool.formatFormResultWithApiTag(result, "remove_form_command_interface_item", formFqn); //$NON-NLS-1$
    }

    /**
     * 1.42: changes one of {@code group} / {@code visible} / {@code index} on
     * an existing item of a form's command interface. Validates the group
     * category against the panel before the write.
     */
    String opSetFormCommandInterfaceItemProperty(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String panel = JsonUtils.extractStringArgument(params, "panel"); //$NON-NLS-1$
        String commandFqn = JsonUtils.extractStringArgument(params, "commandFqn"); //$NON-NLS-1$
        String propertyName = JsonUtils.extractStringArgument(params, "propertyName"); //$NON-NLS-1$
        String propertyValue = JsonUtils.extractStringArgument(params, "propertyValue"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(panel, "panel") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(commandFqn, "commandFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(propertyName, "propertyName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String panelLc = panel.toLowerCase();
        if (!"navigation".equals(panelLc) && !"commandbar".equals(panelLc)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Unknown panel '" + panel //$NON-NLS-1$
                + "'. Allowed: 'navigation' (FormNavigationPanel*), " //$NON-NLS-1$
                + "'commandBar' (FormCommandBar*).").toJson(); //$NON-NLS-1$
        }
        String propLc = propertyName.toLowerCase();
        if (!"group".equals(propLc) && !"visible".equals(propLc) && !"index".equals(propLc)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return ToolResult.error("Unknown propertyName '" + propertyName //$NON-NLS-1$
                + "'. Allowed: 'group', 'visible', 'index'.").toJson(); //$NON-NLS-1$
        }
        if ("group".equals(propLc) && propertyValue != null && !propertyValue.isEmpty()) //$NON-NLS-1$
        {
            String groupErr = validateCommandInterfaceGroup(panelLc, propertyValue);
            if (groupErr != null)
            {
                return ToolResult.error(groupErr).toJson();
            }
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final String propLcFinal = propLc;
        final String propValueFinal = propertyValue;
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) ->
            applyCommandInterfaceMutation(form, panelLc, commandFqn,
                "set_property", null, null, null, //$NON-NLS-1$
                new String[] { propLcFinal, propValueFinal }));
        return EditMetadataTool.formatFormResultWithApiTag(result, "set_form_command_interface_item_property", formFqn); //$NON-NLS-1$
    }

    /**
     * 1.42: validates that the requested group category matches the panel
     * kind. Returns {@code null} on success, an error string with hint
     * otherwise.
     */
    private static String validateCommandInterfaceGroup(String panelLc, String group)
    {
        boolean isNavigationGroup = group.startsWith("FormNavigationPanel"); //$NON-NLS-1$
        boolean isCommandBarGroup = group.startsWith("FormCommandBar"); //$NON-NLS-1$
        if (!isNavigationGroup && !isCommandBarGroup)
        {
            return "Unknown group '" + group + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use FormNavigationPanelImportant / FormNavigationPanelOrdinary / " //$NON-NLS-1$
                + "FormNavigationPanelSeeAlso for panel=navigation, " //$NON-NLS-1$
                + "or FormCommandBar / FormCommandBarImportant / FormCommandBarSeeAlso / " //$NON-NLS-1$
                + "FormCommandBarCreateBasedOn for panel=commandBar."; //$NON-NLS-1$
        }
        if ("navigation".equals(panelLc) && !isNavigationGroup) //$NON-NLS-1$
        {
            return "Group '" + group + "' belongs to the command bar - use it with " //$NON-NLS-1$ //$NON-NLS-2$
                + "panel=commandBar. For navigation use FormNavigationPanel*."; //$NON-NLS-1$
        }
        if ("commandbar".equals(panelLc) && !isCommandBarGroup) //$NON-NLS-1$
        {
            return "Group '" + group + "' belongs to the navigation panel - use it with " //$NON-NLS-1$ //$NON-NLS-2$
                + "panel=navigation. For the command bar use FormCommandBar*."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * 1.42: applies an add/remove/setProperty mutation against a form's
     * command interface using reflection on
     * {@code Form.getCommandInterface().getNavigationPanel()/getCommandBar()}.
     * Returns a status string consumed by {@link #formatFormResultWithApiTag}.
     *
     * <p>When the EDT runtime exposes no {@code Form.getCommandInterface()}
     * method (older builds), the response carries
     * {@code formApiNotFound} so the agent picks the GUI
     * fallback.
     */
    @SuppressWarnings("unchecked")
    private static String applyCommandInterfaceMutation(Object form, String panelLc,
        String commandFqn, String mode, String group, Boolean visible, Integer index,
        String[] property)
    {
        try
        {
            Object commandInterface;
            try
            {
                commandInterface = form.getClass().getMethod("getCommandInterface").invoke(form); //$NON-NLS-1$
            }
            catch (NoSuchMethodException nsme)
            {
                return "Error: formApiNotFound:" //$NON-NLS-1$
                    + "Form.getCommandInterface() is not exposed on this EDT runtime. " //$NON-NLS-1$
                    + "Use the EDT GUI 'Command interface' editor instead."; //$NON-NLS-1$
            }
            if (commandInterface == null)
            {
                return "Error: form has no command interface object."; //$NON-NLS-1$
            }
            String panelGetter = "navigation".equals(panelLc) //$NON-NLS-1$
                ? "getNavigationPanel" : "getCommandBar"; //$NON-NLS-1$ //$NON-NLS-2$
            Object panelObject = commandInterface.getClass().getMethod(panelGetter)
                .invoke(commandInterface);
            if (panelObject == null)
            {
                return "Error: form panel '" + panelLc + "' is not initialised."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            Object items = panelObject.getClass().getMethod("getItems").invoke(panelObject); //$NON-NLS-1$
            if (!(items instanceof org.eclipse.emf.common.util.EList))
            {
                return "Error: panel.getItems() did not return an EList."; //$NON-NLS-1$
            }
            org.eclipse.emf.common.util.EList<Object> itemList =
                (org.eclipse.emf.common.util.EList<Object>) items;

            Object existing = findCommandInterfaceItemByFqn(itemList, commandFqn);
            switch (mode)
            {
                case "add": //$NON-NLS-1$
                    if (existing != null)
                    {
                        return "Error: command '" + commandFqn //$NON-NLS-1$
                            + "' is already present in this panel. Use " //$NON-NLS-1$
                            + "setFormCommandInterfaceItemProperty to change its " //$NON-NLS-1$
                            + "group/visible/index, or removeFormCommandInterfaceItem first."; //$NON-NLS-1$
                    }
                    Object newItem = createCommandInterfaceItem(panelObject, commandFqn);
                    if (newItem == null)
                    {
                        return "Error: formApiNotFound:" //$NON-NLS-1$
                            + "no factory method to create a command interface item."; //$NON-NLS-1$
                    }
                    // The command is the one property an item cannot exist without. The EDT 2026.1
                    // model exposes setCommand(Command), not setCommandFqn(String), and resolving the
                    // FQN to the Command EObject needs the BM transaction this reflection path does
                    // not hold - so if binding the command did not take, refuse rather than add an
                    // empty item that would corrupt the panel. Point at the GUI, same as a missing
                    // getCommandInterface().
                    String commandNotApplied = setCommandInterfaceItemProperty(newItem, "commandFqn", commandFqn); //$NON-NLS-1$
                    if (commandNotApplied != null)
                    {
                        return "Error: formApiNotFound:" //$NON-NLS-1$
                            + "binding a command by FQN is not supported on this EDT runtime " //$NON-NLS-1$
                            + "(setCommand needs the resolved Command object, not the FQN string). " //$NON-NLS-1$
                            + "Use the EDT GUI 'Command interface' editor to add the command."; //$NON-NLS-1$
                    }
                    java.util.List<String> notAppliedList = new java.util.ArrayList<>();
                    if (group != null && !group.isEmpty())
                    {
                        addIfNotNull(notAppliedList, setCommandInterfaceItemProperty(newItem, "group", group)); //$NON-NLS-1$
                    }
                    if (visible != null)
                    {
                        addIfNotNull(notAppliedList, setCommandInterfaceItemProperty(newItem, "visible", visible)); //$NON-NLS-1$
                    }
                    if (index != null)
                    {
                        addIfNotNull(notAppliedList, setCommandInterfaceItemProperty(newItem, "order", index)); //$NON-NLS-1$
                    }
                    itemList.add(newItem);
                    return "added " + commandFqn + " to " + panelLc //$NON-NLS-1$ //$NON-NLS-2$
                        + notAppliedWarning(notAppliedList);
                case "remove": //$NON-NLS-1$
                    if (existing == null)
                    {
                        return "Error: command '" + commandFqn //$NON-NLS-1$
                            + "' is not present in this panel."; //$NON-NLS-1$
                    }
                    itemList.remove(existing);
                    return "removed " + commandFqn + " from " + panelLc; //$NON-NLS-1$ //$NON-NLS-2$
                case "set_property": //$NON-NLS-1$
                    if (existing == null)
                    {
                        return "Error: command '" + commandFqn //$NON-NLS-1$
                            + "' is not present in this panel."; //$NON-NLS-1$
                    }
                    String propLc = property[0];
                    String propValue = property[1];
                    String notApplied;
                    if ("group".equals(propLc)) //$NON-NLS-1$
                    {
                        notApplied = setCommandInterfaceItemProperty(existing, "group", propValue); //$NON-NLS-1$
                    }
                    else if ("visible".equals(propLc)) //$NON-NLS-1$
                    {
                        notApplied = setCommandInterfaceItemProperty(existing, "visible", //$NON-NLS-1$
                            "true".equalsIgnoreCase(propValue)); //$NON-NLS-1$
                    }
                    else if ("index".equals(propLc)) //$NON-NLS-1$
                    {
                        try
                        {
                            notApplied = setCommandInterfaceItemProperty(existing, "order", //$NON-NLS-1$
                                Integer.parseInt(propValue));
                        }
                        catch (NumberFormatException nfe)
                        {
                            return "Error: index must be an integer, got '" + propValue + "'."; //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                    else
                    {
                        notApplied = "unknown property '" + propLc + "' (expected group/visible/index)"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    return "updated " + propLc + " on " + commandFqn //$NON-NLS-1$ //$NON-NLS-2$
                        + (notApplied != null ? "; WARNING: " + notApplied : ""); //$NON-NLS-1$ //$NON-NLS-2$
                default:
                    return "Error: " + TextSuggest.invalidValue("mode", mode, //$NON-NLS-1$ //$NON-NLS-2$
                        java.util.Arrays.asList("add", "remove", "set_property")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        catch (Exception e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "Error: form command interface mutation failed - " //$NON-NLS-1$
                + (cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName());
        }
    }

    private static Object findCommandInterfaceItemByFqn(
        org.eclipse.emf.common.util.EList<Object> items, String commandFqn)
    {
        for (Object item : items)
        {
            if (java.util.Objects.equals(commandFqn, commandFqnOfItem(item)))
            {
                return item;
            }
        }
        return null;
    }

    /**
     * Best-effort FQN of the command bound to a command-interface item, probed in reliability order:
     * a hypothetical {@code getCommandFqn} accessor first (kept for forward compatibility with a
     * runtime that might expose one), then the EDT-native {@code bmGetFqn()} of the bound command when
     * it is itself a top BM object (a free-standing {@code CommonCommand.X} - the common case for a
     * command-interface reference), then the command's {@code toString()} as a weak last resort.
     * Returns {@code null} when no probe yields a value.
     * <p>
     * The EDT 2026.1 {@code FormCommandInterfaceItem} model exposes only {@code getCommand(Command)};
     * the {@code getCommandFqn} probe this class originally led with never resolved here, so every
     * by-FQN lookup fell through to {@code Command.toString()} (an EMF label, not an FQN) and silently
     * returned null - {@code remove} and {@code set_property} always answered "not present" and the
     * {@code add} duplicate check never saw an existing entry. The {@code bmGetFqn} step fixes the
     * free-standing common-command case.
     * </p>
     * <p>
     * Known limitation: {@code IBmObject.bmGetFqn()} asserts {@code bmIsTop()} and throws for a nested
     * object command ({@code Catalog.X.Command.Y}) or a form command ({@code ...Form.F.Command.C}), so
     * those do not resolve here - the by-FQN lookup honestly returns null rather than risk a wrong match
     * from a guessed containment path (the segment type for a {@code CatalogCommand} is {@code Command}
     * but for a {@code StandardCommand} it stays {@code StandardCommand}, and that mapping is not
     * reconstructible from the EClass alone). The canonical FQN for a nested command comes from EDT's
     * {@code DelegatingFqnProvider} (resource-set context), which this reflection path does not hold;
     * wiring it is follow-up that needs a live form to verify.
     * </p>
     */
    static String commandFqnOfItem(Object item)
    {
        if (item == null)
        {
            return null;
        }
        Object fqn = reflectiveGetter(item, "getCommandFqn"); //$NON-NLS-1$
        if (fqn instanceof String && !((String)fqn).isEmpty())
        {
            return (String)fqn;
        }
        Object cmd = reflectiveGetter(item, "getCommand"); //$NON-NLS-1$
        if (cmd == null)
        {
            return null;
        }
        Object bmFqn = reflectiveGetter(cmd, "bmGetFqn"); //$NON-NLS-1$
        if (bmFqn instanceof String && !((String)bmFqn).isEmpty())
        {
            return (String)bmFqn;
        }
        return cmd.toString();
    }

    private static Object reflectiveGetter(Object target, String getter)
    {
        try
        {
            return target.getClass().getMethod(getter).invoke(target);
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    private static Object createCommandInterfaceItem(Object panelObject, String commandFqn)
    {
        try
        {
            Class<?> ffClass = Class.forName("com._1c.g5.v8.dt.form.model.FormFactory"); //$NON-NLS-1$
            Object ff = ffClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            for (String factoryMethod : new String[] {
                "createFormCommandInterfaceItem", //$NON-NLS-1$
                "createCommandInterfaceItem", //$NON-NLS-1$
                "createFormCmdInterfaceItem" //$NON-NLS-1$
            })
            {
                try
                {
                    return ffClass.getMethod(factoryMethod).invoke(ff);
                }
                catch (NoSuchMethodException ignored)
                {
                    // Try next factory method name.
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("createCommandInterfaceItem failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Reflectively applies one property to a command-interface item. Returns {@code null} on
     * success, or a short failure reason when no usable setter was found or every overload threw.
     * The caller surfaces the reason as a {@code WARNING} in the result so a partial mutation
     * (an item added without its commandFqn, or a requested property that did not take) is no
     * longer indistinguishable from a clean success (hindsight A3).
     */
    private static String setCommandInterfaceItemProperty(Object item, String propertyName,
        Object value)
    {
        String setterName = "set" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        boolean foundOverload = false;
        Exception lastError = null;
        for (java.lang.reflect.Method m : item.getClass().getMethods())
        {
            if (!m.getName().equals(setterName) || m.getParameterCount() != 1)
            {
                continue;
            }
            foundOverload = true;
            try
            {
                Class<?> paramType = m.getParameterTypes()[0];
                Object coerced = coerceForSetter(paramType, value);
                m.invoke(item, coerced);
                return null;
            }
            catch (Exception e)
            {
                lastError = e;
                // Try next overload.
            }
        }
        // No usable setter applied - the property was NOT set.
        String reason = !foundOverload
            ? "no single-arg setter '" + setterName + "' on " + item.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
            : "setter '" + setterName + "' threw: " //$NON-NLS-1$ //$NON-NLS-2$
                + (lastError.getMessage() != null ? lastError.getMessage() //$NON-NLS-1$
                    : lastError.getClass().getSimpleName());
        Activator.logWarning("setCommandInterfaceItemProperty: property '" + propertyName //$NON-NLS-1$
            + "' not applied - " + reason); //$NON-NLS-1$
        return "property '" + propertyName + "' not applied (" + reason + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addIfNotNull(java.util.List<String> list, String item)
    {
        if (item != null)
        {
            list.add(item);
        }
    }

    private static String notAppliedWarning(java.util.List<String> notApplied)
    {
        if (notApplied == null || notApplied.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return "; WARNING - " + String.join("; ", notApplied); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Object coerceForSetter(Class<?> paramType, Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (paramType.isInstance(value))
        {
            return value;
        }
        if (paramType == int.class || paramType == Integer.class)
        {
            return (value instanceof Number) ? ((Number) value).intValue()
                : Integer.parseInt(value.toString());
        }
        if (paramType == boolean.class || paramType == Boolean.class)
        {
            return (value instanceof Boolean) ? value
                : Boolean.parseBoolean(value.toString());
        }
        if (paramType == String.class)
        {
            return value.toString();
        }
        return value;
    }

    private static Integer extractIntegerNullable(Map<String, String> params, String key)
    {
        String raw = JsonUtils.extractStringArgument(params, key);
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException nfe)
        {
            // Surface as a typed exception so callers convert it to an error
            // response instead of silently dropping the value.
            throw new IllegalArgumentException(key + " must be an integer, got '" //$NON-NLS-1$
                + raw + "'."); //$NON-NLS-1$
        }
    }

}
