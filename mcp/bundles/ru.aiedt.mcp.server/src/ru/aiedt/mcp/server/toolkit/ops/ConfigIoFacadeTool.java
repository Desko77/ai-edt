/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Unified configuration and object import/export facade with four operations.
 *
 * <p>Collapses the file-system round-trip tools under one name: sending a whole
 * configuration or a single object out to files, and pulling a configuration
 * back in.
 * <ul>
 *   <li>{@code export_configuration_to_xml} - export a whole configuration
 *       project to platform Designer-XML files (delegates to
 *       {@link ConfigurationXmlExporter})</li>
 *   <li>{@code import_configuration_from_xml} - import a configuration from
 *       Designer-XML files into a NEW EDT project (delegates to
 *       {@link ConfigurationXmlImporter}; MUTATING, creates a project)</li>
 *   <li>{@code import_configuration_from_binary} - import a {@code .cf} or
 *       {@code .cfe} into a NEW EDT project through a staging infobase
 *       (delegates to {@link ConfigurationBinaryImporter}; MUTATING, creates a
 *       project)</li>
 *   <li>{@code export_object} - build an external data processor / report DT
 *       project into a binary .epf / .erf file (delegates to
 *       {@link ExportObjectTool}; may reply Pending with a runKey)</li>
 *   <li>{@code export_common_picture} - export a CommonPicture's image bytes to
 *       a file (delegates to {@link CommonPictureExporter})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass
 * through as-is, and the standalone tools stay registered for back-compat.
 * This facade always answers as MARKDOWN, the safest wrapper: it carries any
 * string body regardless of the routed tool's own native response type. An
 * agent that needs a JSON-typed result (structuredContent) from one of the
 * JSON-response standalones should call that standalone directly - the same
 * tradeoff {@code code_search}, {@code diagnostics} and {@code infobase_admin}
 * accept.
 */
