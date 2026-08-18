/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;

/**
 * Asks the platform whether a bare type name exists, so a typo stops being
 * written onto an attribute and reported as applied.
 * <p>
 * Bare type names used to be accepted on their SHAPE alone - any capitalized
 * ASCII word passed as a primitive - so {@code Stirng} was materialized into an
 * unresolved-type proxy while the answer said the type had been applied. An
 * allowlist cannot replace this: one demo configuration uses 41 distinct legal
 * bare type names on form attributes, among them DynamicList,
 * SpreadsheetDocument, StandardPeriod and TypeDescription. Only the platform
 * knows the whole vocabulary, and it does: the register answers to 4797 names on
 * 8.3.22.
 * </p>
 * <p>
 * <b>The trap this class exists to contain.</b> The register is keyed by
 * platform VERSION and filled by {@code IEObjectProviderExtension}
 * contributions living in the per-version bundles. Ask for a version whose
 * bundle is absent and the registry hands back a provider that is not null and
 * knows NOTHING - it answers null to every name, exactly as it would for a
 * genuine typo. A check that trusted that would reject every type in the
 * language. So a miss is only believed once the register has proved it knows
 * something, and every other outcome is {@link Verdict#CANNOT_TELL} rather than
 * a rejection.
 * </p>
 * <p>
 * Reached by reflection rather than by import, matching how
 * {@code BmDefinedTypeHelper} already reaches the same registry: the whole chain
 * is optional at runtime, and a package rename between EDT releases must
 * degrade to CANNOT_TELL instead of failing to resolve the bundle.
 * </p>
 */
public final class PlatformTypeNames
{
    /** What the platform register was able to say about a name. */
    public enum Verdict
    {
        /** The register knows this name. */
        KNOWN,
        /** The register is usable and does not know this name - a typo. */
        UNKNOWN,
        /** No usable register here; the caller must fall back to its own rules. */
        CANNOT_TELL
    }

    /**
     * A name every platform version has. Used to tell a register that knows
     * nothing from a name it genuinely does not know - the two are otherwise
     * indistinguishable, both being a null lookup.
     */
    private static final String CANARY = "String"; //$NON-NLS-1$

    /**
     * Whether the register for a given platform version proved usable, keyed by
     * the version's own string form. The canary lookup is O(1), but so is the
     * cache, and the answer cannot change while the runtime is up.
     */
    private static final Map<String, Boolean> USABLE_BY_VERSION = new ConcurrentHashMap<>();

    /** Reasons already written to the log, so a broken lookup is reported once and not per name. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private PlatformTypeNames()
    {
    }

    /**
     * Asks the platform register about a bare (no-dot) type name.
     *
     * @param bareName the type name as the caller wrote it; case-sensitive, as
     *            the register is.
     * @param project the project whose runtime version selects the register;
     *            may be <code>null</code>, which yields CANNOT_TELL.
     * @return KNOWN / UNKNOWN when the register could answer, CANNOT_TELL when
     *         it could not be reached or proved to know nothing.
     */
    public static Verdict check(String bareName, IProject project)
    {
        if (project == null)
        {
            return silent("no project was passed, so there is no platform version to ask about"); //$NON-NLS-1$
        }
        Object versionSupport;
        Object version;
        try
        {
            Activator activator = Activator.getDefault();
            versionSupport = activator == null ? null : activator.getRuntimeVersionSupport();
            if (versionSupport == null)
            {
                // Told apart from "the service answered nothing" on purpose. One message
                // covering both sends whoever debugs it looking in the wrong place, which
                // is the failure this reporting exists to prevent.
                return silent(activator == null ? "the plugin has no activator here" //$NON-NLS-1$
                    : "EDT offers no IRuntimeVersionSupport service"); //$NON-NLS-1$
            }
            version = runtimeVersion(versionSupport, project);
        }
        catch (Exception e)
        {
            return unreachable(bareName, e);
        }
        if (version == null)
        {
            return silent("IRuntimeVersionSupport named no platform version for this project"); //$NON-NLS-1$
        }
        return checkForVersion(bareName, version);
    }

    /**
     * The same question asked against an explicit platform version, which is what
     * the register is actually keyed by.
     * <p>
     * Separate from {@link #check(String, IProject)} so the answer can be pinned
     * without a workspace - in particular the empty-register case, which is the
     * one outcome that must never come back as UNKNOWN.
     * </p>
     *
     * @param bareName the type name as the caller wrote it; case-sensitive.
     * @param version a {@code com._1c.g5.v8.dt.platform.version.Version}; may be
     *            <code>null</code>, which yields CANNOT_TELL.
     * @return KNOWN / UNKNOWN when the register could answer, CANNOT_TELL otherwise.
     */
    public static Verdict checkForVersion(String bareName, Object version)
    {
        if (bareName == null || bareName.isEmpty() || version == null)
        {
            return Verdict.CANNOT_TELL;
        }
        try
        {
            Object provider = typeItemProvider(version);
            if (provider == null)
            {
                return silent("the platform bundles offer no type register for version " + version); //$NON-NLS-1$
            }
            if (!isUsable(provider, version))
            {
                return Verdict.CANNOT_TELL;
            }
            return describe(provider, bareName) != null ? Verdict.KNOWN : Verdict.UNKNOWN;
        }
        catch (Exception e)
        {
            return unreachable(bareName, e);
        }
    }

