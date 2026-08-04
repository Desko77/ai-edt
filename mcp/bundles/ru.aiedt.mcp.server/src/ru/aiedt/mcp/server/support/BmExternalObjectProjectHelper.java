/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectRestorer;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import ru.aiedt.mcp.server.Activator;

/**
 * Creates an External Data Processor (.epf) or External Report (.erf) DT project
 * for {@code external_object_workshop create}.
 * <p>
 * Wraps EDT's {@code IExternalObjectProjectManager.create(String name, Version,
 * MdObject externalObject, IProject parentProject, IProgressMonitor)}. That call
 * writes the new project's manifest, the external-object Eclipse nature and the
 * root object's {@code .mdo}. Unlike an extension there is no adopter step - the
 * root object is a fresh {@code ExternalDataProcessor}/{@code ExternalReport}
 * built from {@link MdClassFactory} with its name set before the call.
 * <p>
 * {@code parentProject} is optional: when given (a 1C configuration) it supplies
 * the platform {@link Version} and type-resolution context; otherwise the version
 * is taken from any open 1C project in the workspace. The manager comes from the
 * Activator's OSGi {@code ServiceTracker} (an {@code IManagedService}). The create
 * runs on the calling (MCP worker) thread - a workspace mutation, off the UI thread.
 */
public final class BmExternalObjectProjectHelper
{
    private BmExternalObjectProjectHelper()
    {
        // utility class
    }

    /** Outcome of a create-external-object-project attempt. */
    public static final class CreateResult
    {
        public boolean ok;
        /** Project of that name already existed - nothing was created. */
        public boolean alreadyExists;
        /** EDT external-object project manager service not reachable on this runtime. */
        public boolean serviceNotFound;
        public String error;
        public String hint;
        public String createdProjectName;
        /** {@code ExternalDataProcessor} or {@code ExternalReport}. */
        public String kind;
        /** Root object FQN, e.g. {@code ExternalDataProcessor.MyTool}. */
        public String rootFqn;
    }

