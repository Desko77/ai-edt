/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether the names a call gave a run are in the report that run produced.
 * <p>
 * The {@code tests} and {@code modules} filters travel to the launch configuration, and YAXUnit
 * writes the report of what it ran. The two need not agree: a name that no module registers is
 * dropped by the filter rather than refused, so a call naming three tests and one module can come
 * back with a green report that carries neither the module nor one of the tests, and the answer then
 * reads as if everything named had run and passed. The names the call gave are therefore looked up in
 * the report, and the ones that are not there are reported as the discrepancy they are.
 * </p>
 * <p>
 * A module is looked up among the suite and class names of the report, whole and ignoring case. A
 * test is looked up among the test names, whole, ignoring case, or by its tail after a dot:
 * {@code Тест_Сумма} and {@code МодульТестов.Тест_Сумма} are one test, so a call may name a test with
 * its module or without it. The two kinds are never matched across: a module is not found by a test
 * that happens to carry its name. 1C identifiers do not distinguish case, and neither does this.
 * </p>
 */
public final class RunNameReconciliation
{
    private RunNameReconciliation()
    {
        // utility
    }

    /**
     * What the report had to say about the names a call gave.
     */
    public static final class Result
    {
        /** The names the report does not carry, modules first, in the order the call gave them. */
        public final List<String> missing;

        /** The names of the {@code modules} filter the report does not carry. */
        public final List<String> missingModules;

        /** The names of the {@code tests} filter the report does not carry. */
        public final List<String> missingTests;

        /** How many distinct names the call gave. */
        public final int namedCount;

        /** How many of them the report carries. */
        public final int foundCount;

        /**
         * @param missingModules the module names the report does not carry
         * @param missingTests the test names the report does not carry
         * @param namedCount how many distinct names the call gave
         */
        Result(List<String> missingModules, List<String> missingTests, int namedCount)
        {
            List<String> all = new ArrayList<>(missingModules);
            all.addAll(missingTests);
            this.missing = Collections.unmodifiableList(all);
            this.missingModules = Collections.unmodifiableList(missingModules);
            this.missingTests = Collections.unmodifiableList(missingTests);
            this.namedCount = namedCount;
            this.foundCount = namedCount - all.size();
        }

        /**
         * @return whether the report carries a name for everything the call named
         */
        public boolean isComplete()
        {
            return missing.isEmpty();
        }

        /**
         * One sentence naming what is missing, what a name is matched against, and what a name that
         * matched nothing means. Written once so the refusal of the call and the text of the report
         * cannot drift apart.
         *
         * @return what to tell the caller; meant for a result that {@link #isComplete()} rejects
         */
        public String describe()
        {
            List<String> parts = new ArrayList<>();
            if (!missingModules.isEmpty())
            {
                parts.add("no suite or class named " + String.join(", ", missingModules) //$NON-NLS-1$ //$NON-NLS-2$
                    + " (modules)"); //$NON-NLS-1$
            }
            if (!missingTests.isEmpty())
            {
                parts.add("no test named " + String.join(", ", missingTests) + " (tests)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return "The report carries " + String.join(" and ", parts) + ": " + missing.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " of the " + namedCount + " names given in tests / modules. A module is matched" //$NON-NLS-1$ //$NON-NLS-2$
                + " against the suite and class names of the report, a test against its test names," //$NON-NLS-1$
                + " with or without the module, so one listed here is not registered in the module" //$NON-NLS-1$
                + " the run was given, or is not found under the name the call used."; //$NON-NLS-1$
        }
    }

    /**
     * Looks every name the call gave up in the report, modules among module names and tests among
     * test names.
     *
     * @param modules the names of the {@code modules} filter as the call gave them; <code>null</code>
     *            and blank entries are ignored, and a name given twice is one name
     * @param tests the names of the {@code tests} filter, read the same way
     * @param moduleNamesInReport the suite and class names of the report (see
     *            {@link JUnitRunOutcome#getModuleNamesInReport()})
     * @param testNamesInReport the test names of the report (see
     *            {@link JUnitRunOutcome#getTestNamesInReport()})
     * @return what the report says about them; missing modules come before missing tests
     */
    public static Result reconcile(List<String> modules, List<String> tests,
        Collection<String> moduleNamesInReport, Collection<String> testNamesInReport)
    {
        List<String> missingModules = new ArrayList<>();
        List<String> missingTests = new ArrayList<>();
        int counted = lookUp(modules, moduleNamesInReport, false, missingModules);
        counted += lookUp(tests, testNamesInReport, true, missingTests);
        return new Result(missingModules, missingTests, counted);
    }

    /**
     * Looks one filter's names up among the report names of the same kind.
     *
     * @param named the names the filter carries, possibly <code>null</code>
     * @param inReport the report names of that kind, possibly <code>null</code>
     * @param byTail whether a name also matches by its tail after a dot - true for tests only
     * @param missing where the names the report lacks are added, in the order given
     * @return how many distinct names the filter gave
     */
    private static int lookUp(List<String> named, Collection<String> inReport, boolean byTail,
        List<String> missing)
    {
        int counted = 0;
        if (named == null)
        {
            return counted;
        }
        Set<String> seen = new HashSet<>();
        for (String name : named)
        {
            if (name == null || name.trim().isEmpty())
            {
                continue;
            }
            String trimmed = name.trim();
            if (!seen.add(trimmed.toLowerCase(Locale.ROOT)))
            {
                continue;
            }
            counted++;
            if (!carried(trimmed, inReport, byTail))
            {
                missing.add(trimmed);
            }
        }
        return counted;
    }

    /**
     * Whether the report carries a name for what the call named.
     *
     * @param named one name the call gave
     * @param inReport the report names of the same kind
     * @param byTail whether a tail after a dot matches as well
     * @return whether one of them is that name
     */
    private static boolean carried(String named, Collection<String> inReport, boolean byTail)
    {
        if (inReport == null)
        {
            return false;
        }
        for (String candidate : inReport)
        {
            if (byTail ? sameName(named, candidate) : named.equalsIgnoreCase(candidate))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether two names are the same one.
     *
     * @param left one name
     * @param right the other name
     * @return whether they are equal ignoring case, or one is the tail of the other after a dot
     */
    static boolean sameName(String left, String right)
    {
        return left.equalsIgnoreCase(right) || isTail(left, right) || isTail(right, left);
    }

    /**
     * @param shorter the name that may be the tail
     * @param longer the name it may be the tail of
     * @return whether <code>longer</code> ends with a dot followed by <code>shorter</code>
     */
    private static boolean isTail(String shorter, String longer)
    {
        int from = longer.length() - shorter.length();
        return from > 0 && longer.charAt(from - 1) == '.'
            && longer.regionMatches(true, from, shorter, 0, shorter.length());
    }
}
