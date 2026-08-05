/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolProfile;

/**
 * Locks in the canonical-surface contract: every legacy standalone tool that
 * {@link ToolProfile#CANONICAL} hides from {@code tools/list} must still be reachable through some
 * facade operation - otherwise selecting Canonical would silently strand a capability nobody can
 * find. Headless and pure-POJO: it reads only {@link ToolProfile} (a plain enum with static
 * {@code Set<String>} accessors) plus a hand-verified snapshot of what each of the 14 facades under
 * {@code toolkit/ops/} actually accepts as an operation - no EDT runtime, no tool instantiation, no
 * registry.
 * <p>
 * {@code FACADE_OPERATIONS} below was built by reading each facade's {@code execute()} dispatch
 * directly - a plain {@code switch} for some, an {@code OPS} catalog built from
 * {@code Arrays.asList} for others, a single-source registry map for {@code edit_metadata} - not by
 * trusting any description string. Most facades reuse the hidden standalone's own registered name as
 * their operation value verbatim: {@code diagnostics}, {@code project_admin}, {@code infobase_admin},
 * {@code config_io}, {@code insights}, {@code security_audit}, {@code workspace_marks},
 * {@code docs_lookup} and {@code extension_workshop}'s deploy/inspect operations
 * all do this, so for them "is the standalone name an accepted operation" is a plain string
 * membership check.
 * <p>
 * Three facades do not reuse the standalone name literally, yet the capability is still genuinely
 * reachable through them:
 * <ul>
 * <li>{@code code_search} was designed with its own short vocabulary from day one (its own javadoc
 * documents each pairing, e.g. {@code text_search} for {@code search_in_code}).</li>
 * <li>{@code yaxunit_tests} dispatches on {@code mode}, which has only ever accepted {@code run} and
 * {@code debug} - never the standalone names {@code run_yaxunit_tests} / {@code debug_yaxunit_tests}.
 * {@code help} there is a separate top-level parameter ({@code help=<topic>}), not a mode value, so it
 * is intentionally absent from that facade's operation set.</li>
 * <li>{@code launch_debugger} renamed most of its actions while also keeping the old standalone name
 * as a working alias in the same {@code switch} ({@code debug_launch} alongside {@code launch},
 * {@code debug_status} alongside {@code get_state}, {@code terminate_launch} alongside
 * {@code terminate}) - except for one, {@code evaluate_expression}, which kept only the new name
 * ({@code evaluate}).</li>
 * </ul>
 * {@code RENAMED_STANDALONES} records exactly those ten exceptions. Each entry was verified by
 * confirming the facade's {@code switch} case for the given operation instantiates the very same
 * delegate class the standalone tool is itself registered under - e.g. {@code CodeSearchTool}'s
 * {@code text_search} case calls {@code new CodeTextSearcher()}, and
 * {@code CodeTextSearcher.getName()} returns {@code "search_in_code"} - so the capability is proven
 * identical even though the two names differ. This is not the same thing as inventing a fake alias:
 * every mapping here is backed by matching a real delegate class on both sides.
 */
public class CanonicalSurfaceContractTest
{
    /** Facade name -> the exact set of operation values its {@code execute()} accepts. */
    private static final Map<String, Set<String>> FACADE_OPERATIONS = buildFacadeOperations();

    /**
     * Standalone name hidden by Canonical -> the operation value (in exactly one of the sets above)
     * that actually reaches it, for the ten cases where the two names differ. See the class javadoc
     * for how each entry was verified.
     */
    private static final Map<String, String> RENAMED_STANDALONES = buildRenamedStandalones();

