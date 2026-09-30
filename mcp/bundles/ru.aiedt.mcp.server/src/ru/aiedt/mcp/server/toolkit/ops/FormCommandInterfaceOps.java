package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

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
 * ({@link #validateCommandInterfaceGroup}, {@link #findCommandInterfaceItemByFqn},
 * {@link #createCommandInterfaceItem}, {@link #setCommandInterfaceItemProperty},
 * {@link #coerceForSetter}) are private here. {@link #applyCommandInterfaceMutation} and
 * {@link #commandFqnOfItem} are package-visible because the regression test drives them
 * directly against a form model built from the factories.
 */
final class FormCommandInterfaceOps
{
    /** Lower-case prefix of a form command address, compared against a lower-cased address. */
    private static final String FORM_COMMAND_MARKER = "form.command."; //$NON-NLS-1$

    /** Lower-case prefix of every address that names something inside a form. */
    private static final String FORM_PREFIX = "form."; //$NON-NLS-1$

    /** Lower-case prefix of an address that names something inside a form item. */
    private static final String FORM_ITEM_MARKER = "form.item."; //$NON-NLS-1$

    /** Lower-case marker of a standard command segment inside an address. */
    private static final String STANDARD_COMMAND_MARKER = ".standardcommand."; //$NON-NLS-1$

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
            return ProjectResolver.notFound(projectName).toJson();
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
            applyCommandInterfaceMutation(tx, form, panelLcFinal, commandFqn,
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
            return ProjectResolver.notFound(projectName).toJson();
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
            applyCommandInterfaceMutation(tx, form, panelLc, commandFqn,
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
            return ProjectResolver.notFound(projectName).toJson();
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
            applyCommandInterfaceMutation(tx, form, panelLc, commandFqn,
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
     * fallback. The same tag carries a command address that resolves to no
     * command: the item is not created and the panel is left untouched.
     *
     * @param transaction the BM transaction the mutation runs in, or <code>null</code> when the
     *     caller holds none
     * @param form the form being edited
     * @param panelLc the panel, lower-cased: {@code navigation} or {@code commandbar}
     * @param commandFqn the address of the command
     * @param mode {@code add}, {@code remove} or {@code set_property}
     * @param group the group to apply on an add, or <code>null</code>
     * @param visible the visibility to apply on an add, or <code>null</code>
     * @param index the position to apply on an add, or <code>null</code>
     * @param property the name and value of the property to set on {@code set_property}
     * @return the status text the caller turns into the tool answer
     */
    @SuppressWarnings("unchecked")
    static String applyCommandInterfaceMutation(Object transaction, Object form, String panelLc,
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
            // Measured against the 2026.1 and 2026.2 bundles: FormCommandInterfaceItems declares
            // EList<FormCommandInterfaceItem> getCmiFragmentRecord() and no getItems() in either
            // release, so every call here refused with a NoSuchMethodException naming
            // FormCommandInterfaceItemsImpl.
            Object items =
                panelObject.getClass().getMethod("getCmiFragmentRecord").invoke(panelObject); //$NON-NLS-1$
            if (!(items instanceof org.eclipse.emf.common.util.EList))
            {
                return "Error: panel.getCmiFragmentRecord() did not return an EList."; //$NON-NLS-1$
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
                    // The command is the one property an item cannot exist without, and the model
                    // carries it as a Command object rather than as its FQN. The address is
                    // therefore resolved before the item is created: an address that names no
                    // command is refused here, with the panel still untouched.
                    Object command = resolveCommandByFqn(transaction, form, commandFqn);
                    if (command == null)
                    {
                        return "Error: formApiNotFound:" //$NON-NLS-1$
                            + "command '" + commandFqn + "' does not resolve to a command. Expected " //$NON-NLS-1$
                            + "Form.Command.<Name>, Form.StandardCommand.<Name>, " //$NON-NLS-1$
                            + "CommonCommand.<Name>, <Type>.<Object>.Command.<Name> or " //$NON-NLS-1$
                            + "<Type>.<Object>.StandardCommand.<Name>."; //$NON-NLS-1$
                    }
                    Object newItem = createCommandInterfaceItem(panelObject, commandFqn);
                    if (newItem == null)
                    {
                        return "Error: formApiNotFound:" //$NON-NLS-1$
                            + "no factory method to create a command interface item."; //$NON-NLS-1$
                    }
                    String commandNotApplied = setCommandInterfaceItemProperty(newItem, "command", command); //$NON-NLS-1$
                    if (commandNotApplied != null)
                    {
                        // An item without its command would corrupt the panel, so refuse rather
                        // than add it half-bound. Point at the GUI, same as a missing
                        // getCommandInterface().
                        return "Error: formApiNotFound:" //$NON-NLS-1$
                            + "the resolved command was not bound to the item (" //$NON-NLS-1$
                            + commandNotApplied + ")."; //$NON-NLS-1$
                    }
                    java.util.List<String> notAppliedList = new java.util.ArrayList<>();
                    if (group != null && !group.isEmpty())
                    {
                        addIfNotNull(notAppliedList, applyCommandInterfaceGroup(newItem, panelObject, group));
                    }
                    if (visible != null)
                    {
                        addIfNotNull(notAppliedList, applyCommandInterfaceVisible(newItem, visible));
                    }
                    if (index != null)
                    {
                        addIfNotNull(notAppliedList, setCommandInterfaceItemProperty(newItem, "index", index)); //$NON-NLS-1$
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
                        notApplied = applyCommandInterfaceGroup(existing, panelObject, propValue);
                    }
                    else if ("visible".equals(propLc)) //$NON-NLS-1$
                    {
                        notApplied = applyCommandInterfaceVisible(existing,
                            "true".equalsIgnoreCase(propValue)); //$NON-NLS-1$
                    }
                    else if ("index".equals(propLc)) //$NON-NLS-1$
                    {
                        try
                        {
                            notApplied = setCommandInterfaceItemProperty(existing, "index", //$NON-NLS-1$
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

    /**
     * Finds the item of a panel whose command carries an address.
     *
     * @param items the panel's items
     * @param commandFqn the address of the command
     * @return the item, or <code>null</code> when no item carries that address
     */
    private static Object findCommandInterfaceItemByFqn(
        org.eclipse.emf.common.util.EList<Object> items, String commandFqn)
    {
        if (commandFqn == null)
        {
            return null;
        }
        for (Object item : items)
        {
            String itemFqn = commandFqnOfItem(item);
            if (itemFqn != null && itemFqn.equalsIgnoreCase(commandFqn))
            {
                return item;
            }
        }
        return null;
    }

    /**
     * The address of the command bound to a command-interface item, probed in reliability order: a
     * hypothetical {@code getCommandFqn} accessor first (kept for forward compatibility with a runtime
     * that might expose one), then the command path built by walking the command's own container, then
     * the EDT-native {@code bmGetFqn()} of the bound command when it is a top BM object, then the
     * command's {@code toString()} as a weak last resort. Returns {@code null} when no probe yields a
     * value.
     * <p>
     * The EDT 2026.1 {@code FormCommandInterfaceItem} model exposes only {@code getCommand(Command)};
     * the {@code getCommandFqn} probe this class originally led with never resolved here, so every
     * by-FQN lookup fell through to {@code Command.toString()} (an EMF label, not an address) and
     * silently returned null - {@code remove} and {@code set_property} always answered "not present"
     * and the {@code add} duplicate check never saw an existing entry.
     * </p>
     * <p>
     * {@code IBmObject.bmGetFqn()} asserts {@code bmIsTop()} and throws for every command nested in
     * another object - an object command ({@code Catalog.X.Command.Y}), a standard command of an
     * object ({@code Catalog.X.StandardCommand.Y}) or a command of the form itself
     * ({@code Form.Command.<Name>}, {@code Form.Item.<Item>.StandardCommand.<Name>}). The container
     * walk answers those, in the spelling the {@code .form} file writes and
     * {@code get_form_structure} reports, so an address read from the form is the address the
     * mutation operations accept.
     * </p>
     * @param item the command-interface item
     * @return the address of its command, or <code>null</code> when no probe yields one
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
        String path = GetFormStructureTool.commandPath(cmd);
        if (path != null && !path.isEmpty())
        {
            return path;
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

    /**
     * Resolves the address of a command to the command object the model holds, so a mutation binds
     * an object rather than a string.
     *
     * <p>The accepted addresses are the ones {@code get_form_structure} reports and the form file
     * writes: {@code Form.Command.<Name>} for a command of the form itself,
     * {@code Form.StandardCommand.<Name>} for a standard command the form carries,
     * {@code Form.Item.<Item>.StandardCommand.<Name>} for a standard command a form item carries,
     * {@code CommonCommand.<Name>},
     * {@code <Type>.<Object>.Command.<Name>} and {@code <Type>.<Object>.StandardCommand.<Name>} for
     * a command of the project model. Names are matched case-insensitively.
     *
     * @param transaction the BM transaction the mutation runs in, or <code>null</code> when the
     *     caller holds none
     * @param form the form being edited
     * @param commandFqn the command address
     * @return the command object, or <code>null</code> when nothing reachable from the form or the
     *     project carries that address
     */
    private static Object resolveCommandByFqn(Object transaction, Object form, String commandFqn)
    {
        if (commandFqn == null || commandFqn.isEmpty())
        {
            return null;
        }
        String fqn = commandFqn.trim();
        String lower = fqn.toLowerCase(Locale.ROOT);
        if (lower.startsWith(FORM_COMMAND_MARKER))
        {
            return findNamed(form, "getFormCommands", //$NON-NLS-1$
                fqn.substring(FORM_COMMAND_MARKER.length()));
        }
        int standard = lower.lastIndexOf(STANDARD_COMMAND_MARKER);
        if (lower.startsWith(FORM_PREFIX))
        {
            if (standard < 0)
            {
                return null;
            }
            String commandName = fqn.substring(standard + STANDARD_COMMAND_MARKER.length());
            if (lower.startsWith(FORM_ITEM_MARKER) && standard > FORM_ITEM_MARKER.length())
            {
                // A standard command an address pins to a form item is a child of that item (its
                // FormStandardCommandSource.getCommands() list), not of the form; this mirrors the
                // way the address is built from the command's container when the structure is read.
                // The form's own list stays the fallback, so an item address that names a command
                // the form itself carries keeps resolving the way it did.
                Object item = findFormItemByName(form,
                    fqn.substring(FORM_ITEM_MARKER.length(), standard));
                Object command = findNamed(item, "getCommands", commandName); //$NON-NLS-1$
                if (command != null)
                {
                    return command;
                }
            }
            return findNamed(form, "getCommands", commandName); //$NON-NLS-1$
        }
        if (standard > 0)
        {
            Object owner = referenceTarget(transaction, fqn.substring(0, standard));
            return owner == null ? null
                : findNamed(owner, "getStandardCommands", //$NON-NLS-1$
                    fqn.substring(standard + STANDARD_COMMAND_MARKER.length()));
        }
        return referenceTarget(transaction, fqn);
    }

    /**
     * Resolves an address that names something outside the form, through the BM transaction, and
     * keeps it only when it is a command.
     *
     * @param transaction the BM transaction the mutation runs in, or <code>null</code> when the
     *     caller holds none
     * @param fqn the address of a metadata object or of one of its commands
     * @return the command the address names, or <code>null</code> when the address resolves to
     *     nothing or to something that is not a command
     */
    private static Object referenceTarget(Object transaction, String fqn)
    {
        if (!(transaction instanceof IBmTransaction))
        {
            // The reflection path can be driven without a transaction, and nothing outside the form
            // is reachable then; the caller reads null as "not resolved".
            return null;
        }
        Object target;
        try
        {
            target = EditMetadataTool.resolveReferenceTarget((IBmTransaction) transaction, fqn);
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("resolveCommandByFqn: '" + fqn + "' not resolved - " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
        return target instanceof Command ? target : null;
    }

    /**
     * Finds a named element of a model list.
     *
     * @param container the object holding the list, or <code>null</code>
     * @param listGetter the getter of the list, for example {@code getFormCommands}
     * @param name the wanted name, matched case-insensitively
     * @return the element carrying the name, or <code>null</code> when the container has no such
     *     list or none of its elements carries the name
     */
    private static Object findNamed(Object container, String listGetter, String name)
    {
        if (container == null || name == null || name.isEmpty())
        {
            return null;
        }
        Object list = reflectiveGetter(container, listGetter);
        if (!(list instanceof Iterable))
        {
            return null;
        }
        for (Object element : (Iterable<?>) list)
        {
            Object elementName = reflectiveGetter(element, "getName"); //$NON-NLS-1$
            if (elementName instanceof String && name.equalsIgnoreCase((String)elementName))
            {
                return element;
            }
        }
        return null;
    }

    /**
     * Finds a form item by name, walking {@code FormItemContainer.getItems()} recursively. An item
     * that is not a container answers no {@code getItems()} and is read as a leaf.
     *
     * @param container the form or a container item to walk, or <code>null</code>
     * @param name the item name, matched case-insensitively
     * @return the item carrying the name, or <code>null</code> when the container holds no item
     *     with that name
     */
    private static Object findFormItemByName(Object container, String name)
    {
        if (container == null || name == null || name.isEmpty())
        {
            return null;
        }
        Object items = reflectiveGetter(container, "getItems"); //$NON-NLS-1$
        if (!(items instanceof Iterable))
        {
            return null;
        }
        for (Object item : (Iterable<?>) items)
        {
            Object itemName = reflectiveGetter(item, "getName"); //$NON-NLS-1$
            if (itemName instanceof String && name.equalsIgnoreCase((String)itemName))
            {
                return item;
            }
            Object found = findFormItemByName(item, name);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    /**
     * Applies a group to a command-interface item. The item references a {@code CommandGroup}
     * object, not a name, so a group another item of the same panel already carries is reused, and a
     * group no item carries yet is created as a built-in one.
     *
     * @param item the item
     * @param panelObject the panel the item belongs to or is being added to
     * @param groupName the group name, one of the {@code FormNavigationPanel*} /
     *     {@code FormCommandBar*} names
     * @return <code>null</code> on success, or a short failure reason
     */
    private static String applyCommandInterfaceGroup(Object item, Object panelObject,
        String groupName)
    {
        Object group = findGroupInPanel(panelObject, groupName);
        if (group == null)
        {
            group = referenceStandardCommandGroup(groupName);
        }
        return setCommandInterfaceItemProperty(item, "group", group); //$NON-NLS-1$
    }

    /**
     * The group that one of the panel's items already carries.
     *
     * @param panelObject the panel
     * @param groupName the group name, matched case-insensitively
     * @return the group object, or <code>null</code> when no item of the panel carries that name
     */
    private static Object findGroupInPanel(Object panelObject, String groupName)
    {
        Object items = reflectiveGetter(panelObject, "getCmiFragmentRecord"); //$NON-NLS-1$
        if (!(items instanceof Iterable))
        {
            return null;
        }
        for (Object item : (Iterable<?>) items)
        {
            Object group = reflectiveGetter(item, "getGroup"); //$NON-NLS-1$
            Object name = group == null ? null : reflectiveGetter(group, "getName"); //$NON-NLS-1$
            if (name instanceof String && groupName.equalsIgnoreCase((String)name))
            {
                return group;
            }
        }
        return null;
    }

    /**
     * A reference to a built-in command group that the form does not carry yet.
     *
     * <p>A detached, factory-created {@code StandardCommandGroup} is a BM object with no namespace
     * and no resource, so the transaction cannot build a persistable reference to it and the commit
     * fails with "Failed to persist reference value". The reference is therefore written the way
     * EDT's own XML reader writes a group it has not resolved yet: a proxy with an
     * {@code unresolved:/<name>} URI, which the form writer serializes back to the bare group name
     * in the {@code <group>} element.
     *
     * @param groupName the group name
     * @return the group object to reference
     */
    private static StandardCommandGroup referenceStandardCommandGroup(String groupName)
    {
        StandardCommandGroup group = McoreFactory.eINSTANCE.createStandardCommandGroup();
        group.setName(groupName);
        group.setCategory(groupCategory(groupName));
        ((InternalEObject)group).eSetProxyURI(URI.createURI("unresolved:/" + groupName)); //$NON-NLS-1$
        return group;
    }

    /**
     * The category a built-in form group name belongs to.
     *
     * @param groupName the group name
     * @return the category the name's prefix selects
     */
    private static CommandGroupCategory groupCategory(String groupName)
    {
        return groupName.regionMatches(true, 0, "FormCommandBar", 0, "FormCommandBar".length()) //$NON-NLS-1$ //$NON-NLS-2$
            ? CommandGroupCategory.FORM_COMMAND_BAR : CommandGroupCategory.FORM_NAVIGATION_PANEL;
    }

    /**
     * Applies the visibility of a command-interface item. The model carries it as an
     * {@code AdjustableBoolean} rather than as a plain flag, so the flag is wrapped before the
     * setter is looked up.
     *
     * @param item the item
     * @param visible the wanted visibility
     * @return <code>null</code> on success, or a short failure reason
     */
    private static String applyCommandInterfaceVisible(Object item, boolean visible)
    {
        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.setCommon(visible);
        return setCommandInterfaceItemProperty(item, "userVisible", flag); //$NON-NLS-1$
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
