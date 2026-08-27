/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * What a failed batch tells the caller, and what it inherits.
 * <p>
 * The failure message used to say "see batchResults[] for each". The array is in the structured
 * payload, but the text channel carries a summary, and for a failure that summary is the failure
 * text alone - so a caller reading text was sent to an array it had not been handed. The only route
 * left was reissuing the operations one at a time, which is the thing a batch exists to avoid.
 * </p>
 * <p>
 * Separately: {@code formFqn} was not among the inherited parameters, so a batch of Forms
 * operations with it named once at the top failed whole while the same operations passed one at a
 * time. Reported from two workspaces two days apart.
 * </p>
 */
public class BatchSaysWhyEachOperationFailedTest
{
    private static Map<String, Object> entry(int index, String operation, String error,
        String response)
    {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("index", Integer.valueOf(index)); //$NON-NLS-1$
        e.put("operation", operation); //$NON-NLS-1$
        e.put("ok", Boolean.FALSE); //$NON-NLS-1$
        if (error != null)
        {
            e.put("error", error); //$NON-NLS-1$
        }
        if (response != null)
        {
            e.put("response", response); //$NON-NLS-1$
        }
        return e;
    }

    @Test
    public void whereAnOperationIsIdentifiedIsInherited()
    {
        assertTrue("every Forms operation needs formFqn, so a batch of them needs it inherited", //$NON-NLS-1$
            Arrays.asList(EditMetadataTool.SHARED_BATCH_PARAMS).contains("formFqn")); //$NON-NLS-1$
        assertTrue(Arrays.asList(EditMetadataTool.SHARED_BATCH_PARAMS).contains("projectName")); //$NON-NLS-1$
        assertTrue(Arrays.asList(EditMetadataTool.SHARED_BATCH_PARAMS).contains("ownerFqn")); //$NON-NLS-1$
        assertTrue(Arrays.asList(EditMetadataTool.SHARED_BATCH_PARAMS).contains("dryRun")); //$NON-NLS-1$
    }

    @Test
    public void whatAnOperationWritesIsNotInherited()
    {
        // Inheriting a payload argument would apply one operation's value to another's write.
        for (String payload : new String[] {"name", "type", "propertyValue", "dataPath"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertFalse(payload + " says what to write, not where", //$NON-NLS-1$
                Arrays.asList(EditMetadataTool.SHARED_BATCH_PARAMS).contains(payload));
        }
    }

    @Test
    public void aReasonGivenDirectlyIsUsed()
    {
        assertEquals("unknown or empty operation", //$NON-NLS-1$
            EditMetadataTool.reasonOf(entry(0, "add_field", "unknown or empty operation", null))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aReasonInsideTheOperationsOwnAnswerIsUnwrapped()
    {
        Map<String, Object> failed = entry(1, "add_field", null, //$NON-NLS-1$
            "{\"success\":false,\"error\":\"formFqn is required\"}"); //$NON-NLS-1$

        assertEquals("formFqn is required", EditMetadataTool.reasonOf(failed)); //$NON-NLS-1$
    }

    @Test
    public void anAnswerThatIsNotJsonIsPassedThroughRatherThanSwallowed()
    {
        Map<String, Object> failed = entry(2, "add_field", null, "Error: something went wrong"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Error: something went wrong", EditMetadataTool.reasonOf(failed)); //$NON-NLS-1$
    }

    @Test
    public void anEntryWithNoReasonSaysSoRatherThanReadingEmpty()
    {
        String reason = EditMetadataTool.reasonOf(entry(3, "add_field", null, null)); //$NON-NLS-1$

        assertFalse("an empty reason reads as no failure at all", reason.isEmpty()); //$NON-NLS-1$
    }
}
