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
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;

import ru.aiedt.mcp.server.Activator;

/**
 * The values a system enumeration can take.
 * <p>
 * The question this answers is one an agent asks constantly and currently guesses at: what may I
 * write after {@code ВидДвиженияНакопления.}? Guessing produces code the editor accepts and the
 * platform rejects at run time, because an unknown member of a system enumeration is a run-time
 * error, not a syntax one.
 * </p>
 * <p>
 * <b>The name in front of the dot is not a type.</b> It is a property of the platform's global
 * context, and its TYPE is what carries the values. The type register does hold something under the
 * same name - the type OF a value, flagged {@code sysEnum} with an empty context - which is why
 * asking the type register looks like it worked and yields nothing. Both wrong turns were taken
 * here and both were caught only on a live stand: first the register's proxy was read as a failure,
 * then the value type was read as the answer. The values belong to a second type
 * ({@code EnumAccumulationRecordType} / {@code ПеречислениеВидДвиженияНакопления}), and that type is
 * in NO spelling present in the type register - measured, not assumed.
 * </p>
 * <p>
 * So the lookup goes through the property register instead, which the version bundle contributes
 * separately and fills from its own {@code SystemEnumsLoader}: name to global property, property to
 * its type, type's context to the values.
 * </p>
 * <p>
 * Every failure here is a "cannot tell", never an empty list. An empty list reads as "this
 * enumeration has no values", which is never true of a real one and would send the caller looking
 * for the mistake in their own code.
 * </p>
 */
public final class SystemEnumValues
{
    /** One value, under both of the names it answers to. */
    public static final class Value
    {
        /** The English name, as in {@code Receipt}. */
        public final String name;

        /** The Russian name, as in {@code Приход}; empty when the register carries none. */
        public final String nameRu;

        Value(String name, String nameRu)
        {
            this.name = name;
            this.nameRu = nameRu == null ? "" : nameRu; //$NON-NLS-1$
        }
    }

    /** What a lookup produced. */
    public static final class Lookup
    {
        /** The values, in the order the register holds them. */
        public final List<Value> values = new ArrayList<>();

        /** True when the name resolved to something that is a system enumeration. */
        public boolean isSystemEnum;

        /**
         * The type the values were actually read from, which is never the name that was asked for.
         * Named in the answer so the caller can see the type behind it.
         */
        public String valuesFrom;

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
        // One resource set for the whole lookup, so the property and the type it points at load
        // into the same graph and the reference between them resolves rather than dangling.
        ResourceSet loadInto = new ResourceSetImpl();
        try
        {
            Object described = PlatformTypeNames.describeGlobalProperty(bareName, project);
            if (described != null)
            {
                return fromGlobalProperty(bareName, resolve(described, loadInto), loadInto, lookup);
            }
            return whyTheGlobalContextHasNothing(bareName, project, loadInto, lookup);
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read the values of " + bareName + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            lookup.values.clear();
            lookup.cannotTell = "reading " + bareName + " from the platform registers failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage();
            return lookup;
        }
    }

    /**
     * Follows a global property to its type and reads the values off it.
     *
     * @param bareName what was asked for.
     * @param property the resolved property, or <code>null</code>.
     * @param loadInto the resource set to resolve through.
     * @param lookup the answer being built.
     * @return the answer
     * @throws Exception when a reflective call fails
     */
    private static Lookup fromGlobalProperty(String bareName, EObject property, ResourceSet loadInto,
        Lookup lookup) throws Exception
    {
        EObject holder = property == null ? null : typeOf(property, loadInto);
        if (holder == null)
        {
            lookup.cannotTell = bareName + " is in the platform's global context, but the type " //$NON-NLS-1$
                + "behind it could not be loaded"; //$NON-NLS-1$
            return lookup;
        }
        if (!Boolean.TRUE.equals(call(holder, "isSysEnum"))) //$NON-NLS-1$
        {
            lookup.cannotTell = bareName + " is a name in the platform's global context, but its " //$NON-NLS-1$
                + "type is not a system enumeration"; //$NON-NLS-1$
            return lookup;
        }
        lookup.isSystemEnum = true;
        lookup.valuesFrom = nameOf(holder, bareName);
        if (!readValues(holder, lookup))
        {
            lookup.cannotTell = bareName + " resolved to the system enumeration " //$NON-NLS-1$
                + lookup.valuesFrom + ", which listed no values - no real one has none, so treat " //$NON-NLS-1$
                + "this as a failed read"; //$NON-NLS-1$
        }
        return lookup;
    }

