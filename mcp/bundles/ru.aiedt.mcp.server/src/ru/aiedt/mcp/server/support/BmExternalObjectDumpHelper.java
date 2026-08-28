/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Builds an external data processor / report DT project into a binary
 * {@code .epf}/{@code .erf} via EDT's {@code IExternalObjectDumper} (package
 * {@code com._1c.g5.v8.dt.platform.services.core.dump}).
 * <p>
 * The dumper is a Guice-bound singleton, NOT an OSGi service, so it is resolved
 * reflectively from the {@code PlatformServicesCore} bundle's injector -
 * mirroring {@link BmFormGeneratorHelper}'s injectorService pattern, with one
 * difference: {@code PlatformServicesCore.getInjector()} is package-private, so
 * it is reached via {@code getDeclaredMethod} + {@code setAccessible}.
 * <p>
 * {@code dump(...)} exports the object to a temporary XML directory and then
 * launches a 1C:Enterprise thick client to convert it to the binary. Hence it
 * needs a resolvable 1C:Enterprise platform runtime (always), plus an infobase
 * only when the external project is linked to a base configuration - a
 * standalone external DT project dumps with {@code infobase == null}. All
 * failures are classified into an actionable message + tag; nothing throws out.
 */
public final class BmExternalObjectDumpHelper
{
    private static final String PS_BUNDLE =
        "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$
    private static final String PS_PLUGIN =
        "com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore"; //$NON-NLS-1$
    private static final String DUMPER_FQN =
        "com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper"; //$NON-NLS-1$
    private static final String INJECTOR_IFACE = "com.google.inject.Injector"; //$NON-NLS-1$

    private BmExternalObjectDumpHelper()
    {
    }

    /** Result of resolving the external project's root object. */
    public static final class RootResolution
    {
        /** The resolved root {@code MdObject} (an EObject), or null on failure. */
        public EObject object;
        /** The resolved object name. */
        public String objectName;
        /** The object EClass name: {@code ExternalDataProcessor} / {@code ExternalReport}. */
        public String eClassName;
        /** Non-null when resolution failed (actionable message). */
        public String error;
        /** True when the failure is "not an external object project". */
        public boolean notExternalProject;
    }

    /** Outcome of a dump invocation. */
    public static final class DumpInvocation
    {
        /** True when the binary was written. */
        public boolean ok;
        /** Actionable failure message (non-null when {@code !ok}). */
        public String error;
        /** Failure category tag: runtimeNotFound / noInfobase / dumpFailed / serviceUnavailable / invocation. */
        public String failureKind;
    }

