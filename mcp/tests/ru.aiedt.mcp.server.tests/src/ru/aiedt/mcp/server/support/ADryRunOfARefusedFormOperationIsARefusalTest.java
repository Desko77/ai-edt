/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * A dry run of a form operation that the action refuses is answered as a refusal, whichever shape
 * the action reported it in.
 */
public class ADryRunOfARefusedFormOperationIsARefusalTest
{
    /**
     * An answer formatted with a front-matter header and {@code status: error} is a refusal, and
     * only its body is kept.
     */
    @Test
    public void aFormattedErrorIsARefusal()
    {
        String preview = "---\ntool: edit_form\nstatus: error\n---\ndataPath 'X.Y' starts with 'X'. Nothing was created.\n"; //$NON-NLS-1$

        assertEquals("dataPath 'X.Y' starts with 'X'. Nothing was created.", //$NON-NLS-1$
            BmFormHelper.dryRunRefusal(preview));
    }

    /**
     * A plain {@code Error:} string is a refusal as it stands.
     */
    @Test
    public void aPlainErrorIsARefusal()
    {
        assertEquals("Error: form not found", BmFormHelper.dryRunRefusal("Error: form not found")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A formatted success, a plain message and no result at all are not refusals.
     */
    @Test
    public void aSuccessIsNotARefusal()
    {
        assertNull(BmFormHelper.dryRunRefusal("---\ntool: edit_form\nstatus: success\n---\nField added.\n")); //$NON-NLS-1$
        assertNull(BmFormHelper.dryRunRefusal("added attribute X")); //$NON-NLS-1$
        assertNull(BmFormHelper.dryRunRefusal(null));
    }

    /**
     * A {@code status: error} line below the header is body text, not the answer's status.
     */
    @Test
    public void anErrorLineInTheBodyIsNotTheStatus()
    {
        assertNull(BmFormHelper.dryRunRefusal("---\ntool: edit_form\nstatus: success\n---\nstatus: error\n")); //$NON-NLS-1$
    }
}
