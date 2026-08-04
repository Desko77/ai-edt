/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a JUnit XML report says, once it has been read.
 * <p>
 * Four counters and three lists of the cases worth naming. Passing cases are counted and then
 * forgotten: nothing downstream enumerates them, and a run of ten thousand green tests should not cost
 * ten thousand objects to say so.
 * </p>
 * <p>
 * {@link JUnitXmlReader} fills one of these in and {@link JUnitReportFormatter} reads it back out;
 * the tools in between pass it around. The mutators are package-private for exactly that reason - the
 * parser is a neighbour, everyone else gets the read-only face.
 * </p>
 */
public final class JUnitRunOutcome
{
    /**
     * One test worth reporting on: a failure, an error or a skip.
     *
     * <p>
     * Plain public fields, no accessors. This is a record of what the XML said, not an object with
     * behaviour, and its readers - the formatter and the tests - read the three values directly.
     * </p>
     */
    public static final class TestCase
    {
        /** Test name, qualified with its module when the report gave one. */
        public final String name;

        /** The producer's message; may be <code>null</code> or empty. */
        public final String message;

        /** Stack trace or failure text; <code>null</code> for a skipped test, which has none. */
        public final String trace;

        /**
         * @param name the test name, as it should be shown
         * @param message the reported message, or <code>null</code>
         * @param trace the reported trace, or <code>null</code>
         */
        public TestCase(String name, String message, String trace)
        {
            this.name = name;
            this.message = message;
            this.trace = trace;
        }
    }

    private final List<TestCase> failureDetails = new ArrayList<>();
    private final List<TestCase> errorDetails = new ArrayList<>();
    private final List<TestCase> skippedDetails = new ArrayList<>();

    private int total;
    private int failures;
    private int errors;
    private int skipped;

    /**
     * @return how many tests ran in total
     */
    public int getTotal()
    {
        return total;
    }

    /**
     * @return how many tests failed an assertion
     */
    public int getFailures()
    {
        return failures;
    }

    /**
     * @return how many tests died on an unexpected error
     */
    public int getErrors()
    {
        return errors;
    }

    /**
     * @return how many tests were skipped
     */
    public int getSkipped()
    {
        return skipped;
    }

    /**
     * The tests that neither failed, errored nor were skipped.
     * <p>
     * Derived rather than counted, so a report whose counters do not add up cannot push this below
     * zero and hand a negative number to a client.
     * </p>
     *
     * @return the number of passing tests, never negative
     */
    public int getPassed()
    {
        return Math.max(total - failures - errors - skipped, 0);
    }

    /**
     * The verdict.
     * <p>
     * Skipped tests do not spoil it: a test that never ran has not disproved anything. Only a failure
     * or an error turns the run red.
     * </p>
     *
     * @return <code>true</code> when nothing failed and nothing errored
     */
    public boolean isPassed()
    {
        return failures == 0 && errors == 0;
    }

    /**
     * @return the failed tests, in report order; empty, never <code>null</code>, and not modifiable
     */
    public List<TestCase> getFailureDetails()
    {
        return Collections.unmodifiableList(failureDetails);
    }

    /**
     * @return the errored tests, in report order; empty, never <code>null</code>, and not modifiable
     */
    public List<TestCase> getErrorDetails()
    {
        return Collections.unmodifiableList(errorDetails);
    }

    /**
     * @return the skipped tests, in report order; empty, never <code>null</code>, and not modifiable
     */
    public List<TestCase> getSkippedDetails()
    {
        return Collections.unmodifiableList(skippedDetails);
    }

    /**
     * Adds one test suite's numbers to the running totals.
     *
     * @param tests tests in the suite
     * @param suiteFailures failures in the suite
     * @param suiteErrors errors in the suite
     * @param suiteSkipped skipped tests in the suite
     */
    void addToTotals(int tests, int suiteFailures, int suiteErrors, int suiteSkipped)
    {
        this.total += tests;
        this.failures += suiteFailures;
        this.errors += suiteErrors;
        this.skipped += suiteSkipped;
    }

    /**
     * Replaces the total outright, for the report that has test cases but no suite to count them.
     *
     * @param total the number of tests that ran
     */
    void setTotal(int total)
    {
        this.total = total;
    }

    /**
     * @param testCase a test that failed an assertion
     */
    void addFailure(TestCase testCase)
    {
        failureDetails.add(testCase);
    }

    /**
     * @param testCase a test that died on an error
     */
    void addError(TestCase testCase)
    {
        errorDetails.add(testCase);
    }

    /**
     * @param testCase a test that was skipped
     */
    void addSkipped(TestCase testCase)
    {
        skippedDetails.add(testCase);
    }
}
