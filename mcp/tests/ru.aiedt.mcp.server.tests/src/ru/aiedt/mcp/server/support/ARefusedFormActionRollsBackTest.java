/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;

import org.junit.Test;

/**
 * A form action that refuses rolls its transaction back: the refusal leaves the task as a throw,
 * and the caller gets the action's own answer back from it.
 */
public class ARefusedFormActionRollsBackTest
{
    private static final String FORMATTED_ERROR =
        "---\ntool: edit_form\nstatus: error\n---\nThe rule was not added.\n"; //$NON-NLS-1$

    /**
     * A plain {@code Error:} answer and a formatted {@code status: error} answer both leave the task
     * as a rollback carrying the answer unchanged.
     */
    @Test
    public void aRefusalLeavesTheTaskAsARollback()
    {
        for (String refusal : new String[] { "Error: the container was created, the rule failed", //$NON-NLS-1$
            FORMATTED_ERROR })
        {
            try
            {
                BmFormHelper.rollBackOnRefusal(refusal);
                fail("a refusal must not return normally: " + refusal); //$NON-NLS-1$
            }
            catch (BmFormHelper.RefusalRollback rollback)
            {
                assertEquals(refusal, rollback.answer);
            }
        }
    }

    /**
     * A success, a plain message and no answer at all let the task commit.
     */
    @Test
    public void aSuccessCommits()
    {
        BmFormHelper.rollBackOnRefusal("added attribute X"); //$NON-NLS-1$
        BmFormHelper.rollBackOnRefusal("---\ntool: edit_form\nstatus: success\n---\nField added.\n"); //$NON-NLS-1$
        BmFormHelper.rollBackOnRefusal(null);
    }

    /**
     * The refusal is found however deep the model and the reflective call wrapped it; any other
     * failure is not a refusal.
     */
    @Test
    public void theRefusalIsFoundThroughTheWrapping()
    {
        Throwable wrapped = new InvocationTargetException(
            new RuntimeException(new BmFormHelper.RefusalRollback(FORMATTED_ERROR)));

        assertEquals(FORMATTED_ERROR, BmFormHelper.refusalRolledBack(wrapped));
        assertNull(BmFormHelper.refusalRolledBack(new InvocationTargetException(new IllegalStateException())));
        assertNull(BmFormHelper.refusalRolledBack(null));
    }
}
