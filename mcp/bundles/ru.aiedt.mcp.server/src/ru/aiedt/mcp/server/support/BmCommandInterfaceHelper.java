/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrder;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacement;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibility;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.cmi.model.SubsystemsOrder;
import com._1c.g5.v8.dt.cmi.model.SubsystemsVisibility;
import com._1c.g5.v8.dt.cmi.model.SubsystemsVisibilityFragment;
import com._1c.g5.v8.dt.cmi.model.util.CmiModelUtil;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * J3: mutates the command interface sub-model (cmi.model.CommandInterface) of the
 * Configuration root, its main section, or a subsystem. Isolates every cmi.model
 * type and EDT's {@link CmiModelUtil} lazy-create factory so the callers
 * (EditMetadataTool ops) stay free of the cmi.model dependency. Mirrors the
 * shape of BmSubsystemHelper / BmFormHelper.
 *
 * <p>All methods run inside an active BM transaction (the caller's
 * {@code executeWriteOnObject} action).
 */
public class BmCommandInterfaceHelper
{
    /** The config-level CommandInterface (subsystems order + section visibility), or null. */
    public CommandInterface getConfigCommandInterface(Configuration config)
    {
        Object aci = config.getCommandInterface();
        return (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
    }

    /** Gets, or lazily creates and attaches, the config-level CommandInterface. */
    public CommandInterface getOrCreateConfigCommandInterface(Configuration config, IBmTransaction tx)
    {
        CommandInterface ci = getConfigCommandInterface(config);
        if (ci == null)
        {
            ci = CmiModelUtil.createConfigurationCommandInterface(config, tx);
        }
        return ci;
    }

    /**
     * Result of a {@code setSubsystemsOrder} write: the final ordered list of
     * subsystem names actually written, and which ones were auto-appended.
     * EDT flags an incomplete subsystems order (one that omits a participating
     * subsystem) as a validation WARNING, so the op always writes the complete
     * set - the requested leading order plus the rest in configuration order.
     */
    public static final class OrderResult
    {
        public final List<String> finalOrder;
        public final List<String> autoAppended;
        /**
         * FQN of the config-level CommandInterface top-object the caller must
         * force-export: it is a SEPARATE BM top-object (serialized to
         * Configuration/CommandInterface.cmi), so exporting only the owning
         * Configuration does not flush the order change to disk.
         */
        public final String commandInterfaceFqn;

        OrderResult(List<String> finalOrder, List<String> autoAppended, String commandInterfaceFqn)
        {
            this.finalOrder = finalOrder;
            this.autoAppended = autoAppended;
            this.commandInterfaceFqn = commandInterfaceFqn;
        }
    }

    /**
     * Sets the configuration's subsystems order. {@code requestedFqns} lists the
     * subsystems (top-level, participating in the command interface) in the
     * desired leading order; any remaining participating subsystem is appended
     * in its natural configuration order so the written order is complete.
     *
     * @throws RuntimeException with a clear message if a requested subsystem is
     *         not a top-level subsystem, or does not participate in the command
     *         interface (includeInCommandInterface=false)
     */
    public OrderResult setSubsystemsOrder(Configuration config, IBmTransaction tx,
        List<String> requestedFqns)
    {
        // The set of subsystems that legitimately belong in the order: top-level
        // subsystems with includeInCommandInterface=true, keyed by name in
        // configuration order.
        Map<String, Subsystem> participating = new LinkedHashMap<>();
        for (Subsystem s : config.getSubsystems())
        {
            if (s.isIncludeInCommandInterface() && s.getName() != null)
            {
                participating.put(s.getName(), s);
            }
        }

        List<Subsystem> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (String fqn : requestedFqns)
        {
            String name = simpleSubsystemName(fqn);
            // Case-insensitive, matching findTopSubsystem below and the codebase's
            // MetadataTypeCatalog.findObject convention.
            Subsystem s = lookupParticipating(participating, name);
            if (s == null)
            {
                // Distinguish "not a participating subsystem" from "not top-level
                // / not found" for a clearer error.
                Subsystem any = findTopSubsystem(config, name);
                if (any == null)
                {
                    throw new RuntimeException("Subsystem not found as a top-level subsystem: " + fqn //$NON-NLS-1$
                        + " (subsystems order covers top-level subsystems only)."); //$NON-NLS-1$
                }
                throw new RuntimeException("Subsystem '" + fqn //$NON-NLS-1$
                    + "' does not participate in the command interface " //$NON-NLS-1$
                    + "(includeInCommandInterface=false) - it cannot appear in the subsystems order."); //$NON-NLS-1$
            }
            // Dedup by the ACTUAL subsystem name so the auto-append pass (which
            // keys on participating's actual names) sees the same identity - a
            // requested-case key would let the subsystem be appended twice.
            if (placed.add(s.getName()))
            {
                ordered.add(s);
            }
        }
        // Auto-append the remaining participating subsystems (configuration order).
        List<String> autoAppended = new ArrayList<>();
        for (Map.Entry<String, Subsystem> e : participating.entrySet())
        {
            if (!placed.contains(e.getKey()))
            {
                ordered.add(e.getValue());
                autoAppended.add("Subsystem." + e.getKey()); //$NON-NLS-1$
            }
        }

        CommandInterface ci = getOrCreateConfigCommandInterface(config, tx);
        String ciFqn = topObjectFqn(ci);
        SubsystemsOrder so = ci.getSubsystemsOrder();
        if (so == null)
        {
            so = CmiFactory.eINSTANCE.createSubsystemsOrder();
            ci.setSubsystemsOrder(so);
        }
        EList<Subsystem> list = so.getSubsystems();
        list.clear();
        list.addAll(ordered);

        List<String> finalNames = new ArrayList<>();
        for (Subsystem s : ordered)
        {
            finalNames.add("Subsystem." + s.getName()); //$NON-NLS-1$
        }
        return new OrderResult(finalNames, autoAppended, ciFqn);
    }

    /**
     * The FQN of a CommandInterface top-object, for a targeted forceExport, or
     * null if it is not (yet) a resolvable top object. EDT 2026.1 restricts
     * {@code bmGetFqn()} to top objects; the config-level CommandInterface is one
     * (attached via {@code tx.attachTopObject}).
     */
    private static String topObjectFqn(Object o)
    {
        if (o instanceof IBmObject)
        {
            try
            {
                return ((IBmObject) o).bmGetFqn();
            }
            catch (RuntimeException ignore)
            {
                // not a resolvable top object in this state - caller skips the export
            }
        }
        return null;
    }

    /** Result of a subsystem section-visibility write. */
    public static final class VisibilityResult
    {
        public final String commandInterfaceFqn;
        /** created | updated | removed | unchanged */
        public final String action;
        public final String subsystem;

        VisibilityResult(String commandInterfaceFqn, String action, String subsystem)
        {
            this.commandInterfaceFqn = commandInterfaceFqn;
            this.action = action;
            this.subsystem = subsystem;
        }
    }

    /**
     * Sets the config-level section visibility of a top-level subsystem (whether
     * the subsystem is shown as a section). Mirrors EDT's own
     * {@code CommandInterfaceUtil} semantics on the {@link AdjustableBoolean}:
     * {@code isCommon()} carries the actual value (true = shown, false = hidden),
     * and "shown with no per-role exception" is the default - so setting
     * {@code visible=true} removes a plain hide-override (the default is
     * indistinguishable from no override) but keeps a fragment that carries
     * per-role exceptions ({@code getFor()}), never discarding those.
     * {@code visible=false} persists an explicit hide override, preserving any
     * per-role exceptions. Materializes the config-level CommandInterface only
     * when a hide override actually needs writing.
     *
     * @throws RuntimeException if the subsystem is not a top-level subsystem
     */
    public VisibilityResult setSubsystemVisibility(Configuration config, IBmTransaction tx,
        String subsystemFqn, boolean visible)
    {
        Subsystem target = findTopSubsystem(config, simpleSubsystemName(subsystemFqn));
        if (target == null)
        {
            throw new RuntimeException("Subsystem not found as a top-level subsystem: " + subsystemFqn //$NON-NLS-1$
                + " (subsystem section visibility covers top-level subsystems only)."); //$NON-NLS-1$
        }
        // Read, do NOT create: a no-op call must not materialize an empty CommandInterface.
        CommandInterface ci = getConfigCommandInterface(config);
        SubsystemsVisibility sv = (ci != null) ? ci.getSubsystemsVisibility() : null;
        SubsystemsVisibilityFragment frag = findVisibilityFragment(sv, target);

        if (visible)
        {
            // Shown is the default (CommandInterfaceUtil.createDefaultVisibility -> common=true).
            // A hide override with no per-role exception is then indistinguishable from no
            // override -> remove it (EDT's own prune rule). Keep a fragment that has per-role
            // exceptions and only flip its common value; NEVER discard per-role data.
            if (frag == null)
            {
                return new VisibilityResult(topObjectFqn(ci), "unchanged", target.getName()); //$NON-NLS-1$
            }
            AdjustableBoolean ab = frag.getVisible();
            boolean hasPerRole = ab != null && !ab.getFor().isEmpty();
            if (!hasPerRole)
            {
                sv.getVisibilityFragments().remove(frag);
                if (sv.getVisibilityFragments().isEmpty())
                {
                    ci.setSubsystemsVisibility(null);
                }
                return new VisibilityResult(topObjectFqn(ci), "removed", target.getName()); //$NON-NLS-1$
            }
            if (ab.isCommon())
            {
                return new VisibilityResult(topObjectFqn(ci), "unchanged", target.getName()); //$NON-NLS-1$
            }
            ab.setCommon(true);
            return new VisibilityResult(topObjectFqn(ci), "updated", target.getName()); //$NON-NLS-1$
        }

        // Hidden: persist an explicit common=false override (preserving per-role exceptions).
        if (ci == null)
        {
            ci = getOrCreateConfigCommandInterface(config, tx);
        }
        String ciFqn = topObjectFqn(ci);
        if (sv == null)
        {
            sv = ci.getSubsystemsVisibility();
            if (sv == null)
            {
                sv = CmiFactory.eINSTANCE.createSubsystemsVisibility();
                ci.setSubsystemsVisibility(sv);
            }
        }
        if (frag == null)
        {
            frag = CmiFactory.eINSTANCE.createSubsystemsVisibilityFragment();
            frag.setSubsystem(target);
            AdjustableBoolean ab = MdClassFactory.eINSTANCE.createAdjustableBoolean();
            ab.setCommon(false);
            frag.setVisible(ab);
            sv.getVisibilityFragments().add(frag);
            return new VisibilityResult(ciFqn, "created", target.getName()); //$NON-NLS-1$
        }
        AdjustableBoolean ab = frag.getVisible();
        if (ab == null)
        {
            ab = MdClassFactory.eINSTANCE.createAdjustableBoolean();
            frag.setVisible(ab);
        }
        if (!ab.isCommon())
        {
            return new VisibilityResult(ciFqn, "unchanged", target.getName()); //$NON-NLS-1$
        }
        ab.setCommon(false);
        return new VisibilityResult(ciFqn, "updated", target.getName()); //$NON-NLS-1$
    }

    /**
     * Finds the visibility fragment for {@code target} using EDT's own identity
     * semantics ({@link CmiModelUtil#isSameBmObject}: reference / proxy-URI /
     * bmGetId), or null.
     */
    private static SubsystemsVisibilityFragment findVisibilityFragment(SubsystemsVisibility sv,
        Subsystem target)
    {
        if (sv == null)
        {
            return null;
        }
        for (SubsystemsVisibilityFragment f : sv.getVisibilityFragments())
        {
            if (CmiModelUtil.isSameBmObject(f.getSubsystem(), target))
            {
                return f;
            }
        }
        return null;
    }

    /** Bare subsystem name from a "Subsystem.Name" FQN or a plain name. */
    private static String simpleSubsystemName(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String s = fqn.trim();
        if (s.regionMatches(true, 0, "Subsystem.", 0, "Subsystem.".length())) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return s.substring("Subsystem.".length()); //$NON-NLS-1$
        }
        return s;
    }

