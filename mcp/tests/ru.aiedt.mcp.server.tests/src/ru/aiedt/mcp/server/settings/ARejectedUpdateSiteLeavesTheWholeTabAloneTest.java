/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * How far a save reaches when the update address it carries is refused.
 * <p>
 * Start and Restart call the tab's save directly - they have to commit the port before opening a
 * socket - so a refusal there reaches the user as a dialog rather than through the page's own
 * "cannot save" state. A refusal that arrived after the fields were written would leave a port, a
 * token and a dozen other settings changed by a save the user was told had not been kept.
 * </p>
 */
public class ARejectedUpdateSiteLeavesTheWholeTabAloneTest
{
    /** An address the policy accepts, so the store starts from a usable state. */
    private static final String ACCEPTED_SITE = "https://updates.example.com/aiedt"; //$NON-NLS-1$

    /** The same address over plain http, which delivers executable code and is refused. */
    private static final String REFUSED_SITE = "http://updates.example.com/aiedt"; //$NON-NLS-1$

    /** The port the tab is built on, and the one typed over it. */
    private static final int PORT_BEFORE = 12250;

    private static final int PORT_AFTER = 12251;

    @Test
    public void aRefusedAddressLeavesEveryOtherFieldAsItWas()
    {
        try (AGeneralTabOnAShell tab = AGeneralTabOnAShell.open(PrefKeys.PREF_UPKEEP_SITE_URL,
            ACCEPTED_SITE, PrefKeys.PREF_PORT, String.valueOf(PORT_BEFORE)))
        {
            tab.textHolding(ACCEPTED_SITE).setText(REFUSED_SITE);
            // Written before the update section on the way through the save, which is the point.
            tab.spinnerHolding(PORT_BEFORE).setSelection(PORT_AFTER);

            String refused = tab.save();

            assertNotNull("a save that cannot keep the address has to answer", refused); //$NON-NLS-1$
            assertEquals("nothing ahead of the refused address may be written: " + refused, //$NON-NLS-1$
                PORT_BEFORE, tab.store().getInt(PrefKeys.PREF_PORT));
        }
    }
}
