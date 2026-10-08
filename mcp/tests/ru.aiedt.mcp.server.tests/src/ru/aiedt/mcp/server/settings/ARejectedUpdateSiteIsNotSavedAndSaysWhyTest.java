/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the user is told when a save cannot keep the update address they typed.
 * <p>
 * The page refuses OK while the address is rejected, but the Start and Restart buttons on the tab
 * call the same save directly - they have to commit the port before opening a socket - and so reach
 * it without passing that refusal. The save used to leave the five update settings out and answer
 * nothing at all, so the caller read the silence as success: the server started, and the reason the
 * settings had not moved was nowhere on screen.
 * </p>
 */
public class ARejectedUpdateSiteIsNotSavedAndSaysWhyTest
{
    /** An address the policy accepts, so the store starts from a usable state. */
    private static final String ACCEPTED_SITE = "https://updates.example.com/aiedt"; //$NON-NLS-1$

    /** The same address over plain http, which delivers executable code and is refused. */
    private static final String REFUSED_SITE = "http://updates.example.com/aiedt"; //$NON-NLS-1$

    @Test
    public void aSaveWithARefusedAddressAnswersWithTheReason()
    {
        try (AGeneralTabOnAShell tab = AGeneralTabOnAShell.open(PrefKeys.PREF_UPKEEP_SITE_URL,
            ACCEPTED_SITE))
        {
            // Typed rather than built: the point is a field the page has just rejected.
            tab.textHolding(ACCEPTED_SITE).setText(REFUSED_SITE);

            String refused = tab.save();

            assertNotNull("a save that cannot keep the address has to answer, because Start and " //$NON-NLS-1$
                + "Restart reach it without passing the page's own refusal", refused);
            assertTrue("and the answer has to name what is wrong with the address: " + refused, //$NON-NLS-1$
                refused.contains("http")); //$NON-NLS-1$
            assertEquals("the address that was refused is not written", ACCEPTED_SITE, //$NON-NLS-1$
                tab.store().getString(PrefKeys.PREF_UPKEEP_SITE_URL));
        }
    }
}
