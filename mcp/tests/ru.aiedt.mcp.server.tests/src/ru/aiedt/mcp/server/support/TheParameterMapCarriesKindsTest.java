/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * The map now says what kind each parameter is, and still says the same names it always did.
 * <p>
 * Two things read it: {@code UnreadArguments}, which refuses a call carrying an argument the
 * operation does not read, and the help of {@code edit_metadata}. Both ask for names. A map that
 * started answering {@code projectName:string} to them would make every correct call look like it
 * carried an argument nobody reads - so the old answer is held here, not assumed.
 * </p>
 */
public class TheParameterMapCarriesKindsTest
{
    @Test
    public void theOldAnswerIsUnchanged()
    {
        List<String> names = OperationParameters.of("DiagnosticsFacadeTool", "revalidate_objects"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the map has to be packaged for any of this to mean anything", //$NON-NLS-1$
            OperationParameters.available());
        assertEquals("names, with no kind attached - this is what both readers ask for", //$NON-NLS-1$
            java.util.Arrays.asList("objectFqns", "objects", "projectName"), names); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theKindIsThereWhenAskedFor()
    {
        List<String> declared =
            OperationParameters.withKinds("DiagnosticsFacadeTool", "revalidate_objects"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(java.util.Arrays.asList(
            "objectFqns:string[]", "objects:string[]", "projectName:string"), declared); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void anEntryIsReadApart()
    {
        assertEquals("projectName", OperationParameters.nameOf("projectName:string")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("string", OperationParameters.kindOf("projectName:string")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("?", OperationParameters.kindOf("somethingNobodyDeclares:?")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEntryWithoutAKindStillYieldsItsName()
    {
        // The map is regenerated from the sources, and a reader that broke on an older shape of the
        // file would take help down rather than answer less.
        assertEquals("projectName", OperationParameters.nameOf("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(OperationParameters.kindOf("projectName")); //$NON-NLS-1$
    }

    @Test
    public void anOperationNobodyRecordedAnswersEmpty()
    {
        // Not the same as an operation that takes no parameters, and the callers say so in words.
        assertTrue(OperationParameters.of("NoSuchTool", "no_such_operation").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(OperationParameters.withKinds("NoSuchTool", "no_such_operation").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void everyRecordedParameterCarriesAKindOrSaysItHasNone()
    {
        // Swept rather than sampled: a parameter whose kind silently vanished would otherwise show
        // up only in whatever help happens to be read.
        List<String> declared =
            OperationParameters.withKinds("EditMetadataTool", "create_object"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("create_object has to be in the map for this to check anything", //$NON-NLS-1$
            !declared.isEmpty());
        for (String entry : declared)
        {
            assertTrue("every entry carries a kind, even if it is the mark for none: " + entry, //$NON-NLS-1$
                OperationParameters.kindOf(entry) != null);
        }
    }
}