    private static Subsystem findTopSubsystem(Configuration config, String name)
    {
        for (Subsystem s : config.getSubsystems())
        {
            if (name != null && name.equalsIgnoreCase(s.getName()))
            {
                return s;
            }
        }
        return null;
    }

    /** Case-insensitive lookup into the participating-subsystems map. */
    private static Subsystem lookupParticipating(Map<String, Subsystem> participating, String name)
    {
        if (name == null)
        {
            return null;
        }
        for (Map.Entry<String, Subsystem> e : participating.entrySet())
        {
            if (name.equalsIgnoreCase(e.getKey()))
            {
                return e.getValue();
            }
        }
        return null;
    }

    // ---- command visibility (main-section and, later, subsystem) -------------

    /** Result of a command-visibility write. */
    public static final class CommandVisibilityResult
    {
        public final String commandInterfaceFqn;
        /** created | updated | removed | unchanged */
        public final String action;
        /** display name of the affected command */
        public final String command;

        CommandVisibilityResult(String commandInterfaceFqn, String action, String command)
        {
            this.commandInterfaceFqn = commandInterfaceFqn;
            this.action = action;
            this.command = command;
        }
    }

    /**
     * Sets the visibility of a command in the configuration's MAIN SECTION command
     * interface (the "Начальная страница"/desktop section). {@code command} is the
     * already-resolved command object (a {@code CommonCommand} or an object command
     * such as {@code Catalog.X.Command.Y}). Uses the same {@link AdjustableBoolean}
     * prune semantics as subsystem section visibility: {@code visible=true} reverts
     * a plain hide-override to the default (shown) but keeps a fragment that carries
     * per-role exceptions; {@code visible=false} persists an explicit hide override,
     * preserving per-role exceptions. A hide requires the main-section command
     * interface to already exist (see {@link #applyCommandVisibility}).
     *
     * @param commandFqn the original command FQN (for clear error messages)
     * @throws RuntimeException if {@code command} is not an {@link Command}
     */
    public CommandVisibilityResult setMainSectionCommandVisibility(Configuration config,
        Object command, String commandFqn, boolean visible)
    {
        Command cmd = asCommand(command, commandFqn);
        Object aci = config.getMainSectionCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        ApplyOutcome out = applyCommandVisibility(ci, "the configuration main section", cmd, visible); //$NON-NLS-1$
        return new CommandVisibilityResult(out.ciFqn, out.action, CmiModelUtil.getCommandName(cmd));
    }

