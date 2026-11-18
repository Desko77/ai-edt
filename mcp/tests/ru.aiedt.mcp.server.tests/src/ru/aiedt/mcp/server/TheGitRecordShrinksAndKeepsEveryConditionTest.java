/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The published {@code git} record gets smaller and keeps every input condition.
 * <p>
 * The object under test is the {@code git} entry of {@code tools/list} from {@link LiveServer}.
 * Descriptions may shrink. Names, types, the empty required list, key order and the writer
 * annotation may not. Every condition below is a phrase the description already carried, so the
 * check does not depend on a phrase that exists only after the cut.
 * </p>
 */
public class TheGitRecordShrinksAndKeepsEveryConditionTest
{
    /** UTF-8 size of the git tools/list object in the preserved runtime measurement. */
    private static final int RECORDED_BYTES = 4341;

    /**
     * The git object with every {@code description} removed, in wire key order.
     * <p>
     * {@code git} writes, so the annotation object is only {@code openWorldHint:false}. A reader
     * would also carry {@code readOnlyHint} and {@code idempotentHint}.
     * </p>
     */
    private static final String SHAPE_WITHOUT_DESCRIPTIONS =
        "{\"name\":\"git\",\"inputSchema\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"operation\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"projectName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"limit\":{\"type\":\"integer\"}," //$NON-NLS-1$
            + "\"paths\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"message\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"authorName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"authorEmail\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"branch\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"createBranch\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"filePath\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"fromRef\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"toRef\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"granularity\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"dryRun\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"pointId\":{\"type\":\"string\"}}," //$NON-NLS-1$
            + "\"required\":[]},\"annotations\":{\"openWorldHint\":false}}"; //$NON-NLS-1$

    /** The 15 input names in the order the schema publishes them. */
    private static final String[] PROPERTY_ORDER = {
        "operation", //$NON-NLS-1$
        "projectName", //$NON-NLS-1$
        "limit", //$NON-NLS-1$
        "paths", //$NON-NLS-1$
        "message", //$NON-NLS-1$
        "authorName", //$NON-NLS-1$
        "authorEmail", //$NON-NLS-1$
        "branch", //$NON-NLS-1$
        "createBranch", //$NON-NLS-1$
        "filePath", //$NON-NLS-1$
        "fromRef", //$NON-NLS-1$
        "toRef", //$NON-NLS-1$
        "granularity", //$NON-NLS-1$
        "dryRun", //$NON-NLS-1$
        "pointId" //$NON-NLS-1$
    };

    /** The server this case started, or <code>null</code> before the first catalogue read. */
    private LiveServer server;

    /**
     * Stops the server this case started.
     */
    @After
    public void stopTheServer()
    {
        if (server != null)
        {
            server.close();
        }
    }

