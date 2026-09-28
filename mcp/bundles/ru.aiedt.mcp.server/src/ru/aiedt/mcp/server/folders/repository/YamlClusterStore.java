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

import java.nio.charset.CodingErrorAction;

import java.nio.charset.StandardCharsets;

import java.nio.file.DirectoryStream;

import java.nio.file.FileAlreadyExistsException;

import java.nio.file.Files;

import java.nio.file.Path;

import java.nio.file.StandardOpenOption;
import java.nio.file.AccessDeniedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;

import java.nio.file.attribute.PosixFileAttributeView;

import java.nio.file.attribute.PosixFilePermission;

import java.nio.file.attribute.PosixFilePermissions;

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
import org.yaml.snakeyaml.introspector.PropertyUtils;

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

 * On the way in, the loader refuses global YAML markers, so a file arriving through a clone cannot

 * name an arbitrary class for the parser to instantiate. A file it cannot parse degrades to no

 * clusters for the reader rather than throwing into the Navigator, and a later save refuses to

 * replace that file: the bytes stay, and a copy is kept beside it. A key the loader does not know

 * is skipped, so a file written by a newer build still reads.

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

    private static final int ATOMIC_MOVE_ATTEMPTS = 4;

    private static final long ATOMIC_MOVE_RETRY_MILLIS = 75L;
    private static final long STALE_TEMPORARY_MILLIS = 24L * 60L * 60L * 1000L;

    private static final Set<PosixFilePermission> DEFAULT_POSIX_FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);


    /** Performs one atomic replacement attempt. */
    @FunctionalInterface
    interface AtomicMover
    {
        /**
         * Moves the staged file over the destination atomically.
         *
         * @param source the staged file
         * @param target the destination
         * @throws IOException when the atomic replacement is refused
         */
        void move(Path source, Path target) throws IOException;
    }

    /** Waits between atomic replacement attempts. */
    @FunctionalInterface
    interface RetrySleeper
    {
        /**
         * Waits for the requested retry delay.
         *
         * @param milliseconds the delay in milliseconds
         * @throws InterruptedException when the waiting thread is interrupted
         */
        void sleep(long milliseconds) throws InterruptedException;
    }



    @Override

    public ClusterStore load(IProject project)

    {

        IFile file = clustersFile(project);

        if (!file.exists())

        {

            return new ClusterStore();

        }

        try (InputStream in = file.getContents();

            Reader reader = strictUtf8Reader(in))

        {

            Yaml yaml = createLoadYaml();

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

                + " could not be parsed and was left unchanged: " + e.getMessage()); //$NON-NLS-1$

            return null;

        }

        catch (CoreException | IOException e)

        {

            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            return null;

        }

    }

    /**
     * Creates a reader that refuses malformed or unmappable UTF-8 instead of replacing it.
     *
     * @param input the byte stream to decode
     * @return a strict UTF-8 reader
     */
    private static Reader strictUtf8Reader(InputStream input)
    {
        return new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));
    }



    /**
     * Saves a project's clusters.
     * <p>
     * An existing file that this loader cannot parse is not replaced: the bytes stay and a copy is
     * written beside the file, and this method answers {@code false}. Saving an empty set still
     * deletes a file that did parse.
     * </p>
     *
     * @param project the project
     * @param storage the clusters to save
     * @return {@code true} on success
     */
    @Override
    public boolean save(IProject project, ClusterStore storage)
    {
        if (unreadableFileBlocksSave(project))
        {
            return false;
        }
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
     * Builds the reader-side YAML with the clusters file's root type fixed, unknown properties
     * ignored, and global YAML markers refused.
     * <p>
     * The marker inspector answers no to every global marker. The standard map, sequence and scalar
     * markers our own files use are not global and are never put to it, so nothing legitimate is
     * refused; a crafted global marker is. Unknown keys are skipped so a file written by a newer
     * build still reads instead of degrading to an empty set.
     * </p>
     *
     * @return the configured reader
     */
    private static Yaml createLoadYaml()
    {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(marker -> false);
        Constructor constructor = new Constructor(ClusterStore.class, options);
        PropertyUtils propertyUtils = new PropertyUtils();
        propertyUtils.setSkipMissingProperties(true);
        constructor.setPropertyUtils(propertyUtils);
        return new Yaml(constructor);
    }

    /**
     * Refuses to overwrite a clusters file that is not valid YAML, and keeps a copy beside it.
     * <p>
     * A reader treats that file as no clusters. Writing an edited set back would replace the bytes
     * the reader could not understand, so the first edit after a bad read leaves the file alone.
     * </p>
     *
     * @param project the project
     * @return {@code true} when the save must stop and leave the file as it is
     */
    private boolean unreadableFileBlocksSave(IProject project)
    {
        IFile file = clustersFile(project);
        IPath location = file.getLocation();
        if (location == null)
        {
            return unreadableWorkspaceFileBlocksSave(project, file);
        }
        Path path = location.toFile().toPath();
        if (Files.notExists(path))
        {
            return false;
        }
        byte[] bytes;
        try
        {
            bytes = Files.readAllBytes(path);
        }
        catch (IOException e)
        {
            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " before writing it; the write was refused", e); //$NON-NLS-1$
            return true;
        }
        if (bytes.length == 0 || parses(bytes))
        {
            return false;
        }
        backupUnreadable(project, path, bytes);
        return true;
    }

    /**
     * Checks a non-local workspace file when no operating-system path is available.
     *
     * @param project the project, for diagnostics
     * @param file the workspace file
     * @return {@code true} when the save must be refused
     */
    private boolean unreadableWorkspaceFileBlocksSave(IProject project, IFile file)
    {
        if (!file.exists())
        {
            return false;
        }
        try (InputStream in = file.getContents())
        {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0 || parses(bytes))
            {
                return false;
            }
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " could not be parsed; the write was refused"); //$NON-NLS-1$
            return true;
        }
        catch (CoreException | IOException e)
        {
            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " before writing it; the write was refused", e); //$NON-NLS-1$
            return true;
        }
    }

    /**
     * Tells whether the bytes are a clusters document this loader accepts.
     * <p>
     * An unknown key is accepted. A syntax error, including conflict markers, is not.
     * </p>
     *
     * @param bytes the file contents
     * @return {@code true} when {@link #createLoadYaml()} can read them
     */
    private static boolean parses(byte[] bytes)
    {
        try (Reader reader = strictUtf8Reader(new ByteArrayInputStream(bytes)))
        {
            createLoadYaml().load(reader);
            return true;
        }
        catch (YAMLException | IOException e)
        {
            return false;
        }
    }

    /**
     * Copies the first unreadable clusters file to {@code aiedt-clusters.yaml.bak} beside it.
     * <p>
     * The original is not modified, and an existing backup is preserved so repeated refused saves do
     * not destroy the first recoverable snapshot. A failed copy is logged and still leaves the original
     * in place, because the caller refuses the write either way.
     * </p>
     *
     * @param project the project, for the log line
     * @param file the clusters file on disk
     * @param bytes the bytes to copy
     */
    private static void backupUnreadable(IProject project, Path file, byte[] bytes)
    {
        Path backup = file.resolveSibling(ClusterKeys.CLUSTERS_FILE + ".bak"); //$NON-NLS-1$
        try
        {
            Files.write(backup, bytes, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " could not be parsed; the write was refused and a copy kept at " //$NON-NLS-1$
                + backup.getFileName());
        }
        catch (FileAlreadyExistsException e)
        {
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " is still unreadable; the existing backup was preserved at " //$NON-NLS-1$
                + backup.getFileName());
        }
        catch (IOException e)
        {
            Activator.logError("Could not back up unreadable aiedt-clusters.yaml for " //$NON-NLS-1$
                + project.getName(), e);
        }
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

     * Writes the content by replacing the file under an exclusive lock. When the file has no location

     * on disk, writes through the workspace instead. A lock that cannot be taken refuses the write.

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

                    + " could not be locked or opened for writing; the write was refused"); //$NON-NLS-1$

                return false;

            }

            clustersFile.refreshLocal(IResource.DEPTH_ZERO, null);

            return true;

        }

        catch (IOException | CoreException e)

        {

            Activator.logError("Failed to write aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            return false;

        }

    }



    /**
     * Stages the content in a temporary file, probes the destination with an exclusive lock, and
     * replaces the destination atomically after that probe succeeds.
     * <p>
     * The lock is taken on the destination as it stands. A lock another process holds, or one this
     * process already holds, answers {@code false} and leaves the destination byte for byte. The lock
     * is released before the move, so it is an availability probe rather than synchronization around
     * replacement. The bytes are forced to the temporary file first, and only an atomic replacement
     * makes them visible; an unavailable destination is retried and never falls back to a delete-first
     * move.
     * </p>
     *
     * @param osPath the file's location on disk
     * @param content the YAML to write
     * @return {@code true} if the destination was replaced, {@code false} if the lock could not be taken
     * @throws IOException if staging or replacing fails; the destination is left as it was when the
     *             failure happens before the replace
     */
    static boolean writeChannelLocked(Path osPath, String content) throws IOException
    {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path directory = osPath.getParent();
        if (directory != null)
        {
            Files.createDirectories(directory);
        }
        Path stagingDirectory = directory == null ? Path.of(".") : directory; //$NON-NLS-1$
        cleanupStaleTemporaryFiles(osPath, stagingDirectory);
        Path temporary = createTemporaryFile(osPath, stagingDirectory);
        IOException failure = null;
        try
        {
            writeForced(temporary, bytes);
            if (!tryLockWithoutTruncating(osPath))
            {
                return false;
            }
            moveReplacing(temporary, osPath);
            return true;
        }
        catch (IOException e)
        {
            failure = e;
            throw e;
        }
        finally
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException cleanupFailure)
            {
                if (failure == null)
                {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Creates a sibling staging file with the destination's POSIX permissions when available.
     * <p>
     * A new destination starts with ordinary 0644 permissions subject to the process umask. Other
     * file-system providers receive no unsupported attributes.
     * </p>
     *
     * @param target the destination whose permissions should be retained
     * @param directory the directory in which to create the staging file
     * @return the new staging file
     * @throws IOException when the file or its permissions cannot be created
     */
    private static Path createTemporaryFile(Path target, Path directory) throws IOException
    {
        Set<PosixFilePermission> permissions = DEFAULT_POSIX_FILE_PERMISSIONS;
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
        {
            if (Files.exists(target))
            {
                permissions = Files.getPosixFilePermissions(target);
            }
            FileAttribute<Set<PosixFilePermission>> attribute =
                PosixFilePermissions.asFileAttribute(permissions);
            return Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp", //$NON-NLS-1$ //$NON-NLS-2$
                attribute);
        }
        return Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removes staging files left by an earlier process once they are old enough not to be active.
     *
     * @param target the destination whose staging-file prefix identifies owned files
     * @param directory the directory containing the destination
     */
    private static void cleanupStaleTemporaryFiles(Path target, Path directory)
    {
        long cutoff = System.currentTimeMillis() - STALE_TEMPORARY_MILLIS;
        String pattern = target.getFileName().toString() + ".*.tmp"; //$NON-NLS-1$
        try (DirectoryStream<Path> stagedFiles = Files.newDirectoryStream(directory, pattern))
        {
            for (Path stagedFile : stagedFiles)
            {
                try
                {
                    if (Files.getLastModifiedTime(stagedFile).toMillis() < cutoff)
                    {
                        Files.deleteIfExists(stagedFile);
                    }
                }
                catch (IOException e)
                {
                    Activator.logError("Failed to remove stale cluster staging file " //$NON-NLS-1$
                        + stagedFile.getFileName(), e);
                }
            }
        }
        catch (IOException e)
        {
            Activator.logError("Failed to inspect cluster staging files beside " //$NON-NLS-1$
                + target.getFileName(), e);
        }
    }

    /**
     * Writes every byte of {@code bytes} to {@code path} and forces them to disk.
     *
     * @param path the file to write; it is truncated because it is a temporary file, not the destination
     * @param bytes the bytes to write
     * @throws IOException if the write stops short or the channel cannot be forced
     */
    private static void writeForced(Path path, byte[] bytes) throws IOException
    {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING))
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int written = 0;
            while (buffer.hasRemaining())
            {
                int count = channel.write(buffer);
                if (count <= 0)
                {
                    throw new IOException("writing " + path.getFileName() + " made no progress"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                written += count;
            }
            if (written != bytes.length)
            {
                throw new IOException("short write to " + path.getFileName()); //$NON-NLS-1$
            }
            channel.force(true);
        }
    }

    /**
     * Takes an exclusive lock on {@code osPath} without truncating it.
     * <p>
     * A missing destination needs no lock and is left absent until the atomic move. An existing file
     * is opened for write without {@code TRUNCATE_EXISTING}.
     * </p>
     *
     * @param osPath the destination
     * @return {@code true} when the lock was taken and released, {@code false} when it could not be taken
     * @throws IOException when the file cannot be created or opened for a reason other than access
     */
    private static boolean tryLockWithoutTruncating(Path osPath) throws IOException
    {
        if (Files.notExists(osPath))
        {
            return true;
        }
        try
        {
            try (FileChannel channel = FileChannel.open(osPath, StandardOpenOption.WRITE))
            {
                try
                {
                    FileLock lock = channel.tryLock();
                    if (lock == null)
                    {
                        return false;
                    }
                    lock.release();
                    return true;
                }
                catch (OverlappingFileLockException overlapping)
                {
                    return false;
                }
            }
        }
        catch (AccessDeniedException denied)
        {
            return false;
        }
    }

    /**
     * Replaces {@code target} with {@code temporary}, retrying a refused atomic move.
     * <p>
     * There is deliberately no non-atomic fallback: if every atomic attempt fails, the destination
     * remains intact and the failure is returned to the caller.
     * </p>
     *
     * @param temporary the staged file
     * @param target the destination
     * @throws IOException if the move fails
     */
    private static void moveReplacing(Path temporary, Path target) throws IOException
    {
        moveReplacing(temporary, target, (source, destination) -> Files.move(source, destination,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), Thread::sleep);
    }

    /**
     * Repeats an atomic replacement through injectable move and sleep operations.
     *
     * @param temporary the staged file
     * @param target the destination
     * @param mover the atomic move operation
     * @param sleeper the delay operation between attempts
     * @throws IOException when every attempt fails or the retry wait is interrupted
     */
    static void moveReplacing(Path temporary, Path target, AtomicMover mover, RetrySleeper sleeper)
        throws IOException
    {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= ATOMIC_MOVE_ATTEMPTS; attempt++)
        {
            try
            {
                mover.move(temporary, target);
                return;
            }
            catch (IOException e)
            {
                lastFailure = e;
            }
            if (attempt < ATOMIC_MOVE_ATTEMPTS)
            {
                try
                {
                    sleeper.sleep(ATOMIC_MOVE_RETRY_MILLIS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    IOException interrupted = new IOException("Interrupted while retrying atomic replacement", e); //$NON-NLS-1$
                    interrupted.addSuppressed(lastFailure);
                    throw interrupted;
                }
            }
        }
        throw lastFailure;
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
     * @param project the project whose settings folder is wanted
     * @return the settings folder on disk, or {@code null} when the project has none yet
     */
    private static java.nio.file.Path settingsFolderOnDisk(IProject project)
    {
        IPath location = project.getFolder(ClusterKeys.SETTINGS_FOLDER).getLocation();
        return location == null ? null : location.toFile().toPath();
    }

    /**

     * Returns the project's clusters file handle.

     *

     * @param project the project

     * @return the file handle, whether or not it exists

     */
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

