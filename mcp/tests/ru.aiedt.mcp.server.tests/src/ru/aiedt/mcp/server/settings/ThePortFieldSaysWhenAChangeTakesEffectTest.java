/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.eclipse.swt.widgets.Spinner;
import org.junit.Test;

/**
 * What the port field tells the user about the moment a change to it takes effect.
 * <p>
 * A port saved on this tab does not move a server that is already listening, and does not restart it
 * either: only a change on the Tools tab restarts one, or the Restart button under the field. The
 * page relies on the tooltips to say so, and this field was the one without any - a user could set a
 * new port, press OK, watch the page close, and go on being served on the old port with nothing on
 * screen having warned them.
 * </p>
 */
public class ThePortFieldSaysWhenAChangeTakesEffectTest
{
    /** A port nothing else on the machine listens on; only its number matters here. */
    private static final int CHOSEN_PORT = 45871;

    @Test
    public void thePortFieldNamesWhatDoesAndDoesNotMoveARunningServer()
    {
        try (AGeneralTabOnAShell tab = AGeneralTabOnAShell.open(PrefKeys.PREF_PORT,
            String.valueOf(CHOSEN_PORT)))
        {
            Spinner port = tab.spinnerHolding(CHOSEN_PORT);

            String tip = port.getToolTipText();
            assertNotNull("the port field has to say when a change to it takes effect", tip); //$NON-NLS-1$
            assertTrue("it has to name the Tools tab as what does restart a running server: " + tip, //$NON-NLS-1$
                tip.contains("Tools")); //$NON-NLS-1$
            assertTrue("and it has to name the Restart button the user has in front of them: " + tip, //$NON-NLS-1$
                tip.toLowerCase(Locale.ROOT).contains("restart")); //$NON-NLS-1$
        }
    }
}
