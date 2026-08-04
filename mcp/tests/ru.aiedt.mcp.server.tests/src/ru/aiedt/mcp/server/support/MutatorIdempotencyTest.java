/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * Verifies the gate: only an allowlisted synchronous mutator with a non-blank
 * operationId, not a dry run, and not an edit_metadata batch, is keyed.
 */
public class MutatorIdempotencyTest
{
    private static final Map<String, String> NONE = Map.of();

    @Test
    public void keyedOnlyForAllowlistedMutatorWithKey()
    {
        assertTrue(MutatorIdempotency.applies("write_module_source", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MutatorIdempotency.applies("create_infobase", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void notKeyedWithoutAnOperationId()
    {
        assertFalse(MutatorIdempotency.applies("write_module_source", null, NONE)); //$NON-NLS-1$
        assertFalse(MutatorIdempotency.applies("write_module_source", "", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("write_module_source", "   ", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void notKeyedForNonMutatorsOrExcluded()
    {
        assertFalse(MutatorIdempotency.applies("get_project_errors", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("code_search", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("update_database", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("install_extension", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("uninstall_extension", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("import_configuration_from_xml", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("generate_event_handlers", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies(null, "op1", NONE)); //$NON-NLS-1$
    }

    @Test
    public void dryRunIsNotKeyed()
    {
        assertFalse(MutatorIdempotency.applies("write_module_source", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("dryRun", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("write_module_source", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("dryRun", "TRUE"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void editMetadataSingleOpKeyedButBatchNot()
    {
        assertTrue(MutatorIdempotency.applies("edit_metadata", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MutatorIdempotency.applies("edit_metadata", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("batch", "false"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("edit_metadata", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("batch", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void renameAndDeleteKeyedOnlyWhenConfirmed()
    {
        // Unconfirmed = a cacheable preview -> must NOT be keyed.
        assertFalse(MutatorIdempotency.applies("rename_metadata_object", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("delete_metadata_object", "op1", NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MutatorIdempotency.applies("rename_metadata_object", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("confirm", "false"))); //$NON-NLS-1$ //$NON-NLS-2$
        // Confirmed = the real mutation -> keyed.
        assertTrue(MutatorIdempotency.applies("rename_metadata_object", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("confirm", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MutatorIdempotency.applies("delete_metadata_object", "op1", //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("confirm", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