    /**
     * Resolves the external project's root object (the {@code dump} 2nd arg).
     * When {@code requestedName} is null/empty and the project has exactly one
     * external object, that one is used; when it has several, the caller must
     * name one. The returned EObject is the exact BM instance the dumper
     * validates by containment - do NOT substitute a freshly-created MdObject.
     */
    public static RootResolution resolveRoot(IProject project, String requestedName)
    {
        RootResolution r = new RootResolution();
        Activator act = Activator.getDefault();
        IDtProjectManager dtMgr = act != null ? act.getDtProjectManager() : null;
        IV8ProjectManager v8Mgr = act != null ? act.getV8ProjectManager() : null;
        if (dtMgr == null || v8Mgr == null)
        {
            r.error = "Project managers are not available on this EDT runtime."; //$NON-NLS-1$
            return r;
        }
        IV8Project v8p;
        try
        {
            IDtProject dtProject = dtMgr.getDtProject(project);
            if (dtProject == null)
            {
                r.error = "Project '" + project.getName() //$NON-NLS-1$
                    + "' is not a DT project (not indexed yet)."; //$NON-NLS-1$
                return r;
            }
            v8p = v8Mgr.getProject(dtProject);
        }
        catch (Exception e)
        {
            r.error = "Failed to resolve the V8 project: " + msg(e); //$NON-NLS-1$
            return r;
        }
        if (!(v8p instanceof IExternalObjectProject))
        {
            r.notExternalProject = true;
            r.error = "Project '" + project.getName() + "' is not an external object " //$NON-NLS-1$ //$NON-NLS-2$
                + "(.epf/.erf) DT project. export_object builds ExternalDataProcessor / " //$NON-NLS-1$
                + "ExternalReport projects created via external_object_workshop."; //$NON-NLS-1$
            return r;
        }
        Collection<MdObject> objs;
        try
        {
            objs = ((IExternalObjectProject) v8p).getExternalObjects();
        }
        catch (Exception e)
        {
            r.error = "Failed to read the project's external objects: " + msg(e); //$NON-NLS-1$
            return r;
        }
        if (objs == null || objs.isEmpty())
        {
            r.error = "The external object project has no root object."; //$NON-NLS-1$
            return r;
        }
        MdObject match = null;
        if (requestedName != null && !requestedName.isEmpty())
        {
            for (MdObject o : objs)
            {
                if (requestedName.equals(o.getName()))
                {
                    match = o;
                    break;
                }
            }
            if (match == null)
            {
                r.error = "No external object named '" + requestedName //$NON-NLS-1$
                    + "' in project '" + project.getName() + "'. Known values: " //$NON-NLS-1$ //$NON-NLS-2$
                    + names(objs);
                return r;
            }
        }
        else if (objs.size() > 1)
        {
            r.error = "Project '" + project.getName() + "' has multiple external " //$NON-NLS-1$ //$NON-NLS-2$
                + "objects (" + names(objs) + "); pass objectName to pick one."; //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }
        else
        {
            match = objs.iterator().next();
        }
        r.object = match;
        r.objectName = match.getName();
        r.eClassName = match.eClass() != null ? match.eClass().getName() : null;
        return r;
    }

    /** {@code .epf} for ExternalDataProcessor, {@code .erf} for ExternalReport, else null. */
    public static String extensionForEClass(String eClassName)
    {
        if ("ExternalReport".equals(eClassName)) //$NON-NLS-1$
        {
            return ".erf"; //$NON-NLS-1$
        }
        if ("ExternalDataProcessor".equals(eClassName)) //$NON-NLS-1$
        {
            return ".epf"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolves the Guice-bound {@code IExternalObjectDumper} singleton from the
     * PlatformServicesCore injector. Returns null when the bundle / plugin /
     * injector / binding is unreachable (older or stripped EDT runtime).
     */
    public static Object resolveDumper()
    {
        try
        {
            Bundle b = Platform.getBundle(PS_BUNDLE);
            if (b == null)
            {
                return null;
            }
            Class<?> pluginClass = b.loadClass(PS_PLUGIN);
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin == null)
            {
                return null;
            }
            // PlatformServicesCore.getInjector() is package-private (unlike
            // FormPlugin.getInjector()); reach it via getDeclaredMethod.
            Method giM = pluginClass.getDeclaredMethod("getInjector"); //$NON-NLS-1$
            giM.setAccessible(true);
            Object injector = giM.invoke(plugin);
            if (injector == null)
            {
                return null;
            }
            Class<?> dumperC = b.loadClass(DUMPER_FQN);
            // Call getInstance through the com.google.inject.Injector interface
            // (the concrete InjectorImpl is x-internal - reflecting on it throws
            // IllegalAccessException). Same trick as BmFormGeneratorHelper.
            Class<?> injectorIface = b.loadClass(INJECTOR_IFACE);
            return injectorIface.getMethod("getInstance", Class.class) //$NON-NLS-1$
                .invoke(injector, dumperC);
        }
        catch (Throwable e)
        {
            Activator.logWarning("resolveDumper() failed: " + msg(e)); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Invokes {@code dumper.dump(project, object, Paths.get(outputPath), null)}.
     * The {@code dump} Method is taken from the {@code IExternalObjectDumper}
     * interface class (not the x-internal impl) so the reflective invoke is
     * accessible. All throwables are captured and classified.
     */
    public static DumpInvocation dump(Object dumper, IProject project, EObject object,
        String outputPath)
    {
        DumpInvocation r = new DumpInvocation();
        if (dumper == null)
        {
            r.error = "IExternalObjectDumper is not available on this EDT runtime."; //$NON-NLS-1$
            r.failureKind = ErrorTags.SERVICE_UNAVAILABLE.wire();
            return r;
        }
        try
        {
            Bundle b = Platform.getBundle(PS_BUNDLE);
            if (b == null)
            {
                r.error = "The platform-services bundle is not available."; //$NON-NLS-1$
                r.failureKind = ErrorTags.SERVICE_UNAVAILABLE.wire();
                return r;
            }
            // Take the dump Method from the interface class (not the x-internal
            // impl) so the reflective invoke stays accessible.
            Class<?> dumperC = b.loadClass(DUMPER_FQN);
            Method dumpM = dumperC.getMethod("dump", IProject.class, EObject.class, //$NON-NLS-1$
                Path.class, IProgressMonitor.class);
            Path out = Paths.get(outputPath);
            dumpM.invoke(dumper, project, object, out, (IProgressMonitor) null);
            // The dumper returns void, so "it did not throw" says nothing about a
            // file having appeared. Its siblings in this plugin check their output
            // and report outputMissing; this one reported success and left the
            // caller to discover the absence later, with the operation that failed
            // several steps behind. An empty file counts as no file: the platform
            // creates one before writing into it.
            java.io.File written = out.toFile();
            if (!written.isFile() || written.length() == 0L)
            {
                r.error = "The dump reported no error but no file was written to " + outputPath //$NON-NLS-1$
                    + ". The object may not be buildable, or the platform may have exited before " //$NON-NLS-1$
                    + "writing - check the project for validation errors first."; //$NON-NLS-1$
                r.failureKind = ErrorTags.OUTPUT_MISSING.wire();
                return r;
            }
            r.ok = true;
            return r;
        }
        catch (InvocationTargetException ite)
        {
            classifyFailure(r, ite.getCause() != null ? ite.getCause() : ite);
            return r;
        }
        catch (Throwable e)
        {
            r.error = "Dump invocation failed: " + msg(e); //$NON-NLS-1$
            r.failureKind = ErrorTags.INVOCATION.wire();
            return r;
        }
    }

    /**
     * Maps a dump failure cause chain to an actionable message + tag.
     * <p>
     * Package-visible so the classification can be tested against a cause chain directly: it turns
     * on prose the platform writes in the IDE's language, which is precisely the kind of matching
     * that stops working without anyone noticing.
     * </p>
     *
     * @param r the invocation being classified, not <code>null</code>
     * @param cause the failure, whose whole cause chain is read
     */
    static void classifyFailure(DumpInvocation r, Throwable cause)
    {
        String chain = causeChainText(cause);
        String lower = chain.toLowerCase(Locale.ROOT);
        if (lower.contains("matchingruntimenotfound") //$NON-NLS-1$
            || (lower.contains("runtime") //$NON-NLS-1$
                && (lower.contains("not found") || lower.contains("no matching") //$NON-NLS-1$ //$NON-NLS-2$
                    || lower.contains("cannot be resolved")))) //$NON-NLS-1$
        {
            r.failureKind = ErrorTags.RUNTIME_NOT_FOUND.wire();
            r.error = "No resolvable 1C:Enterprise platform runtime for this project. " //$NON-NLS-1$
                + "Install a matching 1C:Enterprise platform version and associate it " //$NON-NLS-1$
                + "with the project (its platform / runtime installation), then retry. " //$NON-NLS-1$
                + "The thick client performs the actual .epf/.erf build, so a runtime " //$NON-NLS-1$
                + "is required. Underlying: " + firstLine(chain); //$NON-NLS-1$
            return;
        }
        // The Russian wording is matched too, because the platform speaks the language the IDE runs
        // in: on a Russian EDT every one of the English markers above misses, both actionable
        // diagnoses collapse into the generic "could not be built" below, and the agent is told to
        // look at the build when what it actually needs is an infobase.
        if (lower.contains("no developing infobase") //$NON-NLS-1$
            || lower.contains("developing infobase applications") //$NON-NLS-1$
            || lower.contains("developing application with an infobase") //$NON-NLS-1$
            || lower.contains("\u0440\u0430\u0437\u0440\u0430\u0431\u0430\u0442\u044b\u0432\u0430\u0435\u043c\u044b\u0445 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439 \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u043e\u043d\u043d\u043e\u0439 \u0431\u0430\u0437\u044b")) //$NON-NLS-1$
        {
            r.failureKind = ErrorTags.NO_INFOBASE.wire();
            r.error = "The external object's base configuration has no developing " //$NON-NLS-1$
                + "infobase application. Create/associate an infobase for the base " //$NON-NLS-1$
                + "project and set its connection credentials, then retry. (A standalone " //$NON-NLS-1$
                + "external object with no base configuration does not need one.) " //$NON-NLS-1$
                + "Underlying: " + firstLine(chain); //$NON-NLS-1$
            return;
        }
        r.failureKind = ErrorTags.DUMP_FAILED.wire();
        r.error = "Error: the external object could not be built: " + firstLine(chain); //$NON-NLS-1$
    }

    private static String causeChainText(Throwable t)
    {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        Throwable c = t;
        while (c != null && depth < 8)
        {
            if (sb.length() > 0)
            {
                sb.append(" | "); //$NON-NLS-1$
            }
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null)
            {
                sb.append(": ").append(c.getMessage()); //$NON-NLS-1$
            }
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static final String NEWLINE = "\n"; //$NON-NLS-1$

    /** How much of the platform's own report to carry back, in characters. */
    private static final int DETAIL_BUDGET = 700;

    /**
     * The platform's report, kept whole enough to name the reason.
     * <p>
     * It arrives as a block of lines: a header naming the platform version, the command that ran,
     * the paths it used, and - at the end - what actually went wrong. Taking the first line alone
     * returned the header: a sentence ending in a colon with nothing after it. The caller was told
     * the build failed and never told why, while the reason sat in the log.
     * </p>
     * <p>
     * Measured on the stand: an attribute typed Stirng made the dump fail with "unknown type name -
     * Stirng" seven lines in, and the answer stopped at "1C:Enterprise 8.3.27.2214:".
     * </p>
     *
     * @param s the cause chain text; may be <code>null</code>
     * @return the lines worth reading, joined by " | "; never <code>null</code>
     */
    private static String firstLine(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> alreadySaid = new java.util.LinkedHashSet<>();
        for (String raw : s.split(NEWLINE))
        {
            String line = raw.trim();
            // The platform says the reason twice - once in its summary, once in the detail.
            if (line.isEmpty() || !alreadySaid.add(line))
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(" | "); //$NON-NLS-1$
            }
            sb.append(line);
            if (sb.length() >= DETAIL_BUDGET)
            {
                sb.append("..."); //$NON-NLS-1$
                break;
            }
        }
        return sb.toString();
    }

    private static String names(Collection<MdObject> objs)
    {
        StringBuilder sb = new StringBuilder();
        for (MdObject o : objs)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(o.getName());
        }
        return sb.toString();
    }

    private static String msg(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
