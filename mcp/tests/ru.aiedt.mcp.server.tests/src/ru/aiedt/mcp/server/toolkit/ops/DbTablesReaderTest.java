/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers what the table reader promises before it ever reaches a configuration.
 * <p>
 * The tables themselves come from the environment's derived model, so a real answer is verified
 * against a live workspace. What is pinned here is the part a caller meets first: that the tool is
 * findable and describes itself truthfully, and that a call missing what it needs is answered with
 * a reason rather than an exception.
 * </p>
 */
public class DbTablesReaderTest
{
    @Test
    public void theNameSaysWhatItDoes()
    {
        assertEquals("describe_db_tables", new DbTablesReader().getName()); //$NON-NLS-1$
    }

    @Test
    public void theAnswerIsStructured()
    {
        // A caller reconciling its own table list against ours parses this; markdown would make it
        // read the answer back out of prose.
        assertEquals(IMcpTool.ResponseType.JSON, new DbTablesReader().getResponseType());
    }

    @Test
    public void theDescriptionNamesWhatOnlyThisToolAnswers()
    {
        // An agent picks tools by their descriptions, and the reason to reach for this one is the
        // half query validation cannot cover: the tables and fields nobody thought to ask about.
        String description = new DbTablesReader().getDescription();
        assertTrue("it has to say it lists tables", //$NON-NLS-1$
            description.contains("tables")); //$NON-NLS-1$
        assertTrue("virtual register tables are the hard part - say so", //$NON-NLS-1$
            description.contains("virtual")); //$NON-NLS-1$
        assertTrue("both languages are the point, not a detail", //$NON-NLS-1$
            description.contains("Russian")); //$NON-NLS-1$
    }

    @Test
    public void theSchemaAsksForWhatItNeedsAndNothingElse()
    {
        String schema = new DbTablesReader().getInputSchema();
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"includeFields\"")); //$NON-NLS-1$
        assertTrue("both required parameters must be declared required", //$NON-NLS-1$
            schema.contains("\"required\"")); //$NON-NLS-1$
    }

    @Test
    public void aCallWithoutItsParametersIsAnsweredNotThrown()
    {
        assertTrue(new DbTablesReader().execute(new HashMap<>()).contains("required")); //$NON-NLS-1$

        Map<String, String> onlyProject = new HashMap<>();
        onlyProject.put("projectName", "aiedt-tests-no-such-project"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new DbTablesReader().execute(onlyProject).contains("required")); //$NON-NLS-1$

        Map<String, String> onlyObject = new HashMap<>();
        onlyObject.put("objectFqn", "Catalog.Currencies"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new DbTablesReader().execute(onlyObject).contains("required")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownProjectIsReportedRatherThanGuessedAt()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "aiedt-tests-no-such-project"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Currencies"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DbTablesReader().execute(params);
        assertTrue("the answer must name the project it could not find, got: " + result, //$NON-NLS-1$
            result.contains("aiedt-tests-no-such-project")); //$NON-NLS-1$
    }
}
