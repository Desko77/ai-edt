/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Guards the F2 facade folds against a preset bypass.
 * <p>
 * A facade that folds in an operation whose standalone tool a preset disables, while the facade's own
 * category stays enabled under that preset, would let an agent reach the disabled capability through the
 * facade - a bypass (found live 2026-07-20: {@code edit_metadata} folding the REFACTORING trio, and
 * {@code extension_workshop} folding the APPLICATIONS deployment ops, both reachable under Code Review).
 * The fix routes those delegated operations through {@code ToolGate}, so the facade rejects exactly when
 * the standalone would. This test recomputes the hazard from the LIVE preset disabled-sets and fails if
 * any hazardous fold is not in {@link #GATED} - so a future category or preset change that reopens a
 * bypass breaks the build instead of shipping.
 * </p>
 * <p>
 * {@link #FOLDS} and {@link #GATED} are declared here (the folds live in facade switch/registry code that
 * a test cannot introspect); everything else - each tool's category and each preset's disabled set - is
 * read live from {@link ToolProfile}, which is static enum data and needs no EDT runtime.
 * </p>
 */
public class FoldPresetHazardTest
{
    /** Which facade folds in which standalone tools (F2). Read live for categories, declared here for the wiring. */
    private static final Map<String, List<String>> FOLDS = Map.of(
        "edit_metadata", //$NON-NLS-1$
        List.of("delete_metadata_object", "rename_metadata_object", "add_metadata_attribute"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "extension_workshop", //$NON-NLS-1$
        List.of("install_extension", "uninstall_extension", "list_extension", "export_extension", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "extension_lifecycle", "extension_diff", "list_interceptors"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "code_search", //$NON-NLS-1$
        List.of("search_in_code", "find_references", "go_to_definition", "get_method_call_hierarchy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "get_symbol_info", "get_content_assist", "get_outgoing_structures"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "yaxunit_tests", //$NON-NLS-1$
        List.of("run_yaxunit_tests", "debug_yaxunit_tests"), //$NON-NLS-1$ //$NON-NLS-2$
        "launch_debugger", //$NON-NLS-1$
        List.of("debug_launch", "set_breakpoint", "remove_breakpoint", "list_breakpoints", "wait_for_break", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "resume", "step", "evaluate_expression", "get_variables", "debug_status", "set_variable", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "terminate_launch", "run_to_line", "set_exception_breakpoint", "start_profiling", "get_profiling_results")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * The (facade, standalone) fold pairs whose delegated handler is gate-checked. edit_metadata and
     * extension_workshop route registered standalones through {@code ToolGate.gateOrNull}; yaxunit_tests
     * gates its debug MODE (debug_yaxunit_tests is the DEBUG-group name a preset disables, reached as a
     * mode and backed today by a deprecated alias) through {@code ToolGate.gateIfPresetDisabled}, so the
     * gate keeps holding once that alias is retired.
     */
    private static final Set<String> GATED = Set.of(
        "edit_metadata/delete_metadata_object", "edit_metadata/rename_metadata_object", //$NON-NLS-1$ //$NON-NLS-2$
        "edit_metadata/add_metadata_attribute", //$NON-NLS-1$
        "extension_workshop/install_extension", "extension_workshop/uninstall_extension", //$NON-NLS-1$ //$NON-NLS-2$
        "extension_workshop/list_extension", "extension_workshop/export_extension", //$NON-NLS-1$ //$NON-NLS-2$
        "yaxunit_tests/debug_yaxunit_tests"); //$NON-NLS-1$

    @Test
    public void everyCrossPresetFoldHazardIsGateChecked()
    {
        List<String> ungated = new ArrayList<>();
        for (ToolProfile preset : ToolProfile.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue; // CUSTOM has no opinion of its own.
            }
            for (Map.Entry<String, List<String>> fold : FOLDS.entrySet())
            {
                String facade = fold.getKey();
                if (disabled.contains(facade))
                {
                    continue; // The whole facade is off under this preset - no shortcut to bypass.
                }
                for (String standalone : fold.getValue())
                {
                    if (disabled.contains(standalone) && !GATED.contains(facade + "/" + standalone)) //$NON-NLS-1$
                    {
                        ungated.add(preset.name() + ": " + facade + " -> " + standalone); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
        }
        assertTrue("fold operations that bypass a preset without a ToolGate check: " + ungated, //$NON-NLS-1$
            ungated.isEmpty());
    }

    @Test
    public void everyGatedPairIsAKnownFold()
    {
        // No gate declared for a fold that does not exist - keeps GATED honest against FOLDS.
        for (String pair : GATED)
        {
            String[] parts = pair.split("/", 2); //$NON-NLS-1$
            List<String> folded = FOLDS.get(parts[0]);
            assertFalse("GATED names a facade absent from FOLDS: " + pair, folded == null); //$NON-NLS-1$
            assertTrue("GATED names a standalone that facade does not fold: " + pair, //$NON-NLS-1$
                folded.contains(parts[1]));
        }
    }
}
