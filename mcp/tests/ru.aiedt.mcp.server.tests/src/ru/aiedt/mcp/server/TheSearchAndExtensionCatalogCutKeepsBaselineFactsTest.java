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

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.FacadeHelpSearch;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.CodeSearchTool;
import ru.aiedt.mcp.server.toolkit.ops.ExtensionWorkshopTool;

/**
 * The catalogue cut of {@code code_search} and {@code extension_workshop} keeps the published
 * shape and every baseline fact that the schema still has to state.
 * <p>
 * The two resources are the tools/list entries captured before the cut. Weight is the compact
 * Gson form of the parsed entry, the same escaping {@code JsonElement.toString()} applies, for
 * the baseline and for the entry this cut publishes. A fact that left the schema is read back
 * from the named help topic that now carries it. The root help topic is not consulted.
 * </p>
 */
public class TheSearchAndExtensionCatalogCutKeepsBaselineFactsTest
{
    private static final String CODE_BASELINE = "catalog-runtime-baseline-code-search.json"; //$NON-NLS-1$

    private static final String EXTENSION_BASELINE =
        "catalog-runtime-baseline-extension-workshop.json"; //$NON-NLS-1$

    /** Combined compact-Gson ceiling for the two published entries. */
    private static final int COMBINED_CEILING = 8500;

    private static final String[] CODE_SCHEMA = {
        "text_search / object_references / method_references / resolve_symbol / " //$NON-NLS-1$
            + "call_hierarchy / symbol_info / content_assist / outgoing_structures / help", //$NON-NLS-1$
        "snake_case canonical", //$NON-NLS-1$
        "camelCase", //$NON-NLS-1$
        "textSearch", //$NON-NLS-1$
        "EDT project name.", //$NON-NLS-1$
        "Search string for text_search / method_references.", //$NON-NLS-1$
        "object_references and outgoing_structures", //$NON-NLS-1$
        "1-based line for symbol_info / content_assist.", //$NON-NLS-1$
        "1-based column for symbol_info / content_assist.", //$NON-NLS-1$
        "line:column", //$NON-NLS-1$
        "{\"line\":N,\"column\":M}", //$NON-NLS-1$
        "Whole-word match for text_search / method_references.", //$NON-NLS-1$
        "top-5 files", //$NON-NLS-1$
        "(default 25, range 5-120)", //$NON-NLS-1$
        "default 100", //$NON-NLS-1$
        "max 500", //$NON-NLS-1$
        "Inverse of skipBsl", //$NON-NLS-1$
        "metadata back-references", //$NON-NLS-1$
        "commonModules, documents, catalogs, informationRegisters", //$NON-NLS-1$
        "incoming / callers (default", //$NON-NLS-1$
        "outgoing / callees", //$NON-NLS-1$
        "Object / Reference / Selection / Manager / Cache / List", //$NON-NLS-1$
        "Pending", //$NON-NLS-1$
        "soft timeout", //$NON-NLS-1$
        "/ predefined / fields", //$NON-NLS-1$
        "case-insensitive", //$NON-NLS-1$
        "path from src", //$NON-NLS-1$
        "module FQN", //$NON-NLS-1$
        "Treat query as regex. Default: false.", //$NON-NLS-1$
        "Default: false.", //$NON-NLS-1$
        "(default true)", //$NON-NLS-1$
        "(default 2, max 5)", //$NON-NLS-1$
        "(overrides contextLines)", //$NON-NLS-1$
        "substring", //$NON-NLS-1$
        "with limit", //$NON-NLS-1$
        "metadata-only", //$NON-NLS-1$
        "skip the BSL", //$NON-NLS-1$
        "re-call", //$NON-NLS-1$
    };

