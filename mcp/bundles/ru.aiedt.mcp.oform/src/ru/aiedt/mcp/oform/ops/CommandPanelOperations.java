/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.List;

import ru.aiedt.mcp.oform.layout.CommandPanels;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.StandardActions;

/**
 * Command panels: the panel itself with its source of actions, its buttons - a procedure, a
 * standard action, a picture - its submenus and separators, and the buttons it loses. The
 * records the operations write are the templates of {@link CommandPanels}; the standard actions
 * a button can run are the registry {@link StandardActions}.
 */
public final class CommandPanelOperations
{
    private CommandPanelOperations()
    {
        // static catalog
    }

    /** @return the specs of this catalog */
    public static List<OperationSpec> specs()
    {
        List<OperationSpec> out = new ArrayList<>();
        out.add(OperationSpec.layout("add_command_panel", //$NON-NLS-1$
            "name, the box (left/top/right/bottom or width/height), parent and page, actionSource " //$NON-NLS-1$
                + "(a Table of the form) and autoFill (true with a source, false without one); " //$NON-NLS-1$
                + "add_element type=CommandPanel writes the same panel without a source", //$NON-NLS-1$
            List.of(ParamSpec.string("name", "the name of the new panel."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("left", "the left edge."), ParamSpec.integer("top", "the top edge."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.integer("right", "the right edge."), ParamSpec.integer("bottom", "the bottom edge."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.integer("width", "the width, instead of right."), ParamSpec.integer("height", "the height, instead of bottom."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.string("parent", "the Panel to put the panel in; absent for the form."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("page", "the page of the panel or form; absent for the first."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("actionSource", "the Table the panel takes its actions from; absent or none for no source."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("autoFill", "whether the platform fills the panel's buttons itself " //$NON-NLS-1$
                    + "(default true with an actionSource, false without one).")), //$NON-NLS-1$
            CommandPanelOperations::addCommandPanel));
        out.add(OperationSpec.layout("add_button", //$NON-NLS-1$
            "panel + name + caption (a standard action keeps its own text and takes the caption as its " //$NON-NLS-1$
                + "tooltip), and exactly one of procedure (a procedure of the form module, " //$NON-NLS-1$
                + "stub=true adds an empty one) and action (a registered standard action); picture is " //$NON-NLS-1$
                + "the index of the measured dictionary; parent puts the button in a submenu, before " //$NON-NLS-1$
                + "in front of a named button of the same block", //$NON-NLS-1$
            List.of(ParamSpec.string("panel", "the command panel that takes the button."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the button's name."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("caption", "the caption of a procedure button (default the name); a button of a " //$NON-NLS-1$
                    + "standard action keeps the text the platform gives that action and takes the caption as its " //$NON-NLS-1$
                    + "tooltip."), //$NON-NLS-1$
                ParamSpec.string("procedure", "the procedure the button runs (the parameters: Кнопка)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("action", () -> "a standard action: one of " + String.join(", ", StandardActions.names()) + "."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ParamSpec.integer("picture", "the index of the picture dictionary of a standard action (0 to " //$NON-NLS-1$
                    + (CommandPanels.pictureCount() - 1) + "; default the picture the corpus most often writes for the action)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the submenu to put the button in; absent for the panel itself."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("before", "a button of the same block to put the button in front of."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("stub", "add an empty procedure with the parameter Кнопка to the module " //$NON-NLS-1$
                    + "when it declares none (default false).")), //$NON-NLS-1$
            CommandPanelOperations::addButton));
        out.add(OperationSpec.layout("add_submenu", //$NON-NLS-1$
            "panel + name + caption of a submenu, with parent and before as of a button; the submenu " //$NON-NLS-1$
                + "gets an empty buttons block of its own - add_button parent=<submenu> fills it", //$NON-NLS-1$
            List.of(ParamSpec.string("panel", "the command panel that takes the submenu."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the submenu's name."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("caption", "the caption (default the name)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the submenu to put it in; absent for the panel itself."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("before", "a button of the same block to put the submenu in front of.")), //$NON-NLS-1$ //$NON-NLS-2$
            (context) -> OperationResult.of(CommandPanels.addSubmenu(context.tree, language(context), //$NON-NLS-1$
                context.string("panel"), context.string("name"), context.string("caption"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                context.string("parent"), context.string("before"))))); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(OperationSpec.layout("add_separator", //$NON-NLS-1$
            "panel + name of a separator, with parent and before as of a button", //$NON-NLS-1$
            List.of(ParamSpec.string("panel", "the command panel that takes the separator."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the separator's name."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the submenu to put it in; absent for the panel itself."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("before", "a button of the same block to put the separator in front of.")), //$NON-NLS-1$ //$NON-NLS-2$
            (context) -> OperationResult.of(CommandPanels.addSeparator(context.tree, context.string("panel"), //$NON-NLS-1$ //$NON-NLS-2$
                context.string("name"), context.string("parent"), context.string("before"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(OperationSpec.layout("remove_button", //$NON-NLS-1$
            "panel + name: a button, a separator or a submenu with every button under it; the action " //$NON-NLS-1$
                + "records nothing references any more go with it", //$NON-NLS-1$
            List.of(ParamSpec.string("panel", "the command panel that holds the button."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the button to remove.")), //$NON-NLS-1$ //$NON-NLS-2$
            (context) -> OperationResult.of(CommandPanels.removeButton(context.tree, context.string("panel"), //$NON-NLS-1$ //$NON-NLS-2$
                context.string("name"))))); //$NON-NLS-1$
        out.add(OperationSpec.layout("set_action_source", //$NON-NLS-1$
            "panel + table (a Table of the form, or none): sets the source and autoFill=true, or " //$NON-NLS-1$
                + "clears both; autoFill=false keeps the source without the platform filling the buttons", //$NON-NLS-1$
            List.of(ParamSpec.string("panel", "the command panel."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("table", "the Table the panel takes its actions from; none clears the source."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("autoFill", "whether the platform fills the panel's buttons itself " //$NON-NLS-1$
                    + "(default true with a table, false with none).")), //$NON-NLS-1$
            (context) -> {
                String table = context.string("table"); //$NON-NLS-1$
                if (table == null || table.trim().isEmpty())
                {
                    return OperationResult.refused("set_action_source needs table: the Table the panel takes its " //$NON-NLS-1$
                        + "actions from, or none to clear the source."); //$NON-NLS-1$
                }
                return OperationResult.of(CommandPanels.setActionSource(context.tree, context.string("panel"), //$NON-NLS-1$
                    table, Boolean.valueOf(context.bool("autoFill", hasTable(table))))); //$NON-NLS-1$
            }));
        return out;
    }

    private static OperationResult addCommandPanel(LayoutContext context)
    {
        String name = context.string("name"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return OperationResult.refused("add_command_panel needs name."); //$NON-NLS-1$
        }
        Integer left = context.integer("left"); //$NON-NLS-1$
        Integer top = context.integer("top"); //$NON-NLS-1$
        Integer right = context.integer("right"); //$NON-NLS-1$
        Integer bottom = context.integer("bottom"); //$NON-NLS-1$
        Integer width = context.integer("width"); //$NON-NLS-1$
        Integer height = context.integer("height"); //$NON-NLS-1$
        int l = left == null ? 8 : left.intValue();
        int t = top == null ? 8 : top.intValue();
        // the default box of a command panel: one row of buttons, as the corpus holds it
        int r = right != null ? right.intValue() : l + (width != null ? width.intValue() : 400);
        int b = bottom != null ? bottom.intValue() : t + (height != null ? height.intValue() : 25);
        if (r < l || b < t)
        {
            return OperationResult.refused("The box must have its right at or beyond its left and its bottom at or below its top."); //$NON-NLS-1$
        }
        LayoutEditor.Outcome added = context.editor.addElement("CommandPanel", name, context.string("parent"), //$NON-NLS-1$ //$NON-NLS-2$
            context.string("page"), new int[] { l, t, r, b }, null, null, null); //$NON-NLS-1$
        if (!added.done)
        {
            return OperationResult.of(added);
        }
        context.answer.put("box", l + "," + t + "," + r + "," + b); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String source = context.string("actionSource"); //$NON-NLS-1$
        boolean named = source != null && !source.isEmpty() && !"none".equalsIgnoreCase(source); //$NON-NLS-1$
        LayoutEditor.Outcome sourced = CommandPanels.setActionSource(context.tree, name, source, //$NON-NLS-1$
            Boolean.valueOf(context.bool("autoFill", named))); //$NON-NLS-1$
        if (!sourced.done)
        {
            context.editor.removeElement(name);
            return OperationResult.of(sourced);
        }
        return OperationResult.of(added);
    }

    private static OperationResult addButton(LayoutContext context)
    {
        String panel = context.string("panel"); //$NON-NLS-1$
        String name = context.string("name"); //$NON-NLS-1$
        String procedure = context.string("procedure"); //$NON-NLS-1$
        String actionName = context.string("action"); //$NON-NLS-1$
        if (panel == null || panel.isEmpty() || name == null || name.isEmpty())
        {
            return OperationResult.refused("add_button needs panel and name."); //$NON-NLS-1$
        }
        boolean stub = context.bool("stub", false); //$NON-NLS-1$
        StandardActions.Action action = actionName == null || actionName.isEmpty() ? null
            : StandardActions.find(actionName);
        if (actionName != null && !actionName.isEmpty() && action == null)
        {
            return OperationResult.refused("Unknown standard action " + actionName + "; known: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", StandardActions.names()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (procedure != null && !procedure.isEmpty() && !procedure.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return OperationResult.refused("The procedure name must be an identifier: " + procedure); //$NON-NLS-1$
        }
        if (stub && procedure != null && !procedure.isEmpty() && context.file == null)
        {
            return OperationResult.refused("add_button stub=true needs the form's module."); //$NON-NLS-1$
        }
        LayoutEditor.Outcome added = CommandPanels.addButton(context.tree, language(context), panel, name, //$NON-NLS-1$
            context.string("caption"), procedure == null || procedure.isEmpty() ? null : procedure, action, //$NON-NLS-1$
            context.integer("picture"), context.string("parent"), context.string("before")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!added.done || procedure == null || procedure.isEmpty() || !stub)
        {
            return OperationResult.of(added);
        }
        if (context.file == null)
        {
            return OperationResult.refused("add_button stub=true needs the form's module."); //$NON-NLS-1$
        }
        String module = context.file.moduleText();
        boolean declared = module != null && java.util.regex.Pattern
            .compile("(?imu)^\\s*(Процедура|Функция)\\s+" + java.util.regex.Pattern.quote(procedure) + "\\s*\\(") //$NON-NLS-1$ //$NON-NLS-2$
            .matcher(module).find();
        if (declared)
        {
            context.answer.put("procedureDeclared", Boolean.TRUE); //$NON-NLS-1$
            return OperationResult.of(added);
        }
        // the procedure of a panel button takes the button: 4320 of the 4473 measured procedures
        context.answer.put("stub", "Процедура " + procedure + "(Кнопка) added to the module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return OperationResult.withModule(added, CoreOperations.withStub(module, procedure, "Кнопка")); //$NON-NLS-1$
    }

    /** Whether the table argument names a table (not none). */
    private static boolean hasTable(String table)
    {
        return table != null && !table.isEmpty() && !"none".equalsIgnoreCase(table); //$NON-NLS-1$
    }

    private static String language(LayoutContext context)
    {
        String language = context.string("language"); //$NON-NLS-1$
        return language == null || language.isEmpty() ? "ru" : language; //$NON-NLS-1$
    }
}