    private static Map<String, Set<String>> buildFacadeOperations()
    {
        Map<String, Set<String>> m = new LinkedHashMap<>();

        // code_search (CodeSearchTool.execute): a plain switch on "operation". Eight real
        // operations plus help - none of them literally matches the standalone name it replaces
        // (see RENAMED_STANDALONES).
        m.put("code_search", Set.of(
            "text_search", "object_references", "method_references", "resolve_symbol",
            "call_hierarchy", "symbol_info", "content_assist", "outgoing_structures", "help"));

        // launch_debugger (LaunchDebuggerTool.execute): a plain switch on "action". Most renamed
        // actions keep BOTH the new short name and the old standalone name as accepted case
        // labels (launch/debug_launch, get_state/debug_status, terminate/terminate_launch) -
        // evaluate is the one exception, see RENAMED_STANDALONES.
        m.put("launch_debugger", Set.of(
            "launch", "debug_launch", "add_breakpoint", "set_breakpoint",
            "set_exception_breakpoint", "run_to_line", "remove_breakpoint", "list_breakpoints",
            "wait_for_break", "get_state", "debug_status", "get_variables", "set_variable",
            "step_over", "step_into", "step_out", "step", "resume", "terminate",
            "terminate_launch", "evaluate", "start_profiling", "get_profiling_results", "help"));

        // edit_metadata (EditMetadataTool.buildRegistry): a single-source registry map, 159
        // operations across the class's own ten OP_GROUP_ORDER groups, plus help handled
        // separately before the registry lookup. Three of them - delete_metadata_object,
        // rename_metadata_object, add_metadata_attribute - are gate-checked delegations to the
        // standalone tool of the same name (ToolGate.gateOrNull) and DO participate in the coverage
        // check below, reached the same literal-name-match way as diagnostics / project_admin / etc.
        // The remaining ~156 have no standalone counterpart and are listed anyway because this
        // snapshot is meant to catch a silent drop from ANY of the 14 facades, not only the ones
        // Canonical currently depends on.
        m.put("edit_metadata", Set.of(
            // Objects (20)
            "create_object", "set_object_property", "add_object_attribute",
            "remove_object_attribute", "add_tabular_section", "remove_tabular_section",
            "add_tabular_section_attribute", "remove_tabular_section_attribute",
            "add_object_reference", "remove_object_reference", "set_object_reference",
            "clear_object_reference", "set_object_type", "remove_object", "sync_export",
            "set_help", "remove_help", "delete_metadata_object", "rename_metadata_object",
            "add_metadata_attribute",
            // Specialized (29)
            "add_register_field", "remove_register_field", "add_enum_value",
            "add_addressing_attribute", "add_accounting_flag",
            "add_ext_dimension_accounting_flag", "add_predefined_account_subconto",
            "remove_predefined_account_subconto", "add_recalculation",
            "add_recalculation_dimension", "add_predefined_item", "add_subsystem_content",
            "remove_subsystem_content", "set_role_right", "set_defined_type_types",
            "set_restriction_template", "remove_restriction_template", "set_role_restriction",
            "remove_role_restriction", "add_event_subscription_handler",
            "set_event_subscription", "create_object_command", "remove_command",
            "add_exchange_plan_content", "remove_exchange_plan_content",
            "add_common_attribute_content", "remove_common_attribute_content",
            "add_functional_option_content", "remove_functional_option_content",
            // Command interface: 6 real entries. The source's own inline comment still says "(5)"
            // - stale since set_command_order was added later without bumping it; the reg() calls
            // themselves (verified here) are complete and correct.
            "set_subsystems_order", "set_subsystem_visibility",
            "set_main_section_command_visibility", "set_subsystem_command_visibility",
            "set_command_placement", "set_command_order",
            // Services HTTP/SOAP (9)
            "create_http_service", "add_url_template", "add_url_template_method",
            "remove_url_template", "remove_url_template_method", "create_web_service",
            "add_web_service_operation", "remove_web_service_operation",
            "add_operation_parameter",
            // Forms (27)
            "create_form", "add_form_attribute", "add_form_attribute_column",
            "add_dynamic_list_table", "add_field", "add_group", "add_button", "add_table",
            "add_decoration", "add_radio_button", "set_property", "list_pictures",
            "add_command_handler", "add_form_event_handler", "remove_form_event_handler",
            "add_form_parameter", "remove_form_parameter", "add_form_command_interface_item",
            "remove_form_command_interface_item", "set_form_command_interface_item_property",
            "add_form_item_functional_option", "remove_form_item_functional_option",
            "setup_settings_composer_on_form", "remove_form_item", "remove_form_attribute",
            "add_form_command", "set_form_item_property",
            // Templates (6)
            "add_template", "set_template_content", "get_template_content", "set_template_cell",
            "merge_template_cells", "draw_template",
            // BusinessProcess route map (3)
            "create_route_map", "get_route_map", "remove_route_map",
            // Extensions (5)
            "adopt_object", "adopt_objects", "adopt_child", "adopt_form_item", "adopt_module",
            // DCS (51)
            "create_report_schema", "add_data_set", "add_data_set_field", "add_schema_parameter",
            "set_schema_parameter", "remove_schema_parameter", "move_schema_parameter",
            "add_calculated_field", "add_total_field", "add_user_field", "remove_data_set",
            "add_settings_group", "add_settings_table", "add_settings_chart",
            "add_settings_filter", "add_settings_filter_group", "add_order",
            "add_settings_order", "add_settings_selected_field",
            "remove_settings_selected_field", "add_settings_variant", "set_settings_parameter",
            "remove_settings_item", "add_conditional_appearance",
            "remove_conditional_appearance", "set_data_set_field_appearance",
            "set_output_parameter", "repair_report_schema", "add_data_set_link",
            "set_data_set_link_property", "remove_data_set_link", "set_data_set_property",
            "set_data_set_query", "remove_data_set_field", "set_calculated_field",
            "remove_calculated_field", "set_total_field", "remove_total_field",
            "clear_settings_selected_fields", "remove_settings_filter", "remove_settings_order",
            "set_settings_item_user_mode", "remove_settings_variant", "clone_settings_variant",
            "add_query_field", "remove_query_field", "add_query_condition",
            "remove_query_condition", "add_data_source", "remove_data_source",
            "set_data_source_property",
            // Common (3)
            "move_item", "remove_item", "remove_item_universal",
            // help (handled before the registry lookup, not a registry entry, but still a valid
            // dispatchable token)
            "help"));

        // yaxunit_tests (YaxunitTestsTool.execute): dispatches on "mode", which is validated
        // against exactly "run" and "debug" before the switch runs - it does NOT accept the hidden
        // standalone names run_yaxunit_tests / debug_yaxunit_tests as mode values, see
        // RENAMED_STANDALONES. "help" there is a SEPARATE top-level parameter (help=<topic>), not
        // a mode value, so it is intentionally not included in this set.
        m.put("yaxunit_tests", Set.of("run", "debug"));

        // extension_workshop (ExtensionWorkshopTool.execute + its OPS catalog): fourteen
        // operations built from Arrays.asList, plus help. All seven deploy/inspect operations that
        // Canonical hides use the exact standalone name as their operation value.
        m.put("extension_workshop", Set.of(
            "create_extension_project", "borrow_object", "borrow_objects", "borrow_child",
            "borrow_form_item", "borrow_module", "list_borrowed", "install_extension",
            "uninstall_extension", "list_extension", "export_extension", "extension_lifecycle",
            "extension_diff", "list_interceptors", "help"));

        // diagnostics (DiagnosticsFacadeTool.execute + its OPS catalog): six operations, all
        // literally the standalone names they replace, plus help.
        m.put("diagnostics", Set.of(
            "get_project_errors", "get_problem_summary", "revalidate_objects", "clean_project",
            "validate_for_export", "get_check_description", "help"));

        // project_admin (ProjectAdminFacadeTool.execute + its OPS catalog): nine operations, all
        // literally the standalone names they replace, plus help.
        m.put("project_admin", Set.of(
            "list_projects", "list_configurations", "get_configuration_properties",
            "create_project", "delete_project", "resync_to_disk", "restart_edt",
            "self_upkeep", "list_subsystems", "help"));

        // infobase_admin (InfobaseAdminFacadeTool.execute + its OPS catalog): eight operations,
        // all literally the standalone names they replace, plus help. sync_control's own inner
        // action travels as a separate "syncOperation" parameter and is not part of this
        // vocabulary.
        m.put("infobase_admin", Set.of(
            "get_applications", "create_infobase", "delete_infobase", "set_infobase_credentials",
            "create_launch_config", "start_client", "update_database", "sync_control", "help"));

        // config_io (ConfigIoFacadeTool.execute + its OPS catalog): four operations, all
        // literally the standalone names they replace, plus help.
        m.put("config_io", Set.of(
            "export_configuration_to_xml", "import_configuration_from_xml", "export_object",
            "export_common_picture", "export_configuration_to_cf",
            "unpack_external_binary", "help"));

        // insights (InsightsFacadeTool.execute + its OPS catalog): eight operations, all
        // literally the standalone names they replace, plus help.
        m.put("insights", Set.of(
            "project_metrics", "dependency_graph", "compare_configurations",
            "detect_query_anti_patterns", "generate_health_snapshot", "impact_analysis",
            "object_summary", "semantic_metadata_search", "help"));

        // security_audit (SecurityAuditFacadeTool.execute + its OPS catalog): three operations,
        // all literally the standalone names they replace, plus help.
        m.put("security_audit", Set.of(
            "audit_role_rights", "find_rls_violations", "sensitive_data_scan", "help"));

        // workspace_marks (WorkspaceMarksFacadeTool.execute + its OPS catalog): four operations,
        // all literally the standalone names they replace, plus help.
        m.put("workspace_marks", Set.of(
            "get_tags", "get_objects_by_tags", "get_bookmarks", "get_tasks", "help"));

        // docs_lookup (DocsLookupFacadeTool.execute + its OPS catalog): two operations, both
        // literally the standalone names they replace, plus help.
        m.put("docs_lookup", Set.of("get_platform_documentation", "get_object_help", "help"));

        return m;
    }

