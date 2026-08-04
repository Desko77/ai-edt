/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Helper for {@code Subsystem} operations: include/exclude objects in the
 * {@code content} list. <p>
 *
 * <p>Subsystem content is an EList of cross-references to other metadata
 * objects (catalogs, documents, registers, etc.). The EMF feature is exposed
 * via {@code getContent()} on the {@code Subsystem} class, returning a list
 * whose element type varies between EDT versions ({@code MdObject},
 * {@code MetadataObject}, {@code IBmObject}). We rely on case-insensitive
 * name comparison to stay compatible.
 *
 * <p>1.40 operations: {@code addSubsystemContent}, {@code removeSubsystemContent} -
 * both idempotent (no-op when the object is already in / out).
 */
public final class BmSubsystemHelper
{
    private BmSubsystemHelper()
    {
        // utility
    }

    /**
     * Adds the target object to the subsystem's content list, idempotent.
     *
     * @param subsystem the Subsystem MdObject to mutate
     * @param target    the metadata object to include
     * @return true when an addition happened, false when it was already present
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean addContent(MdObject subsystem, MdObject target)
    {
        if (subsystem == null || target == null)
        {
            return false;
        }
        EList contentList = getContentList(subsystem);
        if (contentList == null)
        {
            return false;
        }
        for (Object existing : contentList)
        {
            if (existing == target)
            {
                return false;
            }
            if (existing instanceof MdObject
                && target.getName().equalsIgnoreCase(((MdObject) existing).getName())
                && target.eClass().getName().equals(((MdObject) existing).eClass().getName()))
            {
                return false;
            }
        }
        contentList.add(target);
        return true;
    }

    /**
     * Removes a target object (resolved by FQN) from the subsystem's content.
     *
     * @return true when an item was removed
     */
    @SuppressWarnings("rawtypes")
    public static boolean removeContent(MdObject subsystem, String targetFqn)
    {
        if (subsystem == null || targetFqn == null || targetFqn.isEmpty())
        {
            return false;
        }
        EList contentList = getContentList(subsystem);
        if (contentList == null)
        {
            return false;
        }
        String[] parts = targetFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2)
        {
            return false;
        }
        String type = parts[0];
        String name = parts[1];
        Object found = null;
        for (Object existing : contentList)
        {
            if (existing instanceof MdObject)
            {
                MdObject m = (MdObject) existing;
                if (name.equalsIgnoreCase(m.getName())
                    && (type.equalsIgnoreCase(m.eClass().getName())
                        || type.equalsIgnoreCase(MetadataTypeCatalog.toEnglishSingular(m.eClass().getName()))))
                {
                    found = existing;
                    break;
                }
            }
        }
        if (found == null)
        {
            return false;
        }
        contentList.remove(found);
        return true;
    }

    /**
     * Resolves a metadata object by its FQN against the project's configuration.
     */
    public static MdObject resolveByFqn(Configuration config, String fqn)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String normalized = MetadataTypeCatalog.normalizeFqn(fqn);
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        return MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
    }

    /**
     * Builds a {@code targetNotFound} error tag for {@code addSubsystemContent}.
     */
    public static MetadataGuards.BlockedGuardException targetNotFound(String targetFqn)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetFqn", targetFqn); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Target metadata object not found in configuration: " + targetFqn,
            "Verify the FQN. Use 'Type.Name' shape, e.g. 'Catalog.Goods'.",
            new MetadataGuards.ErrorTag(ErrorTags.TARGET_NOT_FOUND.wire(), data)));
    }

    @SuppressWarnings({ "rawtypes" })
    private static EList getContentList(MdObject subsystem)
    {
        try
        {
            Method m = subsystem.getClass().getMethod("getContent"); //$NON-NLS-1$
            Object list = m.invoke(subsystem);
            if (list instanceof EList)
            {
                return (EList) list;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a subsystem
        }
        catch (Exception e)
        {
            Activator.logWarning("getContentList failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Minimal empty {@code CommandInterface.cmi} payload, byte-identical to what
     * EDT writes for a leaf subsystem that has no command visibility / placement /
     * order fragments: a self-closing {@code cmi:CommandInterface} root. When the
     * user later edits the subsystem command interface EDT appends fragments
     * below this root and re-serializes the file.
     */
    private static final String EMPTY_COMMAND_INTERFACE =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<cmi:CommandInterface xmlns:cmi=\"http://g5.1c.ru/v8/dt/cmi\"/>\n"; //$NON-NLS-1$

    /**
     * Writes a minimal {@code CommandInterface.cmi} next to a freshly-created
     * top-level subsystem's {@code .mdo} when the file is absent, then refreshes
     * the workspace folder so EDT discovers it.
     *
     * <p>Rationale: a subsystem with {@code includeInCommandInterface=true} (the
     * EDT default) needs a {@code CommandInterface.cmi}; BM persists the subsystem
     * {@code .mdo} but not the {@code .cmi}. Without it the incremental
     * configuration export to the infobase fails with
     * {@code RuntimeCoreException: Файл не обнаружен 'zip:///...CommandInterface.xml'}
     * while {@code get_project_errors} stays clean (the EDT validator inspects the
     * model, not the export). An empty but well-formed command interface is
     * harmless when {@code includeInCommandInterface=false} and removes the export
     * pitfall otherwise. Mirrors
     * {@link BmFormResourceHelper#writeEmptyFormResources}.
     *
     * @param project       the EDT project (configuration or extension), must be open
     * @param subsystemName the top-level subsystem name (the folder under
     *                      {@code src/Subsystems}); create_object always creates a
     *                      top-level subsystem, so no nested path is needed
     * @return null on success (written or already present) or a descriptive error
     *     string (the caller surfaces it as a tag without aborting, since the BM
     *     commit has already succeeded)
     */
    public static String writeEmptyCommandInterface(IProject project, String subsystemName)
    {
        if (project == null || subsystemName == null || subsystemName.isEmpty())
        {
            return "project and subsystemName are required"; //$NON-NLS-1$
        }
        if (project.getLocation() == null)
        {
            return "project location is not on the local filesystem"; //$NON-NLS-1$
        }
        Path subsystemDir = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Subsystems").resolve(subsystemName); //$NON-NLS-1$ //$NON-NLS-2$
        Path cmiFile = subsystemDir.resolve("CommandInterface.cmi"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(subsystemDir);
            if (!Files.exists(cmiFile))
            {
                Files.write(cmiFile, EMPTY_COMMAND_INTERFACE.getBytes(StandardCharsets.UTF_8));
            }
        }
        catch (IOException ioe)
        {
            return "Failed to write CommandInterface.cmi: " + ioe.getMessage(); //$NON-NLS-1$
        }
        // Refresh the subsystem folder so EDT discovers the new file; fall back to
        // a project-level refresh when the folder handle is not yet materialised.
        try
        {
            IFolder folder = project.getFolder("src").getFolder("Subsystems") //$NON-NLS-1$ //$NON-NLS-2$
                .getFolder(subsystemName);
            if (folder.exists())
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("CommandInterface.cmi written but workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
        return null;
    }
}
