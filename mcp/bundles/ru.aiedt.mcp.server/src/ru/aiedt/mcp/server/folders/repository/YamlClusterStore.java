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

import java.nio.charset.CodingErrorAction;

import java.nio.charset.StandardCharsets;

import java.nio.file.FileAlreadyExistsException;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import java.nio.file.Path;

import java.util.ArrayList;

import java.util.Comparator;

import java.util.List;

import java.util.Map;
import java.util.Set;

import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;



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

import ru.aiedt.mcp.server.support.AtomicFileReplace;

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

 * <p>

 * The file on disk, not the resource tree, is what a project's clusters are read from: a file

 * written by another process - git, a checkout, an editor outside the workspace - is read even while

 * the resource tree still reports the resource absent. What a set was read from is remembered, and a

 * save whose file no longer holds those bytes is refused rather than applied: replacing what nobody

 * read is how a valid file loses the clusters it carried.

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

    /**
     * The digest of the bytes each project's set was read from, by project name.
     * <p>
     * The state is kept here rather than in {@link ClusterStore}, which is what the YAML is written
     * from: a field on the store would be dumped into the file, and a reader of another build would
     * meet a key it does not know. The map is per instance, and the manager that owns this store is
     * the only writer for a project's file. A name that is absent means this store never read that
     * project - which is not the same as having read no file, and is stored as
     * {@link AtomicFileReplace#NO_FILE_FINGERPRINT} instead.
     * </p>
     */
    private final Map<String, String> loadedFingerprints = new ConcurrentHashMap<>();


    @Override

    public ClusterStore load(IProject project)

    {

        IFile file = clustersFile(project);

        byte[] bytes;

        try

        {

            bytes = readClustersBytes(file);

        }

        catch (CoreException | IOException e)

        {

            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            forgetFingerprint(project);

            return null;

        }

        if (bytes == null)

        {

            rememberFingerprint(project, AtomicFileReplace.NO_FILE_FINGERPRINT);

            return new ClusterStore();

        }

        return readClusters(project, bytes);

    }

    /**
     * Reads the clusters file, following it onto the local disk when the resource tree does not have
     * it yet.
     * <p>
     * Another process writes the file - git checks a branch out, an editor outside the workspace saves
     * it - while the tree still answers that there is no such resource. The bytes on disk are the ones
     * the project has, so this refreshes that one resource and reads it; a refresh the workspace
     * refuses, which a folder it does not know causes, leaves the disk bytes as the only source and
     * they are read directly.
     * </p>
     *
     * @param file the clusters file handle
     * @return the bytes, or {@code null} when the file is not there at all
     * @throws CoreException when the file cannot be read through the workspace
     * @throws IOException when the file cannot be read from disk
     */
    private static byte[] readClustersBytes(IFile file) throws CoreException, IOException

    {

        if (file.exists())

        {

            return contents(file);

        }

        IPath location = file.getLocation();

        if (location == null)

        {

            return null;

        }

        Path path = location.toFile().toPath();

        if (!Files.exists(path))

        {

            return null;

        }

        try

        {

            file.refreshLocal(IResource.DEPTH_ZERO, null);

        }

        catch (CoreException e)

        {

            // The tree has no parent for this file, so the refresh has nothing to attach to; the
            // bytes on disk are the project's own either way and are read below.

        }

        return file.exists() ? contents(file) : Files.readAllBytes(path);

    }

    /**
     * Reads the bytes of a workspace file.

     *

     * @param file the file to read

     * @return the bytes

     * @throws CoreException when the workspace refuses the read

     * @throws IOException when the stream cannot be read

     */

    private static byte[] contents(IFile file) throws CoreException, IOException

    {

        try (InputStream in = file.getContents())

        {

            return in.readAllBytes();

        }

    }

    /**
     * Reads a project's clusters out of the bytes the file held, and records those bytes.
     * <p>
     * What was read is remembered before the parse is judged: a set that came from these bytes is a
     * base a later save may build on, and bytes the loader cannot read are remembered as nothing read
     * at all, so no save overwrites them.
     * </p>
     *
     * @param project the project, for the log line and the fingerprint
     * @param bytes the file contents
     * @return the clusters, or {@code null} when the bytes are not a document this loader accepts
     */
    private ClusterStore readClusters(IProject project, byte[] bytes)

    {

        try (Reader reader = strictUtf8Reader(new ByteArrayInputStream(bytes)))

        {

            Yaml yaml = createLoadYaml();

            ClusterStore storage = yaml.load(reader);

            rememberFingerprint(project, AtomicFileReplace.fingerprint(bytes));

            if (storage == null)

            {

                return new ClusterStore();

            }

            cleanupOrphanedFqns(storage);

            return storage;

        }

        catch (YAMLException e)

        {

            forgetFingerprint(project);

            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$

                + " could not be parsed and was left unchanged: " + e.getMessage()); //$NON-NLS-1$

            return null;

        }

        catch (IOException e)

        {

            forgetFingerprint(project);

            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$

            return null;

        }

    }

    /**
     * Remembers the fingerprint of the content a project's clusters were read from.

     *

     * @param project the project

     * @param fingerprint the digest of the bytes, or {@link AtomicFileReplace#NO_FILE_FINGERPRINT}

     */

    private void rememberFingerprint(IProject project, String fingerprint)

    {

        loadedFingerprints.put(project.getName(), fingerprint);

    }

    /**
     * Forgets what a project's clusters were read from, so no save counts the file as a base.

     *

     * @param project the project

     */

    private void forgetFingerprint(IProject project)

    {

        loadedFingerprints.remove(project.getName());

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
     * An existing file that this loader cannot parse is not replaced: the bytes stay, a copy is
     * written beside the file, and the outcome is {@link ClusterSaveOutcome#UNREADABLE_FILE}.
     * A file that changed after the set was read is not replaced either. Saving bytes identical
     * to the ones already read is {@link ClusterSaveOutcome#NO_CHANGE} and does not touch the file.
     * Saving an empty set deletes a file that did parse, as long as it is the one the empty set
     * came from. After a write, the bytes written are what the next save compares against.
     * </p>
     *
     * @param project the project
     * @param storage the clusters to save
     * @return what the save did
     */
    @Override
    public ClusterSaveOutcome save(IProject project, ClusterStore storage)
    {
        ClusterSaveOutcome blocked = unreadableFileBlocksSave(project);
        if (blocked != null)
        {
            return blocked;
        }
        ClusterSaveOutcome drift = isTheFileThatWasRead(project);
        if (drift != null)
        {
            return drift;
        }
        if (storage == null || storage.isEmpty())
        {
            ClusterSaveOutcome deleted = deleteIfExists(project);
            if (deleted.isRefused() || deleted.isNoChange())
            {
                return deleted;
            }
            rememberWritten(project, null);
            return ClusterSaveOutcome.ok();
        }
        String content = dump(sortForOutput(storage));
        if (writesTheBytesAlreadyRead(project, content.getBytes(StandardCharsets.UTF_8)))
        {
            return ClusterSaveOutcome.noChange();
        }
        return saveWithLock(project, content);
    }

    /**
     * Tells whether the clusters file still holds what the set in hand was read from.
     * <p>
     * The set was read from the file as it stood. Another process may have written the file since -
     * git checks a branch out, an editor outside the workspace saves it - and what the file then
     * holds is a set nobody read. Writing over it replaces content that was never in hand, which is
     * how a valid file loses the clusters it carried, so the write is refused and the file is left
     * alone. A file that was absent when the set was read and is there now counts as changed for the
     * same reason: the set was built without it.
     * </p>
     * <p>
     * A store that never read this project has nothing the set could have come from. It may create a
     * file where there is none and may not replace one that is there.
     * </p>
     *
     * @param project the project
     * @return the refusal, or {@code null} when the write may go ahead
     */
    private ClusterSaveOutcome isTheFileThatWasRead(IProject project)
    {
        String onDisk = diskFingerprint(clustersFile(project));
        if (onDisk == null)
        {
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " could not be read before writing it; the write was refused"); //$NON-NLS-1$
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.READ_FAILED);
        }
        String read = loadedFingerprints.get(project.getName());
        if (read == null ? AtomicFileReplace.NO_FILE_FINGERPRINT.equals(onDisk) : read.equals(onDisk))
        {
            return null;
        }
        if (read == null)
        {
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " exists on disk and this store has not read it; the write was refused and the file " //$NON-NLS-1$
                + "was left as it is"); //$NON-NLS-1$
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.NOT_READ_BY_THIS_STORE);
        }
        Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
            + " changed on disk after it was read; the write was refused and the file was left as " //$NON-NLS-1$
            + "it is"); //$NON-NLS-1$
        return ClusterSaveOutcome.refused(ClusterSaveOutcome.CHANGED_ON_DISK);
    }

    /**
     * Records the bytes this store just wrote, or records that the file is gone.
     * <p>
     * The digest is taken from those bytes. The file is not read back: a write that lands between
     * the replacement and a later read is someone else's content, and the next save has to notice
     * that it is not what this store wrote.
     * </p>
     *
     * @param project the project
     * @param written the bytes just written, or {@code null} when the file was removed
     */
    private void rememberWritten(IProject project, byte[] written)
    {
        rememberFingerprint(project, written == null ? AtomicFileReplace.NO_FILE_FINGERPRINT : AtomicFileReplace.fingerprint(written));
    }

    /**
     * Tells whether the bytes about to be written are the bytes the set was read from.
     *
     * @param project the project
     * @param bytes the bytes that would be written
     * @return {@code true} when writing them would not change the file
     */
    private boolean writesTheBytesAlreadyRead(IProject project, byte[] bytes)
    {
        String read = loadedFingerprints.get(project.getName());
        return read != null && read.equals(AtomicFileReplace.fingerprint(bytes));
    }

    /**
     * The text of an exception to keep with a refused write.
     *
     * @param failure the exception
     * @return its message, or {@link Throwable#toString()} when it has none
     */
    private static String exceptionText(Throwable failure)
    {
        String message = failure.getMessage();
        if (message == null || message.isEmpty())
        {
            return failure.toString();
        }
        return message;
    }

    /**
     * The fingerprint of the clusters file as it stands now, or {@code null} when it cannot be read.
     * <p>
     * The bytes are taken the same way {@link #load(IProject)} takes them, so a file that only exists
     * on disk is compared by what it holds rather than by a resource the tree does not have.
     * </p>
     *
     * @param file the clusters file handle
     * @return the fingerprint, {@link AtomicFileReplace#NO_FILE_FINGERPRINT} when there is no file, or {@code null}
     *         when the file cannot be read
     */
    private static String diskFingerprint(IFile file)
    {
        try
        {
            byte[] bytes = readClustersBytes(file);
            return bytes == null ? AtomicFileReplace.NO_FILE_FINGERPRINT : AtomicFileReplace.fingerprint(bytes);
        }
        catch (CoreException | IOException e)
        {
            Activator.logError("Failed to read aiedt-clusters.yaml before writing it", e); //$NON-NLS-1$
            return null;
        }
    }



    @Override

    public boolean exists(IProject project)

    {

        return clustersFile(project).exists();

    }



    /**
     * Deletes a project's clusters file.
     *
     * @param project the project
     * @return {@code true} if the file is gone afterwards
     */
    @Override
    public boolean delete(IProject project)
    {
        return deleteIfExists(project).succeeded();
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
     * @return the refusal, or {@code null} when the save may go ahead
     */
    private ClusterSaveOutcome unreadableFileBlocksSave(IProject project)
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
            return null;
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
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.READ_FAILED, exceptionText(e));
        }
        if (bytes.length == 0 || parses(bytes))
        {
            return null;
        }
        Path backup = backupUnreadable(project, path, bytes);
        return ClusterSaveOutcome.refused(ClusterSaveOutcome.UNREADABLE_FILE,
            backup == null ? null : backup.toString());
    }

    /**
     * Checks a non-local workspace file when no operating-system path is available.
     *
     * @param project the project, for diagnostics
     * @param file the workspace file
     * @return the refusal, or {@code null} when the save may go ahead
     */
    private ClusterSaveOutcome unreadableWorkspaceFileBlocksSave(IProject project, IFile file)
    {
        if (!file.exists())
        {
            return null;
        }
        try (InputStream in = file.getContents())
        {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0 || parses(bytes))
            {
                return null;
            }
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " could not be parsed; the write was refused"); //$NON-NLS-1$
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.UNREADABLE_FILE);
        }
        catch (CoreException | IOException e)
        {
            Activator.logError("Failed to read aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " before writing it; the write was refused", e); //$NON-NLS-1$
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.READ_FAILED, exceptionText(e));
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
     * @return the backup path when a copy is there, or {@code null} when the copy could not be written
     */
    private static Path backupUnreadable(IProject project, Path file, byte[] bytes)
    {
        Path backup = file.resolveSibling(ClusterKeys.CLUSTERS_FILE + ".bak"); //$NON-NLS-1$
        try
        {
            Files.write(backup, bytes, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " could not be parsed; the write was refused and a copy kept at " //$NON-NLS-1$
                + backup.getFileName());
            return backup;
        }
        catch (FileAlreadyExistsException e)
        {
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " is still unreadable; the existing backup was preserved at " //$NON-NLS-1$
                + backup.getFileName());
            return backup;
        }
        catch (IOException e)
        {
            Activator.logError("Could not back up unreadable aiedt-clusters.yaml for " //$NON-NLS-1$
                + project.getName(), e);
            return null;
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
     * Writes the content through the shared atomic replace helper. When the file has no
     * location on disk, writes through the workspace instead.
     * <p>
     * The write runs under the file's lock, against the fingerprint of the bytes this store
     * read. A write that succeeds is remembered from the bytes handed to the helper, not from
     * a later read of the file, and the workspace is refreshed after the write; a refresh the
     * workspace refuses is a warning on a completed write rather than a failure of it.
     * </p>
     *
     * @param project the project
     * @param content the YAML to write
     * @return what the write did
     */
    private ClusterSaveOutcome saveWithLock(IProject project, String content)
    {
        IFile clustersFile = clustersFile(project);
        IPath location = clustersFile.getLocation();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (location == null)
        {
            ClusterSaveOutcome direct = saveDirectly(project, clustersFile, content);
            if (direct.isOk())
            {
                rememberWritten(project, bytes);
            }
            return direct;
        }
        Path osPath = location.toFile().toPath();
        String read = loadedFingerprints.get(project.getName());
        String expected = read == null ? AtomicFileReplace.NO_FILE_FINGERPRINT : read;
        AtomicFileReplace.Outcome written = AtomicFileReplace.replace(osPath, expected, bytes,
            clustersFile);
        if (!written.isOk())
        {
            Activator.logWarning("aiedt-clusters.yaml for " + project.getName() //$NON-NLS-1$
                + " could not be written (" + written + "); the write was refused"); //$NON-NLS-1$ //$NON-NLS-2$
            return refusal(written);
        }
        rememberWritten(project, bytes);
        return ClusterSaveOutcome.ok();
    }

    /**
     * Translates a refusal of the shared write helper into this store's outcome.
     *
     * @param refused the helper's refusal
     * @return the matching cluster save refusal
     */
    private static ClusterSaveOutcome refusal(AtomicFileReplace.Outcome refused)
    {
        if (AtomicFileReplace.CHANGED_ON_DISK.equals(refused.getCode()))
        {
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.CHANGED_ON_DISK,
                refused.getDetail());
        }
        if (AtomicFileReplace.READ_FAILED.equals(refused.getCode()))
        {
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.READ_FAILED,
                refused.getDetail());
        }
        if (AtomicFileReplace.LOCK_REFUSED.equals(refused.getCode()))
        {
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.LOCK_REFUSED,
                refused.getDetail());
        }
        if (AtomicFileReplace.ACCESS_DENIED.equals(refused.getCode()))
        {
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.ACCESS_DENIED,
                refused.getDetail());
        }
        return ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED, refused.getDetail());
    }

    /**
     * Writes the content through the workspace, creating the settings folder and file as needed.
     *
     * @param project the project
     * @param clustersFile the target file
     * @param content the YAML to write
     * @return what the write did
     */
    private ClusterSaveOutcome saveDirectly(IProject project, IFile clustersFile, String content)
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
            return ClusterSaveOutcome.ok();
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to write aiedt-clusters.yaml through the workspace for " //$NON-NLS-1$
                + project.getName(), e);
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED, exceptionText(e));
        }
    }



    /**
     * Deletes the clusters file when it is on disk, regardless of whether the resource tree
     * knows it.
     * <p>
     * The file's existence is decided by the disk, not by the tree: a file another process
     * wrote is still the project's clusters file while the tree reports no such resource, and
     * a delete that trusted the tree would leave it behind and answer that nothing changed.
     * The removal runs under the file's lock against the fingerprint of the bytes this store
     * read. A project whose file has no location on disk is deleted through the workspace.
     * </p>
     *
     * @param project the project
     * @return {@link ClusterSaveOutcome#ok()} when the file was deleted,
     *         {@link ClusterSaveOutcome#noChange()} when there was no file to delete, or a
     *         refusal
     */
    private ClusterSaveOutcome deleteIfExists(IProject project)
    {
        IFile file = clustersFile(project);
        IPath location = file.getLocation();
        if (location == null)
        {
            return deleteThroughWorkspace(project, file);
        }
        Path osPath = location.toFile().toPath();
        if (Files.notExists(osPath))
        {
            return ClusterSaveOutcome.noChange();
        }
        String read = loadedFingerprints.get(project.getName());
        String expected = read == null ? AtomicFileReplace.NO_FILE_FINGERPRINT : read;
        AtomicFileReplace.Outcome removed = AtomicFileReplace.remove(osPath, expected, file);
        return removed.isOk() ? ClusterSaveOutcome.ok() : refusal(removed);
    }

    /**
     * Deletes the clusters file through the workspace, the only route available when the file
     * has no location on disk.
     *
     * @param project the project
     * @param file the workspace file
     * @return {@link ClusterSaveOutcome#ok()} when the file was deleted,
     *         {@link ClusterSaveOutcome#noChange()} when it was already absent, or a
     *         {@link ClusterSaveOutcome#WRITE_FAILED} refusal
     */
    private static ClusterSaveOutcome deleteThroughWorkspace(IProject project, IFile file)
    {
        if (!file.exists())
        {
            return ClusterSaveOutcome.noChange();
        }
        try
        {
            file.delete(true, null);
            return ClusterSaveOutcome.ok();
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to delete aiedt-clusters.yaml for " + project.getName(), e); //$NON-NLS-1$
            return ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED, exceptionText(e));
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