    private static Map<String, String> buildRenamedStandalones()
    {
        Map<String, String> m = new LinkedHashMap<>();
        // code_search: designed with its own vocabulary from the start. Each delegate class's own
        // getName()/NAME below matches the standalone name on the left, and CodeSearchTool's
        // switch instantiates that exact class for the operation on the right.
        m.put("search_in_code", "text_search"); // CodeTextSearcher.getName() == "search_in_code"
        m.put("find_references", "object_references"); // ReferenceLocator.NAME == "find_references"
        m.put("go_to_definition", "resolve_symbol"); // DefinitionNavigator.getName() == "go_to_definition"
        m.put("get_method_call_hierarchy", "call_hierarchy"); // CallHierarchyReader.NAME
        m.put("get_symbol_info", "symbol_info"); // SymbolInfoReader.NAME
        m.put("get_content_assist", "content_assist"); // ContentAssistReader.getName()
        m.put("get_outgoing_structures", "outgoing_structures"); // OutgoingStructuresReader.getName()
        // launch_debugger: the one renamed action that dropped the old standalone name as an
        // alias - every sibling rename (debug_launch, debug_status, terminate_launch, ...) kept
        // both spellings working.
        m.put("evaluate_expression", "evaluate"); // ExpressionEvaluator.NAME == "evaluate_expression"
        // yaxunit_tests: "mode" was designed as run/debug from day one, never as the two
        // standalone tool names.
        m.put("run_yaxunit_tests", "run"); // YaxunitTestRunner.NAME == "run_yaxunit_tests"
        m.put("debug_yaxunit_tests", "debug"); // YaxunitDebugRunner.NAME == "debug_yaxunit_tests"
        return m;
    }

