/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;

import org.junit.Test;

/**
 * The name a call site starts with is matched against the project's common modules.
 */
public class ACallSiteNamesTheCommonModuleItCallsTest
{
    private static final Map<String, String> MODULES = Map.of("общегоназначения", //$NON-NLS-1$
        "ОбщегоНазначения"); //$NON-NLS-1$

    /** The name is matched without regard to case and answers the module's own spelling. */
    @Test
    public void theNameIsMatchedWithoutRegardToCase()
    {
        assertEquals("CommonModule.ОбщегоНазначения.Module", //$NON-NLS-1$
            BslCallGraphHelper.commonModuleFqn("ОБЩЕГОНАЗНАЧЕНИЯ", MODULES)); //$NON-NLS-1$
    }

    /** A name that is no common module is not an edge. */
    @Test
    public void aNameThatIsNoCommonModuleIsNotAnEdge()
    {
        assertNull(BslCallGraphHelper.commonModuleFqn("Запрос", MODULES)); //$NON-NLS-1$
        assertNull(BslCallGraphHelper.commonModuleFqn(null, MODULES));
    }

    /** Common modules that could not be listed answer null, not an empty list. */
    @Test
    public void unknownCommonModulesAnswerNull()
    {
        assertNull(BslCallGraphHelper.calleesByCommonModuleName(
            com._1c.g5.v8.dt.bsl.model.BslFactory.eINSTANCE.createModule(), null));
    }
}
