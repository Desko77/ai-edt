/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Register-specific helpers for {@code edit_metadata}: dimensions, resources,
 * attributes of all four register kinds (Information, Accumulation, Accounting,
 * Calculation) plus enum values.
 * <p>
 * Phase 3 skeleton. The detailed factory wiring per register kind is added
 * incrementally - the initial revision only exposes the lookup helpers used by
 * the dispatcher.
 */
public final class BmRegisterHelper
{
    private BmRegisterHelper()
    {
        // utility class
    }

    /**
     * Reflectively retrieves the {@code getDimensions()} EList of a register
     * (or {@code null} for non-register types).
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getDimensions(MdObject register)
    {
        return (EList<MdObject>) invokeListGetter(register, "getDimensions"); //$NON-NLS-1$
    }

    /**
     * Reflectively retrieves the {@code getResources()} EList of a register.
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getResources(MdObject register)
    {
        return (EList<MdObject>) invokeListGetter(register, "getResources"); //$NON-NLS-1$
    }

    /**
     * Reflectively retrieves the {@code getValues()} EList of an enum.
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getEnumValues(MdObject anEnum)
    {
        return (EList<MdObject>) invokeListGetter(anEnum, "getEnumValues"); //$NON-NLS-1$
    }

    private static Object invokeListGetter(MdObject obj, String methodName)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            if (v instanceof EList)
            {
                return v;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // type does not expose this collection
        }
        catch (Exception e)
        {
            Activator.logWarning("BmRegisterHelper " + methodName //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }
}
