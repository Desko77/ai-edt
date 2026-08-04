/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Covers the three reflection primitives that walk the public API surface of an EDT object:
 * method invocation, field reads up the hierarchy, and method lookup by exact signature.
 */
public class ReflectionAccessTest
{
    /** Carrier for a private field and two public no-arg methods. */
    private static class Carrier
    {
        private final String payload;

        Carrier(String payload)
        {
            this.payload = payload;
        }

        public String reveal()
        {
            return payload;
        }

        public int length()
        {
            return payload.length();
        }

        public void accept(String value)
        {
            // no-op; exists so findMethod can locate a parameterized signature
        }
    }

    /** Adds its own field, to prove the field/method walk reaches a superclass. */
    private static class ExtendedCarrier extends Carrier
    {
        private final int stamp;

        ExtendedCarrier(String payload, int stamp)
        {
            super(payload);
            this.stamp = stamp;
        }

        public int stamp()
        {
            return stamp;
        }
    }

    // ---------- invokeMethod ----------

    @Test
    public void invokesPublicNoArgMethodReturningObject() throws Exception
    {
        Object result = ReflectionAccess.invokeMethod(new Carrier("data"), "reveal");
        assertEquals("data", result);
    }

    @Test
    public void invokesPublicNoArgMethodReturningPrimitive() throws Exception
    {
        Object result = ReflectionAccess.invokeMethod(new Carrier("word"), "length");
        assertEquals(Integer.valueOf(4), result);
    }

    @Test(expected = NoSuchMethodException.class)
    public void invokeMethodMissingNameThrowsNoSuchMethod() throws Exception
    {
        ReflectionAccess.invokeMethod(new Carrier("x"), "nonexistent");
    }

    // ---------- getFieldValue ----------

    @Test
    public void readsPrivateFieldOnRuntimeClass() throws Exception
    {
        Object value = ReflectionAccess.getFieldValue(new Carrier("hidden"), "payload");
        assertEquals("hidden", value);
    }

    @Test
    public void readsFieldDeclaredOnSuperclass() throws Exception
    {
        Object value = ReflectionAccess.getFieldValue(new ExtendedCarrier("up", 0), "payload");
        assertEquals("up", value);
    }

    @Test
    public void readsFieldDeclaredOnSubclass() throws Exception
    {
        Object value = ReflectionAccess.getFieldValue(new ExtendedCarrier("x", 42), "stamp");
        assertEquals(42, value);
    }

    @Test
    public void absentFieldReturnsNullNotException() throws Exception
    {
        Object value = ReflectionAccess.getFieldValue(new Carrier("x"), "nowhere");
        assertNull(value);
    }

    // ---------- findMethod ----------

    @Test
    public void findsNoArgMethodOnClass()
    {
        Method method = ReflectionAccess.findMethod(Carrier.class, "reveal");
        assertNotNull(method);
        assertEquals("reveal", method.getName());
    }

    @Test
    public void findsMethodWithExactParameterTypes()
    {
        assertNotNull(ReflectionAccess.findMethod(Carrier.class, "accept", String.class));
    }

    @Test
    public void findsMethodInheritedFromSuperclass()
    {
        assertNotNull(ReflectionAccess.findMethod(ExtendedCarrier.class, "reveal"));
    }

    @Test
    public void missingMethodReturnsNull()
    {
        assertNull(ReflectionAccess.findMethod(Carrier.class, "phantom"));
    }

    @Test
    public void mismatchedParameterTypesReturnsNull()
    {
        assertNull(ReflectionAccess.findMethod(Carrier.class, "reveal", String.class));
    }

    @Test
    public void nullClassReturnsNull()
    {
        assertNull(ReflectionAccess.findMethod(null, "reveal"));
    }
}
