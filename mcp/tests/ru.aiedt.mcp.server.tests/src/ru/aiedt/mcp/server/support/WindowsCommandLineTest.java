/*
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** The quoting Windows applies to the one command-line string {@code CreateProcessW} takes. */
public class WindowsCommandLineTest
{
    @Test
    public void aPlainArgumentIsWrittenAsItIs()
    {
        assertEquals("ENTERPRISE", WindowsCommandLine.join(List.of("ENTERPRISE"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a\\b", WindowsCommandLine.join(List.of("a\\b"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aPathWithSpacesIsQuoted()
    {
        assertEquals("\"C:\\Program Files\\1cv8.exe\"", //$NON-NLS-1$
            WindowsCommandLine.join(List.of("C:\\Program Files\\1cv8.exe"))); //$NON-NLS-1$
    }

    @Test
    public void anEmptyArgumentIsAnEmptyQuotedToken()
    {
        assertEquals("\"\"", WindowsCommandLine.join(List.of(""))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEmbeddedQuoteWithoutWhitespaceIsWrittenAsItIs()
    {
        assertEquals("a\"b\"c", WindowsCommandLine.join(List.of("a\"b\"c"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theConnectionStringWithoutWhitespaceIsWrittenAsItIs()
    {
        assertEquals("File=\"C:\\base\";", //$NON-NLS-1$
            WindowsCommandLine.join(List.of("File=\"C:\\base\";"))); //$NON-NLS-1$
    }

    @Test
    public void aConnectionStringWithSpacesKeepsInnerQuotesUnescaped()
    {
        assertEquals("\"File=\"C:\\Program Files\\1C\\base\";\"", //$NON-NLS-1$
            WindowsCommandLine.join(List.of("File=\"C:\\Program Files\\1C\\base\";"))); //$NON-NLS-1$
    }

    @Test
    public void aServerReferenceWithSpacesKeepsInnerQuotesUnescaped()
    {
        assertEquals("\"Srvr=\"cluster host\";Ref=\"base name\";\"", //$NON-NLS-1$
            WindowsCommandLine.join(List.of("Srvr=\"cluster host\";Ref=\"base name\";"))); //$NON-NLS-1$
    }

    @Test
    public void spaceAndQuoteAreWrappedWithoutEscapingTheQuote()
    {
        assertEquals("\"a b\"c\"", WindowsCommandLine.join(List.of("a b\"c"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTrailingBackslashWithoutWhitespaceIsNotDoubled()
    {
        assertEquals("C:\\dir\\", WindowsCommandLine.join(List.of("C:\\dir\\"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTabQuotesTheArgument()
    {
        assertEquals("\"a\tb\"", WindowsCommandLine.join(List.of("a\tb"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCyrillicPathWithSpaceIsQuoted()
    {
        assertEquals("\"C:\\Путь с пробелом\\ИБ\"", //$NON-NLS-1$
            WindowsCommandLine.join(List.of("C:\\Путь с пробелом\\ИБ"))); //$NON-NLS-1$
    }

    @Test
    public void aVaParamsArgumentWithSpaceIsQuoted()
    {
        String value = "StartFeaturePlayer;VAParams=C:\\own client dir\\VAParams.json"; //$NON-NLS-1$
        assertEquals("\"" + value + "\"", WindowsCommandLine.join(List.of(value))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theLivePayloadWritesTheConnectionStringWithoutAnOuterQuote()
    {
        String exe = "C:\\Program Files\\1C\\1cv8.exe"; //$NON-NLS-1$
        String connection = "File=\"E:\\EDT\\AI-EDT-1.0-acceptance-artifacts-20261114\\infobase\";"; //$NON-NLS-1$
        String epf = "C:\\own client dir\\VanessaAutomation.epf"; //$NON-NLS-1$
        String params = "C:\\own client dir\\VAParams.json"; //$NON-NLS-1$
        String expected = "\"" + exe + "\" ENTERPRISE /IBConnectionString " + connection //$NON-NLS-1$
            + " /DisableStartupMessages /TESTMANAGER /Execute \"" + epf //$NON-NLS-1$
            + "\" /C \"StartFeaturePlayer;VAParams=" + params + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected, WindowsCommandLine.join(List.of(exe, "ENTERPRISE", //$NON-NLS-1$
            "/IBConnectionString", connection, "/DisableStartupMessages", "/TESTMANAGER", //$NON-NLS-1$ //$NON-NLS-2$
            "/Execute", epf, "/C", "StartFeaturePlayer;VAParams=" + params))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTrailingBackslashDoesNotEscapeTheClosingQuote()
    {
        assertEquals("\"a b\\\\\"", WindowsCommandLine.join(List.of("a b\\"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void eachArgumentGetsOneSeparatingSpace()
    {
        assertEquals("exe ENTERPRISE /Execute", //$NON-NLS-1$
            WindowsCommandLine.join(List.of("exe", "ENTERPRISE", "/Execute"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void thePolicyRefusesEveryMachineWithoutTheBackend()
    {
        String nonWindows = OwnedProcessContainments.whyUnavailable("Linux", true, true); //$NON-NLS-1$
        assertNotNull(nonWindows);
        assertTrue(nonWindows.contains("not started")); //$NON-NLS-1$
        assertTrue(nonWindows.toLowerCase().contains("windows")); //$NON-NLS-1$

        String noJna = OwnedProcessContainments.whyUnavailable("Windows 11", true, false); //$NON-NLS-1$
        assertNotNull(noJna);
        assertTrue(noJna.contains("JNA")); //$NON-NLS-1$

        String thin = OwnedProcessContainments.whyUnavailable("Windows 11", false, true); //$NON-NLS-1$
        assertNotNull(thin);
        assertTrue(thin.contains("64-bit")); //$NON-NLS-1$

        assertNull(OwnedProcessContainments.whyUnavailable("Windows 11", true, true)); //$NON-NLS-1$
    }

    @Test
    public void theLiveAnswerMatchesThePlatform()
    {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (windows)
        {
            // The native tests exercise the backend when it is available; the policy is tested
            // above. This test only speaks for a machine that cannot offer it at all.
            return;
        }
        assertFalse(OwnedProcessContainments.isAvailable());
        assertNotNull(OwnedProcessContainments.unavailableReason());
    }
}