    /**
     * Says why the global context had nothing, using the type register to tell the cases apart.
     * <p>
     * Worth the second lookup: "you misspelled it", "that is a type but not an enumeration" and
     * "the register could not be reached" are three different problems, and one message for all
     * three sends the caller to the wrong place.
     * </p>
     *
     * @param bareName what was asked for.
     * @param project the project.
     * @param loadInto the resource set to resolve through.
     * @param lookup the answer being built.
     * @return the answer
     * @throws Exception when a reflective call fails
     */
    private static Lookup whyTheGlobalContextHasNothing(String bareName, IProject project,
        ResourceSet loadInto, Lookup lookup) throws Exception
    {
        Object describedType = PlatformTypeNames.describeType(bareName, project);
        EObject asType = describedType == null ? null : resolve(describedType, loadInto);
        if (asType == null)
        {
            lookup.cannotTell = "the platform registers do not know " + bareName //$NON-NLS-1$
                + ", or could not be consulted - it is not necessarily absent. It was looked for " //$NON-NLS-1$
                + "as a global property and as a type"; //$NON-NLS-1$
            return lookup;
        }
        lookup.isSystemEnum = Boolean.TRUE.equals(call(asType, "isSysEnum")); //$NON-NLS-1$
        lookup.cannotTell = lookup.isSystemEnum
            ? bareName + " is the type OF a value of a system enumeration, and a value type holds " //$NON-NLS-1$
                + "no values. The values sit on the type behind the global-context entry of the " //$NON-NLS-1$
                + "same name, and that entry was not reachable here" //$NON-NLS-1$
            : bareName + " is a platform type, but not a system enumeration"; //$NON-NLS-1$
        return lookup;
    }

    /**
     * The type a property carries, resolved.
     * <p>
     * Two ways in, because the register uses both: a property may answer with its types directly,
     * or hold a container that does.
     * </p>
     *
     * @param property the property.
     * @param loadInto the resource set to resolve through.
     * @return the type, or <code>null</code>
     * @throws Exception when a reflective call fails
     */
    private static EObject typeOf(EObject property, ResourceSet loadInto) throws Exception
    {
        EObject direct = firstType(call(property, "getTypes"), loadInto); //$NON-NLS-1$
        if (direct != null)
        {
            return direct;
        }
        Object container = call(property, "getTypeContainer"); //$NON-NLS-1$
        if (container == null)
        {
            return null;
        }
        EObject fromContainer = firstType(call(container, "allTypes"), loadInto); //$NON-NLS-1$
        return fromContainer != null ? fromContainer : firstType(call(container, "getTypes"), loadInto); //$NON-NLS-1$
    }

    /**
     * The first usable type out of whatever the model handed back.
     *
     * @param types a list, or anything else.
     * @param loadInto the resource set to resolve through.
     * @return the first type that loads, or <code>null</code>
     */
    private static EObject firstType(Object types, ResourceSet loadInto)
    {
        if (!(types instanceof Iterable))
        {
            return null;
        }
        for (Object candidate : (Iterable<?>)types)
        {
            if (!(candidate instanceof EObject))
            {
                continue;
            }
            EObject type = (EObject)candidate;
            EObject loaded = type.eIsProxy() ? resolveProxy(type, loadInto) : type;
            if (loaded != null)
            {
                return loaded;
            }
        }
        return null;
    }

