/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeSet;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * The insights and config_io catalogue entries keep the before-record contract.
 * <p>
 * The weighed value is the facade name, the facade description and the parsed facade input schema,
 * with annotations copied from the before record. The facade classes do not publish annotations.
 * Both sides are serialized with the same compact Gson writer ({@code disableHtmlEscaping}). This
 * is not a live tools/list measurement. The static combined length is 15203 bytes.
 * </p>
 */
public class TheInsightsAndConfigIoCatalogKeepsTheBeforeContractTest
{
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private static final String INSIGHTS_DESCRIPTION =
        "Configuration insight and analysis. Every operation reads only, except " //$NON-NLS-1$
            + "compare_three_way with an intent other than REPORT, which writes into the " //$NON-NLS-1$
            + "project and is refused under a preset without write rights."; //$NON-NLS-1$

    private static final String CONFIG_IO_DESCRIPTION =
        "Configuration import / export: round-trip the configuration to XML. " //$NON-NLS-1$
            + "import_configuration_from_xml and import_configuration_from_binary mutate " //$NON-NLS-1$
            + "the workspace (each creates a project) and export_object, " //$NON-NLS-1$
            + "import_configuration_from_binary and export_infobase_objects may reply " //$NON-NLS-1$
            + "Pending with a runKey to resume - the facade only routes, it adds no dryRun. " //$NON-NLS-1$
            + "export_configuration_to_cf dumps the infobase's current configuration (run " //$NON-NLS-1$
            + "update_database first to capture project changes); export_infobase_objects " //$NON-NLS-1$
            + "reads the infobase's own configuration and writes the Designer-XML files to " //$NON-NLS-1$
            + "a destination directory; export_database_configuration and " //$NON-NLS-1$
            + "export_database_extension are the guarded .cf/.cfe dumps - they refuse an " //$NON-NLS-1$
            + "occupied outputPath (overwrite=true overrides) and an infobase that does not " //$NON-NLS-1$
            + "hold the project's current configuration (allowOutOfSync=true overrides), so " //$NON-NLS-1$
            + "an old file can never pass as the result."; //$NON-NLS-1$

    private static final int INSIGHTS_BEFORE_BYTES = 8998;

    private static final int INSIGHTS_AFTER_BYTES = 8693;

    private static final int CONFIG_IO_BEFORE_BYTES = 6777;

    private static final int CONFIG_IO_AFTER_BYTES = 6510;

