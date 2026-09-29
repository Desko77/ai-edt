/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog.MetadataTypeInfo;

/**
 * Removes the sources of the objects an infobase no longer has, after the objects themselves were
 * taken out of the project by a pull.
 *
 * <p>A name is turned into a path only when it names one whole object. Types the module catalog
 * addresses by directory use that directory. Types the catalog leaves with no directory - a
 * subsystem, a role, a common picture, and the rest of that list - use {@link #DELETION_DIRECTORIES},
 * a table that exists only for this removal. Filling the catalog's own directory would hand module
 * and form resolution a path they are written to refuse.</p>
 *
 * <p>A nested subsystem ({@code src/Subsystems/A/Subsystems/B}) is removed when the platform's name
 * spells that path ({@code Subsystem.A.Subsystem.B}) or when the leaf name matches exactly one
 * directory in the tree. Several directories of the same leaf are left in place and reported in
 * {@link Outcome#filesKept} with the paths that remained. Anything else - a form, a template, a
 * name this build cannot place, the configuration itself - is left alone and reported as skipped.</p>
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

    /** The directory a subsystem's children live in, repeated at every level of the tree. */
    private static final String NESTED = "Subsystems"; //$NON-NLS-1$

    /**
     * Where an object's sources live when {@link MetadataTypeCatalog} records no directory for the
     * type. The keys are the English singular; the values are the directory under {@code src/} an
     * EDT project actually uses. Measured against the {@code src/} of projects on disk (roles,
     * subsystems, common pictures, common templates, style items, and the other null-directory
     * kinds). {@code WebSocketClients} follows the same plural; no project sampled contained one.
     */
    private static final Map<String, String> DELETION_DIRECTORIES;

    static
    {
        Map<String, String> directories = new LinkedHashMap<>();
        for (MetadataTypeInfo type : MetadataTypeInfo.values())
        {
            if (type.getDirectoryName() != null)
            {
                continue;
            }
            directories.put(type.getEnglishSingular(), type.getEnglishPlural());
        }
        DELETION_DIRECTORIES = Collections.unmodifiableMap(directories);
    }

    private RemovedObjectFiles()
    {
        // utility
    }

    /**
     * One object whose sources were left in place because more than one directory matches the name.
     */
    public static final class Kept
    {
        /** The platform qualified name. */
        public final String name;

        /** The project-relative path that was left in place. */
        public final String path;

        /**
         * @param name the platform qualified name
         * @param path the project-relative path that remained
         */
        Kept(String name, String path)
        {
            this.name = name;
            this.path = path;
        }
    }

    /**
     * What one round of removals did.
     */
    public static final class Outcome
    {
        /** The project-relative paths that were removed. */
        public final List<String> removed = new ArrayList<>();

        /** The names that were not files of a whole object, and why each is the name itself. */
        public final List<String> skipped = new ArrayList<>();

        /** The paths that were there and could not be removed. */
        public final List<String> failures = new ArrayList<>();

        /**
         * Objects that were not removed because the name does not pick one directory, each with a
         * path that remained.
         */
        public final List<Kept> filesKept = new ArrayList<>();
    }

    /**
     * Removes the sources of every named object that is still in the project.
     *
     * @param project the project to remove from; may be <code>null</code>
     * @param qualifiedNames the platform qualified names of the objects, as an infobase change set
     *            spells them; may be <code>null</code>
     * @return what was removed, skipped, kept and refused; never <code>null</code>
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
            Placement placement = place(project, name);
            if (placement.kind == Kind.SKIP)
            {
                outcome.skipped.add(name == null ? "(no name)" : name); //$NON-NLS-1$
                continue;
            }
            if (placement.kind == Kind.KEEP)
            {
                for (String path : placement.paths)
                {
                    outcome.filesKept.add(new Kept(name, path));
                }
                continue;
            }
            if (placement.kind != Kind.DELETE || placement.paths.isEmpty())
            {
                continue;
            }
            String path = placement.paths.get(0);
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
     * The directory a type's sources are removed from.
     * <p>
     * The catalog's own directory when it has one, otherwise the deletion table. The configuration
     * directory is never returned: that file is not an object of the configuration.
     * </p>
     *
     * @param typeName the type, in any spelling the catalog accepts; may be <code>null</code>
     * @return the directory under {@code src/}, or <code>null</code> when the name is not a type
     *         this removal will touch
     */
    static String deletionDirectory(String typeName)
    {
        String canonical = MetadataTypeCatalog.toEnglishSingular(typeName);
        if (canonical == null)
        {
            return null;
        }
        String fromCatalog = MetadataTypeCatalog.getDirectoryName(canonical);
        if (CONFIGURATION_DIR.equals(fromCatalog))
        {
            return null;
        }
        if (fromCatalog != null)
        {
            return fromCatalog;
        }
        return DELETION_DIRECTORIES.get(canonical);
    }

    /**
     * Turns a platform qualified name into where that object's sources are, or into the several
     * directories a short subsystem name cannot choose between.
     *
     * @param project the project the sources live in; may be <code>null</code>
     * @param qualifiedName the name, for example {@code Catalog.Goods}; may be <code>null</code>
     * @return the placement; never <code>null</code>
     */
    static Placement place(IProject project, String qualifiedName)
    {
        if (qualifiedName == null || qualifiedName.isEmpty())
        {
            return Placement.skip();
        }
        String[] segments = qualifiedName.split("\\."); //$NON-NLS-1$
        for (String segment : segments)
        {
            if (!isPlainName(segment))
            {
                return Placement.skip();
            }
        }
        if ("Subsystem".equals(MetadataTypeCatalog.toEnglishSingular(segments[0]))) //$NON-NLS-1$
        {
            return placeSubsystem(project, segments);
        }
        if (segments.length != 2)
        {
            return Placement.skip();
        }
        String directory = deletionDirectory(segments[0]);
        if (directory == null)
        {
            return Placement.skip();
        }
        return Placement.delete(SOURCE_ROOT + directory + '/' + segments[1]);
    }

    /**
     * Where a subsystem name points.
     * <p>
     * {@code Subsystem.A.Subsystem.B} is the path {@code src/Subsystems/A/Subsystems/B} and no
     * other. {@code Subsystem.B} is that leaf wherever it sits: one directory is removed, more than
     * one is kept, none is already gone.
     * </p>
     *
     * @param project the project; may be <code>null</code>
     * @param segments the qualified name, already checked to be plain segments, starting with a
     *            subsystem
     * @return the placement
     */
    private static Placement placeSubsystem(IProject project, String[] segments)
    {
        String encoded = encodedSubsystemPath(segments);
        if (encoded != null)
        {
            return Placement.delete(encoded);
        }
        if (segments.length != 2)
        {
            return Placement.skip();
        }
        if (project == null)
        {
            return Placement.skip();
        }
        List<String> found = subsystemDirectoriesNamed(project, segments[1]);
        if (found == null)
        {
            return Placement.skip();
        }
        if (found.size() == 1)
        {
            return Placement.delete(found.get(0));
        }
        if (found.size() > 1)
        {
            return Placement.keep(found);
        }
        return Placement.gone();
    }

    /**
     * The path a subsystem name spells when every other segment is the type and every name segment
     * is the object. A two-segment name is a leaf, not this path.
     *
     * @param segments the qualified name
     * @return the project-relative path, or <code>null</code> when the name does not spell one
     */
    static String encodedSubsystemPath(String[] segments)
    {
        // Two segments are a leaf name, not a path: Subsystem.Only may sit at the top or nested,
        // and only a search can say which. The spelled path starts at Subsystem.A.Subsystem.B.
        if (segments.length < 4 || (segments.length % 2) != 0)
        {
            return null;
        }
        StringBuilder path = new StringBuilder(SOURCE_ROOT);
        for (int i = 0; i < segments.length; i += 2)
        {
            if (!"Subsystem".equals(MetadataTypeCatalog.toEnglishSingular(segments[i]))) //$NON-NLS-1$
            {
                return null;
            }
            if (!isPlainName(segments[i + 1]))
            {
                return null;
            }
            if (i > 0)
            {
                path.append('/');
            }
            path.append(NESTED).append('/').append(segments[i + 1]);
        }
        return path.toString();
    }

    /**
     * Every subsystem directory of this leaf name, top-level and nested.
     *
     * @param project the project
     * @param leaf the subsystem's own name
     * @return the project-relative paths, empty when none remain, or <code>null</code> when the
     *         tree could not be read
     */
    private static List<String> subsystemDirectoriesNamed(IProject project, String leaf)
    {
        IResource root = project.findMember(SOURCE_ROOT + NESTED);
        if (!(root instanceof IFolder))
        {
            return new ArrayList<>();
        }
        List<String> found = new ArrayList<>();
        try
        {
            collect((IFolder)root, leaf, found);
        }
        catch (CoreException e)
        {
            Activator.logWarning("sync_control retrieve_database_changes: the subsystem tree of " //$NON-NLS-1$
                + project.getName() + " was not read: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
            return null;
        }
        return found;
    }

    /**
     * Collects subsystem directories named {@code leaf} under one {@code Subsystems} folder, then
     * walks each child's own {@code Subsystems} folder.
     *
     * @param subsystemsFolder a {@code Subsystems} directory
     * @param leaf the name to match
     * @param found the paths collected so far
     * @throws CoreException when the folder's members cannot be read
     */
    private static void collect(IFolder subsystemsFolder, String leaf, List<String> found) throws CoreException
    {
        IResource direct = subsystemsFolder.findMember(leaf);
        if (direct instanceof IFolder && direct.exists())
        {
            found.add(direct.getProjectRelativePath().toPortableString());
        }
        for (IResource member : subsystemsFolder.members())
        {
            if (!(member instanceof IFolder))
            {
                continue;
            }
            IResource nested = ((IFolder)member).findMember(NESTED);
            if (nested instanceof IFolder)
            {
                collect((IFolder)nested, leaf, found);
            }
        }
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

    /** What to do with one qualified name. */
    enum Kind
    {
        /** Not a whole object; leave it and report it skipped. */
        SKIP,
        /** One directory; remove it when it is still there. */
        DELETE,
        /** Several directories; remove none and report the paths. */
        KEEP,
        /** A whole object whose directory is already absent. */
        GONE
    }

    /**
     * The decision for one qualified name.
     */
    static final class Placement
    {
        /** What to do. */
        final Kind kind;

        /** The paths the decision is about; empty except for {@link Kind#DELETE} and {@link Kind#KEEP}. */
        final List<String> paths;

        private Placement(Kind kind, List<String> paths)
        {
            this.kind = kind;
            this.paths = paths;
        }

        /**
         * @return a skip
         */
        static Placement skip()
        {
            return new Placement(Kind.SKIP, List.of());
        }

        /**
         * @return an object that is already absent
         */
        static Placement gone()
        {
            return new Placement(Kind.GONE, List.of());
        }

        /**
         * @param path the one directory to remove
         * @return a deletion
         */
        static Placement delete(String path)
        {
            return new Placement(Kind.DELETE, List.of(path));
        }

        /**
         * @param paths the directories that remained
         * @return a keep
         */
        static Placement keep(List<String> paths)
        {
            return new Placement(Kind.KEEP, paths);
        }
    }
}