    private static final String[] EXTENSION_SCHEMA = {
        "create_extension_project / borrow_object / borrow_objects / borrow_child / " //$NON-NLS-1$
            + "borrow_form_item / borrow_module / list_borrowed / update_borrowed / " //$NON-NLS-1$
            + "install_extension / uninstall_extension / list_extension / " //$NON-NLS-1$
            + "export_extension / extension_lifecycle / extension_diff / " //$NON-NLS-1$
            + "list_interceptors / check_release_fitness / check_platform_verdict / help", //$NON-NLS-1$
        "must not already exist", //$NON-NLS-1$
        "existing extension", //$NON-NLS-1$
        "Required for create_extension_project", //$NON-NLS-1$
        "EDT default", //$NON-NLS-1$
        "Russian child-kind aliases accepted", //$NON-NLS-1$
        "batchResults", //$NON-NLS-1$
        "objectFqns", //$NON-NLS-1$
        "Pending", //$NON-NLS-1$
        "soft timeout", //$NON-NLS-1$
        "borrow_child: Attribute / TabularSection / Form / Template", //$NON-NLS-1$
        "when objectFqn names the owner", //$NON-NLS-1$
        "childName is read when this is", //$NON-NLS-1$
        "not borrowed", //$NON-NLS-1$
        "own children", //$NON-NLS-1$
        "Default false.", //$NON-NLS-1$
        "write the alignments", //$NON-NLS-1$
        "review only", //$NON-NLS-1$
        "ObjectModule / ManagerModule / RecordSetModule / CommandModule / ValueModule", //$NON-NLS-1$
        "http(s)://", //$NON-NLS-1$
        "github:/gh:", //$NON-NLS-1$
        "project default", //$NON-NLS-1$
        "infobase configuration", //$NON-NLS-1$
        "Default true.", //$NON-NLS-1$
        "full / dryRun / probeOnly", //$NON-NLS-1$
        "list_interceptors: filter by interceptor kind.", //$NON-NLS-1$
        "maximum rows", //$NON-NLS-1$
        "not opened, locked or altered", //$NON-NLS-1$
        "loadedAs", //$NON-NLS-1$
        "does not change the verdict", //$NON-NLS-1$
        "newest installed", //$NON-NLS-1$
        "refused by a newer", //$NON-NLS-1$
        "adoptServiceNotFound", //$NON-NLS-1$
        "GUI workaround hint", //$NON-NLS-1$
        "dryRun", //$NON-NLS-1$
        "staging infobase", //$NON-NLS-1$
        "write the extracted .cfe", //$NON-NLS-1$
        "generate a handler", //$NON-NLS-1$
        "object/method", //$NON-NLS-1$
    };

    /**
     * Names, types, required flags and annotations stay. Only description text may change.
     *
     * @throws IOException when a baseline resource cannot be read
     */
    @Test
    public void theRecordsKeepEveryNonProseField() throws IOException
    {
        assertShape(new CodeSearchTool(), CODE_BASELINE, true);
        assertShape(new ExtensionWorkshopTool(), EXTENSION_BASELINE, false);
    }

    /**
     * Each baseline fact is still a substring of its fixture, and is published either in the
     * schema record or at the named help topic.
     *
     * @throws IOException when a baseline resource cannot be read
     */
    @Test
    public void everyBaselineFactStaysAtItsPointer() throws IOException
    {
        String codeRaw = readResource(CODE_BASELINE);
        String extensionRaw = readResource(EXTENSION_BASELINE);
        List<String> missing = new ArrayList<>();
        missing.addAll(factsMissing("code_search schema", codeRaw, //$NON-NLS-1$
            recordText(published(new CodeSearchTool())), CODE_SCHEMA));
        missing.addAll(factsMissing("extension_workshop schema", extensionRaw, //$NON-NLS-1$
            recordText(published(new ExtensionWorkshopTool())), EXTENSION_SCHEMA));
        missing.addAll(helpFactsMissing("code_search", codeRaw, codeHelpTopics())); //$NON-NLS-1$
        missing.addAll(helpFactsMissing("extension_workshop", extensionRaw, //$NON-NLS-1$
            extensionHelpTopics()));
        assertTrue("baseline facts with no publication:\n" + String.join("\n", missing), //$NON-NLS-1$ //$NON-NLS-2$
            missing.isEmpty());
    }

