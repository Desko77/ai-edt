/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.ILogListener;
import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * The value written by {@code set_variable} does not reach the platform log.
 *
 * <p>The value is an expression the caller chose, and the call exists to write what an agent cannot
 * know in advance - a password, a token, a personal name. It was logged beside the variable it was
 * written into, which puts it in the workspace log file, in whatever collects that file and in any
 * bug report that carries it. The log now records that the variable was written and which one, and
 * the value is nowhere in it.</p>
 *
 * <p>Read through a log listener on the plugin, the way the platform itself sees every entry.</p>
 */
public class AVariableValueDoesNotReachThePlatformLogTest
{
    private static final String APP = "b61-log-app"; //$NON-NLS-1$

    private static final String SECRET = "s3cret-выражение"; //$NON-NLS-1$

    @After
    public void clearTheFakeSnapshot()
    {
        DebugSessionBook.get().clearSnapshot(APP);
    }

    @Test
    public void theWrittenValueIsInNoLogEntry()
    {
        FakeDebugFrames.Var password = FakeDebugFrames.var("Пароль").type("Строка").value("\"старый\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        session.moveTo(session.frame("ОбщийМодуль.Метод", password.asVariable())); //$NON-NLS-1$
        long threadId = FakeDebugToolCalls.register(APP, session);

        List<String> logged = new ArrayList<>();
        ILogListener listener = (status, plugin) -> {
            synchronized (logged)
            {
                logged.add(String.valueOf(status.getMessage()));
                if (status.getException() != null)
                {
                    logged.add(String.valueOf(status.getException().getMessage()));
                }
            }
        };
        Activator.getDefault().getLog().addLogListener(listener);
        String answer;
        try
        {
            answer = new DebugVariableWriter().execute(FakeDebugToolCalls.args(
                "threadId", String.valueOf(threadId), "path", "Пароль", "value", "\"" + SECRET + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        }
        finally
        {
            Activator.getDefault().getLog().removeLogListener(listener);
        }

        JsonObject document = FakeDebugToolCalls.json(answer);
        assertEquals(answer, true, document.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the write itself still happened", //$NON-NLS-1$
            Collections.singletonList("\"" + SECRET + "\""), password.written); //$NON-NLS-1$ //$NON-NLS-2$
        synchronized (logged)
        {
            assertFalse("the call logged nothing at all, so this proves nothing", logged.isEmpty()); //$NON-NLS-1$
            for (String line : logged)
            {
                assertFalse(line, line.contains(SECRET));
            }
        }
    }
}
