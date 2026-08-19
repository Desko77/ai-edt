/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import ru.aiedt.mcp.server.Activator;

/**
 * The values a system enumeration can take.
 * <p>
 * The question this answers is one an agent asks constantly and currently guesses at: what may I
 * write after {@code ВидДвиженияНакопления.}? Guessing produces code that compiles as far as the
 * editor is concerned and fails at run time, because an unknown member of a system enumeration is a
 * run-time error, not a syntax one.
 * </p>
 * <p>
 * The values are not documentation - they are in the platform's own type register, the same one the
 * editor completes from. A system enumeration is a type flagged {@code isSysEnum}, and its values
 * are the properties of its context: {@code ВидДвиженияНакопления} has properties named
 * {@code Приход} and {@code Расход} and nothing else.
 * </p>
 * <p>
 * Every failure here is a "cannot tell", never an empty list. An empty list reads as "this
 * enumeration has no values", which is never true of a real one and would send the caller looking
 * for the mistake in their own code.
 * </p>
 */
public final class SystemEnumValues
{
    /** What a lookup produced. */
    public static final class Lookup
    {
        /** The values, in the order the register holds them. */
        public final List<String> values = new ArrayList<>();

        /** True when the name resolved to a type that is a system enumeration. */
        public boolean isSystemEnum;

        /** Why nothing could be said, when nothing could. */
        public String cannotTell;
    }

    private SystemEnumValues()
    {
    }

    /**
     * Reads the values of one system enumeration.
     *
     * @param bareName the enumeration's name as it appears in code, in either script variant.
     * @param project the project, which fixes the platform version to ask about.
     * @return the values, or a reason none could be read
     */
    public static Lookup of(String bareName, IProject project)
    {
        Lookup lookup = new Lookup();
        if (bareName == null || bareName.isEmpty())
        {
            lookup.cannotTell = "no type name was given"; //$NON-NLS-1$
            return lookup;
        }
        Object described = PlatformTypeNames.describeType(bareName, project);
        if (described == null)
        {
            lookup.cannotTell = "the platform type register does not know " + bareName //$NON-NLS-1$
                + ", or could not be consulted - it is not necessarily absent"; //$NON-NLS-1$
            return lookup;
        }
        try
        {
            EObject type = resolve(described);
            if (type == null)
            {
                lookup.cannotTell = "the register holds " + bareName //$NON-NLS-1$
                    + " but its definition could not be loaded"; //$NON-NLS-1$
                return lookup;
            }
            if (!Boolean.TRUE.equals(call(type, "isSysEnum"))) //$NON-NLS-1$
            {
                // Resolved, and it is simply not an enumeration. Said plainly rather than answered
                // with an empty list, which would read as "an enumeration with no values".
                lookup.isSystemEnum = false;
                lookup.cannotTell = bareName + " is a platform type, but not a system enumeration"; //$NON-NLS-1$
                return lookup;
            }
            lookup.isSystemEnum = true;
            Object context = call(type, "getContextDef"); //$NON-NLS-1$
            if (context == null)
            {
                lookup.cannotTell = bareName + " is a system enumeration whose context is not " //$NON-NLS-1$
                    + "loaded, so its values cannot be read"; //$NON-NLS-1$
                return lookup;
            }
            Object properties = call(context, "getProperties"); //$NON-NLS-1$
            if (!(properties instanceof Iterable))
            {
                lookup.cannotTell = "the register offered no property list for " + bareName; //$NON-NLS-1$
                return lookup;
            }
            for (Object property : (Iterable<?>)properties)
            {
                Object name = call(property, "getName"); //$NON-NLS-1$
                if (name instanceof String && !((String)name).isEmpty())
                {
                    lookup.values.add((String)name);
                }
            }
            if (lookup.values.isEmpty())
            {
                lookup.cannotTell = bareName + " resolved as a system enumeration but listed no " //$NON-NLS-1$
                    + "values, which no real one does - treat this as a failed read, not as an " //$NON-NLS-1$
                    + "empty enumeration"; //$NON-NLS-1$
            }
            return lookup;
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read the values of " + bareName + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            lookup.cannotTell = "reading " + bareName + " from the type register failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage();
            return lookup;
        }
    }

    /**
     * Turns whatever the register handed back into a loaded object.
     * <p>
     * The register answers with a description, and a description carries a proxy. Resolving it is
     * what turns a name into a definition with properties on it.
     * </p>
     *
     * @param described the register's answer.
     * @return the loaded type, or {@code null}
     * @throws Exception when the reflective call fails
     */
    private static EObject resolve(Object described) throws Exception
    {
        Object object = call(described, "getEObjectOrProxy"); //$NON-NLS-1$
        if (!(object instanceof EObject))
        {
            return described instanceof EObject ? (EObject)described : null;
        }
        EObject proxy = (EObject)object;
        return proxy.eIsProxy() ? null : proxy;
    }

    /**
     * Calls a no-argument method by name, tolerating its absence.
     * <p>
     * Reflection, because these types come from the EDT bundles and a compile dependency on them
     * would stop this plugin resolving on an install that ships a different set. The absence of a
     * method is reported as {@code null} and read by the caller as "cannot tell" - never swallowed
     * into a confident answer.
     * </p>
     *
     * @param target the object to ask.
     * @param method the method name.
     * @return what it returned, or {@code null} when there is no such method
     * @throws Exception when the call itself throws
     */
    private static Object call(Object target, String method) throws Exception
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        }
        catch (NoSuchMethodException absent)
        {
            return null;
        }
    }
}
