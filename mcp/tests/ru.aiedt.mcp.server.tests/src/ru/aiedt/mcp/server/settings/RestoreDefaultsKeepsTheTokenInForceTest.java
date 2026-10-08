/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * What Restore Defaults does to the bearer token.
 * <p>
 * The token is the one field on the tab that is not a setting with a shipped value. It is the
 * credential in force, and the shipped value is the empty string, which a save reads as "make me a
 * new one". A page restored and saved would therefore rotate the token of every client configured
 * for it, with nothing on the page having said so - and the clients answer 401 from then on.
 * </p>
 * <p>
 * The check is the round trip the user makes: restore, save, and read the token the server would ask
 * a request for.
 * </p>
 */
public class RestoreDefaultsKeepsTheTokenInForceTest
{
    /** A token as a workspace that has been used would hold it. */
    private static final String IN_USE = "4f1c0b7d2a9e6c5b8d3f1a70e2c4b6d8"; //$NON-NLS-1$

    @Test
    public void theTokenTheClientsCarrySurvivesARestoreAndASave()
    {
        try (AGeneralTabOnAShell tab = AGeneralTabOnAShell.open(PrefKeys.PREF_AUTH_TOKEN, IN_USE))
        {
            tab.restoreDefaults();
            assertNull("the restored page has to be savable", tab.save()); //$NON-NLS-1$

            assertEquals("the token every client already carries is still the one in force", //$NON-NLS-1$
                IN_USE, McpAuth.activeToken());
        }
    }
}