    /**
     * The published git object weighs fewer than 4341 UTF-8 bytes.
     * <p>
     * The bytes are one {@code tools/list} object from {@link LiveServer}. A record that still
     * weighs 4341 fails on that number. {@code projectName} and {@code message} are compared with
     * the short sentences only after the weight has dropped.
     * </p>
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void thePublishedGitRecordWeighsLessThan4341() throws IOException
    {
        JsonObject tool = publishedGit();
        int bytes = tool.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue("git record weighs " + bytes + ", not under " + RECORDED_BYTES, //$NON-NLS-1$ //$NON-NLS-2$
            bytes < RECORDED_BYTES);
        assertEquals("projectName", "EDT project name; its repository is used.", //$NON-NLS-1$ //$NON-NLS-2$
            propertyDescription(tool, "projectName")); //$NON-NLS-1$
        assertEquals("message", "commit: commit message (required).", //$NON-NLS-1$ //$NON-NLS-2$
            propertyDescription(tool, "message")); //$NON-NLS-1$
    }

    /**
     * Names, types, required, key order and the writer annotation stay put.
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void theRecordWithoutDescriptionsKeepsItsShape() throws IOException
    {
        JsonObject tool = publishedGit();
        assertEquals(SHAPE_WITHOUT_DESCRIPTIONS, withoutDescriptions(tool).toString());
        assertEquals(PROPERTY_ORDER.length, propertyNames(tool).size());
    }

    /**
     * Each of the 15 inputs still publishes the condition that governs it.
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void everyPropertyKeepsItsPublishedCondition() throws IOException
    {
        JsonObject tool = publishedGit();
        List<String> missing = missingConditions(tool);
        assertTrue(String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
        assertEquals(List.of(PROPERTY_ORDER), new ArrayList<>(propertiesCovered()));
        assertTrue("the condition matrix is empty", conditions().size() > PROPERTY_ORDER.length); //$NON-NLS-1$
    }

    /**
     * Removing a safety phrase from a copy of the live record is reported.
     * <p>
     * The live record itself is left intact. Each oracle deletes one phrase the checker requires,
     * so a checker that ignores that phrase cannot pass.
     * </p>
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void aDeletedSafetyPhraseIsReported() throws IOException
    {
        JsonObject tool = publishedGit();
        List<String> silent = new ArrayList<>();
        for (Condition oracle : safetyOracles())
        {
            JsonObject damaged = copyOf(tool);
            if (oracle.onTool)
            {
                dropToolPhrase(damaged, oracle.token);
            }
            else
            {
                dropPropertyPhrase(damaged, oracle.property, oracle.token);
            }
            String report = String.join("\n", missingConditions(damaged)); //$NON-NLS-1$
            if (!holds(report, oracle.token))
            {
                silent.add(oracle.property + " did not report \"" + oracle.token + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue(String.join("\n", silent), silent.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A changed type, an extra required name, or a deleted property leaves the frozen shape.
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void aCorruptedSchemaShapeIsReported() throws IOException
    {
        JsonObject tool = publishedGit();
        JsonObject retyped = copyOf(tool);
        retyped.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("paths").addProperty("type", "number"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(SHAPE_WITHOUT_DESCRIPTIONS.equals(withoutDescriptions(retyped).toString()));

        JsonObject required = copyOf(tool);
        required.getAsJsonObject("inputSchema").getAsJsonArray("required").add("paths"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(SHAPE_WITHOUT_DESCRIPTIONS.equals(withoutDescriptions(required).toString()));

        JsonObject deleted = copyOf(tool);
        deleted.getAsJsonObject("inputSchema").getAsJsonObject("properties").remove("paths"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(SHAPE_WITHOUT_DESCRIPTIONS.equals(withoutDescriptions(deleted).toString()));
    }

    /**
     * Phrases whose deletion has to be visible.
     * <p>
     * Each one is also in {@link #conditions()}, on the same property. The families are add-all,
     * conflicting files, method-only {@code .bsl}, a dirty-buffer write, a disk-based preview, a mandatory delete point,
     * configured-author precedence, the project-relative path, and a delete that touches no project
     * file.
     * </p>
     *
     * @return the oracles, never <code>null</code>
     */
    private static List<Condition> safetyOracles()
    {
        List<Condition> oracles = new ArrayList<>();
        oracles.add(claim("paths", false, "a blanket add-all is refused")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("branch", false, //$NON-NLS-1$
            "A switch refused on conflicting files names them and changes nothing")); //$NON-NLS-1$
        oracles.add(claim("granularity", false, "any other file is refused")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("dryRun", false, "only the write is refused then")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("dryRun", false, "file on disk")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("pointId", false, "the point to delete, required")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("authorName", false, "The configured author is used first.")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("filePath", false, "a project-relative path is accepted")); //$NON-NLS-1$ //$NON-NLS-2$
        oracles.add(claim("pointId", true, "no project file is touched")); //$NON-NLS-1$ //$NON-NLS-2$
        return oracles;
    }

