/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The four addressing properties of a Task, and what a call that names none of them gets.
 * <p>
 * Without the refusal such a call reaches the transaction, writes nothing, and answers with
 * the message of an object it did not touch - and a caller reads that as the addressing
 * having been applied. The tests pin the refusal, the argument names it hands back, and the
 * reading of the one argument that is not a plain string.
 * </p>
 */
public class AnEmptyTaskAddressingCallIsRefusedTest
{
    /** The four names the refusal has to hand back, so a caller can pick one and call again. */
    private static final String[] ARGUMENTS = {"addressingRegister", "addressingAttributes", //$NON-NLS-1$ //$NON-NLS-2$
        "mainAddressingAttribute", "currentPerformer"}; //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void aCallThatNamesNothingIsRefusedWithTheFourArguments()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "set_task_addressing"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Task.Задача"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new SpecializedOps().opSetTaskAddressing(params);

        assertTrue("a call that asks for nothing is refused: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":false")); //$NON-NLS-1$
        for (String argument : ARGUMENTS)
        {
            assertTrue("the refusal names " + argument + ": " + answer, answer.contains(argument)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue("a refused call must say nothing was changed: " + answer, //$NON-NLS-1$
            answer.contains("Nothing was changed")); //$NON-NLS-1$
    }

    @Test
    public void anyOneOfTheFourIsEnoughToGetPastTheRefusal()
    {
        assertNull(SpecializedOps.taskAddressingRefusal("InformationRegister.Адресация", null, null, null)); //$NON-NLS-1$
        assertNull(SpecializedOps.taskAddressingRefusal(null, "[{\"name\":\"Executor\"}]", null, null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SpecializedOps.taskAddressingRefusal(null, null, "Executor", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SpecializedOps.taskAddressingRefusal(null, null, null, "SessionParameter.CurrentUser")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(SpecializedOps.taskAddressingRefusal(null, null, null, null));
        assertNotNull("a blank argument asks for nothing", //$NON-NLS-1$
            SpecializedOps.taskAddressingRefusal("  ", "", null, null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSpecCarriesItsNameAndTheOptionalTypeAndDimension()
    {
        SpecializedOps.AddressingSpecs parsed = SpecializedOps.parseAddressingSpecs(
            "[{\"name\":\"Executor\",\"type\":\"CatalogRef.Users\"}," //$NON-NLS-1$
                + "{\"name\":\"DueDate\",\"dimension\":\"InformationRegister.Адресация.Dimension.Дата\"}]"); //$NON-NLS-1$

        assertNull(parsed.error);
        assertEquals(2, parsed.specs.size());
        assertEquals("Executor", parsed.specs.get(0).name); //$NON-NLS-1$
        assertEquals("CatalogRef.Users", parsed.specs.get(0).type); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a spec without a dimension has none", parsed.specs.get(0).dimension); //$NON-NLS-1$
        assertEquals("DueDate", parsed.specs.get(1).name); //$NON-NLS-1$
        assertNull(parsed.specs.get(1).type);
        assertEquals("InformationRegister.Адресация.Dimension.Дата", //$NON-NLS-1$
            parsed.specs.get(1).dimension);
    }

    @Test
    public void anEntryWithoutANameIsRefusedRatherThanCreated()
    {
        SpecializedOps.AddressingSpecs parsed = SpecializedOps.parseAddressingSpecs(
            "[{\"type\":\"CatalogRef.Users\"}]"); //$NON-NLS-1$

        assertNotNull("an attribute with no name cannot be created", parsed.error); //$NON-NLS-1$
        assertTrue(parsed.error, parsed.error.contains("name")); //$NON-NLS-1$
        assertTrue("nothing may be left half-read", parsed.specs.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anArgumentThatIsNotAnArrayIsRefusedWithItsShape()
    {
        SpecializedOps.AddressingSpecs scalar =
            SpecializedOps.parseAddressingSpecs("CatalogRef.Users"); //$NON-NLS-1$
        assertNotNull(scalar.error);
        assertTrue("the refusal shows the shape it wanted: " + scalar.error, //$NON-NLS-1$
            scalar.error.contains("[{\"name\":")); //$NON-NLS-1$
        assertTrue("the refusal quotes what it was given: " + scalar.error, //$NON-NLS-1$
            scalar.error.contains("CatalogRef.Users")); //$NON-NLS-1$ //$NON-NLS-2$

        SpecializedOps.AddressingSpecs empty = SpecializedOps.parseAddressingSpecs("[]"); //$NON-NLS-1$
        assertNotNull("an empty array asks for nothing", empty.error); //$NON-NLS-1$
        assertTrue(empty.error, empty.error.contains("empty array")); //$NON-NLS-1$
        assertTrue(empty.specs.isEmpty());
    }
}
