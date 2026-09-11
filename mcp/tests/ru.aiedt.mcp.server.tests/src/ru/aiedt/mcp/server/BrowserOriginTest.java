/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * An origin is read whole: a host that begins with a loopback name is not a loopback host.
 */
public class BrowserOriginTest
{
    private static final String[] ACCEPTED = {
        "http://localhost", //$NON-NLS-1$
        "http://localhost:3000", //$NON-NLS-1$
        "HTTP://LOCALHOST", //$NON-NLS-1$
        "http://127.0.0.1:5173", //$NON-NLS-1$
        "http://[::1]:8080", //$NON-NLS-1$
        "https://localhost", //$NON-NLS-1$
        "vscode-webview://abc123"}; //$NON-NLS-1$

    private static final String[] REJECTED = {
        "http://localhost.evil.example", //$NON-NLS-1$
        "https://localhost.evil.example", //$NON-NLS-1$
        "http://127.0.0.1.evil.example", //$NON-NLS-1$
        "http://localhostx", //$NON-NLS-1$
        "http://user@localhost", //$NON-NLS-1$
        "http://localhost/path", //$NON-NLS-1$
        "http://localhost?x=1", //$NON-NLS-1$
        "http://localhost#f", //$NON-NLS-1$
        "http://localhost:99999", //$NON-NLS-1$
        "http://localhost:0", //$NON-NLS-1$
        "http://localhost:99999999999999", //$NON-NLS-1$
        "file:///c:/page.html", //$NON-NLS-1$
        "vscode-webview://", //$NON-NLS-1$
        "ftp://localhost", //$NON-NLS-1$
        "http://[bad", //$NON-NLS-1$
        "not an origin at all", //$NON-NLS-1$
        ""}; //$NON-NLS-1$

    @Test
    public void aLoopbackOriginIsAccepted()
    {
        for (String origin : ACCEPTED)
        {
            assertTrue(origin, BrowserOrigin.accepted(origin, false));
        }
    }

    @Test
    public void anythingElseIsNot()
    {
        for (String origin : REJECTED)
        {
            assertFalse(origin, BrowserOrigin.accepted(origin, true));
        }
    }

    @Test
    public void aMissingOriginIsAcceptedOnlyWhenTheOperatorSaysSo()
    {
        assertFalse(BrowserOrigin.accepted(BrowserOrigin.NULL_ORIGIN, false));
        assertTrue(BrowserOrigin.accepted(BrowserOrigin.NULL_ORIGIN, true));
    }

    @Test
    public void theNullSettingOpensNothingElse()
    {
        for (String origin : REJECTED)
        {
            assertFalse(origin, BrowserOrigin.accepted(origin, true));
        }
    }
}