    /**
     * Every input, the condition it governs, and whether that phrase is on the tool description.
     *
     * @return the matrix, property order preserved, never <code>null</code>
     */
    private static List<Condition> conditions()
    {
        List<Condition> rows = new ArrayList<>();
        rows.add(claim("operation", false, "status")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "branches")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "log")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "commit")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "checkout")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "show_file_changes")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "revert_file")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "create_merge_restore_point")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "delete_merge_restore_point")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "restore_merge_point")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", false, "(required)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "work tree and index vs HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "ahead/behind")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "local branches, current first")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "recent commits")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "switch branch, or create it")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "status")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "branches")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "log")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "commit")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "checkout")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "show_file_changes")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "revert_file")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "create_merge_restore_point")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "delete_merge_restore_point")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("operation", true, "restore_merge_point")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("projectName", false, "EDT project")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("projectName", false, "its repository is")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("limit", false, "(default 10, at most 100)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("limit", false, "(default 50, at most 200)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("limit", false, "totalFileCount")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("paths", false, "relative to the repository's work tree")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("paths", false, "by name only")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("paths", false, "a blanket add-all is refused")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("paths", true, "no add-all")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("message", false, "commit message (required)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("authorName", false, "names none")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("authorName", false, "The configured author is used first.")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("authorEmail", false, "paired with authorName")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("branch", false, "(with createBranch) to create and switch to")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("branch", false, //$NON-NLS-1$
            "A switch refused on conflicting files names them and changes nothing")); //$NON-NLS-1$
        rows.add(claim("createBranch", false, "create the branch")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("createBranch", false, "switching to an existing one (default false)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("createBranch", false, "HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", false, "relative to the work tree")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", false, "a project-relative path is accepted")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", false, "Omit it to list")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", false, "no hunks")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", false, "at most limit")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", false, "the one file to put back (required)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", true, "line endings a checkout would give")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", true, "index is not touched")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", true, "matches HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", true, "listed as modified")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("filePath", true, "revalidate_objects")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("fromRef", false, "commit to read")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("fromRef", false, "SHA, branch or HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("fromRef", false, "Default HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("toRef", false, "to compare against")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("toRef", false, "Omit it to compare with the work tree")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("granularity", false, "line (default) or method")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("granularity", false, "changed procedure and function")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("granularity", false, "applies only to one .bsl filePath; any other file is refused")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("granularity", true, "not a method hunk")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("granularity", true, "file totals")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("granularity", true, "changed procedures and functions by name")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("dryRun", false, "previews the diff against fromRef")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("dryRun", false, "writes nothing (default false)")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("dryRun", false, "file on disk")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("dryRun", false, "while an editor holds unsaved changes")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("dryRun", false, "only the write is refused then")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", false, "point to put back")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", false, "Omit it to use the latest point for the project")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", false, "the point to delete, required")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "records the project files")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "does not move HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "copy of the project directory")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "not rewritten")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "does not roll back the infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "the ref or the copy, and the index entry")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.add(claim("pointId", true, "no project file is touched")); //$NON-NLS-1$ //$NON-NLS-2$
        return rows;
    }

    /**
     * Conditions the live record no longer publishes.
     *
     * @param tool the git object, possibly a damaged copy
     * @return one line per missing phrase, empty when every phrase holds
     */
    private static List<String> missingConditions(JsonObject tool)
    {
        List<String> missing = new ArrayList<>();
        for (Condition condition : conditions())
        {
            String text = condition.onTool ? toolDescription(tool)
                : propertyDescription(tool, condition.property);
            if (!holds(text, condition.token))
            {
                String where = condition.onTool ? "tool description" : condition.property; //$NON-NLS-1$
                missing.add(condition.property + " at " + where + " lost \"" + condition.token + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return missing;
    }

    /**
     * Properties named by the matrix, in first-seen order.
     *
     * @return the 15 input names
     */
    private static Set<String> propertiesCovered()
    {
        Set<String> names = new LinkedHashSet<>();
        for (Condition condition : conditions())
        {
            names.add(condition.property);
        }
        return names;
    }

    /**
     * Whether a text carries a required phrase.
     *
     * @param text the description, or <code>null</code> when it is absent
     * @param required the phrase
     * @return <code>true</code> when the phrase is there, case-insensitively
     */
    private static boolean holds(String text, String required)
    {
        return text != null && text.toLowerCase(Locale.ROOT).contains(required.toLowerCase(Locale.ROOT));
    }

    /**
     * The tool description.
     *
     * @param tool the git object
     * @return the description, or <code>null</code> when the key is absent
     */
    private static String toolDescription(JsonObject tool)
    {
        JsonElement description = tool.get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * One parameter description.
     *
     * @param tool the git object
     * @param property the parameter name
     * @return the description, or <code>null</code> when the parameter or its description is absent
     */
    private static String propertyDescription(JsonObject tool, String property)
    {
        JsonObject schema = tool.getAsJsonObject("inputSchema"); //$NON-NLS-1$
        if (schema == null || !schema.has("properties")) //$NON-NLS-1$
        {
            return null;
        }
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        if (!properties.has(property) || !properties.get(property).isJsonObject())
        {
            return null;
        }
        JsonElement description = properties.getAsJsonObject(property).get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * Parameter names in wire order.
     *
     * @param tool the git object
     * @return the names, never <code>null</code>
     */
    private static List<String> propertyNames(JsonObject tool)
    {
        List<String> names = new ArrayList<>();
        JsonObject properties = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : properties.keySet())
        {
            names.add(name);
        }
        return names;
    }

    /**
     * The same value with every {@code description} removed.
     * <p>
     * Key order is kept. Sorting would hide a reorder of the published object.
     * </p>
     *
     * @param element any part of the git object
     * @return a copy without description members
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
        JsonObject copied = new JsonObject();
        for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
        {
            if ("description".equals(member.getKey())) //$NON-NLS-1$
            {
                continue;
            }
            copied.add(member.getKey(), withoutDescriptions(member.getValue()));
        }
        return copied;
    }

    /**
     * A separate parse of the same object, so a mutation does not touch the original.
     *
     * @param tool the git object
     * @return the copy
     */
    private static JsonObject copyOf(JsonObject tool)
    {
        return JsonParser.parseString(tool.toString()).getAsJsonObject();
    }

    /**
     * Deletes one exact phrase from a parameter description.
     *
     * @param tool a copy to damage
     * @param property the parameter
     * @param token the phrase that must be present before the deletion
     */
    private static void dropPropertyPhrase(JsonObject tool, String property, String token)
    {
        JsonObject declared = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject(property);
        String text = declared.get("description").getAsString(); //$NON-NLS-1$
        assertTrue(property + " does not publish \"" + token + "\"", text.contains(token)); //$NON-NLS-1$ //$NON-NLS-2$
        declared.addProperty("description", text.replace(token, "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Deletes one exact phrase from the tool description.
     *
     * @param tool a copy to damage
     * @param token the phrase that must be present before the deletion
     */
    private static void dropToolPhrase(JsonObject tool, String token)
    {
        String text = tool.get("description").getAsString(); //$NON-NLS-1$
        assertTrue("tool description does not publish \"" + token + "\"", text.contains(token)); //$NON-NLS-1$ //$NON-NLS-2$
        tool.addProperty("description", text.replace(token, "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The git entry of {@code tools/list}.
     *
     * @return the published object
     * @throws IOException when the catalogue request fails
     */
    private JsonObject publishedGit() throws IOException
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
        JsonArray tools = document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        for (JsonElement element : tools)
        {
            JsonObject tool = element.getAsJsonObject();
            if ("git".equals(tool.get("name").getAsString())) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return tool;
            }
        }
        assertNotNull("git is no longer advertised", null); //$NON-NLS-1$
        return null;
    }

    /**
     * One matrix row.
     *
     * @param property the input the claim belongs to
     * @param onTool <code>true</code> when the phrase is on the tool description
     * @param token the phrase
     * @return the row
     */
    private static Condition claim(String property, boolean onTool, String token)
    {
        return new Condition(property, onTool, token);
    }

    /**
     * One required phrase and the input it belongs to.
     */
    private static final class Condition
    {
        private final String property;

        private final boolean onTool;

        private final String token;

        /**
         * Records one phrase.
         *
         * @param property the input the claim belongs to
         * @param onTool <code>true</code> when the phrase is on the tool description
         * @param token the phrase
         */
        private Condition(String property, boolean onTool, String token)
        {
            this.property = property;
            this.onTool = onTool;
            this.token = token;
        }
    }
}