public class ConfigIoFacadeTool implements IMcpTool
{
    public static final String NAME = "config_io"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Configuration import / export - round-trip the configuration to XML, dump it to a " //$NON-NLS-1$
            + ".cf, import a .cf or .cfe someone sent you, export an object to an .epf/.erf, " //$NON-NLS-1$
            + "export a common picture. Operations: export_configuration_to_xml, " //$NON-NLS-1$
            + "import_configuration_from_xml, import_configuration_from_binary, export_object, " //$NON-NLS-1$
            + "export_common_picture, export_configuration_to_cf, help. Pass operation=<name> " //$NON-NLS-1$
            + "(snake_case canonical; camelCase like exportObject is also accepted); remaining " //$NON-NLS-1$
            + "parameters follow the per-operation contracts (call operation=help for the catalog). " //$NON-NLS-1$
            + "import_configuration_from_xml and import_configuration_from_binary mutate " //$NON-NLS-1$
            + "the workspace (each creates a project) and " //$NON-NLS-1$
            + "export_object and import_configuration_from_binary may reply with a Pending " //$NON-NLS-1$
            + "status and a runKey to resume - the " //$NON-NLS-1$
            + "facade only routes, it adds no dryRun. export_configuration_to_cf dumps the " //$NON-NLS-1$
            + "infobase's current configuration (run update_database first to capture project " //$NON-NLS-1$
            + "changes). The standalone tools remain available for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "export_configuration_to_xml / import_configuration_from_xml / " //$NON-NLS-1$
                    + "import_configuration_from_binary / export_object / " //$NON-NLS-1$
                    + "export_common_picture / export_configuration_to_cf / unpack_external_binary / " //$NON-NLS-1$
                    + "help (snake_case " //$NON-NLS-1$
                    + "canonical; camelCase like exportObject is also accepted). Pass " //$NON-NLS-1$
                    + "operation=help without other params for the operation catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("binaryPath", //$NON-NLS-1$
                "import_configuration_from_binary: absolute path to the .cf or .cfe to " //$NON-NLS-1$
                    + "import. Required for that operation.") //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "import_configuration_from_binary: platform version for the staging infobase " //$NON-NLS-1$
                    + "(e.g. 8.3.24). Omit for the newest installed.") //$NON-NLS-1$
            .stringProperty("extensionName", //$NON-NLS-1$
                "import_configuration_from_binary, .cfe only: the name to load the extension " //$NON-NLS-1$
                    + "under. Omit to take it from the file name.") //$NON-NLS-1$
            .stringProperty("baseConfigurationPath", //$NON-NLS-1$
                "import_configuration_from_binary, .cfe only: a .cf to load into the staging " //$NON-NLS-1$
                    + "infobase first, so the extension has the configuration it borrows from.") //$NON-NLS-1$
            .stringProperty("keepXmlPath", //$NON-NLS-1$
                "import_configuration_from_binary: keep the intermediate Designer-XML here " //$NON-NLS-1$
                    + "instead of in a temporary directory that is deleted.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for export_configuration_to_xml, export_object and " //$NON-NLS-1$
                    + "export_common_picture. For import_configuration_from_xml and " //$NON-NLS-1$
                    + "import_configuration_from_binary this is instead the name of the NEW " //$NON-NLS-1$
                    + "project to create (required; must not already exist).") //$NON-NLS-1$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Absolute output path (required for export_configuration_to_xml, " //$NON-NLS-1$
                    + "export_object, export_common_picture and export_configuration_to_cf). " //$NON-NLS-1$
                    + "export_configuration_to_xml: a directory for the Designer-XML dump " //$NON-NLS-1$
                    + "(created if missing; existing contents overwritten). export_object: " //$NON-NLS-1$
                    + "the .epf/.erf file path (extension decides the kind, or it is " //$NON-NLS-1$
                    + "auto-detected). export_common_picture: a FILE path for the default / " //$NON-NLS-1$
                    + "single-variant export, or a DIRECTORY path when allVariants=true. " //$NON-NLS-1$
                    + "export_configuration_to_cf: the .cf file path.") //$NON-NLS-1$
            .stringProperty("importPath", //$NON-NLS-1$
                "import_configuration_from_xml: absolute path to the directory of " //$NON-NLS-1$
                    + "Designer-XML files to import (required for that operation).") //$NON-NLS-1$
            .stringProperty("sourcePath", //$NON-NLS-1$
                "unpack_external_binary: absolute path to the .epf or .erf file to convert " //$NON-NLS-1$
                    + "(required for that operation).") //$NON-NLS-1$
            .stringProperty("targetPath", //$NON-NLS-1$
                "unpack_external_binary: directory the XML is written into (required for that " //$NON-NLS-1$
                    + "operation). Feed it to import_configuration_from_xml as importPath " //$NON-NLS-1$
                    + "afterwards.")
            .stringProperty("applicationId", //$NON-NLS-1$
                "export_configuration_to_cf: infobase application id (from get_applications). " //$NON-NLS-1$
                    + "Optional - the project's default infobase is used when omitted.") //$NON-NLS-1$
            .stringProperty("skipValidation", //$NON-NLS-1$
                "export_configuration_to_cf: pass 'true' to skip the built-in validate_for_export " //$NON-NLS-1$
                    + "guard. By default the dump is blocked when export-breakers are found (run " //$NON-NLS-1$
                    + "validate_for_export for the full list); skip only to dump a configuration you " //$NON-NLS-1$
                    + "have already checked.") //$NON-NLS-1$
            .stringProperty("projectNature", //$NON-NLS-1$
                "import_configuration_from_xml: EDT nature id, or omit to auto-detect (e.g. " //$NON-NLS-1$
                    + "com._1c.g5.v8.dt.core.V8ConfigurationNature).") //$NON-NLS-1$
            .stringProperty("xmlVersion", //$NON-NLS-1$
                "import_configuration_from_xml: platform XML format version (e.g. 8.3.20), " //$NON-NLS-1$
                    + "or omit to auto-detect.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "export_object: object name within the project. Optional - required only " //$NON-NLS-1$
                    + "when the project contains more than one external object.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "export_object: soft timeout in seconds before returning a Pending JSON with " //$NON-NLS-1$
                    + "a runKey (default 30, range 5-120, clamped). " //$NON-NLS-1$
                    + "import_configuration_from_binary: the same, for its staging run.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "export_object and import_configuration_from_binary: resumes a previously " //$NON-NLS-1$
                    + "issued Pending run by its runKey; other params are ignored once runKey " //$NON-NLS-1$
                    + "is supplied. Still pass operation - the facade needs it to know which " //$NON-NLS-1$
                    + "run you are collecting.") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "export_common_picture: CommonPicture name, either 'CommonPicture.<Name>' or " //$NON-NLS-1$
                    + "the bare '<Name>' (required for that operation).") //$NON-NLS-1$
            .stringProperty("variant", //$NON-NLS-1$
                "export_common_picture: multi-variant only - pick one DPI tier by its " //$NON-NLS-1$
                    + "manifest entry name (e.g. '200.png'), a bare scale ('200'), or a " //$NON-NLS-1$
                    + "screenDensity token ('hdpi').") //$NON-NLS-1$
            .booleanProperty("allVariants", //$NON-NLS-1$
                "export_common_picture: multi-variant only - extract every zip entry into " //$NON-NLS-1$
                    + "outputPath treated as a directory (default false).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isBlank())
        {
            return ToolResult.error("operation is required. Allowed: " //$NON-NLS-1$
                + "export_configuration_to_xml / import_configuration_from_xml / " //$NON-NLS-1$
                + "import_configuration_from_binary / export_object / export_common_picture / " //$NON-NLS-1$
                + "export_configuration_to_cf / help.").toJson(); //$NON-NLS-1$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        if ("help".equals(operation)) //$NON-NLS-1$
        {
            return buildHelp(JsonUtils.extractStringArgument(params, "topic")); //$NON-NLS-1$
        }
        if (!OPS.containsKey(operation))
        {
            return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                + "'. Allowed: " + String.join(" / ", OPS.keySet()) //$NON-NLS-1$ //$NON-NLS-2$
                + " / help.").toJson(); //$NON-NLS-1$
        }
        // One gate for every operation this facade folds in. Reaching a tool through a facade is
        // still reaching that tool, and a preset that switched it off means it. Keyed on the
        // operation name because that IS the folded tool's name; an operation with no tool of its
        // own is in nobody's disabled set and passes straight through.
        String presetGate = ToolGate.gateIfPresetDisabled(operation);
        if (presetGate != null)
        {
            return ToolResult.error(presetGate).put("operation", operation).toJson(); //$NON-NLS-1$
        }
        switch (operation)
        {
            case "export_configuration_to_xml": //$NON-NLS-1$
                return new ConfigurationXmlExporter().execute(params);
            case "import_configuration_from_xml": //$NON-NLS-1$
                return new ConfigurationXmlImporter().execute(params);
            case "import_configuration_from_binary": //$NON-NLS-1$
                // Gated rather than called straight, for the same reason as
                // unpack_external_binary: this one starts a Designer and creates an
                // infobase, and a facade delegating in Java never passes the router where a
                // disabled tool is refused.
                return gatedRoute(ConfigurationBinaryImporter.NAME,
                    () -> new ConfigurationBinaryImporter().execute(params));
            case "export_object": //$NON-NLS-1$
                return new ExportObjectTool().execute(params);
            case "export_common_picture": //$NON-NLS-1$
                return new CommonPictureExporter().execute(params);
            case "unpack_external_binary": //$NON-NLS-1$
                // Routed through the gate rather than called straight: a facade delegating in Java
                // never passes McpRequestRouter, which is where a disabled tool is refused. Without
                // this, switching the Applications group off would still let this start a Designer
                // and write files, because config_io itself stays on.
                return gatedRoute(ExternalBinaryUnpacker.NAME,
                    () -> new ExternalBinaryUnpacker().execute(params));
            case "export_configuration_to_cf": //$NON-NLS-1$
                return exportConfigurationCfMarkdown(params);
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# config_io - operations\n\n"); //$NON-NLS-1$
            sb.append("- **export_configuration_to_xml** - export a whole configuration " //$NON-NLS-1$
                + "project to Designer-XML files (DumpConfigToFiles format). Synchronous, " //$NON-NLS-1$
                + "overwrites outputPath.\n"); //$NON-NLS-1$
            sb.append("- **import_configuration_from_xml** - import a configuration from " //$NON-NLS-1$
                + "Designer-XML files into a NEW EDT project. Rejects a pre-existing project " //$NON-NLS-1$
                + "name. MUTATING (creates a project).\n"); //$NON-NLS-1$
            sb.append("- **import_configuration_from_binary** - import a .cf or .cfe into a " //$NON-NLS-1$
                + "NEW EDT project, through a staging infobase that is created and deleted " //$NON-NLS-1$
                + "here. Never touches an existing project's infobase. Runs the thick client " //$NON-NLS-1$
                + "and may reply Pending with a runKey (50 s measured on 157 MB). MUTATING " //$NON-NLS-1$
                + "(creates a project).\n"); //$NON-NLS-1$
            sb.append("- **export_object** - build an external data processor / report DT " //$NON-NLS-1$
                + "project into a binary .epf / .erf file. May reply Pending with a runKey.\n"); //$NON-NLS-1$
            sb.append("- **export_common_picture** - export a CommonPicture's image bytes to " //$NON-NLS-1$
                + "a file.\n"); //$NON-NLS-1$
            sb.append("- **export_configuration_to_cf** - dump the infobase's current MAIN " //$NON-NLS-1$
                + "configuration to a binary .cf file via the 1C thick client (DESIGNER). " //$NON-NLS-1$
                + "Run update_database first to capture the project's latest changes. " //$NON-NLS-1$
                + "validate_for_export then runs automatically and blocks the dump on " //$NON-NLS-1$
                + "dump-breakers (e.g. <help> without its HTML); pass skipValidation=true to " //$NON-NLS-1$
                + "override. Synchronous.\n"); //$NON-NLS-1$
            sb.append("- **unpack_external_binary** - turn a binary .epf or .erf into XML " //$NON-NLS-1$
                + "sources. The first of two steps: import the XML afterwards to get a " //$NON-NLS-1$
                + "project.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation-picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# config_io - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| Dump the whole configuration to XML for VCS / exchange | " //$NON-NLS-1$
                + "export_configuration_to_xml |\n"); //$NON-NLS-1$
            sb.append("| Load a configuration from XML into a brand-new project | " //$NON-NLS-1$
                + "import_configuration_from_xml |\n"); //$NON-NLS-1$
            sb.append("| Somebody sent me the configuration or extension as one .cf/.cfe file | " //$NON-NLS-1$
                + "import_configuration_from_binary |\n"); //$NON-NLS-1$
            sb.append("| Build an external data processor / report into .epf/.erf | " //$NON-NLS-1$
                + "export_object |\n"); //$NON-NLS-1$
            sb.append("| Pull a CommonPicture's image bytes out to a file | " //$NON-NLS-1$
                + "export_common_picture |\n"); //$NON-NLS-1$
            sb.append("| Dump the whole configuration to a binary .cf file | " //$NON-NLS-1$
                + "export_configuration_to_cf (infobase's current config; update_database first) |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs an operation only when the tool behind it is enabled.
     * <p>
     * A facade that delegates in Java never passes {@code McpRequestRouter}, and the router is where
     * a disabled tool is refused. Only the routes added with this are covered; the older ones here
     * still delegate directly, which is a gap of its own rather than something this introduces.
     * </p>
     *
     * @param op the tool name whose setting decides
     * @param run what to do when it is enabled
     * @return the operation's reply, or the refusal
     */
    private static String gatedRoute(String op, java.util.function.Supplier<String> run)
    {
        String gate = ToolGate.gateOrNull(op);
        return gate != null ? ToolResult.error(gate).put("operation", op).toJson() : run.get(); //$NON-NLS-1$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "export_configuration_to_xml", "import_configuration_from_xml", //$NON-NLS-1$ //$NON-NLS-2$
            "import_configuration_from_binary", //$NON-NLS-1$
            "export_object", "export_common_picture", //$NON-NLS-1$ //$NON-NLS-2$
            "export_configuration_to_cf", "unpack_external_binary")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * Dumps the project's infobase main configuration to a {@code .cf} file through the
     * 1C thick client and renders the result as the facade's MARKDOWN.
     * <p>
     * The {@code .cf} reflects the infobase's CURRENT configuration; run {@code update_database}
     * first to capture project changes. {@code validate_for_export} then runs automatically and
     * blocks the dump on export-breakers (e.g. a missing help HTML); skipValidation=true overrides.
     *
     * @param params the call arguments (projectName / applicationId / outputPath)
     * @return the MARKDOWN report
     */
    private static String exportConfigurationCfMarkdown(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String outputPath = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required for export_configuration_to_cf."; //$NON-NLS-1$
        }
        if (outputPath == null || outputPath.isEmpty())
        {
            return "Error: outputPath is required for export_configuration_to_cf (.cf file path)."; //$NON-NLS-1$
        }

        // Pre-export guard: by default block the dump when validate_for_export finds export-breakers
        // (XDTO/XML defects that pass get_project_errors but crash the platform dump). Read-only scan;
        // opt out with skipValidation=true for a configuration already checked.
        boolean skipValidation =
            "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "skipValidation")); //$NON-NLS-1$ //$NON-NLS-2$
        if (!skipValidation)
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project != null)
            {
                ValidateForExportTool.ExportScan scan =
                    new ValidateForExportTool().scanForExport(project, null, null, 25);
                if (scan.error != null)
                {
                    // A guard that cannot run has not cleared anything. Falling through here would
                    // dump the .cf on the strength of a check that never happened, and the dump is
                    // exactly what these defects crash - so refuse and make the caller say
                    // skipValidation=true if they mean to export unchecked.
                    return "# export_configuration_to_cf - blocked\n\n" //$NON-NLS-1$
                        + "The pre-export scan could not complete, so nothing vouches for this " //$NON-NLS-1$
                        + "configuration:\n\n**" + scan.error + "**\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                        + "Run `validate_for_export` to see the failure in full, or pass " //$NON-NLS-1$
                        + "**skipValidation=true** to dump without the check.\n"; //$NON-NLS-1$
                }
                if (scan.findingsCount > 0)
                {
                    StringBuilder block = new StringBuilder();
                    block.append("# export_configuration_to_cf - blocked by validate_for_export\n\n"); //$NON-NLS-1$
                    block.append("**").append(scan.findingsCount).append(" export-breaker(s) found"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (scan.limited)
                    {
                        block.append(" (first shown: ").append(scan.findings.size()).append(')'); //$NON-NLS-1$
                    }
                    block.append("** - the .cf was NOT dumped. These defects pass get_project_errors " //$NON-NLS-1$
                        + "but crash the platform dump.\n\n"); //$NON-NLS-1$
                    block.append("| file | check | severity | line | message |\n"); //$NON-NLS-1$
                    block.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
                    for (Map<String, Object> finding : scan.findings)
                    {
                        String msg = String.valueOf(finding.get("message")).replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
                        block.append("| ").append(finding.get("file")).append(" | ").append(finding.get("check")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            .append(" | ").append(finding.get("severity")).append(" | ").append(finding.get("line")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            .append(" | ").append(msg).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    block.append("\nFix the findings (or run `validate_for_export` for the full list), " //$NON-NLS-1$
                        + "then retry. Pass **skipValidation=true** to dump anyway.\n"); //$NON-NLS-1$
                    return block.toString();
                }
            }
        }

        BmInfobaseExtensionHelper.ExportResult res =
            BmInfobaseExtensionHelper.exportConfigurationCf(projectName, applicationId, outputPath);
        StringBuilder out = new StringBuilder();
        out.append("# export_configuration_to_cf\n\n"); //$NON-NLS-1$
        if (res.ok)
        {
            out.append("**Exported:** ").append(res.outputPath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            out.append("- size: ").append(res.sizeBytes).append(" bytes\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (res.infobaseName != null && !res.infobaseName.isEmpty())
            {
                out.append("- infobase: ").append(res.infobaseName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            out.append("\nThe .cf holds the infobase's CURRENT configuration. Run `update_database` " //$NON-NLS-1$
                + "first to capture the EDT project's latest changes, and `validate_for_export` to " //$NON-NLS-1$
                + "catch export-breakers (e.g. a <help> page without its HTML file) before dumping.\n"); //$NON-NLS-1$
        }
        else
        {
            out.append("**Failed:** ").append(res.error).append("\n"); //$NON-NLS-1$
            if (res.failureKind != null)
            {
                out.append("- failureKind: ").append(res.failureKind).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (res.infobaseName != null && !res.infobaseName.isEmpty())
            {
                out.append("- infobase: ").append(res.infobaseName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return out.toString();
    }
}
