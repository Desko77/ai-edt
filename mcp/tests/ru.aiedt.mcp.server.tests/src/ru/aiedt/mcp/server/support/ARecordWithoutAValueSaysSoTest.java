/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A variable the environment returned no value for is marked, and one with an empty value is not.
 *
 * <p>Measured on a live suspension 17.09, in the form module of a catalogue item form: scope=all
 * printed module properties with the type <code>&lt;?&gt;</code> and no value field at all, while
 * evaluating the same name answered Булево / Истина. So the value exists and this API does not carry
 * it - which is a different thing from a variable whose value is genuinely empty, and the two must
 * not be told apart by guessing.
 */
public class ARecordWithoutAValueSaysSoTest
{
    @Test
    public void anUnknownTypeWithNoValueIsMarked()
    {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "<?>");
        record.put("name", "АвтоЗаголовок");

        assertTrue(DebugValueSerializer.carriesNoValue(record));
    }

    @Test
    public void anEmptyStringIsAValueAndIsNotMarked()
    {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "Строка");
        record.put("value", "");

        assertFalse("an empty string is what the variable holds, not a missing answer",
            DebugValueSerializer.carriesNoValue(record));
    }

    @Test
    public void aKnownTypeWithoutAValueIsNotMarked()
    {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "Структура");

        assertFalse("the environment named the type, so it answered about this one",
            DebugValueSerializer.carriesNoValue(record));
    }

    @Test
    public void anUnknownTypeWithAValueIsNotMarked()
    {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "<?>");
        record.put("value", "Истина");

        assertFalse(DebugValueSerializer.carriesNoValue(record));
    }
}
