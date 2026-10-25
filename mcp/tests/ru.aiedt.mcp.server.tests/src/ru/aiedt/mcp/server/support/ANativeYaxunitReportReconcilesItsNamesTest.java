/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * How the names in a native YAXUnit report are reconciled against the tests and modules a call
 * named. The native producer decorates the suite name with the run context
 * ("SampleTests [ManagedClient]") and repeats the qualified test name as the case classname
 * ("SampleTests.TestOne"), with the bare method as the case name. Reconciliation must still answer
 * that the module SampleTests and the tests SampleTests.TestOne and SampleTests.TestTwo ran, must
 * keep naming what is genuinely absent, and the classic and generic JUnit shapes must keep reading
 * the way they always did. Every report below goes through {@link JUnitXmlReader#parse} and
 * {@link RunNameReconciliation#reconcile} - nothing is stubbed or prefilled.
 */
public class ANativeYaxunitReportReconcilesItsNamesTest
{
    /**
     * The anonymized shape the real engine emits: a green suite of two tests.
     */
    private static final String NATIVE_GREEN = "<testsuites>" //$NON-NLS-1$
        + "<testsuite name=\"SampleTests [ManagedClient]\" classname=\"SampleTests\" tests=\"2\"" //$NON-NLS-1$
        + " failures=\"0\" errors=\"0\" skipped=\"0\" package=\"TestExtension\" context=\"ManagedClient\">" //$NON-NLS-1$
        + "<testcase name=\"TestOne\" classname=\"SampleTests.TestOne\"/>" //$NON-NLS-1$
        + "<testcase name=\"TestTwo\" classname=\"SampleTests.TestTwo\"/>" //$NON-NLS-1$
        + "</testsuite></testsuites>"; //$NON-NLS-1$

    /**
     * Parses a report from its XML text.
     *
     * @param xml the report
     * @return what the report says
     * @throws Exception if the report cannot be read
     */
    private static JUnitRunOutcome parse(String xml) throws Exception
    {
        return JUnitXmlReader.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Registry row 1: the native green run carries every name the call gave - the module and both
     * qualified tests - with the suite total of two it stated.
     */
    @Test
    public void aNativeRunCarriesEveryNameTheCallGave() throws Exception
    {
        JUnitRunOutcome outcome = parse(NATIVE_GREEN);

        assertEquals(2, outcome.getTotal());
        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(
            Collections.singletonList("SampleTests"), //$NON-NLS-1$
            Arrays.asList("SampleTests.TestOne", "SampleTests.TestTwo"), //$NON-NLS-1$ //$NON-NLS-2$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertEquals(3, result.namedCount);
        assertEquals("every name the call gave is in the report: " + result.describe(), //$NON-NLS-1$
            3, result.foundCount);
        assertTrue("nothing may be reported missing: " + result.describe(), result.isComplete()); //$NON-NLS-1$
    }

    /**
     * Registry row 2: a module the native run never executed stays missing.
     */
    @Test
    public void anAbsentModuleStaysMissing() throws Exception
    {
        JUnitRunOutcome outcome = parse(NATIVE_GREEN);

        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(
            Arrays.asList("SampleTests", "AbsentModule"), null, //$NON-NLS-1$ //$NON-NLS-2$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertTrue("the absent module must be named missing: " + result.describe(), //$NON-NLS-1$
            result.missingModules.contains("AbsentModule")); //$NON-NLS-1$
        assertFalse(result.isComplete());
    }

    /**
     * Registry row 3: a test the native run never executed stays missing.
     */
    @Test
    public void anAbsentTestStaysMissing() throws Exception
    {
        JUnitRunOutcome outcome = parse(NATIVE_GREEN);

        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(null,
            Collections.singletonList("SampleTests.AbsentTest"), //$NON-NLS-1$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertTrue("the absent test must be named missing: " + result.describe(), //$NON-NLS-1$
            result.missingTests.contains("SampleTests.AbsentTest")); //$NON-NLS-1$
        assertFalse(result.isComplete());
    }

    /**
     * Registry row 4: a test qualified with a module that never ran stays missing even though the
     * bare method name exists under SampleTests - a lookup that matched on the raw method name
     * alone would accept the wrong module.
     */
    @Test
    public void aTestOfAnotherModuleStaysMissing() throws Exception
    {
        JUnitRunOutcome outcome = parse(NATIVE_GREEN);

        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(null,
            Collections.singletonList("OtherTests.TestOne"), //$NON-NLS-1$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertTrue("a bare method match must not stand for another module: " + result.describe(), //$NON-NLS-1$
            result.missingTests.contains("OtherTests.TestOne")); //$NON-NLS-1$
        assertFalse(result.isComplete());
    }

    /**
     * Registry row 5: the classic JUnit shape - suite named for the module, case classname equal to
     * the module and case name equal to the method - still matches the module and the qualified
     * test the call named.
     */
    @Test
    public void aClassicJunitReportStillMatches() throws Exception
    {
        String xml = "<testsuite name=\"SampleTests\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">" //$NON-NLS-1$
            + "<testcase classname=\"SampleTests\" name=\"TestOne\"/>" //$NON-NLS-1$
            + "</testsuite>"; //$NON-NLS-1$

        JUnitRunOutcome outcome = parse(xml);

        assertEquals(1, outcome.getTotal());
        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(
            Collections.singletonList("SampleTests"), //$NON-NLS-1$
            Collections.singletonList("SampleTests.TestOne"), //$NON-NLS-1$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertEquals(2, result.namedCount);
        assertEquals(2, result.foundCount);
        assertTrue(result.isComplete());
    }

    /**
     * Registry row 6: the generic JUnit shape keeps the full test name
     * "com.example.TestOne.TestOne" - the parsed names themselves are asserted, so a deduplication
     * that folded the name to its tail would be caught here and not only in reconciliation.
     */
    @Test
    public void aGenericJunitReportKeepsTheFullTestName() throws Exception
    {
        String xml = "<testsuite name=\"Suite\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">" //$NON-NLS-1$
            + "<testcase classname=\"com.example.TestOne\" name=\"TestOne\"/>" //$NON-NLS-1$
            + "</testsuite>"; //$NON-NLS-1$

        JUnitRunOutcome outcome = parse(xml);

        assertEquals(Collections.singleton("com.example.TestOne.TestOne"), //$NON-NLS-1$
            outcome.getTestNamesInReport());
        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(null,
            Collections.singletonList("com.example.TestOne.TestOne"), //$NON-NLS-1$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertTrue(result.isComplete());
        assertEquals(1, result.foundCount);
    }

    /**
     * Registry row 7: a report with no suite at all - a bare testcase carrying classname and name -
     * is still read, and its module and qualified test reconcile.
     */
    @Test
    public void aSuiteLessCaseStillReconciles() throws Exception
    {
        String xml = "<testsuites>" //$NON-NLS-1$
            + "<testcase classname=\"SampleTests\" name=\"TestOne\"/>" //$NON-NLS-1$
            + "</testsuites>"; //$NON-NLS-1$

        JUnitRunOutcome outcome = parse(xml);

        assertEquals(1, outcome.getTotal());
        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(
            Collections.singletonList("SampleTests"), //$NON-NLS-1$
            Collections.singletonList("SampleTests.TestOne"), //$NON-NLS-1$
            outcome.getModuleNamesInReport(), outcome.getTestNamesInReport());

        assertTrue(result.isComplete());
        assertEquals(2, result.foundCount);
    }

    /**
     * Registry row 8: a native run with one failure names the failed method once,
     * "SampleTests.TestOne" - not the doubled "SampleTests.TestOne.TestOne" that repeating the
     * qualified case classname over the bare method would produce - and the counters hold.
     */
    @Test
    public void aNativeFailureNamesTheQualifiedMethodOnce() throws Exception
    {
        String xml = "<testsuites>" //$NON-NLS-1$
            + "<testsuite name=\"SampleTests [ManagedClient]\" classname=\"SampleTests\" tests=\"2\"" //$NON-NLS-1$
            + " failures=\"1\" errors=\"0\" skipped=\"0\" package=\"TestExtension\" context=\"ManagedClient\">" //$NON-NLS-1$
            + "<testcase name=\"TestOne\" classname=\"SampleTests.TestOne\">" //$NON-NLS-1$
            + "<failure message=\"assertion failed\">at SampleTests.TestOne line 7</failure>" //$NON-NLS-1$
            + "</testcase>" //$NON-NLS-1$
            + "<testcase name=\"TestTwo\" classname=\"SampleTests.TestTwo\"/>" //$NON-NLS-1$
            + "</testsuite></testsuites>"; //$NON-NLS-1$

        JUnitRunOutcome outcome = parse(xml);

        assertEquals(2, outcome.getTotal());
        assertEquals(1, outcome.getFailures());
        assertEquals(0, outcome.getErrors());
        assertEquals(1, outcome.getFailureDetails().size());
        assertEquals("the failed method is named once, not doubled", //$NON-NLS-1$
            "SampleTests.TestOne", outcome.getFailureDetails().get(0).name); //$NON-NLS-1$
    }
}
