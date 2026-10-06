/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.ToolGate;

/**
 * A launch that updates the infobase first asks the preset about {@code update_database} before
 * anything runs, and a launch that opted out of the update has nothing to ask.
 * <p>
 * The debug launch, the client start and both YAXUnit modes carry {@code updateBeforeLaunch} and
 * run the same pre-launch update, so they all refuse through the one decision
 * {@link DebugSessionStarter#presetUpdateRefusal}. The YAXUnit seam takes the update step as a
 * parameter, which is what lets the refusal be held to "the step did not run" without an
 * infobase; the decision itself is asked directly for the other paths.
 * </p>
 */
public class ALaunchUpdateAsksThePresetTest
{
    private IPreferenceStore store;

    private String presetBefore;

    /**
     * Takes the live preference store and remembers which preset it held.
     */
    @Before
    public void aStoreToHoldThePreset()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
    }

    /**
     * Puts the preset back.
     */
    @After
    public void thePresetGoesBack()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /**
     * Under a preset that disables update_database, a launch asking for the update is refused with
     * the gate's own wording and the way out, and the update step never runs. The same call that
     * opts out of the update goes on and still runs nothing.
     */
    @Test
    public void aPresetThatForbidsTheUpdateRefusesTheLaunchBeforeTheStepRuns()
    {
        List<String> asked = new ArrayList<>();
        for (String preset : Arrays.asList(ToolProfile.DEBUG_AND_TEST.name(),
            ToolProfile.CODE_REVIEW.name(), ToolProfile.READ_ONLY.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(true, "Proj", "app-1", //$NON-NLS-1$ //$NON-NLS-2$
                (project, application) -> {
                    asked.add(project + "/" + application); //$NON-NLS-1$
                    return ApplicationUpdater.Result.failed("must not run"); //$NON-NLS-1$
                });
            assertTrue(preset + ": " + refusal, //$NON-NLS-1$
                refusal.contains(ToolGate.disabledMessage("update_database"))); //$NON-NLS-1$
            assertTrue(preset + ": the way out is named: " + refusal, //$NON-NLS-1$
                refusal.contains("updateBeforeLaunch=false")); //$NON-NLS-1$
        }
        assertEquals("the update step must not run under any of these presets", //$NON-NLS-1$
            0, asked.size());

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        String optedOut = YaxunitTestRunner.preLaunchUpdateRefusal(false, "Proj", "app-1", //$NON-NLS-1$ //$NON-NLS-2$
            (project, application) -> {
                asked.add("called anyway"); //$NON-NLS-1$
                return ApplicationUpdater.Result.failed("boom"); //$NON-NLS-1$
            });
        assertNull("a launch that opted out of the update asks no question", optedOut); //$NON-NLS-1$
        assertEquals(0, asked.size());
    }

    /**
     * The decision every launching path asks: refused wherever update_database is disabled,
     * silent under the presets that allow writing, and silent for a launch that updates nothing -
     * whatever else it does.
     */
    @Test
    public void theDecisionEveryLaunchAsksAnswersByPresetAndByFlag()
    {
        for (String preset : Arrays.asList(ToolProfile.READ_ONLY.name(),
            ToolProfile.DEBUG_AND_TEST.name(), ToolProfile.CODE_REVIEW.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            String refusal = DebugSessionStarter.presetUpdateRefusal(true);
            assertTrue(preset + ": " + refusal, //$NON-NLS-1$
                refusal.contains(ToolGate.disabledMessage("update_database"))); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("Nothing was launched or updated")); //$NON-NLS-1$
            assertNull(preset + " asks nothing of a launch that updates nothing", //$NON-NLS-1$
                DebugSessionStarter.presetUpdateRefusal(false));
        }
        for (String preset : Arrays.asList(ToolProfile.ALL_TOOLS.name(), ToolProfile.EDITING.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            assertNull(preset + " allows the update", DebugSessionStarter.presetUpdateRefusal(true)); //$NON-NLS-1$
            assertNull(DebugSessionStarter.presetUpdateRefusal(false));
        }
    }
}