    /**
     * Each published entry is smaller than its baseline, and the pair stays within the ceiling.
     * Both lengths are compact Gson bytes of the parsed JSON.
     *
     * @throws IOException when a baseline resource cannot be read
     */
    @Test
    public void thePairWeighsLessAndStaysWithinTheCeiling() throws IOException
    {
        String codeRaw = readResource(CODE_BASELINE);
        String extensionRaw = readResource(EXTENSION_BASELINE);
        int codeBefore = weigh(JsonParser.parseString(codeRaw));
        int codeAfter = weigh(published(new CodeSearchTool()));
        int extensionBefore = weigh(JsonParser.parseString(extensionRaw));
        int extensionAfter = weigh(published(new ExtensionWorkshopTool()));
        assertTrue("code_search weighs " + codeAfter + ", not under " + codeBefore, //$NON-NLS-1$ //$NON-NLS-2$
            codeAfter < codeBefore);
        assertTrue("extension_workshop weighs " + extensionAfter + ", not under " //$NON-NLS-1$ //$NON-NLS-2$
            + extensionBefore, extensionAfter < extensionBefore);
        assertTrue("the pair weighs " + (codeAfter + extensionAfter) //$NON-NLS-1$
            + ", above " + COMBINED_CEILING, //$NON-NLS-1$
            codeAfter + extensionAfter <= COMBINED_CEILING);
    }

