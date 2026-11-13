/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
 * The route operations of {@code external_object_workshop} are gated by the list {@code execute}
 * runs.
 * <p>
 * R3 enters through {@code import_external_object}; {@code create} makes the same kind of project
 * and is gated the same way. Every operation but help asks the write door, and help is the one
 * operation that must answer while the door is off.
 * </p>
 */
public class AnExternalObjectOperationHonoursItsGateChecksTest
{
    /** The operations of the route registry this facade carries, plus the same-shaped create. */
    private static final String[] ROUTE_OPERATIONS = { "create", "import_external_object" }; //$NON-NLS-1$ //$NON-NLS-2$

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

    /** Every route operation names the write door and refuses with its exact text when it is off. */
    @Test
    public void everyRouteOperationRefusesWhenTheWriteDoorIsOff()
    {
        IMcpTool facade = new ExternalObjectWorkshopTool();
        for (String operation : ROUTE_OPERATIONS)
        {
            List<GateCheck> checks = ExternalObjectWorkshopTool.gateChecksOf(operation);
            assertFalse(operation + " publishes no check at all", checks.isEmpty()); //$NON-NLS-1$
            for (GateCheck check : checks)
            {
                assertEquals(operation + " must ask the facade's write door", //$NON-NLS-1$
                    GateCheck.Kind.WRITE_DOOR, check.kind());
                assertEquals(operation + " must ask the facade's write door", //$NON-NLS-1$
                    ExternalObjectWorkshopTool.WRITE_DOOR, check.name());
                assertEquals("operation=" + operation + " check=" + check, //$NON-NLS-1$ //$NON-NLS-2$
                    GateCheckRefusals.expected(check),
                    GateCheckRefusals.refusalOf(facade, "operation", operation, check)); //$NON-NLS-1$
            }
        }
    }

    /** Help is a read and carries no write-door check, so it answers while the door is off. */
    @Test
    public void helpCarriesNoWriteDoor()
    {
        assertTrue("help must not be gated by the write door", //$NON-NLS-1$
            ExternalObjectWorkshopTool.gateChecksOf("help").isEmpty()); //$NON-NLS-1$
    }
}
