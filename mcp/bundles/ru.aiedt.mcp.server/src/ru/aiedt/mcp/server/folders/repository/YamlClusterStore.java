/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.folders.repository;



import java.io.ByteArrayInputStream;

import java.io.IOException;

import java.io.InputStream;

import java.io.InputStreamReader;

import java.io.Reader;

import java.nio.ByteBuffer;

import java.nio.channels.FileChannel;

import java.nio.channels.FileLock;

import java.nio.channels.OverlappingFileLockException;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.nio.file.StandardOpenOption;

import java.util.ArrayList;

import java.util.Comparator;

import java.util.List;

import java.util.Set;

import java.util.TreeSet;



import org.eclipse.core.resources.IFile;

import org.eclipse.core.resources.IFolder;

import org.eclipse.core.resources.IProject;

import org.eclipse.core.resources.IResource;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.core.runtime.IPath;

import org.yaml.snakeyaml.DumperOptions;

import org.yaml.snakeyaml.LoaderOptions;

import org.yaml.snakeyaml.Yaml;

import org.yaml.snakeyaml.constructor.Constructor;

import org.yaml.snakeyaml.error.YAMLException;

import org.yaml.snakeyaml.introspector.Property;

import org.yaml.snakeyaml.nodes.Tag;

import org.yaml.snakeyaml.representer.Representer;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.folders.ClusterKeys;

import ru.aiedt.mcp.server.support.LegacyStorageMigration;

import ru.aiedt.mcp.server.folders.model.Cluster;

import ru.aiedt.mcp.server.folders.model.ClusterStore;



/**

 * Reads and writes {@code .settings/aiedt-clusters.yaml} for a project.

 * <p>

 * The output is deliberately stable so that two logically equal sets of clusters produce the same

 * bytes and version control sees no change: the clusters are ordered by path, then order, then name

 * without regard to case; each cluster's objects are ordered without regard to case; and the map keys

 * of each cluster come out in alphabetical order. The file is UTF-8 with line-feed endings and no

 * byte-order mark - it is written raw through a file channel, not through a text-normalizing layer,

 * so those endings survive on every platform.

 * </p>

 * <p>

 * Saving an empty set of clusters deletes the file rather than leaving an empty one behind; a reader

 * treats an absent file and an empty one alike.

 * </p>

 * <p>

 * On the way in, the loader is hardened two ways. It refuses global YAML markers, so a file arriving

 * through a clone cannot name an arbitrary class for the parser to instantiate. And a file it cannot

 * parse - bad syntax, or a key it does not know - degrades to no clusters rather than throwing out into

 * the Navigator.

 * </p>

 */

public class YamlClusterStore

    implements IClusterStore

