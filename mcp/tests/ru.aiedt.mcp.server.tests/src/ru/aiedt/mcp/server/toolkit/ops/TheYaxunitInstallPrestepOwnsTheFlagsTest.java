/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlags;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlagsResult;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ListResult;

/**
 * The YAxUnit install pre-step lowers and confirms the engine flags on both install paths, skips
 * the whole flags step when explicitly asked, and never reaches a launch after a flags refusal.
 * <p>
 * The designer-session implementation has its own test for the read, single write and control
 * read. These tests hold the orchestration around that implementation without an infobase or a
 * download: install comes before flags, already-installed still reaches flags, and a failure is
 * returned as the facade's structured error with both last-read values.
 * </p>
 */
public class TheYaxunitInstallPrestepOwnsTheFlagsTest
{
    private IPreferenceStore store;

    private String presetBefore;

    /** Gives direct facade calls a stable preset and remembers the setting they replace. */
    @Before
    public void useTheCanonicalPreset()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CANONICAL.name());
    }

    /** Restores the preference changed for the direct facade calls. */
    @After
    public void restoreThePreset()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /** A fresh install is followed by the flags step before the facade tries to launch. */
    @Test
    public void aFreshInstallRunsBeforeTheFlagsAndTheLaunchRunsOnlyAfterThem()
    {
        StubSteps steps = new StubSteps();
        steps.listed = listing();
        steps.installed = YaxunitTestsTool.InstallOutcome.done("Installed engine.cfe."); //$NON-NLS-1$
        steps.lowered = flags(true, false, false, null);

        JsonObject answer = JsonParser.parseString(new YaxunitTestsTool().executeWithInstallSteps(
            params("installYaxunit", "true"), steps)).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("list", "install", "flags"), steps.events); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the runner's refusal must stay an error after the summary is merged: " //$NON-NLS-1$
            + answer, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Installed engine.cfe; safe mode off; unsafe action protection off.", //$NON-NLS-1$
            answer.get("installYaxunit").getAsString()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("NoSuchProjectAnywhere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An already installed engine still has its flags handled and is not downloaded again. */
    @Test
    public void theAlreadyInstalledPathStillLowersAndConfirmsTheFlags()
    {
        StubSteps steps = new StubSteps();
        steps.listed = listing("YAxUnit"); //$NON-NLS-1$
        steps.lowered = flags(true, false, false, null);

        YaxunitTestsTool.InstallOutcome outcome = YaxunitTestsTool.ensureYaxunitInstalled(
            params(), true, steps);

        assertTrue(outcome.error, outcome.isOk());
        assertEquals(Arrays.asList("list", "flags"), steps.events); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outcome.summary.contains("already installed")); //$NON-NLS-1$
        assertTrue(outcome.summary.contains("safe mode off")); //$NON-NLS-1$
        assertTrue(outcome.summary.contains("unsafe action protection off")); //$NON-NLS-1$
    }

    /** The explicit opt-out neither reads nor writes the flags, on the installed path too. */
    @Test
    public void unsafeModeFalseDoesNotTouchTheFlags()
    {
        StubSteps steps = new StubSteps();
        steps.listed = listing("YAxUnit"); //$NON-NLS-1$

        YaxunitTestsTool.InstallOutcome outcome = YaxunitTestsTool.ensureYaxunitInstalled(
            params(), false, steps);

        assertTrue(outcome.error, outcome.isOk());
        assertEquals(Collections.singletonList("list"), steps.events); //$NON-NLS-1$
        assertFalse(outcome.summary.contains("safe mode")); //$NON-NLS-1$
    }

    /** A mismatching control read becomes an error with both actual flags and no launch. */
    @Test
    public void anUnconfirmedFlagStopsBeforeTheLaunchAndReportsBothActualValues()
    {
        StubSteps steps = new StubSteps();
        steps.listed = listing("YAxUnit"); //$NON-NLS-1$
        steps.lowered = flags(false, true, false,
            "Flags not confirmed: safe mode on, unsafe action protection off."); //$NON-NLS-1$

        JsonObject answer = JsonParser.parseString(new YaxunitTestsTool().executeWithInstallSteps(
            params("installYaxunit", "true"), steps)).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("failed", answer.get("installYaxunit").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("on", answer.get("safeMode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("off", answer.get("unsafeActionProtection").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("list", "flags"), steps.events); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the runner error would name the project if a launch had been attempted", //$NON-NLS-1$
            answer.get("error").getAsString().contains("NoSuchProjectAnywhere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Arguments for an install call against a deliberately nonexistent project.
     *
     * @param extra additional name/value pairs
     * @return the arguments
     */
    private static Map<String, String> params(String... extra)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mode", "run"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "NoSuchProjectAnywhere"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i + 1 < extra.length; i += 2)
        {
            params.put(extra[i], extra[i + 1]);
        }
        return params;
    }

    /**
     * A successful extension listing.
     *
     * @param names extension names
     * @return the listing
     */
    private static ListResult listing(String... names)
    {
        ListResult result = new ListResult();
        result.ok = true;
        result.extensions = Arrays.asList(names);
        return result;
    }

    /**
     * A flags-step result carrying the last values read.
     *
     * @param ok whether the step succeeded
     * @param safeMode the safe-mode value
     * @param protection the unsafe-action-protection value
     * @param error the failure text
     * @return the result
     */
    private static ExtensionFlagsResult flags(boolean ok, boolean safeMode, boolean protection,
        String error)
    {
        ExtensionFlags actual = new ExtensionFlags();
        actual.found = true;
        actual.safeMode = Boolean.valueOf(safeMode);
        actual.unsafeActionProtection = Boolean.valueOf(protection);
        ExtensionFlagsResult result = new ExtensionFlagsResult();
        result.ok = ok;
        result.error = error;
        result.flags = actual;
        return result;
    }

    /** External pre-step doors that record their order and return staged outcomes. */
    private static final class StubSteps implements YaxunitTestsTool.InstallSteps
    {
        /** Calls in their observed order. */
        final List<String> events = new ArrayList<>();

        /** Staged list result. */
        ListResult listed;

        /** Staged install result. */
        YaxunitTestsTool.InstallOutcome installed;

        /** Staged flags result. */
        ExtensionFlagsResult lowered;

        @Override
        public ListResult listExtensions(String projectName, String applicationId)
        {
            events.add("list"); //$NON-NLS-1$
            return listed;
        }

        @Override
        public YaxunitTestsTool.InstallOutcome installEngine(String projectName,
            String applicationId, String repo)
        {
            events.add("install"); //$NON-NLS-1$
            return installed;
        }

        @Override
        public ExtensionFlagsResult lowerEngineFlags(String projectName, String applicationId)
        {
            events.add("flags"); //$NON-NLS-1$
            return lowered;
        }
    }
}
