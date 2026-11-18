/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;

/**
 * The shortened {@code yaxunit_tests} catalogue record keeps its contract and loses only prose.
 * <p>
 * The record is read from a real HTTP {@code tools/list} answer, not from the production description
 * getter and not from a document rebuilt out of the source. The record of the tool before the cut
 * weighed 3530 UTF-8 bytes; that number is frozen here, and the shrink test is deliberately written
 * so it compiles against the baseline and fails there only on "3530 not less than 3530".
 * </p>
 * <p>
 * Every caller-visible fact that decides what a call does is asserted by an exact phrase, in the
 * place the catalogue publishes it: the tool description or one named schema property. Negative
 * mutations reverse one family at a time in a copy of the actual record and must be reported under
 * that family name; schema changes are caught by letting the complete non-description record, with
 * its order, types, items, required list and annotations, stand as one independent literal. A
 * phrase list that accepted a reversed default would read as a passing contract, so the mutations
 * replace whole sentences rather than single words.
 * </p>
 */
public class TheYaxunitCatalogueRecordKeepsItsContractTest
{
    private static final String TOOL = "yaxunit_tests"; //$NON-NLS-1$

    /** The record's weight before this cut, in UTF-8 bytes, as the HTTP catalogue returned it. */
    private static final int BEFORE_BYTES = 3530;

    /**
     * The complete record with every description removed: name, schema, types, property order,
     * required list. {@code yaxunit_tests} is a writer and an open-world tool, so the catalogue
     * publishes no annotations member for it; the absence is asserted beside this literal.
     */
    private static final String NON_DESCRIPTION_RECORD =
        "{\"name\":\"yaxunit_tests\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
        + "\"mode\":{\"type\":\"string\"},"
        + "\"help\":{\"type\":\"string\"},"
        + "\"launchConfigurationName\":{\"type\":\"string\"},"
        + "\"projectName\":{\"type\":\"string\"},"
        + "\"applicationId\":{\"type\":\"string\"},"
        + "\"extensions\":{\"type\":\"string\"},"
        + "\"modules\":{\"type\":\"string\"},"
        + "\"tests\":{\"type\":\"string\"},"
        + "\"timeoutSeconds\":{\"type\":\"string\"},"
        + "\"runKey\":{\"type\":\"string\"},"
        + "\"updateBeforeLaunch\":{\"type\":\"boolean\"},"
        + "\"reuseRecent\":{\"type\":\"boolean\"},"
        + "\"installYaxunit\":{\"type\":\"boolean\"},"
        + "\"yaxunitUnsafeMode\":{\"type\":\"boolean\"},"
        + "\"yaxunitRepo\":{\"type\":\"string\"}"
        + "},\"required\":[]}}";

    private static final List<String> PROPERTY_ORDER = List.of(
        "mode", "help", "launchConfigurationName", "projectName", "applicationId",
        "extensions", "modules", "tests", "timeoutSeconds", "runKey", "updateBeforeLaunch",
        "reuseRecent", "installYaxunit", "yaxunitUnsafeMode", "yaxunitRepo");

    /** One phrase the published record must carry, and the family that owns it. */
    private static final class Condition
    {
        final String family;

        final String property;

        final String phrase;

        /**
         * Records one required phrase and its owning family and description.
         *
         * @param family detector family
         * @param property schema property, or null for the root description
         * @param phrase required literal phrase
         */
        Condition(String family, String property, String phrase)
        {
            this.family = family;
            this.property = property;
            this.phrase = phrase;
        }
    }

    /** A copy of the real record with one family's condition reversed. */
    private static final class Mutation
    {
        final String family;

        final Consumer<JsonObject> apply;

        /**
         * Records a mutation of a copied record and its expected detector family.
         *
         * @param family expected detector family
         * @param apply mutation applied only to a copy
         */
        Mutation(String family, Consumer<JsonObject> apply)
        {
            this.family = family;
            this.apply = apply;
        }
    }

    private static final Map<String, List<Condition>> SCHEMA_CONDITIONS = new LinkedHashMap<>();

    private static final List<Condition> TOOL_CONDITIONS = new ArrayList<>();