    private static Set<String> unionOfFacadeOperations()
    {
        Set<String> union = new TreeSet<>();
        for (Set<String> operations : FACADE_OPERATIONS.values())
        {
            union.addAll(operations);
        }
        return union;
    }

    private static boolean isReachable(String standaloneName, Set<String> union)
    {
        if (union.contains(standaloneName))
        {
            return true;
        }
        String renamedTo = RENAMED_STANDALONES.get(standaloneName);
        return renamedTo != null && union.contains(renamedTo);
    }

    /**
     * @return the standalone names Canonical hides for which neither the name itself nor its
     *         verified rename target appears in any facade's operation set - i.e. names that would
     *         be genuinely unreachable once Canonical is selected. Empty when the contract holds.
     */
    private static Set<String> findOrphans()
    {
        Set<String> union = unionOfFacadeOperations();
        Set<String> orphans = new TreeSet<>();
        for (String standaloneName : ToolProfile.CANONICAL.getUnlistedTools())
        {
            if (!isReachable(standaloneName, union))
            {
                orphans.add(standaloneName);
            }
        }
        return orphans;
    }

    @Test
    public void everyCanonicallyHiddenToolIsReachableThroughAFacade()
    {
        Set<String> orphans = findOrphans();
        assertTrue("These standalone tools are hidden by ToolProfile.CANONICAL but no facade "
            + "operation - direct or verified rename - reaches them; selecting Canonical would "
            + "strand them: " + orphans, orphans.isEmpty());
    }

    @Test
    public void theSurfaceCountsAreLockedIn()
    {
        // Tripwire 1: exactly how many standalone names CANONICAL currently hides. A change here
        // means a standalone moved in or out of a facade's coverage - update this number
        // deliberately after confirming the move is intended, not to silence a failure.
        assertEquals(80, ToolProfile.CANONICAL.getUnlistedTools().size());

        // Tripwire 2: exactly how many facades this snapshot tracks - code_search,
        // launch_debugger, edit_metadata, yaxunit_tests, extension_workshop, diagnostics,
        // project_admin, infobase_admin, config_io, insights, security_audit, workspace_marks,
        // docs_lookup.
        assertEquals(13, FACADE_OPERATIONS.size());

        // Tripwire 3: a floor, not an exact count - the union is far larger in practice (action
        // aliases, help, and edit_metadata's own ~150-operation registry inflate it well past 80).
        // If it ever drops below the number of hidden tools, coverage cannot possibly hold.
        assertTrue("facade operation union shrank below the hidden-tool floor",
            unionOfFacadeOperations().size() >= 80);
    }

    @Test
    public void noStandaloneIsHiddenWithoutAFacadeHome()
    {
        Set<String> orphans = findOrphans();
        assertTrue("orphaned standalone names - hidden by Canonical, unreachable through any "
            + "facade operation: " + orphans, orphans.isEmpty());

        // A facade itself must stay listed under Canonical - it is the entry point, hiding it
        // behind itself would be self-defeating. None of the 14 facade names may appear among the
        // names Canonical hides.
        Set<String> canonicalUnlisted = ToolProfile.CANONICAL.getUnlistedTools();
        for (String facadeName : FACADE_OPERATIONS.keySet())
        {
            assertFalse(facadeName + " is itself a facade and must stay listed under Canonical, "
                + "not hidden behind another facade", canonicalUnlisted.contains(facadeName));
        }
    }
}
