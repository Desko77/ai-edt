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
            // A handle exists whether or not a project does, so it cannot carry the
            // verdict - success has to come from the workspace.
            if (created == null || !created.exists())
            {
                r.error = "The project manager reported no error but no project '" + name //$NON-NLS-1$
                    + "' exists in the workspace."; //$NON-NLS-1$
                return r;
            }
            r.createdProjectName = created.getName();
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
        /** FQN of the object that appeared in the container - the evidence the import happened,
         *  in the form every other operation accepts. */
        public String importedObjectFqn;
        public String failureKind;
        /** Where the converted XML was left when the import failed, so the failure can
         *  be examined. Null when the import succeeded and the scratch space went. */
        public String leftoverXmlDir;
    }

    /**
     * Refuses an object the container already holds.
     * <p>
     * The environment's own import asks the user whether to replace; this operation does not ask
     * anybody, so it refuses instead. Running the factory over an existing object would write into
     * its directory while leaving files of the old one behind, and the arrival check afterwards
     * would report failure anyway, because no NEW name appeared.
     * </p>
     *
     * @param before what the container held before the conversion.
     * @param xmlDir the conversion's output, whose single XML is named after the object.
     * @return <code>null</code> when the name is free, or the refusal
     */
    private static String refuseIfAlreadyThere(java.util.Set<String> before,
        java.nio.file.Path xmlDir)
    {
        RootXml root = findRootXml(xmlDir);
        if (root.file == null)
        {
            // Not this check's business: the caller reports the missing root properly.
            return null;
        }
        String name = root.file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String objectName = dot > 0 ? name.substring(0, dot) : name;
        for (String held : before)
        {
            String tail = held.contains(".") ? held.substring(held.lastIndexOf('.') + 1) : held; //$NON-NLS-1$
            if (tail.equalsIgnoreCase(objectName))
            {
                return "'" + objectName + "' is already in this container. Replacing it is not " //$NON-NLS-1$ //$NON-NLS-2$
                    + "supported here: remove the existing object first, or import into another " //$NON-NLS-1$
                    + "container."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /** Either the object's root XML, or why it could not be picked. */
    static final class RootXml
    {
        final java.nio.file.Path file;

        final String problem;

        private RootXml(java.nio.file.Path file, String problem)
        {
            this.file = file;
            this.problem = problem;
        }
    }

    /**
     * Picks the object's root XML out of what the conversion wrote.
     * <p>
     * FOUND, not computed. Deriving the name from the binary looked right and was wrong: the XML
     * is named after the OBJECT inside it, so a file named one thing converts to an XML named
     * another, and the derived name refused a conversion that had in fact succeeded.
     * </p>
     *
     * @param xmlDir the conversion's output directory.
     * @return the single top-level XML, or the reason there is no single one
     */
    static RootXml findRootXml(java.nio.file.Path xmlDir)
    {
        java.util.List<java.nio.file.Path> roots = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> top = java.nio.file.Files.list(xmlDir))
        {
            for (java.nio.file.Path candidate : top.collect(java.util.stream.Collectors.toList()))
            {
                if (java.nio.file.Files.isRegularFile(candidate)
                    && candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".xml")) //$NON-NLS-1$
                {
                    roots.add(candidate);
                }
            }
        }
        catch (java.io.IOException cannotList)
        {
            return new RootXml(null,
                "The conversion output could not be read: " + cannotList.getMessage()); //$NON-NLS-1$
        }
        if (roots.isEmpty())
        {
            return new RootXml(null,
                "The conversion produced no XML in its output directory, so there is nothing to " //$NON-NLS-1$
                    + "import. It runs as a platform batch and traces itself in the workspace log " //$NON-NLS-1$
                    + "(.metadata/.log)."); //$NON-NLS-1$
        }
        if (roots.size() > 1)
        {
            return new RootXml(null,
                "The conversion produced " + roots.size() + " XML files at the top of its output " //$NON-NLS-1$ //$NON-NLS-2$
                    + "directory, and which one is the object is not decidable here."); //$NON-NLS-1$
        }
        return new RootXml(roots.get(0), null);
    }

    /**
     * Hands the converted XML to the environment's own import operation.
     * <p>
     * This is the step the plugin was missing. {@code IExternalObjectRestorer.restore} converts the
     * binary to XML and stops there; the Import menu then calls
     * {@code IImportOperationFactory.createImportExternalObjectOperation} on the produced file,
     * which is what actually puts the object into the container. Without it the XML was written to
     * a temporary directory and deleted unread, and the container gained nothing.
     * </p>
     * <p>
     * The root XML is found, not computed. It is named after the OBJECT inside the binary, which
     * need not match the file name: measured, a file named one thing converted to an XML named
     * after the object it contained. Deriving the name from the binary looked right and refused a
     * conversion that had in fact succeeded.
     * </p>
     *
     * @param target the container project, already checked for its nature.
     * @param xmlDir where the conversion wrote.
     * @param baseProjectName the configuration the object belongs to, named by the caller; the
     *            container does not carry it, which is why the environment's own wizard asks for
     *            it. Falls back to the container's parent when omitted.
     * @return <code>null</code> when the object was imported, or the reason it was not
     */
    private static String attachConvertedXml(IProject target, java.nio.file.Path xmlDir,
        String baseProjectName)
    {
        Object factory = Activator.getDefault() != null
            ? Activator.getDefault().getImportOperationFactory() : null;
        if (factory == null)
        {
            return "The XML was produced but no import service is reachable on this EDT runtime " //$NON-NLS-1$
                + "(com._1c.g5.v8.dt.import_.IImportOperationFactory), so nothing could put it " //$NON-NLS-1$
                + "into the container."; //$NON-NLS-1$
        }
        RootXml root = findRootXml(xmlDir);
        if (root.problem != null)
        {
            return root.problem;
        }
        java.nio.file.Path rootXml = root.file;
        try
        {
            com._1c.g5.v8.dt.core.platform.IConfigurationProject baseProject = null;
            com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager projects =
                Activator.getDefault().getExternalObjectProjectManager();
            if (baseProjectName != null && !baseProjectName.trim().isEmpty())
            {
                IProject named = ResourcesPlugin.getWorkspace().getRoot()
                    .getProject(baseProjectName.trim());
                com._1c.g5.v8.dt.core.platform.IV8ProjectManager v8 =
                    Activator.getDefault().getV8ProjectManager();
                Object candidate = named.exists() && v8 != null ? v8.getProject(named) : null;
                if (!(candidate instanceof com._1c.g5.v8.dt.core.platform.IConfigurationProject))
                {
                    return "baseProjectName '" + baseProjectName + "' is not a configuration " //$NON-NLS-1$ //$NON-NLS-2$
                        + "project in this workspace."; //$NON-NLS-1$
                }
                baseProject = (com._1c.g5.v8.dt.core.platform.IConfigurationProject)candidate;
            }
            else if (projects != null)
            {
                // Hands back the V8 project directly, not the workspace one. A container created
                // outside the environment's wizard carries no parent, and then the caller has to
                // name it: the import operation cannot proceed without knowing the configuration.
                Object parent = projects.getParentProject(target);
                if (parent instanceof com._1c.g5.v8.dt.core.platform.IConfigurationProject)
                {
                    baseProject = (com._1c.g5.v8.dt.core.platform.IConfigurationProject)parent;
                }
            }
            if (baseProject == null)
            {
                return "The container carries no base configuration, so the import operation " //$NON-NLS-1$
                    + "cannot tell which configuration the object belongs to. Name it with " //$NON-NLS-1$
                    + "baseProjectName."; //$NON-NLS-1$
            }
            com._1c.g5.v8.dt.platform.version.Version version = null;
            if (Activator.getDefault().getRuntimeVersionSupport() != null)
            {
                version = Activator.getDefault().getRuntimeVersionSupport()
                    .getRuntimeVersionOrDefault(baseProject.getProject(),
                        com._1c.g5.v8.dt.platform.version.Version.LATEST);
            }
            if (version == null)
            {
                return "The platform version of the container could not be resolved, and the " //$NON-NLS-1$
                    + "import operation needs it."; //$NON-NLS-1$
            }
            com._1c.g5.v8.dt.import_.IImportOperation operation =
                ((com._1c.g5.v8.dt.import_.IImportOperationFactory)factory)
                    .createImportExternalObjectOperation(target.getName(), version, rootXml,
                        baseProject);
            operation.setRefreshProject(true);
            operation.run(new NullProgressMonitor());
            org.eclipse.core.runtime.IStatus status = operation.getStatus();
            if (status != null && status.getSeverity() >= org.eclipse.core.runtime.IStatus.ERROR)
            {
                return "The import operation refused: " + status.getMessage(); //$NON-NLS-1$
            }
            return null;
        }
        catch (Exception | LinkageError failed)
        {
            return "The import operation failed: " + TextSuggest.safeMessage(failed); //$NON-NLS-1$
        }
    }

    /**
     * Names of the external objects a container holds, read from disk. The import
     * is verified by comparing this before and after: the restorer says nothing
     * about what it did, and the object's name is not knowable from the binary's
     * file name.
     *
     * @param container the external-object container project
     * @return the object names currently in it, empty when none or unreadable
     */
    private static java.util.Set<String> listExternalObjectDirs(IProject container)
    {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String collection : new String[] { "ExternalDataProcessors", "ExternalReports" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            java.io.File dir = new java.io.File(container.getLocation().toFile(),
                "src" + java.io.File.separator + collection); //$NON-NLS-1$
            java.io.File[] entries = dir.listFiles();
            if (entries == null)
            {
                continue;
            }
            // The directory is named in the plural, the FQN every other operation
            // takes is singular. Returning the directory key would hand the caller
            // an identifier it cannot then pass to get_metadata_details or
            // edit_metadata - a name that looks addressable and is not.
            String kind = "ExternalDataProcessors".equals(collection) //$NON-NLS-1$
                ? ExternalProjectResolver.KIND_EXTERNAL_DATA_PROCESSOR
                : ExternalProjectResolver.KIND_EXTERNAL_REPORT;
            for (java.io.File e : entries)
            {
                if (e.isDirectory())
                {
                    names.add(kind + "." + e.getName()); //$NON-NLS-1$
                }
            }
        }
        return names;
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
    public static ImportResult importExternalObject(String targetProjectName, String inputPath,
        String baseProjectName)
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
            // thick-client runtime from the target and converts the binary -> XML into xmlOutDir
            // via a 1C DESIGNER batch. It does NOT attach anything: read as bytecode, restore calls
            // convertBinaryExternalToXml and the infobase lock, and nothing else. The attaching is
            // the step below, which is what the environment's own Import menu runs next.
            java.nio.file.Path tempXmlDir = java.nio.file.Files.createTempDirectory("xml-ext-obj-"); //$NON-NLS-1$
            boolean keepXml = false;
            try
            {
                // What the container holds before the import, so afterwards the
                // question "did anything arrive" has an answer. restore() returns
                // void, and taking that for success reported an import that never
                // happened: the reply said the object was added, get_metadata_objects
                // returned the same list as before, and nothing was on disk.
                java.util.Set<String> before = listExternalObjectDirs(target);
                restorer.restore(target, binary.toPath(), tempXmlDir, new NullProgressMonitor());
                String importFailure = refuseIfAlreadyThere(before, tempXmlDir);
                if (importFailure == null)
                {
                    importFailure = attachConvertedXml(target, tempXmlDir, baseProjectName);
                }
                if (importFailure != null)
                {
                    r.error = importFailure;
                    r.leftoverXmlDir = tempXmlDir.toString();
                    keepXml = true;
                    return r;
                }
                try
                {
                    target.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                        new NullProgressMonitor());
                }
                catch (Exception refreshFailed)
                {
                    // The comparison below reads the filesystem, so a stale workspace
                    // view would only make this stricter, never laxer.
                }
                java.util.Set<String> after = listExternalObjectDirs(target);
                after.removeAll(before);
                if (after.isEmpty())
                {
                    // What is known is that the API returned without error and the project
                    // gained nothing. The two causes this used to name - a file that is not an
                    // external object, and one built by an absent platform version - are guesses,
                    // and both were false the one time this was measured: the file had been built
                    // by this plugin on this platform seconds earlier, and EDT logged the start of
                    // the conversion and then nothing at all. Naming a cause that is not
                    // established sends the caller after the wrong thing.
                    r.error = "The import returned without an error and '" + targetProjectName //$NON-NLS-1$
                        + "' gained no object. Why is not established here: EDT's importer reports " //$NON-NLS-1$
                        + "nothing back. Its own trace is in the workspace log " //$NON-NLS-1$
                        + "(.metadata/.log), where the conversion of the binary to XML is logged " //$NON-NLS-1$
                        + "as it runs; importing the same file through the EDT UI surfaces what " //$NON-NLS-1$
                        + "the API withholds."; //$NON-NLS-1$
                    r.failureKind = ErrorTags.OUTPUT_MISSING.wire();
                    // Exactly the case the XML is needed for: the operation reported nothing wrong
                    // and nothing arrived. Deleting it here removed the only evidence there was.
                    r.leftoverXmlDir = tempXmlDir.toString();
                    keepXml = true;
                    return r;
                }
                r.importedObjectFqn = after.iterator().next();
                r.ok = true;
            }
            finally
            {
                // Scratch space, deleted once the object is in. Kept only when the import failed,
                // and then its path is in the answer: without it the failure cannot be examined,
                // and deleting it was how the converted XML disappeared unread.
                if (!keepXml)
                {
                    deleteRecursively(tempXmlDir);
                }
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
