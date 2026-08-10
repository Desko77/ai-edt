/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Replicates a metadata object into another project through EDT's own copier.
 * <p>
 * EDT copies objects for its own Navigator - {@code IModelObjectCopySupport} in
 * {@code com._1c.g5.v8.dt.md.copy}, with an {@code ExternalProjectCopyTarget} for the
 * copy-into-another-project case. Going through it rather than duplicating source files is what keeps
 * the identifiers, the name generation, the subsystem membership and the type descriptions consistent:
 * hand-written file surgery would have to reproduce all of that and would get it subtly wrong.
 * </p>
 * <p>
 * The service is a Guice binding rather than an OSGi service - bound in the md bundle's
 * {@code ServiceProviderModule} - so it is reached through {@code MdPlugin.getDefault().getInjector()}.
 * Both of those are public, so only the Guice types themselves are touched reflectively, which keeps
 * this bundle free of a compile dependency on Guice.
 * </p>
 */
public final class BmObjectCopyHelper
{
    private static final String MD_BUNDLE = "com._1c.g5.v8.dt.md"; //$NON-NLS-1$

    private static final String MD_PLUGIN = "com._1c.g5.v8.dt.md.MdPlugin"; //$NON-NLS-1$

    private static final String COPY_SUPPORT = "com._1c.g5.v8.dt.md.copy.IModelObjectCopySupport"; //$NON-NLS-1$

    private BmObjectCopyHelper()
    {
        // static utility
    }

    /** What a copy attempt came back with. */
    public static final class CopyResult
    {
        /** True only when the copy is present in the target project afterwards. */
        public boolean ok;

        /** Actionable failure message; non-null when {@code !ok}. */
        public String error;

        /** Structured failure category, or <code>null</code>. */
        public String failureKind;

        /** The name the copy ended up with - EDT picks it, and it is not always the source's. */
        public String copyName;
    }

    /**
     * Copies a top-level metadata object into another project.
     * <p>
     * The name is EDT's to choose: its copier generates one that does not collide in the target, so a
     * copy of {@code Документ.X} may well arrive as {@code Документ.X1}. The chosen name is reported
     * back rather than assumed, because the caller has to address the object afterwards.
     * </p>
     *
     * @param source the object to copy, not <code>null</code>
     * @param target the project to copy it into, not <code>null</code>
     * @return the outcome, never <code>null</code>
     */
    public static CopyResult copyToProject(MdObject source, IProject target)
    {
        CopyResult r = new CopyResult();
        if (source == null || target == null)
        {
            r.error = "Both the source object and the target project are required."; //$NON-NLS-1$
            r.failureKind = ErrorTags.NOT_FOUND.wire();
            return r;
        }
        try
        {
            Object copySupport = resolveCopySupport();
            if (copySupport == null)
            {
                r.error = "EDT's object copier is not available on this runtime (" + COPY_SUPPORT //$NON-NLS-1$
                    + " could not be resolved)."; //$NON-NLS-1$
                r.failureKind = ErrorTags.SERVICE_UNAVAILABLE.wire();
                return r;
            }
            Method copyAndAttach = findProjectOverload(copySupport.getClass());
            if (copyAndAttach == null)
            {
                r.error = "EDT's object copier has no copy-into-project method on this runtime."; //$NON-NLS-1$
                r.failureKind = ErrorTags.SERVICE_UNAVAILABLE.wire();
                return r;
            }
            Object copy = copyAndAttach.invoke(copySupport, source, target, new NullProgressMonitor());
            if (!(copy instanceof MdObject))
            {
                // A void-looking success is exactly what this project keeps getting caught by: the
                // caller has to see the copy, not merely the absence of an exception.
                r.error = "The copier returned nothing, so no object was created."; //$NON-NLS-1$
                r.failureKind = ErrorTags.OUTPUT_MISSING.wire();
                return r;
            }
            r.copyName = ((MdObject)copy).getName();
            r.ok = true;
            return r;
        }
        catch (Exception e)
        {
            r.error = "Copying the object failed: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
            r.failureKind = ErrorTags.INVOCATION.wire();
            return r;
        }
    }

    /**
     * Resolves the copier out of the md bundle's Guice injector.
     *
     * @return the service, or <code>null</code> when this runtime does not offer it
     * @throws Exception when the bundle is there but the injector cannot be reached
     */
    private static Object resolveCopySupport() throws Exception
    {
        Bundle bundle = org.eclipse.core.runtime.Platform.getBundle(MD_BUNDLE);
        if (bundle == null)
        {
            return null;
        }
        Class<?> pluginClass = bundle.loadClass(MD_PLUGIN);
        Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
        if (plugin == null)
        {
            return null;
        }
        Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
        if (injector == null)
        {
            return null;
        }
        Class<?> serviceIface = bundle.loadClass(COPY_SUPPORT);
        Method getInstance = injector.getClass().getMethod("getInstance", Class.class); //$NON-NLS-1$
        getInstance.setAccessible(true);
        return getInstance.invoke(injector, serviceIface);
    }

    /**
     * Picks the {@code copyAndAttach(MdObject, IProject, IProgressMonitor)} overload.
     * <p>
     * By shape rather than by exact parameter types: the interface declares the first parameter as a
     * type variable, so the compiled signature is the erasure and matching on {@code MdObject} would
     * miss it.
     * </p>
     *
     * @param type the service implementation class
     * @return the method, or <code>null</code> when no overload takes a project
     */
    private static Method findProjectOverload(Class<?> type)
    {
        for (Method m : type.getMethods())
        {
            if (!"copyAndAttach".equals(m.getName()) || m.getParameterCount() != 3) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (IProject.class.isAssignableFrom(p[1]) && !java.util.List.class.isAssignableFrom(p[0]))
            {
                return m;
            }
        }
        return null;
    }
}