    /**
     * Creates a new external data processor / external report DT project.
     *
     * @param name new project and root-object name (must not already exist)
     * @param kind {@code ExternalDataProcessor} / {@code ExternalReport} (English or
     *     the Russian aliases {@code ВнешняяОбработка} / {@code ВнешнийОтчет})
     * @param parentProject optional parent 1C configuration (version + type context);
     *     {@code null} for a standalone object
     * @return structured {@link CreateResult}
     */
    public static CreateResult createExternalObjectProject(String name, String kind, IProject parentProject)
    {
        CreateResult r = new CreateResult();
        if (name == null || name.trim().isEmpty())
        {
            r.error = "name is required (the new external object / project name)"; //$NON-NLS-1$
            return r;
        }
        name = name.trim();

        Boolean isReport = parseKind(kind);
        if (isReport == null)
        {
            r.error = "kind must be ExternalDataProcessor or ExternalReport (got '" + kind + "')."; //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }

        IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (target.exists())
        {
            r.alreadyExists = true;
            r.ok = true;
            r.createdProjectName = name;
            r.kind = isReport ? ExternalProjectResolver.KIND_EXTERNAL_REPORT
                : ExternalProjectResolver.KIND_EXTERNAL_DATA_PROCESSOR;
            r.rootFqn = r.kind + "." + name; //$NON-NLS-1$
            r.hint = "A project named '" + name + "' already exists - nothing created. Its type was not " //$NON-NLS-1$ //$NON-NLS-2$
                + "verified; choose a different name if it is not the external object you intended."; //$NON-NLS-1$
            return r;
        }

        IExternalObjectProjectManager mgr =
            Activator.getDefault() != null ? Activator.getDefault().getExternalObjectProjectManager() : null;
        if (mgr == null)
        {
            r.serviceNotFound = true;
            r.error = "IExternalObjectProjectManager not reachable on this EDT runtime."; //$NON-NLS-1$
            r.hint = "Create it via EDT GUI: File - New - External Data Processor / External Report."; //$NON-NLS-1$
            return r;
        }

        Version version = resolveVersion(parentProject);
        if (version == null)
        {
            r.error = "Could not resolve the platform version. Pass parentProject (a 1C configuration) " //$NON-NLS-1$
                + "or have at least one open 1C project in the workspace."; //$NON-NLS-1$
            return r;
        }

        try
        {
            IProgressMonitor monitor = new NullProgressMonitor();
            MdObject externalObject = isReport
                ? MdClassFactory.eINSTANCE.createExternalReport()
                : MdClassFactory.eINSTANCE.createExternalDataProcessor();
            externalObject.setName(name);
            externalObject.setUuid(UUID.randomUUID());
            IProject created = mgr.create(name, version, externalObject,
                (parentProject != null && parentProject.isAccessible()) ? parentProject : null, monitor);
            if (created == null)
            {
                created = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            }
            r.createdProjectName = created != null ? created.getName() : name;
            r.kind = isReport ? ExternalProjectResolver.KIND_EXTERNAL_REPORT
                : ExternalProjectResolver.KIND_EXTERNAL_DATA_PROCESSOR;
            r.rootFqn = r.kind + "." + name; //$NON-NLS-1$
            r.ok = true;
        }
        catch (Exception t)
        {
            r.error = "create external object project failed: " + TextSuggest.safeMessage(t); //$NON-NLS-1$
            Activator.logError("createExternalObjectProject(" + name + ") failed", t); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return r;
    }

    /** Outcome of an import-external-object-into-container attempt. */
    public static final class ImportResult
    {
        public boolean ok;
        public String error;
        public String hint;
        public String targetProject;
        public String inputPath;
    }

    /**
     * Imports an external data processor / report ({@code .epf} / {@code .erf} binary) INTO an
     * EXISTING external-object container project (a {@code V8ExternalObjectsNature} multi-object
     * project), adding it as another object - the same path EDT GUI "Import" takes. The container is
     * typically parent-bound to a configuration, so the imported object's types resolve (no markers).
     * <p>
     * Backs {@code external_object_workshop operation=import_external_object}. Delegates to EDT's
     * {@link IExternalObjectRestorer#restore(IProject, java.nio.file.Path, java.nio.file.Path,
     * IProgressMonitor)}, which converts the binary to XML via the 1C thick client and attaches the
     * object to the target project. This is NOT for creating a new standalone project - use
     * {@link #createExternalObjectProject(String, String, IProject)} for that.
     *
     * @param targetProjectName an existing external-object container project
     * @param inputPath absolute path of the {@code .epf} / {@code .erf} file
     * @return structured {@link ImportResult}
     */
    public static ImportResult importExternalObject(String targetProjectName, String inputPath)
    {
        ImportResult r = new ImportResult();
        r.targetProject = targetProjectName;
        r.inputPath = inputPath;
        if (targetProjectName == null || targetProjectName.trim().isEmpty())
        {
            r.error = "targetProjectName is required (an existing V8ExternalObjectsNature container " //$NON-NLS-1$
                + "project to import INTO)."; //$NON-NLS-1$
            return r;
        }
        if (inputPath == null || inputPath.trim().isEmpty())
        {
            r.error = "inputPath is required (the .erf / .epf binary to import)."; //$NON-NLS-1$
            return r;
        }

        IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(targetProjectName.trim());
        if (!target.exists() || !target.isAccessible())
        {
            r.error = "Target project '" + targetProjectName + "' not found or not open."; //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }
        // Fail fast on a non-container target - IExternalObjectRestorer.restore rejects it too, but
        // with a bare IllegalArgumentException; give the agent the actionable reason.
        boolean isContainer;
        try
        {
            isContainer = target.hasNature("com._1c.g5.v8.dt.core.V8ExternalObjectsNature"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            isContainer = false;
        }
        if (!isContainer)
        {
            r.error = "Target project '" + targetProjectName + "' is not a V8ExternalObjectsNature " //$NON-NLS-1$ //$NON-NLS-2$
                + "multi-object container. import_external_object adds an object to an EXISTING " //$NON-NLS-1$
                + "external-object container; for a new standalone object use external_object_workshop " //$NON-NLS-1$
                + "operation=create."; //$NON-NLS-1$
            return r;
        }

        java.io.File binary = new java.io.File(inputPath.trim());
        if (!binary.isFile())
        {
            r.error = "inputPath is not an existing file: " + inputPath; //$NON-NLS-1$
            return r;
        }

        IExternalObjectRestorer restorer =
            Activator.getDefault() != null ? Activator.getDefault().getExternalObjectRestorer() : null;
        if (restorer == null)
        {
            r.error = "IExternalObjectRestorer service not reachable on this EDT runtime."; //$NON-NLS-1$
            r.hint = "Import via EDT GUI: File - Import - External Data Processor / Report, target the " //$NON-NLS-1$
                + "container project."; //$NON-NLS-1$
            return r;
        }

        try
        {
            // 4-arg restore (target, binaryFile, xmlOutDir, monitor): EDT resolves the infobase +
            // thick-client runtime from the target, converts the binary -> XML into xmlOutDir via a
            // 1C DESIGNER batch, then attaches the object. Create a temp dir for the XML output
            // (matches the EDT GUI flow). The 3-arg overload exists only in the newer runtime API,
            // not the build-target API, so the 4-arg with a temp dir is the portable call.
            java.nio.file.Path tempXmlDir = java.nio.file.Files.createTempDirectory("xml-ext-obj-"); //$NON-NLS-1$
            try
            {
                restorer.restore(target, binary.toPath(), tempXmlDir, new NullProgressMonitor());
                r.ok = true;
            }
            finally
            {
                // The XML is scratch space for the binary -> XML conversion; EDT has read what it
                // needs by the time restore returns. Left behind, a whole configuration's worth of
                // XML accumulates in the system temp on every import, and nothing ever collects it.
                deleteRecursively(tempXmlDir);
            }
        }
        catch (Throwable t)
        {
            r.error = "import external object failed: " + TextSuggest.safeMessage(t); //$NON-NLS-1$
            Activator.logError("importExternalObject(" + targetProjectName + ", " + inputPath + ") failed", t); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return r;
    }

    /**
     * Removes a scratch directory and everything under it. Failures are logged, never propagated:
     * losing the cleanup must not turn a successful import into a reported failure.
     *
     * @param root the directory to remove; ignored when <code>null</code>
     */
    private static void deleteRecursively(java.nio.file.Path root)
    {
        if (root == null)
        {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    java.nio.file.Files.deleteIfExists(path);
                }
                catch (java.io.IOException perFile)
                {
                    Activator.logWarning("Could not delete scratch file " + path + ": " //$NON-NLS-1$ //$NON-NLS-2$
                        + perFile.getMessage());
                }
            });
        }
        catch (java.io.IOException e)
        {
            Activator.logWarning("Could not clean scratch directory " + root + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** @return {@code Boolean.TRUE} for report, {@code FALSE} for data processor, {@code null} if unknown. */
    private static Boolean parseKind(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        String k = kind.trim();
        if (ExternalProjectResolver.KIND_EXTERNAL_DATA_PROCESSOR.equalsIgnoreCase(k)
            || "ВнешняяОбработка".equalsIgnoreCase(k)) //$NON-NLS-1$
        {
            return Boolean.FALSE;
        }
        if (ExternalProjectResolver.KIND_EXTERNAL_REPORT.equalsIgnoreCase(k)
            || "ВнешнийОтчет".equalsIgnoreCase(k) || "ВнешнийОтчёт".equalsIgnoreCase(k)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Boolean.TRUE;
        }
        return null;
    }

    /**
     * Platform {@link Version}: from {@code parentProject} when given, else from any
     * open 1C project in the workspace (the external object runs on that platform).
     * Returns null when nothing resolves.
     */
    private static Version resolveVersion(IProject parentProject)
    {
        IRuntimeVersionSupport versionSupport =
            Activator.getDefault() != null ? Activator.getDefault().getRuntimeVersionSupport() : null;
        if (versionSupport == null)
        {
            return null;
        }
        if (parentProject != null && parentProject.isAccessible())
        {
            Version v = runtimeVersion(versionSupport, parentProject);
            if (v != null)
            {
                return v;
            }
        }
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (p == parentProject || !p.isAccessible())
            {
                continue;
            }
            Version v = runtimeVersion(versionSupport, p);
            if (v != null)
            {
                return v;
            }
        }
        return null;
    }

    private static Version runtimeVersion(IRuntimeVersionSupport versionSupport, IProject project)
    {
        try
        {
            return versionSupport.getRuntimeVersion(project);
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
