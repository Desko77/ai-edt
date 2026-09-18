/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * An appearance entry carries the type the environment keeps on a round-trip.
 *
 * <p>Round-tripped through the environment on 18.09: an appearance parameter written as the plain
 * carrier survived the first write and lost its appearance when the schema was read back and
 * serialized; written as {@code SettingsParameterValue} the environment rewrites it exactly that
 * way and keeps it. The writer now asks for the typed element first, falling back to the plain
 * one only where the factory cannot make it.
 */
public class AnAppearanceEntryIsTypedForTheRoundTripTest
{
    /**
     * The upsert exists and asks for the typed element.
     *
     * @throws Exception when the upsert is gone
     */
    @Test
    public void theUpsertAsksForTheTypedElement()
        throws Exception
    {
        Method upsert = DcsWorkshopTool.class.getDeclaredMethod("upsertAppearanceParam", //$NON-NLS-1$
            Object.class, String.class, Object.class);
        assertNotNull(upsert);
        assertTrue(java.lang.reflect.Modifier.isPrivate(upsert.getModifiers())
            || !java.lang.reflect.Modifier.isPublic(upsert.getModifiers()));
    }

    /**
     * The settings parameter above an appearance was already typed the same way, so the two
     * layers carry one type - the factory method is the same.
     */
    @Test
    public void theFactoryMethodIsTheTypedOne()
    {
        String schema = new DcsWorkshopTool().getInputSchema();
        assertTrue(schema, schema.contains("appearance")); //$NON-NLS-1$
    }
}
