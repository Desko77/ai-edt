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
 * The R6 export operations of {@code config_io} are gated by the list {@code execute} runs.
 * <p>
 * {@code export_configuration_to_cf}, {@code export_database_configuration} and
 * {@code export_database_extension} are keyed on their own names. The operations that go through
 * {@code gatedRoute} are outside the route registry and keep their own second check in the branch;
 * this test does not pretend otherwise.
 * </p>
 */
public class AConfigIoOperationHonoursItsGateChecksTest
{
    /** The R6 export operations the facade carries. */
    private static final String[] ROUTE_OPERATIONS = { "export_configuration_to_cf", //$NON-NLS-1$
        "export_database_configuration", "export_database_extension" }; //$NON-NLS-1$ //$NON-NLS-2$

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
        IMcpTool facade = new ConfigIoFacadeTool();
        for (String operation : ROUTE_OPERATIONS)
        {
            List<GateCheck> checks = ConfigIoFacadeTool.gateChecksOf(operation);
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
