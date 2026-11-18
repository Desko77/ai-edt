/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ToolGate;

/**
 * {@code enableExternalObjectDump=true} writes the external-object project's dump setting, and a
 * preset that blocks writing refuses it before the setting is touched and before anything starts.
 * <p>
 * Debug &amp; Test keeps {@code launch_debugger} on and disables {@code launch_debugger_writes}.
 * The launch asks that door twice: once on entry, before the project is resolved, and once more
 * right before the dump service is told to switch generation on. The second ask is held here over a
 * recording dump service, so "the setting was not changed" is a count of {@code setEnabled} calls
 * rather than an inference from the answer.
 * </p>
 */
public class TheDumpSettingAsksTheWriteDoorTest
{
    private static final String NO_PROJECT = "NoSuchProjectAnywhere"; //$NON-NLS-1$

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
     * A dump service with generation switched off that records every {@code setEnabled} call.
     *
     * @param switchedOn where the calls are recorded
     * @return the service
     */
    private static IExternalObjectDumpSupport recordingDumps(List<Boolean> switchedOn)
    {
        boolean[] enabled = {false};
        return (IExternalObjectDumpSupport)Proxy.newProxyInstance(
            IExternalObjectDumpSupport.class.getClassLoader(),
            new Class<?>[] {IExternalObjectDumpSupport.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "isEnabled": //$NON-NLS-1$
                        return Boolean.valueOf(enabled[0]);
                    case "setEnabled": //$NON-NLS-1$
                        enabled[0] = ((Boolean)args[1]).booleanValue();
                        switchedOn.add((Boolean)args[1]);
                        return null;
                    case "validateDumpGeneration": //$NON-NLS-1$
                        return Status.OK_STATUS;
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "toString": //$NON-NLS-1$
                        return "recording dump support"; //$NON-NLS-1$
                    default:
                        return null;
                }
            });
    }

    private static IProject aProjectHandle()
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProject("DumpDoorProbe"); //$NON-NLS-1$
    }

    private static JsonObject call(IMcpToolCall tool, String... arguments)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return JsonParser.parseString(tool.execute(params)).getAsJsonObject();
    }

    /** The execute method of a tool, so the starter and the facade are called the same way. */
    private interface IMcpToolCall
    {
        String execute(Map<String, String> params);
    }

    /**
     * Under Debug &amp; Test the dump service is never told to switch generation on: the answer is
     * the door's refusal and {@code setEnabled} was not called.
     */
    @Test
    public void debugAndTestLeavesTheSettingAlone()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        List<Boolean> switchedOn = new ArrayList<>();
        String answer =
            DebugSessionStarter.dumpReadiness(aProjectHandle(), true, recordingDumps(switchedOn));
        assertNotNull("the launch went on under a preset that blocks writing", answer); //$NON-NLS-1$
        assertTrue("refused with something other than the door: " + answer, //$NON-NLS-1$
            answer.startsWith(ToolGate.writeDoorMessage(DebugSessionStarter.DUMP_WRITE_DOOR)));
        assertEquals("the dump setting was changed", 0, switchedOn.size()); //$NON-NLS-1$
    }

    /**
     * Under every preset that blocks writing a launch with {@code enableExternalObjectDump=true}
     * is refused on entry, through the starter and through the facade alike: the project named
     * does not exist, so an answer naming the door cannot have come from anywhere past it.
     */
    @Test
    public void everyWriteBlockingPresetRefusesTheLaunchOnEntry()
    {
        for (ToolProfile preset : Arrays.asList(ToolProfile.DEBUG_AND_TEST, ToolProfile.READ_ONLY,
            ToolProfile.CODE_REVIEW))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset.name());
            assertTrue(preset + " has to disable the door", //$NON-NLS-1$
                preset.getDisabledTools().contains(DebugSessionStarter.DUMP_WRITE_DOOR));
            JsonObject answer = call(new DebugSessionStarter()::execute,
                "projectName", NO_PROJECT, //$NON-NLS-1$
                "applicationId", "app-1", //$NON-NLS-1$ //$NON-NLS-2$
                "externalObjectName", "Probe", //$NON-NLS-1$ //$NON-NLS-2$
                "enableExternalObjectDump", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(preset + " let the launch answer as a success", //$NON-NLS-1$
                answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(preset + " refused with something other than the door: " + answer, //$NON-NLS-1$
                answer.get("error").getAsString() //$NON-NLS-1$
                    .startsWith(ToolGate.writeDoorMessage(DebugSessionStarter.DUMP_WRITE_DOOR)));
            assertTrue(preset + " has to say nothing ran: " + answer, //$NON-NLS-1$
                answer.get("nothingWasLaunchedOrUpdated").getAsBoolean()); //$NON-NLS-1$
        }

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        JsonObject throughFacade = call(new LaunchDebuggerTool()::execute,
            "action", "launch", //$NON-NLS-1$ //$NON-NLS-2$
            "launchConfigurationName", "NoSuchConfigurationAnywhere", //$NON-NLS-1$ //$NON-NLS-2$
            "enableExternalObjectDump", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the facade refused with something other than the door: " + throughFacade, //$NON-NLS-1$
            throughFacade.get("error").getAsString() //$NON-NLS-1$
                .startsWith(ToolGate.writeDoorMessage(DebugSessionStarter.DUMP_WRITE_DOOR)));
    }

    /**
     * Under a preset that allows writing {@code true} switches generation on, once, as before; the
     * launch is not refused by the door.
     */
    @Test
    public void aWritingPresetSwitchesTheSettingOnAsBefore()
    {
        for (ToolProfile preset : Arrays.asList(ToolProfile.ALL_TOOLS, ToolProfile.CANONICAL))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset.name());
            List<Boolean> switchedOn = new ArrayList<>();
            String answer =
                DebugSessionStarter.dumpReadiness(aProjectHandle(), true, recordingDumps(switchedOn));
            assertNull(preset + " refused a launch it allows: " + answer, answer); //$NON-NLS-1$
            assertEquals(preset + " has to switch generation on once", //$NON-NLS-1$
                Arrays.asList(Boolean.TRUE), switchedOn);

            JsonObject launch = call(new DebugSessionStarter()::execute,
                "projectName", NO_PROJECT, //$NON-NLS-1$
                "applicationId", "app-1", //$NON-NLS-1$ //$NON-NLS-2$
                "enableExternalObjectDump", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(preset + " met the door: " + launch, //$NON-NLS-1$
                launch.get("error").getAsString() //$NON-NLS-1$
                    .contains(ToolGate.writeDoorMessage(DebugSessionStarter.DUMP_WRITE_DOOR)));
        }
    }

    /**
     * {@code false} asks no door under any preset: the setting stays off, the answer is the old
     * "switched off" refusal, and a launch carrying it is not refused by the door.
     */
    @Test
    public void falseAsksNothingUnderAnyPreset()
    {
        for (ToolProfile preset : Arrays.asList(ToolProfile.ALL_TOOLS, ToolProfile.CANONICAL,
            ToolProfile.EDITING, ToolProfile.DEBUG_AND_TEST, ToolProfile.READ_ONLY,
            ToolProfile.CODE_REVIEW))
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, preset.name());
            List<Boolean> switchedOn = new ArrayList<>();
            String answer =
                DebugSessionStarter.dumpReadiness(aProjectHandle(), false, recordingDumps(switchedOn));
            assertNotNull(preset + " has to report the setting switched off", answer); //$NON-NLS-1$
            assertTrue(preset + " answered something other than the old refusal: " + answer, //$NON-NLS-1$
                answer.contains("Pass enableExternalObjectDump=true")); //$NON-NLS-1$
            assertEquals(preset + " changed the setting", 0, switchedOn.size()); //$NON-NLS-1$

            for (String value : Arrays.asList("false", null)) //$NON-NLS-1$
            {
                JsonObject launch = value == null
                    ? call(new DebugSessionStarter()::execute, "projectName", NO_PROJECT, //$NON-NLS-1$
                        "applicationId", "app-1") //$NON-NLS-1$ //$NON-NLS-2$
                    : call(new DebugSessionStarter()::execute, "projectName", NO_PROJECT, //$NON-NLS-1$
                        "applicationId", "app-1", "enableExternalObjectDump", value); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertFalse(preset + " met the door with " + value + ": " + launch, //$NON-NLS-1$ //$NON-NLS-2$
                    launch.get("error").getAsString() //$NON-NLS-1$
                        .contains(ToolGate.writeDoorMessage(DebugSessionStarter.DUMP_WRITE_DOOR)));
            }
        }
    }

    /**
     * The facade and its alias name the door, so the annotations a client reads say the launch
     * writes while the preset leaves it open.
     */
    @Test
    public void bothSurfacesNameTheDoor()
    {
        assertTrue(new LaunchDebuggerTool().getGatedWriteNames()
            .contains(DebugSessionStarter.DUMP_WRITE_DOOR));
        assertTrue(new DebugSessionStarter().getGatedWriteNames()
            .contains(DebugSessionStarter.DUMP_WRITE_DOOR));
    }
}