    /**
     * Reads the values off a type's context.
     *
     * @param type the type to read.
     * @param lookup the answer being built.
     * @return true when at least one value was added
     * @throws Exception when a reflective call fails
     */
    private static boolean readValues(EObject type, Lookup lookup) throws Exception
    {
        Object context = call(type, "getContextDef"); //$NON-NLS-1$
        if (context == null)
        {
            return false;
        }
        Object properties = call(context, "getProperties"); //$NON-NLS-1$
        if (!(properties instanceof Iterable) || !((Iterable<?>)properties).iterator().hasNext())
        {
            // A context that delegates keeps its own list empty and answers through the referenced
            // ones, which is what allProperties walks.
            properties = call(context, "allProperties"); //$NON-NLS-1$
        }
        if (!(properties instanceof Iterable))
        {
            return false;
        }
        // Collected aside and published only once the whole list is read. Appending straight into
        // the answer would leave half an enumeration behind when a reflective call throws
        // mid-iteration, and a half-read enumeration is worse than none: it looks complete.
        List<Value> read = new ArrayList<>();
        for (Object property : (Iterable<?>)properties)
        {
            Object name = call(property, "getName"); //$NON-NLS-1$
            if (name instanceof String && !((String)name).isEmpty())
            {
                Object nameRu = call(property, "getNameRu"); //$NON-NLS-1$
                read.add(new Value((String)name, nameRu instanceof String ? (String)nameRu : null));
            }
        }
        lookup.values.addAll(read);
        return !read.isEmpty();
    }

    /**
     * A type's own name, preferring the English one the register keeps consistently.
     *
     * @param type the type.
     * @param fallback what to say when it names itself neither way.
     * @return the name
     * @throws Exception when a reflective call fails
     */
    private static String nameOf(EObject type, String fallback) throws Exception
    {
        Object name = call(type, "getName"); //$NON-NLS-1$
        if (name instanceof String && !((String)name).isEmpty())
        {
            return (String)name;
        }
        Object nameRu = call(type, "getNameRu"); //$NON-NLS-1$
        return nameRu instanceof String && !((String)nameRu).isEmpty() ? (String)nameRu : fallback;
    }

    /**
     * Turns whatever a register handed back into a loaded object.
     * <p>
     * A register answers with a description, and a description carries a proxy. Resolving it is
     * what turns a name into a definition with something on it.
     * </p>
     *
     * @param described the register's answer.
     * @param loadInto the resource set to load through.
     * @return the loaded object, or {@code null}
     * @throws Exception when the reflective call fails
     */
    private static EObject resolve(Object described, ResourceSet loadInto) throws Exception
    {
        Object object = call(described, "getEObjectOrProxy"); //$NON-NLS-1$
        if (!(object instanceof EObject))
        {
            return described instanceof EObject ? (EObject)described : null;
        }
        EObject candidate = (EObject)object;
        return candidate.eIsProxy() ? resolveProxy(candidate, loadInto) : candidate;
    }

    /**
     * Loads a proxy.
     * <p>
     * A proxy is what a register normally hands back - it holds descriptions, not loaded models.
     * Treating "is a proxy" as failure meant this returned nothing at all: measured on the stand,
     * every lookup answered "the definition could not be loaded", including for names that plainly
     * exist.
     * </p>
     *
     * @param proxy the proxy.
     * @param loadInto the resource set to load through.
     * @return the loaded object, or {@code null}
     */
    private static EObject resolveProxy(EObject proxy, ResourceSet loadInto)
    {
        try
        {
            EObject resolved = org.eclipse.emf.ecore.util.EcoreUtil.resolve(proxy, loadInto);
            return resolved == null || resolved.eIsProxy() ? null : resolved;
        }
        catch (Exception cannotLoad)
        {
            Activator.logWarning("Could not resolve a platform proxy: " + cannotLoad.getMessage()); //$NON-NLS-1$
            return null;
        }
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
