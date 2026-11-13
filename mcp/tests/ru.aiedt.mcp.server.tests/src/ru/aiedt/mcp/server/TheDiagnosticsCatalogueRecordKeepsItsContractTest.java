/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The diagnostics tools/list entry: its byte weight, the description-free shape, and the
 * condition each of the 17 inputs publishes.
 * <p>
 * The shape oracle is the ordered tools/list object with every description removed, including
 * annotations and the schema constraints (types, items, required). It is a literal of the
 * declarations as published before this cut. The live record is the tools/list entry; the
 * comparison does not rebuild a schema from source and call that the baseline.
 * </p>
 * <p>
 * save_baseline uses {@code objects} as an optional FQN scope: an empty or omitted list records
 * the whole project, a non-array is refused, and a capped snapshot is refused. Those refusals
 * belong to save_baseline. The objects description keeps the revalidate whole-project rule and
 * the get_project_errors FQN filter. A description that calls the cap a revalidate refusal is
 * reported. fileFilter is a substring of the marker's object presentation applied on top of
 * scope, not a file-name filter.
 * </p>
 */
public class TheDiagnosticsCatalogueRecordKeepsItsContractTest
{
    /** UTF-8 weight of the diagnostics tools/list entry before this cut. */
    private static final int RECORDED_BYTES = 4077;

    /**
     * Description-free tools/list entry: name, inputSchema (types, items, required) and
     * annotations, in the order the catalogue publishes them.
     */
    private static final String FROZEN_SHAPE =
        "{\"name\":\"diagnostics\",\"inputSchema\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"operation\":{\"type\":\"string\"},\"topic\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"find\":{\"type\":\"string\"},\"projectName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"checkId\":{\"type\":\"string\"},\"objects\":{\"type\":\"array\",\"items\":" //$NON-NLS-1$
            + "{\"type\":\"string\"}},\"severity\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"scope\":{\"type\":\"string\"},\"compact\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"extraInfo\":{\"type\":\"boolean\"},\"sinceBaseline\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"limit\":{\"type\":\"integer\"},\"checkFilter\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"pathFilter\":{\"type\":\"string\"},\"fileFilter\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"waitForRefresh\":{\"type\":\"boolean\"},\"objectFqns\":{\"type\":\"array\"," //$NON-NLS-1$
            + "\"items\":{\"type\":\"string\"}}},\"required\":[\"operation\"]}," //$NON-NLS-1$
            + "\"annotations\":{\"readOnlyHint\":true,\"idempotentHint\":true," //$NON-NLS-1$
            + "\"openWorldHint\":false}}"; //$NON-NLS-1$

    /** Annotations object of {@link #FROZEN_SHAPE}. */
    private static final String FROZEN_ANNOTATIONS =
        "{\"readOnlyHint\":true,\"idempotentHint\":true,\"openWorldHint\":false}"; //$NON-NLS-1$

