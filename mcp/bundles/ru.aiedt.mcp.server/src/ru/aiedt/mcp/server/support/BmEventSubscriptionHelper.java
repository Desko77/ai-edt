/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Helper for {@code EventSubscription} metadata: handler-name normalization,
 * source resolution, addEventSubscriptionHandler stub generation. <p>
 *
 * <b>Defensive layer 3.8.1</b>: the platform stores the handler in the form
 * {@code "CommonModule.X.Method"} (with the mandatory {@code CommonModule.}
 * prefix). Without the prefix the configuration is formally valid and even
 * passes the EDT visual editor's checks - but at runtime, on UpdateDBCfg, the
 * platform raises *"Reference to unknown method - X.Method"* and all the
 * agent's edits have to be rolled back manually.
 *
 * <p>This helper accepts both forms ({@code "X.Method"} and the canonical
 * {@code "CommonModule.X.Method"}) and normalizes them to the prefixed shape
 * before persisting. It also early-fails when the referenced common module
 * does not exist in the project, so the agent can correct the call instead of
 * waiting for a UpdateDBCfg failure several minutes later.
 */
public final class BmEventSubscriptionHelper
{
    /** Required prefix for the {@code handler} property of an EventSubscription. */
    public static final String COMMON_MODULE_PREFIX = "CommonModule.";

    private BmEventSubscriptionHelper()
    {
        // utility
    }