    /**
     * Reads a fixture stored beside this class.
     *
     * @param name the resource file name
     * @return the file text, never <code>null</code>
     * @throws IOException when the resource cannot be read
     */
    private static String read(String name) throws IOException
    {
        InputStream stream = TheInsightsAndConfigIoCatalogKeepsTheBeforeContractTest.class
            .getResourceAsStream(name);
        assertTrue("missing fixture " + name, stream != null); //$NON-NLS-1$
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8))
        {
            return scanner.useDelimiter("\\A").next(); //$NON-NLS-1$
        }
    }

    /**
     * Compact JSON used for both the before record and the weighed facade entry.
     *
     * @param element a parsed JSON value
     * @return UTF-8 text with no extra whitespace
     */
    private static String compact(JsonElement element)
    {
        return COMPACT.toJson(element);
    }

    /**
     * UTF-8 length of a compact JSON text.
     *
     * @param text compact JSON
     * @return the byte length
     */
    private static int utf8Length(String text)
    {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The description string of one schema node, or <code>null</code> when it has none.
     *
     * @param node a schema object
     * @return the description text
     */
    private static String descriptionOf(JsonObject node)
    {
        JsonElement description = node.get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * A copy with schema prose removed and a parameter named description kept.
     * <p>
     * Inside {@code properties} the keys are parameter names, not schema keywords. Skipping every
     * key spelled description would drop that parameter. The keyword is skipped only on the way
     * into each parameter's own schema.
     * </p>
     *
     * @param element any part of a tool entry
     * @return a copy without description keywords
     */
    private static JsonElement withoutDescriptions(JsonElement element)
    {
        if (element.isJsonArray())
        {
            JsonArray copied = new JsonArray();
            for (JsonElement item : element.getAsJsonArray())
            {
                copied.add(withoutDescriptions(item));
            }
            return copied;
        }
        if (!element.isJsonObject())
        {
            return element;
        }
        JsonObject source = element.getAsJsonObject();
        JsonObject copied = new JsonObject();
        for (String key : new TreeSet<>(source.keySet()))
        {
            if ("description".equals(key)) //$NON-NLS-1$
            {
                continue;
            }
            JsonElement value = source.get(key);
            if ("properties".equals(key) && value.isJsonObject()) //$NON-NLS-1$
            {
                JsonObject named = new JsonObject();
                JsonObject declared = value.getAsJsonObject();
                for (String parameter : new TreeSet<>(declared.keySet()))
                {
                    named.add(parameter, withoutDescriptions(declared.get(parameter)));
                }
                copied.add(key, named);
            }
            else
            {
                copied.add(key, withoutDescriptions(value));
            }
        }
        return copied;
    }

    /**
     * Operation names advertised by the operation parameter, in listed order.
     * <p>
     * The list ends where the spelling note begins ({@code (snake_case}).
     * </p>
     *
     * @param schema the input schema
     * @return the operation names, including help
     */
    private static List<String> advertisedOperations(JsonObject schema)
    {
        String description = descriptionOf(schema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("operation")); //$NON-NLS-1$
        int cut = description.indexOf(" ("); //$NON-NLS-1$
        String list = cut < 0 ? description : description.substring(0, cut);
        List<String> names = new ArrayList<>();
        for (String part : list.split(" / ")) //$NON-NLS-1$
        {
            names.add(part.trim());
        }
        return names;
    }

    /**
     * Checks one facade entry against its before record.
     * <p>
     * Schema shape must match the historical record; parameter text matches that record
     * or the explicit literal descriptor contract. Annotations are read from the historical record.
     * The fixed byte comparison isolates the purpose by substituting it into the historical record.
     * </p>
     *
     * @param tool the facade
     * @param resource the before-record fixture
     * @param expectedDescription the shortened purpose
     * @param beforeBytes compact length of the before record
     * @param afterBytes compact length after the purpose is replaced
     * @param propertyCount how many parameters the schema declares
     * @param readOnlyFacade whether the before record carries the two read-only hints
     * @param expectedDescriptions exact parameter text, or null to retain the historical descriptors
     * @throws IOException when the fixture cannot be read
     */
    private static void checkShortenedEntry(IMcpTool tool, String resource,
        String expectedDescription, int beforeBytes, int afterBytes, int propertyCount,
        boolean readOnlyFacade, JsonObject expectedDescriptions) throws IOException
    {
        String raw = read(resource);
        JsonObject before = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(raw, compact(before));
        assertEquals(beforeBytes, utf8Length(raw));
        assertEquals(before.get("name").getAsString(), tool.getName()); //$NON-NLS-1$

        JsonObject annotations = before.getAsJsonObject("annotations"); //$NON-NLS-1$
        assertFalse(annotations.get("openWorldHint").getAsBoolean()); //$NON-NLS-1$
        if (readOnlyFacade)
        {
            assertEquals(3, annotations.keySet().size());
            assertTrue(annotations.get("readOnlyHint").getAsBoolean()); //$NON-NLS-1$
            assertTrue(annotations.get("idempotentHint").getAsBoolean()); //$NON-NLS-1$
        }
        else
        {
            assertEquals(1, annotations.keySet().size());
            assertFalse(annotations.has("readOnlyHint")); //$NON-NLS-1$
            assertFalse(annotations.has("idempotentHint")); //$NON-NLS-1$
        }

        JsonObject liveSchema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        if (expectedDescriptions == null)
        {
            assertEquals(before.get("inputSchema"), liveSchema); //$NON-NLS-1$
        }
        else
        {
            assertEquals(withoutDescriptions(before.get("inputSchema")), //$NON-NLS-1$
                withoutDescriptions(liveSchema));
            assertEquals(before.getAsJsonObject("inputSchema").getAsJsonObject("properties").keySet(), //$NON-NLS-1$ //$NON-NLS-2$
                expectedDescriptions.keySet());
        }
        JsonObject properties = liveSchema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertEquals(propertyCount, properties.keySet().size());
        JsonArray required = liveSchema.getAsJsonArray("required"); //$NON-NLS-1$
        assertEquals(1, required.size());
        assertEquals("operation", required.get(0).getAsString()); //$NON-NLS-1$
        for (String parameter : before.getAsJsonObject("inputSchema") //$NON-NLS-1$
            .getAsJsonObject("properties").keySet()) //$NON-NLS-1$
        {
            String expected = expectedDescriptions == null
                ? descriptionOf(before.getAsJsonObject("inputSchema") //$NON-NLS-1$
                    .getAsJsonObject("properties").getAsJsonObject(parameter)) //$NON-NLS-1$
                : expectedDescriptions.get(parameter).getAsString();
            assertEquals(parameter, expected, descriptionOf(properties.getAsJsonObject(parameter)));
        }

        assertTrue(before.get("description").getAsString().contains("Pass operation=")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expectedDescription, tool.getDescription());
        assertFalse(tool.getDescription().contains("Pass operation=")); //$NON-NLS-1$
        assertTrue(tool.getDescription().length() < before.get("description").getAsString() //$NON-NLS-1$
            .length());

        JsonObject after = JsonParser.parseString(compact(before)).getAsJsonObject();
        after.addProperty("description", tool.getDescription()); //$NON-NLS-1$
        assertEquals(withoutDescriptions(before), withoutDescriptions(after));
        assertEquals(afterBytes, utf8Length(compact(after)));
        assertTrue(utf8Length(compact(after)) < utf8Length(compact(before)));
    }

    /**
     * insights keeps all 51 parameter descriptions and the read-only annotation shape.
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    public void insightsKeepsEveryParameterAndTheShortenedPurpose() throws IOException
    {
        checkShortenedEntry(new InsightsFacadeTool(), "catalog-runtime-before-insights.json", //$NON-NLS-1$
            INSIGHTS_DESCRIPTION, INSIGHTS_BEFORE_BYTES, INSIGHTS_AFTER_BYTES, 51, true, null);
        assertTrue(INSIGHTS_DESCRIPTION.contains(
            "Every operation reads only, except compare_three_way")); //$NON-NLS-1$
        assertTrue(INSIGHTS_DESCRIPTION.contains("with an intent other than REPORT")); //$NON-NLS-1$
        assertTrue(INSIGHTS_DESCRIPTION.contains("writes into the project")); //$NON-NLS-1$
        assertTrue(INSIGHTS_DESCRIPTION.contains("refused under a preset without write rights")); //$NON-NLS-1$
    }

    /**
     * config_io keeps all 26 parameter descriptions and the single open-world annotation.
     * <p>
     * The purpose still separates a binary import, which creates a project, from the connected
     * infobase exports, and it still states both overwrite refusals.
     * </p>
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    public void configIoKeepsEveryParameterAndTheShortenedPurpose() throws IOException
    {
        JsonObject expectedDescriptions = JsonParser.parseString(read(
            "/ru/aiedt/mcp/server/catalog-further-config-io-contract.json")).getAsJsonObject() //$NON-NLS-1$
            .getAsJsonObject("descriptions"); //$NON-NLS-1$
        checkShortenedEntry(new ConfigIoFacadeTool(), "catalog-runtime-before-config-io.json", //$NON-NLS-1$
            CONFIG_IO_DESCRIPTION, CONFIG_IO_BEFORE_BYTES, CONFIG_IO_AFTER_BYTES, 26, false, expectedDescriptions);
        assertTrue(CONFIG_IO_DESCRIPTION.contains("import_configuration_from_binary mutate")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("each creates a project")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("may reply Pending with a runKey to resume")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("the facade only routes, it adds no dryRun")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("dumps the infobase's current configuration")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains(
            "run update_database first to capture project changes")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("reads the infobase's own configuration")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains(
            "writes the Designer-XML files to a destination directory")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("guarded .cf/.cfe dumps")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains(
            "refuse an occupied outputPath (overwrite=true overrides)")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains(
            "does not hold the project's current configuration (allowOutOfSync=true overrides)")); //$NON-NLS-1$
        assertTrue(CONFIG_IO_DESCRIPTION.contains("an old file can never pass as the result")); //$NON-NLS-1$
    }

    /**
     * A parameter named description is a name in the properties map, so stripping prose keeps it.
     */
    @Test
    public void aParameterNamedDescriptionSurvivesStripping()
    {
        JsonObject entry = JsonParser.parseString(
            "{\"properties\":{\"description\":{\"type\":\"string\",\"description\":\"keep\"}}}") //$NON-NLS-1$
            .getAsJsonObject();
        JsonObject stripped = withoutDescriptions(entry).getAsJsonObject();
        JsonObject parameter = stripped.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("description"); //$NON-NLS-1$
        assertEquals("string", parameter.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(parameter.has("description")); //$NON-NLS-1$
    }

    /**
     * The delegate registries stay the before lists. Asking {@code routesTo} for a delegated
     * operation would construct that delegate, so this check reads {@code describedOperations}
     * instead.
     */
    @Test
    public void describedOperationsStayTheDelegateRegistries()
    {
        List<String> insights = new ArrayList<>(InsightsFacadeTool.describedOperations());
        Collections.sort(insights);
        assertEquals(Arrays.asList("compare_configurations", "compare_three_way", //$NON-NLS-1$ //$NON-NLS-2$
            "dependency_graph", "describe_db_tables", "detect_query_anti_patterns", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "generate_health_snapshot", "impact_analysis", "object_summary", "project_metrics", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "semantic_metadata_search"), insights); //$NON-NLS-1$

        List<String> configIo = new ArrayList<>(ConfigIoFacadeTool.describedOperations().keySet());
        Collections.sort(configIo);
        assertEquals(Arrays.asList("export_common_picture", "export_configuration_to_xml", //$NON-NLS-1$ //$NON-NLS-2$
            "export_object", "import_configuration_from_binary", //$NON-NLS-1$ //$NON-NLS-2$
            "import_configuration_from_xml", "unpack_external_binary"), configIo); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The four config_io exports the facade runs itself name themselves. help, an empty operation
     * and an unknown operation name none of them, and none of those lookups constructs a delegate.
     */
    @Test
    public void selfHandledConfigRoutesNameTheFacadeItself()
    {
        assertEquals("export_infobase_objects", route("export_infobase_objects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("export_configuration_to_cf", route("export_configuration_to_cf")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("export_database_configuration", route("export_database_configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("export_database_extension", route("export_database_extension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(route("help")); //$NON-NLS-1$
        assertNull(route("")); //$NON-NLS-1$
        assertNull(route("not_an_operation")); //$NON-NLS-1$
        assertNull(new ConfigIoFacadeTool().routesTo(new HashMap<>()));
    }

    /**
     * Asks one facade for help.
     *
     * @param tool the facade
     * @param topic the help topic, or <code>null</code> for the operation catalog
     * @return the help text
     */
    private static String help(IMcpTool tool, String topic)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        if (topic != null)
        {
            params.put("topic", topic); //$NON-NLS-1$
        }
        return tool.execute(params);
    }

    /**
     * The operation catalog and the workflow picker still name the advertised operations.
     * <p>
     * No schema sentence was moved onto a new topic. The catalog at {@code operation=help} with no
     * topic, and {@code topic=workflow}, are the topics the facades already advertise. A per-operation
     * topic is not requested here: that answer constructs the delegated tool.
     * </p>
     *
     * @throws IOException when a fixture cannot be read
     */
    @Test
    public void advertisedHelpStillNamesEveryOperation() throws IOException
    {
        JsonObject insightsSchema = JsonParser.parseString(new InsightsFacadeTool().getInputSchema())
            .getAsJsonObject();
        String insightsCatalog = help(new InsightsFacadeTool(), null);
        for (String name : advertisedOperations(insightsSchema))
        {
            assertTrue(name, insightsCatalog.contains("**" + name + "**")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(help(new InsightsFacadeTool(), "workflow").contains("operation picker")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(insightsCatalog.contains("an intent other than REPORT writes into the project")); //$NON-NLS-1$

        JsonObject configSchema = JsonParser.parseString(new ConfigIoFacadeTool().getInputSchema())
            .getAsJsonObject();
        String configCatalog = help(new ConfigIoFacadeTool(), null);
        for (String name : advertisedOperations(configSchema))
        {
            assertTrue(name, configCatalog.contains("**" + name + "**")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(help(new ConfigIoFacadeTool(), "workflow").contains("operation picker")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(configCatalog.contains("staging infobase")); //$NON-NLS-1$
        assertTrue(configCatalog.contains("Never touches an existing project's infobase")); //$NON-NLS-1$
        assertTrue(descriptionOf(insightsSchema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("operation")).contains("topic=<operation>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(descriptionOf(configSchema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("operation")).contains("topic=<operation>")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The route named by one config_io operation the facade handles itself.
     *
     * @param operation the operation argument
     * @return the route name, or <code>null</code>
     */
    private static String route(String operation)
    {
        Map<String, String> arguments = new HashMap<>();
        arguments.put("operation", operation); //$NON-NLS-1$
        return new ConfigIoFacadeTool().routesTo(arguments);
    }
}
