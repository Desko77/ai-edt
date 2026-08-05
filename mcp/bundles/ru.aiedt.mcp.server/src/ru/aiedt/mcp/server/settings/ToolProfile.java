/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The ready-made answers to "which tools should this agent have".
 * <p>
 * A preset <em>is</em> its pair of sets: what it disables and what it merely hides from the tool
 * list while leaving it callable. Nothing else about it survives - the preference store keeps the two
 * lists of names, never the label - so the combo works out what to show by looking for a preset whose
 * pair matches what is stored. Two presets with the same pair are therefore one preset wearing two
 * names, and the second can never be shown. The disabled set alone is not enough to tell them apart,
 * because {@link #CANONICAL} disables nothing yet is not {@link #ALL_TOOLS}; keep the pairs distinct,
 * as <code>ToolPresetTest</code> insists.
 * </p>
 * <p>
 * The sets are built by static <em>methods</em> rather than from static fields. Enum constants are
 * constructed before any static field of the enum has been assigned, so a set held in a field would
 * still be <code>null</code> at the moment the constants ask for it. A method has no such moment.
 * </p>
 */
public enum ToolProfile
{
    /** Nothing switched off, nothing hidden. */
    ALL_TOOLS("All Tools", //$NON-NLS-1$
        "All tools enabled", //$NON-NLS-1$
        Collections.emptySet(),
        Collections.emptySet()),

    /**
     * Compact surface. Disables nothing - every tool stays callable - but hides the legacy standalone
     * tools that the facades (code_search, launch_debugger, yaxunit_tests, extension_workshop,
     * diagnostics, project_admin, infobase_admin, config_io) now cover, so tools/list shows one entry
     * point per job while every old name still answers a call. Its disabled set is empty, the same as
     * {@link #ALL_TOOLS}; the two are told apart by the unlisted set alone, which is why
     * {@link #matchPreset} compares both.
     */
    CANONICAL("Canonical (compact surface)", //$NON-NLS-1$
        "Every tool stays callable, but the legacy standalone tools that the facades now " //$NON-NLS-1$
            + "cover are hidden from the tool list, so it shows one entry point per job. " //$NON-NLS-1$
            + "The facades and every old name keep working.", //$NON-NLS-1$
        Collections.emptySet(),
        canonicalUnlisted()),

    /**
     * Look, do not touch. Everything that writes - to the sources, to the metadata or to an infobase
     * - and everything that runs code is off.
     */
    READ_ONLY("Read-only", //$NON-NLS-1$
        "Reading, search, navigation, validation. No edits / debug / database updates.", //$NON-NLS-1$
        readOnlyDisabled(),
        Collections.emptySet()),

    /** Write freely, but do not start anything: the debugger is off, nothing else is. */
    EDITING("Editing", //$NON-NLS-1$
        "Read + write through write_module_source, edit_metadata, refactoring, constructors. " //$NON-NLS-1$
            + "No debug.", //$NON-NLS-1$
        toolsOf(ToolCategory.DEBUG),
        Collections.emptySet()),

    /**
     * Run it and step through it, but change nothing.
     * <p>
     * The only preset that leaves the applications group on - a debugger with nothing to launch is
     * no use - so the members of that group which create, delete or overwrite are named individually
     * instead. That is what {@link #destructiveTools()} is for.
     * </p>
     */
    DEBUG_AND_TEST("Debug & Test", //$NON-NLS-1$
        "Read + debug ops + breakpoints + yaxunit_tests. No write / refactoring / constructors, " //$NON-NLS-1$
            + "and nothing that creates, deletes or overwrites an infobase.", //$NON-NLS-1$
        debugAndTestDisabled(),
        Collections.emptySet()),

    /** Read the code and reason about it. No writing, no launching, no debugging. */
    CODE_REVIEW("Code Review", //$NON-NLS-1$
        "BSL code analysis and reading (no writes)", //$NON-NLS-1$
        codeReviewDisabled(),
        Collections.emptySet()),

    /**
     * Whatever the user ticked. It has no set of its own - it is what the combo falls back to when
     * the stored set is nobody's - and applying it is meaningless, so it does nothing.
     */
    CUSTOM("Custom", //$NON-NLS-1$
        "Hand-picked", //$NON-NLS-1$
        null,
        null);

    private final String displayName;

    private final String description;

    private final Set<String> disabledTools;

    private final Set<String> unlistedTools;

    ToolProfile(String displayName, String description, Set<String> disabledTools,
        Set<String> unlistedTools)
    {
        this.displayName = displayName;
        this.description = description;
        this.disabledTools = disabledTools == null ? null : Set.copyOf(disabledTools);
        this.unlistedTools = unlistedTools == null ? null : Set.copyOf(unlistedTools);
    }

    /**
     * Returns the label the combo shows.
     *
     * @return the display name, never <code>null</code>
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns what this preset is for.
     *
     * @return the description, never <code>null</code>
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Returns the tools this preset switches off.
     *
     * @return an unmodifiable set of tool names; <code>null</code>, and only for {@link #CUSTOM},
     *         which has no opinion of its own
     */
    public Set<String> getDisabledTools()
    {
        return disabledTools;
    }

    /**
     * Returns the tools this preset hides from <code>tools/list</code> while leaving them callable.
     *
     * @return an unmodifiable set of tool names; empty for every preset but {@link #CANONICAL}, and
     *         <code>null</code> only for {@link #CUSTOM}, which has no opinion of its own
     */
    public Set<String> getUnlistedTools()
    {
        return unlistedTools;
    }

    /**
     * Works out which preset a stored configuration is.
     * <p>
     * A preset is now its <em>pair</em> of sets - what it disables and what it merely hides - because
     * {@link #CANONICAL} disables nothing, exactly like {@link #ALL_TOOLS}, and the two can only be
     * told apart by the unlisted set. A preset matches only when both of its sets equal the stored
     * ones.
     * </p>
     * <p>
     * Names the plugin no longer knows are dropped from each side first. A workspace saved against an
     * older build may well name tools that have since been renamed away - or, in the unlisted set,
     * name a currently-registered tool that belongs to no group; either way, leaving them in would
     * make the workspace read as {@link #CUSTOM} after an upgrade when nothing about the user's intent
     * had changed. The stored preset sets are filtered the same way, so an ungrouped name a preset
     * itself carries is dropped from both sides and does not spoil the match.
     * </p>
     * <p>
     * The scan is in declaration order and takes the first match, which is why two presets may not
     * share a pair.
     * </p>
     *
     * @param disabledTools the names currently switched off; not <code>null</code>
     * @param unlistedTools the names currently hidden but callable; not <code>null</code>
     * @return the preset that means this, or {@link #CUSTOM} when no preset does. Two empty sets are
     *         {@link #ALL_TOOLS}
     */
    public static ToolProfile matchPreset(Set<String> disabledTools, Set<String> unlistedTools)
    {
        Set<String> knownDisabled = retainGrouped(disabledTools);
        Set<String> knownUnlisted = retainGrouped(unlistedTools);

        for (ToolProfile preset : values())
        {
            if (preset != CUSTOM
                && retainGrouped(preset.disabledTools).equals(knownDisabled)
                && retainGrouped(preset.unlistedTools).equals(knownUnlisted))
            {
                return preset;
            }
        }
        return CUSTOM;
    }

    /**
     * Keeps only the names the plugin still groups. A name in no {@link ToolCategory} is one the
     * preference page cannot show and no preset can be built from, so for the purpose of matching a
     * preset it is noise - whether it is a name renamed away in an older build or a registered tool
     * that was never placed in a group.
     *
     * @param names the names to sift; not <code>null</code>
     * @return a fresh, mutable set of the grouped names among them
     */
    private static Set<String> retainGrouped(Set<String> names)
    {
        Set<String> grouped = new HashSet<>();
        for (String toolName : names)
        {
            if (ToolCategory.getGroupForTool(toolName) != null)
            {
                grouped.add(toolName);
            }
        }
        return grouped;
    }

    /**
     * The legacy standalone tools a facade now covers, gathered into the set {@link #CANONICAL} hides.
     * <p>
     * Each name is a separately registered tool, so hiding it actually shrinks <code>tools/list</code>;
     * the facade that subsumes it - {@code code_search}, {@code launch_debugger}, {@code yaxunit_tests},
     * {@code extension_workshop}, {@code diagnostics}, {@code project_admin}, {@code infobase_admin},
     * {@code config_io}, {@code insights}, {@code security_audit}, {@code workspace_marks},
     * {@code docs_lookup} or {@code edit_metadata} - stays listed, and every name
     * here stays callable. {@code edit_metadata} folds in three of its own -
     * {@code delete_metadata_object}, {@code rename_metadata_object}, {@code add_metadata_attribute} -
     * as gate-checked delegated operations (see {@code ToolGate}), the same way the other facades fold
     * in the standalones they cover. The facades themselves are deliberately absent - a facade must
     * remain visible - which is why {@code edit_metadata} is not in this set even though three of its
     * operations now are. The set is built by a method, not held in a field, for the same reason the
     * disabled sets are - a field would be unassigned at the moment the enum constant asks for it.
     * </p>
     *
     * @return the standalone names to hide behind their facades
     */
    private static Set<String> canonicalUnlisted()
    {
        return Set.of(
            // code_search covers these
            "search_in_code", //$NON-NLS-1$
            "find_references", //$NON-NLS-1$
            "go_to_definition", //$NON-NLS-1$
            "get_method_call_hierarchy", //$NON-NLS-1$
            "get_symbol_info", //$NON-NLS-1$
            "get_content_assist", //$NON-NLS-1$
            "get_outgoing_structures", //$NON-NLS-1$

            // launch_debugger covers these
            "debug_launch", //$NON-NLS-1$
            "set_breakpoint", //$NON-NLS-1$
            "set_exception_breakpoint", //$NON-NLS-1$
            "run_to_line", //$NON-NLS-1$
            "remove_breakpoint", //$NON-NLS-1$
            "list_breakpoints", //$NON-NLS-1$
            "wait_for_break", //$NON-NLS-1$
            "debug_status", //$NON-NLS-1$
            "get_variables", //$NON-NLS-1$
            "set_variable", //$NON-NLS-1$
            "step", //$NON-NLS-1$
            "resume", //$NON-NLS-1$
            "terminate_launch", //$NON-NLS-1$
            "evaluate_expression", //$NON-NLS-1$
            "start_profiling", //$NON-NLS-1$
            "get_profiling_results", //$NON-NLS-1$

            // yaxunit_tests covers these
            "run_yaxunit_tests", //$NON-NLS-1$
            "debug_yaxunit_tests", //$NON-NLS-1$

            // extension_workshop covers these
            "install_extension", //$NON-NLS-1$
            "uninstall_extension", //$NON-NLS-1$
            "list_extension", //$NON-NLS-1$
            "export_extension", //$NON-NLS-1$
            "extension_lifecycle", //$NON-NLS-1$
            "extension_diff", //$NON-NLS-1$
            "list_interceptors", //$NON-NLS-1$

            // diagnostics covers these
            "get_project_errors", //$NON-NLS-1$
            "get_problem_summary", //$NON-NLS-1$
            "revalidate_objects", //$NON-NLS-1$
            "clean_project", //$NON-NLS-1$
            "validate_for_export", //$NON-NLS-1$
            "get_check_description", //$NON-NLS-1$

            // project_admin covers these
            "list_projects", //$NON-NLS-1$
            "list_configurations", //$NON-NLS-1$
            "get_configuration_properties", //$NON-NLS-1$
            "create_project", //$NON-NLS-1$
            "delete_project", //$NON-NLS-1$
            "resync_to_disk", //$NON-NLS-1$
            "restart_edt", //$NON-NLS-1$
            "self_upkeep", //$NON-NLS-1$
            "list_subsystems", //$NON-NLS-1$

            // infobase_admin covers these
            "get_applications", //$NON-NLS-1$
            "create_infobase", //$NON-NLS-1$
            "delete_infobase", //$NON-NLS-1$
            "set_infobase_credentials", //$NON-NLS-1$
            "create_launch_config", //$NON-NLS-1$
            "update_database", //$NON-NLS-1$
            "sync_control", //$NON-NLS-1$

            // config_io covers these
            "export_configuration_to_xml", //$NON-NLS-1$
            "import_configuration_from_xml", //$NON-NLS-1$
            "export_object", //$NON-NLS-1$
            "export_common_picture", //$NON-NLS-1$

            // insights covers these
            "project_metrics", //$NON-NLS-1$
            "dependency_graph", //$NON-NLS-1$
            "compare_configurations", //$NON-NLS-1$
            "detect_query_anti_patterns", //$NON-NLS-1$
            "generate_health_snapshot", //$NON-NLS-1$
            "impact_analysis", //$NON-NLS-1$
            "object_summary", //$NON-NLS-1$
            "semantic_metadata_search", //$NON-NLS-1$

            // security_audit covers these
            "audit_role_rights", //$NON-NLS-1$
            "find_rls_violations", //$NON-NLS-1$
            "sensitive_data_scan", //$NON-NLS-1$

            // workspace_marks covers these
            "get_tags", //$NON-NLS-1$
            "get_objects_by_tags", //$NON-NLS-1$
            "get_bookmarks", //$NON-NLS-1$
            "get_tasks", //$NON-NLS-1$

            // docs_lookup covers these
            "get_platform_documentation", //$NON-NLS-1$
            "get_object_help", //$NON-NLS-1$

            // edit_metadata covers these (gate-checked delegated operations, see ToolGate)
            "delete_metadata_object", //$NON-NLS-1$
            "rename_metadata_object", //$NON-NLS-1$
            "add_metadata_attribute"); //$NON-NLS-1$
    }

    /**
     * The tools that can destroy work: they drop an infobase or a project, overwrite a configuration
     * from outside, change what is installed, or resynchronise one side of the pair onto the other.
     * <p>
     * Every one of them is in {@link ToolCategory#APPLICATIONS}, so a preset that disables that whole
     * group has them covered already. This list exists for the one preset that must not disable the
     * group.
     * </p>
     *
     * @return the destructive names
     */
    private static Set<String> destructiveTools()
    {
        return Set.of("update_database", //$NON-NLS-1$
            "create_infobase", //$NON-NLS-1$
            "delete_infobase", //$NON-NLS-1$
            "delete_project", //$NON-NLS-1$
            "import_configuration_from_xml", //$NON-NLS-1$
            "install_extension", //$NON-NLS-1$
            "uninstall_extension", //$NON-NLS-1$
            "set_infobase_credentials", //$NON-NLS-1$
            "sync_control", //$NON-NLS-1$
            "resync_to_disk", //$NON-NLS-1$
            "restart_edt", //$NON-NLS-1$
            "self_upkeep"); //$NON-NLS-1$
    }

    /**
     * The compact-surface facades that can reach a destructive or writing operation
     * (project_admin -> create/delete_project/restart_edt, infobase_admin ->
     * create/delete_infobase/update_database, config_io -> import_configuration_from_xml).
     * A preset that forbids writes must disable these too, or an agent could bypass the
     * preset by routing a write through the facade - the same reason edit_metadata /
     * extension_workshop are gated by living in the CONSTRUCTORS group. diagnostics is
     * deliberately absent: its operations only validate, which a read-only preset allows.
     *
     * @return the mutating facade names
     */
    private static Set<String> mutatingFacades()
    {
        return Set.of("project_admin", "infobase_admin", "config_io"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The write-capable tools that live in a group every write-blocking preset keeps enabled, so each
     * such preset has to name them one by one.
     * <p>
     * Collected here instead of being repeated per preset, because repeating them is how one goes
     * missing: {@code extension_lifecycle} borrows an object into an extension and appends a handler
     * stub, yet it sits in the agent-composites group that Read-only, Debug &amp; Test and Code Review
     * all leave on, and none of the three named it. All three therefore let a write through.
     * {@code PresetWriteBlockingTest} now asserts every preset that claims to block writing contains
     * this whole set.
     * </p>
     *
     * @return the names no write-blocking preset may leave enabled
     */
    private static Set<String> writersOutsideWriteGroups()
    {
        Set<String> names = new HashSet<>(mutatingFacades());
        names.add("write_module_source"); //$NON-NLS-1$
        names.add("generate_event_handlers"); //$NON-NLS-1$
        names.add("extension_lifecycle"); //$NON-NLS-1$
        return names;
    }

    /**
     * @return everything that writes, launches or debugs
     */
    private static Set<String> readOnlyDisabled()
    {
        Set<String> disabled = toolsOf(ToolCategory.APPLICATIONS, ToolCategory.DEBUG, ToolCategory.REFACTORING,
            ToolCategory.CONSTRUCTORS);
        disabled.addAll(writersOutsideWriteGroups());
        // Returns a snippet and writes nothing, but a preset this strict is expected to hand back
        // nothing that reads like generated code either.
        disabled.add("code_template"); //$NON-NLS-1$
        return disabled;
    }

    /**
     * @return everything that changes the sources or the metadata, plus everything destructive - but
     *         not the rest of the applications group, which is what there is to debug
     */
    private static Set<String> debugAndTestDisabled()
    {
        Set<String> disabled = toolsOf(ToolCategory.REFACTORING, ToolCategory.CONSTRUCTORS);
        disabled.addAll(destructiveTools());
        disabled.addAll(writersOutsideWriteGroups());
        return disabled;
    }

    /**
     * @return everything that writes, launches or debugs - the constructors excepted, since reading
     *         what they would produce is part of a review
     */
    private static Set<String> codeReviewDisabled()
    {
        Set<String> disabled = toolsOf(ToolCategory.APPLICATIONS, ToolCategory.DEBUG, ToolCategory.REFACTORING);
        disabled.addAll(writersOutsideWriteGroups());
        return disabled;
    }

    /**
     * Collects the members of whole groups.
     *
     * @param groups the groups to take
     * @return a fresh, mutable set of every tool in them
     */
    private static Set<String> toolsOf(ToolCategory... groups)
    {
        Set<String> names = new HashSet<>();
        for (ToolCategory group : groups)
        {
            names.addAll(group.getToolNames());
        }
        return names;
    }
}
