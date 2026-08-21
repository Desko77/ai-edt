/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Guards the rule for what a release actually breaks in an extension.
 * <p>
 * The value of this check is entirely in what it does NOT report. An extension author faced with a
 * list where a renamed parameter sits beside a removed one will read neither: the real break is
 * buried among differences that change nothing. So harmless differences are separated from breaking
 * ones, and both are shown.
 * </p>
 */
public class MethodSignatureTest
{
    private static MethodSignature of(String name, boolean exported, String... params)
    {
        MethodSignature signature = new MethodSignature(name, exported);
        for (String param : params)
        {
            boolean byValue = param.startsWith("!");
            boolean hasDefault = param.endsWith("=");
            String bare = param.replace("!", "").replace("=", "");
            signature.params.add(new MethodSignature.Param(bare, byValue, hasDefault));
        }
        return signature;
    }

    @Test
    public void aParameterAppearingBreaksTheBinding()
    {
        List<String> breaks = MethodSignature.whatBreaks(of("Handle", true, "Source"),
            of("Handle", true, "Source", "Cancel"));
        assertEquals(breaks.toString(), 1, breaks.size());
        assertTrue(breaks.get(0), breaks.get(0).contains("1 -> 2"));
    }

    @Test
    public void aParameterDisappearingBreaksItToo()
    {
        List<String> breaks = MethodSignature.whatBreaks(of("Handle", true, "Source", "Cancel"),
            of("Handle", true, "Source"));
        assertEquals(breaks.toString(), 1, breaks.size());
    }

    @Test
    public void aParameterThatStoppedBeingPassedByValueBreaksIt()
    {
        // The handler was written to be able to change it, or written knowing it could not. Either
        // way it is now called with something other than what it expects.
        List<String> breaks = MethodSignature.whatBreaks(of("Handle", true, "!Source"),
            of("Handle", true, "Source"));
        assertEquals(breaks.toString(), 1, breaks.size());
        assertTrue(breaks.get(0), breaks.get(0).contains("by value"));
    }

    @Test
    public void unexportingTheMethodBreaksIt()
    {
        List<String> breaks =
            MethodSignature.whatBreaks(of("Handle", true, "Source"), of("Handle", false, "Source"));
        assertEquals(breaks.toString(), 1, breaks.size());
        assertTrue(breaks.get(0), breaks.get(0).contains("no longer exported"));
    }

    @Test
    public void arenamedParameterBreaksNothingAndIsReportedSeparately()
    {
        // The binding is by position. Reporting this as a break would bury the real ones, which is
        // how a list of findings stops being read at all.
        MethodSignature was = of("Handle", true, "Source");
        MethodSignature now = of("Handle", true, "Object");
        assertTrue(MethodSignature.whatBreaks(was, now).isEmpty());

        List<String> harmless = MethodSignature.whatChangedHarmlessly(was, now);
        assertEquals(harmless.toString(), 1, harmless.size());
        assertTrue(harmless.get(0), harmless.get(0).contains("renamed"));
    }

    @Test
    public void aParameterGainingADefaultBreaksNothing()
    {
        MethodSignature was = of("Handle", true, "Source");
        MethodSignature now = of("Handle", true, "Source=");
        assertTrue(MethodSignature.whatBreaks(was, now).isEmpty());
        assertTrue(MethodSignature.whatChangedHarmlessly(was, now).get(0).contains("gained"));
    }

    @Test
    public void anIdenticalSignatureReportsNothingAtAll()
    {
        MethodSignature same = of("Handle", true, "!Source", "Cancel=");
        assertTrue(MethodSignature.whatBreaks(same, of("Handle", true, "!Source", "Cancel="))
            .isEmpty());
        assertTrue(MethodSignature
            .whatChangedHarmlessly(same, of("Handle", true, "!Source", "Cancel=")).isEmpty());
    }

    @Test
    public void aMissingSideIsNotReportedAsADifference()
    {
        // A target that is gone is a finding of its own, made by the caller. Turning it into a
        // signature difference here would report the same break twice under two names.
        assertTrue(MethodSignature.whatBreaks(of("Handle", true, "Source"), null).isEmpty());
        assertTrue(MethodSignature.whatBreaks(null, of("Handle", true, "Source")).isEmpty());
    }

    @Test
    public void aSignatureRendersTheWayItIsWritten()
    {
        String rendered = of("Handle", true, "!Source", "Cancel=").render();
        assertTrue(rendered, rendered.contains("Знач Source"));
        assertTrue(rendered, rendered.contains("Cancel = ..."));
        assertTrue(rendered, rendered.contains("Экспорт"));
    }
}
