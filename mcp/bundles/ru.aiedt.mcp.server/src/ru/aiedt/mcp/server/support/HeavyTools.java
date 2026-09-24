/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * The tools whose one invocation can be genuinely expensive on a large configuration - whole-project
 * scans, whole-configuration export/import, cross-configuration compares, thick-client spawns, and the
 * index-wide reference searches. The endpoint bounds how many of these run at once so a burst of them
 * cannot overwhelm EDT and starve everything else.
 *
 * <p>Membership is a deliberate policy list, derived from a duration survey of every tool. It errs on
 * the side of naming a tool heavy when a single call reaches across the whole configuration or shells
 * out to a 1C client. It deliberately omits the common fast reads that are only occasionally slow
 * ({@code get_metadata_objects}, {@code list_modules} when called with no filter), because throttling
 * them would hurt ordinary use far more than it protects EDT. This is a soft, tunable limit, not a
 * correctness gate: adjust the set here rather than scattering the decision.
 *
 * <p>The limiter counts by the wire tool name of the incoming call, so the facade entry points
 * ({@code code_search}) are named here alongside their standalone equivalents. A tool that internally
 * calls a heavy tool through the catalog (rather than over the wire) is not counted a second time -
 * acceptable for a soft limit, since the outer call is already gated.
 */
public final class HeavyTools
{
    private static final Set<String> HEAVY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "update_database", //$NON-NLS-1$
        "export_object", //$NON-NLS-1$
        "export_configuration_to_xml", //$NON-NLS-1$
        "import_configuration_from_xml", //$NON-NLS-1$
        "import_configuration_from_binary", //$NON-NLS-1$
        // config_io's export_infobase_objects: the facade runs it itself - there is no standalone
        // tool whose name a call arrives under - and its Designer spawn against the infobase is
        // the same weight as the grouped export's. Named by the operation because that is the
        // name the facade's route answers for it.
        "export_infobase_objects", //$NON-NLS-1$
        // config_io's export_configuration_to_cf: the same weight as export_infobase_objects and
        // named the same way - the facade runs it itself, so the operation name is what its route
        // answers. It dumps the whole configuration through a Designer spawned against the
        // infobase.
        "export_configuration_to_cf", //$NON-NLS-1$
        // convertExternalToXml behind two callers: the standalone tool and config_io's operation of
        // the same name. Runs a Designer against the infobase the caller names.
        "unpack_external_binary", //$NON-NLS-1$
        // external_object_workshop's import_external_object: the same Designer conversion, reached
        // through the workshop's own route.
        "import_external_object", //$NON-NLS-1$
        // create_infobase: the physical creation is a CREATEINFOBASE batch child process.
        "create_infobase", //$NON-NLS-1$
        // sync_control's rebuild_dump_info: the infobase is released to a Designer that dumps
        // ConfigDumpInfo.xml. The other sync_control operations read files in-process.
        "rebuild_dump_info", //$NON-NLS-1$
        // extension_workshop's check_platform_verdict: the facade runs it itself, and the run
        // creates a staging infobase and puts the delivery and the extension to a Designer
        // there. Named by the operation, as the facade's route answers it.
        "check_platform_verdict", //$NON-NLS-1$
        "export_extension", //$NON-NLS-1$
        "install_extension", //$NON-NLS-1$
        "uninstall_extension", //$NON-NLS-1$
        "list_extension", //$NON-NLS-1$
        "vanessa", //$NON-NLS-1$
        "code_review", //$NON-NLS-1$
        "compare_configurations", //$NON-NLS-1$
        "compare_three_way", //$NON-NLS-1$
        "dependency_graph", //$NON-NLS-1$
        "generate_health_snapshot", //$NON-NLS-1$
        "project_metrics", //$NON-NLS-1$
        "semantic_metadata_search", //$NON-NLS-1$
        "sensitive_data_scan", //$NON-NLS-1$
        "find_rls_violations", //$NON-NLS-1$
        "audit_role_rights", //$NON-NLS-1$
        "detect_query_anti_patterns", //$NON-NLS-1$
        "validate_for_export", //$NON-NLS-1$
        "list_interceptors", //$NON-NLS-1$
        "clean_project", //$NON-NLS-1$
        "revalidate_objects", //$NON-NLS-1$
        "dcs_search", //$NON-NLS-1$
        "search_in_code", //$NON-NLS-1$
        "find_references", //$NON-NLS-1$
        "impact_analysis", //$NON-NLS-1$
        "find_dead_code", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "yaxunit_tests", //$NON-NLS-1$
        "run_yaxunit_tests", //$NON-NLS-1$
        "debug_yaxunit_tests", //$NON-NLS-1$
        // One call opens the file in its editor, resolves the whole visible scope and can hand back
        // thousands of proposals; a long series of them once walked EDT's heap to its ceiling. Named
        // here as well as under the facade, so calling the old standalone name is guarded too.
        "get_content_assist", //$NON-NLS-1$
        // Naming a symbol's type means resolving the whole module's cross-references, the same work
        // that filled the heap twice. Named here as well as under the facade, so calling the old
        // standalone name is guarded too.
        "get_symbol_info", //$NON-NLS-1$
        "code_search"))); //$NON-NLS-1$

    private HeavyTools()
    {
    }

    /**
     * Whether the named tool is one the heavy-tool limiter should count.
     * <p>
     * A tool another bundle published is named here by its own declaration, the
     * {@code ru.aiedt.mcp.tool.heavy} service property, rather than by this list: the list is this
     * server's policy over this server's tools, and a bundle is the one who knows what its own
     * call costs.
     * </p>
     *
     * @param toolName the wire tool name, or <code>null</code>
     * @return <code>true</code> when the tool is heavy
     */
    public static boolean isHeavy(String toolName)
    {
        return toolName != null
            && (HEAVY.contains(toolName)
                || McpToolCatalog.getInstance().isExternalHeavy(toolName));
    }

    /**
     * @return the number of heavy tools, for tests and diagnostics
     */
    public static int count()
    {
        return HEAVY.size();
    }
}
