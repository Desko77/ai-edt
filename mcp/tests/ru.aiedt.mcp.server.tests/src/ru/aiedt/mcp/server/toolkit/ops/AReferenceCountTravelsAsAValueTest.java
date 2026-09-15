/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Holds the reference count to travelling as a value, and to saying how sure it is.
 * <p>
 * Two tools used to read the count out of the search's report by matching a heading. The heading was
 * reworded, both matched nothing, and {@code impact_analysis} answered "LOW (no references)" while
 * {@code object_summary} answered "unknown" - for objects with hundreds of references, with every
 * test green throughout. Nothing failed because nothing was asked.
 * </p>
 * <p>
 * So two things are held here: the count leaves the search as a number, and a number that is only a
 * floor is never handed out as a total.
 * </p>
 */
public class AReferenceCountTravelsAsAValueTest
{
    private static Object sink() throws Exception
    {
        Class<?> type = Class.forName("ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$Sink"); //$NON-NLS-1$
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void set(Object target, String field, Object value) throws Exception
    {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String field) throws Exception
    {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        return (T)declared.get(target);
    }

    private static Object resultOf(Object sink) throws Exception
    {
        Method toResult = sink.getClass().getDeclaredMethod("toResult", String.class); //$NON-NLS-1$
        toResult.setAccessible(true);
        return toResult.invoke(sink, "# Usages of Catalog.Products\n"); //$NON-NLS-1$
    }

    private static String certaintyOf(Object result) throws Exception
    {
        Field declared = result.getClass().getDeclaredField("certainty"); //$NON-NLS-1$
        declared.setAccessible(true);
        return String.valueOf(declared.get(result));
    }

    /** A walk that ran everywhere and stopped at nothing gives a total. */
    @Test
    public void aWalkThatFinishedGivesATotal() throws Exception
    {
        Object sink = sink();
        set(sink, "count", Integer.valueOf(17)); //$NON-NLS-1$
        Object result = resultOf(sink);
        assertEquals("COMPLETE", certaintyOf(result)); //$NON-NLS-1$
        Method isExact = result.getClass().getDeclaredMethod("isExact"); //$NON-NLS-1$
        assertTrue("a finished walk is the whole answer", (Boolean)isExact.invoke(result)); //$NON-NLS-1$
        Method why = result.getClass().getDeclaredMethod("whyNotExact"); //$NON-NLS-1$
        assertNull("and there is no reason to give", why.invoke(result)); //$NON-NLS-1$
    }

    /** The cap makes the number a floor, and the answer says so. */
    @Test
    public void aCappedWalkGivesAFloor() throws Exception
    {
        Object sink = sink();
        set(sink, "count", Integer.valueOf(100)); //$NON-NLS-1$
        set(sink, "capped", Boolean.TRUE); //$NON-NLS-1$
        Object result = resultOf(sink);
        assertEquals("TRUNCATED", certaintyOf(result)); //$NON-NLS-1$
        Method isExact = result.getClass().getDeclaredMethod("isExact"); //$NON-NLS-1$
        assertFalse("a capped walk is not the whole answer", (Boolean)isExact.invoke(result)); //$NON-NLS-1$
    }

    /** A project that could not be searched outranks the cap: the gap is wider than a cap. */
    @Test
    public void aProjectThatCouldNotBeSearchedOutranksTheCap() throws Exception
    {
        Object sink = sink();
        set(sink, "count", Integer.valueOf(100)); //$NON-NLS-1$
        set(sink, "capped", Boolean.TRUE); //$NON-NLS-1$
        java.util.List<String> projects = get(sink, "projectsNotSearched"); //$NON-NLS-1$
        projects.add("SisterExtension"); //$NON-NLS-1$
        Object result = resultOf(sink);
        assertEquals("PARTIAL", certaintyOf(result)); //$NON-NLS-1$
        Method why = result.getClass().getDeclaredMethod("whyNotExact"); //$NON-NLS-1$
        assertTrue("the answer names the project it could not search", //$NON-NLS-1$
            String.valueOf(why.invoke(result)).contains("SisterExtension")); //$NON-NLS-1$
    }

    /** The operator's stop outranks both: the walk ended where the operator said. */
    @Test
    public void theOperatorsStopOutranksEverythingButAFailure() throws Exception
    {
        Object sink = sink();
        set(sink, "count", Integer.valueOf(42)); //$NON-NLS-1$
        set(sink, "capped", Boolean.TRUE); //$NON-NLS-1$
        set(sink, "stopped", Boolean.TRUE); //$NON-NLS-1$
        java.util.List<String> projects = get(sink, "projectsNotSearched"); //$NON-NLS-1$
        projects.add("SisterExtension"); //$NON-NLS-1$
        Object result = resultOf(sink);
        assertEquals("CANCELLED", certaintyOf(result)); //$NON-NLS-1$
    }

    /** A search that never got to a count says so instead of handing out a zero. */
    @Test
    public void aSearchWithoutAnAnswerIsNotAZero() throws Exception
    {
        Object sink = sink();
        set(sink, "stopped", Boolean.TRUE); //$NON-NLS-1$
        Object result = resultOf(sink);
        assertEquals("FAILED", certaintyOf(result)); //$NON-NLS-1$
        Field count = result.getClass().getDeclaredField("count"); //$NON-NLS-1$
        count.setAccessible(true);
        assertEquals("a count that was never established is -1, not 0", -1, count.get(result)); //$NON-NLS-1$
        Method why = result.getClass().getDeclaredMethod("whyNotExact"); //$NON-NLS-1$
        assertNotNull("and it says why", why.invoke(result)); //$NON-NLS-1$
    }

    /**
     * The tool that grades impact holds no regular expression over another tool's report.
     * <p>
     * This is the defect itself, not a consequence of it: as long as the count is read out of prose,
     * the next rewording breaks it again and nothing goes red.
     * </p>
     */
    @Test
    public void theImpactToolReadsNoProse()
    {
        for (Field field : ImpactAnalysisTool.class.getDeclaredFields())
        {
            assertFalse("ImpactAnalysisTool." + field.getName() //$NON-NLS-1$
                + " is a Pattern: the count must come as a value, not out of a report", //$NON-NLS-1$
                Pattern.class.equals(field.getType()));
        }
    }

    /** And neither does the tool that summarises an object - not for the reference count. */
    @Test
    public void theSummaryToolKeepsNoTotalPattern()
    {
        for (Field field : ObjectSummaryTool.class.getDeclaredFields())
        {
            assertFalse("ObjectSummaryTool still declares " + field.getName(), //$NON-NLS-1$
                "TOTAL_PATTERN".equals(field.getName())); //$NON-NLS-1$
        }
    }
}
