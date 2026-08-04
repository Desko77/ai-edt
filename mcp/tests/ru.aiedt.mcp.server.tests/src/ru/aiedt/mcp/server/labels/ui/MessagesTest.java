/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Checks that every message the marker dialogs display is actually in the bundle.
 * <p>
 * NLS binds these fields by reflection at class load, and a key that is missing from the properties
 * file does not fail: the field is filled with a placeholder and an entry goes to the error log
 * nobody is reading. The result reaches a user as {@code NLS missing message: ...} inside a dialog.
 * Since the binding is by field name, a renamed field and a stale properties file drift apart
 * silently, which is exactly what this test exists to catch.
 * </p>
 */
public class MessagesTest
{
    private static List<Field> messageFields()
    {
        List<Field> fields = new ArrayList<>();
        for (Field field : Messages.class.getDeclaredFields())
        {
            if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())
                && !Modifier.isFinal(field.getModifiers()))
            {
                fields.add(field);
            }
        }
        return fields;
    }

    @Test
    public void theBundleDeclaresMessages()
    {
        // Without this the sweep below would pass over an empty list and prove nothing.
        assertFalse("no translatable fields found - the sweep would be vacuous", //$NON-NLS-1$
            messageFields().isEmpty());
    }

    @Test
    public void everyDeclaredMessageIsBound() throws IllegalAccessException
    {
        List<String> unbound = new ArrayList<>();
        for (Field field : messageFields())
        {
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null || String.valueOf(value).startsWith("NLS missing message")) //$NON-NLS-1$
            {
                unbound.add(field.getName());
            }
        }
        assertTrue("these keys are absent from the properties file and would be shown to a user " //$NON-NLS-1$
            + "verbatim: " + unbound, unbound.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void noMessageIsBlank()
    {
        List<String> blank = new ArrayList<>();
        for (Field field : messageFields())
        {
            field.setAccessible(true);
            try
            {
                Object value = field.get(null);
                if (value != null && String.valueOf(value).isBlank())
                {
                    blank.add(field.getName());
                }
            }
            catch (IllegalAccessException unreachable)
            {
                throw new AssertionError(unreachable);
            }
        }
        assertNotNull(blank);
        assertTrue("a blank message renders as an empty control: " + blank, blank.isEmpty()); //$NON-NLS-1$
    }
}
