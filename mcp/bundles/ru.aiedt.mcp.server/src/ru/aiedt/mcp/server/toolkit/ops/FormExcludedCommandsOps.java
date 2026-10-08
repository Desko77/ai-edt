/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The standard commands a form object keeps out of its command interface, in {@code edit_metadata}.
 * <p>
 * The property belongs to the objects the platform fills with standard commands - the form, a
 * table, a field and the form's global-commands source - and names the commands of that object's own
 * command list which it must not show. A command bar, a context menu and a button group keep no
 * list of their own: they take their commands from a source, and an address naming one of them is
 * refused with the address of that source.
 * </p>
 * <p>
 * The form model is reached by reflection, the way the neighbouring form operations reach it: its
 * package is an optional import of this bundle, so naming its types here would make this class
 * unloadable on an install without it.
 * </p>
 */
final class FormExcludedCommandsOps
{
    /** The ways a call may change the list. */
    static final List<String> MODES = List.of("replace", "add", "remove"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The address that names the form itself. */
    private static final String FORM_ADDRESS = "Form"; //$NON-NLS-1$

    /** The address that names the form's global standard command source. */
    private static final String GLOBAL_ADDRESS = "FormCommandPanelGlobalCommands"; //$NON-NLS-1$

    /** The prefix an address gives a form item. */
    private static final String ITEM_PREFIX = "Item."; //$NON-NLS-1$

    /**
     * Sets the standard commands a form object keeps out of its command interface.
     * <p>
     * The object is named by {@code itemPath}: the form itself (empty or {@code Form}), the form's
     * global-commands source ({@code FormCommandPanelGlobalCommands}) or a form item
     * ({@code Item.<name>}, or the bare name of the item). Every name is checked against that
     * object's own standard command list before anything is written, and a name the list does not
     * hold refuses the call with the names it does hold. {@code mode} is {@code replace} (the
     * default - the list becomes exactly the names passed, and an empty {@code commands} clears it),
     * {@code add} or {@code remove}. A call whose result equals the list already there reports
     * {@code changed: false} and writes nothing.
     * </p>
     *
     * @param params projectName, formFqn, itemPath, commands, mode and dryRun
     * @return the answer: the final list, the names added and removed, and whether anything changed
     */
    String opSetExcludedCommands(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemPath = JsonUtils.extractStringArgument(params, "itemPath"); //$NON-NLS-1$
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        List<String> commands = JsonUtils.extractArrayArgument(params, "commands"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String wantedMode = mode == null || mode.trim().isEmpty()
            ? "replace" : mode.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if (!MODES.contains(wantedMode))
        {
            err = err + TextSuggest.invalidValue("mode", mode, MODES) + " "; //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<String> names = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
        if (names.isEmpty() && !"replace".equals(wantedMode)) //$NON-NLS-1$
        {
            err = err + "mode=" + wantedMode + " needs at least one name in commands. "; //$NON-NLS-1$ //$NON-NLS-2$
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

        // Read first: a refusal has to name the commands the object allows, and a call that changes
        // nothing must not open a write transaction at all - that one exports the form file.
        List<Plan> read = new ArrayList<>();
        String outcome = helper.executeFormReadOperation(project, formFqn, (tx, form) -> {
            read.add(plan(form, itemPath, names, wantedMode));
            return null;
        });
        if (outcome != null && outcome.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(outcome.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        if (read.isEmpty())
        {
            return ToolResult.error("the form could not be read").toJson(); //$NON-NLS-1$
        }
        Plan proposed = read.get(0);
        if (proposed.refusal != null)
        {
            return refusal(proposed.refusal, proposed.available).toJson();
        }
        if (!proposed.changed)
        {
            return answer(formFqn, proposed, dryRun).toJson();
        }

        // The write plans again inside its own transaction: the object and its command list belong
        // to that transaction, and a form another call changed in between is judged as it now is.
        List<Plan> written = new ArrayList<>();
        String applied = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) -> {
            Plan fresh = plan(form, itemPath, names, wantedMode);
            if (fresh.refusal != null)
            {
                return "Error: " + fresh.refusal; //$NON-NLS-1$
            }
            String failure = applyPlan(fresh);
            if (failure != null)
            {
                return "Error: " + failure; //$NON-NLS-1$
            }
            written.add(fresh);
            return null;
        });
        if (applied != null && applied.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(applied.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        return withWriteNote(answer(formFqn, written.isEmpty() ? proposed : written.get(0), dryRun),
            applied).toJson();
    }

    /**
     * Adds what the form write said about reaching the disk to a successful answer.
     * <p>
     * The model change is committed before the form file is exported, and the export can time out or
     * fail on its own. The write then returns a note rather than an error, and an answer without it
     * would read as a file that carries the exclusions.
     * </p>
     *
     * @param result the successful answer
     * @param writeNote what the form write returned; <code>null</code> or empty when it had nothing to say
     * @return the same answer, with {@code persistNote} when there is a note
     */
    static ToolResult withWriteNote(ToolResult result, String writeNote)
    {
        if (writeNote != null && !writeNote.trim().isEmpty())
        {
            result.put("persistNote", writeNote.trim()); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * The names of the standard commands a command list holds, in the order the list keeps them.
     * <p>
     * The read and the write share this method, so a name {@code get_form_structure} reports is the
     * name a mutation accepts.
     * </p>
     *
     * @param commands a standard-command list, or <code>null</code> when the object keeps none
     * @return the names, empty when there is no list
     */
    static List<String> standardCommandNames(Object commands)
    {
        List<String> names = new ArrayList<>();
        for (Object command : elements(commands))
        {
            String name = nameOf(command);
            if (!name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Works out what a call would do, reading the model and changing nothing.
     *
     * @param form the form the address sits in
     * @param itemPath the address of the object, or <code>null</code> / empty for the form itself
     * @param names the command names the caller passed
     * @param mode the wanted change: replace, add or remove
     * @return the plan, carrying a refusal when the call must not be made
     */
    static Plan plan(Object form, String itemPath, List<String> names, String mode)
    {
        Plan plan = new Plan();
        plan.mode = mode;
        plan.address = itemPath == null || itemPath.trim().isEmpty()
            ? FORM_ADDRESS : itemPath.trim();
        Target target = resolveTarget(form, plan.address);
        plan.target = target.object;
        if (target.refusal != null)
        {
            plan.refusal = target.refusal;
            return plan;
        }
        Object listed = readNoArg(target.object, "getCommands"); //$NON-NLS-1$
        plan.available = standardCommandNames(listed);
        plan.previous = standardCommandNames(readNoArg(target.object, "getExcludedCommands")); //$NON-NLS-1$
        if (plan.available.isEmpty())
        {
            plan.refusal = "The standard command list of this form is not built, so no name can be " //$NON-NLS-1$
                + "checked against it. Nothing was written."; //$NON-NLS-1$
            return plan;
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (String name : plan.available)
        {
            canonical.put(name.toLowerCase(Locale.ROOT), name);
        }
        List<String> unknown = new ArrayList<>();
        List<String> requested = new ArrayList<>();
        for (String name : names)
        {
            String known = canonical.get(name.toLowerCase(Locale.ROOT));
            if (known == null)
            {
                unknown.add(name);
            }
            else if (!requested.contains(known))
            {
                requested.add(known);
            }
        }
        if (!unknown.isEmpty())
        {
            plan.refusal = unknownCommands(unknown, plan.available);
            return plan;
        }

        // The commands themselves come from the object's own list, so what is written into the
        // exclusion is the object the platform built for that command and not a new one.
        for (Object command : elements(listed))
        {
            plan.commands.put(nameOf(command).toLowerCase(Locale.ROOT), command);
        }
        if ("replace".equals(mode)) //$NON-NLS-1$
        {
            for (String name : plan.available)
            {
                if (requested.contains(name))
                {
                    plan.wanted.add(name);
                }
            }
        }
        else if ("add".equals(mode)) //$NON-NLS-1$
        {
            plan.wanted.addAll(plan.previous);
            for (String name : plan.available)
            {
                if (requested.contains(name) && !plan.wanted.contains(name))
                {
                    plan.wanted.add(name);
                }
            }
        }
        else
        {
            for (String name : plan.previous)
            {
                if (!requested.contains(name))
                {
                    plan.wanted.add(name);
                }
            }
        }
        for (String name : plan.wanted)
        {
            if (!plan.previous.contains(name))
            {
                plan.added.add(name);
            }
        }
        for (String name : plan.previous)
        {
            if (!plan.wanted.contains(name))
            {
                plan.removed.add(name);
            }
        }
        plan.changed = !plan.wanted.equals(plan.previous);
        return plan;
    }

    /**
     * Writes a plan into the model.
     *
     * @param plan a plan built inside the transaction that is writing
     * @return the reason nothing was written, or <code>null</code> when the model now holds the
     *         wanted list
     */
    @SuppressWarnings("unchecked")
    static String applyPlan(Plan plan)
    {
        Object list = readNoArg(plan.target, "getExcludedCommands"); //$NON-NLS-1$
        if (!(list instanceof List) || !plan.changed)
        {
            return list instanceof List ? null
                : "the excluded commands of this object cannot be written"; //$NON-NLS-1$
        }
        List<Object> excluded = (List<Object>)list;
        if ("remove".equals(plan.mode)) //$NON-NLS-1$
        {
            for (Iterator<Object> it = excluded.iterator(); it.hasNext();)
            {
                if (plan.removed.contains(nameOf(it.next())))
                {
                    it.remove();
                }
            }
            return null;
        }
        if ("add".equals(plan.mode)) //$NON-NLS-1$
        {
            for (String name : plan.added)
            {
                Object command = commandNamed(plan, name);
                if (command == null)
                {
                    return missingCommand(name);
                }
                excluded.add(command);
            }
            return null;
        }
        excluded.clear();
        for (String name : plan.wanted)
        {
            Object command = commandNamed(plan, name);
            if (command == null)
            {
                return missingCommand(name);
            }
            excluded.add(command);
        }
        return null;
    }

    /**
     * The success answer: the final list, what moved and whether anything changed.
     *
     * @param formFqn the form the call named
     * @param plan the plan the call carried out
     * @param dryRun whether the form was left as it was
     * @return the answer
     */
    private static ToolResult answer(String formFqn, Plan plan, boolean dryRun)
    {
        ToolResult result = ToolResult.success()
            .put("operation", "set_excluded_commands") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("itemPath", plan.address) //$NON-NLS-1$
            .put("mode", plan.mode) //$NON-NLS-1$
            .put("excludedCommands", plan.wanted) //$NON-NLS-1$
            .put("added", plan.added) //$NON-NLS-1$
            .put("removed", plan.removed) //$NON-NLS-1$
            .put("changed", plan.changed); //$NON-NLS-1$
        if (dryRun)
        {
            result.put("dryRun", true); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * The refusal answer: why the call was refused, and the names the object does accept.
     *
     * @param reason the reason
     * @param available the standard command names the object allows
     * @return the answer
     */
    private static ToolResult refusal(String reason, List<String> available)
    {
        ToolResult result = ToolResult.error(reason);
        if (available != null && !available.isEmpty())
        {
            result = result.put("availableCommands", available); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * The refusal for command names the object's own standard command list does not hold.
     *
     * @param unknown the names the caller passed
     * @param available the standard command names the object allows
     * @return the reason
     */
    private static String unknownCommands(List<String> unknown, List<String> available)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(unknown.size() == 1 ? "The form has no standard command " : "The form has no standard commands "); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < unknown.size(); i++)
        {
            sb.append(i == 0 ? "" : ", ").append('\'').append(unknown.get(i)).append('\''); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append('.');
        String closest = TextSuggest.closest(unknown.get(0), available);
        if (closest != null)
        {
            sb.append(" Did you mean '").append(closest).append("'?"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(" The form allows: ").append(String.join(", ", available)).append('.'); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(" Nothing was written."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * The reason a command the plan named is no longer in the object's list.
     *
     * @param name the command name
     * @return the reason
     */
    private static String missingCommand(String name)
    {
        return "the standard command '" + name + "' is no longer in the object's list"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The standard command a name belongs to, taken from the object's own list.
     *
     * @param plan the plan holding the list
     * @param name the command name
     * @return the command, or <code>null</code> when the list holds no such name
     */
    private static Object commandNamed(Plan plan, String name)
    {
        return plan.commands.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The object an address names, or the reason it names none.
     *
     * @param form the form the address sits in
     * @param address the address: the form, the global command source, or an item
     * @return the resolved object or the refusal
     */
    private static Target resolveTarget(Object form, String address)
    {
        Target target = new Target();
        if (address.equalsIgnoreCase(FORM_ADDRESS))
        {
            target.object = form;
            return target;
        }
        if (address.equalsIgnoreCase(GLOBAL_ADDRESS))
        {
            target.object = readNoArg(form, "getCommandPanelGlobalCommandSource"); //$NON-NLS-1$
            if (target.object == null)
            {
                target.refusal = "The form's global command source is not built, so it keeps no " //$NON-NLS-1$
                    + "excluded commands to change. Nothing was written."; //$NON-NLS-1$
            }
            return target;
        }
        String name = address.regionMatches(true, 0, ITEM_PREFIX, 0, ITEM_PREFIX.length())
            ? address.substring(ITEM_PREFIX.length()) : address;
        target.object = findItem(form, name);
        if (target.object == null)
        {
            target.refusal = "The form has no item '" + name + "'. Nothing was written."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (!holdsExcludedCommands(target.object))
        {
            target.refusal = notACommandSource(target.object, address);
        }
        return target;
    }

    /**
     * Whether an object is one the platform fills with standard commands and lets them be kept out
     * of the command interface.
     *
     * @param element a form element
     * @return <code>true</code> when the element carries an excluded-command list
     */
    private static boolean holdsExcludedCommands(Object element)
    {
        // Asked by the property rather than by the type name: the form model package is an optional
        // import, so the interface that declares the list cannot be named from here.
        return readNoArg(element, "getExcludedCommands") instanceof List; //$NON-NLS-1$
    }

    /**
     * The refusal for an address whose element keeps no excluded commands of its own.
     *
     * @param element the element the address resolved to
     * @param address the address the caller passed
     * @return the reason, naming the source the element takes its commands from when it has one
     */
    private static String notACommandSource(Object element, String address)
    {
        String kind = element instanceof EObject && ((EObject)element).eClass() != null
            ? ((EObject)element).eClass().getName() : "element"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(address).append(" is a ").append(kind) //$NON-NLS-1$
            .append(" and keeps no excluded commands of its own"); //$NON-NLS-1$
        String source = referredSource(element);
        if (source == null)
        {
            sb.append(". Only the form, a table, a field and the global command source keep them."); //$NON-NLS-1$
        }
        else
        {
            sb.append("; its commands come from ").append(source).append(", so address that object instead."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.append(" Nothing was written.").toString(); //$NON-NLS-1$
    }

    /**
     * The object a command bar, a submenu or a button group takes its standard commands from, the
     * way the form file writes it.
     *
     * @param element a form element
     * @return the source address, or <code>null</code> when the element refers to none
     */
    private static String referredSource(Object element)
    {
        String direct = GetFormStructureTool.commandSourceText(
            readNoArg(element, "getCommandSource")); //$NON-NLS-1$
        if (direct != null)
        {
            return direct;
        }
        // A table and a field keep their command bars and their context menu in references of their
        // own, so the source the refused address meant is reached through one of them.
        for (String holder : new String[] { "getExtInfo", "getAutoCommandBar", "getContextMenu" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object part = readNoArg(element, holder);
            String source = GetFormStructureTool.commandSourceText(
                readNoArg(part, "getCommandSource")); //$NON-NLS-1$
            if (source == null)
            {
                source = GetFormStructureTool.commandSourceText(
                    readNoArg(readNoArg(part, "getExtInfo"), "getCommandSource")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (source != null)
            {
                return source;
            }
        }
        return null;
    }

    /**
     * A form item by name: the items of the form and of every container under it, first by their
     * own child list, then anywhere in the form's subtree.
     * <p>
     * The second walk is the one that reaches an item held in a reference of its own rather than in
     * the item tree, such as a button inside a table's command bar.
     * </p>
     *
     * @param form the form
     * @param name the item name
     * @return the item, or <code>null</code> when the form has none by that name
     */
    private static Object findItem(Object form, String name)
    {
        Object found = findChild(form, name);
        if (found != null)
        {
            return found;
        }
        if (form instanceof EObject)
        {
            for (Iterator<EObject> it = ((EObject)form).eAllContents(); it.hasNext();)
            {
                Object candidate = it.next();
                if (name.equals(readNoArg(candidate, "getName"))) //$NON-NLS-1$
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * An item by name among the children of a container and of the containers under it.
     *
     * @param container the container to search
     * @param name the item name
     * @return the item, or <code>null</code> when the container holds none by that name
     */
    private static Object findChild(Object container, String name)
    {
        for (Object child : elements(readNoArg(container, "getItems"))) //$NON-NLS-1$
        {
            if (name.equals(readNoArg(child, "getName"))) //$NON-NLS-1$
            {
                return child;
            }
            Object deeper = findChild(child, name);
            if (deeper != null)
            {
                return deeper;
            }
        }
        return null;
    }

    /**
     * The elements of a model list.
     *
     * @param list a model list, or anything else
     * @return its elements in order, empty when the value is not a list
     */
    private static Iterable<?> elements(Object list)
    {
        return list instanceof Iterable ? (Iterable<?>)list : List.of();
    }

    /**
     * The name of a standard command.
     *
     * @param command a standard command, or <code>null</code>
     * @return its name, empty when it has none
     */
    private static String nameOf(Object command)
    {
        Object name = readNoArg(command, "getName"); //$NON-NLS-1$
        return name instanceof String ? (String)name : ""; //$NON-NLS-1$
    }

    /**
     * Calls a public no-argument getter.
     *
     * @param target the receiver, or <code>null</code> when the value it sits on is absent
     * @param getter the getter name
     * @return what it returned, or <code>null</code> when there is no receiver or the receiver has
     *         no such getter
     */
    private static Object readNoArg(Object target, String getter)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            return target.getClass().getMethod(getter).invoke(target);
        }
        catch (ReflectiveOperationException e)
        {
            // A form element of another kind has no such getter; the caller reads null as "absent".
            return null;
        }
    }

    /** The object an address names, or the reason it names none. */
    private static final class Target
    {
        /** The resolved object, or <code>null</code> when the address named none. */
        Object object;

        /** Why the address named no object, or <code>null</code>. */
        String refusal;
    }

    /** What a call would do to one object's excluded commands. */
    static final class Plan
    {
        /** The object the address resolved to, or <code>null</code> when it resolved to none. */
        Object target;

        /** The address the call named. */
        String address = FORM_ADDRESS;

        /** The change the call asked for. */
        String mode = "replace"; //$NON-NLS-1$

        /** The excluded command names the object holds now, in model order. */
        List<String> previous = new ArrayList<>();

        /** The excluded command names the call wants, in model order. */
        List<String> wanted = new ArrayList<>();

        /** The names the call adds. */
        List<String> added = new ArrayList<>();

        /** The names the call removes. */
        List<String> removed = new ArrayList<>();

        /** The standard command names the object allows. */
        List<String> available = new ArrayList<>();

        /** The allowed commands by lower-case name, taken from the object's own list. */
        Map<String, Object> commands = new LinkedHashMap<>();

        /** Whether the wanted list differs from the one the object holds. */
        boolean changed;

        /** Why the call must not be made, or <code>null</code> when it may. */
        String refusal;
    }
}
