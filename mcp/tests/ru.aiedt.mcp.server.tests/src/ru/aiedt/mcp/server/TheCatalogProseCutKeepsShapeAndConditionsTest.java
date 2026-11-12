/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.InsightsFacadeTool;

/**
 * What a prose cut of two catalog entries is allowed to touch, held against what was published
 * before it.
 * <p>
 * The two heaviest facades after edit_metadata and infobase_admin had their descriptions and
 * parameter prose shortened: repetitions deleted, combination rules left to the help topics that
 * already carried them, every condition of write, refusal, permission and data loss kept in the
 * record itself. A cut like that fails silently in one of two ways - a field or a type goes with
 * the prose, or a condition the source text carried stops being published anywhere - and both are
 * what this holds against.
 * </p>
 * <p>
 * The baseline resources are the tools/list entries as they were published before the cut,
 * measured on a live server, not reconstructed from the shortened text afterwards. The condition
 * registries were written from the source text before it was cut: every needle names a condition
 * the pre-cut record or its help carried, and the test answers for each of them where it is
 * published now - in the record, or anywhere at all.
 * </p>
 */
public class TheCatalogProseCutKeepsShapeAndConditionsTest
{
    private static final String INSIGHTS_BASELINE = "catalog-cut-baseline-insights.json";

    private static final String CONFIG_IO_BASELINE = "catalog-cut-baseline-config-io.json";

    private LiveServer server;

    @After
    public void stopTheServer()
    {
        if (server != null)
        {
            server.close();
        }
    }

    /**
     * The records keep every field except the prose: same properties, same types, same required
     * flags, same annotations.
     *
     * @throws IOException when the server or a resource cannot be read
     */
    @Test
    public void theCutRecordsKeptEveryNonProseField() throws IOException
    {
        assertShapeKept("insights", INSIGHTS_BASELINE); //$NON-NLS-1$
        assertShapeKept("config_io", CONFIG_IO_BASELINE); //$NON-NLS-1$
    }

    /**
     * Every condition the pre-cut record and its help carried is still published - the critical
     * ones (write, refusal, permission, data loss, defaults, value sets) in the record itself,
     * the combination rules and response shapes in the record or a help topic.
     *
     * @throws IOException when the server or a resource cannot be read
     */
    @Test
    public void everySourceConditionIsStillPublished() throws IOException
    {
        List<String> missing = new ArrayList<>();
        missing.addAll(conditionsMissingFromTheRecord("insights", INSIGHTS_IN_RECORD)); //$NON-NLS-1$
        missing.addAll(conditionsMissingFromTheRecord("config_io", CONFIG_IO_IN_RECORD)); //$NON-NLS-1$

        String insightsHelp = helpOf(new InsightsFacadeTool(), INSIGHTS_TOPICS);
        String configIoHelp = helpOf(new ConfigIoFacadeTool(), CONFIG_IO_TOPICS);
        missing.addAll(conditionsMissingFrom("insights", INSIGHTS_ANYWHERE, //$NON-NLS-1$
            recordText("insights") + "\n" + insightsHelp)); //$NON-NLS-1$
        missing.addAll(conditionsMissingFrom("config_io", CONFIG_IO_ANYWHERE, //$NON-NLS-1$
            recordText("config_io") + "\n" + configIoHelp)); //$NON-NLS-1$

        assertTrue("conditions the source published and nothing answers for now:\n" //$NON-NLS-1$
            + String.join("\n", missing), missing.isEmpty());
    }

    /**
     * The cut actually cut: each of the two records weighs fewer UTF-8 bytes than its baseline.
     *
     * @throws IOException when the server or a resource cannot be read
     */
    @Test
    public void eachRecordWeighsLessThanBeforeTheCut() throws IOException
    {
        assertShrunk("insights", INSIGHTS_BASELINE); //$NON-NLS-1$
        assertShrunk("config_io", CONFIG_IO_BASELINE); //$NON-NLS-1$
    }

    private void assertShapeKept(String tool, String baselineResource) throws IOException
    {
        JsonObject published = recordOf(tool);
        JsonObject before = JsonParser.parseString(readResource(baselineResource))
            .getAsJsonObject();
        assertFalse("the baseline of " + tool + " carries no prose - a stripped baseline " //$NON-NLS-1$ //$NON-NLS-2$
            + "would make this comparison vacuous", proseOf(before).isEmpty()); //$NON-NLS-1$
        assertEquals("the record of " + tool + " changed a field other than a description", //$NON-NLS-1$ //$NON-NLS-2$
            withoutProse(before), withoutProse(published));
    }