    /** Property names in catalogue order. */
    private static final String[] ORDER = {
        "operation", "topic", "find", "projectName", "checkId", "objects", "severity", "scope", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "compact", "extraInfo", "sinceBaseline", "limit", "checkFilter", "pathFilter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "fileFilter", "waitForRefresh", "objectFqns"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Input, family, needle. A needle is read only from that input's description, or from the
     * tool description when the input column is {@code <root>}.
     */
    private static final String[][] MATRIX = {
        {"operation", "operation", //$NON-NLS-1$ //$NON-NLS-2$
            "get_project_errors / get_problem_summary / revalidate_objects / clean_project / " //$NON-NLS-1$
                + "validate_for_export / get_check_description / save_baseline / " //$NON-NLS-1$
                + "clear_baseline / help (snake_case canonical"}, //$NON-NLS-1$
        {"operation", "operation", "camelCase like getProjectErrors is also accepted"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"operation", "operation", //$NON-NLS-1$ //$NON-NLS-2$
            "Pass operation=help without other params for the operation catalog."}, //$NON-NLS-1$
        {"topic", "topic", "Help topic when operation=help."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"topic", "topic", "one-line summaries."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"find", "find", "case-insensitive"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"find", "find", "all the words must appear inside one chunk"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"find", "find", "with topic only that topic is searched."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"projectName", "required-project", //$NON-NLS-1$ //$NON-NLS-2$
            "Required for revalidate_objects, validate_for_export, save_baseline and " //$NON-NLS-1$
                + "clear_baseline"}, //$NON-NLS-1$
        {"projectName", "optional-clean", "omitted cleans every project"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"projectName", "workspace-scope", "omitted scopes them to the whole workspace"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"checkId", "check-required", "check id (required"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"checkId", "check-substring", //$NON-NLS-1$ //$NON-NLS-2$
            "get_project_errors: keep only checks whose id contains this substring (optional)."}, //$NON-NLS-1$
        {"objects", "whole-project", "empty/omitted = whole project"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"objects", "error-fqn", "get_project_errors: keep only these object FQNs."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"severity", "severity", //$NON-NLS-1$ //$NON-NLS-2$
            "ERROR (default), WARNING, INFO, ALL, or a concrete native level (ERRORS, BLOCKER, " //$NON-NLS-1$
                + "CRITICAL, MAJOR, MINOR, TRIVIAL, NONE)."}, //$NON-NLS-1$
        {"scope", "scope", "session (default) / object / project / all."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"compact", "compact", //$NON-NLS-1$ //$NON-NLS-2$
            "grouped per-check / per-location counts instead of one row per marker."}, //$NON-NLS-1$
        {"extraInfo", "extra-info", //$NON-NLS-1$ //$NON-NLS-2$
            "add an 'Extra' column with what the check attached to the marker beyond its " //$NON-NLS-1$
                + "position"}, //$NON-NLS-1$
        {"sinceBaseline", "baseline-comparison", //$NON-NLS-1$ //$NON-NLS-2$
            "answer only findings absent from this project's recorded baseline"}, //$NON-NLS-1$
        {"sinceBaseline", "baseline-resolved", "resolvedSinceBaseline"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"sinceBaseline", "baseline-project", "needs projectName."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"limit", "error-limit", "get_project_errors: cap on results (default 100, max 1000)."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"limit", "export-limit", "validate_for_export: cap on findings (default 500)."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"checkFilter", "filter-check", //$NON-NLS-1$ //$NON-NLS-2$
            "validate_for_export: keep only findings whose check id contains this substring."}, //$NON-NLS-1$
        {"pathFilter", "filter-path", //$NON-NLS-1$ //$NON-NLS-2$
            "validate_for_export: keep only findings whose file path contains this substring."}, //$NON-NLS-1$
        {"fileFilter", "filter-file", //$NON-NLS-1$ //$NON-NLS-2$
            "substring filter on the marker's object presentation, applied on top of scope"}, //$NON-NLS-1$
        {"waitForRefresh", "refresh-default", //$NON-NLS-1$ //$NON-NLS-2$
            "poll EDT up to 3x300ms for freshly-computed markers (default true)"}, //$NON-NLS-1$
        {"objectFqns", "alias", "alias of `objects`"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"objectFqns", "alias", "Empty/omitted = whole project."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"<root>", "catalogue-root", //$NON-NLS-1$ //$NON-NLS-2$
            "get_project_errors, get_problem_summary, revalidate_objects, clean_project, " //$NON-NLS-1$
                + "validate_for_export, get_check_description, save_baseline, clear_baseline, " //$NON-NLS-1$
                + "help."}, //$NON-NLS-1$
        {"<root>", "catalogue-root", "snake_case canonical"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"<root>", "catalogue-root", "camelCase like getProjectErrors"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"<root>", "catalogue-root", "call operation=help for the catalog"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"<root>", "catalogue-root", "remain available for back-compat."}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    private LiveServer server;

    /**
     * Stops the server this test started.
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
     * The entry weighs fewer UTF-8 bytes than the 4077 it weighed before the cut.
     * <p>
     * On the unchanged entry the weight is 4077, so the assertion fails because the record is
     * not smaller.
     * </p>
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void theRecordWeighsFewerBytesThanThe4077ItWeighedBefore() throws IOException
    {
        int now = recordOf("diagnostics").toString().getBytes(StandardCharsets.UTF_8).length; //$NON-NLS-1$
        assertTrue("diagnostics weighs " + now + " bytes, not fewer than the " + RECORDED_BYTES //$NON-NLS-1$ //$NON-NLS-2$
            + " it weighed before the cut", now < RECORDED_BYTES); //$NON-NLS-1$
    }

    /**
     * Removing description keys leaves the frozen ordered record: properties, types, item
     * constraints, required, and annotations.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void theOrderedRecordWithoutDescriptionsMatchesTheFrozenShape() throws IOException
    {
        JsonObject published = recordOf("diagnostics"); //$NON-NLS-1$
        assertEquals("the description-free record changed shape", //$NON-NLS-1$
            FROZEN_SHAPE, withoutProse(published).toString());
        assertFamiliesReported(shapeDifference(published));
    }

    /**
     * Each of the 17 inputs publishes its condition in its own description. The clean record
     * reports no missing, conflicting or unresolved family.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void allSeventeenInputsPublishTheirConditions() throws IOException
    {
        JsonObject published = recordOf("diagnostics"); //$NON-NLS-1$
        List<String> uncovered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> absent = new ArrayList<>();
        for (String name : ORDER)
        {
            boolean listed = false;
            for (String[] row : MATRIX)
            {
                if (name.equals(row[0]))
                {
                    listed = true;
                    seen.add(name);
                }
            }
            if (!listed)
            {
                uncovered.add(name);
            }
        }
        for (String[] row : MATRIX)
        {
            if (!textOf(published, row[0]).contains(row[2]))
            {
                absent.add(row[0] + ": " + row[2]); //$NON-NLS-1$
            }
        }
        assertEquals("schema property order", Arrays.asList(ORDER), propertyNames(published)); //$NON-NLS-1$
        assertEquals(17, ORDER.length);
        assertEquals(17, seen.size());
        assertTrue("uncovered inputs: " + uncovered, uncovered.isEmpty()); //$NON-NLS-1$
        assertTrue("absent conditions:\n" + String.join("\n", absent), absent.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamiliesReported(missingConditions(published));
    }

    /**
     * Deleting or replacing one safety or applicability phrase in a copy of the live record
     * makes the detector report that family and no other.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void corruptingEachSafetyFamilyIsReported() throws IOException
    {
        JsonObject published = recordOf("diagnostics"); //$NON-NLS-1$
        assertFamilies(withToolDescription(published, "Project validation."), "catalogue-root"); //$NON-NLS-1$
        assertFamilies(withDescription(published, "operation", "help"), "operation"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withDescription(published, "topic", "topic"), "topic"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withDescription(published, "find", "find"), "find"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withDescription(published, "projectName", "EDT project name."), //$NON-NLS-1$ //$NON-NLS-2$
            "required-project", "optional-clean", "workspace-scope"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withoutPhrase(published, "projectName", //$NON-NLS-1$
            "Required for revalidate_objects, validate_for_export, save_baseline and clear_baseline"), //$NON-NLS-1$
            "required-project"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "checkId", "check id (required"), "check-required"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withoutPhrase(published, "checkId", //$NON-NLS-1$
            "get_project_errors: keep only checks whose id contains this substring (optional)."), //$NON-NLS-1$
            "check-substring"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "objects", "empty/omitted = whole project"), //$NON-NLS-1$ //$NON-NLS-2$
            "whole-project"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "objects", "keep only these object FQNs."), //$NON-NLS-1$ //$NON-NLS-2$
            "error-fqn"); //$NON-NLS-1$
        assertFamilies(withDescription(published, "objects", //$NON-NLS-1$
            propertyDescription(published, "objects") //$NON-NLS-1$
                + " revalidate_objects refuses a capped snapshot."), //$NON-NLS-1$
            "objects-misattributed-cap"); //$NON-NLS-1$
        assertFamilies(withDescription(published, "objects", //$NON-NLS-1$
            "revalidate_objects refuses a capped snapshot."), //$NON-NLS-1$
            "whole-project", "error-fqn", "objects-misattributed-cap"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withDescription(published, "severity", "ERROR"), "severity"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withDescription(published, "scope", "session"), "scope"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withoutPhrase(published, "compact", //$NON-NLS-1$
            "grouped per-check / per-location counts instead of one row per marker."), "compact"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withoutPhrase(published, "extraInfo", //$NON-NLS-1$
            "add an 'Extra' column with what the check attached to the marker beyond its position"), //$NON-NLS-1$
            "extra-info"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "sinceBaseline", "resolvedSinceBaseline"), //$NON-NLS-1$ //$NON-NLS-2$
            "baseline-resolved"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "sinceBaseline", //$NON-NLS-1$
            "answer only findings absent from this project's recorded baseline"), //$NON-NLS-1$
            "baseline-comparison"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "sinceBaseline", "needs projectName."), //$NON-NLS-1$ //$NON-NLS-2$
            "baseline-project"); //$NON-NLS-1$
        assertFamilies(withoutPhrase(published, "limit", "default 100, max 1000"), "error-limit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withoutPhrase(published, "limit", "default 500"), "export-limit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withDescription(published, "limit", "default 100"), //$NON-NLS-1$ //$NON-NLS-2$
            "error-limit", "export-limit"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withoutPhrase(published, "checkFilter", //$NON-NLS-1$
            "whose check id contains this substring."), "filter-check"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withoutPhrase(published, "pathFilter", //$NON-NLS-1$
            "whose file path contains this substring."), "filter-path"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withDescription(published, "fileFilter", //$NON-NLS-1$
            propertyDescription(published, "fileFilter") + " filename"), //$NON-NLS-1$ //$NON-NLS-2$
            "filter-file-filename"); //$NON-NLS-1$
        assertFamilies(withDescription(published, "fileFilter", "filename substring"), //$NON-NLS-1$ //$NON-NLS-2$
            "filter-file", "filter-file-filename"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(withoutPhrase(published, "waitForRefresh", "3x300ms"), "refresh-default"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withoutPhrase(published, "objectFqns", "alias of `objects`"), "alias"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFamilies(withoutPhrase(published, "objectFqns", "Empty/omitted = whole project."), //$NON-NLS-1$ //$NON-NLS-2$
            "alias"); //$NON-NLS-1$
        JsonObject dropped = copyOf(published);
        dropped.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("scope").remove("description"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFamilies(dropped, "scope"); //$NON-NLS-1$
    }

    /**
     * A copy with one schema constraint removed or reordered is reported as that constraint.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void aMalformedSchemaIsReported() throws IOException
    {
        JsonObject published = recordOf("diagnostics"); //$NON-NLS-1$

        JsonObject noProperties = copyOf(published);
        noProperties.getAsJsonObject("inputSchema").remove("properties"); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(noProperties, "properties"); //$NON-NLS-1$

        JsonObject noRequired = copyOf(published);
        noRequired.getAsJsonObject("inputSchema").remove("required"); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(noRequired, "required"); //$NON-NLS-1$

        JsonObject otherRequired = copyOf(published);
        JsonArray required = new JsonArray();
        required.add("projectName"); //$NON-NLS-1$
        otherRequired.getAsJsonObject("inputSchema").add("required", required); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(otherRequired, "required-operation"); //$NON-NLS-1$

        JsonObject limitType = copyOf(published);
        limitType.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("limit").addProperty("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertDifference(limitType, "limit-type"); //$NON-NLS-1$

        JsonObject noItems = copyOf(published);
        noItems.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("objects").remove("items"); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(noItems, "objects-items"); //$NON-NLS-1$

        JsonObject noAliasItems = copyOf(published);
        noAliasItems.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject("objectFqns").remove("items"); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(noAliasItems, "objectFqns-items"); //$NON-NLS-1$

        JsonObject noAnnotations = copyOf(published);
        noAnnotations.remove("annotations"); //$NON-NLS-1$
        assertDifference(noAnnotations, "annotations"); //$NON-NLS-1$

        JsonObject writer = copyOf(published);
        writer.getAsJsonObject("annotations").addProperty("readOnlyHint", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(writer, "annotations"); //$NON-NLS-1$

        JsonObject reordered = copyOf(published);
        JsonObject properties = reordered.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject rebuilt = new JsonObject();
        rebuilt.add("topic", properties.get("topic")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, JsonElement> member : properties.entrySet())
        {
            if (!"topic".equals(member.getKey())) //$NON-NLS-1$
            {
                rebuilt.add(member.getKey(), member.getValue());
            }
        }
        reordered.getAsJsonObject("inputSchema").add("properties", rebuilt); //$NON-NLS-1$ //$NON-NLS-2$
        assertDifference(reordered, "property-order"); //$NON-NLS-1$

        JsonObject noSchema = copyOf(published);
        noSchema.remove("inputSchema"); //$NON-NLS-1$
        assertDifference(noSchema, "inputSchema"); //$NON-NLS-1$
    }

    /**
     * Asserts that the shape detector reports exactly these differences.
     *
     * @param copy a mutated copy of the live record
     * @param expected the differences, in any order
     */
    private static void assertDifference(JsonObject copy, String... expected)
    {
        assertEquals(sorted(expected), shapeDifference(copy));
    }

    /**
     * Asserts that the condition detector reports exactly these families.
     *
     * @param copy a mutated copy of the live record
     * @param expected the families, in any order
     */
    private static void assertFamilies(JsonObject copy, String... expected)
    {
        assertEquals(sorted(expected), missingConditions(copy));
    }

    /**
     * Asserts that a detector reported nothing.
     *
     * @param reported families or shape differences; empty when the record is intact
     */
    private static void assertFamiliesReported(List<String> reported)
    {
        assertTrue("conflicting or unresolved: " + reported, reported.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Families whose needle is absent from that input, plus a cap attributed to revalidate and a
     * fileFilter described as a file name.
     *
     * @param record a tools/list entry, or a copy with descriptions replaced
     * @return the family names, sorted; empty when every condition holds
     */
    private static List<String> missingConditions(JsonObject record)
    {
        Set<String> missing = new LinkedHashSet<>();
        for (String[] row : MATRIX)
        {
            if (!textOf(record, row[0]).contains(row[2]))
            {
                missing.add(row[1]);
            }
        }
        if (propertyDescription(record, "objects").contains("capped")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            missing.add("objects-misattributed-cap"); //$NON-NLS-1$
        }
        String fileFilter = propertyDescription(record, "fileFilter"); //$NON-NLS-1$
        if (fileFilter.contains("filename") || fileFilter.contains("file name")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            missing.add("filter-file-filename"); //$NON-NLS-1$
        }
        List<String> reported = new ArrayList<>(missing);
        Collections.sort(reported);
        return reported;
    }

    /**
     * Differences between the description-free record and {@link #FROZEN_SHAPE}.
     *
     * @param live a tools/list entry, or a copy with the schema corrupted
     * @return the difference names, sorted; empty when the shape matches
     */
    private static List<String> shapeDifference(JsonObject live)
    {
        List<String> diff = new ArrayList<>();
        JsonObject actual = withoutProse(live).getAsJsonObject();
        if (!actual.has("annotations")) //$NON-NLS-1$
        {
            diff.add("annotations"); //$NON-NLS-1$
        }
        else if (!FROZEN_ANNOTATIONS.equals(actual.get("annotations").toString())) //$NON-NLS-1$
        {
            diff.add("annotations"); //$NON-NLS-1$
        }
        if (!actual.has("inputSchema") || !actual.get("inputSchema").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            diff.add("inputSchema"); //$NON-NLS-1$
        }
        else
        {
            JsonObject schema = actual.getAsJsonObject("inputSchema"); //$NON-NLS-1$
            if (!schema.has("properties") || !schema.get("properties").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                diff.add("properties"); //$NON-NLS-1$
            }
            else
            {
                JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
                if (!propertyNamesOf(properties).equals(Arrays.asList(ORDER)))
                {
                    diff.add("property-order"); //$NON-NLS-1$
                }
                if (!"integer".equals(typeName(properties, "limit"))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    diff.add("limit-type"); //$NON-NLS-1$
                }
                if (!stringItems(properties, "objects")) //$NON-NLS-1$
                {
                    diff.add("objects-items"); //$NON-NLS-1$
                }
                if (!stringItems(properties, "objectFqns")) //$NON-NLS-1$
                {
                    diff.add("objectFqns-items"); //$NON-NLS-1$
                }
            }
            if (!schema.has("required") || !schema.get("required").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                diff.add("required"); //$NON-NLS-1$
            }
            else if (!"[\"operation\"]".equals(schema.get("required").toString())) //$NON-NLS-1$ //$NON-NLS-2$
            {
                diff.add("required-operation"); //$NON-NLS-1$
            }
        }
        if (!FROZEN_SHAPE.equals(actual.toString()) && diff.isEmpty())
        {
            diff.add("frozen-literal"); //$NON-NLS-1$
        }
        Collections.sort(diff);
        return diff;
    }

    /**
     * Copies the record and replaces one property description.
     *
     * @param record the live record
     * @param property the input whose description changes
     * @param text the replacement description
     * @return the copy
     */
    private static JsonObject withDescription(JsonObject record, String property, String text)
    {
        JsonObject copy = copyOf(record);
        copy.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject(property).addProperty("description", text); //$NON-NLS-1$
        return copy;
    }

    /**
     * Copies the record and deletes one phrase from a property description.
     *
     * @param record the live record
     * @param property the input whose description changes
     * @param phrase the phrase to delete
     * @return the copy
     */
    private static JsonObject withoutPhrase(JsonObject record, String property, String phrase)
    {
        return withDescription(record, property,
            propertyDescription(record, property).replace(phrase, "")); //$NON-NLS-1$
    }

    /**
     * Copies the record and replaces the tool description.
     *
     * @param record the live record
     * @param text the replacement tool description
     * @return the copy
     */
    private static JsonObject withToolDescription(JsonObject record, String text)
    {
        JsonObject copy = copyOf(record);
        copy.addProperty("description", text); //$NON-NLS-1$
        return copy;
    }

    /**
     * The description of one input, or an empty string when the input or the description is gone.
     *
     * @param record a tools/list entry
     * @param property the input name
     * @return the description text; empty when absent
     */
    private static String propertyDescription(JsonObject record, String property)
    {
        if (!record.has("inputSchema") || !record.get("inputSchema").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ""; //$NON-NLS-1$
        }
        JsonObject schema = record.getAsJsonObject("inputSchema"); //$NON-NLS-1$
        if (!schema.has("properties") || !schema.get("properties").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ""; //$NON-NLS-1$
        }
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        if (!properties.has(property) || !properties.get(property).isJsonObject())
        {
            return ""; //$NON-NLS-1$
        }
        JsonObject node = properties.getAsJsonObject(property);
        if (!node.has("description") || !node.get("description").isJsonPrimitive()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ""; //$NON-NLS-1$
        }
        return node.get("description").getAsString(); //$NON-NLS-1$
    }

    /**
     * The tool description, or the named input's description.
     *
     * @param record a tools/list entry
     * @param input {@code <root>} or a property name
     * @return the description text; empty when absent
     */
    private static String textOf(JsonObject record, String input)
    {
        if ("<root>".equals(input)) //$NON-NLS-1$
        {
            if (!record.has("description") || !record.get("description").isJsonPrimitive()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return ""; //$NON-NLS-1$
            }
            return record.get("description").getAsString(); //$NON-NLS-1$
        }
        return propertyDescription(record, input);
    }

    /**
     * Property names of the live schema, in published order.
     *
     * @param record a tools/list entry
     * @return the names; empty when the schema has no properties object
     */
    private static List<String> propertyNames(JsonObject record)
    {
        if (!record.has("inputSchema") || !record.get("inputSchema").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Collections.emptyList();
        }
        JsonObject schema = record.getAsJsonObject("inputSchema"); //$NON-NLS-1$
        if (!schema.has("properties") || !schema.get("properties").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Collections.emptyList();
        }
        return propertyNamesOf(schema.getAsJsonObject("properties")); //$NON-NLS-1$
    }

    /**
     * Names of a properties object, in insertion order.
     *
     * @param properties the schema properties object
     * @return the names
     */
    private static List<String> propertyNamesOf(JsonObject properties)
    {
        return new ArrayList<>(properties.keySet());
    }

    /**
     * The JSON Schema type of one property, or an empty string when it has none.
     *
     * @param properties the schema properties object
     * @param name the property name
     * @return the type token
     */
    private static String typeName(JsonObject properties, String name)
    {
        if (!properties.has(name) || !properties.get(name).isJsonObject())
        {
            return ""; //$NON-NLS-1$
        }
        JsonObject node = properties.getAsJsonObject(name);
        if (!node.has("type") || !node.get("type").isJsonPrimitive()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ""; //$NON-NLS-1$
        }
        return node.get("type").getAsString(); //$NON-NLS-1$
    }

    /**
     * Whether an array property declares items of type string.
     *
     * @param properties the schema properties object
     * @param name the property name
     * @return {@code true} when items are strings
     */
    private static boolean stringItems(JsonObject properties, String name)
    {
        if (!properties.has(name) || !properties.get(name).isJsonObject())
        {
            return false;
        }
        JsonObject node = properties.getAsJsonObject(name);
        if (!node.has("items") || !node.get("items").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return false;
        }
        JsonObject items = node.getAsJsonObject("items"); //$NON-NLS-1$
        return items.has("type") && "string".equals(items.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A deep copy, so a mutation does not change the live record or another case.
     *
     * @param record the record to copy
     * @return the copy
     */
    private static JsonObject copyOf(JsonObject record)
    {
        return JsonParser.parseString(record.toString()).getAsJsonObject();
    }

    /**
     * Sorts a copy of the names.
     *
     * @param names the names
     * @return the names in order
     */
    private static List<String> sorted(String... names)
    {
        List<String> copy = new ArrayList<>(Arrays.asList(names));
        Collections.sort(copy);
        return copy;
    }

    /**
     * The same record with description keys removed, including nested schema descriptions.
     *
     * @param element a JSON value
     * @return the value without primitive description members
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
     * The diagnostics object from tools/list.
     *
     * @param tool the tool name
     * @return the catalogue entry
     * @throws IOException when the server cannot be read or does not advertise the tool
     */
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

    /**
     * tools/list as the server publishes it.
     *
     * @return the tools array
     * @throws IOException when the server cannot be read
     */
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
}
