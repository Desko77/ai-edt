/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefaultDataLockControlMode;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import ru.aiedt.mcp.server.Activator;

/**
 * Creates a base 1C:Configuration DT project for {@code create_project}.
 * <p>
 * Wraps EDT's {@code IConfigurationProjectManager.create(String name, Version,
 * Configuration, IProgressMonitor)} (4-arg, throws CoreException). That call
 * writes the new project's {@code DT-INF/PROJECT.PMF}, the configuration Eclipse
 * nature and the root {@code Configuration.mdo}. Unlike an extension there is no
 * adopter step and no parent project - the root {@link Configuration} is a fresh,
 * detached shell built from {@link MdClassFactory}. EDT accepts the shell as-is
 * and fills only the name, so everything else the root must carry is set here: a
 * uuid, a compatibility mode matching the runtime version (see
 * {@code compatibilityModeFor}) and managed data locking. Each was left at the
 * model default once, and each produced a project that opened with findings on a
 * configuration nobody had edited yet.
 * <p>
 * The platform {@link Version}: a fresh base configuration has no parent project,
 * so {@code IRuntimeVersionSupport.getRuntimeVersion(IProject)} cannot be used.
 * When the caller supplies {@code version} it is parsed strictly; otherwise the
 * latest version this EDT installation supports is used (the same default the
 * New Project wizard applies). The manager comes from the Activator's OSGi
 * {@code ServiceTracker} (an {@code IManagedService}). The create runs on the
 * calling (MCP worker) thread - a workspace mutation, off the UI thread, the same
 * as {@code import_configuration_from_xml} and the extension/external-object
 * project creators.
 */
public final class BmConfigurationProjectHelper
{
    private BmConfigurationProjectHelper()
    {
        // utility class
    }

    /** Outcome of a create-configuration-project attempt. */
    public static final class CreateResult
    {
        public boolean ok;
        /** Project of that name already existed - nothing was created. */
        public boolean alreadyExists;
        /** EDT configuration-project manager service not reachable on this runtime. */
        public boolean serviceNotFound;
        public String error;
        public String hint;
        public String createdProjectName;
        /** Resolved platform version, e.g. {@code 8.3.27}. */
        public String version;
    }