    /**
     * Reports, once, a reason the register could not be consulted, and returns
     * CANNOT_TELL.
     * <p>
     * Once, because the question is asked per type name and a broken lookup would
     * otherwise fill the log. But reported: a check that quietly stops checking is
     * the failure this whole change is against, and a silent CANNOT_TELL is exactly
     * that. One such silence - a project that never arrived here, swallowed by a
     * reflective call in a caller - cost a full build and install cycle to find,
     * because nothing anywhere said the check had not run.
     * </p>
     */
    private static Verdict silent(String reason)
    {
        if (REPORTED.add(reason))
        {
            Activator.logWarning("PlatformTypeNames: type names are NOT being checked here - " //$NON-NLS-1$
                + reason + "."); //$NON-NLS-1$
        }
        return Verdict.CANNOT_TELL;
    }

    /**
     * Every failure here is a failure to ASK, never an answer: reporting UNKNOWN
     * on a broken lookup would reject valid types.
     */
    private static Verdict unreachable(String bareName, Exception e)
    {
        Activator.logWarning("PlatformTypeNames: could not reach the platform register for '" //$NON-NLS-1$
            + bareName + "': " + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        return Verdict.CANNOT_TELL;
    }

    /**
     * True once the register for this version has answered the canary, i.e. it
     * holds a real vocabulary rather than being the empty provider a missing
     * version bundle produces.
     */
    private static boolean isUsable(Object provider, Object version) throws Exception
    {
        String key = String.valueOf(version);
        Boolean cached = USABLE_BY_VERSION.get(key);
        if (cached != null)
        {
            return cached.booleanValue();
        }
        boolean usable = describe(provider, CANARY) != null;
        USABLE_BY_VERSION.put(key, Boolean.valueOf(usable));
        if (!usable)
        {
            Activator.logWarning("PlatformTypeNames: the platform type register for version " //$NON-NLS-1$
                + key + " knows nothing (no '" + CANARY //$NON-NLS-1$
                + "'), so type names cannot be checked against it here."); //$NON-NLS-1$
        }
        return usable;
    }

    /**
     * The reflective handles into the platform register, looked up once.
     * <p>
     * A type edit can carry a composite of several names, and each one asks the
     * register; resolving the same classes and methods again per name is a cost
     * with nothing to show for it. Held in one object so there is a single
     * "either we got in or we did not" state rather than four fields that can
     * disagree.
     * </p>
     */
    private static final class Handles
    {
        final Object registry;
        final Method registryGet;
        final Object typeItemEClass;
        final Method getDescription;

        Handles() throws Exception
        {
            Class<?> mcoreLiterals =
                Class.forName("com._1c.g5.v8.dt.mcore.McorePackage$Literals"); //$NON-NLS-1$
            typeItemEClass = mcoreLiterals.getField("TYPE_ITEM").get(null); //$NON-NLS-1$
            Class<?> registryClass =
                Class.forName("com._1c.g5.v8.dt.platform.IEObjectProvider$Registry"); //$NON-NLS-1$
            registry = registryClass.getField("INSTANCE").get(null); //$NON-NLS-1$
            registryGet = findRegistryGet(registryClass);
            getDescription = Class.forName("com._1c.g5.v8.dt.platform.IEObjectProvider") //$NON-NLS-1$
                .getMethod("getEObjectDescription", String.class); //$NON-NLS-1$
        }

        /**
         * {@code Registry.get(EClass, Version)}, found by shape rather than by
         * signature: some EDT releases declare the second parameter as the Version
         * superclass, and {@code getMethod} with the concrete type misses those.
         */
        private static Method findRegistryGet(Class<?> registryClass) throws NoSuchMethodException
        {
            for (Method m : registryClass.getMethods())
            {
                if ("get".equals(m.getName()) && m.getParameterTypes().length == 2 //$NON-NLS-1$
                    && m.getParameterTypes()[0].isAssignableFrom(org.eclipse.emf.ecore.EClass.class))
                {
                    return m;
                }
            }
            throw new NoSuchMethodException("IEObjectProvider.Registry.get(EClass, Version)"); //$NON-NLS-1$
        }
    }

    /** Set once on first use; stays null when the platform classes are not there. */
    private static volatile Handles handles;

    /** Whether the one-time lookup has run, successfully or not. */
    private static volatile boolean handlesResolved;

    private static Handles handles() throws Exception
    {
        if (!handlesResolved)
        {
            synchronized (PlatformTypeNames.class)
            {
                if (!handlesResolved)
                {
                    try
                    {
                        handles = new Handles();
                    }
                    finally
                    {
                        // Set even on failure: a runtime without these classes must
                        // not pay for the lookup on every type edit.
                        handlesResolved = true;
                    }
                }
            }
        }
        return handles;
    }

    /** {@code provider.getEObjectDescription(name)} - null when the name is not in the register. */
    private static Object describe(Object provider, String name) throws Exception
    {
        Handles h = handles();
        return h == null ? null : h.getDescription.invoke(provider, name);
    }

    /**
     * {@code IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version)}.
     * The eClass is TypeItem because that is what the per-version bundles
     * register their contribution under.
     */
    private static Object typeItemProvider(Object version) throws Exception
    {
        Handles h = handles();
        return h == null ? null : h.registryGet.invoke(h.registry, h.typeItemEClass, version);
    }

    /**
     * {@code IRuntimeVersionSupport.getRuntimeVersion(IProject)}, asked of a service
     * the caller has already established is there - so a null answer means the service
     * named no version, and nothing else.
     */
    private static Object runtimeVersion(Object versionSupport, IProject project) throws Exception
    {
        Method getRuntimeVersion =
            versionSupport.getClass().getMethod("getRuntimeVersion", IProject.class); //$NON-NLS-1$
        return getRuntimeVersion.invoke(versionSupport, project);
    }
}
