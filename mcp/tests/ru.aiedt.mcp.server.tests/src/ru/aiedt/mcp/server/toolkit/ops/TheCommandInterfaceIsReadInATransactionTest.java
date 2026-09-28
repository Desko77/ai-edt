/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;

/**
 * get_command_interface reads the command interface inside a read-only transaction of the
 * project's model, not beside it.
 */
public class TheCommandInterfaceIsReadInATransactionTest
{
    /**
     * The read runs while the model's read-only task is executing, and its answer is what the
     * helper returns.
     */
    @Test
    public void theReadRunsInsideTheReadOnlyTask()
    {
        AtomicBoolean inTask = new AtomicBoolean();
        AtomicBoolean readInsideTask = new AtomicBoolean();
        IBmModel model = (IBmModel)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { IBmModel.class },
            (proxy, method, args) -> {
                if ("executeReadonlyTask".equals(method.getName()) && args != null //$NON-NLS-1$
                    && args.length > 0 && args[0] instanceof IBmTask)
                {
                    inTask.set(true);
                    try
                    {
                        return ((IBmTask<?>)args[0]).execute(null, null);
                    }
                    finally
                    {
                        inTask.set(false);
                    }
                }
                return null;
            });

        String answer = GetCommandInterfaceTool.readInTransaction(model, () -> {
            readInsideTask.set(inTask.get());
            return "{\"success\":true}"; //$NON-NLS-1$
        });

        assertTrue("the command interface was read outside the read-only task", //$NON-NLS-1$
            readInsideTask.get());
        assertEquals("{\"success\":true}", answer); //$NON-NLS-1$
    }
}
