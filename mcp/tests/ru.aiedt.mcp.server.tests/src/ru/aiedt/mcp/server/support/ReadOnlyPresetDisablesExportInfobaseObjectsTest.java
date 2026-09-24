/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.ops.ConfigIoFacadeTool;

/**
 * The write-blocking presets switch the infobase-objects export off. The operation writes files
 * and runs a Designer against the infobase, so it travels with the exports of the applications
 * group: the presets that switch those off switch the whole {@code config_io} facade off with
 * them, and the facade gates the operation by the grouped export's enablement besides.
 */
public class ReadOnlyPresetDisablesExportInfobaseObjectsTest
{
    /**
     * Every preset that switches the grouped exports off switches the facade off too, so no
     * preset leaves the operation reachable while its siblings are refused.
     */
    @Test
    public void everyPresetThatRefusesTheGroupedExportsRefusesTheFacade()
    {
        for (ToolProfile preset : ToolProfile.values())
        {
            if (preset.getDisabledTools() == null)
            {
                continue; // CUSTOM has no opinion of its own.
            }
            if (preset.getDisabledTools().contains("export_configuration_to_xml")) //$NON-NLS-1$
            {
                assertTrue(preset + " refuses the grouped export but leaves config_io on, " //$NON-NLS-1$
                    + "which would leave export_infobase_objects reachable", //$NON-NLS-1$
                    preset.getDisabledTools().contains("config_io")); //$NON-NLS-1$
            }
        }
        assertTrue(ToolProfile.READ_ONLY.getDisabledTools().contains("config_io")); //$NON-NLS-1$
        assertTrue(ToolProfile.CODE_REVIEW.getDisabledTools().contains("config_io")); //$NON-NLS-1$
    }

    /**
     * The facade gates the operation by the grouped export's enablement: with the standalone not
     * enabled, the operation is refused before any work starts. A bare test JVM registers no
     * tools, which makes the gate's answer deterministic here.
     */
    @Test
    public void theFacadeRefusesTheOperationWhenTheGroupedExportIsNotEnabled()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "export_infobase_objects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Проект"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objects", "Catalog.Банки"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputPath", "C:/nowhere/result"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new ConfigIoFacadeTool().execute(params);

        assertFalse(answer.isEmpty());
        assertTrue("the operation is refused while the grouped export is not enabled: " + answer, //$NON-NLS-1$
            answer.contains("is disabled and was not executed")); //$NON-NLS-1$
    }
}
