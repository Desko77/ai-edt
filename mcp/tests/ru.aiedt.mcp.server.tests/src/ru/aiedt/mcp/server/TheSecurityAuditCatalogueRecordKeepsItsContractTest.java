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
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;

/**
 * The {@code security_audit} {@code tools/list} entry may lose repeated prose and must keep every
 * published caller condition, the ordered non-description shape, and the recorded shrink.
 * <p>
 * The size constant is the UTF-8 length of this entry as {@code JsonObject.toString()} of a
 * LiveServer {@code tools/list} record before the cut: 3481. On that revision this class compiles
 * unchanged and {@link #thePublishedRecordWeighsLessThan3481Utf8Bytes()} fails because 3481 is not
 * less than 3481. After the cut the same method passes. The shape oracle is a literal, not a
 * reconstruction from {@code getDescription} or {@code getInputSchema}.
 * </p>
 */
public class TheSecurityAuditCatalogueRecordKeepsItsContractTest
{
    /** UTF-8 bytes of the LiveServer {@code tools/list} entry before the cut. */
    static final int BEFORE_UTF8_BYTES = 3481;

    private static final String TOOL = "security_audit"; //$NON-NLS-1$

    private static final int OK = 200;

    /**
     * Ordered non-description record: name, inputSchema (types, property order, required),
     * annotations. Descriptions stripped; insertion order kept.
     */
    private static final String SHAPE_ORACLE =
        "{\"name\":\"security_audit\",\"inputSchema\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"operation\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"topic\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"find\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"projectName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"scope\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"moduleFqn\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"methodName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"subsystemName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"mode\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"roleName\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"roleNames\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"objectType\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"apply\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"includeRls\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"checks\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"customPatterns\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"severity_filter\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"format\":{\"type\":\"string\"}" //$NON-NLS-1$
            + "},\"required\":[\"operation\"]},\"annotations\":{" //$NON-NLS-1$
            + "\"openWorldHint\":false}}"; //$NON-NLS-1$

    private static final String FAMILY_DEFAULTS = "DEFAULTS"; //$NON-NLS-1$

    private static final String FAMILY_APPLICABILITY = "OPERATION_APPLICABILITY"; //$NON-NLS-1$

    private static final String FAMILY_SELECTORS = "SELECTOR_DIRECTION"; //$NON-NLS-1$

    private static final String FAMILY_APPLY = "PREVIEW_APPLY"; //$NON-NLS-1$

    private static final String FAMILY_SCHEMA = "SCHEMA_CORRUPTION"; //$NON-NLS-1$

    private static final String FAMILY_SAFETY = "SAFETY"; //$NON-NLS-1$

    private LiveServer server;

    private String presetBefore;

    private String disabledBefore;

    private String unlistedBefore;

    private boolean presetWasDefault;

    private boolean disabledWasDefault;

    private boolean unlistedWasDefault;

    /**
     * Pins the shipped Canonical catalogue surface and remembers the previous tool sets.
     */
    @Before
    public void pinTheShippedCataloguePreset()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
        presetWasDefault = store.isDefault(PrefKeys.PREF_TOOL_PRESET);
        disabledWasDefault = store.isDefault(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedWasDefault = store.isDefault(PrefKeys.PREF_UNLISTED_TOOLS);
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
    }

    /**
     * Stops the LiveServer and puts the catalogue preferences back.
     */
    @After
    public void restoreCataloguePreferencesAndStopTheServer()
    {
        if (server != null)
        {
            server.close();
            server = null;
        }
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        restorePreference(store, PrefKeys.PREF_DISABLED_TOOLS, disabledBefore, disabledWasDefault);
        restorePreference(store, PrefKeys.PREF_UNLISTED_TOOLS, unlistedBefore, unlistedWasDefault);
        restorePreference(store, PrefKeys.PREF_TOOL_PRESET, presetBefore, presetWasDefault);
    }

