/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.StandardCommandRegistry;

/**
 * MCP tool for creating and removing form elements via BM API.
 * <p>
 * Supports operations: addField, addGroup, addButton, addTable, addDecoration,
 * removeItem, help.
 * <p>
 * Uses {@link BmFormHelper} for all form manipulation - reflection-based access
 * to EDT's internal EMF model, executed inside BM read-write transactions.
 */
public class EditFormTool implements IMcpTool
{
    public static final String NAME = "edit_form"; //$NON-NLS-1$

    private static final String OP_ADD_FIELD = "add_field"; //$NON-NLS-1$
    private static final String OP_ADD_GROUP = "add_group"; //$NON-NLS-1$
    private static final String OP_ADD_BUTTON = "add_button"; //$NON-NLS-1$
    private static final String OP_ADD_TABLE = "add_table"; //$NON-NLS-1$
    private static final String OP_ADD_DECORATION = "add_decoration"; //$NON-NLS-1$
    private static final String OP_REMOVE_ITEM = "remove_item"; //$NON-NLS-1$
    private static final String OP_HELP = "help"; //$NON-NLS-1$

    /** Lazy-initialized singleton helper */
    private BmFormHelper helper;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Edit a 1C managed form: add or remove elements " + //$NON-NLS-1$
            "(fields, groups, buttons, tables, decorations) via BM API. " + //$NON-NLS-1$
            "Operations: addField, addGroup, addButton, addTable, addDecoration, " + //$NON-NLS-1$
            "removeItem, help. Use 'help' operation for detailed usage. The same form " + //$NON-NLS-1$
            "operations are reachable via edit_metadata (the canonical metadata constructor); " + //$NON-NLS-1$
            "prefer edit_metadata when chaining metadata edits in one call."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project to work in", true) //$NON-NLS-1$
            .stringProperty("formFqn", //$NON-NLS-1$
                "BM top-object FQN of the form, ending with '.Form' " //$NON-NLS-1$
                + "(the Form.form file segment). " //$NON-NLS-1$
                + "Example: 'Catalog.Products.Form.ItemForm.Form'. " //$NON-NLS-1$
                + "On error the tool lists matching FQNs from the BM namespace " //$NON-NLS-1$
                + "(borrowed forms in extensions may use a different prefix). " //$NON-NLS-1$
                + "Required.", true) //$NON-NLS-1$
            .stringProperty("operation", //$NON-NLS-1$
                "Operation: addField, addGroup, addButton, addTable, addDecoration, " + //$NON-NLS-1$
                "removeItem, help (required)", true) //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Element name (required for add/remove operations)") //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Element title/caption") //$NON-NLS-1$
            .stringProperty("elementType", //$NON-NLS-1$
                "For addField: InputField, CheckBox, RadioButton, Label, Image. " + //$NON-NLS-1$
                "For addGroup: UsualGroup, Pages, Page, Column, CommandBar. " + //$NON-NLS-1$
                "For addDecoration: Label or Picture.") //$NON-NLS-1$
            .stringProperty("dataPath", //$NON-NLS-1$
                "Data path for field binding (e.g. 'Object.Name')") //$NON-NLS-1$
            .stringProperty("parentName", //$NON-NLS-1$
                "Parent container name (default: root form)") //$NON-NLS-1$
            .stringProperty("beforeName", //$NON-NLS-1$
                "Insert before this element name") //$NON-NLS-1$
            .stringProperty("standardCommand", //$NON-NLS-1$
                "Bind the button to a platform stock " //$NON-NLS-1$
                + "command instead of a custom form command. Names match the " //$NON-NLS-1$
                + "FormStandardCommand enum (PostAndClose, Write, Post, Copy, " //$NON-NLS-1$
                + "SetDeletionMark, Generate, Refresh, Find, Help and others). " //$NON-NLS-1$
                + "22 frequent commands get an auto-icon. Standard commands are " //$NON-NLS-1$
                + "rejected on DataProcessor / ExternalDataProcessor / " //$NON-NLS-1$
                + "ExternalReport forms - use a regular command + addCommandHandler.") //$NON-NLS-1$
            .booleanProperty("autoGenerateColumns", //$NON-NLS-1$
                "When adding a table with dataPath, " //$NON-NLS-1$
                + "automatically create FormField columns for every attribute of " //$NON-NLS-1$
                + "the underlying tabular section / value-table form attribute. " //$NON-NLS-1$
                + "Field names are prefixed with the parent table name " //$NON-NLS-1$
                + "(ТоварыКоличество instead of Количество) to avoid a platform " //$NON-NLS-1$
                + "render-time crash. Default: false.") //$NON-NLS-1$
            .stringProperty("picture", //$NON-NLS-1$
                "Picture reference for addDecoration with elementType=Picture. " //$NON-NLS-1$
                + "Accepts StdPicture.X / StdExtPicture.X (validated against the " //$NON-NLS-1$
                + "platform registry) or CommonPicture.X (validated against the " //$NON-NLS-1$
                + "project configuration). Typo'd names fail before write.") //$NON-NLS-1$
            .booleanProperty("hyperlink", //$NON-NLS-1$
                "Render the element as a clickable hyperlink. Applies to Label " //$NON-NLS-1$
                + "decorations (addDecoration elementType=Label) and Label fields " //$NON-NLS-1$
                + "(addField elementType=Label), setting hyperlink=true on the " //$NON-NLS-1$
                + "LabelDecorationExtInfo / LabelFieldExtInfo. Ignored (with a note " //$NON-NLS-1$
                + "in the response) for Picture decorations and non-Label fields. " //$NON-NLS-1$
                + "Default: false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$

        // Handle help operation early (no project/form needed)
        if (OP_HELP.equalsIgnoreCase(operation))
        {
            return buildHelpResponse();
        }

        // Validate required params
        if (projectName == null || projectName.isEmpty())
        {
            return buildError("projectName is required"); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return buildError("formFqn is required. " + //$NON-NLS-1$
                "Example: 'Catalog.Products.Form.ItemForm.Form' " + //$NON-NLS-1$
                "(note the trailing '.Form' segment from the Form.form file)"); //$NON-NLS-1$
        }
        if (operation == null || operation.isEmpty())
        {
            return buildError("operation is required. " + //$NON-NLS-1$
                "Options: addField, addGroup, addButton, addTable, addDecoration, removeItem, help"); //$NON-NLS-1$
        }

        // Execute on UI thread (BM API requires it in some EDT versions)
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() ->
        {
            try
            {
                resultRef.set(executeInternal(projectName, formFqn, operation, params));
            }
            catch (Exception e)
            {
                Activator.logError("Error in edit_form", e); //$NON-NLS-1$
                resultRef.set(buildError(e.getMessage()));
            }
        });