    /**
     * Sets the visibility of a command in a SUBSYSTEM's command interface (the
     * command interface of that subsystem's own section). {@code command} is the
     * already-resolved command object (a {@code CommonCommand} or an object command
     * such as {@code Catalog.X.Command.Y}). Uses the same {@link AdjustableBoolean}
     * prune semantics as {@link #setMainSectionCommandVisibility}. A hide requires the
     * subsystem to already have a command interface (see
     * {@link #applyCommandVisibility}).
     *
     * @param commandFqn the original command FQN (for clear error messages)
     * @throws RuntimeException if {@code command} is not an {@link Command}
     */
    public CommandVisibilityResult setSubsystemCommandVisibility(Subsystem subsystem,
        Object command, String commandFqn, boolean visible)
    {
        Command cmd = asCommand(command, commandFqn);
        Object aci = subsystem.getCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        String label = "subsystem '" + subsystem.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        ApplyOutcome out = applyCommandVisibility(ci, label, cmd, visible);
        return new CommandVisibilityResult(out.ciFqn, out.action, CmiModelUtil.getCommandName(cmd));
    }

    // ---- per-role visibility (RSV 5.10 parity) ---------------------------
    // The common-value setters above only touch AdjustableBoolean.isCommon(); a per-role
    // exception lives in AdjustableBoolean.getFor() as a ForRoleType per role. These
    // setters find-or-create the ForRoleType for one role and set its value. Unlike the
    // common value, a per-role entry is NEVER pruned - it is an explicit exception.

    /**
     * Sets one role's value on an {@link AdjustableBoolean}: finds the matching
     * {@link ForRoleType} (EDT identity via {@link CmiModelUtil#isSameBmObject}) and
     * updates it, or appends a new one. Returns {@code created | updated | unchanged}.
     */
    private static String applyPerRole(AdjustableBoolean ab, Role role, boolean visible)
    {
        for (ForRoleType fr : ab.getFor())
        {
            if (CmiModelUtil.isSameBmObject(fr.getRole(), role))
            {
                if (fr.isValue() == visible)
                {
                    return "unchanged"; //$NON-NLS-1$
                }
                fr.setValue(visible);
                return "updated"; //$NON-NLS-1$
            }
        }
        ForRoleType fr = MdClassFactory.eINSTANCE.createForRoleType();
        fr.setRole(role);
        fr.setValue(visible);
        ab.getFor().add(fr);
        return "created"; //$NON-NLS-1$
    }

    /**
     * Per-role variant of {@link #setSubsystemVisibility}: sets one role's view of a
     * top-level subsystem section. Always materializes the fragment, because a per-role
     * entry is an explicit exception even when its value matches the common one.
     */
    public VisibilityResult setSubsystemVisibilityRole(Configuration config, IBmTransaction tx,
        String subsystemFqn, Role role, boolean visible)
    {
        Subsystem target = findTopSubsystem(config, simpleSubsystemName(subsystemFqn));
        if (target == null)
        {
            throw new RuntimeException("Subsystem not found as a top-level subsystem: " + subsystemFqn //$NON-NLS-1$
                + " (subsystem section visibility covers top-level subsystems only)."); //$NON-NLS-1$
        }
        CommandInterface ci = getOrCreateConfigCommandInterface(config, tx);
        String ciFqn = topObjectFqn(ci);
        SubsystemsVisibility sv = ci.getSubsystemsVisibility();
        if (sv == null)
        {
            sv = CmiFactory.eINSTANCE.createSubsystemsVisibility();
            ci.setSubsystemsVisibility(sv);
        }
        SubsystemsVisibilityFragment frag = findVisibilityFragment(sv, target);
        if (frag == null)
        {
            frag = CmiFactory.eINSTANCE.createSubsystemsVisibilityFragment();
            frag.setSubsystem(target);
            sv.getVisibilityFragments().add(frag);
        }
        AdjustableBoolean ab = frag.getVisible();
        if (ab == null)
        {
            ab = MdClassFactory.eINSTANCE.createAdjustableBoolean();
            // A fresh fragment's common value defaults to the shown state; the per-role
            // entry is the exception. Without this the EMF default (common=false) would
            // hide the section for every role that has no exception (codex HIGH).
            ab.setCommon(true);
            frag.setVisible(ab);
        }
        return new VisibilityResult(ciFqn, applyPerRole(ab, role, visible), target.getName());
    }

    /**
     * Per-role variant of {@link #applyCommandVisibility}: same command-interface
     * existence requirement (a transient/no-FQN CI is refused), but sets one role's
     * value instead of the common one.
     */
    private static ApplyOutcome applyCommandVisibilityRole(CommandInterface ci, String ownerLabel,
        Command cmd, Role role, boolean visible)
    {
        String ciFqn = (ci != null) ? topObjectFqn(ci) : null;
        if (ciFqn == null)
        {
            throw new RuntimeException(ownerLabel + " has no command interface yet. Setting a command's " //$NON-NLS-1$
                + "per-role visibility requires an existing command interface; creating one from " //$NON-NLS-1$
                + "scratch is not supported. Adjust the command interface of " + ownerLabel //$NON-NLS-1$
                + " in EDT once (set any command's placement or order), then retry."); //$NON-NLS-1$
        }
        CommandsVisibility cv = ci.getCommandsVisibility();
        if (cv == null)
        {
            cv = CmiFactory.eINSTANCE.createCommandsVisibility();
            ci.setCommandsVisibility(cv);
        }
        CommandsVisibilityFragment frag = findCommandVisibilityFragment(cv, cmd);
        if (frag == null)
        {
            frag = CmiFactory.eINSTANCE.createCommandsVisibilityFragment();
            frag.setCommand(cmd);
            cv.getVisibilityFragments().add(frag);
        }
        AdjustableBoolean ab = frag.getVisible();
        if (ab == null)
        {
            ab = MdClassFactory.eINSTANCE.createAdjustableBoolean();
            // Fresh fragment: common defaults to shown so the per-role entry is the
            // exception, not a hide-everything-else (codex HIGH).
            ab.setCommon(true);
            frag.setVisible(ab);
        }
        return new ApplyOutcome(applyPerRole(ab, role, visible), ciFqn);
    }