    private static final List<String> TOOL_FAMILY_ORDER = List.of(
        "filters", "aliases", "purpose", "help", "selection");

    private static final Set<String> TOOL_FAMILIES = new LinkedHashSet<>();

    private static final List<Condition> ALL_CONDITIONS = new ArrayList<>();

    private static final List<Mutation> MUTATIONS = new ArrayList<>();

    private static final List<Consumer<JsonObject>> SCHEMA_CORRUPTIONS = new ArrayList<>();

    static
    {
        addSchema("mode", //$NON-NLS-1$
            "run (default)", //$NON-NLS-1$
            "polls the launch synchronously", //$NON-NLS-1$
            "breakpoint-aware debugging"); //$NON-NLS-1$
        addSchema("help", //$NON-NLS-1$
            "topics, writing, assertions, setup, events, advanced", //$NON-NLS-1$
            "other parameters are ignored"); //$NON-NLS-1$
        addSchema("launchConfigurationName", "EDT Run Configuration name (preferred)"); //$NON-NLS-1$ //$NON-NLS-2$
        addSchema("projectName", "Project name (alternative to launchConfigurationName)"); //$NON-NLS-1$ //$NON-NLS-2$
        addSchema("applicationId", "Application ID for the project"); //$NON-NLS-1$ //$NON-NLS-2$
        addSchema("extensions", "CSV: extension names to run"); //$NON-NLS-1$ //$NON-NLS-2$
        addSchema("modules", "CSV: common module names with tests"); //$NON-NLS-1$ //$NON-NLS-2$
        addSchema("tests", //$NON-NLS-1$
            "CSV: test FQNs (Module.Method)", //$NON-NLS-1$
            "A name in tests or modules the run's report lacks is a discrepancy, not a pass."); //$NON-NLS-1$
        addSchema("timeoutSeconds", //$NON-NLS-1$
            "Whole-call deadline, seconds (default 60)", //$NON-NLS-1$
            "Pending with runKey", //$NON-NLS-1$
            "error naming the step", //$NON-NLS-1$
            "Aliases: timeout, timeoutMs"); //$NON-NLS-1$
        addSchema("runKey", //$NON-NLS-1$
            "Collect a Pending run", //$NON-NLS-1$
            "mode=run only"); //$NON-NLS-1$
        addSchema("updateBeforeLaunch", //$NON-NLS-1$
            "Default true", //$NON-NLS-1$
            "An update that does not finish refuses the launch", //$NON-NLS-1$
            "skips the pre-launch infobase sync", //$NON-NLS-1$
            "an omitted argument launches without updating", //$NON-NLS-1$
            "databaseUpdate=SKIPPED_BY_PRESET", //$NON-NLS-1$
            "explicit true is refused"); //$NON-NLS-1$
        addSchema("reuseRecent", //$NON-NLS-1$
            "Default false", //$NON-NLS-1$
            "last 5 minutes", //$NON-NLS-1$
            "instead of running again"); //$NON-NLS-1$
        addSchema("installYaxunit", //$NON-NLS-1$
            "Default false", //$NON-NLS-1$
            "latest YAxUnit release from GitHub", //$NON-NLS-1$
            "as 'YAxUnit' when absent", //$NON-NLS-1$
            "an installed engine is not downloaded", //$NON-NLS-1$
            "GitHub access", //$NON-NLS-1$
            "resolvable thick-client runtime", //$NON-NLS-1$
            "stored IB credentials", //$NON-NLS-1$
            "outcome 'installYaxunit'"); //$NON-NLS-1$
        addSchema("yaxunitUnsafeMode", //$NON-NLS-1$
            "Default true", //$NON-NLS-1$
            "only with installYaxunit=true", //$NON-NLS-1$
            "new and already-installed engines", //$NON-NLS-1$
            "reads both safety flags", //$NON-NLS-1$
            "lowers both in one write when either is on", //$NON-NLS-1$
            "confirms them before launch", //$NON-NLS-1$
            "a mismatch aborts with both actual values", //$NON-NLS-1$
            "False leaves the flags unread and unchanged"); //$NON-NLS-1$
        addSchema("yaxunitRepo", //$NON-NLS-1$
            "GitHub repo for installYaxunit as 'owner/repo'", //$NON-NLS-1$
            "Default 'bia-technologies/yaxunit'"); //$NON-NLS-1$

        addTool("filters", //$NON-NLS-1$
            "Filters extensions, modules, tests (CSV)", //$NON-NLS-1$
            "suites, tags and contexts are refused, not ignored"); //$NON-NLS-1$
        addTool("aliases", //$NON-NLS-1$
            "run_yaxunit_tests (mode=run)", //$NON-NLS-1$
            "debug_yaxunit_tests (mode=debug)", //$NON-NLS-1$
            "back-compat aliases", //$NON-NLS-1$
            "prefer it for new prompts"); //$NON-NLS-1$
        addTool("purpose", "Unified YAxUnit test runner"); //$NON-NLS-1$ //$NON-NLS-2$
        addTool("help", "help=<topic> returns built-in YAxUnit guidance"); //$NON-NLS-1$ //$NON-NLS-2$
        addTool("selection", //$NON-NLS-1$
            "New launches:", //$NON-NLS-1$
            "Without launchConfigurationName, projectName and applicationId are required", //$NON-NLS-1$
            "installYaxunit also requires projectName"); //$NON-NLS-1$

        MUTATIONS.add(new Mutation("mode", //$NON-NLS-1$
            record -> setProperty(record, "mode", //$NON-NLS-1$
                "debug (default) or run; neither polls the launch and debugging is not " //$NON-NLS-1$
                    + "breakpoint-aware."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("help", //$NON-NLS-1$
            record -> setProperty(record, "help", "Topic: topics only; other parameters are used."))); //$NON-NLS-1$ //$NON-NLS-2$
        MUTATIONS.add(new Mutation("launchConfigurationName", //$NON-NLS-1$
            record -> setProperty(record, "launchConfigurationName", //$NON-NLS-1$
                "EDT Run Configuration name (ignored)."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("projectName", //$NON-NLS-1$
            record -> setProperty(record, "projectName", //$NON-NLS-1$
                "EDT project; never required, even for installYaxunit."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("applicationId", //$NON-NLS-1$
            record -> setProperty(record, "applicationId", "Application ID; optional in every case."))); //$NON-NLS-1$ //$NON-NLS-2$
        MUTATIONS.add(new Mutation("extensions", //$NON-NLS-1$
            record -> setProperty(record, "extensions", "CSV: extension names to skip."))); //$NON-NLS-1$ //$NON-NLS-2$
        MUTATIONS.add(new Mutation("modules", //$NON-NLS-1$
            record -> setProperty(record, "modules", "CSV: module names without tests."))); //$NON-NLS-1$ //$NON-NLS-2$
        MUTATIONS.add(new Mutation("tests", //$NON-NLS-1$
            record -> setProperty(record, "tests", //$NON-NLS-1$
                "CSV: test names. A name in tests or modules the run's report lacks is a pass."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("timeoutSeconds", //$NON-NLS-1$
            record -> setProperty(record, "timeoutSeconds", //$NON-NLS-1$
                "No deadline. Aliases are ignored and a running client never answers Pending."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("runKey", //$NON-NLS-1$
            record -> setProperty(record, "runKey", "Collect a Pending run in either mode."))); //$NON-NLS-1$ //$NON-NLS-2$
        MUTATIONS.add(new Mutation("updateBeforeLaunch", //$NON-NLS-1$
            record -> setProperty(record, "updateBeforeLaunch", //$NON-NLS-1$
                "Default false. Set true to update before the launch. If the preset disables " //$NON-NLS-1$
                    + "update_database, an omitted argument updates and an explicit true is " //$NON-NLS-1$
                    + "accepted; answers never name a skip."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("reuseRecent", //$NON-NLS-1$
            record -> setProperty(record, "reuseRecent", //$NON-NLS-1$
                "Default true. Take any report, however old, without running."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("installYaxunit", //$NON-NLS-1$
            record -> setProperty(record, "installYaxunit", //$NON-NLS-1$
                "Default true. Download and replace the engine even when installed, from any " //$NON-NLS-1$
                    + "URL, without projectName or credentials."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("yaxunitUnsafeMode", //$NON-NLS-1$
            record -> setProperty(record, "yaxunitUnsafeMode", //$NON-NLS-1$
                "Default false; used in every mode. Flags are never read, lowered or confirmed; " //$NON-NLS-1$
                    + "false leaves them changed."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("yaxunitRepo", //$NON-NLS-1$
            record -> setProperty(record, "yaxunitRepo", "Any repo; no default."))); //$NON-NLS-1$ //$NON-NLS-2$
        MUTATIONS.add(new Mutation("filters", //$NON-NLS-1$
            record -> setTool(record,
                "Unified YAxUnit test runner. help=<topic> returns built-in YAxUnit guidance. " //$NON-NLS-1$
                    + "New launches: Without launchConfigurationName, projectName and applicationId are " //$NON-NLS-1$
                    + "required; installYaxunit also requires projectName. Filters are passed " //$NON-NLS-1$
                    + "through; any name is accepted. " //$NON-NLS-1$
                    + "run_yaxunit_tests (mode=run) and debug_yaxunit_tests (mode=debug) are " //$NON-NLS-1$
                    + "back-compat aliases of this facade; prefer it for new prompts."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("aliases", //$NON-NLS-1$
            record -> setTool(record,
                "Unified YAxUnit test runner. help=<topic> returns built-in YAxUnit guidance. " //$NON-NLS-1$
                    + "New launches: Without launchConfigurationName, projectName and applicationId are " //$NON-NLS-1$
                    + "required; installYaxunit also requires projectName. Filters extensions, " //$NON-NLS-1$
                    + "modules, tests (CSV); the launch configuration has only those " //$NON-NLS-1$
                    + "three, so suites, tags and contexts are refused, not ignored. " //$NON-NLS-1$
                    + "run_yaxunit_tests and debug_yaxunit_tests are separate tools."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("purpose", //$NON-NLS-1$
            record -> setTool(record,
                "A YAxUnit helper. help=<topic> returns built-in YAxUnit guidance. New launches: Without " //$NON-NLS-1$
                    + "launchConfigurationName, projectName and applicationId are required; " //$NON-NLS-1$
                    + "installYaxunit also requires projectName. Filters extensions, " //$NON-NLS-1$
                    + "modules, tests (CSV); the launch configuration has only those three, so " //$NON-NLS-1$
                    + "suites, tags and contexts are refused, not ignored. run_yaxunit_tests " //$NON-NLS-1$
                    + "(mode=run) and debug_yaxunit_tests (mode=debug) are back-compat aliases of " //$NON-NLS-1$
                    + "this facade; prefer it for new prompts."))); //$NON-NLS-1$
        MUTATIONS.add(new Mutation("selection", //$NON-NLS-1$
            record -> setTool(record,
                "Unified YAxUnit test runner. help=<topic> returns built-in YAxUnit guidance. " //$NON-NLS-1$
                    + "New launches: projectName and applicationId are optional without launchConfigurationName; " //$NON-NLS-1$
                    + "installYaxunit needs no projectName. " //$NON-NLS-1$
                    + "Filters extensions, modules, tests (CSV); the launch configuration has " //$NON-NLS-1$
                    + "only those three, so suites, tags and contexts are refused, not ignored. " //$NON-NLS-1$
                    + "run_yaxunit_tests (mode=run) and debug_yaxunit_tests (mode=debug) are " //$NON-NLS-1$
                    + "back-compat aliases of this facade; prefer it for new prompts."))); //$NON-NLS-1$

        SCHEMA_CORRUPTIONS.add(record -> properties(record).remove("runKey")); //$NON-NLS-1$
        SCHEMA_CORRUPTIONS.add(record -> properties(record).getAsJsonObject("mode") //$NON-NLS-1$
            .addProperty("type", "boolean")); //$NON-NLS-1$ //$NON-NLS-2$
        SCHEMA_CORRUPTIONS.add(TheYaxunitCatalogueRecordKeepsItsContractTest::reorderProperties);
        SCHEMA_CORRUPTIONS.add(record -> record.getAsJsonObject("inputSchema") //$NON-NLS-1$
            .getAsJsonArray("required").add("mode")); //$NON-NLS-1$ //$NON-NLS-2$
        SCHEMA_CORRUPTIONS.add(record -> properties(record).add("dryRun", new JsonObject())); //$NON-NLS-1$
        SCHEMA_CORRUPTIONS.add(record -> record.add("annotations", new JsonObject())); //$NON-NLS-1$
    }

    private LiveServer server;

    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    private String unlistedBefore;

    private boolean presetWasDefault;

    private boolean disabledWasDefault;

    private boolean unlistedWasDefault;

    /**
     * Saves catalogue preference values and default states, then selects Canonical.
     */
    @Before
    public void isolateCataloguePreferences()
    {
        store = ru.aiedt.mcp.server.Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
        presetWasDefault = store.isDefault(PrefKeys.PREF_TOOL_PRESET);
        disabledWasDefault = store.isDefault(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedWasDefault = store.isDefault(PrefKeys.PREF_UNLISTED_TOOLS);
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
    }

    /**
     * Stops the isolated server and restores all three saved preferences.
     */
    @After
    public void restoreCataloguePreferencesAndStopServer()
    {
        if (server != null)
        {
            server.close();
            server = null;
        }
        restore(PrefKeys.PREF_TOOL_PRESET, presetBefore, presetWasDefault);
        restore(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore, disabledWasDefault);
        restore(PrefKeys.PREF_UNLISTED_TOOLS, unlistedBefore, unlistedWasDefault);
    }

    /**
     * The immutable shrink test. It compiles against the baseline unchanged and, on the baseline
     * record of 3530 bytes, fails only here with the message "3530 not less than 3530".
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void theRecordWeighsLessThanTheRecorded3530Bytes() throws IOException
    {
        int bytes = record().toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes + " not less than " + BEFORE_BYTES, bytes < BEFORE_BYTES);
    }

    /**
     * The schema shape, types, property order, required list and annotations are frozen as one
     * literal with every description removed. A renamed, retyped, added, reordered or required
     * property cannot pass this, and no annotation member may appear.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void theNonDescriptionRecordIsFrozen() throws IOException
    {
        JsonObject record = record();
        assertTrue("the tool publishes no description", record.has("description")); //$NON-NLS-1$
        assertFalse("yaxunit_tests is a writer and an open-world tool, so no annotations member " //$NON-NLS-1$
            + "may be published", record.has("annotations")); //$NON-NLS-1$
        assertEquals("the non-description record, schema, types, order or required list changed", //$NON-NLS-1$
            NON_DESCRIPTION_RECORD, withoutDescriptions(record).toString());
    }

    /**
     * Every caller-visible condition is published in the record itself, one row per schema
     * property plus the tool-level filter, alias and purpose rows.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void everyInputConditionIsStillPublished() throws IOException
    {
        JsonObject record = record();
        assertEquals("the condition matrix must cover every schema property in order", //$NON-NLS-1$
            PROPERTY_ORDER, new ArrayList<>(SCHEMA_CONDITIONS.keySet()));
        for (Map.Entry<String, List<Condition>> row : SCHEMA_CONDITIONS.entrySet())
        {
            assertFalse("no condition is recorded for " + row.getKey(), row.getValue().isEmpty()); //$NON-NLS-1$
        }
        assertFalse("no tool-level condition is recorded", TOOL_CONDITIONS.isEmpty()); //$NON-NLS-1$
        assertEquals("the tool-level condition families changed", //$NON-NLS-1$
            TOOL_FAMILY_ORDER, new ArrayList<>(TOOL_FAMILIES));
        List<String> missing = conditionsMissing(record);
        assertTrue("conditions the contract published and the record no longer carries:\n" //$NON-NLS-1$
            + String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Reversing one family in a copy of the actual record is reported under exactly that family.
     * The families cover every property and every tool-level row, including the four defaults, the
     * run mode applicability and the update, install, reuse and unsafe-mode implications.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void everyCriticalFamilyIsNamedWhenItsConditionIsReversed() throws IOException
    {
        JsonObject actual = record();
        Set<String> originalProblems = affectedFamilies(actual);
        assertTrue("the unmodified record must satisfy every mutation target: " + originalProblems, //$NON-NLS-1$
            originalProblems.isEmpty());
        List<String> bad = new ArrayList<>();
        for (Mutation mutation : MUTATIONS)
        {
            JsonObject mutated = copy(actual);
            String original = mutated.toString();
            mutation.apply.accept(mutated);
            assertFalse("mutation must change its target family: " + mutation.family, //$NON-NLS-1$
                original.equals(mutated.toString()));
            Set<String> families = affectedFamilies(mutated);
            if (!families.equals(Set.of(mutation.family)))
            {
                bad.add(mutation.family + " was reported as " + families); //$NON-NLS-1$
            }
        }
        assertFalse("the negative mutation set is empty", MUTATIONS.isEmpty()); //$NON-NLS-1$
        assertTrue("mutations not reported as exactly their own family:\n" + String.join("\n", bad), //$NON-NLS-1$
            bad.isEmpty());
    }

    /**
     * A schema corruption is reported as the schema family, never as a missing sentence. The
     * mutations drop a property, retype one, reorder the map, require an optional one, add one and
     * add the annotations member the record must not carry.
     *
     * @throws IOException when the server cannot be read
     */
    @Test
    public void everySchemaCorruptionIsNamedAsSchema() throws IOException
    {
        JsonObject actual = record();
        String originalProblem = shapeProblem(actual);
        assertTrue("the unmodified schema must satisfy the frozen shape: " + originalProblem, //$NON-NLS-1$
            originalProblem == null);
        List<String> bad = new ArrayList<>();
        for (Consumer<JsonObject> corruption : SCHEMA_CORRUPTIONS)
        {
            JsonObject mutated = copy(actual);
            String original = mutated.toString();
            corruption.accept(mutated);
            assertFalse("schema corruption must change its target", original.equals(mutated.toString())); //$NON-NLS-1$
            String problem = shapeProblem(mutated);
            if (problem == null || !problem.startsWith("schema:")) //$NON-NLS-1$
            {
                bad.add(problem == null ? "not detected" : problem); //$NON-NLS-1$
            }
        }
        assertFalse("the schema corruption set is empty", SCHEMA_CORRUPTIONS.isEmpty()); //$NON-NLS-1$
        assertTrue("schema corruptions not reported as schema:\n" + String.join("\n", bad), //$NON-NLS-1$
            bad.isEmpty());
    }

    /**
     * Registers required phrases for one schema property in declaration order.
     *
     * @param property schema property and detector family
     * @param phrases required published phrases
     */
    private static void addSchema(String property, String... phrases)
    {
        List<Condition> rows = SCHEMA_CONDITIONS.computeIfAbsent(property,
            ignored -> new ArrayList<>());
        for (String phrase : phrases)
        {
            Condition condition = new Condition(property, property, phrase);
            rows.add(condition);
            ALL_CONDITIONS.add(condition);
        }
    }

    /**
     * Registers required root-description phrases under one detector family.
     *
     * @param family detector family
     * @param phrases required published phrases
     */
    private static void addTool(String family, String... phrases)
    {
        TOOL_FAMILIES.add(family);
        for (String phrase : phrases)
        {
            Condition condition = new Condition(family, null, phrase);
            TOOL_CONDITIONS.add(condition);
            ALL_CONDITIONS.add(condition);
        }
    }

    /**
     * Replaces one existing property description in a copied record.
     *
     * @param record copied catalogue record
     * @param property existing property name
     * @param text replacement description
     */
    private static void setProperty(JsonObject record, String property, String text)
    {
        properties(record).getAsJsonObject(property).addProperty("description", text); //$NON-NLS-1$
    }

    /**
     * Replaces the root description in a copied catalogue record.
     *
     * @param record copied catalogue record
     * @param text replacement description
     */
    private static void setTool(JsonObject record, String text)
    {
        record.addProperty("description", text); //$NON-NLS-1$
    }

    /**
     * Returns the schema properties without copying or modifying the record.
     *
     * @param record catalogue record
     * @return ordered schema property objects
     */
    private static JsonObject properties(JsonObject record)
    {
        return record.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Moves runKey to the first property position in a copied schema.
     *
     * @param record copied catalogue record
     */
    private static void reorderProperties(JsonObject record)
    {
        JsonObject properties = properties(record);
        JsonObject reordered = new JsonObject();
        reordered.add("runKey", properties.remove("runKey")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, JsonElement> member : properties.entrySet())
        {
            reordered.add(member.getKey(), member.getValue());
        }
        record.getAsJsonObject("inputSchema").add("properties", reordered); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Copies a record through JSON serialization without modifying the input.
     *
     * @param record original catalogue record
     * @return independent copied record
     */
    private static JsonObject copy(JsonObject record)
    {
        return JsonParser.parseString(record.toString()).getAsJsonObject();
    }

    /**
     * Reports absent required phrases with their owning family names.
     *
     * @param record catalogue record to inspect
     * @return missing-condition messages in declaration order
     */
    private static List<String> conditionsMissing(JsonObject record)
    {
        List<String> missing = new ArrayList<>();
        for (Condition condition : ALL_CONDITIONS)
        {
            String text = textOf(record, condition.property);
            if (text == null || !text.contains(condition.phrase))
            {
                missing.add(condition.family + ": missing \"" + condition.phrase + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return missing;
    }

    /**
     * Collects condition families and the schema family violated by the record.
     *
     * @param record catalogue record to inspect
     * @return distinct violated family names
     */
    private static Set<String> affectedFamilies(JsonObject record)
    {
        Set<String> families = new LinkedHashSet<>();
        for (String problem : conditionsMissing(record))
        {
            families.add(problem.substring(0, problem.indexOf(':')));
        }
        if (shapeProblem(record) != null)
        {
            families.add("schema"); //$NON-NLS-1$
        }
        return families;
    }

    /**
     * Compares the ordered non-description record with the frozen shape.
     *
     * @param record catalogue record to inspect
     * @return schema-prefixed diagnostic, or null when the shape matches
     */
    private static String shapeProblem(JsonObject record)
    {
        if (record.has("annotations")) //$NON-NLS-1$
        {
            return "schema: an annotations member was published"; //$NON-NLS-1$
        }
        String actual = withoutDescriptions(record).toString();
        if (!NON_DESCRIPTION_RECORD.equals(actual))
        {
            return "schema: expected " + NON_DESCRIPTION_RECORD + " but was " + actual; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Reads the root or one property description without changing the record.
     *
     * @param record catalogue record
     * @param property property name, or null for the root
     * @return description text, or null when absent
     */
    private static String textOf(JsonObject record, String property)
    {
        if (property == null)
        {
            return record.has("description") ? record.get("description").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonObject properties = properties(record);
        if (!properties.has(property) || !properties.get(property).isJsonObject())
        {
            return null;
        }
        JsonElement description = properties.getAsJsonObject(property).get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * Copies JSON recursively, omitting description members and retaining order.
     *
     * @param element original JSON value
     * @return JSON value without description members
     */
    private static JsonElement withoutDescriptions(JsonElement element)
    {
        if (element.isJsonArray())
        {
            com.google.gson.JsonArray copied = new com.google.gson.JsonArray();
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
     * Reads the advertised yaxunit_tests record from the isolated HTTP catalogue.
     *
     * @return actual catalogue record
     * @throws IOException when the catalogue cannot be read
     */
    private JsonObject record() throws IOException
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
        for (JsonElement element : document.getAsJsonObject("result").getAsJsonArray("tools")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject tool = element.getAsJsonObject();
            if (TOOL.equals(tool.get("name").getAsString())) //$NON-NLS-1$
            {
                return tool;
            }
        }
        fail("tools/list does not advertise " + TOOL); //$NON-NLS-1$
        return null;
    }

    /**
     * Restores one preference value or its original default state.
     *
     * @param key preference key
     * @param value saved raw value
     * @param wasDefault whether the original preference used its default
     */
    private void restore(String key, String value, boolean wasDefault)
    {
        if (store == null)
        {
            return;
        }
        if (wasDefault)
        {
            store.setToDefault(key);
        }
        else
        {
            store.setValue(key, value);
        }
    }
}