{

    /** Orders clusters for stable, diff-friendly output: path, then order, then case-insensitive name. */

    private static final Comparator<Cluster> GIT_FRIENDLY_ORDER =

        Comparator.comparing((Cluster cluster) -> cluster.getPath() == null ? "" : cluster.getPath()) //$NON-NLS-1$

            .thenComparingInt(Cluster::getOrder)

            .thenComparing(cluster -> cluster.getName() == null ? "" : cluster.getName(), //$NON-NLS-1$

                String.CASE_INSENSITIVE_ORDER);



    @Override

    public ClusterStore load(IProject project)

    {

        IFile file = clustersFile(project);

        if (!file.exists())

        {

            return new ClusterStore();

        }

        try (InputStream in = file.getContents();

            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))

        {

            Yaml yaml = new Yaml(new Constructor(ClusterStore.class, createLoaderOptions()));

            ClusterStore storage = yaml.load(reader);

            if (storage == null)

            {

                return new ClusterStore();

            }

            cleanupOrphanedFqns(storage);

            return storage;

        }

        catch (YAMLException e)

        {

            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$

                + " could not be parsed and was ignored: " + e.getMessage()); //$NON-NLS-1$

            return new ClusterStore();

        }

        catch (CoreException | IOException e)

        {

            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            return new ClusterStore();

        }

    }



    @Override

    public boolean save(IProject project, ClusterStore storage)

    {

        if (storage == null || storage.isEmpty())

        {

            return deleteIfExists(project);

        }

        String content = dump(sortForOutput(storage));

        return saveWithLock(project, content);

    }



    @Override

    public boolean exists(IProject project)

    {

        return clustersFile(project).exists();

    }



    @Override

    public boolean delete(IProject project)

    {

        return deleteIfExists(project);

    }



    /**

     * Serializes a storage to the exact YAML the file holds.

     * <p>

     * Block style throughout, two-space indent, alphabetical cluster keys, and no type markers. Package

     * visibility so the serialization can be exercised in a test without going near the workspace.

     * </p>

     *

     * @param storage the storage to serialize

     * @return the YAML text, line-feed terminated

     */

    static String dump(ClusterStore storage)

    {

        DumperOptions options = new DumperOptions();

        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        options.setPrettyFlow(true);

        options.setIndent(2);

        options.setWidth(120);

        options.setSplitLines(false);



        Representer representer = new AlphabeticalPropertyRepresenter(options);

        representer.addClassTag(ClusterStore.class, Tag.MAP);

        representer.addClassTag(Cluster.class, Tag.MAP);



        Yaml yaml = new Yaml(representer, options);

        return yaml.dump(storage);

    }



    /**

     * Builds the loader options.

     * <p>

     * The marker inspector answers no to every global marker. The standard map, sequence and scalar markers our

     * own files use are not global and are never put to it, so nothing legitimate is refused; a crafted

     * global marker - the SnakeYAML deserialization-gadget vector - is.

     * </p>

     *

     * @return the loader options

     */

    private static LoaderOptions createLoaderOptions()

    {

        LoaderOptions options = new LoaderOptions();

        options.setTagInspector(marker -> false);

        return options;

    }



    /**

     * Strips blank object references from every cluster after a load.

     *

     * @param storage the freshly loaded storage

     */

    private static void cleanupOrphanedFqns(ClusterStore storage)

    {

        for (Cluster cluster : storage.getGroups())

        {

            List<String> children = cluster.getChildren();

            List<String> cleaned = new ArrayList<>(children.size());

            for (String fqn : children)

            {

                if (fqn != null && !fqn.trim().isEmpty())

                {

                    cleaned.add(fqn);

                }

            }

            if (cleaned.size() != children.size())

            {

                cluster.setChildren(cleaned);

            }

        }

    }



    /**

     * Produces a copy of the storage ordered for stable output, sorting each cluster's objects in place.

     *

     * @param storage the storage to order

     * @return a storage whose cluster list is sorted and whose clusters have sorted children

     */

    private static ClusterStore sortForOutput(ClusterStore storage)

    {

        List<Cluster> clusters = storage.getGroups();

        for (Cluster cluster : clusters)

        {

            List<String> children = cluster.getChildren();

            children.sort(String.CASE_INSENSITIVE_ORDER);

            cluster.setChildren(children);

        }

        clusters.sort(GIT_FRIENDLY_ORDER);



        ClusterStore sorted = new ClusterStore();

        sorted.setGroups(clusters);

        return sorted;

    }



    /**

     * Writes the content by locking the file directly, falling back to the workspace when that is not

     * possible.

     *

     * @param project the project

     * @param content the YAML to write

     * @return <code>true</code> on success

     */

    private boolean saveWithLock(IProject project, String content)

    {

        IFile clustersFile = clustersFile(project);

        IPath location = clustersFile.getLocation();

        if (location == null)

        {

            return saveDirectly(project, clustersFile, content);

        }

        Path osPath = location.toFile().toPath();

        try

        {

            if (osPath.getParent() != null)

            {

                Files.createDirectories(osPath.getParent());

            }

            if (!writeChannelLocked(osPath, content))

            {

                Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$

                    + " is locked by another process; writing it through the workspace instead"); //$NON-NLS-1$

                return saveDirectly(project, clustersFile, content);

            }

            clustersFile.refreshLocal(IResource.DEPTH_ZERO, null);

            return true;

        }

        catch (OverlappingFileLockException e)

        {

            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$

                + " is already being written in this process; writing it through the workspace instead"); //$NON-NLS-1$

            return saveDirectly(project, clustersFile, content);

        }

        catch (IOException | CoreException e)

        {

            Activator.logError("Failed to write aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            return false;

        }

    }



    /**

     * Opens the file, takes an exclusive whole-file lock without blocking, and writes.

     *

     * @param osPath the file's location on disk

     * @param content the YAML to write

     * @return <code>true</code> if written, <code>false</code> if the lock could not be taken because

     *         another process holds it

     * @throws IOException if the write fails

     * @throws OverlappingFileLockException if this process already holds an overlapping lock

     */

    private static boolean writeChannelLocked(Path osPath, String content) throws IOException

    {

        try (FileChannel channel = FileChannel.open(osPath, StandardOpenOption.CREATE,

            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))

        {

            FileLock lock = channel.tryLock();

            if (lock == null)

            {

                return false;

            }

            try

            {

                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));

            }

            finally

            {

                lock.release();

            }

            return true;

        }

    }



    /**

     * Writes the content through the workspace, creating the settings folder and file as needed.

     *

     * @param project the project

     * @param clustersFile the target file

     * @param content the YAML to write

     * @return <code>true</code> on success

     */

    private boolean saveDirectly(IProject project, IFile clustersFile, String content)

    {

        try

        {

            ensureSettingsFolder(project);

            InputStream source = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            if (clustersFile.exists())

            {

                clustersFile.setContents(source, true, false, null);

            }

            else

            {

                clustersFile.create(source, true, null);

            }

            return true;

        }

        catch (CoreException e)

        {

            Activator.logError("Failed to write aiedt-clusters.yaml through the workspace for " //$NON-NLS-1$

                + project.getName(), e);

            return false;

        }

    }



    /**

     * Deletes the clusters file if it is there.

     *

     * @param project the project

     * @return <code>true</code> if the file is gone afterwards

     */

    private boolean deleteIfExists(IProject project)

    {

        IFile file = clustersFile(project);

        if (!file.exists())

        {

            return true;

        }

        try

        {

            file.delete(true, null);

            return true;

        }

        catch (CoreException e)

        {

            Activator.logError("Failed to delete aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            return false;

        }

    }



    /**

     * Creates the {@code .settings} folder if it is missing.

     *

     * @param project the project

     * @throws CoreException if the folder cannot be created

     */

    private static void ensureSettingsFolder(IProject project) throws CoreException

    {

        IFolder folder = project.getFolder(IPath.fromPortableString(ClusterKeys.SETTINGS_FOLDER));

        if (!folder.exists())

        {

            folder.create(true, true, null);

        }

    }



    /**

     * Returns the project's clusters file handle.

     *

     * @param project the project

     * @return the file handle, whether or not it exists

     */

    /**
     * @param project the project whose settings folder is wanted
     * @return the settings folder on disk, or {@code null} when the project has none yet
     */
    private static java.nio.file.Path settingsFolderOnDisk(IProject project)
    {
        IPath location = project.getFolder(ClusterKeys.SETTINGS_FOLDER).getLocation();
        return location == null ? null : location.toFile().toPath();
    }

    private static IFile clustersFile(IProject project)

    {

        try
        {
            if (LegacyStorageMigration.carryOver(settingsFolderOnDisk(project),
                ClusterKeys.LEGACY_CLUSTERS_FILE, ClusterKeys.CLUSTERS_FILE))
            {
                // The carry-over writes straight to disk, which the workspace does not see. Without
                // this refresh the IFile returned below reports itself absent, the caller reads an
                // empty store, and the next save writes that emptiness over the clusters just
                // migrated - the upgrade would eat them.
                project.getFolder(ClusterKeys.SETTINGS_FOLDER).refreshLocal(IResource.DEPTH_ONE, null);
            }
        }
        catch (java.io.IOException | CoreException e)
        {
            Activator.logError("Could not carry " + ClusterKeys.LEGACY_CLUSTERS_FILE + " over to " //$NON-NLS-1$ //$NON-NLS-2$
                + ClusterKeys.CLUSTERS_FILE + " for " + project.getName(), e); //$NON-NLS-1$
        }
        return project.getFile(IPath.fromPortableString(ClusterKeys.CLUSTERS_PATH));

    }



    /**

     * A representer that emits a bean's properties in alphabetical order.

     * <p>

     * {@link Property} sorts by name, so a sorted set of them yields {@code children}, {@code description},

     * {@code name}, {@code order}, {@code path} every time - which is what keeps the file's diff quiet.

     * </p>

     */

    private static final class AlphabeticalPropertyRepresenter

        extends Representer

    {

        AlphabeticalPropertyRepresenter(DumperOptions options)

        {

            super(options);

        }



        @Override

        protected Set<Property> getProperties(Class<? extends Object> type)

        {

            return new TreeSet<>(super.getProperties(type));

        }

    }

}