    /** Per-role variant of {@link #setMainSectionCommandVisibility}. */
    public CommandVisibilityResult setMainSectionCommandVisibilityRole(Configuration config,
        Object command, String commandFqn, Role role, boolean visible)
    {
        Command cmd = asCommand(command, commandFqn);
        Object aci = config.getMainSectionCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        ApplyOutcome out = applyCommandVisibilityRole(ci, "the configuration main section", cmd, role, visible); //$NON-NLS-1$
        return new CommandVisibilityResult(out.ciFqn, out.action, CmiModelUtil.getCommandName(cmd));
    }

    /** Per-role variant of {@link #setSubsystemCommandVisibility}. */
    public CommandVisibilityResult setSubsystemCommandVisibilityRole(Subsystem subsystem,
        Object command, String commandFqn, Role role, boolean visible)
    {
        Command cmd = asCommand(command, commandFqn);
        Object aci = subsystem.getCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        String label = "subsystem '" + subsystem.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        ApplyOutcome out = applyCommandVisibilityRole(ci, label, cmd, role, visible);
        return new CommandVisibilityResult(out.ciFqn, out.action, CmiModelUtil.getCommandName(cmd));
    }

    /** {action, ciFqn} pair produced by {@link #applyCommandVisibility}. */
    private static final class ApplyOutcome
    {
        final String action;
        final String ciFqn;

        ApplyOutcome(String action, String ciFqn)
        {
            this.action = action;
            this.ciFqn = ciFqn;
        }
    }

    /**
     * Applies a command-visibility change to a CommandInterface's commandsVisibility
     * fragment, mirroring EDT's AdjustableBoolean prune rule. {@code ci} is the
     * already-read CommandInterface. A {@code visible=true} request on an absent
     * ({@code null}) CI is a no-op ("unchanged"); a hide ({@code visible=false}) on
     * an absent CI is REFUSED, because creating a command-interface top-object from
     * scratch through the generic BM write task does not persist reliably - the
     * attached top-object does not survive the commit (forceExport reports not-ok and
     * the model stays empty). Modifying an EXISTING command interface works normally.
     * {@code ownerLabel} names the owner ("the configuration main section" /
     * "subsystem 'X'") for that refusal message.
     */
    private static ApplyOutcome applyCommandVisibility(CommandInterface ci, String ownerLabel,
        Command cmd, boolean visible)
    {
        // A subsystem/config WITHOUT a persisted command interface still returns a
        // non-null, transient CI from getCommandInterface() - it is not a real BM
        // top-object, so its bmGetFqn (topObjectFqn) is null and a change to it can
        // never be force-exported. Treat "no resolvable top-object FQN" as "no command
        // interface": a visible=true request is then a no-op, and a hide is REFUSED
        // (attaching a fresh CI top-object does not survive the generic write task's
        // commit). Only a real, persisted CI (non-null FQN) is mutated.
        String ciFqn = (ci != null) ? topObjectFqn(ci) : null;
        if (ciFqn == null)
        {
            if (visible)
            {
                return new ApplyOutcome("unchanged", null); //$NON-NLS-1$
            }
            throw new RuntimeException(ownerLabel + " has no command interface yet. Setting a command's " //$NON-NLS-1$
                + "visibility requires an existing command interface; creating one from scratch is not " //$NON-NLS-1$
                + "supported by this operation. Adjust the command interface of " + ownerLabel + " in EDT " //$NON-NLS-1$ //$NON-NLS-2$
                + "once (for example set any command's placement or order), then retry."); //$NON-NLS-1$
        }

        CommandsVisibility cv = ci.getCommandsVisibility();
        CommandsVisibilityFragment frag = findCommandVisibilityFragment(cv, cmd);

        if (visible)
        {
            // Shown is the default; a hide override with no per-role exception is then
            // indistinguishable from no override -> remove it (EDT's prune rule).
            if (frag == null)
            {
                return new ApplyOutcome("unchanged", ciFqn); //$NON-NLS-1$
            }
            AdjustableBoolean ab = frag.getVisible();
            boolean hasPerRole = ab != null && !ab.getFor().isEmpty();
            if (!hasPerRole)
            {
                cv.getVisibilityFragments().remove(frag);
                if (cv.getVisibilityFragments().isEmpty())
                {
                    ci.setCommandsVisibility(null);
                }
                return new ApplyOutcome("removed", ciFqn); //$NON-NLS-1$
            }
            if (ab.isCommon())
            {
                return new ApplyOutcome("unchanged", ciFqn); //$NON-NLS-1$
            }
            ab.setCommon(true);
            return new ApplyOutcome("updated", ciFqn); //$NON-NLS-1$
        }

        // Hidden: an explicit common=false override on the existing command interface.
        if (cv == null)
        {
            cv = CmiFactory.eINSTANCE.createCommandsVisibility();
            ci.setCommandsVisibility(cv);
        }
        if (frag == null)
        {
            frag = CmiFactory.eINSTANCE.createCommandsVisibilityFragment();
            frag.setCommand(cmd);
            AdjustableBoolean ab = MdClassFactory.eINSTANCE.createAdjustableBoolean();
            ab.setCommon(false);
            frag.setVisible(ab);
            cv.getVisibilityFragments().add(frag);
            return new ApplyOutcome("created", ciFqn); //$NON-NLS-1$
        }
        AdjustableBoolean ab = frag.getVisible();
        if (ab == null)
        {
            ab = MdClassFactory.eINSTANCE.createAdjustableBoolean();
            frag.setVisible(ab);
        }
        if (!ab.isCommon())
        {
            return new ApplyOutcome("unchanged", ciFqn); //$NON-NLS-1$
        }
        ab.setCommon(false);
        return new ApplyOutcome("updated", ciFqn); //$NON-NLS-1$
    }

    /**
     * Finds the commands-visibility fragment for {@code cmd} using EDT's own identity
     * semantics ({@link CmiModelUtil#isSameBmObject}), or null.
     */
    private static CommandsVisibilityFragment findCommandVisibilityFragment(CommandsVisibility cv,
        Command cmd)
    {
        if (cv == null)
        {
            return null;
        }
        for (CommandsVisibilityFragment f : cv.getVisibilityFragments())
        {
            if (CmiModelUtil.isSameBmObject(f.getCommand(), cmd))
            {
                return f;
            }
        }
        return null;
    }

