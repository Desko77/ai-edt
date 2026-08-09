/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import ru.aiedt.mcp.server.Activator;

/**
 * Creates a configuration-extension EDT project from a base configuration
 * project for {@code extension_workshop create_extension_project}.
 * <p>
 * Wraps EDT's {@code IExtensionProjectManager.create(String name, Version,
 * Configuration extConfig, IProject baseProject, IProgressMonitor)}. That call
 * writes the new project's {@code DT-INF/PROJECT.PMF}, the
 * {@code V8ExtensionNature}, and the extension's {@code Configuration.mdo}.
 * <p>
 * The {@code Configuration} handed to {@code create} is NOT the base project's
 * live config (attaching that fails with "object already attached") - it is a
 * fresh, detached extension shell produced by
 * {@code IModelObjectAdopter.adopt(baseConfig, version, monitor)}, with its name
 * and (optional) namePrefix set before the call. This mirrors
 * {@code ExtensionWizard.adopt()} in {@code com._1c.g5.v8.dt.md.ui.extension}.
 * <p>
 * The manager is obtained from {@code Activator}'s OSGi {@code ServiceTracker}
 * (it is an {@code IManagedService}, registered like the other 14 EDT services
 * the activator tracks - no leak, no manual {@code getService}/{@code ungetService}).
 * The platform {@link Version} for the new extension is resolved from
 * the base project via {@code IRuntimeVersionSupport.getRuntimeVersion(IProject)}.
 * <p>
 * The create call runs on the calling (MCP worker) thread, not the SWT UI
 * thread: it is a workspace mutation and Eclipse runs those off the UI thread
 * (the closest precedent, {@code import_configuration_from_xml}, does the same).
 */
public final class BmExtensionProjectHelper
{
    private BmExtensionProjectHelper()
    {
        // utility class
    }

    /**
     * Outcome of a create-extension-project attempt.
     */
    public static final class CreateResult
    {
        public boolean ok;
        /** Project of that name already existed - nothing was created. */
        public boolean alreadyExists;
        /** EDT extension-project manager service not reachable on this runtime. */
        public boolean serviceNotFound;
        public String error;
        public String hint;
        public String createdProjectName;
        /** True when namePrefix was requested and successfully applied. */
        public boolean namePrefixApplied;
    }

    /**
     * Creates a new configuration extension {@code newName} whose base is
     * {@code baseProject}. When {@code namePrefix} is non-empty it is applied to
     * the created extension's {@code Configuration} after creation (best-effort).
     *
     * @param newName the new extension project name (must not already exist)
     * @param baseProject the base configuration project (must be a configuration)
     * @param namePrefix optional object name prefix for the extension; null/blank
     *     leaves the EDT default
     * @return structured {@link CreateResult}
     */
    public static CreateResult createExtensionProject(String newName, IProject baseProject,
        String namePrefix)
    {
        CreateResult r = new CreateResult();
        if (newName == null || newName.trim().isEmpty())
        {
            r.error = "projectName (new extension name) is required"; //$NON-NLS-1$
            return r;
        }
        newName = newName.trim();
        if (baseProject == null || !baseProject.exists())
        {
            r.error = "Base project not found or not open"; //$NON-NLS-1$
            return r;
        }
        // Guard: target project name must be free.
        IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(newName);
        if (target.exists())
        {
            r.alreadyExists = true;
            r.ok = true;
            r.createdProjectName = newName;
            r.hint = "A project named '" + newName //$NON-NLS-1$
                + "' already exists - nothing created. NOTE: its type was not verified; " //$NON-NLS-1$
                + "if it is not the extension you intended (e.g. a plain configuration or an " //$NON-NLS-1$
                + "extension of a different base), choose a different projectName."; //$NON-NLS-1$
            return r;
        }

        // 1. Base configuration (the parent the extension adopts from).
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            r.serviceNotFound = true;
            r.error = "IConfigurationProvider not available on this EDT runtime"; //$NON-NLS-1$
            return r;
        }
        Configuration baseConfig = configProvider.getConfiguration(baseProject);
        if (baseConfig == null)
        {
            r.error = "Parent project '" + baseProject.getName() //$NON-NLS-1$
                + "' is not a configuration project (no Configuration resolved). " //$NON-NLS-1$
                + "Pass a base CONFIGURATION project (not an extension or a non-1C project)."; //$NON-NLS-1$
            return r;
        }

        // 2. Platform version for the new extension - take the base project's
        // runtime version. getRuntimeVersion(IProject) is impl-only, so resolve
        // reflectively (mirrors BmDefinedTypeHelper#createCanonicalPrimitiveProxy).
        Version version = resolveRuntimeVersion(baseProject);
        if (version == null)
        {
            r.error = "Could not resolve the platform runtime version of base project '" //$NON-NLS-1$
                + baseProject.getName() + "'. Ensure the base project is fully loaded/indexed."; //$NON-NLS-1$
            return r;
        }

