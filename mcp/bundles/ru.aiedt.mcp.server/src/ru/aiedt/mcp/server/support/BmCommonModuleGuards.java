/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * <b>Defensive layer 3.8.2</b>: guards against the platform-rejected
 * combinations for {@code CommonModule} inside a configuration extension.
 * <p>
 * The platform forbids two combinations on UpdateDBCfg:
 * <ul>
 *   <li>{@code privileged=true} - "privileged common modules are not allowed
 *       in extensions"</li>
 *   <li>{@code global=true} together with {@code server=true}</li>
 * </ul>
 * The visual EDT editor flags these in the UI, but headless creation through
 * MCP would only fail at the very end of the configure-update cycle, after
 * dozens of agent-driven edits, requiring a full rollback.
 *
 * <p>This helper checks the project type (extension vs regular configuration)
 * and validates the requested flags before letting BmObjectHelper write the
 * module to disk - failing fast with a structured error tag.
 */
public final class BmCommonModuleGuards
{
    private BmCommonModuleGuards()
    {
        // utility
    }

    /**
     * Whether the project is a configuration extension.
     * <p>
     * By project type. The old test read {@code Configuration.getConfigurationExtensionPurpose()}
     * reflectively and treated a non-null answer as "extension" - but that value is an EMF enum with
     * no unset state, so it is NEVER null and the test answered "extension" for every project whose
     * configuration resolved. A plain configuration was therefore refused a privileged common module
     * with "not allowed in an extension", which is exactly backwards.
     * </p>
     *
     * @param project the project, may be <code>null</code>
     * @return <code>true</code> only for an extension project
     */
    public static boolean isExtensionProject(IProject project)
    {
        if (project == null || !project.isOpen())
        {
            return false;
        }
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null)
            {
                return false;
            }
            IV8ProjectManager projectManager = activator.getV8ProjectManager();
            if (projectManager == null)
            {
                return false;
            }
            return projectManager.getProject(project) instanceof IExtensionProject;
        }
        catch (Exception e)
        {
            Activator.logWarning("isExtensionProject probe failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * The configuration project an extension project extends, which is where its infobase
     * lives.
     * <p>
     * An extension project has no infobase of its own. Anything that looks for one on the
     * extension itself finds nothing and reports it as an absent application - which reads
     * as "this cannot be done" rather than "ask the parent". That answer cost a working
     * route: getting an extension's current code into the infobase became a hand-run cycle
     * of exporting what the infobase already had, unpacking it, substituting the module,
     * packing it again and installing the result.
     * </p>
     * <p>
     * Called on the interface. {@code getParentProject()} is declared by
     * {@link com._1c.g5.v8.dt.core.platform.IDependentProject}, which
     * {@link IExtensionProject} extends, so no lookup by name is needed - and a lookup by
     * name here would be the shape that fails silently on a non-public implementation.
     * </p>
     *
     * @param project the project to ask about; may be <code>null</code>.
     * @return the parent configuration project, or <code>null</code> when this is not an
     *         extension project or the parent cannot be determined.
     */
    public static IProject parentProjectOf(IProject project)
    {
        if (project == null || !project.isOpen())
        {
            return null;
        }
        try
        {
            Activator activator = Activator.getDefault();
            IV8ProjectManager projectManager =
                activator == null ? null : activator.getV8ProjectManager();
            if (projectManager == null)
            {
                return null;
            }
            IV8Project v8Project = projectManager.getProject(project);
            if (v8Project instanceof IExtensionProject)
            {
                return ((IExtensionProject)v8Project).getParentProject();
            }
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("parentProjectOf probe failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Validates flag combinations for a CommonModule being created (or about
     * to receive {@code setObjectProperty}) in the given project.
     * <p>
     * Throws {@link MetadataGuards.BlockedGuardException} with one of:
     * {@code privilegedNotAllowedInExtension},
     * {@code globalServerNotAllowedInExtension}.
     *
     * @param project   the target project (must be open)
     * @param privileged the privileged-flag value being set; null/false is OK
     * @param globalFlag the global-flag value being set; null/false is OK
     * @param serverFlag the server-flag value being set; null/false is OK
     */
    public static void validate(IProject project, Boolean privileged, Boolean globalFlag,
        Boolean serverFlag) throws MetadataGuards.BlockedGuardException
    {
        if (!isExtensionProject(project))
        {
            return; // regular config - all flag combinations allowed
        }
        if (Boolean.TRUE.equals(privileged))
        {
            throw privilegedInExtension();
        }
        if (Boolean.TRUE.equals(globalFlag) && Boolean.TRUE.equals(serverFlag))
        {
            throw globalServerInExtension();
        }
    }

    /**
     * Same as {@link #validate(IProject, Boolean, Boolean, Boolean)} but
     * reads the flags directly from an already-existing CommonModule object.
     * Useful as a post-mutation guard inside a BM transaction (e.g. after
     * {@code setObjectProperty}).
     */
    public static void validateExisting(IProject project, MdObject commonModule)
        throws MetadataGuards.BlockedGuardException
    {
        if (commonModule == null || !isExtensionProject(project))
        {
            return;
        }
        Boolean privileged = readBooleanProperty(commonModule, "isPrivileged"); //$NON-NLS-1$
        Boolean global = readBooleanProperty(commonModule, "isGlobal"); //$NON-NLS-1$
        Boolean server = readBooleanProperty(commonModule, "isServer"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(privileged))
        {
            throw privilegedInExtension();
        }
        if (Boolean.TRUE.equals(global) && Boolean.TRUE.equals(server))
        {
            throw globalServerInExtension();
        }
    }

    private static Boolean readBooleanProperty(Object obj, String getter)
    {
        try
        {
            Method m = obj.getClass().getMethod(getter);
            Object result = m.invoke(obj);
            if (result instanceof Boolean)
            {
                return (Boolean) result;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // try the get<X> form
            try
            {
                String alt = "get" + getter.substring(2);
                Method m2 = obj.getClass().getMethod(alt);
                Object result = m2.invoke(obj);
                if (result instanceof Boolean)
                {
                    return (Boolean) result;
                }
            }
            catch (Exception ignored2)
            {
                return null;
            }
        }
        catch (Exception e)
        {
            return null;
        }
        return null;
    }

    private static MetadataGuards.BlockedGuardException privilegedInExtension()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flag", "privileged"); //$NON-NLS-1$ //$NON-NLS-2$
        data.put("rejectedBy", "platform on UpdateDBCfg"); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Privileged common module is not allowed in an extension "
                + "(platform rejects on UpdateDBCfg). Drop the privileged flag.",
            "Use isPrivileged=false. If privileged context is required, place the module in the base configuration.",
            new MetadataGuards.ErrorTag(ErrorTags.PRIVILEGED_NOT_ALLOWED_IN_EXTENSION.wire(), data)));
    }

    private static MetadataGuards.BlockedGuardException globalServerInExtension()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flags", "global=true + server=true"); //$NON-NLS-1$ //$NON-NLS-2$
        data.put("rejectedBy", "platform on UpdateDBCfg"); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "global=true together with server=true is not allowed in an extension "
                + "(platform rejects on UpdateDBCfg).",
            "Pick one: server-only OR global-only. Most extension code wants server=true, global=false.",
            new MetadataGuards.ErrorTag(ErrorTags.GLOBAL_SERVER_NOT_ALLOWED_IN_EXTENSION.wire(), data)));
    }
}