    /**
     * Casts a resolved reference target to an {@link Command}, or throws a clear
     * error naming the offending FQN. The caller resolves the FQN and rejects a
     * missing target first, so this only guards the "resolved to something that is
     * not a command" case (e.g. a catalog or attribute FQN was passed).
     */
    private static Command asCommand(Object o, String fqn)
    {
        if (o instanceof Command)
        {
            return (Command) o;
        }
        throw new RuntimeException("Target is not a command: " + fqn //$NON-NLS-1$
            + " - expected a CommonCommand (CommonCommand.X) or an object command " //$NON-NLS-1$
            + "(e.g. Catalog.X.Command.Y)."); //$NON-NLS-1$
    }

    // ---- command placement + order (group membership, and optional order within it) ----

    /** Result of a command-placement (and optional order) write. */
    public static final class CommandPlacementResult
    {
        public final String commandInterfaceFqn;
        /** placed | unchanged - group membership + position within the placement fragment */
        public final String action;
        /** display name of the affected command */
        public final String command;
        /** resolved target group - a StandardCommandGroup name, or CommandGroup.&lt;name&gt; */
        public final String group;
        /** placed | unchanged | null (null = order was not requested) */
        public final String orderAction;

        CommandPlacementResult(String commandInterfaceFqn, String action, String command, String group,
            String orderAction)
        {
            this.commandInterfaceFqn = commandInterfaceFqn;
            this.action = action;
            this.command = command;
            this.group = group;
            this.orderAction = orderAction;
        }
    }

    /**
     * Sets the placement of a command in the configuration's MAIN SECTION command
     * interface - which command-interface group ({@code CommandsPlacement}) it
     * belongs to and, optionally, its position within that group's separate
     * {@code CommandsOrder} overlay. Mirrors {@link #setMainSectionCommandVisibility}:
     * {@code command} is the already-resolved command object, {@code commandFqn} is
     * only for error messages. Exactly one of {@code group} (an already-resolved
     * custom {@code metadata.mdclass.CommandGroup}) / {@code standardGroupToken} (a
     * friendly name or platform token for one of the six built-in groups) must be
     * given - see {@link #setCommandPlacement} for the full semantics.
     *
     * @throws RuntimeException if {@code command} is not a {@link Command}, the
     *         main section has no command interface yet, or the group cannot be
     *         resolved
     */
    public CommandPlacementResult setMainSectionCommandPlacement(Configuration config, Object command,
        String commandFqn, Object group, String standardGroupToken, Integer orderIndex)
    {
        Command cmd = asCommand(command, commandFqn);
        Object aci = config.getMainSectionCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        return setCommandPlacement(ci, "the configuration main section", cmd, group, standardGroupToken, //$NON-NLS-1$
            orderIndex);
    }

    /**
     * Sets the placement of a command in a SUBSYSTEM's command interface. Mirrors
     * {@link #setSubsystemCommandVisibility}; see {@link #setCommandPlacement} for
     * the full semantics.
     *
     * @throws RuntimeException if {@code command} is not a {@link Command}, the
     *         subsystem has no command interface yet, or the group cannot be
     *         resolved
     */
    public CommandPlacementResult setSubsystemCommandPlacement(Subsystem subsystem, Object command,
        String commandFqn, Object group, String standardGroupToken, Integer orderIndex)
    {
        Command cmd = asCommand(command, commandFqn);
        Object aci = subsystem.getCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        String label = "subsystem '" + subsystem.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return setCommandPlacement(ci, label, cmd, group, standardGroupToken, orderIndex);
    }

    /**
     * Batch reorder entry point for the configuration's MAIN SECTION command interface.
     * See {@link #setCommandOrder} for the overlay semantics. Exactly one of
     * {@code group} / {@code standardGroupToken} must be given.
     */
    public CommandPlacementResult setMainSectionCommandOrder(Configuration config, Object group,
        String standardGroupToken, List<Command> ordered, List<String> commandLabels)
    {
        Object aci = config.getMainSectionCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        return setCommandOrder(ci, "the configuration main section", group, standardGroupToken, ordered, //$NON-NLS-1$
            commandLabels);
    }