        return resultRef.get();
    }

    private String executeInternal(String projectName, String formFqn, String operation,
        Map<String, String> params)
    {
        // Initialize helper (lazy singleton)
        if (helper == null)
        {
            helper = new BmFormHelper();
        }
        if (!helper.init())
        {
            return buildError("BmFormHelper initialization failed. " + //$NON-NLS-1$
                "Form model classes not available in this EDT version."); //$NON-NLS-1$
        }

        // Find project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return buildError(ProjectResolver.describeNotFound(projectName)); //$NON-NLS-1$
        }

        // Extract common params
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String elementType = JsonUtils.extractStringArgument(params, "elementType"); //$NON-NLS-1$
        String dataPath = JsonUtils.extractStringArgument(params, "dataPath"); //$NON-NLS-1$
        String parentName = JsonUtils.extractStringArgument(params, "parentName"); //$NON-NLS-1$
        String beforeName = JsonUtils.extractStringArgument(params, "beforeName"); //$NON-NLS-1$
        String standardCommand = JsonUtils.extractStringArgument(params, "standardCommand"); //$NON-NLS-1$
        String picture = JsonUtils.extractStringArgument(params, "picture"); //$NON-NLS-1$
        // add_button: optional BSL action handler (wires <action><handler> so a
        // click does something) and optional commandName to reuse an existing
        // form command instead of creating a new one.
        String handler = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        boolean autoGenerateColumns = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "autoGenerateColumns", false); //$NON-NLS-1$
        // Hyperlink flag for Label decorations (addDecoration) and Label fields
        // (addField): renders the element as clickable hyperlink text. Ignored
        // for Picture decorations and non-Label fields.
        boolean hyperlink = JsonUtils.extractBooleanArgument(params, "hyperlink", false); //$NON-NLS-1$
        // dryRun preview: run the operation inside the BM transaction and roll it
        // back without persisting Form.form. The unified edit_metadata form ops
        // (add_field / add_button / ...) delegate here and forward dryRun in
        // params; honour it so a preview leaves no garbage on disk (1.43.x #1).
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        // Validate name for add/remove operations
        if (!OP_HELP.equalsIgnoreCase(operation)
            && (name == null || name.isEmpty()))
        {
            return buildError("'name' parameter is required for operation: " + operation); //$NON-NLS-1$
        }

        // Execute inside BM transaction (dryRun-aware: rolls back + skips persist)
        String error = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) ->
        {
            // Include BaseForm (if any) in the ID scan so that new IDs do not
            // collide with those inherited from the main configuration form.
            Object baseForm = helper.findBaseForm(tx, formFqn);
            helper.resetIdCounter(form, baseForm);

            // Resolve the form's OWNING metadata object (Catalog / Document / ...)
            // so Object.<attr> dataPaths can reach its attributes / tabular
            // sections. The form model object is a BM top object whose eContainer
            // is null, so the owner is resolved from the form FQN via the
            // transaction (not by walking the containment tree).
            Object ownerObject = helper.resolveFormOwnerObject(tx, formFqn);

            // Normalize camelCase -> snake_case so the documented camelCase
            // operations (addField, ...) and snake_case (add_field, ...) both
            // match the switch below. Plain toLowerCase() left "addfield" which
            // matched no snake_case case and hit the unknown-operation error.
            String op = JsonUtils.normalizeOperationToken(operation);

            // 1.42 (B1): block name collisions before mutating the form. The
            // platform crashes on render when two visual elements share a name
            // (RSV 4.2 fix - documented case: header attribute "Организация" +
            // table column "Организация"). Surface a clear error so the agent
            // picks a different name (e.g. parent-table prefixed) instead of
            // shipping a form that crashes 1С client without any log entry.
            if (("add_field".equals(op) || "add_group".equals(op) //$NON-NLS-1$ //$NON-NLS-2$
                || "add_button".equals(op) || "add_table".equals(op) //$NON-NLS-1$ //$NON-NLS-2$
                || "add_decoration".equals(op)) //$NON-NLS-1$
                && helper.isNameUsedAnywhere(form, name))
            {
                return "Error: name '" + name + "' is already used by another " //$NON-NLS-1$ //$NON-NLS-2$
                    + "visual element on this form. Picking the same name for two " //$NON-NLS-1$
                    + "elements (e.g. a header attribute and a table column) crashes " //$NON-NLS-1$
                    + "the 1C client at form render. Use a unique name - the EDT " //$NON-NLS-1$
                    + "wizard prefixes auto-generated columns with the parent table " //$NON-NLS-1$
                    + "name (e.g. ТоварыКоличество instead of Количество)."; //$NON-NLS-1$
            }

            switch (op)
            {
                case "add_field": //$NON-NLS-1$
                    return executeAddField(form, ownerObject, name, title, elementType, dataPath,
                        parentName, beforeName, hyperlink);
                case "add_group": //$NON-NLS-1$
                    return executeAddGroup(form, name, title, elementType,
                        parentName, beforeName);
                case "add_button": //$NON-NLS-1$
                    return executeAddButton(form, name, title,
                        parentName, beforeName, standardCommand, handler, commandName);
                case "add_table": //$NON-NLS-1$
                    return executeAddTable(form, ownerObject, name, title, dataPath,
                        parentName, beforeName, autoGenerateColumns);
                case "add_decoration": //$NON-NLS-1$
                    return executeAddDecoration(form, name, title, elementType,
                        parentName, beforeName, picture, projectName, hyperlink);
                case "remove_item": //$NON-NLS-1$
                    return executeRemoveItem(form, name);
                default:
                    return "Error: " + TextSuggest.invalidValue("operation", operation, //$NON-NLS-1$ //$NON-NLS-2$
                        java.util.Arrays.asList("addField", "addGroup", "addButton", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            "addTable", "addDecoration", "removeItem", "help")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
        });

        // If executeFormOperation returned an error string
        if (error != null && error.startsWith("Error:")) //$NON-NLS-1$
        {
            return buildError(error);
        }

        // If the transaction action returned an error
        if (error != null && !error.isEmpty())
        {
            // Check if it's actually an error or a success message
            if (error.startsWith("Error:")) //$NON-NLS-1$
            {
                return buildError(error);
            }
            // It's a success message from the action
            return error;
        }

        // Default success
        return buildSuccess(projectName, formFqn, operation, name, title, elementType,
            dataPath, parentName);
    }

    // -----------------------------------------------------------------------
    // Operation implementations
    // -----------------------------------------------------------------------

    private String executeAddField(Object form, Object ownerObject, String name, String title,
        String fieldType, String dataPath, String parentName, String beforeName,
        boolean hyperlink)
        throws Exception
    {
        // 1.43.x forms-completeness: when no explicit elementType is given and the
        // field binds to a Boolean attribute, render it as a CheckBoxField - the EDT
        // wizard (and our column auto-generator, generateColumnsForTable) do the same.
        // Fail-safe: only override when the attribute positively resolves to Boolean;
        // any resolve miss keeps the InputField default (no regression - pass
        // elementType to force a kind).
        boolean autoFieldKind = false;
        if ((fieldType == null || fieldType.isEmpty())
            && dataPath != null && !dataPath.isEmpty())
        {
            Object boundAttr = resolveBoundAttribute(form, ownerObject, dataPath);
            if (boundAttr != null && isBooleanTyped(boundAttr))
            {
                fieldType = "CheckBoxField"; //$NON-NLS-1$
                autoFieldKind = true;
            }
        }
        if (fieldType == null || fieldType.isEmpty())
        {
            fieldType = "InputField"; //$NON-NLS-1$
        }
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object field = helper.createFormField(name, title, fieldType, hyperlink);

        if (dataPath != null && !dataPath.isEmpty())
        {
            helper.setDataPath(field, dataPath);
        }

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, field, beforeName);
        }
        else
        {
            helper.addToContainer(container, field);
        }

        // 1.43.x forms-completeness: a FormField placed INSIDE a Table is a column.
        // createFormField alone leaves it half-built (no contextMenu / editMode /
        // showInHeader / footer / populated InputFieldExtInfo) so the column is not
        // visible in the header and not editable at runtime, though EDT validates 0
        // errors. Apply the Designer column properties when the parent is a Table.
        boolean isTableColumn = helper.isTable(container);
        if (isTableColumn)
        {
            helper.applyTableColumnDefaults(field, fieldType);
        }

        // The hyperlink flag is honored only for Label fields (LabelFieldExtInfo
        // is the sole field ext-info exposing setHyperlink). Surface an
        // "ignored" note for other field types so a mis-applied flag is visible.
        boolean isLabelField = fieldType.toLowerCase().contains("label"); //$NON-NLS-1$
        String hyperlinkLine = !hyperlink ? "" //$NON-NLS-1$
            : (isLabelField ? "- Hyperlink: true\n" //$NON-NLS-1$
                : "- Hyperlink: ignored (only Label fields can be hyperlinks)\n"); //$NON-NLS-1$

        return buildSuccess("edit_form", name, "add_field", //$NON-NLS-1$ //$NON-NLS-2$
            "Field '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Type: " + fieldType //$NON-NLS-1$
                + (autoFieldKind ? " (auto-selected from Boolean attribute)" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + "\n" + //$NON-NLS-1$
            (dataPath != null ? "- DataPath: " + dataPath + "\n" : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            hyperlinkLine +
            (isTableColumn
                ? "- TableColumn: Designer column defaults applied (contextMenu / editMode / " //$NON-NLS-1$
                    + "showInHeader / showInFooter / InputFieldExtInfo)\n" //$NON-NLS-1$
                : "") +
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddGroup(Object form, String name, String title,
        String groupType, String parentName, String beforeName) throws Exception
    {
        if (groupType == null || groupType.isEmpty())
        {
            groupType = "UsualGroup"; //$NON-NLS-1$
        }
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object group = helper.createFormGroup(name, title, groupType);

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, group, beforeName);
        }
        else
        {
            helper.addToContainer(container, group);
        }

        return buildSuccess("edit_form", name, "add_group", //$NON-NLS-1$ //$NON-NLS-2$
            "Group '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Type: " + groupType + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddButton(Object form, String name, String title,
        String parentName, String beforeName, String standardCommand,
        String handler, String existingCommandName) throws Exception
    {
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        // 1.42 (RSV 4.2 parity, B3): when bound to a platform stock command,
        // verify the form's owner kind supports it before any write hits disk.
        // Documents / catalogs / registers / reports / business processes /
        // tasks accept stock commands; data processors and external data
        // processors / reports do not - the platform creates the button but
        // never renders it (the "silent button" bug).
        boolean useStandardCommand = standardCommand != null && !standardCommand.isEmpty();
        if (useStandardCommand)
        {
            String ownerType = readFormOwnerEClassName(form);
            String compatErr = StandardCommandRegistry
                .checkOwnerKindCompatibility(ownerType, standardCommand);
            if (compatErr != null)
            {
                return buildError(compatErr);
            }
        }

        Object command = null;
        String commandName;
        boolean reusedCommand = false;
        boolean actionWired = false;
        if (useStandardCommand)
        {
            commandName = StandardCommandRegistry.buildStandardCommandFqn(standardCommand);
        }
        else if (existingCommandName != null && !existingCommandName.isEmpty())
        {
            // #3(C): bind the button to an EXISTING form command instead of
            // silently creating a duplicate (the old code ignored commandName
            // and always made <name>Command).
            command = helper.findFormCommandByName(form, existingCommandName);
            if (command == null)
            {
                return buildError("commandName '" + existingCommandName //$NON-NLS-1$
                    + "' not found on the form. Existing form commands: " //$NON-NLS-1$
                    + helper.listFormCommandNames(form)
                    + ". Omit commandName to create a new command."); //$NON-NLS-1$
            }
            commandName = existingCommandName;
            reusedCommand = true;
            if (handler != null && !handler.isEmpty())
            {
                helper.setFormCommandAction(command, handler);
                actionWired = true;
            }
        }
        else
        {
            // Name the auto-created command by the button's action, not with a
            // "Command" suffix (which reads as machine-generated). Form items and
            // form commands live in separate name spaces, so a button and its
            // command may share the name - matching the clean names EDT produces.
            commandName = name;
            command = helper.createFormCommand(commandName, title);
            // #3(B): wire a BSL action handler when provided so the button does
            // something on click; otherwise the command stays actionless and the
            // user attaches one later via add_command_handler.
            if (handler != null && !handler.isEmpty())
            {
                helper.setFormCommandAction(command, handler);
                actionWired = true;
            }
        }
        // A11 fix: a button bound to a FormCommand inherits the command's title
        // (EDT Designer default - a freshly added command button has an empty
        // title and renders the command's synonym). The title param names the
        // auto-created command; the button's own title stays empty so it follows
        // the command instead of freezing a private copy. A per-button override
        // is an explicit set_form_item_property title call.
        boolean titleOnCommand = !useStandardCommand && !reusedCommand;
        Object button = helper.createButton(name, null);

        if (useStandardCommand)
        {
            // Bind the button to the stock command via reflection - the
            // platform exposes the command list lazily, so a setter that
            // accepts the FQN string is the most portable path. Fall back to
            // surfaceless tag when the EDT runtime rejects it.
            String linkErr = bindStandardCommandToButton(button, standardCommand);
            if (linkErr != null)
            {
                return buildError(linkErr);
            }
        }
        else
        {
            helper.linkButtonToCommand(button, command);
            if (!reusedCommand)
            {
                helper.addCommandToForm(form, command);
            }
        }

        Object container = resolveContainer(form, parentName);
        // #3(A) FIX: a command button targeted at a TABLE must live in the
        // table's AutoCommandBar - a Button placed directly in the table's
        // getItems() is an "Unsupported child element type" that breaks the form
        // at load. The previous code probed getCommandBar(), which does NOT
        // exist on CommandBarHolder (the real accessor is getAutoCommandBar()),
        // so the delegation silently never happened and the button was left on
        // the table itself. getOrCreateAutoCommandBar creates the bar when the
        // table has none yet.
        String delegatedTo = null;
        if (parentName != null && !parentName.isEmpty() && helper.isTable(container))
        {
            Object commandBar = helper.getOrCreateAutoCommandBar(container, parentName);
            if (commandBar != null)
            {
                delegatedTo = parentName + "КоманднаяПанель"; //$NON-NLS-1$
                container = commandBar;
            }
        }

        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, button, beforeName);
        }
        else
        {
            helper.addToContainer(container, button);
        }

        StringBuilder body = new StringBuilder();
        body.append("Button '").append(name).append("' added to form successfully.\n"); //$NON-NLS-1$ //$NON-NLS-2$
        body.append("- Name: ").append(name).append('\n'); //$NON-NLS-1$
        if (titleOnCommand)
        {
            body.append("- Title: ").append(title).append(" (on command; button inherits)\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else
        {
            body.append("- Title: inherited from command '").append(commandName).append('\''); //$NON-NLS-1$
            if (title != null && !title.isEmpty())
            {
                body.append(" (title param ignored; use set_form_item_property to override)"); //$NON-NLS-1$
            }
            body.append('\n');
        }
        body.append("- Command: ").append(commandName) //$NON-NLS-1$
            .append(reusedCommand ? " (existing)\n" : "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (useStandardCommand)
        {
            body.append("- standardCommand: ").append(standardCommand); //$NON-NLS-1$
            body.append(StandardCommandRegistry.hasAutoIcon(standardCommand)
                ? " (auto-icon)\n" : " (no auto-icon - set picture manually)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (actionWired)
        {
            body.append("- action handler: ").append(handler).append('\n'); //$NON-NLS-1$
            body.append("  (add the &НаКлиенте Процедура ").append(handler) //$NON-NLS-1$
                .append("(Команда) body to the form module via write_module_source)\n"); //$NON-NLS-1$
        }
        else if (reusedCommand)
        {
            // The reused command keeps whatever action it already has (e.g. wired
            // by a prior add_command_handler); this op did not touch it, so do not
            // claim "none yet" - that would mislead the agent into re-wiring.
            body.append("- action: retained on reused command '").append(commandName).append("'\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (!useStandardCommand)
        {
            body.append("- action: none yet (use add_command_handler to wire a BSL handler)\n"); //$NON-NLS-1$
        }
        body.append("- Parent: ").append(parentName != null ? parentName : "root"); //$NON-NLS-1$ //$NON-NLS-2$
        if (delegatedTo != null)
        {
            body.append('\n').append("- delegatedToContainer: ").append(delegatedTo); //$NON-NLS-1$
        }
        return buildSuccess("edit_form", name, "add_button", body.toString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddTable(Object form, Object ownerObject, String name, String title,
        String dataPath, String parentName, String beforeName, boolean autoGenerateColumns)
        throws Exception
    {
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object table = helper.createTable(name, title);
        // Give the table the wizard default set (command bar, context menu, ~24
        // behaviour props) so it renders in the WYSIWYG editor - a bare table
        // opens blank. Applied before columns so bar/menu ids precede column ids.
        helper.applyTableRenderDefaults(table, name);

        if (dataPath != null && !dataPath.isEmpty())
        {
            helper.setDataPath(table, dataPath);
        }

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, table, beforeName);
        }
        else
        {
            helper.addToContainer(container, table);
        }

        // 1.42 (RSV 4.2 parity): auto-generate FormField columns for every
        // attribute of the underlying tabular section / form attribute. The
        // EDT wizard does the same when the user drops a tabular section
        // onto a form. Names are prefixed with the parent table name so
        // they cannot collide with header attributes (the B1 render-crash
        // case).
        java.util.List<String> generatedColumns = new java.util.ArrayList<>();
        java.util.List<String> autoGenWarnings = new java.util.ArrayList<>();
        if (autoGenerateColumns && dataPath != null && !dataPath.isEmpty())
        {
            generateColumnsForTable(form, ownerObject, table, name, dataPath, generatedColumns,
                autoGenWarnings);
        }

        StringBuilder body = new StringBuilder();
        body.append("Table '").append(name).append("' added to form successfully.\n"); //$NON-NLS-1$ //$NON-NLS-2$
        body.append("- Name: ").append(name).append('\n'); //$NON-NLS-1$
        body.append("- Title: ").append(title).append('\n'); //$NON-NLS-1$
        if (dataPath != null)
        {
            body.append("- DataPath: ").append(dataPath).append('\n'); //$NON-NLS-1$
        }
        body.append("- Parent: ").append(parentName != null ? parentName : "root"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!generatedColumns.isEmpty())
        {
            body.append('\n').append("- Generated columns: ").append(generatedColumns.size()) //$NON-NLS-1$
                .append(" (").append(String.join(", ", generatedColumns)).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (!autoGenWarnings.isEmpty())
        {
            body.append('\n').append("- autoGenerateColumns warning: ") //$NON-NLS-1$
                .append(autoGenWarnings.get(0));
        }
        return buildSuccess("edit_form", name, "add_table", body.toString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.42 helper: resolves the column source from {@code dataPath}, walks the
     * source's attribute list and creates a FormField for each entry inside
     * the freshly-created table. The field name is prefixed with the parent
     * table name (e.g. {@code ТоварыКоличество} instead of
     * {@code Количество}) to prevent collisions with header attributes -
     * matches the EDT wizard behaviour and closes the B1 render crash.
     *
     * <p>Resolution covers two cases that the EDT wizard handles:
     * <ul>
     *   <li>{@code Object.<TS>} - tabular section on the form's owner object
     *       (e.g. {@code Object.Товары} on a Document form).</li>
     *   <li>{@code <FormAttr>} - form attribute of type ValueTable / form
     *       data collection.</li>
     * </ul>
     * Other paths are unresolved - the caller still gets the empty table and
     * a warning so the agent can decide whether to fall back to manual
     * addField calls.
     */
    private void generateColumnsForTable(Object form, Object ownerObject, Object table,
        String tableName, String dataPath, java.util.List<String> generated,
        java.util.List<String> warnings)
    {
        try
        {
            java.util.List<ColumnSpec> columns =
                resolveColumnsFromDataPath(form, ownerObject, dataPath);
            if (columns == null || columns.isEmpty())
            {
                warnings.add("could not resolve columns from dataPath '" + dataPath //$NON-NLS-1$
                    + "' - keep the empty table and add fields manually via addField"); //$NON-NLS-1$
                return;
            }
            for (ColumnSpec col : columns)
            {
                String fieldName = tableName + col.name;
                if (helper.isNameUsedAnywhere(form, fieldName))
                {
                    // Already taken (e.g. partial re-run of autogen). Skip
                    // rather than fail - the agent gets a warning per entry.
                    warnings.add("column '" + col.name + "' skipped: '" + fieldName //$NON-NLS-1$ //$NON-NLS-2$
                        + "' is already used on the form"); //$NON-NLS-1$
                    continue;
                }
                String fieldType = col.isBoolean ? "CheckBoxField" : "InputField"; //$NON-NLS-1$ //$NON-NLS-2$
                Object field = helper.createFormField(fieldName, col.name, fieldType);
                helper.setDataPath(field, dataPath + "." + col.name); //$NON-NLS-1$
                helper.addToContainer(table, field);
                // A generated column is a FormField inside a Table - apply the same
                // Designer column defaults (contextMenu / editMode / showInHeader /
                // footer / InputFieldExtInfo) that single add_field-into-table gets
                // (c42224e). Without this, autoGenerated columns are half-built:
                // invisible header / not editable at runtime though EDT validates 0.
                helper.applyTableColumnDefaults(field, fieldType);
                generated.add(col.name);
            }
        }
        catch (Exception e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            warnings.add("autogen failed: " + (cause.getMessage() != null //$NON-NLS-1$
                ? cause.getMessage() : cause.getClass().getSimpleName()));
        }
    }

    /**
     * 1.42 helper: column descriptor for autogen. {@code isBoolean} drives the
     * CheckBoxField / InputField choice so booleans render as a checkbox by
     * default - the EDT wizard does the same for tabular section attributes
     * of type Boolean.
     */
    private static final class ColumnSpec
    {
        final String name;
        final boolean isBoolean;

        ColumnSpec(String name, boolean isBoolean)
        {
            this.name = name;
            this.isBoolean = isBoolean;
        }
    }

    /**
     * 1.42 helper: walks {@code dataPath} (Object.X / FormAttr) and returns
     * the list of {@link ColumnSpec} entries from the attribute collection
     * of the resolved tabular section / form attribute. Reflection-based to
     * avoid hard EDT dependencies; returns {@code null} on resolve miss.
     */
    @SuppressWarnings("rawtypes")
    private java.util.List<ColumnSpec> resolveColumnsFromDataPath(Object form, Object ownerObject,
        String dataPath)
        throws Exception
    {
        Object source = null;
        if (dataPath.startsWith("Object.")) //$NON-NLS-1$
        {
            String tsName = dataPath.substring("Object.".length()); //$NON-NLS-1$
            // The form model object's eContainer is null (BM top object); use the
            // owner Catalog / Document resolved from the form FQN instead.
            Object ownerMdo = ownerObject;
            if (ownerMdo == null)
            {
                return null;
            }
            try
            {
                Object tabSections = ownerMdo.getClass()
                    .getMethod("getTabularSections").invoke(ownerMdo); //$NON-NLS-1$
                source = findNamedInIterable(tabSections, tsName);
            }
            catch (NoSuchMethodException nsme)
            {
                return null;
            }
        }
        else
        {
            // Treat as a form attribute name.
            try
            {
                Object attrs = form.getClass().getMethod("getAttributes").invoke(form); //$NON-NLS-1$
                source = findNamedInIterable(attrs, dataPath);
            }
            catch (NoSuchMethodException nsme)
            {
                return null;
            }
        }
        if (source == null)
        {
            return null;
        }
        java.util.List<ColumnSpec> result = new java.util.ArrayList<>();
        // Tabular sections expose getAttributes(); form attributes expose
        // getColumns(). Try both - the first one with results wins.
        for (String accessor : new String[] { "getAttributes", "getColumns" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                Object cols = source.getClass().getMethod(accessor).invoke(source);
                if (cols instanceof Iterable)
                {
                    for (Object col : (Iterable) cols)
                    {
                        try
                        {
                            String n = (String) col.getClass().getMethod("getName").invoke(col); //$NON-NLS-1$
                            if (n == null || n.isEmpty())
                            {
                                continue;
                            }
                            result.add(new ColumnSpec(n, isBooleanColumn(col)));
                        }
                        catch (Exception ignored)
                        {
                            // Column without a name - skip.
                        }
                    }
                    if (!result.isEmpty())
                    {
                        return result;
                    }
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // Try next accessor.
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 1.42 helper: probes the column's type description for a Boolean type
     * marker so we can render the column as a CheckBoxField. Reflection-only
     * - returns false on any probe miss (the caller defaults to InputField).
     */
    private static boolean isBooleanColumn(Object col)
    {
        return isBooleanTyped(col);
    }

    /**
     * 1.43.x forms-completeness: true when the attribute's type description
     * contains a Boolean type. Tries {@code getType()} (metadata / tabular-section
     * attributes, table columns) then {@code getValueType()} (top-level form
     * attributes). The EList of mcore.Type is matched on the {@code toString()}
     * Boolean marker - accurate enough without locking to a specific EDT API
     * surface. Reflection only; returns false on any probe miss (caller defaults
     * to InputField).
     */
    private static boolean isBooleanTyped(Object attr)
    {
        for (String accessor : new String[] { "getType", "getValueType" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                Object typeDesc = attr.getClass().getMethod(accessor).invoke(attr);
                if (typeDesc == null)
                {
                    continue;
                }
                // This accessor yielded the type description - decide from it and
                // stop. A non-null but non-Boolean getType() must NOT fall through
                // to getValueType() (which could surface a different, Boolean-looking
                // type on a multi-typed form attribute = false positive).
                Object types = typeDesc.getClass().getMethod("getTypes").invoke(typeDesc); //$NON-NLS-1$
                if (types instanceof Iterable)
                {
                    for (Object t : (Iterable<?>) types)
                    {
                        if (String.valueOf(t).toLowerCase().contains("boolean")) //$NON-NLS-1$
                        {
                            return true;
                        }
                    }
                }
                return false;
            }
            catch (Exception ignored)
            {
                // Accessor absent / threw - try the next one (default to InputField).
            }
        }
        return false;
    }

    /**
     * 1.43.x forms-completeness: resolves the single attribute backing a field's
     * {@code dataPath} so add_field can auto-pick the field kind (Boolean ->
     * CheckBoxField), mirroring the EDT wizard and {@link #generateColumnsForTable}.
     * Walks dotted segments uniformly: {@code Object.<attr>},
     * {@code Object.<TS>.<attr>}, {@code <FormAttr>}, {@code <FormAttr>.<col>}.
     * The {@code Object} root maps to the form's owner metadata object (eContainer);
     * any other root to a top-level form attribute. Standard attributes (e.g.
     * {@code Object.Posted}) are not in getAttributes() and resolve to null ->
     * InputField (no regression). Reflection only; returns {@code null} on any miss.
     */
    private static Object resolveBoundAttribute(Object form, Object ownerObject, String dataPath)
    {
        try
        {
            // split on a non-empty string always yields >= 1 segment, so seg[0]
            // is safe (the caller already guarded dataPath non-empty).
            String[] seg = dataPath.split("\\."); //$NON-NLS-1$
            Object current;
            if ("Object".equalsIgnoreCase(seg[0])) //$NON-NLS-1$
            {
                if (seg.length == 1 || ownerObject == null)
                {
                    // Bare "Object" is not a field binding, and a null owner
                    // (CommonForm / resolve miss) cannot be walked.
                    return null;
                }
                current = ownerObject; // the form's Catalog / Document / ... owner
            }
            else
            {
                Object attrs = form.getClass().getMethod("getAttributes").invoke(form); //$NON-NLS-1$
                current = findNamedInIterable(attrs, seg[0]);
            }
            for (int i = 1; i < seg.length && current != null; i++)
            {
                current = findChildNamed(current, seg[i]);
            }
            return current;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * 1.43.x helper: finds a named child (attribute / column / tabular section)
     * of {@code container} by probing the common EDT accessors in turn. Reflection
     * only; returns {@code null} on miss.
     */
    private static Object findChildNamed(Object container, String name)
    {
        for (String accessor : new String[] { "getAttributes", "getColumns", //$NON-NLS-1$ //$NON-NLS-2$
            "getTabularSections" }) //$NON-NLS-1$
        {
            try
            {
                Object kids = container.getClass().getMethod(accessor).invoke(container);
                Object hit = findNamedInIterable(kids, name);
                if (hit != null)
                {
                    return hit;
                }
            }
            catch (Exception ignored)
            {
                // Try the next accessor.
            }
        }
        return null;
    }

    /**
     * 1.42 helper: finds the first NamedElement whose {@code getName()}
     * matches {@code name} (case-insensitive) inside an EList / Iterable.
     */
    private static Object findNamedInIterable(Object iterable, String name)
    {
        if (!(iterable instanceof Iterable))
        {
            return null;
        }
        for (Object e : (Iterable<?>) iterable)
        {
            try
            {
                String n = (String) e.getClass().getMethod("getName").invoke(e); //$NON-NLS-1$
                if (n != null && n.equalsIgnoreCase(name))
                {
                    return e;
                }
            }
            catch (Exception ignored)
            {
                // Skip unnamed entries.
            }
        }
        return null;
    }

    private String executeAddDecoration(Object form, String name, String title,
        String decorationType, String parentName, String beforeName,
        String picture, String projectNameForPicture, boolean hyperlink) throws Exception
    {
        if (decorationType == null || decorationType.isEmpty())
        {
            decorationType = "Label"; //$NON-NLS-1$
        }
        if (title == null || title.isEmpty())
        {
            title = name;
        }
        // 1.42: validate picture reference up-front when this is a Picture
        // decoration. PictureValidator handles StdPicture / StdExtPicture
        // (against the platform registry) and CommonPicture (against the
        // configuration). Mirrors the same check used by createObjectCommand
        // and setFormItemProperty.
        boolean isPictureDecoration = "Picture".equalsIgnoreCase(decorationType); //$NON-NLS-1$
        if (isPictureDecoration && picture != null && !picture.isEmpty())
        {
            String pictureError = ru.aiedt.mcp.server.support.PictureValidator
                .validate(projectNameForPicture, picture);
            if (pictureError != null)
            {
                return buildError(pictureError);
            }
        }

        Object decoration = isPictureDecoration
            ? helper.createDecoration(name, title, decorationType, picture)
            : helper.createDecoration(name, title, decorationType, null, hyperlink);

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, decoration, beforeName);
        }
        else
        {
            helper.addToContainer(container, decoration);
        }

        StringBuilder body = new StringBuilder();
        body.append("Decoration '").append(name).append("' added to form successfully.\n"); //$NON-NLS-1$ //$NON-NLS-2$
        body.append("- Name: ").append(name).append('\n'); //$NON-NLS-1$
        body.append("- Title: ").append(title).append('\n'); //$NON-NLS-1$
        body.append("- Type: ").append(decorationType).append('\n'); //$NON-NLS-1$
        if (isPictureDecoration && picture != null && !picture.isEmpty())
        {
            body.append("- Picture: ").append(picture).append('\n'); //$NON-NLS-1$
        }
        if (hyperlink)
        {
            body.append(isPictureDecoration
                ? "- Hyperlink: ignored (only Label decorations can be hyperlinks)\n" //$NON-NLS-1$
                : "- Hyperlink: true\n"); //$NON-NLS-1$
        }
        body.append("- Parent: ").append(parentName != null ? parentName : "root"); //$NON-NLS-1$ //$NON-NLS-2$
        return buildSuccess("edit_form", name, "add_decoration", body.toString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeRemoveItem(Object form, String name) throws Exception
    {
        boolean removed = helper.removeItemByName(form, name);
        if (!removed)
        {
            return buildError("Error: Element not found: " + name); //$NON-NLS-1$
        }

        return buildSuccess("edit_form", name, "remove_item", //$NON-NLS-1$ //$NON-NLS-2$
            "Element '" + name + "' removed from form successfully."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Resolves the container for adding elements.
     * If parentName is specified, searches for it recursively in the form.
     * Otherwise returns the form itself (root container).
     *
     * <p>1.42 (B2): also resolves table command bars and context menus by
     * name suffix. EDT stores these as separate properties of the table
     * rather than children of {@code getItems()}, so the regular recursive
     * scan never reaches them. RSV 4.2 fix - {@code addButton} with
     * {@code parent="<TableName>КоманднаяПанель"} now lands the button into
     * {@code table.commandBar} as advertised in {@code get_form_image
     * format=structure}.
     *
     * <p>The virtual {@code ФормаКоманды} / {@code FormCommands} bucket from
     * the structure dump is rejected with a clear message - it is the form's
     * command list, not a UI container.
     */
    private Object resolveContainer(Object form, String parentName) throws Exception
    {
        if (parentName == null || parentName.isEmpty())
        {
            return form;
        }
        if ("ФормаКоманды".equals(parentName) || "FormCommands".equals(parentName)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            throw new RuntimeException("Error: '" + parentName //$NON-NLS-1$
                + "' is the form's virtual command list, not a UI container. " //$NON-NLS-1$
                + "Buttons cannot be placed there. Add the button to a normal " //$NON-NLS-1$
                + "container or to a table's command bar (e.g. " //$NON-NLS-1$
                + "'<TableName>КоманднаяПанель')."); //$NON-NLS-1$
        }
        Object subContainer = resolveTableSubcontainer(form, parentName);
        if (subContainer != null)
        {
            return subContainer;
        }
        Object container = helper.findItemByName(form, parentName);
        if (container == null)
        {
            throw new RuntimeException("Parent container not found: " + parentName); //$NON-NLS-1$
        }
        return container;
    }

    /**
     * 1.42 (B2): if {@code parentName} ends with a known table-subcontainer
     * suffix ({@code КоманднаяПанель}, {@code КонтекстноеМеню},
     * {@code CommandBar}, {@code ContextMenu}), strips the suffix, locates the
     * matching table and returns its command bar / context menu via
     * {@code getCommandBar()} / {@code getContextMenu()} reflection. Returns
     * {@code null} when the name is not a recognised suffix or the table is
     * absent (callers fall back to the regular recursive search).
     */
    private Object resolveTableSubcontainer(Object form, String parentName) throws Exception
    {
        String[][] suffixes = {
            { "КоманднаяПанель", "getCommandBar" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "CommandBar", "getCommandBar" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "КонтекстноеМеню", "getContextMenu" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "ContextMenu", "getContextMenu" } //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] entry : suffixes)
        {
            String suffix = entry[0];
            String accessor = entry[1];
            if (parentName.length() <= suffix.length() || !parentName.endsWith(suffix))
            {
                continue;
            }
            String tableName = parentName.substring(0, parentName.length() - suffix.length());
            Object table = helper.findItemByName(form, tableName);
            if (table == null)
            {
                continue;
            }
            try
            {
                Object sub = table.getClass().getMethod(accessor).invoke(table);
                if (sub == null && "getContextMenu".equals(accessor)) //$NON-NLS-1$
                {
                    // 1.43.x batch 4: the holder has no context menu yet (settable
                    // containment, null until set) - create + attach one so the button
                    // lands in a real menu instead of silently falling back to the root.
                    sub = helper.ensureContextMenu(table);
                }
                if (sub != null)
                {
                    return sub;
                }
            }
            catch (Exception ignored)
            {
                // Accessor absent or threw at runtime - skip and continue.
            }
        }
        return null;
    }

    /**
     * 1.42 (B3): reads the form's owner EClass simple name (e.g. "Document",
     * "ExternalDataProcessor"). The form's owner is the EMF parent in the
     * BM tree. Returns {@code null} when the form has no owner (orphan or
     * common form) or reflection fails.
     */
    private String readFormOwnerEClassName(Object form)
    {
        try
        {
            Object container = form.getClass().getMethod("eContainer").invoke(form); //$NON-NLS-1$
            if (container == null)
            {
                return null;
            }
            Object eClass = container.getClass().getMethod("eClass").invoke(container); //$NON-NLS-1$
            if (eClass == null)
            {
                return null;
            }
            Object name = eClass.getClass().getMethod("getName").invoke(eClass); //$NON-NLS-1$
            return name != null ? name.toString() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * 1.42 (RSV 4.2 parity): binds a button to a platform stock command via
     * reflection. Tries the typed setter first ({@code setCommandName} taking
     * the same {@code Command} interface as a regular form command would),
     * falling back to a String-based variant when the platform exposes one.
     * Returns {@code null} on success, error message on failure.
     */
    private String bindStandardCommandToButton(Object button, String standardCommandName)
    {
        String fqn = StandardCommandRegistry.buildStandardCommandFqn(standardCommandName);
        // The platform represents stock commands as proxies of the same
        // Command interface as ordinary form commands - the button's
        // commandName can therefore be assigned a thin proxy holding the
        // FQN. We try a String-based setter first (matches modern EDT
        // builds), then fall back to setCommandRef for older variants.
        for (String setter : new String[] { "setCommandName", "setCommandRef" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (java.lang.reflect.Method m : button.getClass().getMethods())
            {
                if (!m.getName().equals(setter) || m.getParameterCount() != 1)
                {
                    continue;
                }
                Class<?> paramType = m.getParameterTypes()[0];
                if (paramType == String.class)
                {
                    try
                    {
                        m.invoke(button, fqn);
                        return null;
                    }
                    catch (Exception ignored)
                    {
                        // Try next overload.
                    }
                }
            }
        }
        return "Could not bind the button to '" + fqn + "' on this EDT runtime. " //$NON-NLS-1$ //$NON-NLS-2$
            + "The platform does not expose a String-based commandName setter. " //$NON-NLS-1$
            + "Either upgrade EDT or use a regular form command via " //$NON-NLS-1$
            + "addCommandHandler. Known auto-icon stock commands: " //$NON-NLS-1$
            + StandardCommandRegistry.describeAutoIconCommands(); //$NON-NLS-1$
    }

    private String buildError(String message)
    {
        return YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("status", "error") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(message);
    }

    private String buildSuccess(String tool, String elementName, String operation, String body)
    {
        return YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("operation", operation) //$NON-NLS-1$
            .put("element", elementName) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(body);
    }

    private String buildSuccess(String projectName, String formFqn, String operation,
        String name, String title, String elementType, String dataPath, String parentName)
    {
        StringBuilder body = new StringBuilder();
        body.append("Operation completed successfully.\n"); //$NON-NLS-1$
        body.append("- Operation: ").append(operation).append('\n'); //$NON-NLS-1$
        if (name != null)
        {
            body.append("- Name: ").append(name).append('\n'); //$NON-NLS-1$
        }
        if (title != null)
        {
            body.append("- Title: ").append(title).append('\n'); //$NON-NLS-1$
        }
        if (elementType != null)
        {
            body.append("- Type: ").append(elementType).append('\n'); //$NON-NLS-1$
        }
        if (dataPath != null)
        {
            body.append("- DataPath: ").append(dataPath).append('\n'); //$NON-NLS-1$
        }
        body.append("- Parent: ").append(parentName != null ? parentName : "root").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$

        return YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("operation", operation) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(body.toString());
    }

    private String buildHelpResponse()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# edit_form - Form Element Operations\n\n"); //$NON-NLS-1$

        sb.append("## Common Parameters\n\n"); //$NON-NLS-1$
        sb.append("- **projectName** (required): EDT project name " //$NON-NLS-1$
            + "(for borrowed forms use the extension project name).\n"); //$NON-NLS-1$
        sb.append("- **formFqn** (required): BM top-object FQN of the form. " //$NON-NLS-1$
            + "Must end with '.Form' - this segment comes from the Form.form " //$NON-NLS-1$
            + "file name.\n"); //$NON-NLS-1$
        sb.append("  - Main config: `Catalog.Products.Form.ItemForm.Form`\n"); //$NON-NLS-1$
        sb.append("  - Common form: `CommonForm.MyForm.Form`\n"); //$NON-NLS-1$
        sb.append("  - If the FQN is not found, the error lists matching " //$NON-NLS-1$
            + "FQNs from the BM namespace (useful for borrowed forms where the " //$NON-NLS-1$
            + "canonical FQN may differ from the main configuration).\n\n"); //$NON-NLS-1$

        sb.append("## Preconditions\n\n"); //$NON-NLS-1$
        sb.append("- **Fields with dataPath**: the backing attribute must " //$NON-NLS-1$
            + "already exist on the metadata object. If you are binding to a " //$NON-NLS-1$
            + "new attribute, call `add_metadata_attribute` first - otherwise " //$NON-NLS-1$
            + "EDT will flag the dataPath as `form-data-path` MAJOR error.\n"); //$NON-NLS-1$
        sb.append("- **Extension attributes**: EDT prefixes attributes added " //$NON-NLS-1$
            + "in extensions with the extension's `namePrefix` (e.g. adding " //$NON-NLS-1$
            + "attribute `Price` in extension with prefix `Ais_` results in " //$NON-NLS-1$
            + "`Ais_Price`). Use the prefixed name in dataPath: " //$NON-NLS-1$
            + "`Object.Ais_Price`.\n"); //$NON-NLS-1$
        sb.append("- **BaseForm awareness**: borrowed forms only expose " //$NON-NLS-1$
            + "override items via `getItems()`; the tool automatically scans " //$NON-NLS-1$
            + "the `.BaseForm` top-object for ID collision avoidance.\n"); //$NON-NLS-1$
        sb.append("- **Persistence**: changes are written to the `.form` " //$NON-NLS-1$
            + "file via `forceExport` after each operation - no separate save " //$NON-NLS-1$
            + "step is required.\n\n"); //$NON-NLS-1$

        sb.append("## Operations\n\n"); //$NON-NLS-1$

        sb.append("### addField\n"); //$NON-NLS-1$
        sb.append("Add a field element to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display caption\n"); //$NON-NLS-1$
        sb.append("- **elementType**: InputField (default), CheckBox, RadioButton, Label, Image\n"); //$NON-NLS-1$
        sb.append("- **hyperlink**: render a Label field as a clickable hyperlink (true/false)\n"); //$NON-NLS-1$
        sb.append("- **dataPath**: Data binding path (e.g. 'Object.Name')\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container (default: root form)\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addGroup\n"); //$NON-NLS-1$
        sb.append("Add a group element to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display caption\n"); //$NON-NLS-1$
        sb.append("- **elementType**: UsualGroup (default), Pages, Page, Column, CommandBar\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addButton\n"); //$NON-NLS-1$
        sb.append("Add a button with linked command.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Button name (command: name + 'Command')\n"); //$NON-NLS-1$
        sb.append("- **title**: Button caption\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addTable\n"); //$NON-NLS-1$
        sb.append("Add a table element to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display caption\n"); //$NON-NLS-1$
        sb.append("- **dataPath**: Data binding path (e.g. 'Object.Products')\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addDecoration\n"); //$NON-NLS-1$
        sb.append("Add a decoration (label/picture) to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display text\n"); //$NON-NLS-1$
        sb.append("- **elementType**: Label (default) or Picture\n"); //$NON-NLS-1$
        sb.append("- **hyperlink**: render a Label decoration as a clickable hyperlink (true/false)\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### removeItem\n"); //$NON-NLS-1$
        sb.append("Remove an element by name.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name to remove\n\n"); //$NON-NLS-1$

        sb.append("## Examples\n\n"); //$NON-NLS-1$
        sb.append("```json\n"); //$NON-NLS-1$
        sb.append("// Add an input field bound to Object.Price\n"); //$NON-NLS-1$
        sb.append("{\n"); //$NON-NLS-1$
        sb.append("  \"projectName\": \"MyConfig\",\n"); //$NON-NLS-1$
        sb.append("  \"formFqn\": \"Catalog.Products.Form.ItemForm.Form\",\n"); //$NON-NLS-1$
        sb.append("  \"operation\": \"addField\",\n"); //$NON-NLS-1$
        sb.append("  \"name\": \"FieldPrice\",\n"); //$NON-NLS-1$
        sb.append("  \"title\": \"Price\",\n"); //$NON-NLS-1$
        sb.append("  \"elementType\": \"InputField\",\n"); //$NON-NLS-1$
        sb.append("  \"dataPath\": \"Object.Price\"\n"); //$NON-NLS-1$
        sb.append("}\n"); //$NON-NLS-1$
        sb.append("```\n"); //$NON-NLS-1$

        return YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("operation", OP_HELP) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(sb.toString());
    }
}
