/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unit tests for {@link YaxunitTestRunner}.
 * <p>
 * Covers tool metadata, the schema contents, and the two required-parameter gates at the top of
 * {@code execute} (projectName and applicationId, which fire <em>before</em> the launch manager is
 * touched). The actual launch and polling flow needs a running Eclipse debug runtime and is not
 * exercised here.
 * </p>
 */
public class YaxunitTestRunnerTest
{
    private static YaxunitTestRunner newTool()
    {
        return new YaxunitTestRunner();
    }

    private static Map<String, String> params(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void nameIsRunYaxunitTests()
    {
        assertEquals("run_yaxunit_tests", newTool().getName());
    }

    @Test
    public void descriptionIsNonBlank()
    {
        String description = newTool().getDescription();
        assertNotNull(description);
        assertTrue(description.length() > 0);
    }

    @Test
    public void responseTypeIsMarkdown()
    {
        assertEquals(IMcpTool.ResponseType.MARKDOWN, newTool().getResponseType());
    }

    @Test
    public void schemaListsEveryFilterParameter()
    {
        String schema = newTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema should declare launchConfigurationName",
            schema.contains("\"launchConfigurationName\""));
        assertTrue("schema should declare projectName", schema.contains("\"projectName\""));
        assertTrue("schema should declare applicationId", schema.contains("\"applicationId\""));
        assertTrue("schema should declare extensions", schema.contains("\"extensions\""));
        assertTrue("schema should declare modules", schema.contains("\"modules\""));
        assertTrue("schema should declare tests", schema.contains("\"tests\""));
        assertTrue("schema should declare timeoutSeconds", schema.contains("\"timeoutSeconds\""));
    }

    @Test
    public void executeWithoutProjectNameReportsItAsRequired()
    {
        // applicationId alone (no launch config, no projectName) trips the projectName gate.
        String result = newTool().execute(params("applicationId", "app-1"));
        assertNotNull(result);
        assertTrue(result.contains("projectName"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void executeWithoutApplicationIdReportsItAsRequired()
    {
        // projectName alone (no launch config, no applicationId) trips the applicationId gate.
        String result = newTool().execute(params("projectName", "Proj"));
        assertNotNull(result);
        assertTrue(result.contains("applicationId"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void executeWithNoArgumentsAtAllReportsError()
    {
        String result = newTool().execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }
}
