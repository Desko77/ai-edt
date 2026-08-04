/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 1.42 (RSV 4.2 parity, B3): registry of platform stock form commands plus
 * compatibility checks for the form's owner kind.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li>{@link #DEFAULT_AUTO_ICON_COMMANDS} - 22 commands that the platform
 *       renders with a default icon and "PictureAndText" representation when
 *       added to a form. Mirrors the RSV 4.2 release notes which list the
 *       "22 most-frequent" stock commands. Other valid stock commands are
 *       still accepted, but the agent has to set picture/representation
 *       itself.</li>
 *   <li>{@link #checkOwnerKindCompatibility} - rejects standard commands on
 *       forms whose owner does not have the standard list (DataProcessor,
 *       ExternalDataProcessor, ExternalReport). RSV 4.2 release notes
 *       describe this as a "silent button" bug - the platform creates the
 *       button but never renders it on those forms. We surface the
 *       incompatibility before the write so the agent picks a regular
 *       form-command path.</li>
 * </ul>
 */
public final class StandardCommandRegistry
{
    /**
     * 22 stock commands with default icons. Names match the platform
     * {@code FormStandardCommand} enum constants (English-only - the
     * platform also exposes Russian aliases through the same path, but the
     * underlying enum stores English names).
     */
    public static final Set<String> DEFAULT_AUTO_ICON_COMMANDS;
    static
    {
        Set<String> s = new LinkedHashSet<>();
        s.add("Apply"); //$NON-NLS-1$
        s.add("BusinessProcessStart"); //$NON-NLS-1$
        s.add("ChangePassword"); //$NON-NLS-1$
        s.add("Copy"); //$NON-NLS-1$
        s.add("CreateBasedOn"); //$NON-NLS-1$
        s.add("Find"); //$NON-NLS-1$
        s.add("Generate"); //$NON-NLS-1$
        s.add("Help"); //$NON-NLS-1$
        s.add("Move"); //$NON-NLS-1$
        s.add("Post"); //$NON-NLS-1$
        s.add("PostAndClose"); //$NON-NLS-1$
        s.add("Print"); //$NON-NLS-1$
        s.add("Read"); //$NON-NLS-1$
        s.add("Refresh"); //$NON-NLS-1$
        s.add("ReportGenerate"); //$NON-NLS-1$
        s.add("Save"); //$NON-NLS-1$
        s.add("SetDeletionMark"); //$NON-NLS-1$
        s.add("ShowSearchString"); //$NON-NLS-1$
        s.add("UndoPost"); //$NON-NLS-1$
        s.add("UnsetDeletionMark"); //$NON-NLS-1$
        s.add("Write"); //$NON-NLS-1$
        s.add("WriteAndClose"); //$NON-NLS-1$
        DEFAULT_AUTO_ICON_COMMANDS = Collections.unmodifiableSet(s);
    }

    /**
     * Form owner types that the platform populates with the standard command
     * list. Forms attached to other owners (DataProcessor / external) cannot
     * use stock commands - the platform silently drops them at render time.
     *
     * <p>Type names match the EClass simple names from
     * {@code com._1c.g5.v8.dt.metadata.mdclass}.
     */
    private static final Set<String> COMPATIBLE_OWNER_TYPES;
    static
    {
        Set<String> s = new LinkedHashSet<>();
        s.add("Catalog"); //$NON-NLS-1$
        s.add("Document"); //$NON-NLS-1$
        s.add("ChartOfAccounts"); //$NON-NLS-1$
        s.add("ChartOfCharacteristicTypes"); //$NON-NLS-1$
        s.add("ChartOfCalculationTypes"); //$NON-NLS-1$
        s.add("InformationRegister"); //$NON-NLS-1$
        s.add("AccumulationRegister"); //$NON-NLS-1$
        s.add("AccountingRegister"); //$NON-NLS-1$
        s.add("CalculationRegister"); //$NON-NLS-1$
        s.add("Report"); //$NON-NLS-1$
        s.add("BusinessProcess"); //$NON-NLS-1$
        s.add("Task"); //$NON-NLS-1$
        s.add("ExchangePlan"); //$NON-NLS-1$
        s.add("DocumentJournal"); //$NON-NLS-1$
        COMPATIBLE_OWNER_TYPES = Collections.unmodifiableSet(s);
    }

    private static final Set<String> KNOWN_INCOMPATIBLE_OWNER_TYPES;
    static
    {
        Set<String> s = new LinkedHashSet<>();
        s.add("DataProcessor"); //$NON-NLS-1$
        s.add("ExternalDataProcessor"); //$NON-NLS-1$
        s.add("ExternalReport"); //$NON-NLS-1$
        s.add("CommonForm"); //$NON-NLS-1$
        s.add("Constant"); //$NON-NLS-1$
        s.add("CommonModule"); //$NON-NLS-1$
        KNOWN_INCOMPATIBLE_OWNER_TYPES = Collections.unmodifiableSet(s);
    }

    private StandardCommandRegistry()
    {
    }

    /**
     * @return {@code true} when the platform automatically applies an icon
     *         and {@code PictureAndText} representation to a button bound to
     *         this stock command.
     */
    public static boolean hasAutoIcon(String standardCommandName)
    {
        return standardCommandName != null
            && DEFAULT_AUTO_ICON_COMMANDS.contains(standardCommandName);
    }

    /**
     * Builds the platform FQN for a stock command:
     * {@code Form.StandardCommand.<Name>}.
     */
    public static String buildStandardCommandFqn(String standardCommandName)
    {
        return "Form.StandardCommand." + standardCommandName; //$NON-NLS-1$
    }

    /**
     * Checks whether the form whose owner is of {@code ownerEClassName} can
     * actually render a button bound to a platform stock command. Returns
     * {@code null} on success, an error message for the agent on failure.
     *
     * @param ownerEClassName EClass simple name of the form's owner (e.g.
     *        "Document", "ExternalDataProcessor"). May be {@code null} for
     *        common forms - those are unconditionally rejected.
     */
    public static String checkOwnerKindCompatibility(String ownerEClassName,
        String standardCommandName)
    {
        if (ownerEClassName == null)
        {
            return "Cannot determine the form's owner type. Standard commands " //$NON-NLS-1$
                + "are only valid on document/catalog/register/report/" //$NON-NLS-1$
                + "businessProcess/task forms - use a regular form command " //$NON-NLS-1$
                + "with addCommandHandler instead."; //$NON-NLS-1$
        }
        if (COMPATIBLE_OWNER_TYPES.contains(ownerEClassName))
        {
            return null;
        }
        if (KNOWN_INCOMPATIBLE_OWNER_TYPES.contains(ownerEClassName))
        {
            return "Standard command '" + standardCommandName //$NON-NLS-1$
                + "' is not supported on " + ownerEClassName //$NON-NLS-1$
                + " forms. The platform creates the button but never renders " //$NON-NLS-1$
                + "it - this is the 'silent button' bug from the RSV 4.2 " //$NON-NLS-1$
                + "release notes. Use a regular form command via " //$NON-NLS-1$
                + "addCommandHandler with a procedure in the form module."; //$NON-NLS-1$
        }
        // Unknown owner - permit but warn.
        return null;
    }

    /**
     * Returns a comma-separated, deterministic preview of the auto-icon
     * commands. Used in error messages to point the agent at a known-good
     * subset.
     */
    public static String describeAutoIconCommands()
    {
        StringBuilder sb = new StringBuilder();
        for (String name : DEFAULT_AUTO_ICON_COMMANDS)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * Returns the unmodifiable map of compatible owner types and a short
     * human-readable description for help output. Reserved for future
     * help-topic generators - not used by the dispatch path today.
     */
    public static Map<String, String> getCompatibleOwnerTypes()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : COMPATIBLE_OWNER_TYPES)
        {
            m.put(t, "form owner with platform-provided standard command list"); //$NON-NLS-1$
        }
        return Collections.unmodifiableMap(m);
    }
}
