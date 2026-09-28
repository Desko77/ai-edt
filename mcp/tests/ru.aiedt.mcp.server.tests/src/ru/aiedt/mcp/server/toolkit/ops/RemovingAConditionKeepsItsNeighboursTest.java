/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Removing one query condition drops that condition and the connector beside it.
 * <p>
 * The cut used to start at the last AND anywhere before the condition, so a neighbour between
 * that AND and the condition was lost with it. A connector that is not touching the condition is
 * left alone. OR is a connector too: a condition that sits between AND and OR is refused, and the
 * refusal quotes the WHERE, rather than the whole clause being deleted.
 * </p>
 */
public class RemovingAConditionKeepsItsNeighboursTest
{
    /**
     * Runs the condition removal on one WHERE region.
     *
     * @param whereRegion the region, starting at the WHERE or ГДЕ keyword
     * @param condition the condition text to drop
     * @param english whether the keyword is WHERE rather than ГДЕ
     * @return the rewritten region
     * @throws Exception if the call refuses, or the method cannot be reached
     */
    private static String remove(String whereRegion, String condition, boolean english)
        throws Exception
    {
        Method method = DcsWorkshopTool.class.getDeclaredMethod("removeConditionToken", //$NON-NLS-1$
            String.class, String.class, boolean.class);
        method.setAccessible(true);
        try
        {
            return (String)method.invoke(null, whereRegion, condition, Boolean.valueOf(english));
        }
        catch (InvocationTargetException wrapped)
        {
            if (wrapped.getCause() instanceof RuntimeException)
            {
                throw (RuntimeException)wrapped.getCause();
            }
            throw wrapped;
        }
    }

    /**
     * A condition at the end of an OR keeps the conditions before that OR.
     *
     * @throws Exception if the removal refuses
     */
    @Test
    public void anOrBesideTheConditionDoesNotTakeTheNeighbourBeforeIt() throws Exception
    {
        assertEquals("ГДЕ А = 1 И Б = 2", //$NON-NLS-1$
            remove("ГДЕ А = 1 И Б = 2 ИЛИ (В = 3)", "В = 3", false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * OR is a connector, so removing the second condition leaves the first and the keyword.
     *
     * @throws Exception if the removal refuses
     */
    @Test
    public void anOrBeforeTheOnlyOtherConditionLeavesThatCondition() throws Exception
    {
        assertEquals("ГДЕ А = 1", remove("ГДЕ А = 1 ИЛИ (В = 3)", "В = 3", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The same cut in English.
     *
     * @throws Exception if the removal refuses
     */
    @Test
    public void anEnglishOrBehavesTheSameWay() throws Exception
    {
        assertEquals("WHERE A = 1 AND B = 2", //$NON-NLS-1$
            remove("WHERE A = 1 AND B = 2 OR (C = 3)", "C = 3", true)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A condition between AND and OR is ambiguous, so it is refused and the WHERE is quoted.
     */
    @Test
    public void aConditionBetweenAndAndOrIsRefusedWithTheWhere()
    {
        String where = "ГДЕ А = 1 И Б = 2 ИЛИ (В = 3)"; //$NON-NLS-1$
        try
        {
            remove(where, "Б = 2", false); //$NON-NLS-1$
            fail("dropping the middle condition would have to choose which connector survives"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal quotes the WHERE it left untouched: " + message, //$NON-NLS-1$
                message.contains(where));
        }
    }

    /**
     * The only condition in the clause takes the clause with it.
     *
     * @throws Exception if the removal refuses
     */
    @Test
    public void theOnlyConditionDropsTheWholeClause() throws Exception
    {
        assertEquals("", remove("ГДЕ (В = 3)", "В = 3", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