    /**
     * Restores a saved preference value and whether it used its default.
     *
     * @param store preference store to restore
     * @param key preference key
     * @param value saved value
     * @param wasDefault whether the saved preference used its default
     */
    private static void restorePreference(IPreferenceStore store, String key, String value, boolean wasDefault)
    {
        if (wasDefault)
        {
            store.setToDefault(key);
        }
        else
        {
            store.setValue(key, value);
        }
    }

    /**
     * The published entry must weigh strictly less than the recorded 3481 UTF-8 bytes.
     * <p>
     * On the revision before the cut the method compiles unchanged and fails because 3481 is not
     * less than 3481.
     * </p>
     *
     * @throws IOException when the LiveServer {@code tools/list} call cannot be completed
     */
    @Test
    public void thePublishedRecordWeighsLessThan3481Utf8Bytes() throws IOException
    {
        JsonObject record = publishedRecord();
        int now = record.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue("security_audit weighs " + now + " bytes, not less than " + BEFORE_UTF8_BYTES, //$NON-NLS-1$ //$NON-NLS-2$
            now < BEFORE_UTF8_BYTES);
    }

    /**
     * The ordered non-description shape, required list and annotations match the literal oracle.
     *
     * @throws IOException when the LiveServer {@code tools/list} call cannot be completed
     */
    @Test
    public void theNonDescriptionShapeMatchesTheLiteralOracle() throws IOException
    {
        JsonObject record = publishedRecord();
        assertEquals("the non-description record changed a name, type, order, required flag or annotation", //$NON-NLS-1$
            SHAPE_ORACLE, withoutDescriptions(record).toString());
    }

