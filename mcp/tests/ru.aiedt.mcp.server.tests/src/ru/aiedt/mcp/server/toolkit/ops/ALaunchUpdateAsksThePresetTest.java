/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * A launch asks one decision about the pre-launch infobase update, and the preset bends the
 * unnamed argument of a launching preset rather than refusing it.
 * <p>
 * The debug launch, the client start and both YAXUnit modes carry {@code updateBeforeLaunch} and
 * run the same pre-launch update, so they all read the one decision
 * {@link DebugSessionStarter#launchUpdate}. Under a preset that disabled {@code update_database}
 * a call that named no argument launches without updating and the answer says so in
 * {@code databaseUpdate}; an explicit {@code true} is still refused before anything runs, and a
 * call that opted out has nothing to ask. The YAXUnit seam takes the update step as a parameter,
 * which is what lets the refusal be held to "the step did not run" without an infobase.
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
     * Under every preset that disables update_database, a launch that names no argument launches
     * without updating: no refusal, the update step never runs, and the decision says the update
     * was dropped by the preset. An explicit true under the same preset is refused with the gate's
     * own wording and the way out, and an explicit false asks nothing anywhere.
     */
    @Test
    public void anUnnamedArgumentLaunchesWithoutUpdatingUnderALaunchBlockingPreset()
    {
        List<String> asked = new ArrayList<>();
        for (String preset : Arrays.asList(ToolProfile.DEBUG_AND_TEST.name(),
            ToolProfile.CODE_REVIEW.name(), ToolProfile.READ_ONLY.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);

            DebugSessionStarter.LaunchUpdate unnamed = DebugSessionStarter.launchUpdate((Boolean)null, true);
            assertFalse(preset + " launches rather than refuses an unnamed update", //$NON-NLS-1$
                unnamed.refusal != null);
            assertFalse(preset + " runs no update the preset forbids", unnamed.update); //$NON-NLS-1$
            assertTrue(preset + " names the drop in the decision", unnamed.skippedByPreset); //$NON-NLS-1$
            String unnamedRefusal = YaxunitTestRunner.preLaunchUpdateRefusal(unnamed, "Proj", //$NON-NLS-1$
                "app-1", (project, application) -> { //$NON-NLS-1$
                    asked.add("unnamed: " + project + "/" + application); //$NON-NLS-1$ //$NON-NLS-2$
                    return ApplicationUpdater.Result.failed("must not run"); //$NON-NLS-1$
                });
            assertNull(preset + ": the unnamed launch is not refused: " + unnamedRefusal, //$NON-NLS-1$
                unnamedRefusal);

            DebugSessionStarter.LaunchUpdate explicit =
                DebugSessionStarter.launchUpdate(Boolean.TRUE, true);
            String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(explicit, "Proj", "app-1", //$NON-NLS-1$ //$NON-NLS-2$
                (project, application) -> {
                    asked.add("explicit: " + project + "/" + application); //$NON-NLS-1$ //$NON-NLS-2$
                    return ApplicationUpdater.Result.failed("must not run"); //$NON-NLS-1$
                });
            assertTrue(preset + ": " + refusal, //$NON-NLS-1$
                refusal.contains(ToolGate.disabledMessage("update_database"))); //$NON-NLS-1$
            assertTrue(preset + ": the way out is named: " + refusal, //$NON-NLS-1$
                refusal.contains("updateBeforeLaunch=false")); //$NON-NLS-1$

            DebugSessionStarter.LaunchUpdate optedOut =
                DebugSessionStarter.launchUpdate(Boolean.FALSE, true);
            assertFalse(optedOut.update);
            assertFalse(optedOut.skippedByPreset);
            assertNull(optedOut.refusal);
        }
        assertEquals("the update step must not run under any of these presets", //$NON-NLS-1$
            0, asked.size());
    }

    /**
     * Under a preset that allows writing, the unnamed argument means what it always meant: the
     * update runs, once, for the project and the application the launch names.
     */
    @Test
    public void anUnnamedArgumentUpdatesUnderAPresetThatAllowsWriting()
    {
        List<String> asked = new ArrayList<>();
        for (String preset : Arrays.asList(ToolProfile.ALL_TOOLS.name(), ToolProfile.EDITING.name()))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset);
            DebugSessionStarter.LaunchUpdate unnamed = DebugSessionStarter.launchUpdate((Boolean)null, true);
            assertTrue(preset + " updates by the unnamed default", unnamed.asks && unnamed.update); //$NON-NLS-1$
            assertFalse(unnamed.skippedByPreset);
            assertNull(unnamed.refusal);
            assertTrue(DebugSessionStarter.launchUpdate(Boolean.TRUE, true).update);
            assertFalse(DebugSessionStarter.launchUpdate(Boolean.FALSE, true).update);

            String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(unnamed, "Proj", "app-1", //$NON-NLS-1$ //$NON-NLS-2$
                (project, application) -> {
                    asked.add(project + "/" + application); //$NON-NLS-1$
                    return ApplicationUpdater.Result.failed("the load stopped at row 40"); //$NON-NLS-1$
                });
            assertTrue(refusal, refusal.contains("the load stopped at row 40")); //$NON-NLS-1$
        }
        assertEquals("the step runs once per allowing preset", 2, asked.size()); //$NON-NLS-1$
    }

    /**
     * A start that updates only on request asks the same decision with its own default: unnamed
     * updates nothing and is refused nowhere, an explicit true under a blocking preset is refused.
     */
    @Test
    public void aClientStartKeepsItsOwnDefault()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        DebugSessionStarter.LaunchUpdate unnamed = DebugSessionStarter.launchUpdate((Boolean)null, false);
        assertFalse(unnamed.asks);
        assertFalse(unnamed.update);
        assertFalse(unnamed.skippedByPreset);
        assertNull(unnamed.refusal);
        DebugSessionStarter.LaunchUpdate explicitTrue =
            DebugSessionStarter.launchUpdate(Boolean.TRUE, false);
        assertTrue(explicitTrue.refusal != null && explicitTrue.refusal
            .contains(ToolGate.disabledMessage("update_database"))); //$NON-NLS-1$

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertFalse(DebugSessionStarter.launchUpdate((Boolean)null, false).update);
        assertTrue(DebugSessionStarter.launchUpdate(Boolean.TRUE, false).update);
    }

    /**
     * The launch answer names a preset-dropped update in {@code databaseUpdate} with a note saying
     * what to update with; an update that ran keeps reporting its outcome, and a call that updated
     * nothing and dropped nothing says nothing.
     */
    @Test
    public void theAnswerNamesTheDrop()
    {
        ToolResult skipped = ToolResult.success();
        DebugSessionStarter.putDatabaseUpdate(skipped, null, true);
        JsonObject answer = JsonParser.parseString(skipped.toJson()).getAsJsonObject();
        assertEquals(DebugSessionStarter.DATABASE_UPDATE_SKIPPED_BY_PRESET,
            answer.get("databaseUpdate").getAsString()); //$NON-NLS-1$
        assertTrue(answer.get("databaseUpdateNote").getAsString() //$NON-NLS-1$
            .contains("update_database")); //$NON-NLS-1$

        ToolResult ran = ToolResult.success();
        DebugSessionStarter.putDatabaseUpdate(ran,
            ApplicationUpdater.Result.failed("boom"), false); //$NON-NLS-1$
        assertEquals("FAILED", //$NON-NLS-1$
            JsonParser.parseString(ran.toJson()).getAsJsonObject()
                .get("databaseUpdate").getAsString()); //$NON-NLS-1$

        ToolResult silent = ToolResult.success();
        DebugSessionStarter.putDatabaseUpdate(silent, null, false);
        assertFalse(JsonParser.parseString(silent.toJson()).getAsJsonObject()
            .has("databaseUpdate")); //$NON-NLS-1$
    }
}