    private void assertShrunk(String tool, String baselineResource) throws IOException
    {
        int before = readResource(baselineResource).getBytes(StandardCharsets.UTF_8).length;
        int now = recordOf(tool).toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(tool + " weighs " + now + " bytes, not fewer than the " + before //$NON-NLS-1$ //$NON-NLS-2$
            + " it weighed before the cut", now < before); //$NON-NLS-1$
    }

    private List<String> conditionsMissingFromTheRecord(String tool, String[] needles)
        throws IOException
    {
        return conditionsMissingFrom(tool, needles, recordText(tool));
    }

    private static List<String> conditionsMissingFrom(String tool, String[] needles, String text)
    {
        List<String> missing = new ArrayList<>();
        for (String needle : needles)
        {
            if (!text.contains(needle))
            {
                missing.add(tool + ": " + needle); //$NON-NLS-1$
            }
        }
        return missing;
    }

    private JsonObject recordOf(String tool) throws IOException
    {
        for (JsonElement element : askForTools())
        {
            JsonObject candidate = element.getAsJsonObject();
            if (tool.equals(candidate.get("name").getAsString())) //$NON-NLS-1$
            {
                return candidate;
            }
        }
        throw new IOException("tools/list does not advertise " + tool); //$NON-NLS-1$
    }

    /** The tool's own description plus every description inside its schema. */
    private String recordText(String tool) throws IOException
    {
        JsonObject record = recordOf(tool);
        StringBuilder text = new StringBuilder(record.get("description").getAsString()); //$NON-NLS-1$
        for (String line : proseOf(record))
        {
            text.append('\n').append(line);
        }
        return text.toString();
    }

