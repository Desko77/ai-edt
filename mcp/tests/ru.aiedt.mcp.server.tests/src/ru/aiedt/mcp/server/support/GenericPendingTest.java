/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Guards the generic-Pending membership and key building. The safety-critical
 * checks: the flow is read-only (no mutator or file-writer is listed), and every
 * listed tool is also heavy (so the B2 limiter already bounds it). The key builder
 * drops the control args and is order-stable.
 */
public class GenericPendingTest
{
    /** Mutators / side-effecting tools that must NEVER be cache-replayed or coalesced. */
    private static final String[] FORBIDDEN = {
        "install_extension", //$NON-NLS-1$
        "uninstall_extension", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "import_configuration_from_xml", //$NON-NLS-1$
        "export_configuration_to_xml", //$NON-NLS-1$
        "export_extension", //$NON-NLS-1$
        "revalidate_objects", //$NON-NLS-1$
        "clean_project", //$NON-NLS-1$
        "vanessa", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "edit_metadata", //$NON-NLS-1$
        "write_module_source"}; //$NON-NLS-1$

    @Test
    public void readOnlyAnalysisToolsAreWrapped()
    {
        assertTrue(GenericPending.applies("audit_role_rights")); //$NON-NLS-1$
        assertTrue(GenericPending.applies("dependency_graph")); //$NON-NLS-1$
        assertTrue(GenericPending.applies("project_metrics")); //$NON-NLS-1$
        assertTrue(GenericPending.applies("find_rls_violations")); //$NON-NLS-1$
        assertTrue(GenericPending.applies("generate_health_snapshot")); //$NON-NLS-1$
    }

    @Test
    public void mutatorsAndSideEffectingToolsAreNeverWrapped()
    {
        for (String name : FORBIDDEN)
        {
            assertFalse(name + " must not be generic-Pending (replay/coalesce unsafe)", //$NON-NLS-1$
                GenericPending.applies(name));
        }
    }

    @Test
    public void everyWrappedToolIsAlsoHeavy()
    {
        // The generic-Pending list must be a subset of the heavy set, so the B2
        // limiter already bounds concurrency for each of them.
        for (String name : new String[] {
            "audit_role_rights", "code_review", "compare_configurations", "dcs_search", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dependency_graph", "detect_query_anti_patterns", "find_rls_violations", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "generate_health_snapshot", "list_interceptors", //$NON-NLS-1$ //$NON-NLS-2$
            "project_metrics", "semantic_metadata_search", "sensitive_data_scan", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "validate_for_export", "list_extension"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(name + " should be heavy", HeavyTools.isHeavy(name)); //$NON-NLS-1$
            assertTrue(name + " should be generic-Pending", GenericPending.applies(name)); //$NON-NLS-1$
        }
    }

    @Test
    public void impactAnalysisExcludedForNestedPending()
    {
        // impact_analysis delegates to find_references, which runs its own Pending
        // flow; wrapping it generically would nest two Pending layers. It stays
        // inline under the B2 limiter instead.
        assertFalse(GenericPending.applies("impact_analysis")); //$NON-NLS-1$
    }

    @Test
    public void nullAndUnknownDoNotApply()
    {
        assertFalse(GenericPending.applies(null));
        assertFalse(GenericPending.applies("")); //$NON-NLS-1$
        assertFalse(GenericPending.applies("get_edt_version")); //$NON-NLS-1$
    }

    @Test
    public void canonicalParamsDropsRunKeyKeepsTimeoutAndIsOrderStable()
    {
        Map<String, String> a = new HashMap<>();
        a.put("projectName", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        a.put("depth", "full"); //$NON-NLS-1$ //$NON-NLS-2$
        a.put("runKey", "abc123"); //$NON-NLS-1$ //$NON-NLS-2$
        a.put("timeoutSeconds", "45"); //$NON-NLS-1$ //$NON-NLS-2$

        String key = GenericPending.canonicalParams(a);
        assertFalse("runKey excluded (resume handle, not identity)", //$NON-NLS-1$
            key.contains("runKey")); //$NON-NLS-1$
        assertTrue("timeoutSeconds kept as work identity", //$NON-NLS-1$
            key.contains("timeoutSeconds")); //$NON-NLS-1$
        assertTrue("keeps identity args", key.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("keeps identity args", key.contains("depth")); //$NON-NLS-1$ //$NON-NLS-2$
        // Sorted by key: depth before projectName before timeoutSeconds.
        assertTrue("sorted key order", //$NON-NLS-1$
            key.indexOf("depth") < key.indexOf("projectName") //$NON-NLS-1$ //$NON-NLS-2$
                && key.indexOf("projectName") < key.indexOf("timeoutSeconds")); //$NON-NLS-1$ //$NON-NLS-2$

        // A different insertion order and a different runKey -> same key.
        Map<String, String> b = new HashMap<>();
        b.put("timeoutSeconds", "45"); //$NON-NLS-1$ //$NON-NLS-2$
        b.put("projectName", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        b.put("runKey", "zzz"); //$NON-NLS-1$ //$NON-NLS-2$
        b.put("depth", "full"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("resume (runKey) invariant", key, GenericPending.canonicalParams(b)); //$NON-NLS-1$

        // A different work budget (timeoutSeconds) -> a different run, not a coalesce.
        Map<String, String> c = new HashMap<>(a);
        c.put("timeoutSeconds", "90"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("different budget must not share a key", //$NON-NLS-1$
            key.equals(GenericPending.canonicalParams(c)));
    }

    @Test
    public void canonicalParamsIsInjectiveAcrossDelimiterChars()
    {
        // A naive k=v&k=v join would let these two distinct maps collide onto one
        // key; the length-prefixed encoding must keep them apart so two different
        // calls never wrongly coalesce.
        Map<String, String> two = new HashMap<>();
        two.put("a", "x"); //$NON-NLS-1$ //$NON-NLS-2$
        two.put("b", "y"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> one = new HashMap<>();
        one.put("a", "x&b=y"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("distinct arg maps must not share a canonical key", //$NON-NLS-1$
            GenericPending.canonicalParams(two).equals(GenericPending.canonicalParams(one)));
    }

    @Test
    public void canonicalParamsEmptyForNoArgs()
    {
        assertEquals("", GenericPending.canonicalParams(null)); //$NON-NLS-1$
        assertEquals("", GenericPending.canonicalParams(new HashMap<>())); //$NON-NLS-1$
    }
}
