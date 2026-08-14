/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies which proposal documentation is real and which is a model object that printed itself.
 * <p>
 * A proposal with nothing to say hands back the element, and Java renders it as a class name and an
 * identity hash. Passing that on told the caller nothing and, for a function, put the model's internal
 * state on the wire. The two samples below are the ones actually observed.
 * </p>
 */
public class ProposalDocumentationTest
{
    @Test
    public void aRenderedModelObjectIsRecognised()
    {
        assertTrue(ContentAssistReader.isObjectDump("com._1c.g5.v8.dt.bsl.model.impl.ProposalElementImpl@2194540c")); //$NON-NLS-1$
        assertTrue("a function trails its whole state after the hash", //$NON-NLS-1$
            ContentAssistReader.isObjectDump("FunctionImpl@259aa3d3 (name: Do, finalInParamState: [])")); //$NON-NLS-1$
    }

    @Test
    public void realDocumentationIsKept()
    {
        assertFalse(ContentAssistReader.isObjectDump("Возвращает активные элементы стенда.")); //$NON-NLS-1$
        assertFalse(ContentAssistReader.isObjectDump("<p>Returns the items.</p>")); //$NON-NLS-1$
        assertFalse("an at-sign in prose is not an object rendering", //$NON-NLS-1$
            ContentAssistReader.isObjectDump("Contact support@example.com for details")); //$NON-NLS-1$
        assertFalse("nor is a parameter tag", ContentAssistReader.isObjectDump("@param filter the filter")); //$NON-NLS-1$
    }

    @Test
    public void emptinessIsNotADump()
    {
        assertFalse(ContentAssistReader.isObjectDump(null));
        assertFalse(ContentAssistReader.isObjectDump("")); //$NON-NLS-1$
        assertFalse(ContentAssistReader.isObjectDump("@")); //$NON-NLS-1$
    }
}
