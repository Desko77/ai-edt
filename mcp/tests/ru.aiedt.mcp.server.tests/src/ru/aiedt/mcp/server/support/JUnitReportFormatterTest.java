/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Verifies the report shape {@link JUnitReportFormatter} emits: the summary table is always present,
 * the verdict follows the counters, detail sections appear only when they have content, and a trace
 * lands inside a code fence.
 */
public class JUnitReportFormatterTest
{
    private static JUnitRunOutcome parse(String xml) throws Exception
    {
        return JUnitXmlReader.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void headerAndSummaryTableAlwaysPresent() throws Exception
    {
        JUnitRunOutcome r = parse("<testsuite name=\"LedgerChecks\" tests=\"0\"/>");
        String md = JUnitReportFormatter.format(r);

        assertTrue(md.contains("# Test run"));
        assertTrue(md.contains("## Totals"));
        assertTrue(md.contains("| Total"));
        assertTrue(md.contains("| Passed"));
        assertTrue(md.contains("| Failed"));
        assertTrue(md.contains("| Errors"));
        assertTrue(md.contains("| Skipped"));
    }

    @Test
    public void greenRunShowsPassedAndNoDetailSections() throws Exception
    {
        JUnitRunOutcome r = parse(
            "<testsuite name=\"LedgerChecks\" tests=\"2\" failures=\"0\" errors=\"0\">"
                + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\"/>"
                + "<testcase classname=\"Mod_Ledger\" name=\"keepsPrecision\"/>"
                + "</testsuite>");

        String md = JUnitReportFormatter.format(r);

        assertTrue(md.contains("**Verdict: green**"));
        assertFalse(md.contains("## Failures"));
        assertFalse(md.contains("## Errors"));
        assertFalse(md.contains("## Skipped"));
    }

    @Test
    public void redRunRendersEveryDetailSection() throws Exception
    {
        JUnitRunOutcome r = parse(
            "<testsuite name=\"LedgerChecks\" tests=\"3\" failures=\"1\" errors=\"1\" skipped=\"1\">"
                + "<testcase classname=\"Mod_Ledger\" name=\"rejectsNegative\">"
                + "<failure message=\"total is off by 20\">Ledger.Post : line 12</failure>"
                + "</testcase>"
                + "<testcase classname=\"Mod_Ledger\" name=\"divideByZero\">"
                + "<error message=\"division by zero\">Billing.Charge : line 40</error>"
                + "</testcase>"
                + "<testcase classname=\"Mod_Ledger\" name=\"pendingFixture\">"
                + "<skipped message=\"awaiting sample data\"/>"
                + "</testcase>"
                + "</testsuite>");

        String md = JUnitReportFormatter.format(r);

        assertTrue(md.contains("**Verdict: red**"));
        assertTrue(md.contains("## Failures"));
        assertTrue(md.contains("### Mod_Ledger.rejectsNegative"));
        assertTrue(md.contains("total is off by 20"));
        assertTrue(md.contains("Ledger.Post : line 12"));
        assertTrue(md.contains("## Errors"));
        assertTrue(md.contains("### Mod_Ledger.divideByZero"));
        assertTrue(md.contains("division by zero"));
        assertTrue(md.contains("## Skipped"));
        assertTrue(md.contains("Mod_Ledger.pendingFixture"));
        assertTrue(md.contains("awaiting sample data"));
    }

    @Test
    public void traceGoesInsideAFencedCodeBlock() throws Exception
    {
        JUnitRunOutcome r = parse(
            "<testsuite name=\"LedgerChecks\" tests=\"1\" failures=\"1\">"
                + "<testcase classname=\"Mod_Ledger\" name=\"singleCase\">"
                + "<failure message=\"tax rule missing\">multi\nline\ntrace</failure>"
                + "</testcase>"
                + "</testsuite>");

        String md = JUnitReportFormatter.format(r);

        assertTrue(md.contains("```\nmulti\nline\ntrace\n```"));
    }
}
