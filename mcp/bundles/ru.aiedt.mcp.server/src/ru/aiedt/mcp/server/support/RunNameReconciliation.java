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
 * A name is matched against a name in the report whole, ignoring case, or by its tail after a dot:
 * {@code Тест_Сумма} and {@code МодульТестов.Тест_Сумма} are one name, so a call may name a test with
 * its module or without it. 1C identifiers do not distinguish case, and neither does this.
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
        /** The names the report does not carry, in the order the call gave them. */
        public final List<String> missing;

        /** How many distinct names the call gave. */
        public final int namedCount;

        /** How many of them the report carries. */
        public final int foundCount;

        /**
         * @param missing the names the report does not carry
         * @param namedCount how many distinct names the call gave
         */
        Result(List<String> missing, int namedCount)
        {
            this.missing = Collections.unmodifiableList(missing);
            this.namedCount = namedCount;
            this.foundCount = namedCount - missing.size();
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
            return "The report carries no test, suite or class named " + String.join(", ", missing) //$NON-NLS-1$
                + ": " + missing.size() + " of the " + namedCount //$NON-NLS-1$ //$NON-NLS-2$
                + " names given in tests / modules. A name is matched against every test name, suite" //$NON-NLS-1$
                + " name and class name the report carries, so one listed here is not registered in" //$NON-NLS-1$
                + " the module the run was given, or is not found under the name the call used."; //$NON-NLS-1$
        }
    }

    /**
     * Looks every name the call gave up in the report.
     *
     * @param named the names carried by the {@code tests} and {@code modules} filters, as the call
     *            gave them; <code>null</code> and blank entries are ignored, and a name given twice
     *            is one name
     * @param namesInReport what the report carries (see
     *            {@link JUnitRunOutcome#getNamesInReport()})
     * @return what the report says about them
     */
    public static Result reconcile(List<String> named, Collection<String> namesInReport)
    {
        List<String> missing = new ArrayList<>();
        int counted = 0;
        if (named != null)
        {
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
                if (!carried(trimmed, namesInReport))
                {
                    missing.add(trimmed);
                }
            }
        }
        return new Result(missing, counted);
    }

    /**
     * Whether the report carries a name for what the call named.
     *
     * @param named one name the call gave
     * @param namesInReport what the report carries
     * @return whether one of them is that name
     */
    private static boolean carried(String named, Collection<String> namesInReport)
    {
        if (namesInReport == null)
        {
            return false;
        }
        for (String candidate : namesInReport)
        {
            if (sameName(named, candidate))
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