    /**
     * Standard EventSubscription event tokens (the {@code <event>} element is a
     * plain String in the EDT model, not an enum). Used for SOFT validation
     * only - an unrecognized value is set anyway (the platform may define events
     * this list does not carry) with a warning, never blocked. Harvested from
     * the stock BSP configuration plus the well-known posting / choice-data
     * events not present there.
     */
    private static final Set<String> KNOWN_EVENTS = new HashSet<>(Arrays.asList(
        "BeforeWrite", "OnWrite", "BeforeDelete", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Filling", "FillCheckProcessing", "OnCopy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Posting", "UndoPosting", //$NON-NLS-1$ //$NON-NLS-2$
        "OnSetNewNumber", "OnSetNewCode", //$NON-NLS-1$ //$NON-NLS-2$
        "FormGetProcessing", "PresentationGetProcessing", //$NON-NLS-1$ //$NON-NLS-2$
        "PresentationFieldsGetProcessing", "ChoiceDataGetProcessing", //$NON-NLS-1$ //$NON-NLS-2$
        "OnSendDataToMaster", "OnReceiveDataFromMaster", //$NON-NLS-1$ //$NON-NLS-2$
        "OnSendDataToSlave", "OnReceiveDataFromSlave", "OnSendNodeDataToSlave")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * True when {@code event} is one of the recognized standard event tokens.
     * A false result is advisory only - the caller still sets the value and
     * surfaces {@link #commonEventsHint()} as a warning.
     */
    public static boolean isKnownEvent(String event)
    {
        return event != null && KNOWN_EVENTS.contains(event.trim());
    }

    /**
     * Short list of the most common events, for the "unrecognized event" hint.
     */
    public static String commonEventsHint()
    {
        return "Common events: BeforeWrite, OnWrite, BeforeDelete, Filling, " //$NON-NLS-1$
            + "FillCheckProcessing, OnCopy, Posting, UndoPosting, OnSetNewNumber."; //$NON-NLS-1$
    }

    /**
     * Result of a handler normalization step.
     */
    public static final class NormalizationResult
    {
        public final String input;
        public final String normalized;
        public final boolean changed;
        public final String moduleName;
        public final String methodName;

        private NormalizationResult(String input, String normalized, boolean changed,
            String moduleName, String methodName)
        {
            this.input = input;
            this.normalized = normalized;
            this.changed = changed;
            this.moduleName = moduleName;
            this.methodName = methodName;
        }

        public static NormalizationResult of(String input, String normalized, String module,
            String method)
        {
            boolean changed = !java.util.Objects.equals(input, normalized);
            return new NormalizationResult(input, normalized, changed, module, method);
        }
    }

    /**
     * Normalizes an EventSubscription handler reference to its full
     * {@code CommonModule.X.Method} form. Accepts:
     * <ul>
     *   <li>{@code "Method"} - rejected, ambiguous - returns null</li>
     *   <li>{@code "Module.Method"} - prepends {@code CommonModule.}</li>
     *   <li>{@code "CommonModule.Module.Method"} - returned as-is</li>
     * </ul>
     *
     * @param handler raw handler string; may be null/empty
     * @return result with normalized form and parsed module/method, or null
     *         when the input is too ambiguous or empty
     */
    public static NormalizationResult normalizeHandler(String handler)
    {
        if (handler == null)
        {
            return null;
        }
        String trimmed = handler.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        if (trimmed.startsWith(COMMON_MODULE_PREFIX))
        {
            String tail = trimmed.substring(COMMON_MODULE_PREFIX.length());
            int dot = tail.indexOf('.');
            if (dot <= 0 || dot >= tail.length() - 1)
            {
                return null;
            }
            return NormalizationResult.of(trimmed, trimmed, tail.substring(0, dot),
                tail.substring(dot + 1));
        }
        // "Module.Method" -> "CommonModule.Module.Method"
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot >= trimmed.length() - 1)
        {
            // Single token - can't determine module
            return null;
        }
        // Check for accidental two-dot ("a.b.c") without prefix
        int secondDot = trimmed.indexOf('.', dot + 1);
        if (secondDot != -1)
        {
            // Likely already qualified but with a different prefix (e.g. "Common.X.Method")
            // - we accept and normalize first segment to CommonModule
            String module = trimmed.substring(dot + 1, secondDot);
            String method = trimmed.substring(secondDot + 1);
            String normalized = COMMON_MODULE_PREFIX + module + "." + method;
            return NormalizationResult.of(trimmed, normalized, module, method);
        }
        String module = trimmed.substring(0, dot);
        String method = trimmed.substring(dot + 1);
        String normalized = COMMON_MODULE_PREFIX + module + "." + method;
        return NormalizationResult.of(trimmed, normalized, module, method);
    }

    /**
     * Verifies that a CommonModule with the given name exists in the project's
     * configuration. Used as an early-fail check before persisting the handler.
     *
     * @param config       project configuration (already resolved by caller)
     * @param moduleName   short name (without the {@code CommonModule.} prefix)
     * @return true when the module exists
     */
    public static boolean commonModuleExists(Configuration config, String moduleName)
    {
        if (config == null || moduleName == null || moduleName.isEmpty())
        {
            return false;
        }
        try
        {
            Method m = config.getClass().getMethod("getCommonModules"); //$NON-NLS-1$
            Object list = m.invoke(config);
            if (list instanceof EList)
            {
                for (Object o : (EList<?>) list)
                {
                    if (o instanceof MdObject
                        && moduleName.equalsIgnoreCase(((MdObject) o).getName()))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("commonModuleExists failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * Builds an {@code commonModuleNotFound} {@link MetadataGuards.BlockedGuardException}
     * for use in mutation lambdas: when {@link #normalizeHandler} succeeds but
     * the referenced module does not exist in the project, throw this instead
     * of letting EDT propagate a less informative error.
     */
    public static MetadataGuards.BlockedGuardException commonModuleNotFound(String moduleName)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moduleName", moduleName); //$NON-NLS-1$
        data.put("expectedFqn", "CommonModule." + moduleName); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Handler references a non-existent common module: '" + moduleName + "'.",
            "Create the module first via createObject CommonModule." + moduleName
                + ", or fix the handler to reference an existing module.",
            new MetadataGuards.ErrorTag(ErrorTags.COMMON_MODULE_NOT_FOUND.wire(), data)));
    }

    /**
     * Builds an {@code handlerInvalid} exception for cases where the handler
     * string can't be parsed (single-segment, empty after trim, etc).
     */
    public static MetadataGuards.BlockedGuardException handlerInvalid(String input)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("input", input); //$NON-NLS-1$
        data.put("expectedFormat", "CommonModule.<ModuleName>.<MethodName> or <ModuleName>.<MethodName>");
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Cannot parse EventSubscription handler '" + input + "'.",
            "Pass either 'CommonModule.<ModuleName>.<MethodName>' or shorthand '<ModuleName>.<MethodName>'.",
            new MetadataGuards.ErrorTag(ErrorTags.HANDLER_INVALID.wire(), data)));
    }
}
