/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Drives {@link JUnitXmlReader} against the report shapes YAXUnit and Vanessa emit: a green run, each
 * detail kind, multiple suites, suite-less case lists, and the DOCTYPE rejection that carries the
 * XXE guard.
 */
public class JUnitXmlReaderTest
{
    private static JUnitRunOutcome parse(String xml) throws Exception
    {
        return JUnitXmlReader.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void greenRunCountsEveryoneAsPassed() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<testsuite name=\"WholeRun\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">"
            + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\"/>"
            + "<testcase classname=\"Mod_Ledger\" name=\"keepsPrecision\"/>"
            + "<testcase classname=\"Mod_Ledger\" name=\"acceptsZero\"/>"
            + "</testsuite>";

        JUnitRunOutcome r = parse(xml);

        assertEquals(3, r.getTotal());
        assertEquals(0, r.getFailures());
        assertEquals(0, r.getErrors());
        assertEquals(0, r.getSkipped());
        assertEquals(3, r.getPassed());
        assertTrue(r.isPassed());
        assertTrue(r.getFailureDetails().isEmpty());
        assertTrue(r.getErrorDetails().isEmpty());
        assertTrue(r.getSkippedDetails().isEmpty());
    }

    @Test
    public void failureCarriesMessageAndTrace() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<testsuite name=\"LedgerChecks\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">"
            + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\">"
            + "<failure message=\"balance mismatch: 100 vs 120\">Ledger.Post : line 12\nLedger.Flush : line 30</failure>"
            + "</testcase>"
            + "</testsuite>";

        JUnitRunOutcome r = parse(xml);

        assertEquals(1, r.getTotal());
        assertEquals(1, r.getFailures());
        assertEquals(0, r.getPassed());
        assertFalse(r.isPassed());
        assertEquals(1, r.getFailureDetails().size());
        JUnitRunOutcome.TestCase detail = r.getFailureDetails().get(0);
        assertEquals("Mod_Ledger.roundsHalfUp", detail.name);
        assertEquals("balance mismatch: 100 vs 120", detail.message);
        assertNotNull(detail.trace);
        assertTrue(detail.trace.contains("line 12"));
    }

    @Test
    public void errorCapturedWithMessage() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<testsuite name=\"LedgerChecks\" tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\">"
            + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\">"
            + "<error message=\"Value is not a number\">Ledger.Post : line 27</error>"
            + "</testcase>"
            + "</testsuite>";

        JUnitRunOutcome r = parse(xml);

        assertEquals(1, r.getErrors());
        assertEquals(1, r.getErrorDetails().size());
        assertEquals("Value is not a number", r.getErrorDetails().get(0).message);
        assertFalse(r.isPassed());
    }

    @Test
    public void skipDoesNotTurnTheRunRed() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<testsuite name=\"LedgerChecks\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\">"
            + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\">"
            + "<skipped message=\"fixture not loaded\"/>"
            + "</testcase>"
            + "</testsuite>";

        JUnitRunOutcome r = parse(xml);

        assertEquals(1, r.getSkipped());
        assertEquals(1, r.getSkippedDetails().size());
        assertEquals("fixture not loaded", r.getSkippedDetails().get(0).message);
        assertTrue("a skip alone is still a pass", r.isPassed());
    }

    @Test
    public void multipleSuitesAggregate() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<testsuites>"
            + "<testsuite name=\"LedgerChecks\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\">"
            + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\"/>"
            + "<testcase classname=\"Mod_Ledger\" name=\"keepsPrecision\">"
            + "<failure message=\"tax rule missing\">Ledger.Post : line 12</failure>"
            + "</testcase>"
            + "</testsuite>"
            + "<testsuite name=\"BillingChecks\" tests=\"3\" failures=\"0\" errors=\"1\" skipped=\"1\">"
            + "<testcase classname=\"Mod_Billing\" name=\"roundsHalfUp\"/>"
            + "<testcase classname=\"Mod_Billing\" name=\"keepsPrecision\">"
            + "<error message=\"parser blew up\">Billing.Charge : line 40</error>"
            + "</testcase>"
            + "<testcase classname=\"Mod_Billing\" name=\"acceptsZero\">"
            + "<skipped message=\"waiting on data\"/>"
            + "</testcase>"
            + "</testsuite>"
            + "</testsuites>";

        JUnitRunOutcome r = parse(xml);

        assertEquals(5, r.getTotal());
        assertEquals(1, r.getFailures());
        assertEquals(1, r.getErrors());
        assertEquals(1, r.getSkipped());
        assertEquals(2, r.getPassed());
        assertFalse(r.isPassed());
    }

    @Test
    public void testCaseOutsideAnySuiteStillCounted() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<report>"
            + "<testcase classname=\"Mod_Ledger\" name=\"roundsHalfUp\"/>"
            + "<testcase classname=\"Mod_Ledger\" name=\"keepsPrecision\"/>"
            + "</report>";

        JUnitRunOutcome r = parse(xml);

        assertEquals(2, r.getTotal());
        assertTrue(r.isPassed());
    }

    @Test
    public void testCaseWithoutClassnameUsesBareName() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<testsuite name=\"LedgerChecks\" tests=\"1\" failures=\"1\">"
            + "<testcase name=\"bareCase\">"
            + "<failure message=\"unexpected total\">Ledger.Post : line 12</failure>"
            + "</testcase>"
            + "</testsuite>";

        JUnitRunOutcome r = parse(xml);

        assertEquals("bareCase", r.getFailureDetails().get(0).name);
    }

    @Test
    public void doctypeIsRejectedByXxeGuard()
    {
        String xml = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE probe [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]>"
            + "<testsuite name=\"GuardProbe\" tests=\"0\"/>";

        try
        {
            parse(xml);
            fail("DOCTYPE must be rejected");
        }
        catch (Exception expected)
        {
            // expected - the secure builder turns DOCTYPE into a parse error
        }
    }
}