    /**
     * {@code find} stays the shared help-search sentence, in the fixture and in the schema.
     *
     * @throws IOException when the code_search baseline cannot be read
     */
    @Test
    public void findStaysTheSharedSentence() throws IOException
    {
        String raw = readResource(CODE_BASELINE);
        String fixtureFind = JsonParser.parseString(raw).getAsJsonObject()
            .getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("find").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        String publishedFind = published(new CodeSearchTool())
            .getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("find").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FacadeHelpSearch.FIND_DESCRIPTION, fixtureFind);
        assertEquals(fixtureFind, publishedFind);
    }

    /**
     * Compares one published entry with its baseline once descriptions are removed.
     *
     * @param tool the tool whose schema is published now
     * @param resource the baseline resource name
     * @param annotations whether the baseline entry carries tool annotations
     * @throws IOException when the resource cannot be read
     */
    private static void assertShape(IMcpTool tool, String resource, boolean annotations)
        throws IOException
    {
        String raw = readResource(resource);
        JsonObject before = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject now = published(tool);
        assertFalse("the baseline of " + tool.getName() + " has no prose", //$NON-NLS-1$ //$NON-NLS-2$
            proseOf(before).isEmpty());
        assertEquals("the record of " + tool.getName() + " changed a non-description field", //$NON-NLS-1$ //$NON-NLS-2$
            withoutProse(before), withoutProse(now));
        JsonArray required = now.getAsJsonObject("inputSchema").getAsJsonArray("required"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, required.size());
        assertEquals("operation", required.get(0).getAsString()); //$NON-NLS-1$
        assertEquals(annotations, now.has("annotations")); //$NON-NLS-1$
        if (annotations)
        {
            JsonObject hints = now.getAsJsonObject("annotations"); //$NON-NLS-1$
            assertTrue(hints.get("readOnlyHint").getAsBoolean()); //$NON-NLS-1$
            assertTrue(hints.get("idempotentHint").getAsBoolean()); //$NON-NLS-1$
            assertFalse(hints.get("openWorldHint").getAsBoolean()); //$NON-NLS-1$
        }
    }

    /**
     * Reads the current wire catalogue entry without copying fields from its baseline.
     *
     * @param tool the tool whose registered name is requested
     * @return the actual tools/list entry
     * @throws IOException when the endpoint cannot be read
     */
    private static JsonObject published(IMcpTool tool) throws IOException
    {
        try (LiveServer server = LiveServer.start())
        {
            LiveServer.Response response = server.request("POST", "/mcp", LiveServer.headers( //$NON-NLS-1$ //$NON-NLS-2$
                "Content-Type", "application/json", "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "Authorization", server.bearer()), //$NON-NLS-1$
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
            JsonArray tools = JsonParser.parseString(response.body).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
            for (JsonElement entry : tools)
            {
                JsonObject record = entry.getAsJsonObject();
                if (tool.getName().equals(record.get("name").getAsString())) //$NON-NLS-1$
                {
                    return record;
                }
            }
            throw new AssertionError("Missing published tool: " + tool.getName()); //$NON-NLS-1$
        }
    }

    /**
     * Tool description plus every description inside the schema.
     *
     * @param entry a catalogue entry
     * @return those sentences, one per line
     */
    private static String recordText(JsonObject entry)
    {
        StringBuilder text = new StringBuilder(entry.get("description").getAsString()); //$NON-NLS-1$
        for (String line : proseOf(entry.get("inputSchema"))) //$NON-NLS-1$
        {
            text.append('\n').append(line);
        }
        return text.toString();
    }

    /**
     * Facts that the fixture does not carry, or that the published text dropped.
     *
     * @param place where the fact is required to be published
     * @param raw the baseline resource text
     * @param published the text that should still contain the fact
     * @param needles facts copied from the baseline
     * @return one line per missing fact
     */
    private static List<String> factsMissing(String place, String raw, String published,
        String[] needles)
    {
        List<String> missing = new ArrayList<>();
        for (String needle : needles)
        {
            if (!baselineCarries(raw, needle))
            {
                missing.add(place + " not in baseline: " + needle); //$NON-NLS-1$
            }
            else if (!published.contains(needle))
            {
                missing.add(place + ": " + needle); //$NON-NLS-1$
            }
        }
        return missing;
    }

    /**
     * Whether the raw fixture contains the fact, allowing JSON escapes of quotes.
     *
     * @param raw the baseline resource text
     * @param needle the fact
     * @return {@code true} when the resource carries that fact
     */
    private static boolean baselineCarries(String raw, String needle)
    {
        if (raw.contains(needle))
        {
            return true;
        }
        return raw.contains(needle.replace("\\", "\\\\").replace("\"", "\\\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Help facts, each read from its named topic.
     *
     * @param tool {@code code_search} or {@code extension_workshop}
     * @param raw the baseline resource text
     * @param topics topic name to the facts that topic must still publish
     * @return one line per missing fact
     */
    private static List<String> helpFactsMissing(String tool, String raw,
        Map<String, String[]> topics)
    {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String[]> topic : topics.entrySet())
        {
            String help = "code_search".equals(tool) //$NON-NLS-1$
                ? codeHelp(topic.getKey()) : extensionHelpText(topic.getKey());
            missing.addAll(factsMissing(tool + " help " + topic.getKey(), raw, help, //$NON-NLS-1$
                topic.getValue()));
        }
        return missing;
    }

    /**
     * Named code_search help topics and the baseline facts moved there.
     *
     * @return topic to facts
     */
    private static Map<String, String[]> codeHelpTopics()
    {
        Map<String, String[]> topics = new HashMap<>();
        topics.put("workflow", new String[] { //$NON-NLS-1$
            "search_in_code", "find_references", "go_to_definition", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "get_method_call_hierarchy", "get_symbol_info", "get_content_assist", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "get_outgoing_structures", "remain available", //$NON-NLS-1$ //$NON-NLS-2$
            "operation=help topic=<operation> answers what that one takes.", //$NON-NLS-1$
        });
        topics.put("text_search", new String[] { //$NON-NLS-1$
            "CommonModules", "Documents/SalesOrder", "only the total", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "file list with match counts", //$NON-NLS-1$
        });
        topics.put("object_references", new String[] { //$NON-NLS-1$
            "back (direct back refs) / produced (produced types) / predefined / " //$NON-NLS-1$
                + "fields (attribute/dimension refs) / bsl (BSL code)", //$NON-NLS-1$
        });
        topics.put("call_hierarchy", new String[] { //$NON-NLS-1$
            "CommonModules/MyModule/Module.bsl", "CommonModule.MyModule", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog.Products.ManagerModule", //$NON-NLS-1$
        });
        topics.put("resolve_symbol", new String[] { //$NON-NLS-1$
            "ОбщегоНазначения.СообщитьПользователю", //$NON-NLS-1$
        });
        topics.put("symbol_info", new String[] { //$NON-NLS-1$
            "src/-relative", //$NON-NLS-1$
        });
        topics.put("content_assist", new String[] { //$NON-NLS-1$
            "proposal list", //$NON-NLS-1$
        });
        return topics;
    }

    /**
     * Named extension_workshop help topics and the baseline facts moved there.
     *
     * @return topic to facts
     */
    private static Map<String, String[]> extensionHelpTopics()
    {
        Map<String, String[]> topics = new HashMap<>();
        topics.put("workflow", new String[] { //$NON-NLS-1$
            "Catalog.Name.Form.FormName", //$NON-NLS-1$
            "Document.Name.Attribute.AttrName", //$NON-NLS-1$
            "Document.Name.TabularSection.TsName", //$NON-NLS-1$
            "Catalog.Name.Template.TplName", //$NON-NLS-1$
            "Catalog.Name.Command.CmdName", //$NON-NLS-1$
            "InformationRegister.Name.Dimension.DimName", //$NON-NLS-1$
            "InformationRegister.Name.Resource.ResName", //$NON-NLS-1$
            "which remain available", //$NON-NLS-1$
        });
        topics.put("check_platform_verdict", new String[] { //$NON-NLS-1$
            "8.3.27.1234", //$NON-NLS-1$
        });
        return topics;
    }

    /**
     * Help markdown for one code_search topic.
     *
     * @param topic the topic name; never empty
     * @return the help text
     */
    private static String codeHelp(String topic)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", topic); //$NON-NLS-1$
        return new CodeSearchTool().execute(params);
    }

    /**
     * The {@code text} field of one extension_workshop help topic.
     *
     * @param topic the topic name; never empty, so the root catalogue is not built
     * @return the help text
     */
    private static String extensionHelpText(String topic)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", topic); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(
            new ExtensionWorkshopTool().execute(params)).getAsJsonObject();
        return answer.get("text").getAsString(); //$NON-NLS-1$
    }

    /**
     * Compact Gson byte length of the parsed JSON value.
     *
     * @param element a parsed JSON value
     * @return UTF-8 length of {@link JsonElement#toString()}
     */
    private static int weigh(JsonElement element)
    {
        return element.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Description strings found anywhere under the element.
     *
     * @param element a JSON value
     * @return the description primitives, in encounter order
     */
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

    /**
     * The same JSON with description primitives removed.
     *
     * @param element a JSON value
     * @return a copy that keeps names, types, items, required and annotations
     */
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

    /**
     * Reads a baseline resource from this package.
     *
     * @param name the resource file name
     * @return the file text
     * @throws IOException when the resource is missing
     */
    private static String readResource(String name) throws IOException
    {
        try (InputStream stream = TheSearchAndExtensionCatalogCutKeepsBaselineFactsTest.class
            .getResourceAsStream(name); Scanner scanner = new Scanner(stream,
                StandardCharsets.UTF_8))
        {
            return scanner.useDelimiter("\\A").next(); //$NON-NLS-1$
        }
    }
}
