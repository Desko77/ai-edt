/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import ru.aiedt.mcp.server.Activator;

/**
 * Removes the sources of the objects an infobase no longer has, after the objects themselves were
 * taken out of the project by a pull.
 *
 * <p>A name is turned into a path only when it names one whole object: two segments, the first a
 * metadata type this build knows and the second its name, with neither segment carrying a path
 * separator. Anything else - a form, a template, a name this build cannot place, the configuration
 * itself - is left alone and reported as skipped, because those files belong to an object that is
 * still there. Nothing outside the object's own directory is touched, so the configuration file and
 * every neighbouring object keep their sources.</p>
 *
 * <p>Every removal is recorded, and a file that cannot be removed is reported rather than thrown:
 * a pull that has already applied the infobase's content must not be answered as a failure over a
 * leftover file.</p>
 */
public final class RemovedObjectFiles
{
    /** Root of the sources of an EDT project. */
    private static final String SOURCE_ROOT = "src/"; //$NON-NLS-1$

    /** The directory the configuration itself lives in, which is never an object of its own. */
    private static final String CONFIGURATION_DIR = "Configuration"; //$NON-NLS-1$

    private RemovedObjectFiles()
    {
        // utility
    }

    /**
     * What one round of removals did.
     */
    public static final class Outcome
    {
        /** The project-relative paths that were removed. */
        public final List<String> removed = new ArrayList<>();

        /** The names that were not files of a whole object, and why. */
        public final List<String> skipped = new ArrayList<>();

        /** The paths that were there and could not be removed. */
        public final List<String> failures = new ArrayList<>();
    }

    /**
     * Removes the sources of every named object that is still in the project.
     *
     * @param project the project to remove from; may be <code>null</code>
     * @param qualifiedNames the platform qualified names of the objects, as an infobase change set
     *            spells them; may be <code>null</code>
     * @return what was removed, skipped and refused; never <code>null</code>
     */
    public static Outcome deleteFor(IProject project, Collection<String> qualifiedNames)
    {
        Outcome outcome = new Outcome();
        if (project == null || qualifiedNames == null || qualifiedNames.isEmpty())
        {
            return outcome;
        }
        for (String name : qualifiedNames)
        {
            String path = pathOf(name);
            if (path == null)
            {
                outcome.skipped.add(name == null ? "(no name)" : name); //$NON-NLS-1$
                continue;
            }
            IResource resource = project.findMember(path);
            if (resource == null || !resource.exists())
            {
                // Already gone: the pull itself took it, or the object never had sources here.
                continue;
            }
            try
            {
                resource.delete(true, new NullProgressMonitor());
                outcome.removed.add(path);
            }
            catch (CoreException e)
            {
                outcome.failures.add(path + ": " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
                Activator.logWarning("sync_control retrieve_database_changes: the sources of " //$NON-NLS-1$
                    + name + " were not removed: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
            }
        }
        return outcome;
    }

    /**
     * Turns a platform qualified name into the directory EDT keeps that object's sources in.
     *
     * @param qualifiedName the name, for example {@code Catalog.Goods}; may be <code>null</code>
     * @return the project-relative path, or <code>null</code> when the name does not place one whole
     *         object - a nested object, an unknown type, the configuration, or a name carrying a
     *         path separator
     */
    static String pathOf(String qualifiedName)
    {
        if (qualifiedName == null || qualifiedName.isEmpty())
        {
            return null;
        }
        String[] segments = qualifiedName.split("\\."); //$NON-NLS-1$
        if (segments.length != 2 || !isPlainName(segments[0]) || !isPlainName(segments[1]))
        {
            return null;
        }
        String directory = MetadataPathMapper.resolveMetadataDir(segments[0]);
        if (directory == null || CONFIGURATION_DIR.equals(directory))
        {
            return null;
        }
        return SOURCE_ROOT + directory + '/' + segments[1];
    }

    /**
     * Whether one segment is a metadata name rather than a path.
     *
     * @param segment the segment; may be <code>null</code>
     * @return <code>true</code> when it is non-empty and carries no separator, drive letter or
     *         parent reference
     */
    private static boolean isPlainName(String segment)
    {
        return segment != null && !segment.isEmpty()
            && !".".equals(segment) && !"..".equals(segment) //$NON-NLS-1$ //$NON-NLS-2$
            && segment.indexOf('/') < 0 && segment.indexOf('\\') < 0 && segment.indexOf(':') < 0;
    }
}