    /**
     * Every input-condition row still holds on the published record, bound to its property.
     *
     * @throws IOException when the LiveServer {@code tools/list} call cannot be completed
     */
    @Test
    public void everyInputConditionRowIsStillPublished() throws IOException
    {
        JsonObject record = publishedRecord();
        List<String> missing = detect(record);
        assertTrue("input-condition matrix is empty", conditions().length > 0); //$NON-NLS-1$
        assertEquals(18, propertiesOf(record).entrySet().size());
        assertTrue(String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A copy of the real record, mutated in each critical family, is rejected and named by family.
     *
     * @throws IOException when the LiveServer {@code tools/list} call cannot be completed
     */
    @Test
    public void aMutationOfEachCriticalFamilyIsDetectedByName() throws IOException
    {
        JsonObject original = publishedRecord();
        List<String> originalIssues = detect(original);
        assertTrue("the unmodified record must satisfy the published contract: " + originalIssues, //$NON-NLS-1$
            originalIssues.isEmpty());
        List<String> missed = new ArrayList<>();
        for (Mutation mutation : mutations(original))
        {
            assertTrue(mutation.family + " target missing: " + mutation.target, //$NON-NLS-1$
                mutation.targetPresent);
            JsonObject copy = JsonParser.parseString(original.toString()).getAsJsonObject();
            mutation.apply(copy);
            assertTrue(mutation.family + " mutation must change the copied record", //$NON-NLS-1$
                !original.toString().equals(copy.toString()));
            List<String> issues = detect(copy);
            if (issues.isEmpty())
            {
                missed.add(mutation.family + " accepted the mutated record (" + mutation.target + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            boolean named = false;
            for (String issue : issues)
            {
                if (issue.startsWith(mutation.family))
                {
                    named = true;
                    break;
                }
            }
            if (!named)
            {
                missed.add(mutation.family + " detector did not name the family in " //$NON-NLS-1$
                    + String.join("; ", issues)); //$NON-NLS-1$
            }
        }
        assertTrue(String.join("\n", missed), missed.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The {@code security_audit} entry from a LiveServer {@code tools/list} HTTP call.
     *
     * @return the parsed tool object
     * @throws IOException when the server cannot be started or the call fails
     */
    private JsonObject publishedRecord() throws IOException
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
        assertEquals(answer.body, OK, answer.code);
        JsonObject document = JsonParser.parseString(answer.body).getAsJsonObject();
        JsonArray tools = document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        for (JsonElement element : tools)
        {
            JsonObject candidate = element.getAsJsonObject();
            if (TOOL.equals(candidate.get("name").getAsString())) //$NON-NLS-1$
            {
                return candidate;
            }
        }
        throw new IOException("tools/list does not advertise " + TOOL); //$NON-NLS-1$
    }

    /**
     * Findings against the published contract, each line starting with its family name.
     *
     * @param record a {@code tools/list} tool object, possibly a mutated copy
     * @return findings; empty when the record still matches the contract
     */
    private static List<String> detect(JsonObject record)
    {
        List<String> issues = new ArrayList<>();
        if (!SHAPE_ORACLE.equals(withoutDescriptions(record).toString()))
        {
            issues.add(FAMILY_SCHEMA + ": non-description shape, required list or annotations changed"); //$NON-NLS-1$
        }
        for (Condition condition : conditions())
        {
            String text = textOf(record, condition.property);
            if (text == null || !text.contains(condition.needle))
            {
                String where = condition.property == null ? "tool" : condition.property; //$NON-NLS-1$
                issues.add(condition.family + ": " + condition.id + " lost \"" + condition.needle //$NON-NLS-1$ //$NON-NLS-2$
                    + "\" at " + where); //$NON-NLS-1$
            }
        }
        return issues;
    }

    /**
     * The 18 properties plus the tool-description safety and alias rows.
     *
     * @return the matrix, never empty
     */
    private static Condition[] conditions()
    {
        return new Condition[] {
            new Condition("tool-ops", FAMILY_APPLICABILITY, null, //$NON-NLS-1$
                "Operations: audit_role_rights, find_rls_violations, sensitive_data_scan, help"), //$NON-NLS-1$
            new Condition("tool-alias", FAMILY_APPLICABILITY, null, //$NON-NLS-1$
                "snake_case canonical"), //$NON-NLS-1$
            new Condition("tool-camel", FAMILY_APPLICABILITY, null, //$NON-NLS-1$
                "camelCase like auditRoleRights"), //$NON-NLS-1$
            new Condition("tool-read-except-apply", FAMILY_SAFETY, null, //$NON-NLS-1$
                "Every operation reads only, except audit_role_rights with mode=orphans apply=true"), //$NON-NLS-1$
            new Condition("tool-removes-file", FAMILY_SAFETY, null, //$NON-NLS-1$
                "removes rights from the role's file"), //$NON-NLS-1$
            new Condition("tool-preset-refusal", FAMILY_SAFETY, null, //$NON-NLS-1$
                "refused under a preset without write rights"), //$NON-NLS-1$
            new Condition("tool-standalone", FAMILY_APPLICABILITY, null, //$NON-NLS-1$
                "Standalone tools remain for back-compat"), //$NON-NLS-1$
            new Condition("tool-help-parameters", FAMILY_APPLICABILITY, null, //$NON-NLS-1$
                "For operation parameters: operation=help topic=<operation>"), //$NON-NLS-1$
            new Condition("operation-list", FAMILY_APPLICABILITY, "operation", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights / find_rls_violations / sensitive_data_scan / help"), //$NON-NLS-1$
            new Condition("operation-alias", FAMILY_APPLICABILITY, "operation", //$NON-NLS-1$ //$NON-NLS-2$
                "camelCase like auditRoleRights is also accepted"), //$NON-NLS-1$
            new Condition("operation-help", FAMILY_APPLICABILITY, "operation", //$NON-NLS-1$ //$NON-NLS-2$
                "Pass operation=help without other params for the operation catalog"), //$NON-NLS-1$
            new Condition("topic-help", FAMILY_APPLICABILITY, "topic", //$NON-NLS-1$ //$NON-NLS-2$
                "Help topic when operation=help"), //$NON-NLS-1$
            new Condition("topic-list", FAMILY_APPLICABILITY, "topic", //$NON-NLS-1$ //$NON-NLS-2$
                "lists all operations with one-line summaries"), //$NON-NLS-1$
            new Condition("find-help", FAMILY_APPLICABILITY, "find", //$NON-NLS-1$ //$NON-NLS-2$
                "search this help for a case-insensitive substring"), //$NON-NLS-1$
            new Condition("project-required", FAMILY_APPLICABILITY, "projectName", //$NON-NLS-1$ //$NON-NLS-2$
                "Required for all three operations"), //$NON-NLS-1$
            new Condition("scope-rls", FAMILY_SELECTORS, "scope", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations: project / module / method"), //$NON-NLS-1$
            new Condition("scope-sensitive", FAMILY_SELECTORS, "scope", //$NON-NLS-1$ //$NON-NLS-2$
                "sensitive_data_scan: project / subsystem / module"), //$NON-NLS-1$
            new Condition("module-ops", FAMILY_APPLICABILITY, "moduleFqn", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations and sensitive_data_scan: module FQN"), //$NON-NLS-1$
            new Condition("method-rls", FAMILY_APPLICABILITY, "methodName", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations: method inside moduleFqn"), //$NON-NLS-1$
            new Condition("subsystem-sensitive", FAMILY_APPLICABILITY, "subsystemName", //$NON-NLS-1$ //$NON-NLS-2$
                "sensitive_data_scan: subsystem name"), //$NON-NLS-1$
            new Condition("mode-values", FAMILY_APPLICABILITY, "mode", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: rights / missing / conflicts / impact / orphans"), //$NON-NLS-1$
            new Condition("mode-default", FAMILY_DEFAULTS, "mode", //$NON-NLS-1$ //$NON-NLS-2$
                "(default rights)"), //$NON-NLS-1$
            new Condition("roleName-required-modes", FAMILY_APPLICABILITY, "roleName", //$NON-NLS-1$ //$NON-NLS-2$
                "required for mode=rights / mode=missing / mode=orphans"), //$NON-NLS-1$
            new Condition("roleName-rls-default", FAMILY_DEFAULTS, "roleName", //$NON-NLS-1$ //$NON-NLS-2$
                "(default: any RLS)"), //$NON-NLS-1$
            new Condition("roleNames-conflicts", FAMILY_APPLICABILITY, "roleNames", //$NON-NLS-1$ //$NON-NLS-2$
                "mode=conflicts / mode=impact"), //$NON-NLS-1$
            new Condition("objectType-values", FAMILY_APPLICABILITY, "objectType", //$NON-NLS-1$ //$NON-NLS-2$
                "any metadata type"), //$NON-NLS-1$
            new Condition("objectType-default", FAMILY_DEFAULTS, "objectType", //$NON-NLS-1$ //$NON-NLS-2$
                "(default all)"), //$NON-NLS-1$
            new Condition("objectType-register", FAMILY_APPLICABILITY, "objectType", //$NON-NLS-1$ //$NON-NLS-2$
                "Register selects every register kind"), //$NON-NLS-1$
            new Condition("apply-removes", FAMILY_APPLY, "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "removes the entries this call finds"), //$NON-NLS-1$
            new Condition("apply-removed-list", FAMILY_APPLY, "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "lists them in `removed`"), //$NON-NLS-1$
            new Condition("apply-preview", FAMILY_APPLY, "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "call without apply first to read the list"), //$NON-NLS-1$
            new Condition("includeRls-default", FAMILY_DEFAULTS, "includeRls", //$NON-NLS-1$ //$NON-NLS-2$
                "(default false)"), //$NON-NLS-1$
            new Condition("checks-default", FAMILY_DEFAULTS, "checks", //$NON-NLS-1$ //$NON-NLS-2$
                "(default all)"), //$NON-NLS-1$
            new Condition("checks-comma-list", FAMILY_APPLICABILITY, "checks", //$NON-NLS-1$ //$NON-NLS-2$
                "comma-separated"), //$NON-NLS-1$
            new Condition("checks-names", FAMILY_APPLICABILITY, "checks", //$NON-NLS-1$ //$NON-NLS-2$
                "ATTRIBUTE_NAME, HARDCODED_SECRET, COMMENT_LEAK, LOG_SENSITIVE"), //$NON-NLS-1$
            new Condition("custom-attribute", FAMILY_APPLICABILITY, "customPatterns", //$NON-NLS-1$ //$NON-NLS-2$
                "ATTRIBUTE_NAME"), //$NON-NLS-1$
            new Condition("custom-comma-list", FAMILY_APPLICABILITY, "customPatterns", //$NON-NLS-1$ //$NON-NLS-2$
                "comma-separated extra regex"), //$NON-NLS-1$
            new Condition("severity-values", FAMILY_APPLICABILITY, "severity_filter", //$NON-NLS-1$ //$NON-NLS-2$
                "info / warning / error / all"), //$NON-NLS-1$
            new Condition("severity-default", FAMILY_DEFAULTS, "severity_filter", //$NON-NLS-1$ //$NON-NLS-2$
                "(default warning)"), //$NON-NLS-1$
            new Condition("format-default", FAMILY_DEFAULTS, "format", //$NON-NLS-1$ //$NON-NLS-2$
                "json (default)"), //$NON-NLS-1$
            new Condition("format-rights-only", FAMILY_APPLICABILITY, "format", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: mode=rights only"), //$NON-NLS-1$
            new Condition("full-operation", FAMILY_APPLICABILITY, "operation", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights / find_rls_violations / sensitive_data_scan / help (snake_case " //$NON-NLS-1$
                + "canonical; camelCase like auditRoleRights is also accepted). Pass operation=help without " //$NON-NLS-1$
                + "other params for the operation catalog."), //$NON-NLS-1$
            new Condition("full-topic", FAMILY_APPLICABILITY, "topic", //$NON-NLS-1$ //$NON-NLS-2$
                "Help topic when operation=help. Without topic - lists all operations with one-line " //$NON-NLS-1$
                + "summaries."), //$NON-NLS-1$
            new Condition("full-find", FAMILY_APPLICABILITY, "find", //$NON-NLS-1$ //$NON-NLS-2$
                "With help: search this help for a case-insensitive substring - all the words must appear " //$NON-NLS-1$
                + "inside one chunk, and with topic only that topic is searched."), //$NON-NLS-1$
            new Condition("full-projectName", FAMILY_APPLICABILITY, "projectName", //$NON-NLS-1$ //$NON-NLS-2$
                "EDT project name. Required for all three operations."), //$NON-NLS-1$
            new Condition("full-scope", FAMILY_SELECTORS, "scope", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations: project / module / method; sensitive_data_scan: project / subsystem /" //$NON-NLS-1$
                + " module."), //$NON-NLS-1$
            new Condition("full-moduleFqn", FAMILY_APPLICABILITY, "moduleFqn", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations and sensitive_data_scan: module FQN."), //$NON-NLS-1$
            new Condition("full-methodName", FAMILY_APPLICABILITY, "methodName", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations: method inside moduleFqn."), //$NON-NLS-1$
            new Condition("full-subsystemName", FAMILY_APPLICABILITY, "subsystemName", //$NON-NLS-1$ //$NON-NLS-2$
                "sensitive_data_scan: subsystem name."), //$NON-NLS-1$
            new Condition("full-mode", FAMILY_APPLICABILITY, "mode", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: rights / missing / conflicts / impact / orphans (default rights)."), //$NON-NLS-1$
            new Condition("full-roleName", FAMILY_APPLICABILITY, "roleName", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: required for mode=rights / mode=missing / mode=orphans. " //$NON-NLS-1$
                + "find_rls_violations: optional RLS of this role (default: any RLS)."), //$NON-NLS-1$
            new Condition("full-roleNames", FAMILY_APPLICABILITY, "roleNames", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: comma-separated names for mode=conflicts / mode=impact."), //$NON-NLS-1$
            new Condition("full-objectType", FAMILY_SELECTORS, "objectType", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: Catalog | Document | Register | Report | any metadata type | all " //$NON-NLS-1$
                + "(default all). Register selects every register kind."), //$NON-NLS-1$
            new Condition("full-apply", FAMILY_APPLY, "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights orphans mode: removes the entries this call finds and lists them in " //$NON-NLS-1$
                + "`removed`; call without apply first to read the list."), //$NON-NLS-1$
            new Condition("full-includeRls", FAMILY_DEFAULTS, "includeRls", //$NON-NLS-1$ //$NON-NLS-2$
                "audit_role_rights: hasRls flag in the output (default false)."), //$NON-NLS-1$
            new Condition("full-checks", FAMILY_APPLICABILITY, "checks", //$NON-NLS-1$ //$NON-NLS-2$
                "sensitive_data_scan (default all), comma-separated: ATTRIBUTE_NAME, HARDCODED_SECRET, COMMENT_LEAK, " //$NON-NLS-1$
                + "LOG_SENSITIVE."), //$NON-NLS-1$
            new Condition("full-customPatterns", FAMILY_APPLICABILITY, "customPatterns", //$NON-NLS-1$ //$NON-NLS-2$
                "sensitive_data_scan: comma-separated extra regex for the ATTRIBUTE_NAME check."), //$NON-NLS-1$
            new Condition("full-severity_filter", FAMILY_DEFAULTS, "severity_filter", //$NON-NLS-1$ //$NON-NLS-2$
                "find_rls_violations and sensitive_data_scan: info / warning / error / all (default " //$NON-NLS-1$
                + "warning)."), //$NON-NLS-1$
            new Condition("full-format", FAMILY_DEFAULTS, "format", //$NON-NLS-1$ //$NON-NLS-2$
                "json (default) or markdown; audit_role_rights: mode=rights only."), //$NON-NLS-1$
            new Condition("full-root", FAMILY_SAFETY, null, //$NON-NLS-1$
                "Security and access audit. Operations: audit_role_rights, find_rls_violations, " //$NON-NLS-1$
                + "sensitive_data_scan, help. Pass operation=<name> (snake_case canonical; camelCase like " //$NON-NLS-1$
                + "auditRoleRights is also accepted). Every operation reads only, except audit_role_rights " //$NON-NLS-1$
                + "with mode=orphans apply=true, which removes rights from the role's file and is refused " //$NON-NLS-1$
                + "under a preset without write rights. Standalone tools remain for back-compat. " //$NON-NLS-1$
                + "For operation parameters: operation=help topic=<operation>.") //$NON-NLS-1$
        };
    }

    /**
     * One mutation per critical family, each targeting a field the original record actually has.
     *
     * @param original the published record
     * @return the mutations to try on copies
     */
    private static Mutation[] mutations(JsonObject original)
    {
        JsonObject properties = propertiesOf(original);
        return new Mutation[] {
            new Mutation(FAMILY_DEFAULTS, "mode.(default rights)", //$NON-NLS-1$
                textOf(original, "mode") != null //$NON-NLS-1$
                    && textOf(original, "mode").contains("(default rights)"), //$NON-NLS-1$ //$NON-NLS-2$
                copy -> replaceDescription(copy, "mode", "(default rights)", "(default orphans)")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Mutation(FAMILY_APPLICABILITY, "roleName vs roleNames", //$NON-NLS-1$
                textOf(original, "roleName") != null && textOf(original, "roleNames") != null, //$NON-NLS-1$ //$NON-NLS-2$
                copy -> setDescription(copy, "roleName", textOf(original, "roleNames"))), //$NON-NLS-1$ //$NON-NLS-2$
            new Mutation(FAMILY_APPLICABILITY, "customPatterns comma list", //$NON-NLS-1$
                textOf(original, "customPatterns") != null //$NON-NLS-1$
                    && textOf(original, "customPatterns").contains("comma-separated extra regex"), //$NON-NLS-1$ //$NON-NLS-2$
                copy -> replaceDescription(copy, "customPatterns", //$NON-NLS-1$
                    "comma-separated extra regex", "extra regex")), //$NON-NLS-1$ //$NON-NLS-2$
            new Mutation(FAMILY_APPLICABILITY, "checks comma list", //$NON-NLS-1$
                textOf(original, "checks") != null //$NON-NLS-1$
                    && textOf(original, "checks").contains(", comma-separated"), //$NON-NLS-1$ //$NON-NLS-2$
                copy -> replaceDescription(copy, "checks", ", comma-separated", "")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Mutation(FAMILY_APPLICABILITY, "operation help topic", //$NON-NLS-1$
                textOf(original, null) != null
                    && textOf(original, null).contains(" topic=<operation>"), //$NON-NLS-1$
                copy -> replaceToolDescription(copy, " topic=<operation>", "")), //$NON-NLS-1$ //$NON-NLS-2$
            new Mutation(FAMILY_SELECTORS, "scope selector lists", //$NON-NLS-1$
                textOf(original, "scope") != null //$NON-NLS-1$
                    && textOf(original, "scope").contains( //$NON-NLS-1$
                        "find_rls_violations: project / module / method"), //$NON-NLS-1$
                copy -> replaceDescription(copy, "scope", //$NON-NLS-1$
                    "find_rls_violations: project / module / method", //$NON-NLS-1$
                    "find_rls_violations: project / subsystem / module")), //$NON-NLS-1$
            new Mutation(FAMILY_APPLY, "apply preview", //$NON-NLS-1$
                textOf(original, "apply") != null //$NON-NLS-1$
                    && textOf(original, "apply").contains("call without apply first to read the list"), //$NON-NLS-1$ //$NON-NLS-2$
                copy -> replaceDescription(copy, "apply", //$NON-NLS-1$
                    "call without apply first to read the list", //$NON-NLS-1$
                    "call without apply first to remove the entries")), //$NON-NLS-1$
            new Mutation(FAMILY_SAFETY, "tool read-except-apply", //$NON-NLS-1$
                textOf(original, null) != null && textOf(original, null).contains(
                    "Every operation reads only, except audit_role_rights with mode=orphans apply=true"), //$NON-NLS-1$
                copy -> replaceToolDescription(copy,
                    "Every operation reads only, except audit_role_rights with mode=orphans apply=true", //$NON-NLS-1$
                    "Every operation writes, except audit_role_rights with mode=orphans apply=true")), //$NON-NLS-1$
            new Mutation(FAMILY_SCHEMA, "apply type", //$NON-NLS-1$
                properties.has("apply") && properties.getAsJsonObject("apply").has("type"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                copy -> propertiesOf(copy).getAsJsonObject("apply").addProperty("type", "string")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Mutation(FAMILY_SCHEMA, "required operation", //$NON-NLS-1$
                original.getAsJsonObject("inputSchema").has("required"), //$NON-NLS-1$ //$NON-NLS-2$
                copy -> copy.getAsJsonObject("inputSchema").remove("required")), //$NON-NLS-1$ //$NON-NLS-2$
            new Mutation(FAMILY_SCHEMA, "drop format", //$NON-NLS-1$
                properties.has("format"), //$NON-NLS-1$
                copy -> propertiesOf(copy).remove("format")), //$NON-NLS-1$
            new Mutation(FAMILY_SCHEMA, "annotation openWorldHint", //$NON-NLS-1$
                original.has("annotations") //$NON-NLS-1$
                    && original.getAsJsonObject("annotations").has("openWorldHint"), //$NON-NLS-1$ //$NON-NLS-2$
                copy -> copy.getAsJsonObject("annotations").addProperty("openWorldHint", true)) //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    /**
     * The schema properties object of a tool entry.
     *
     * @param record the tool entry
     * @return the properties map
     */
    private static JsonObject propertiesOf(JsonObject record)
    {
        return record.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The published description of the tool itself or of one property.
     *
     * @param record the tool entry
     * @param property the property name, or {@code null} for the tool description
     * @return the description, or {@code null} when it is absent
     */
    private static String textOf(JsonObject record, String property)
    {
        if (property == null)
        {
            return record.has("description") ? record.get("description").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonObject properties = propertiesOf(record);
        if (!properties.has(property) || !properties.get(property).isJsonObject())
        {
            return null;
        }
        JsonElement description = properties.getAsJsonObject(property).get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * Replaces a phrase inside one property description.
     *
     * @param record the copy
     * @param property the property
     * @param from the phrase to replace
     * @param to the replacement
     */
    private static void replaceDescription(JsonObject record, String property, String from, String to)
    {
        String text = textOf(record, property);
        setDescription(record, property, text.replace(from, to));
    }

    /**
     * Writes a property description onto a copy.
     *
     * @param record the copy
     * @param property the property
     * @param text the new description
     */
    private static void setDescription(JsonObject record, String property, String text)
    {
        propertiesOf(record).getAsJsonObject(property).addProperty("description", text); //$NON-NLS-1$
    }

    /**
     * Replaces a phrase in the tool description.
     *
     * @param record the copy
     * @param from the phrase to replace
     * @param to the replacement
     */
    private static void replaceToolDescription(JsonObject record, String from, String to)
    {
        record.addProperty("description", textOf(record, null).replace(from, to)); //$NON-NLS-1$
    }

    /**
     * The same value with every schema description removed, insertion order kept.
     *
     * @param element any part of a tool entry
     * @return a copy without prose
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
        for (Map.Entry<String, JsonElement> member : source.entrySet())
        {
            if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
            {
                continue;
            }
            JsonElement value = member.getValue();
            if ("properties".equals(member.getKey()) && value.isJsonObject()) //$NON-NLS-1$
            {
                JsonObject named = new JsonObject();
                for (Map.Entry<String, JsonElement> property : value.getAsJsonObject().entrySet())
                {
                    named.add(property.getKey(), withoutDescriptions(property.getValue()));
                }
                copied.add(member.getKey(), named);
            }
            else
            {
                copied.add(member.getKey(), withoutDescriptions(value));
            }
        }
        return copied;
    }

    /** One published condition bound to one location. */
    private static final class Condition
    {
        final String id;

        final String family;

        final String property;

        final String needle;

        /**
         * Records one required phrase and its owning location and detector family.
         *
         * @param id condition identifier
         * @param family detector family
         * @param property schema property, or null for the root description
         * @param needle required published phrase
         */
        Condition(String id, String family, String property, String needle)
        {
            this.id = id;
            this.family = family;
            this.property = property;
            this.needle = needle;
        }
    }

    /** One family mutation of a copy of the real record. */
    private static final class Mutation
    {
        final String family;

        final String target;

        final boolean targetPresent;

        private final Mutator mutator;

        /**
         * Records a mutation and the precondition showing that its target exists.
         *
         * @param family expected detector family
         * @param target description of the mutated condition
         * @param targetPresent whether the original record contains the target
         * @param mutator operation applied only to a copied record
         */
        Mutation(String family, String target, boolean targetPresent, Mutator mutator)
        {
            this.family = family;
            this.target = target;
            this.targetPresent = targetPresent;
            this.mutator = mutator;
        }

        /**
         * Applies the mutation to a copy.
         *
         * @param copy the copy of the published record
         */
        void apply(JsonObject copy)
        {
            mutator.mutate(copy);
        }
    }

    /** Applies one mutation to a copied record. */
    private interface Mutator
    {
        /**
         * Changes the copy.
         *
         * @param copy the copy
         */
        void mutate(JsonObject copy);
    }
}
