/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolParamSettings.ParameterDef;

/**
 * Tests for {@link ToolParamSettings} that exercise the static catalogue and key builder -
 * everything that does not need a live Eclipse preference store. The read/write/reset/initialize
 * paths route through {@link ru.aiedt.mcp.server.Activator#getDefault()} and are not covered here.
 */
public class ToolParamSettingsTest
{
    private static final String SEARCH_IN_CODE = "search_in_code"; //$NON-NLS-1$
    private static final String GET_PROJECT_ERRORS = "get_project_errors"; //$NON-NLS-1$
    private static final String PARAM_LIMIT = "limit"; //$NON-NLS-1$
    private static final String PARAM_MAX_RESULTS = "maxResults"; //$NON-NLS-1$
    private static final String PARAM_CONTEXT_LINES = "contextLines"; //$NON-NLS-1$

    private final ToolParamSettings settings = ToolParamSettings.getInstance();

    // ---- singleton ------------------------------------------------------------------------

    @Test
    public void getInstanceReturnsTheSameInstanceEveryTime()
    {
        assertSame(ToolParamSettings.getInstance(), ToolParamSettings.getInstance());
    }

    // ---- catalogue shape ------------------------------------------------------------------

    @Test
    public void exactlySixToolsAreConfigurable()
    {
        // get_project_errors, get_bookmarks, get_tasks, get_metadata_objects,
        // get_content_assist, search_in_code.
        List<String> names = settings.getConfigurableToolNames();
        assertEquals(6, names.size());
        assertTrue(names.contains(GET_PROJECT_ERRORS));
        assertTrue(names.contains("get_bookmarks")); //$NON-NLS-1$
        assertTrue(names.contains("get_tasks")); //$NON-NLS-1$
        assertTrue(names.contains("get_metadata_objects")); //$NON-NLS-1$
        assertTrue(names.contains("get_content_assist")); //$NON-NLS-1$
        assertTrue(names.contains(SEARCH_IN_CODE));
    }

    @Test
    public void getConfigurableToolNamesReturnsAFreshMutableList()
    {
        // The method is documented to return a fresh list so callers can sort or extend it.
        List<String> first = settings.getConfigurableToolNames();
        List<String> second = settings.getConfigurableToolNames();
        assertNotSame("expected a fresh list per call", first, second); //$NON-NLS-1$
        assertEquals(first, second);
        // mutating the returned list must not corrupt the shared catalogue
        first.add("alien"); //$NON-NLS-1$
        assertEquals(6, settings.getConfigurableToolNames().size());
    }

