/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import ru.aiedt.mcp.server.Activator;

/**
 * The reflection this plugin needs to reach past EDT's published API.
 * <p>
 * Two features have no other way in: rendering a form to a PNG drives the WYSIWYG editor's internals,
 * and reporting a symbol under the cursor reads the BSL text hover. Neither is API, so both are
 * reached by name. That is a deliberate, contained trade: the four primitives here are the only place
 * that knows it, and each degrades rather than throwing when a future EDT renames what it looks for.
 * </p>
 */
public final class ReflectionAccess
{
    private ReflectionAccess()
    {
        // utility
    }

    /**
     * Calls a no-argument method on an object.
     * <p>
     * Only public methods are found, inherited ones included, and the method is <em>not</em> forced
     * accessible. Reaching a public method of a non-public class therefore fails - which is the point:
     * the callers invoke published methods on internal objects, and a lookup that quietly widened
     * access would hide the day one of them stops being published.
     * </p>
     *
     * @param target the receiver; must not be <code>null</code>
     * @param methodName the method to call
     * @return whatever the method returned; <code>null</code> for a void method
     * @throws NoSuchMethodException if no public no-argument method goes by that name
     * @throws Exception if the call is rejected or the method itself threw
     */
    public static Object invokeMethod(Object target, String methodName) throws Exception
    {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    /**
     * Reads a field of an object, whatever its visibility and whichever class in the hierarchy
     * declares it.
     * <p>
     * The search starts at the runtime class and walks up the superclasses, taking the first
     * declaration of that name. An absent field is not an error: it answers <code>null</code>, the
     * same as a field that is present and null. The callers are probing an internal layout they do not
     * control and treat both the same way.
     * </p>
     *
     * @param target the object to read from; must not be <code>null</code>
     * @param fieldName the field to read
     * @return the value, or <code>null</code> when no class in the hierarchy declares the field
     * @throws Exception if the field is found but the runtime refuses to open it
     */
    public static Object getFieldValue(Object target, String fieldName) throws Exception
    {
        Class<?> current = target.getClass();
        while (current != null)
        {
            try
            {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException e)
            {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Finds a method by name and exact parameter types, searching the class and its superclasses.
     * <p>
     * Parameter types must match exactly - no widening, no boxing, no subtypes. The method comes back
     * as found, not made accessible: a caller that means to invoke a non-public method opens it itself,
     * which keeps that decision visible at the call site.
     * </p>
     *
     * @param clazz the class to search; a <code>null</code> class finds nothing
     * @param name the method name
     * @param paramTypes the parameter types, in order
     * @return the method, or <code>null</code> when no class in the hierarchy declares it. Interfaces
     *         are not searched
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
    {
        Class<?> current = clazz;
        while (current != null)
        {
            try
            {
                return current.getDeclaredMethod(name, paramTypes);
            }
            catch (NoSuchMethodException e)
            {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Overwrites a {@code static final boolean} of a class this plugin does not own.
     * <p>
     * There is one use: EDT decides whether a form editor renders into an offscreen buffer by reading
     * such a flag, and a screenshot needs the flag the other way round. The JDK offers no supported way
     * to reassign it, so this goes through {@code sun.misc.Unsafe}, which is reached by name rather
     * than by import - the bundle does not import {@code sun.misc}, and a compile-time reference would
     * leave it unresolvable under OSGi.
     * </p>
     * <p>
     * Failure is expected to be survivable, so nothing propagates: a JVM that has closed this door logs
     * a warning, the answer is <code>false</code>, and the caller falls back to a lower-fidelity path.
     * </p>
     *
     * @param targetClass the class declaring the field
     * @param fieldName the {@code static final boolean} to overwrite
     * @param value the value to write
     * @return <code>true</code> when the field now holds the value; <code>false</code> when the write
     *         could not be made, for any reason
     */
    public static boolean forceStaticFinalBoolean(Class<?> targetClass, String fieldName, boolean value)
    {
        try
        {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe"); //$NON-NLS-1$
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe"); //$NON-NLS-1$
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);

            Field target = targetClass.getDeclaredField(fieldName);
            target.setAccessible(true);

            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class); //$NON-NLS-1$
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class); //$NON-NLS-1$
            Method putBooleanVolatile =
                unsafeClass.getMethod("putBooleanVolatile", Object.class, long.class, boolean.class); //$NON-NLS-1$

            Object base = staticFieldBase.invoke(unsafe, target);
            long offset = ((Long)staticFieldOffset.invoke(unsafe, target)).longValue();
            putBooleanVolatile.invoke(unsafe, base, Long.valueOf(offset), Boolean.valueOf(value));
            return true;
        }
        catch (Exception e)
        {
            Activator.logWarning("The Unsafe-based patch of the static final boolean failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }
}
