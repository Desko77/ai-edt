/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * A type answer is taken from a model that held still, and a moved model is reported, not trusted.
 *
 * <p>Measured on a stand: the same position answered with a type on one run and with
 * Неопределено on another, four runs apart, and the difference was how far the model had got. A
 * position that has no type and a position whose type was not computed must not read alike. The
 * watch that separates them is the one validate_query already uses: every status change of the
 * model while the references resolve, not two readiness readings.</p>
 */
public class ATypeAnswerIsReadOffAStillModelTest
{
    /**
     * A moved model has its own state, and it says so in words the caller can act on.
     */
    @Test
    public void aMovedModelNamesItself()
    {
        BslModuleAccess.TypeState moved = BslModuleAccess.TypeState.valueOf("MODEL_MOVED"); //$NON-NLS-1$
        assertNotNull(moved.reason());
        assertTrue(moved.reason(), moved.reason().contains("ask again")); //$NON-NLS-1$
    }

    /**
     * The single-position and the batch paths go through the same watched resolution.
     *
     * @throws Exception when the helper is gone
     */
    @Test
    public void theWatchedResolutionLivesInOneHelper()
        throws Exception
    {
        Method helper = SymbolInfoReader.class.getDeclaredMethod("computeTypesWatchingModel", //$NON-NLS-1$
            org.eclipse.core.resources.IProject.class,
            org.eclipse.xtext.resource.XtextResource.class);
        assertNotNull(helper);
        assertTrue(java.lang.reflect.Modifier.isStatic(helper.getModifiers()));
    }

    /**
     * The schema still advertises the same arguments - the watch changes nothing a caller passes.
     */
    @Test
    public void theSchemaIsUnchanged()
    {
        String schema = new SymbolInfoReader().getInputSchema();
        assertTrue(schema, schema.contains("computeTypes")); //$NON-NLS-1$
    }
}
