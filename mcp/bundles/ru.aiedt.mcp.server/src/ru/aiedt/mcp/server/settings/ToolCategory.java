/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools, sorted into the thirteen boxes the preference page draws and the presets switch.
 * <p>
 * A group is the only handle anything has on a tool. The tree gives each group a checkbox, each
 * preset is written in terms of whole groups, and both work through the names listed here - so a
 * tool that is in no group has no checkbox, is in no preset, and cannot be switched off at all. That
 * had happened, quietly, to twenty-eight of them; <code>ToolCategoryCoverageTest</code> now walks the
 * registry against this table on every run so that it cannot happen again. Register a tool, put it
 * in a group - and put a destructive one where a cautious preset will find it.
 * </p>
 * <p>
 * Declaration order is what the user sees: it is the order of the tree, and the order
 * {@link ToolProfile#matchPreset} scans. Deliberately no Eclipse types here - this is data, and it is
 * tested as data, outside any workbench.
 * </p>
 */
public enum ToolCategory
{
    /** Version, projects, configuration properties, validation. */
    CORE("core", "Project & Configuration", //$NON-NLS-1$ //$NON-NLS-2$
        "Project, configuration properties, validation, and check reference", //$NON-NLS-1$
        "get_edt_version", //$NON-NLS-1$
        "list_projects", //$NON-NLS-1$
        "get_configuration_properties", //$NON-NLS-1$
        "clean_project", //$NON-NLS-1$
        "revalidate_objects", //$NON-NLS-1$
        "get_check_description", //$NON-NLS-1$
        "project_admin"), //$NON-NLS-1$

    /** Everything that reports on what is wrong with a project, and the server's own diagnostics. */
    PROBLEMS("problems", "Diagnostics & Issues", //$NON-NLS-1$ //$NON-NLS-2$
        "Problem summaries, project errors, code review, and server diagnostics", //$NON-NLS-1$
        "get_problem_summary", //$NON-NLS-1$
        "get_project_errors", //$NON-NLS-1$
        "code_review", //$NON-NLS-1$
        "get_mcp_history", //$NON-NLS-1$
        "self_status", //$NON-NLS-1$
        "diagnostics"), //$NON-NLS-1$

    /** Reading the model: metadata, documentation, references, search by meaning. */
    CODE_INTELLIGENCE("codeIntelligence", "Model Insight", //$NON-NLS-1$ //$NON-NLS-2$
        "Content assist, platform docs, metadata browsing, references, semantic and DCS search", //$NON-NLS-1$
        "get_content_assist", //$NON-NLS-1$
        "get_platform_documentation", //$NON-NLS-1$
        "get_metadata_objects", //$NON-NLS-1$
        "get_metadata_details", //$NON-NLS-1$
        "find_references", //$NON-NLS-1$
        "object_summary", //$NON-NLS-1$
        "describe_db_tables", //$NON-NLS-1$
        "semantic_metadata_search", //$NON-NLS-1$
        "list_subsystems", //$NON-NLS-1$
        "dcs_search", //$NON-NLS-1$
        "docs_lookup"), //$NON-NLS-1$

    /** The plugin's own metadata tags, bookmarks and task markers. */
    TAGS("tags", "Tags & Marks", //$NON-NLS-1$ //$NON-NLS-2$
        "Read tags, bookmarks and task markers, and query objects by tag", //$NON-NLS-1$
        "get_tags", //$NON-NLS-1$
        "get_objects_by_tags", //$NON-NLS-1$
        "get_bookmarks", //$NON-NLS-1$
        "get_tasks", //$NON-NLS-1$
        "workspace_marks"), //$NON-NLS-1$

    /**
     * Everything that touches a live infobase or the shape of the workspace: launching, updating,
     * testing, reading data - and creating, deleting and overwriting. The destructive members are
     * named one by one in {@link ToolProfile}, because the one preset that needs an application to
     * launch cannot afford to switch the whole group off.
     */
    APPLICATIONS("applications", "Infobase & Runtime", //$NON-NLS-1$ //$NON-NLS-2$
        "Infobase lifecycle, database update, sync control, launch, testing, " //$NON-NLS-1$
            + "extension deployment, EDT restart", //$NON-NLS-1$
        "get_applications", //$NON-NLS-1$
        "read_event_log", //$NON-NLS-1$
        "list_configurations", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "debug_launch", //$NON-NLS-1$
        "run_yaxunit_tests", //$NON-NLS-1$
        "yaxunit_tests", //$NON-NLS-1$
        "vanessa", //$NON-NLS-1$
        "create_launch_config", //$NON-NLS-1$
        "start_client", //$NON-NLS-1$
        "create_infobase", //$NON-NLS-1$
        "delete_infobase", //$NON-NLS-1$
        "install_extension", //$NON-NLS-1$
        "uninstall_extension", //$NON-NLS-1$
        "list_extension", //$NON-NLS-1$
        "import_configuration_from_xml", //$NON-NLS-1$
        "unpack_external_binary", //$NON-NLS-1$
        "import_configuration_from_binary", //$NON-NLS-1$
        "export_configuration_to_xml", //$NON-NLS-1$
        "export_extension", //$NON-NLS-1$
        "set_infobase_credentials", //$NON-NLS-1$
        "sync_control", //$NON-NLS-1$
        "resync_to_disk", //$NON-NLS-1$
        "restart_edt", //$NON-NLS-1$
        "self_upkeep", //$NON-NLS-1$
        "delete_project", //$NON-NLS-1$
        "create_project", //$NON-NLS-1$
        "infobase_admin"), //$NON-NLS-1$

    /** Everything that drives a debug session, including the two ways of ending one. */
    DEBUG("debug", "Debug & Profiling", //$NON-NLS-1$ //$NON-NLS-2$
        "Breakpoints, stepping, variable inspection, expression evaluation, and profiling", //$NON-NLS-1$
        "set_breakpoint", //$NON-NLS-1$
        "remove_breakpoint", //$NON-NLS-1$
        "list_breakpoints", //$NON-NLS-1$
        "wait_for_break", //$NON-NLS-1$
        "get_variables", //$NON-NLS-1$
        "step", //$NON-NLS-1$
        "resume", //$NON-NLS-1$
        "evaluate_expression", //$NON-NLS-1$
        "debug_yaxunit_tests", //$NON-NLS-1$
        "debug_status", //$NON-NLS-1$
        "start_profiling", //$NON-NLS-1$
        "get_profiling_results", //$NON-NLS-1$
        "launch_debugger", //$NON-NLS-1$
        "run_to_line", //$NON-NLS-1$
        "set_exception_breakpoint", //$NON-NLS-1$
        "set_variable", //$NON-NLS-1$
        "terminate_launch"), //$NON-NLS-1$

    /** Reading and writing BSL, and finding your way around it. */
    BSL_CODE("bslCode", "BSL Source", //$NON-NLS-1$ //$NON-NLS-2$
        "Read and write BSL modules, inspect structure, search, call hierarchy, and navigation", //$NON-NLS-1$
        "read_module_source", //$NON-NLS-1$
        "write_module_source", //$NON-NLS-1$
        "get_module_structure", //$NON-NLS-1$
        "list_modules", //$NON-NLS-1$
        "search_in_code", //$NON-NLS-1$
        "read_method_source", //$NON-NLS-1$
        "get_method_call_hierarchy", //$NON-NLS-1$
        "go_to_definition", //$NON-NLS-1$
        "get_symbol_info", //$NON-NLS-1$
        "validate_query", //$NON-NLS-1$
        "code_search"), //$NON-NLS-1$

    /** Changing what a metadata object is called, whether it exists, and what it holds. */
    REFACTORING("refactoring", "Metadata Editing", //$NON-NLS-1$ //$NON-NLS-2$
        "Rename, delete, and attribute changes on metadata objects", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "delete_metadata_object", //$NON-NLS-1$
        "add_metadata_attribute", //$NON-NLS-1$
        // Reads like a diagnostic and writes like an editor: applying a correction
        // changes the sources, so it belongs where Read-only can switch it off.
        "marker_corrections", //$NON-NLS-1$
        // Creates a whole object in the target project - as plainly a write as a rename.
        "copy_object"), //$NON-NLS-1$

    /** The builders: they write metadata, forms, schemas and spreadsheets. */
    CONSTRUCTORS("constructors", "Builders & Workshops", //$NON-NLS-1$ //$NON-NLS-2$
        "High-level builders for metadata, forms, DCS, spreadsheets, and extensions", //$NON-NLS-1$
        "edit_form", //$NON-NLS-1$
        "edit_metadata", //$NON-NLS-1$
        "dcs_workshop", //$NON-NLS-1$
        "mxl_workshop", //$NON-NLS-1$
        "extension_workshop", //$NON-NLS-1$
        "xdto_workshop", //$NON-NLS-1$
        "external_object_workshop", //$NON-NLS-1$
        "external_data_source_workshop"), //$NON-NLS-1$

    /** Looking at forms and at what an object is, plus the checks that run before an export. */
    FORMS_AND_HELP("formsAndHelp", "Forms & Export", //$NON-NLS-1$ //$NON-NLS-2$
        "Form structure and screenshots, object help, export, query validation, diff, context", //$NON-NLS-1$
        "get_form_structure", //$NON-NLS-1$
        "get_form_screenshot", //$NON-NLS-1$
        "get_object_help", //$NON-NLS-1$
        "export_object", //$NON-NLS-1$
        "diff_module", //$NON-NLS-1$
        "ai_context", //$NON-NLS-1$
        "get_command_interface", //$NON-NLS-1$
        "validate_for_export", //$NON-NLS-1$
        "export_common_picture", //$NON-NLS-1$
        "config_io"), //$NON-NLS-1$

    /** Reading the configuration as a whole and reporting on it. */
    ANALYSIS("analysis", "Configuration Analytics", //$NON-NLS-1$ //$NON-NLS-2$
        "Dependency graphs, anti-patterns, metrics, comparison, impact, extension diff and interceptors", //$NON-NLS-1$
        "dependency_graph", //$NON-NLS-1$
        "detect_query_anti_patterns", //$NON-NLS-1$
        "project_metrics", //$NON-NLS-1$
        "compare_configurations", //$NON-NLS-1$
        "impact_analysis", //$NON-NLS-1$
        "extension_diff", //$NON-NLS-1$
        "list_interceptors", //$NON-NLS-1$
        "find_dead_code", //$NON-NLS-1$
        "get_outgoing_structures", //$NON-NLS-1$
        "insights"), //$NON-NLS-1$

    /** Rights, row-level security, and what is stored that should not be. */
    SECURITY("security", "Security & Access", //$NON-NLS-1$ //$NON-NLS-2$
        "Role rights audit, RLS violation checks, sensitive data scan", //$NON-NLS-1$
        "audit_role_rights", //$NON-NLS-1$
        "find_rls_violations", //$NON-NLS-1$
        "sensitive_data_scan", //$NON-NLS-1$
        "security_audit"), //$NON-NLS-1$

    /** The composites: one call where an agent would otherwise make five. */
    AI_HELPERS("aiHelpers", "Agent Composites", //$NON-NLS-1$ //$NON-NLS-2$
        "One-call composites and generators for AI agents: health snapshot, code templates, " //$NON-NLS-1$
            + "event-handler stubs, extension lifecycle", //$NON-NLS-1$
        "generate_health_snapshot", //$NON-NLS-1$
        "code_template", //$NON-NLS-1$
        "generate_event_handlers", //$NON-NLS-1$
        "extension_lifecycle"); //$NON-NLS-1$

    /**
     * Tool name to owning group.
     * <p>
     * Built once, after every constant exists - a static initializer runs after the constants are
     * constructed, which is the whole reason this is not built in the constructor. Being a map, it
     * would swallow a name listed in two groups rather than complain about it, so the tests do the
     * complaining.
     * </p>
     */
    private static final Map<String, ToolCategory> BY_TOOL_NAME = buildIndex();

    private final String id;

    private final String displayName;

    private final String description;

    private final List<String> toolNames;

    ToolCategory(String id, String displayName, String description, String... toolNames)
    {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.toolNames = Collections.unmodifiableList(Arrays.asList(toolNames));
    }

    /**
     * Returns the stable identifier of this group.
     *
     * @return the id, never <code>null</code>
     */
    public String getId()
    {
        return id;
    }

    /**
     * Returns the label the tree shows.
     *
     * @return the display name, never <code>null</code>
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns the sentence the detail panel shows when this group is selected.
     *
     * @return the description, never <code>null</code>
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Returns the tools in this group, in the order they are declared - which is the order the tree
     * puts them in.
     *
     * @return an unmodifiable list of tool names, never empty
     */
    public List<String> getToolNames()
    {
        return toolNames;
    }

    /**
     * Finds the group a tool belongs to.
     *
     * @param toolName the tool name; may be <code>null</code>
     * @return the owning group, or <code>null</code> for <code>null</code> and for a name no group
     *         claims - which, for a registered tool, means nothing can switch it off
     */
    public static ToolCategory getGroupForTool(String toolName)
    {
        if (toolName == null)
        {
            return null;
        }
        return BY_TOOL_NAME.get(toolName);
    }

    /**
     * Returns how many distinct tools are grouped. This is the denominator the preference page
     * counts against, and it is meant to equal the number of tools the server registers.
     *
     * @return the number of grouped tool names
     */
    public static int getTotalToolCount()
    {
        return BY_TOOL_NAME.size();
    }

    /**
     * Builds the reverse index.
     *
     * @return tool name to group, unmodifiable
     */
    private static Map<String, ToolCategory> buildIndex()
    {
        Map<String, ToolCategory> index = new HashMap<>();
        for (ToolCategory group : values())
        {
            for (String toolName : group.toolNames)
            {
                index.put(toolName, group);
            }
        }
        return Collections.unmodifiableMap(index);
    }
}
