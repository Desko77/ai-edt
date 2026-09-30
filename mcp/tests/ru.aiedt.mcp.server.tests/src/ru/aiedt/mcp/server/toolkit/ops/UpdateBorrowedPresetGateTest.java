/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;

/**
 * The presets that promise not to write refuse {@code update_borrowed} with {@code apply=true}.
 * <p>
 * The facade lives in the constructors group, which Code Review keeps on - the half the group
 * gating does not cover - so the facade is named in the presets' own write list and the
 * operation asks the gate before the first write. Read-only, Debug &amp; Test and Code Review
 * all have to refuse the call by the gate's own wording; the review without {@code apply} is a
 * read and stays reachable wherever the tool itself is.
 * </p>
 */
public class UpdateBorrowedPresetGateTest
{
    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    /** Switches the facade off in the tool settings, remembering what they replaced. */
    @Before
    public void theFacadeIsSwitchedOff()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        ToolSettingsStore.getInstance().setDisabledTools(Set.of(ExtensionWorkshopTool.NAME));
    }

    /** Puts the tool settings back as they were before the test. */
    @After
    public void theSettingsGoBack()
    {
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /**
     * Every preset that blocks writing switches the facade off by name: the constructors group
     * is the half Code Review keeps, and a borrow or an apply through the group it left open
     * would be the write it just promised not to make.
     */
    @Test
    public void everyWriteBlockingPresetSwitchesTheFacadeOff()
    {
        for (ToolProfile preset : java.util.Arrays.asList(ToolProfile.READ_ONLY,
            ToolProfile.DEBUG_AND_TEST, ToolProfile.CODE_REVIEW))
        {
            assertTrue(preset + " leaves extension_workshop on", //$NON-NLS-1$
                preset.getDisabledTools().contains(ExtensionWorkshopTool.NAME));
        }
    }

    /** With the facade switched off, apply=true is refused by the gate's own wording. */
    @Test
    public void theApplyCallIsRefusedWhileTheFacadeIsOff()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "update_borrowed"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Проект"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("apply", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ExtensionWorkshopTool().execute(params);
        assertTrue("the write is refused while the facade is off: " + answer, //$NON-NLS-1$
            answer.contains("is disabled and was not executed")); //$NON-NLS-1$
        assertFalse(answer.contains("Unknown operation")); //$NON-NLS-1$
    }

    /**
     * The review without apply is a read: it is not refused by the gate. It still cannot run
     * without a project, so what is pinned here is that the refusal is the project's and not
     * the gate's.
     */
    @Test
    public void theReviewCallIsNotRefusedByTheGate()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "update_borrowed"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Проект"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ExtensionWorkshopTool().execute(params);
        assertFalse("a review with no apply is not a write and is not gated: " + answer, //$NON-NLS-1$
            answer.contains("is disabled and was not executed")); //$NON-NLS-1$
    }
}
