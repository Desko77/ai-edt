/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
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
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * The install pre-step of {@code yaxunit_tests} asks the {@code install_extension} door before it
 * writes, and a preset that keeps the facade running still cannot install through it.
 * <p>
 * The facade sits in the applications group with both of its runner names, and Debug &amp; Test -
 * the one write-blocking preset that needs them - keeps all three on while disabling
 * {@code install_extension} by name. Before the door, {@code installYaxunit=true} under that
 * preset wrote the engine into the infobase and only then ran the tests: a write under a preset
 * that promises there will be none. The refusal here is proved to happen BEFORE the write by the
 * project it names: one that does not exist, so any answer but the gate's own wording would have
 * to come from the install path resolving it.
 * </p>
 * <p>
 * Read-only and Code Review switch the whole applications group off, so the facade itself is not
 * callable there - the router refuses the call before {@code execute} runs, and a direct call
 * finds the folded runner-name gate. Nothing installs under any of the three.
 * </p>
 */
public class AYaxunitInstallDoorRefusesUnderWriteBlockingPresetsTest
{
    private YaxunitTestsTool facade;

    private IPreferenceStore store;

    private String presetBefore;

    /**
     * Registers the facade as the live server does and remembers the preset the store held.
     */
    @Before
    public void theFacadeAndThePreset()
    {
        facade = new YaxunitTestsTool();
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(facade);
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
    }

    /**
     * Takes the facade back out and puts the preset back.
     */
    @After
    public void theFacadeAndThePresetGo()
    {
        McpToolCatalog.getInstance().unregister(YaxunitTestsTool.NAME);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /**
     * One call to the facade with the given arguments.
     *
     * @param arguments name/value pairs
     * @return the answer as parsed JSON
     */
    private JsonObject call(String... arguments)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return JsonParser.parseString(facade.execute(params)).getAsJsonObject();
    }

    /**
     * Under Debug &amp; Test - the facade and both runner names on, the install door off - both
     * modes refuse an install call with the door's own wording, and the answer says the step was
     * blocked rather than failed.
     */
    @Test
    public void bothModesAskTheInstallDoorBeforeTheyWrite()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        for (String mode : Arrays.asList("run", "debug")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject answer = call("mode", mode, //$NON-NLS-1$
                "installYaxunit", "true", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", "NoSuchProjectAnywhere", //$NON-NLS-1$ //$NON-NLS-2$
                "applicationId", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(mode + " let the install answer as a success", //$NON-NLS-1$
                answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(mode + " refused the install with something other than the door: " + answer, //$NON-NLS-1$
                answer.get("error").getAsString() //$NON-NLS-1$
                    .startsWith(ToolGate.disabledMessage("install_extension"))); //$NON-NLS-1$
            assertTrue(mode + " has to name the blocked step: " + answer, //$NON-NLS-1$
                "blocked by preset" //$NON-NLS-1$
                    .equals(answer.get("installYaxunit").getAsString())); //$NON-NLS-1$
        }
    }

    /**
     * The door names its writer: the catalogue reads the gated names of the facade, and the
     * install door is among them, so the annotations a client reads say the tool writes while the
     * preset leaves that door open.
     */
    @Test
    public void theFacadeNamesItsInstallDoor()
    {
        assertTrue("the install door has to be one of the facade's gated write names", //$NON-NLS-1$
            facade.getGatedWriteNames().contains("install_extension")); //$NON-NLS-1$
    }

    /**
     * Under the two presets that switch the applications group off wholesale the facade itself is
     * not callable: the router would refuse the call, and a direct one finds the folded
     * runner-name gate - either way no install runs.
     */
    @Test
    public void theWholeFacadeIsOffUnderReadOnlyAndCodeReview()
    {
        for (ToolProfile preset : Arrays.asList(ToolProfile.READ_ONLY, ToolProfile.CODE_REVIEW))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset.name());
            assertFalse(preset + " has to switch the whole facade off", //$NON-NLS-1$
                McpToolCatalog.getInstance().isToolEnabled(YaxunitTestsTool.NAME));
            JsonObject answer = call("mode", "run", //$NON-NLS-1$ //$NON-NLS-2$
                "installYaxunit", "true", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", "NoSuchProjectAnywhere", //$NON-NLS-1$ //$NON-NLS-2$
                "applicationId", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(preset + " let the call answer as a success", //$NON-NLS-1$
                answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(preset + " refused the call with something other than a preset gate: " + answer, //$NON-NLS-1$
                answer.get("error").getAsString() //$NON-NLS-1$
                    .startsWith(ToolGate.disabledMessage("run_yaxunit_tests"))); //$NON-NLS-1$
        }
    }

    /**
     * A call that does not ask for the install passes the door under Debug &amp; Test: the answer
     * is the runner's own - a launch-configuration error over a workspace without one - and not
     * the door's refusal, which is what a normal test run under that preset is.
     */
    @Test
    public void aRunWithoutTheInstallStepPassesTheDoor()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        JsonObject answer = call("mode", "run", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", "NoSuchProjectAnywhere", //$NON-NLS-1$ //$NON-NLS-2$
            "applicationId", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        String error = answer.get("error").getAsString(); //$NON-NLS-1$
        assertFalse("a run without installYaxunit must not meet the install door: " + error, //$NON-NLS-1$
            error.contains(ToolGate.disabledMessage("install_extension"))); //$NON-NLS-1$
        assertFalse("the runner answers its own error, not a success", //$NON-NLS-1$
            answer.get("success").getAsBoolean()); //$NON-NLS-1$
    }
}