    @Test
    public void getAllParametersExposesTheCatalogueUnmodifiable()
    {
        Map<String, List<ParameterDef>> all = settings.getAllParameters();
        try
        {
            all.put("intruder", List.of()); //$NON-NLS-1$
            fail("expected the catalogue map to reject mutation"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // shared static state; must be immutable
        }
    }

    // ---- per-tool lookups -----------------------------------------------------------------

    @Test
    public void getProjectErrorsExposesASingleLimitParameter()
    {
        List<ParameterDef> params = settings.getParametersForTool(GET_PROJECT_ERRORS);
        assertEquals(1, params.size());
        assertEquals(PARAM_LIMIT, params.get(0).getName());
    }

    @Test
    public void searchInCodeExposesMaxResultsAndContextLinesInOrder()
    {
        List<ParameterDef> params = settings.getParametersForTool(SEARCH_IN_CODE);
        assertEquals(2, params.size());
        assertEquals(PARAM_MAX_RESULTS, params.get(0).getName());
        assertEquals(PARAM_CONTEXT_LINES, params.get(1).getName());
    }

    @Test
    public void aToolWithoutConfigurableParametersGetsAnEmptyList()
    {
        // get_edt_version has no spinner on the preference page.
        List<ParameterDef> params = settings.getParametersForTool("get_edt_version"); //$NON-NLS-1$
        assertNotNull(params);
        assertTrue(params.isEmpty());
    }

    @Test
    public void anUnknownToolGetsAnEmptyListNotNull()
    {
        List<ParameterDef> params = settings.getParametersForTool("does_not_exist"); //$NON-NLS-1$
        assertNotNull(params);
        assertTrue(params.isEmpty());
    }

    @Test
    public void parameterListForAToolIsUnmodifiable()
    {
        List<ParameterDef> params = settings.getParametersForTool(SEARCH_IN_CODE);
        try
        {
            params.add(params.get(0));
            fail("expected the per-tool parameter list to reject mutation"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // shared catalogue entry; must be immutable
        }
    }

    // ---- ParameterDef ranges and labels ---------------------------------------------------

    @Test
    public void everyLimitParameterHasDefaultHundredAndRangeOneToThousand()
    {
        for (String tool : new String[] {GET_PROJECT_ERRORS, "get_bookmarks", "get_tasks", //$NON-NLS-1$ //$NON-NLS-2$
            "get_metadata_objects", "get_content_assist"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            ParameterDef limit = settings.getParametersForTool(tool).get(0);
            assertEquals(tool + " limit default", 100, limit.getDefaultValue()); //$NON-NLS-1$
            assertEquals(tool + " limit min", 1, limit.getMinValue()); //$NON-NLS-1$
            assertEquals(tool + " limit max", 1000, limit.getMaxValue()); //$NON-NLS-1$
        }
    }

    @Test
    public void searchInCodeMaxResultsDefaultsToHundredWithRangeOneToFiveHundred()
    {
        ParameterDef maxResults = findParam(SEARCH_IN_CODE, PARAM_MAX_RESULTS);
        assertEquals(100, maxResults.getDefaultValue());
        assertEquals(1, maxResults.getMinValue());
        assertEquals(500, maxResults.getMaxValue());
    }

    @Test
    public void searchInCodeContextLinesDefaultsToTwoWithRangeZeroToFive()
    {
        ParameterDef contextLines = findParam(SEARCH_IN_CODE, PARAM_CONTEXT_LINES);
        assertEquals(2, contextLines.getDefaultValue());
        assertEquals(0, contextLines.getMinValue());
        assertEquals(5, contextLines.getMaxValue());
    }

    @Test
    public void everyParameterHasValidRangeAndDefaultInsideIt()
    {
        for (Map.Entry<String, List<ParameterDef>> entry : settings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                String where = entry.getKey() + "." + param.getName(); //$NON-NLS-1$
                assertTrue(where + " min>max", param.getMinValue() <= param.getMaxValue()); //$NON-NLS-1$
                assertTrue(where + " default<min", param.getDefaultValue() >= param.getMinValue()); //$NON-NLS-1$
                assertTrue(where + " default>max", param.getDefaultValue() <= param.getMaxValue()); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void everyParameterHasNonBlankDisplayLabelAndDescription()
    {
        for (Map.Entry<String, List<ParameterDef>> entry : settings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                assertNotNull(param.getDisplayName());
                assertFalse(param.getName(), param.getDisplayName().isEmpty());
                assertNotNull(param.getDescription());
                assertFalse(param.getName(), param.getDescription().isEmpty());
            }
        }
    }

    // ---- key builder ----------------------------------------------------------------------

    @Test
    public void buildKeyStitchesToolDotToolDotParameter()
    {
        assertEquals("tool.search_in_code.maxResults", //$NON-NLS-1$
            ToolParamSettings.buildKey(SEARCH_IN_CODE, PARAM_MAX_RESULTS));
        assertEquals("tool.get_project_errors.limit", //$NON-NLS-1$
            ToolParamSettings.buildKey(GET_PROJECT_ERRORS, PARAM_LIMIT));
    }

    // ---- catalogue vs group table ---------------------------------------------------------

    @Test
    public void everyConfigurableToolBelongsToAGroup()
    {
        // A configurable tool that no group claims can never be switched off, so it has no business
        // being on the preference page. Catch drift between the two tables here.
        for (String toolName : settings.getConfigurableToolNames())
        {
            assertNotNull("configurable tool " + toolName + " is in no group", //$NON-NLS-1$ //$NON-NLS-2$
                ToolCategory.getGroupForTool(toolName));
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private ParameterDef findParam(String tool, String name)
    {
        for (ParameterDef param : settings.getParametersForTool(tool))
        {
            if (param.getName().equals(name))
            {
                return param;
            }
        }
        throw new AssertionError("no parameter " + name + " on " + tool); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
