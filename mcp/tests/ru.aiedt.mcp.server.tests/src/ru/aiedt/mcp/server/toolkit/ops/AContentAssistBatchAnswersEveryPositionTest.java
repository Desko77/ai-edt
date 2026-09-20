/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The batch form answers every position, and one position's failure does not stop the rest.
 *
 * <p>A survey over hundreds of positions used to take a call apiece. With {@code positions} the
 * answer is one array: each entry is the same object the single-position call would produce, a
 * failed entry carries its own error, and the entries after it still run. The guards below cover
 * what is decidable without a workspace: the malformed-array refusals and the shape of the batch
 * answer.</p>
 */
public class AContentAssistBatchAnswersEveryPositionTest
{
    /**
     * A malformed positions value is refused as a whole - it answers nothing by parts.
     */
    @Test
    public void aMalformedPositionsValueIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "noSuchProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("positions", "not json"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ContentAssistReader().execute(params);
        assertTrue(answer, answer.contains("not valid JSON")); //$NON-NLS-1$
    }

    /**
     * An empty array has nothing to answer and says so.
     */
    @Test
    public void anEmptyArrayIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "noSuchProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("positions", "[]"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ContentAssistReader().execute(params);
        assertTrue(answer, answer.contains("non-empty JSON array")); //$NON-NLS-1$
    }

    /**
     * A valid batch against a project that does not resolve answers with the named project
     * refusal, before any position is attempted.
     */
    @Test
    public void aMissingProjectIsRefusedBeforeThePositions()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "noSuchProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("positions", "[{\"line\":1,\"column\":1}]"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ContentAssistReader().execute(params);
        assertFalse(answer, answer.contains("\"batch\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("noSuchProject")); //$NON-NLS-1$
    }

    /**
     * The schema advertises the batch form.
     */
    @Test
    public void theSchemaAdvertisesTheBatchForm()
    {
        String schema = new ContentAssistReader().getInputSchema();
        assertTrue(schema, schema.contains("positions")); //$NON-NLS-1$
    }
}
