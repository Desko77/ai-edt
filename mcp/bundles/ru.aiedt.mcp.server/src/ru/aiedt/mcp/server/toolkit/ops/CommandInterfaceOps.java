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
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmCommandInterfaceHelper;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Command-interface operations (set_subsystems_order, set_subsystem_visibility,
 * set_main_section_command_visibility, set_subsystem_command_visibility, set_command_placement),
 * extracted from {@link EditMetadataTool} as the fourth cluster of the god-class split (Inc4).
 * Every mutation runs inside {@link BmObjectHelper#executeWriteOnObject} and delegates the actual
 * AdjustableBoolean / CommandsPlacement work to {@link BmCommandInterfaceHelper}; because the
 * CommandInterface is a SEPARATE BM top-object (its own {@code .cmi}), each handler then calls the
 * cluster-local {@link #flushCommandInterface} to force-export it to disk. Shared
 * {@link EditMetadataTool} helpers ({@code requireNonEmpty}, {@code formatResult},
 * {@code resolveReferenceTarget}) are called statically.
 */
final class CommandInterfaceOps
{
    /**
     * J3: sets the configuration's subsystems order (the order top-level subsystems appear as
     * sections in the command interface). {@code subsystems} is a comma-separated list of subsystem
     * FQNs/names in the desired leading order; any participating subsystem
     * (includeInCommandInterface=true) not listed is appended in its natural configuration order so
     * the written order is complete (EDT flags an incomplete order as a validation warning).
     * Materializes the config-level CommandInterface if absent.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetSubsystemsOrder(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String subsystems = JsonUtils.extractStringArgument(params, "subsystems"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(subsystems, "subsystems"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final List<String> requested = new ArrayList<>();
        for (String s : subsystems.split(",")) //$NON-NLS-1$
        {
            String t = s.trim();
            if (!t.isEmpty())
            {
                requested.add(t);
            }
        }
        if (requested.isEmpty())
        {
            return ToolResult.error("subsystems must list at least one subsystem.").toJson(); //$NON-NLS-1$
        }
        final BmCommandInterfaceHelper helper = new BmCommandInterfaceHelper();
        final BmCommandInterfaceHelper.OrderResult[] holder = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, "Configuration", dryRun, //$NON-NLS-1$
            (tx, owner) -> {
                if (!(owner instanceof Configuration))
                {
                    throw new RuntimeException("set_subsystems_order applies to the Configuration root."); //$NON-NLS-1$
                }
                holder[0] = helper.setSubsystemsOrder((Configuration) owner, tx, requested);
                return "Subsystems order set (" + holder[0].finalOrder.size() + " subsystems)."; //$NON-NLS-1$ //$NON-NLS-2$
            });
        flushCommandInterface(project, dryRun, r,
            holder[0] != null ? holder[0].commandInterfaceFqn : null);
        if (holder[0] != null && r.error == null)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("order", holder[0].finalOrder); //$NON-NLS-1$
            if (!holder[0].autoAppended.isEmpty())
            {
                data.put("autoAppended", holder[0].autoAppended); //$NON-NLS-1$
            }
            r.tags.put("subsystemsOrder", data); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "set_subsystems_order"); //$NON-NLS-1$
    }

    /**
     * J3: sets the config-level section visibility of a top-level subsystem in the command
     * interface. {@code visible=true} shows the section (reverting a plain hide-override back to
     * the default), {@code false} hides it. Sets the common (role-independent) value, mirroring
     * EDT's AdjustableBoolean prune semantics, and preserves per-role visibility exceptions.
     * Flushes the separate CommandInterface .cmi.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetSubsystemVisibility(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String subsystem = JsonUtils.extractStringArgument(params, "subsystem"); //$NON-NLS-1$
        String vis = JsonUtils.extractStringArgument(params, "visible"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        final String roleFqn = JsonUtils.extractStringArgument(params, "role"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(subsystem, "subsystem") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(vis, "visible"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final boolean visible;
        if ("true".equalsIgnoreCase(vis)) //$NON-NLS-1$
        {
            visible = true;
        }
        else if ("false".equalsIgnoreCase(vis)) //$NON-NLS-1$
        {
            visible = false;
        }
        else
        {
            return ToolResult.error("visible must be true or false (got '" + vis + "').").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final String fSubsystem = subsystem;
        final BmCommandInterfaceHelper helper = new BmCommandInterfaceHelper();
        final BmCommandInterfaceHelper.VisibilityResult[] holder = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, "Configuration", dryRun, //$NON-NLS-1$
            (tx, owner) -> {
                if (!(owner instanceof Configuration))
                {
                    throw new RuntimeException("set_subsystem_visibility applies to the Configuration root."); //$NON-NLS-1$
                }
                if (roleFqn != null && !roleFqn.isEmpty())
                {
                    holder[0] = helper.setSubsystemVisibilityRole((Configuration) owner, tx, fSubsystem,
                        resolveRole(tx, roleFqn), visible);
                }
                else
                {
                    holder[0] = helper.setSubsystemVisibility((Configuration) owner, tx, fSubsystem, visible);
                }
                return "Subsystem visibility " + holder[0].action + " for " + holder[0].subsystem + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        flushCommandInterface(project, dryRun, r,
            holder[0] != null ? holder[0].commandInterfaceFqn : null);
        if (holder[0] != null && r.error == null)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subsystem", holder[0].subsystem); //$NON-NLS-1$
            data.put("action", holder[0].action); //$NON-NLS-1$
            r.tags.put("subsystemVisibility", data); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "set_subsystem_visibility"); //$NON-NLS-1$
    }

    /**
     * J3: sets the visibility of a command in the configuration's MAIN SECTION command interface
     * (the desktop / "Начальная страница" section). {@code visible=true} shows the command
     * (reverting a plain hide-override to the default), {@code false} hides it. {@code command} is
     * a CommonCommand (CommonCommand.X) or an object-command (e.g. Catalog.X.Command.Y) FQN. Sets
     * the common (role-independent) value and preserves per-role exceptions. Flushes the separate
     * main-section CommandInterface .cmi.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetMainSectionCommandVisibility(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String command = JsonUtils.extractStringArgument(params, "command"); //$NON-NLS-1$
        String vis = JsonUtils.extractStringArgument(params, "visible"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        final String roleFqn = JsonUtils.extractStringArgument(params, "role"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(command, "command") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(vis, "visible"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final boolean visible;
        if ("true".equalsIgnoreCase(vis)) //$NON-NLS-1$
        {
            visible = true;
        }
        else if ("false".equalsIgnoreCase(vis)) //$NON-NLS-1$
        {
            visible = false;
        }
        else
        {
            return ToolResult.error("visible must be true or false (got '" + vis + "').").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        // Trim defensively so a stray leading/trailing space resolves the same as
        // the sibling subsystem path (which trims via simpleSubsystemName).
        final String fCommand = command.trim();
        final String normCmdFqn = MetadataTypeCatalog.normalizeFqn(fCommand);
        final BmCommandInterfaceHelper helper = new BmCommandInterfaceHelper();
        final BmCommandInterfaceHelper.CommandVisibilityResult[] holder = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, "Configuration", dryRun, //$NON-NLS-1$
            (tx, owner) -> {
                if (!(owner instanceof Configuration))
                {
                    throw new RuntimeException(
                        "set_main_section_command_visibility applies to the Configuration root."); //$NON-NLS-1$
                }
                Object cmd = EditMetadataTool.resolveReferenceTarget(tx, normCmdFqn);
                if (!(cmd instanceof EObject))
                {
                    throw new RuntimeException("Command not found: " + normCmdFqn + " (command=" + fCommand //$NON-NLS-1$ //$NON-NLS-2$
                        + "). Expected a CommonCommand (CommonCommand.X) or an object command " //$NON-NLS-1$
                        + "(e.g. Catalog.X.Command.Y)."); //$NON-NLS-1$
                }
                if (roleFqn != null && !roleFqn.isEmpty())
                {
                    holder[0] = helper.setMainSectionCommandVisibilityRole((Configuration) owner, cmd, fCommand,
                        resolveRole(tx, roleFqn), visible);
                }
                else
                {
                    holder[0] = helper.setMainSectionCommandVisibility((Configuration) owner, cmd, fCommand, visible);
                }
                return "Main-section command visibility " + holder[0].action + " for " + holder[0].command + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        flushCommandInterface(project, dryRun, r,
            holder[0] != null ? holder[0].commandInterfaceFqn : null);
        if (holder[0] != null && r.error == null)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", holder[0].command); //$NON-NLS-1$
            data.put("action", holder[0].action); //$NON-NLS-1$
            r.tags.put("mainSectionCommandVisibility", data); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "set_main_section_command_visibility"); //$NON-NLS-1$
    }

    /**
     * J3: sets the visibility of a command in a SUBSYSTEM's command interface (the command
     * interface of that subsystem's own section). {@code subsystem} is the owner Subsystem
     * FQN/name - top-level ({@code Subsystem.X} / {@code X}) or nested
     * ({@code Subsystem.A.Subsystem.B}); {@code command} is a CommonCommand or object-command
     * FQN. Same AdjustableBoolean semantics as the main-section variant. A hide requires the
     * subsystem to already have a command interface (creating one from scratch is not supported);
     * the separate CommandInterface .cmi is force-exported after the write.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetSubsystemCommandVisibility(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String subsystem = JsonUtils.extractStringArgument(params, "subsystem"); //$NON-NLS-1$
        String command = JsonUtils.extractStringArgument(params, "command"); //$NON-NLS-1$
        String vis = JsonUtils.extractStringArgument(params, "visible"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        final String roleFqn = JsonUtils.extractStringArgument(params, "role"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(subsystem, "subsystem") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(command, "command") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(vis, "visible"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final boolean visible;
        if ("true".equalsIgnoreCase(vis)) //$NON-NLS-1$
        {
            visible = true;
        }
        else if ("false".equalsIgnoreCase(vis)) //$NON-NLS-1$
        {
            visible = false;
        }
        else
        {
            return ToolResult.error("visible must be true or false (got '" + vis + "').").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final String fCommand = command.trim();
        final String normCmdFqn = MetadataTypeCatalog.normalizeFqn(fCommand);
        // Owner subsystem FQN: accept a bare top-level name or a full/nested FQN.
        // executeWriteOnObject resolves a nested subsystem FQN
        // (Subsystem.A.Subsystem.B) itself via its nested-subsystem walker (the
        // same one get_command_interface reads through) and applies the
        // supplier-lock guard to the resolved owner, so we pass the full FQN
        // straight through rather than walking here.
        final String fSubsystem = subsystem.trim();
        final String ownerFqn = normalizeSubsystemOwnerFqn(fSubsystem);
        final BmCommandInterfaceHelper helper = new BmCommandInterfaceHelper();
        final BmCommandInterfaceHelper.CommandVisibilityResult[] holder = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                if (!(owner instanceof Subsystem))
                {
                    throw new RuntimeException("set_subsystem_command_visibility applies to a Subsystem, not " //$NON-NLS-1$
                        + owner.eClass().getName() + " (subsystem=" + fSubsystem + ")."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Object cmd = EditMetadataTool.resolveReferenceTarget(tx, normCmdFqn);
                if (!(cmd instanceof EObject))
                {
                    throw new RuntimeException("Command not found: " + normCmdFqn + " (command=" + fCommand //$NON-NLS-1$ //$NON-NLS-2$
                        + "). Expected a CommonCommand (CommonCommand.X) or an object command " //$NON-NLS-1$
                        + "(e.g. Catalog.X.Command.Y)."); //$NON-NLS-1$
                }
                if (roleFqn != null && !roleFqn.isEmpty())
                {
                    holder[0] = helper.setSubsystemCommandVisibilityRole((Subsystem) owner, cmd, fCommand,
                        resolveRole(tx, roleFqn), visible);
                }
                else
                {
                    holder[0] = helper.setSubsystemCommandVisibility((Subsystem) owner, cmd, fCommand, visible);
                }
                return "Subsystem command visibility " + holder[0].action + " for " + holder[0].command //$NON-NLS-1$ //$NON-NLS-2$
                    + " in " + fSubsystem + "."; //$NON-NLS-1$ //$NON-NLS-2$
            });
        flushCommandInterface(project, dryRun, r,
            holder[0] != null ? holder[0].commandInterfaceFqn : null);
        if (holder[0] != null && r.error == null)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subsystem", fSubsystem); //$NON-NLS-1$
            data.put("command", holder[0].command); //$NON-NLS-1$
            data.put("action", holder[0].action); //$NON-NLS-1$
            r.tags.put("subsystemCommandVisibility", data); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "set_subsystem_command_visibility"); //$NON-NLS-1$
    }

    /**
     * Builds the canonical subsystem FQN that {@code executeWriteOnObject}'s nested-subsystem
     * walker recognizes. Accepts a full FQN ({@code Subsystem.A.Subsystem.B} - kept as-is), a bare
     * top-level name ({@code A} -&gt; {@code Subsystem.A}), or a bare dotted nested path
     * ({@code A.B} -&gt; {@code Subsystem.A.Subsystem.B}). The bare-dotted form is what the
     * {@code subsystem} parameter schema advertises; without this normalization it would be
     * prefixed to {@code Subsystem.A.B} and read as a single top-level subsystem named "A.B",
     * yielding an owner-not-found for the nested case.
     *
     * @param subsystem the raw subsystem input
     * @return the canonical FQN
     */
    private static String normalizeSubsystemOwnerFqn(String subsystem)
    {
        if (subsystem.regionMatches(true, 0, "Subsystem.", 0, "Subsystem.".length())) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return subsystem;
        }
        if (subsystem.indexOf('.') < 0) //$NON-NLS-1$
        {
            return "Subsystem." + subsystem; //$NON-NLS-1$
        }
        // Bare dotted nested path: A.B -> Subsystem.A.Subsystem.B, A.B.C -> Subsystem.A.Subsystem.B.Subsystem.C
        String[] segs = subsystem.split("\\."); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("Subsystem.").append(segs[0]); //$NON-NLS-1$
        for (int i = 1; i < segs.length; i++)
        {
            sb.append(".Subsystem.").append(segs[i]); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * J3+: places a command into a command-interface group (CommandsPlacement) on the
     * Configuration's main section or a Subsystem's own command interface, and - only when
     * {@code order} is given - ALSO sets its position in that group's separate CommandsOrder
     * overlay (two independent optional overlays; a real .cmi can have a placement fragment for a
     * group with no matching order fragment at all). {@code ownerFqn} selects the owner:
     * {@code Configuration} (main section) or {@code Subsystem.<name>}. {@code group} accepts a
     * friendly name (Important/Normal/SeeAlso/Create/Reports/Service), a bare/
     * StandardCommandGroup.-prefixed platform token, or a custom CommandGroup.<name> FQN. Flushes
     * the separate CommandInterface .cmi.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opSetCommandPlacement(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String command = JsonUtils.extractStringArgument(params, "command"); //$NON-NLS-1$
        String group = JsonUtils.extractStringArgument(params, "group"); //$NON-NLS-1$
        Integer orderIndex = JsonUtils.extractIntegerArgument(params, "order"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(command, "command") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(group, "group"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final String fCommand = command.trim();
        final String normCmdFqn = MetadataTypeCatalog.normalizeFqn(fCommand);
        final String fGroup = group.trim();
        final boolean isCustomGroup = fGroup.regionMatches(true, 0, "CommandGroup.", 0, "CommandGroup.".length()); //$NON-NLS-1$ //$NON-NLS-2$
        final String normGroupFqn = isCustomGroup ? MetadataTypeCatalog.normalizeFqn(fGroup) : null;
        final String fOwnerFqn = ownerFqn.trim();
        final BmCommandInterfaceHelper helper = new BmCommandInterfaceHelper();
        final BmCommandInterfaceHelper.CommandPlacementResult[] holder = { null };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, fOwnerFqn, dryRun,
            (tx, owner) -> {
                Object cmd = EditMetadataTool.resolveReferenceTarget(tx, normCmdFqn);
                if (!(cmd instanceof EObject))
                {
                    throw new RuntimeException("Command not found: " + normCmdFqn + " (command=" + fCommand //$NON-NLS-1$ //$NON-NLS-2$
                        + "). Expected a CommonCommand (CommonCommand.X) or an object command " //$NON-NLS-1$
                        + "(e.g. Catalog.X.Command.Y)."); //$NON-NLS-1$
                }
                Object customGroup = null;
                String standardToken = null;
                if (isCustomGroup)
                {
                    Object resolved = EditMetadataTool.resolveReferenceTarget(tx, normGroupFqn);
                    if (!(resolved instanceof EObject))
                    {
                        throw new RuntimeException(
                            "Command group not found: " + normGroupFqn + " (group=" + fGroup + ")."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                    customGroup = resolved;
                }
                else
                {
                    standardToken = fGroup;
                }
                if (owner instanceof Configuration)
                {
                    holder[0] = helper.setMainSectionCommandPlacement((Configuration) owner, cmd, fCommand,
                        customGroup, standardToken, orderIndex);
                }
                else if (owner instanceof Subsystem)
                {
                    holder[0] = helper.setSubsystemCommandPlacement((Subsystem) owner, cmd, fCommand, customGroup,
                        standardToken, orderIndex);
                }
                else
                {
                    throw new RuntimeException("set_command_placement applies to the Configuration root or a " //$NON-NLS-1$
                        + "Subsystem, not " + owner.eClass().getName() + " (ownerFqn=" + fOwnerFqn + ")."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return "Command placement " + holder[0].action + " for " + holder[0].command + " in group " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + holder[0].group + "."; //$NON-NLS-1$
            });
        flushCommandInterface(project, dryRun, r, holder[0] != null ? holder[0].commandInterfaceFqn : null);
        if (holder[0] != null && r.error == null)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", holder[0].command); //$NON-NLS-1$
            data.put("group", holder[0].group); //$NON-NLS-1$
            data.put("action", holder[0].action); //$NON-NLS-1$
            if (orderIndex != null)
            {
                data.put("order", orderIndex); //$NON-NLS-1$
                data.put("orderAction", holder[0].orderAction); //$NON-NLS-1$
            }
            r.tags.put("commandPlacement", data); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "set_command_placement"); //$NON-NLS-1$
    }

    /**
     * set_command_order: batch-reorder commands within one command-interface group
     * (ownerFqn = Configuration root or Subsystem) in a single transaction. The
     * {@code commands} list (JSON array of FQNs/names) becomes the leading order of the
     * group's CommandsOrder overlay; commands already present but not listed keep their
     * relative order after them. Pure order - no placement/visibility change. Exactly one
     * of {@code group} (CommandGroup.&lt;name&gt;) / {@code standardGroup} (Important /
     * Normal / SeeAlso / Create / Reports / Service) selects the group.
     *
     * @param params tool parameters
     * @return the JSON result document
     */
    String opSetCommandOrder(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String group = JsonUtils.extractStringArgument(params, "group"); //$NON-NLS-1$
        List<String> commandInputs = JsonUtils.extractArrayArgument(params, "commands"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(group, "group"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        if (commandInputs == null || commandInputs.isEmpty())
        {
            return ToolResult.error("commands is required: a JSON array of command FQNs/names, " //$NON-NLS-1$
                + "e.g. [\"CommonCommand.X\", \"Catalog.Y.Command.Z\"].").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final String fOwnerFqn = ownerFqn.trim();
        final String fGroup = group.trim();
        final boolean isCustomGroup =
            fGroup.regionMatches(true, 0, "CommandGroup.", 0, "CommandGroup.".length()); //$NON-NLS-1$ //$NON-NLS-2$
        final String normGroupFqn = isCustomGroup ? MetadataTypeCatalog.normalizeFqn(fGroup) : null;
        final BmCommandInterfaceHelper helper = new BmCommandInterfaceHelper();
        final BmCommandInterfaceHelper.CommandPlacementResult[] holder = { null };
        final List<String> normCommandFqns = new ArrayList<>();
        final List<String> commandLabels = new ArrayList<>();
        for (String c : commandInputs)
        {
            if (c == null || c.trim().isEmpty())
            {
                continue;
            }
            String t = c.trim();
            normCommandFqns.add(MetadataTypeCatalog.normalizeFqn(t));
            commandLabels.add(t);
        }
        if (normCommandFqns.isEmpty())
        {
            return ToolResult.error("commands contained no non-empty entries.").toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, fOwnerFqn, dryRun,
            (tx, owner) -> {
                List<com._1c.g5.v8.dt.mcore.Command> resolved = new ArrayList<>();
                for (String cfqn : normCommandFqns)
                {
                    Object cmd = EditMetadataTool.resolveReferenceTarget(tx, cfqn);
                    if (!(cmd instanceof com._1c.g5.v8.dt.mcore.Command))
                    {
                        throw new RuntimeException("Command not found: " + cfqn //$NON-NLS-1$
                            + ". Expected a CommonCommand (CommonCommand.X) or an object command " //$NON-NLS-1$
                            + "(e.g. Catalog.X.Command.Y)."); //$NON-NLS-1$
                    }
                    resolved.add((com._1c.g5.v8.dt.mcore.Command)cmd);
                }
                Object customGroup = null;
                String standardToken = null;
                if (isCustomGroup)
                {
                    Object rg = EditMetadataTool.resolveReferenceTarget(tx, normGroupFqn);
                    if (!(rg instanceof EObject))
                    {
                        throw new RuntimeException("Command group not found: " + normGroupFqn //$NON-NLS-1$
                            + " (group=" + fGroup + ")."); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    customGroup = rg;
                }
                else
                {
                    standardToken = fGroup;
                }
                if (owner instanceof Configuration)
                {
                    holder[0] = helper.setMainSectionCommandOrder((Configuration)owner, customGroup,
                        standardToken, resolved, commandLabels);
                }
                else if (owner instanceof Subsystem)
                {
                    holder[0] = helper.setSubsystemCommandOrder((Subsystem)owner, customGroup,
                        standardToken, resolved, commandLabels);
                }
                else
                {
                    throw new RuntimeException("set_command_order applies to the Configuration root " //$NON-NLS-1$
                        + "or a Subsystem, not " + owner.eClass().getName() //$NON-NLS-1$
                        + " (ownerFqn=" + fOwnerFqn + ")."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return "Reordered " + resolved.size() + " command(s) in group " + holder[0].group + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        flushCommandInterface(project, dryRun, r, holder[0] != null ? holder[0].commandInterfaceFqn : null);
        if (holder[0] != null && r.error == null)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("commands", commandLabels); //$NON-NLS-1$
            data.put("group", holder[0].group); //$NON-NLS-1$
            data.put("action", holder[0].action); //$NON-NLS-1$
            data.put("orderAction", holder[0].orderAction); //$NON-NLS-1$
            r.tags.put("commandOrder", data); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "set_command_order"); //$NON-NLS-1$
    }

    /**
     * Resolves a role FQN (e.g. {@code Role.FullAccess}) to a {@link Role} inside a BM
     * transaction, for the per-role visibility operations. Throws a clear error when the
     * FQN does not resolve or resolves to something that is not a Role.
     */
    private static Role resolveRole(IBmTransaction tx, String roleFqn)
    {
        Object resolved = EditMetadataTool.resolveReferenceTarget(tx, roleFqn);
        if (!(resolved instanceof Role))
        {
            throw new RuntimeException("role is not a Role metadata object: " + roleFqn //$NON-NLS-1$
                + " (resolved to " //$NON-NLS-1$
                + (resolved == null ? "nothing" : resolved.getClass().getSimpleName()) //$NON-NLS-1$
                + "). Pass a Role FQN, e.g. Role.FullAccess."); //$NON-NLS-1$
        }
        return (Role) resolved;
    }

    /**
     * Flushes a config / main-section / subsystem CommandInterface top-object to disk after a
     * mutation. The CommandInterface is a SEPARATE BM top-object (its own {@code .cmi});
     * {@code executeWriteOnObject} exports only the owning object, so without this a
     * command-interface change commits to the model but never reaches disk. No-op on dryRun /
     * error / missing fqn; records a {@code commandInterfaceExportWarning} tag on failure.
     */
    private static void flushCommandInterface(IProject project, boolean dryRun,
        BmObjectHelper.Result r, String ciFqn)
    {
        if (dryRun || r.error != null || ciFqn == null)
        {
            return;
        }
        IBmModelManager mgr = Activator.getDefault().getBmModelManager();
        if (mgr == null)
        {
            return;
        }
        BmExportHelper.Result exp = BmExportHelper.forceExportAndWait(mgr, project, ciFqn);
        if (exp == null || !exp.forceExportOk)
        {
            String reason = (exp != null && exp.error != null) ? exp.error : "forceExport not ok"; //$NON-NLS-1$
            r.tags.put("commandInterfaceExportWarning", reason); //$NON-NLS-1$
            // The command interface changed in the model but never reached CommandInterface.cmi on
            // disk. A success here would send the caller on to a commit or a build over a file that
            // does not carry the change - and the visibility they just set would be missing from
            // everything downstream, with nothing to say why. Same reasoning as sync_export.
            r.ok = false;
            r.error = "the command interface changed in the model but was not written to disk (" //$NON-NLS-1$
                + reason + "). The visibility is not in CommandInterface.cmi yet, so do not commit or " //$NON-NLS-1$
                + "build on it: re-run the operation, or restart EDT if it keeps failing."; //$NON-NLS-1$
        }
    }
}
