/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Pins the three ways a {@link GateCheck} can refuse and the order {@link GateCheck#firstRefusal}
 * keeps.
 * <p>
 * The kinds are not interchangeable: {@code PRESET} answers from the disabled set alone and must
 * work for a capability no tool registers, {@code WRITE_DOOR} carries the door's own wording, and
 * {@code ENABLED} also demands that the name be registered - which is the difference the facades
 * depend on. Each refusal has to be byte-identical to the {@link ToolGate} method it stands for,
 * because a facade hands that text back unchanged.
 * </p>
 */
public class GateCheckTest
{
    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    private final List<String> probes = new ArrayList<>();

    @Before
    public void aStoreToChange()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
    }

    @After
    public void theStoreGoesBack()
    {
        for (String probe : probes)
        {
            McpToolCatalog.getInstance().unregister(probe);
        }
        probes.clear();
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /** A PRESET check answers for a capability name that no tool registers, from the set alone. */
    @Test
    public void aPresetCheckAsksTheDisabledSetOnly()
    {
        String capability = "gate_check_capability_" + System.nanoTime(); //$NON-NLS-1$
        disable(capability);

        GateCheck check = GateCheck.preset(capability);
        assertEquals(GateCheck.Kind.PRESET, check.kind());
        assertEquals(capability, check.name());
        assertEquals(ToolGate.disabledMessage(capability), check.refusal());
    }

    /** A PRESET check passes a name the active set does not carry. */
    @Test
    public void aPresetCheckPassesANameTheSetDoesNotCarry()
    {
        disable();
        assertNull(GateCheck.preset("gate_check_free_" + System.nanoTime()).refusal()); //$NON-NLS-1$
    }

    /** A WRITE_DOOR check returns the door's own wording, not the disabled-tool wording. */
    @Test
    public void aWriteDoorCheckUsesTheDoorWording()
    {
        String door = "gate_check_facade_writes"; //$NON-NLS-1$
        disable(door);

        GateCheck check = GateCheck.writeDoor(door);
        assertEquals(GateCheck.Kind.WRITE_DOOR, check.kind());
        assertEquals(door, check.name());
        assertEquals(ToolGate.writeDoorMessage(door), check.refusal());
        assertTrue("the door must not answer with the disabled-tool text", //$NON-NLS-1$
            !ToolGate.disabledMessage(door).equals(check.refusal()));
    }

    /** An ENABLED check refuses a name nothing registered, even when no preset disabled it. */
    @Test
    public void anEnabledCheckRefusesAnUnregisteredName()
    {
        disable();
        String name = "gate_check_absent_" + System.nanoTime(); //$NON-NLS-1$

        GateCheck check = GateCheck.enabled(name);
        assertEquals(GateCheck.Kind.ENABLED, check.kind());
        assertEquals(ToolGate.disabledMessage(name), check.refusal());
    }

    /** An ENABLED check refuses a registered tool the preset switched off. */
    @Test
    public void anEnabledCheckRefusesADisabledRegisteredTool()
    {
        disable();
        String name = registerAProbe();
        disable(name);

        assertEquals(ToolGate.disabledMessage(name), GateCheck.enabled(name).refusal());
    }

    /** An ENABLED check passes a tool that is both registered and enabled. */
    @Test
    public void anEnabledCheckPassesARegisteredEnabledTool()
    {
        disable();
        String name = registerAProbe();
        assertNull(GateCheck.enabled(name).refusal());
    }

    /** The first refusal wins, in the order the facade hands the list over. */
    @Test
    public void firstRefusalAnswersTheFirstRefusingCheck()
    {
        disable();
        String absent = "gate_check_absent_" + System.nanoTime(); //$NON-NLS-1$
        String disabled = "gate_check_disabled_" + System.nanoTime(); //$NON-NLS-1$
        disable(disabled);

        String absentFirst = GateCheck.firstRefusal(Arrays.asList(
            GateCheck.enabled(absent), GateCheck.preset(disabled)));
        assertEquals("the first check that refuses is the one that answers", //$NON-NLS-1$
            ToolGate.disabledMessage(absent), absentFirst);

        String disabledFirst = GateCheck.firstRefusal(Arrays.asList(
            GateCheck.preset(disabled), GateCheck.enabled(absent)));
        assertEquals("a different order answers the other check", //$NON-NLS-1$
            ToolGate.disabledMessage(disabled), disabledFirst);
    }

    /** No refusal at all is {@code null}, not an empty message. */
    @Test
    public void firstRefusalIsNullWhenEveryCheckPasses()
    {
        disable();
        assertNull(GateCheck.firstRefusal(Arrays.asList(
            GateCheck.preset("gate_check_one_" + System.nanoTime()), //$NON-NLS-1$
            GateCheck.preset("gate_check_two_" + System.nanoTime())))); //$NON-NLS-1$
    }

    /** Registers a probe and remembers it for cleanup. */
    private String registerAProbe()
    {
        String name = "gate_check_probe_" + System.nanoTime(); //$NON-NLS-1$
        McpToolCatalog.getInstance().register(new Probe(name));
        probes.add(name);
        return name;
    }

    /** Records exactly the given names as disabled; no preset is in force afterwards. */
    private static void disable(String... names)
    {
        Set<String> disabled = names.length == 0 ? Collections.emptySet()
            : Set.of(names);
        ToolSettingsStore.getInstance().setDisabledTools(disabled);
    }

    /** The least a tool can be, so the registry can answer for it. */
    private static final class Probe implements IMcpTool
    {
        private final String name;

        Probe(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "a gate-check probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "ran"; //$NON-NLS-1$
        }
    }
}