    /**
     * Creates a new base 1C:Configuration DT project.
     *
     * @param name new project (and Configuration) name; must not already exist
     * @param versionStr optional platform version, e.g. {@code 8.3.21}; {@code null}/blank
     *     selects the latest version this EDT installation supports
     * @return structured {@link CreateResult}
     */
    public static CreateResult createConfigurationProject(String name, String versionStr)
    {
        CreateResult r = new CreateResult();
        if (name == null || name.trim().isEmpty())
        {
            r.error = "projectName is required (the new configuration / project name)"; //$NON-NLS-1$
            return r;
        }
        name = name.trim();

        IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (target.exists())
        {
            r.alreadyExists = true;
            r.ok = true;
            r.createdProjectName = name;
            r.hint = "A project named '" + name + "' already exists - nothing created. Its type was not " //$NON-NLS-1$ //$NON-NLS-2$
                + "verified; choose a different name if it is not the base configuration you intended."; //$NON-NLS-1$
            return r;
        }

        IConfigurationProjectManager mgr =
            Activator.getDefault() != null ? Activator.getDefault().getConfigurationProjectManager() : null;
        if (mgr == null)
        {
            r.serviceNotFound = true;
            r.error = "IConfigurationProjectManager not reachable on this EDT runtime."; //$NON-NLS-1$
            r.hint = "Create it via EDT GUI: File - New - 1C Project (Configuration)."; //$NON-NLS-1$
            return r;
        }

        Version version = resolveVersion(versionStr);
        if (version == null)
        {
            r.error = "version '" + versionStr + "' is not a valid platform version. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Expected format 'major.minor.micro', e.g. '8.3.21'."; //$NON-NLS-1$
            return r;
        }

        try
        {
            IProgressMonitor monitor = new NullProgressMonitor();
            // Detached base-Configuration shell; EDT attaches it as the new project root.
            // createProject fills an empty name from the project name, but set it
            // explicitly the way the New Project wizard does.
            Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
            configuration.setName(name);
            // EDT fills neither of these on the shell, and both leave the new project
            // broken: without a uuid md-legacy-emf-check reports the root Configuration
            // as incomplete, and a compatibility mode left at the model default outranks
            // the runtime version written to DT-INF/PROJECT.PMF - a configuration that
            // claims 8.5.1 on an 8.3.x runtime resolves no platform type at all.
            configuration.setUuid(UUID.randomUUID());
            CompatibilityMode compatibility = compatibilityModeFor(version);
            if (compatibility != null)
            {
                configuration.setCompatibilityMode(compatibility);
            }
            // Automatic locking is the model default and a MAJOR standards finding
            // on sight (configuration-data-lock-mode), so a project created here
            // would open with a violation nobody asked for. Managed is what a new
            // configuration is supposed to be; callers who want the legacy mode can
            // still set it afterwards.
            configuration.setDataLockControlMode(DefaultDataLockControlMode.MANAGED);
            IProject created = mgr.create(name, version, configuration, monitor);
            if (created == null)
            {
                created = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            }
            r.createdProjectName = created != null ? created.getName() : name;
            r.version = version.toString();
            r.ok = true;
        }
        catch (Exception t)
        {
            r.error = "create configuration project failed: " + TextSuggest.safeMessage(t); //$NON-NLS-1$
            Activator.logError("createConfigurationProject(" + name + ") failed", t); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return r;
    }

    /**
     * Resolves the platform {@link Version}: strict-parse the caller's string, else
     * the latest version this EDT installation supports. Returns null only when the
     * caller's string is malformed (a blank/null caller string always resolves to
     * the default and never returns null).
     */
    private static Version resolveVersion(String versionStr)
    {
        if (versionStr != null && !versionStr.trim().isEmpty())
        {
            return Version.create(versionStr.trim());
        }
        return latestSupportedVersion();
    }

    /**
     * Picks the compatibility mode a project on {@code version} should declare.
     * <p>
     * Both the platform version and the compatibility mode spell themselves the
     * same way ({@code 8.3.21}), so the common case is a literal lookup. When the
     * runtime has no mode of its own - a version newer than any the model knows,
     * or a maintenance release with no matching literal - the highest mode that
     * does not outrank the runtime is used, because a mode above the runtime is
     * what breaks type resolution in the first place.
     *
     * @param version the resolved platform version; may be <code>null</code>
     * @return the compatibility mode to set, or <code>null</code> to leave the
     *     model default alone
     */
    static CompatibilityMode compatibilityModeFor(Version version)
    {
        if (version == null)
        {
            return null;
        }
        String literal = version.toString();
        CompatibilityMode exact = CompatibilityMode.get(literal);
        if (exact != null)
        {
            return exact;
        }
        int[] wanted = parseVersionParts(literal);
        CompatibilityMode best = null;
        int[] bestParts = null;
        for (CompatibilityMode candidate : CompatibilityMode.VALUES)
        {
            int[] parts = parseVersionParts(candidate.getLiteral());
            if (compareVersionParts(parts, wanted) > 0)
            {
                continue;
            }
            if (bestParts == null || compareVersionParts(parts, bestParts) > 0)
            {
                best = candidate;
                bestParts = parts;
            }
        }
        return best;
    }

    /** Splits {@code 8.3.21} into {@code {8, 3, 21}}, padding missing and unparsable parts with 0. */
    private static int[] parseVersionParts(String literal)
    {
        int[] parts = new int[3];
        if (literal == null)
        {
            return parts;
        }
        String[] chunks = literal.split("\\."); //$NON-NLS-1$
        for (int i = 0; i < parts.length && i < chunks.length; i++)
        {
            try
            {
                parts[i] = Integer.parseInt(chunks[i].trim());
            }
            catch (NumberFormatException notANumber)
            {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static int compareVersionParts(int[] left, int[] right)
    {
        for (int i = 0; i < left.length; i++)
        {
            if (left[i] != right[i])
            {
                return left[i] < right[i] ? -1 : 1;
            }
        }
        return 0;
    }

    private static Version latestSupportedVersion()
    {
        try
        {
            IRuntimeVersionSupport versionSupport =
                Activator.getDefault() != null ? Activator.getDefault().getRuntimeVersionSupport() : null;
            if (versionSupport != null)
            {
                List<Version> supported = versionSupport.getSupportedVersions();
                if (supported != null && !supported.isEmpty())
                {
                    Version max = null;
                    for (Version v : supported)
                    {
                        if (v != null && (max == null || v.isGreaterThan(max)))
                        {
                            max = v;
                        }
                    }
                    if (max != null)
                    {
                        return max;
                    }
                }
            }
        }
        catch (Throwable t)
        {
            Activator.logWarning("latestSupportedVersion failed: " + TextSuggest.safeMessage(t)); //$NON-NLS-1$
        }
        return Version.LATEST;
    }
}