        // 3. The model-object adopter builds the EXTENSION's own Configuration
        // shell (objectBelonging=Adopted) - this detached copy is what EDT attaches
        // as the new project's root. Passing the base project's LIVE Configuration
        // to create() fails with "object already attached"; the adopter returns a
        // fresh detached one. Mirrors ExtensionWizard.adopt() in
        // com._1c.g5.v8.dt.md.ui.extension.
        Object adopter = BmExtensionHelper.resolveModelObjectAdopter();
        if (adopter == null)
        {
            r.serviceNotFound = true;
            r.error = "IModelObjectAdopter not reachable on this EDT runtime"; //$NON-NLS-1$
            r.hint = "Create the extension via EDT GUI: File - New - 1C Extension Project."; //$NON-NLS-1$
            return r;
        }

        // 4. The extension-project manager (managed service).
        IExtensionProjectManager mgr = resolveExtensionProjectManager();
        if (mgr == null)
        {
            r.serviceNotFound = true;
            r.error = "IExtensionProjectManager not reachable on this EDT runtime"; //$NON-NLS-1$
            r.hint = "Create the extension via EDT GUI: File - New - 1C Extension Project."; //$NON-NLS-1$
            return r;
        }

        // 5. Build the detached extension Configuration, then create the project.
        // Runs on the calling (worker) thread - workspace mutation, not UI (the
        // closest precedent, import_configuration_from_xml, does the same).
        try
        {
            IProgressMonitor monitor = new NullProgressMonitor();
            Configuration extConfig = adoptConfiguration(adopter, baseConfig, version, monitor);
            if (extConfig == null)
            {
                r.error = "Adopter did not return a Configuration for the new extension " //$NON-NLS-1$
                    + "(IModelObjectAdopter.adopt returned null or an unexpected type)."; //$NON-NLS-1$
                return r;
            }
            // Give the extension its own Configuration name (the adopter copies the
            // base name) and the requested object name prefix - set on the detached
            // shell BEFORE create attaches it, the same order the EDT wizard uses.
            extConfig.setName(newName);
            if (namePrefix != null && !namePrefix.trim().isEmpty())
            {
                extConfig.setNamePrefix(namePrefix.trim());
                r.namePrefixApplied = true;
            }
            IProject created = mgr.create(newName, version, extConfig, baseProject, monitor);
            if (created == null)
            {
                // Fall back to the workspace handle for the requested name.
                created = ResourcesPlugin.getWorkspace().getRoot().getProject(newName);
            }
            // A handle exists whether or not a project does, so it cannot carry the
            // verdict - success has to come from the workspace.
            if (created == null || !created.exists())
            {
                r.error = "The project manager reported no error but no project '" + newName //$NON-NLS-1$
                    + "' exists in the workspace."; //$NON-NLS-1$
                return r;
            }
            r.createdProjectName = created.getName();
            r.ok = true;
        }
        catch (Exception t)
        {
            r.error = "create extension project failed: " + TextSuggest.safeMessage(t); //$NON-NLS-1$
            Activator.logError("createExtensionProject(" + newName + ") failed", t); //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }
        return r;
    }

    /**
     * Builds the extension's detached {@code Configuration} shell from the base
     * config via {@code IModelObjectAdopter.adopt(EObject, Version, monitor)} (the
     * same call {@code ExtensionWizard.adopt} uses). The result is NOT attached to
     * any BM, so the caller can set name/namePrefix and hand it to
     * {@code IExtensionProjectManager.create}. Returns null when the adopter
     * yields nothing usable.
     */
    private static Configuration adoptConfiguration(Object adopter, Configuration baseConfig,
        Version version, IProgressMonitor monitor) throws Exception
    {
        java.lang.reflect.Method adopt = adopter.getClass().getMethod("adopt", //$NON-NLS-1$
            org.eclipse.emf.ecore.EObject.class, Version.class,
            org.eclipse.core.runtime.IProgressMonitor.class);
        Object result = adopt.invoke(adopter, baseConfig, version, monitor);
        return result instanceof Configuration ? (Configuration) result : null;
    }

    /**
     * Resolves the base project's platform {@link Version} via
     * {@link IRuntimeVersionSupport#getRuntimeVersion(IProject)} (present on the
     * interface in EDT 2026.1 / platform 12.1). Returns null on any failure.
     */
    private static Version resolveRuntimeVersion(IProject baseProject)
    {
        try
        {
            IRuntimeVersionSupport versionSupport = Activator.getDefault().getRuntimeVersionSupport();
            return versionSupport == null ? null : versionSupport.getRuntimeVersion(baseProject);
        }
        catch (Throwable t)
        {
            Activator.logWarning("resolveRuntimeVersion failed: " //$NON-NLS-1$
                + TextSuggest.safeMessage(t));
            return null;
        }
    }

    /**
     * Resolves {@link IExtensionProjectManager} from {@code Activator}'s
     * {@code ServiceTracker}. Returns null when the managed service is not
     * reachable on this EDT runtime.
     */
    private static IExtensionProjectManager resolveExtensionProjectManager()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getExtensionProjectManager();
    }
}
