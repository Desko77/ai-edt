/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.eclipse.debug.core.model.IVariable;
import org.junit.Test;

/**
 * One value is cut the same way whichever route hands it to a client.
 *
 * <p>A 1C value can stringify to megabytes, so every answer cuts it and says how long it really was.
 * The rule lives in one place now, and this is that place: a value under the limit travels whole, a
 * value over it travels cut with {@code truncated} and {@code fullLength} beside it, and the frame
 * listing cuts through the same call rather than through a copy of the rule.</p>
 */
public class ALongValueIsCutTheOneWayTest
{
    private static String longValue()
    {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 700; i++)
        {
            text.append('x');
        }
        return text.toString();
    }

    @Test
    public void aValueUnderTheLimitTravelsWhole()
    {
        Map<String, Object> record = DebugValueSerializer.valueWithCut("Структура"); //$NON-NLS-1$

        assertEquals("Структура", record.get("value")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nothing was cut, so nothing says it was", //$NON-NLS-1$
            record.containsKey("truncated")); //$NON-NLS-1$
        assertFalse(record.containsKey("fullLength")); //$NON-NLS-1$
    }

    @Test
    public void aValueOverTheLimitIsCutAndSaysHowLongItWas()
    {
        Map<String, Object> record = DebugValueSerializer.valueWithCut(longValue());

        String sent = (String)record.get("value"); //$NON-NLS-1$
        assertEquals(DebugValueSerializer.MAX_VALUE_LENGTH, sent.length());
        assertEquals(Boolean.TRUE, record.get("truncated")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(700), record.get("fullLength")); //$NON-NLS-1$
    }

    @Test
    public void aValueThatIsNotThereIsNotACut()
    {
        Map<String, Object> record = DebugValueSerializer.valueWithCut(null);

        assertTrue(record.containsKey("value")); //$NON-NLS-1$
        assertNull(record.get("value")); //$NON-NLS-1$
        assertFalse(record.containsKey("truncated")); //$NON-NLS-1$
    }

    @Test
    public void theFrameListingCutsThroughTheSameRule() throws Exception
    {
        IVariable variable = FakeDebugFrames.var("ДлиннаяСтрока").type("Строка") //$NON-NLS-1$ //$NON-NLS-2$
            .value(longValue()).asVariable();

        Map<String, Object> dto = DebugValueSerializer.serializeVariable(variable, DebugSessionBook.get());

        assertEquals(DebugValueSerializer.MAX_VALUE_LENGTH, ((String)dto.get("value")).length()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, dto.get("truncated")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(700), dto.get("fullLength")); //$NON-NLS-1$
    }
}