    private static List<String> proseOf(JsonElement element)
    {
        List<String> prose = new ArrayList<>();
        if (element.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
                {
                    prose.add(member.getValue().getAsString());
                }
                else
                {
                    prose.addAll(proseOf(member.getValue()));
                }
            }
        }
        else if (element.isJsonArray())
        {
            for (JsonElement each : element.getAsJsonArray())
            {
                prose.addAll(proseOf(each));
            }
        }
        return prose;
    }

    private static JsonElement withoutProse(JsonElement element)
    {
        if (element.isJsonObject())
        {
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
                {
                    continue;
                }
                kept.add(member.getKey(), withoutProse(member.getValue()));
            }
            return kept;
        }
        if (element.isJsonArray())
        {
            JsonArray kept = new JsonArray();
            for (JsonElement each : element.getAsJsonArray())
            {
                kept.add(withoutProse(each));
            }
            return kept;
        }
        return element;
    }

    /** The whole help of a facade: the catalog, the workflow topic and every operation topic. */
    private static String helpOf(IMcpTool facade, String[] topics)
    {
        StringBuilder all = new StringBuilder(facade.execute(Map.of("operation", "help"))); //$NON-NLS-1$ //$NON-NLS-2$
        for (String topic : topics)
        {
            Map<String, String> params = new HashMap<>();
            params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("topic", topic); //$NON-NLS-1$
            all.append('\n').append(facade.execute(params));
        }
        return all.toString();
    }

    private JsonArray askForTools() throws IOException
    {
        if (server == null)
        {
            server = LiveServer.start();
        }
        Map<String, String> headers = LiveServer.headers(
            "Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Authorization", server.bearer()); //$NON-NLS-1$
        LiveServer.Response answer = server.request("POST", "/mcp", headers, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
        JsonObject document = JsonParser.parseString(answer.body).getAsJsonObject();
        return document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String readResource(String name) throws IOException
    {
        try (InputStream stream = TheCatalogProseCutKeepsShapeAndConditionsTest.class
            .getResourceAsStream(name); Scanner scanner = new Scanner(stream,
                StandardCharsets.UTF_8))
        {
            return scanner.useDelimiter("\\A").next(); //$NON-NLS-1$
        }
    }

    private static final String[] INSIGHTS_TOPICS =
        {"project_metrics", "dependency_graph", "compare_configurations", "compare_three_way",
            "detect_query_anti_patterns", "generate_health_snapshot", "impact_analysis",
            "object_summary", "describe_db_tables", "semantic_metadata_search", "workflow"};

    private static final String[] CONFIG_IO_TOPICS =
        {"export_configuration_to_xml", "import_configuration_from_xml",
            "import_configuration_from_binary", "export_object", "export_common_picture",
            "export_configuration_to_cf", "export_infobase_objects",
            "export_database_configuration", "export_database_extension",
            "unpack_external_binary", "workflow"};

    /**
     * Conditions that must stay in the tools/list record itself: purpose, the operation list and
     * its spellings, every value set and default, and every condition of write, refusal,
     * permission and data loss the pre-cut schema carried.
     */
    private static final String[] INSIGHTS_IN_RECORD =
        {
            "Configuration insight and analysis",
            "project_metrics / dependency_graph / compare_configurations / compare_three_way / "
                + "detect_query_anti_patterns / generate_health_snapshot / impact_analysis / "
                + "object_summary / describe_db_tables / semantic_metadata_search / help",
            "snake_case canonical",
            "camelCase like projectMetrics",
            "topic=<operation>",
            "Every operation reads only, except compare_three_way",
            "writes into the project",
            "refused under a preset without write rights",
            "Help topic when operation=help",
            "lists all operations with one-line summaries",
            "search this help for a case-insensitive substring",
            "EDT project name",
            "mode=files: the path of the first export",
            "FQN of the object in question",
            "Catalog.Products",
            "project_metrics: project / subsystem (default project)",
            "dependency_graph: project / subsystem / object / module",
            "project / module / method (default project)",
            "compare_configurations: project / objectFqn (default project)",
            "Subsystem name when scope=subsystem",
            "Module FQN when scope=module (dependency_graph) or scope=module/method",
            "method inside moduleFqn",
            "metadata / modules / mixed (default metadata)",
            "object / attribute / module / template (default object)",
            "attribute needs mode=projects",
            "BFS depth, 1-5 (default 2)",
            "in / out / both (default both)",
            "via values to keep, comma-separated or a JSON array",
            "to keep every kind",
            "node cap, default 200, at most 2000",
            "edge cap, default 500, at most 5000",
            "projects / files, required",
            "failed / failedCount name what the comparison could not read",
            "the second project's name",
            "the path to the second export",
            "directory holding the configuration to compare against",
            "detect renames via structural similarity (default true)",
            "info / warning / error / all (default warning)",
            "SELECT_STAR",
            "QUERY_IN_LOOP",
            "comma-separated rule names (default all)",
            "include the anti-pattern scan, slower (default true)",
            "include LOC/methods/modules metrics",
            "metadata object counts per type (default true)",
            "include the errors/warnings summary (default true)",
            "run get_project_errors to count validation problems",
            "delete / rename / modify",
            "influences the recommendation text only",
            "maximum references per category (default 100)",
            "maximum results (default 50)",
            "skip BSL code references, metadata back-references only (default false)",
            "run find_references (skipBsl=true) to count metadata back-references",
            "include the fields and virtual-table parameters (default true)",
            "matched case-insensitively against name, synonym and comment",
            "optional filter by type",
            "English singular",
            "Russian equivalent",
            "json (default) or markdown",
            "mermaid / plantuml / dot",
            "detailed debt items list with file:line (default false)",
            "timeout cap in seconds (default 60)",
            "directory holding the delivery both sides came from",
            "Omit for a two-sided comparison",
            "vendor configuration of the project",
            "by id or by name",
            "a JSON array of {\"object\":\"...\",\"rule\":\"...\"}",
            "Must end in .zip",
            "hand-made object correspondences are applied to this comparison",
            "REPORT (default) reads and changes nothing",
            "MERGE applies the decisions to the project - IRREVERSIBLE",
            "restore point of the project files is taken first",
            "git restore_merge_point puts them back without rolling back the infobase",
            "MERGE_IGNORING_PROBLEMS proceeds past a problem the environment called blocking",
            "OURS, VENDOR, BOTH or UNKNOWN",
            "the counts always cover everything",
            "list only objects of this metadata type",
            "present on one side",
            "present on both",
            "must take part in a merge",
            "how many matching objects to skip",
            "module pieces to skip in sections",
            "even when they do not identify as the same configuration in different versions",
            "assemble the answer into one document",
            "what the run did NOT check",
            "summary or full",
            "named by the method it is in",
            "CommonModule.X.Module.MethodName",
            "with its own attribution",
            "without touching its neighbours",
            "keeping it open for the next page",
        };

    /**
     * Conditions whose full rules the pre-cut record stated once and the help topics already
     * carried: combination refusals, response-shape notes, delegate-schema detail. Each must be
     * published in the record or in the facade's help.
     */
    private static final String[] INSIGHTS_ANYWHERE =
        {
            "objectFqn narrows the comparison to one object",
            "the answer writes no edgeKinds field",
            "internalEdgesDropped",
            "edgesDroppedByKind",
            "unmatchedKinds",
            "notApplied",
            "scope=project rejects subsystemName",
            "Nested subsystems are included",
            "scope=project rejects every selector",
            "scope=method requires both",
            "Two selectors together are refused",
            "narrows a comparison of two export directories",
            "success:true with failedCount above zero is an incomplete diff",
            "truncated says the four lists were cut at the entry cap",
            "cancelled says the operator stopped the scan",
            "is refused rather than compared as a file of that kind",
            "A file on one side and a directory on the other is refused",
            "a symbolic link, which this comparison does not follow",
            "canonical singular spelling",
            "The standalone tools behind the operations stay callable",
        };

    /** The same for config_io: export, import, staging-infobase and guarded-dump contracts. */
    private static final String[] CONFIG_IO_IN_RECORD =
        {
            "Configuration import / export",
            "round-trip the configuration to XML",
            "export_configuration_to_xml / import_configuration_from_xml / "
                + "import_configuration_from_binary / export_object / export_common_picture / "
                + "export_configuration_to_cf / export_infobase_objects / "
                + "unpack_external_binary / export_database_configuration / "
                + "export_database_extension / help",
            "snake_case canonical",
            "camelCase like exportObject",
            "topic=<operation>",
            "mutate the workspace",
            "each creates a project",
            "may reply Pending with a runKey to resume",
            "the facade only routes, it adds no dryRun",
            "dumps the infobase's current configuration",
            "run update_database first to capture project changes",
            "reads the infobase's own configuration",
            "writes the Designer-XML files to a destination directory",
            "guarded .cf/.cfe dumps",
            "refuse an occupied outputPath (overwrite=true overrides)",
            "does not hold the project's current configuration (allowOutOfSync=true overrides)",
            "an old file can never pass as the result",
            "Help topic when operation=help",
            "absolute .cf/.cfe path to import",
            "staging infobase platform version",
            "name to load the extension under",
            "if omitted, taken from the file name",
            "exact extension name to export",
            "list_extension shows installed names",
            ".cf loaded into the staging infobase first",
            "keep the intermediate Designer-XML here instead of a temporary directory that is deleted",
            "EDT project name",
            "the NEW project to create",
            "must not exist",
            "required by every export",
            "Designer-XML dump directory",
            "created if missing; existing contents overwritten",
            ".epf/.erf file path (extension picks the kind, else auto-detected)",
            "FILE path, or DIRECTORY when allVariants=true",
            "export_configuration_to_cf: .cf file path",
            "DIRECTORY path (must be absent or empty)",
            "existing file refused unless overwrite=true",
            "required object addresses from the INFOBASE's configuration",
            "a whole top object",
            "Catalog.Банки",
            "Справочник.Банки",
            "CommonForm.Имя",
            "directory of Designer-XML files (required)",
            "the .epf or .erf file to convert",
            "directory the XML is written into",
            "infobase application id (from get_applications)",
            "replace an existing file at outputPath",
            "default false - occupied path refused",
            "dump while the infobase lacks the project's current configuration",
            "the dump is refused instead of silently missing changes",
            "run update_database first",
            "skip the built-in validate_for_export guard",
            "the project of the configuration it extends",
            "omit to auto-detect",
            "object name within the project",
            "default 30, range 5-120, clamped",
            "Pending JSON with runKey",
            "resume a Pending run",
            "other params ignored once runKey is supplied",
            "'CommonPicture.<Name>' or bare '<Name>'",
            "manifest entry name",
            "screenDensity token",
            "extract every zip entry into outputPath as a directory",
        };

    /** The same anywhere-set for config_io: rules its parameter help and delegates carry. */
    private static final String[] CONFIG_IO_ANYWHERE =
        {
            "Omit for the newest installed",
            "Required for export_configuration_to_xml, export_object and export_common_picture",
            "Refused before any Designer starts on an empty list",
            "The existence check runs against the EDT project's model",
            "the file is replaced only after the new dump has been written and verified",
            "NOT_EQUAL",
            "LOADING",
            "run validate_for_export for the full list",
            "required only when the project contains more than one external object",
            "Feed it to import_configuration_from_xml as importPath afterwards",
            "the project's default infobase is used when omitted",
            "Still pass operation",
            "The standalone tools behind the operations stay callable",
            "If omitted, the kind is auto-detected",
            "A FILE path for the default / single-variant export",
        };
}
