/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.GateCheck;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * The route actions of {@code launch_debugger} are gated by the list {@code execute} runs.
 * <p>
 * R2 enters through {@code action=launch}, with {@code debug_launch} as the compatibility spelling.
 * Both reach {@link DebugSessionStarter}, so both have to be keyed on its name and refuse with the
 * gate's exact text when that name is off.
 * </p>
 */
public class ADebuggerActionHonoursItsGateChecksTest
{
    /** The actions of the route registry this facade carries. */
    private static final String[] ROUTE_ACTIONS = { "launch", "debug_launch" }; //$NON-NLS-1$ //$NON-NLS-2$

    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

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
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /** Every route action names the debug starter's check and refuses with its exact text. */
    @Test
    public void everyRouteActionRefusesWhenItsPresetCheckIsOff()
    {
        IMcpTool facade = new LaunchDebuggerTool();
        for (String action : ROUTE_ACTIONS)
        {
            List<GateCheck> checks = LaunchDebuggerTool.gateChecksOf(action);
            assertFalse(action + " publishes no check at all", checks.isEmpty()); //$NON-NLS-1$
            for (GateCheck check : checks)
            {
                assertEquals(action + " must be keyed on the renamed tool", //$NON-NLS-1$
                    GateCheck.Kind.PRESET, check.kind());
                assertEquals(action + " must be keyed on the renamed tool", //$NON-NLS-1$
                    DebugSessionStarter.NAME, check.name());
                assertEquals("action=" + action + " check=" + check, //$NON-NLS-1$ //$NON-NLS-2$
                    GateCheckRefusals.expected(check),
                    GateCheckRefusals.refusalOf(facade, "action", action, check)); //$NON-NLS-1$
            }
        }
    }
}