    /**
     * Batch reorder entry point for a SUBSYSTEM's command interface.
     * See {@link #setCommandOrder} for the overlay semantics. Exactly one of
     * {@code group} / {@code standardGroupToken} must be given.
     */
    public CommandPlacementResult setSubsystemCommandOrder(Subsystem subsystem, Object group,
        String standardGroupToken, List<Command> ordered, List<String> commandLabels)
    {
        Object aci = subsystem.getCommandInterface();
        CommandInterface ci = (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
        String label = "subsystem '" + subsystem.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return setCommandOrder(ci, label, group, standardGroupToken, ordered, commandLabels);
    }

    /**
     * Places {@code cmd} into a command-interface group ({@code CommandsPlacement})
     * and, when {@code orderIndex} is given, ALSO sets its position within that
     * group's SEPARATE {@code CommandsOrder} overlay - two independent optional
     * overlays on the same {@link CommandInterface} (a real .cmi can carry a
     * placement fragment for a group with no matching order fragment at all, so
     * order is written only on request, never inferred). Exactly one of
     * {@code group} (an already-resolved top-level {@code metadata.mdclass.CommandGroup}
     * - a custom group) / {@code standardGroupToken} (a friendly name - Important /
     * Normal / SeeAlso / Create / Reports / Service - or a bare / {@code
     * StandardCommandGroup.}-prefixed platform token, for one of the six built-in
     * groups) must be given.
     * <p>
     * Single-group invariant: {@code cmd} is removed from every OTHER placement
     * fragment, and (when order is requested) every other order fragment, before
     * being added to the target one - mirroring {@link #applyCommandVisibility}'s
     * prune-on-empty. A standard group is reused by name when one is already
     * referenced by another placement/order fragment of this CI; otherwise a
     * fresh one is created from the platform name/category map, with its priority
     * copied from a same-category sibling if any (real .cmi files never persist a
     * StandardCommandGroup's category/priority - only its bare name - so this is
     * an in-session nicety, not a disk-format requirement).
     * <p>
     * Same refusal as {@link #applyCommandVisibility}: a subsystem/main-section
     * WITHOUT a persisted command interface cannot have one created from scratch
     * through the generic BM write task.
     *
     * @param ownerLabel names the owner ("the configuration main section" /
     *        "subsystem 'X'") for the missing-CI refusal message
     * @param group an already-resolved custom group, or {@code null} when
     *        {@code standardGroupToken} is given instead
     * @param orderIndex 0-based target position within the group; {@code null}
     *        skips the CommandsOrder overlay entirely and, in CommandsPlacement,
     *        appends instead of inserting at a position
     * @throws RuntimeException if the command interface does not exist yet,
     *         neither/both of {@code group} / {@code standardGroupToken} are
     *         given, {@code group} does not resolve to a command group, or
     *         {@code standardGroupToken} names none of the six built-in groups
     */
    private CommandPlacementResult setCommandPlacement(CommandInterface ci, String ownerLabel, Command cmd,
        Object group, String standardGroupToken, Integer orderIndex)
    {
        String ciFqn = (ci != null) ? topObjectFqn(ci) : null;
        if (ciFqn == null)
        {
            throw new RuntimeException(ownerLabel + " has no command interface yet. Setting a command's " //$NON-NLS-1$
                + "placement requires an existing command interface; creating one from scratch is not " //$NON-NLS-1$
                + "supported by this operation. Adjust the command interface of " + ownerLabel + " in EDT " //$NON-NLS-1$ //$NON-NLS-2$
                + "once (for example show or hide any command), then retry."); //$NON-NLS-1$
        }
        boolean hasCustom = group != null;
        boolean hasStandard = standardGroupToken != null && !standardGroupToken.trim().isEmpty();
        if (hasCustom == hasStandard)
        {
            throw new RuntimeException("set_command_placement requires exactly one group: a custom " //$NON-NLS-1$
                + "CommandGroup.<name> or a standard group name (Important / Normal / SeeAlso / Create / " //$NON-NLS-1$
                + "Reports / Service)."); //$NON-NLS-1$
        }
        CommandGroup targetGroup = hasCustom ? asCommandGroup(group)
            : findOrCreateStandardGroup(ci, resolveStandardGroupName(standardGroupToken));
        String groupLabel = groupDisplayName(targetGroup);

        // ---- CommandsPlacement (always) ----
        CommandsPlacement cp = ci.getCommandsPlacement();
        if (cp == null)
        {
            cp = CmiFactory.eINSTANCE.createCommandsPlacement();
            ci.setCommandsPlacement(cp);
        }
        CommandsPlacementFragment placementFrag = findPlacementFragment(cp, targetGroup);
        removeFromOtherPlacementFragments(cp.getPlacementFragments(), placementFrag, cmd);
        if (placementFrag == null)
        {
            placementFrag = CmiFactory.eINSTANCE.createCommandsPlacementFragment();
            placementFrag.setGroup(targetGroup);
            cp.getPlacementFragments().add(placementFrag);
        }
        String action = placeCommand(placementFrag.getCommands(), cmd, orderIndex);

        // ---- CommandsOrder ----
        // The single-group invariant applies to the order overlay too: cmd must be pruned from
        // every OTHER group's order fragment on ANY group change - even when no order is requested -
        // otherwise a stale cross-fragment reference to cmd survives in the group it left (the
        // CommandsPlacement move alone does not clean the separate CommandsOrder overlay). Fragment
        // creation and positioning stay gated on an explicit order request, so a placement-only call
        // still never forces an order overlay into existence.
        String orderAction = null;
        CommandsOrder co = ci.getCommandsOrder();
        CommandsOrderFragment orderFrag = (co != null) ? findOrderFragment(co, targetGroup) : null;
        if (co != null)
        {
            removeFromOtherOrderFragments(co.getOrderFragments(), orderFrag, cmd);
        }
        if (orderIndex != null)
        {
            if (co == null)
            {
                co = CmiFactory.eINSTANCE.createCommandsOrder();
                ci.setCommandsOrder(co);
            }
            if (orderFrag == null)
            {
                orderFrag = CmiFactory.eINSTANCE.createCommandsOrderFragment();
                orderFrag.setGroup(targetGroup);
                co.getOrderFragments().add(orderFrag);
            }
            orderAction = placeCommand(orderFrag.getCommands(), cmd, orderIndex);
        }
        // Mirror applyCommandVisibility's prune-on-empty at the container level: if the invariant
        // prune above removed the last order fragment (and no order was requested to re-add one),
        // drop the emptied CommandsOrder overlay rather than leave a dangling empty container.
        if (co != null && co.getOrderFragments().isEmpty())
        {
            ci.setCommandsOrder(null);
        }

        return new CommandPlacementResult(ciFqn, action, CmiModelUtil.getCommandName(cmd), groupLabel, orderAction);
    }

    /**
     * Batch reorder: positions the supplied {@code ordered} commands at the front of
     * the group's {@code CommandsOrder} overlay, in the given sequence, in a single
     * pass. Touches ONLY the order overlay - no placement/visibility change, and
     * never creates a {@code CommandsPlacement} fragment. Commands already in the
     * fragment but absent from the list keep their relative order after the listed
     * ones; commands in the list but not yet in the fragment are added. Exactly one
     * of {@code group} (a resolved custom {@code CommandGroup}) / {@code standardGroupToken}
     * must be given, same rule as {@link #setCommandPlacement}.
     *
     * @param ci the command interface (main section or subsystem)
     * @param ownerLabel human-readable owner, for error messages
     * @param group resolved custom command group, or {@code null}
     * @param standardGroupToken standard group name/token, or {@code null}
     * @param ordered the commands in their desired leading order
     * @param commandLabels the command names/FQNs as supplied by the caller (for the result)
     * @return the placement result (action="reordered")
     * @throws RuntimeException if the command interface does not exist yet, the
     *         group spec is missing/ambiguous, or the group does not resolve
     */
    public CommandPlacementResult setCommandOrder(CommandInterface ci, String ownerLabel, Object group,
        String standardGroupToken, List<Command> ordered, List<String> commandLabels)
    {
        if (ordered == null || ordered.isEmpty())
        {
            throw new RuntimeException("set_command_order requires a non-empty commands list."); //$NON-NLS-1$
        }
        String ciFqn = (ci != null) ? topObjectFqn(ci) : null;
        if (ciFqn == null)
        {
            throw new RuntimeException(ownerLabel + " has no command interface yet. Reordering requires an " //$NON-NLS-1$
                + "existing command interface; adjust it in EDT once (show or hide any command), then retry."); //$NON-NLS-1$
        }
        boolean hasCustom = group != null;
        boolean hasStandard = standardGroupToken != null && !standardGroupToken.trim().isEmpty();
        if (hasCustom == hasStandard)
        {
            throw new RuntimeException("set_command_order requires exactly one group: a custom " //$NON-NLS-1$
                + "CommandGroup.<name> or a standard group name (Important / Normal / SeeAlso / Create / " //$NON-NLS-1$
                + "Reports / Service)."); //$NON-NLS-1$
        }
        CommandGroup targetGroup = hasCustom ? asCommandGroup(group)
            : findOrCreateStandardGroup(ci, resolveStandardGroupName(standardGroupToken));
        String groupLabel = groupDisplayName(targetGroup);

        CommandsOrder co = ci.getCommandsOrder();
        if (co == null)
        {
            co = CmiFactory.eINSTANCE.createCommandsOrder();
            ci.setCommandsOrder(co);
        }
        CommandsOrderFragment orderFrag = findOrderFragment(co, targetGroup);
        if (orderFrag == null)
        {
            orderFrag = CmiFactory.eINSTANCE.createCommandsOrderFragment();
            orderFrag.setGroup(targetGroup);
            co.getOrderFragments().add(orderFrag);
        }
        EList<Command> cur = orderFrag.getCommands();
        // Preserve commands already present but absent from the reorder list,
        // appending them after the listed ones in their original order.
        List<Command> tail = new ArrayList<>();
        for (Command c : cur)
        {
            boolean inList = false;
            for (Command o : ordered)
            {
                if (CmiModelUtil.isSameBmObject(o, c))
                {
                    inList = true;
                    break;
                }
            }
            if (!inList)
            {
                tail.add(c);
            }
        }
        cur.clear();
        for (Command o : ordered)
        {
            cur.add(o);
        }
        for (Command c : tail)
        {
            cur.add(c);
        }
        return new CommandPlacementResult(ciFqn, "reordered", commandLabels.toString(), groupLabel, //$NON-NLS-1$
            "set order of " + ordered.size() + " command(s)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Group identity for fragment lookup. A {@link StandardCommandGroup} is NOT a
     * separately BM-identified object - every one shares the same {@code bmGetId()},
     * so {@link CmiModelUtil#isSameBmObject} treats ANY two standard groups as equal
     * and would collapse every standard placement/order onto the first standard
     * fragment in the list. Standard groups are therefore compared by their canonical
     * name; a custom {@code metadata.mdclass.CommandGroup} is a real top-level BM
     * object with a distinct id, so it keeps the BM-identity comparison. Two groups of
     * different kinds (one standard, one custom) are never the same group.
     */
    private static boolean isSameGroup(CommandGroup a, CommandGroup b)
    {
        if (a == null || b == null)
        {
            return false;
        }
        StandardCommandGroup sa = asStandardGroup(a);
        StandardCommandGroup sb = asStandardGroup(b);
        if (sa != null || sb != null)
        {
            return sa != null && sb != null && sa.getName() != null && sa.getName().equalsIgnoreCase(sb.getName());
        }
        return CmiModelUtil.isSameBmObject(a, b);
    }

    /**
     * Finds the placement fragment for {@code target} using {@link #isSameGroup}
     * (name identity for standard groups, BM identity for custom groups), or null.
     */
    private static CommandsPlacementFragment findPlacementFragment(CommandsPlacement cp, CommandGroup target)
    {
        if (cp == null)
        {
            return null;
        }
        for (CommandsPlacementFragment f : cp.getPlacementFragments())
        {
            if (isSameGroup(f.getGroup(), target))
            {
                return f;
            }
        }
        return null;
    }

    /**
     * Finds the order fragment for {@code target} using {@link #isSameGroup}
     * (name identity for standard groups, BM identity for custom groups), or null.
     */
    private static CommandsOrderFragment findOrderFragment(CommandsOrder co, CommandGroup target)
    {
        if (co == null)
        {
            return null;
        }
        for (CommandsOrderFragment f : co.getOrderFragments())
        {
            if (isSameGroup(f.getGroup(), target))
            {
                return f;
            }
        }
        return null;
    }

    /**
     * Removes {@code cmd} from every CommandsPlacement fragment other than
     * {@code keep} (the single-group invariant - a command belongs to at most one
     * group at a time), pruning any fragment left with no commands.
     */
    private static void removeFromOtherPlacementFragments(EList<CommandsPlacementFragment> fragments,
        CommandsPlacementFragment keep, Command cmd)
    {
        for (CommandsPlacementFragment f : new ArrayList<>(fragments))
        {
            if (f == keep)
            {
                continue;
            }
            Iterator<Command> it = f.getCommands().iterator();
            while (it.hasNext())
            {
                if (CmiModelUtil.isSameBmObject(it.next(), cmd))
                {
                    it.remove();
                }
            }
            if (f.getCommands().isEmpty())
            {
                fragments.remove(f);
            }
        }
    }

    /**
     * Removes {@code cmd} from every CommandsOrder fragment other than
     * {@code keep} (the same single-group invariant, applied to the order
     * overlay), pruning any fragment left with no commands.
     */
    private static void removeFromOtherOrderFragments(EList<CommandsOrderFragment> fragments,
        CommandsOrderFragment keep, Command cmd)
    {
        for (CommandsOrderFragment f : new ArrayList<>(fragments))
        {
            if (f == keep)
            {
                continue;
            }
            Iterator<Command> it = f.getCommands().iterator();
            while (it.hasNext())
            {
                if (CmiModelUtil.isSameBmObject(it.next(), cmd))
                {
                    it.remove();
                }
            }
            if (f.getCommands().isEmpty())
            {
                fragments.remove(f);
            }
        }
    }

    /**
     * Adds {@code cmd} to {@code commands} at {@code index}, or appends when
     * {@code index} is {@code null}; an out-of-range {@code index} is clamped
     * into range the same way for both the insert and the reposition paths
     * (negative to the front, too large to the end). Repositions via
     * {@link EList#move(int, int)} when {@code cmd} is already present and a
     * target index is given.
     *
     * @return "placed" when the list actually changed (added or moved), or
     *         "unchanged" when {@code cmd} was already there and either no index
     *         was given (left exactly where it was) or it was already at the
     *         target index
     */
    private static String placeCommand(EList<Command> commands, Command cmd, Integer index)
    {
        int existingIdx = -1;
        for (int i = 0; i < commands.size(); i++)
        {
            if (CmiModelUtil.isSameBmObject(commands.get(i), cmd))
            {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx < 0)
        {
            int insertAt = (index != null) ? Math.min(Math.max(index, 0), commands.size()) : commands.size();
            commands.add(insertAt, cmd);
            return "placed"; //$NON-NLS-1$
        }
        if (index == null)
        {
            return "unchanged"; //$NON-NLS-1$
        }
        int target = Math.min(Math.max(index, 0), commands.size() - 1);
        if (target == existingIdx)
        {
            return "unchanged"; //$NON-NLS-1$
        }
        commands.move(target, existingIdx);
        return "placed"; //$NON-NLS-1$
    }

    /** Friendly name (lower-cased) -> canonical platform StandardCommandGroup name. */
    private static final Map<String, String> STANDARD_GROUP_FRIENDLY_NAMES = buildStandardGroupFriendlyNames();

    private static Map<String, String> buildStandardGroupFriendlyNames()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("important", "NavigationPanelImportant"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("normal", "NavigationPanelOrdinary"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("seealso", "NavigationPanelSeeAlso"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("create", "ActionsPanelCreate"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("reports", "ActionsPanelReports"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("service", "ActionsPanelTools"); //$NON-NLS-1$ //$NON-NLS-2$
        return m;
    }

    /** The 6 canonical platform StandardCommandGroup names, each mapped to its category. */
    private static final Map<String, CommandGroupCategory> STANDARD_GROUP_CATEGORIES =
        buildStandardGroupCategories();

    private static Map<String, CommandGroupCategory> buildStandardGroupCategories()
    {
        Map<String, CommandGroupCategory> m = new LinkedHashMap<>();
        m.put("NavigationPanelImportant", CommandGroupCategory.NAVIGATION_PANEL); //$NON-NLS-1$
        m.put("NavigationPanelOrdinary", CommandGroupCategory.NAVIGATION_PANEL); //$NON-NLS-1$
        m.put("NavigationPanelSeeAlso", CommandGroupCategory.NAVIGATION_PANEL); //$NON-NLS-1$
        m.put("ActionsPanelCreate", CommandGroupCategory.ACTIONS_PANEL); //$NON-NLS-1$
        m.put("ActionsPanelReports", CommandGroupCategory.ACTIONS_PANEL); //$NON-NLS-1$
        m.put("ActionsPanelTools", CommandGroupCategory.ACTIONS_PANEL); //$NON-NLS-1$
        return m;
    }

    /**
     * Resolves {@code token} (a friendly name - Important / Normal / SeeAlso /
     * Create / Reports / Service, case-insensitive - or the bare platform token,
     * optionally prefixed {@code StandardCommandGroup.}) to a canonical platform
     * StandardCommandGroup name (e.g. {@code NavigationPanelImportant}).
     *
     * @throws RuntimeException if {@code token} is not one of the six known groups
     */
    private static String resolveStandardGroupName(String token)
    {
        String t = token == null ? "" : token.trim(); //$NON-NLS-1$
        if (t.regionMatches(true, 0, "StandardCommandGroup.", 0, "StandardCommandGroup.".length())) //$NON-NLS-1$ //$NON-NLS-2$
        {
            t = t.substring("StandardCommandGroup.".length()); //$NON-NLS-1$
        }
        String friendly = STANDARD_GROUP_FRIENDLY_NAMES.get(t.toLowerCase(Locale.ROOT));
        if (friendly != null)
        {
            return friendly;
        }
        for (String canonical : STANDARD_GROUP_CATEGORIES.keySet())
        {
            if (canonical.equalsIgnoreCase(t))
            {
                return canonical;
            }
        }
        throw new RuntimeException("Unknown command group '" + token //$NON-NLS-1$
            + "'. Use a friendly name (Important / Normal / SeeAlso / Create / Reports / Service), " //$NON-NLS-1$
            + "a StandardCommandGroup.<Name> token (NavigationPanelImportant / NavigationPanelOrdinary / " //$NON-NLS-1$
            + "NavigationPanelSeeAlso / ActionsPanelCreate / ActionsPanelReports / ActionsPanelTools), " //$NON-NLS-1$
            + "or CommandGroup.<name> for a custom group."); //$NON-NLS-1$
    }

    /**
     * Finds an existing StandardCommandGroup already referenced by name from
     * another placement/order fragment of {@code ci} and reuses that exact
     * object (its category/priority come along for free, since it IS that same
     * object); otherwise creates a fresh one from the platform name/category
     * map, with its priority copied from a same-category sibling found in the
     * same scan, or left at the EMF default (0) when there is none.
     */
    private static CommandGroup findOrCreateStandardGroup(CommandInterface ci, String canonicalName)
    {
        CommandGroupCategory category = STANDARD_GROUP_CATEGORIES.get(canonicalName);
        StandardCommandGroup existing = null;
        StandardCommandGroup categorySibling = null;
        CommandsPlacement cp = ci.getCommandsPlacement();
        if (cp != null)
        {
            for (CommandsPlacementFragment f : cp.getPlacementFragments())
            {
                StandardCommandGroup scg = asStandardGroup(f.getGroup());
                if (scg == null)
                {
                    continue;
                }
                if (canonicalName.equalsIgnoreCase(scg.getName()))
                {
                    existing = scg;
                    break;
                }
                if (categorySibling == null && category != null && category.equals(scg.getCategory()))
                {
                    categorySibling = scg;
                }
            }
        }
        if (existing == null)
        {
            CommandsOrder co = ci.getCommandsOrder();
            if (co != null)
            {
                for (CommandsOrderFragment f : co.getOrderFragments())
                {
                    StandardCommandGroup scg = asStandardGroup(f.getGroup());
                    if (scg == null)
                    {
                        continue;
                    }
                    if (canonicalName.equalsIgnoreCase(scg.getName()))
                    {
                        existing = scg;
                        break;
                    }
                    if (categorySibling == null && category != null && category.equals(scg.getCategory()))
                    {
                        categorySibling = scg;
                    }
                }
            }
        }
        if (existing != null)
        {
            return existing;
        }
        StandardCommandGroup created = McoreFactory.eINSTANCE.createStandardCommandGroup();
        created.setName(canonicalName);
        created.setCategory(category);
        if (categorySibling != null)
        {
            created.setPriority(categorySibling.getPriority());
        }
        // A detached, factory-created StandardCommandGroup is an IBmObject with no BM namespace and
        // no resource, so the BM transaction's ReferenceValueFactory cannot build a persistable
        // reference to it and the commit fails with "Failed to persist reference value". EDT's own
        // XML reader represents a not-yet-resolved reference to such a non-BM object as a proxy with
        // an "unresolved:/<symbolic name>" URI; the CommandsPlacement/Order writer + SymbolicNameService
        // then serialize that proxy back to the bare group name (<group>NavigationPanelImportant</group>).
        // eSetProxyURI leaves the already-set name/category/priority slots intact, so isSameGroup (which
        // reads getName()) and the rest of this class keep working.
        ((InternalEObject)created).eSetProxyURI(URI.createURI("unresolved:/" + canonicalName)); //$NON-NLS-1$
        return created;
    }

    private static StandardCommandGroup asStandardGroup(CommandGroup g)
    {
        return (g instanceof StandardCommandGroup) ? (StandardCommandGroup) g : null;
    }

    /**
     * Casts a resolved reference target to a {@link CommandGroup} (a custom
     * {@code metadata.mdclass.CommandGroup}), or throws a clear error. Mirrors
     * {@link #asCommand}.
     */
    private static CommandGroup asCommandGroup(Object o)
    {
        if (o instanceof CommandGroup)
        {
            return (CommandGroup) o;
        }
        throw new RuntimeException("Resolved group target is not a command group: " //$NON-NLS-1$
            + (o != null ? o.getClass().getSimpleName() : "null") //$NON-NLS-1$
            + " - expected a custom CommandGroup.<name>."); //$NON-NLS-1$
    }

    /** {@code StandardCommandGroup} name, or {@code CommandGroup.<name>} for a custom group. */
    private static String groupDisplayName(CommandGroup group)
    {
        if (group instanceof StandardCommandGroup)
        {
            return ((StandardCommandGroup) group).getName();
        }
        if (group instanceof MdObject)
        {
            return "CommandGroup." + ((MdObject) group).getName(); //$NON-NLS-1$
        }
        return String.valueOf(group);
    }
}
