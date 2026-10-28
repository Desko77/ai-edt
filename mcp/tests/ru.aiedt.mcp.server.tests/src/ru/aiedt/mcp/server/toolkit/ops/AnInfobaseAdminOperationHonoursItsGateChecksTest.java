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
 * The route operations of {@code infobase_admin} are gated by the list {@code execute} runs.
 * <p>
 * The registry entries are R1 {@code start_client}, R5 {@code read_event_log} and the R6 step
 * {@code update_database}. Each is keyed on its own name, so turning that name off has to make
 * {@code execute} answer the gate's text and nothing else - the facade's own gate, not the
 * delegate's.
 * </p>
 */
public class AnInfobaseAdminOperationHonoursItsGateChecksTest
{
    /** The operations of the route registry this facade carries. */
    private static final String[] ROUTE_OPERATIONS = { "start_client", "read_event_log", //$NON-NLS-1$ //$NON-NLS-2$
        "update_database" }; //$NON-NLS-1$

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

    /** Every route operation names a preset check and refuses with its exact text when it is off. */
    @Test
    public void everyRouteOperationRefusesWhenItsPresetCheckIsOff()
    {
        IMcpTool facade = new InfobaseAdminFacadeTool();
        for (String operation : ROUTE_OPERATIONS)
        {
            List<GateCheck> checks = InfobaseAdminFacadeTool.gateChecksOf(operation);
            assertFalse(operation + " publishes no check at all", checks.isEmpty()); //$NON-NLS-1$
            for (GateCheck check : checks)
            {
                assertEquals(operation + " must be keyed on its own name", //$NON-NLS-1$
                    GateCheck.Kind.PRESET, check.kind());
                assertEquals(operation + " must be keyed on its own name", operation, check.name()); //$NON-NLS-1$
                assertEquals("operation=" + operation + " check=" + check, //$NON-NLS-1$ //$NON-NLS-2$
                    GateCheckRefusals.expected(check),
                    GateCheckRefusals.refusalOf(facade, "operation", operation, check)); //$NON-NLS-1$
            }
        }
    }
}
