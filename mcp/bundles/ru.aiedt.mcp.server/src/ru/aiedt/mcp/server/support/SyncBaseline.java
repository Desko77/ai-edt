/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * The synchronization baseline EDT keeps per infobase - {@code ib-sync/ss/<infobase>/index.idx}
 * - read and written in either of its layouts, and the in-memory copy EDT holds of it.
 *
 * <p>EDT 2026 keeps the store in the project's private working location inside the workspace,
 * beside the infobase's {@code ConfigDumpInfo.xml}; older EDT kept it under
 * {@code %APPDATA%/.1cedt/ib-sync/ss}. The file starts with a version ({@code "1.0"}) in the
 * newer layout, then a timestamp, the signatures - one per resource path, with a flag and an
 * optional resource id in the newer layout - a generation id and the configuration id.</p>
 *
 * <p>An incremental update compares the current resource signatures with this file: a path whose
 * signature differs is exported and loaded. That is what {@link #blankSignature} is for: a
 * resource whose signature is blanked reads as changed to the next update, which is how a change
 * EDT itself does not track - the container of an ordinary form - reaches the infobase through
 * the object that owns it.</p>
 */
public final class SyncBaseline
{
    /** The plug-in whose working location holds the store in EDT 2026. */
    public static final String STORE_PLUGIN = "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$

    /** The index file name. */
    public static final String INDEX_FILE = "index.idx"; //$NON-NLS-1$

    /** Sanity cap on the signature count read from a possibly corrupt index. */
    private static final int MAX_SIGNATURE_COUNT = 5_000_000;

    /** Sanity cap on a per-signature byte length. */
    private static final int MAX_SIGNATURE_BYTES = 10_000_000;

    /** The content of an {@code index.idx}, in either layout. */
    public static final class Index
    {
        /** Whether the file carries the version prefix of EDT 2026. */
        public boolean versioned;

        /** The version string when versioned. */
        public String version;

        /** The timestamp of the sync. */
        public long timestamp;

        /** The resource paths, {@code src/...}. */
        public final List<String> keys = new ArrayList<>();

        /** The signature of each path, parallel to {@link #keys}. */
        public final List<byte[]> signatures = new ArrayList<>();

        /** The resource id of each path when the layout carries one, else {@code null}. */
        public final List<String> resourceUuids = new ArrayList<>();

        /** The generation id recorded at the sync. */
        public String generationId;

        /** The configuration id recorded at the sync. */
        public String configurationUuid;

        /**
         * The position of a key.
         *
         * @param key the resource path
         * @return the index, or -1
         */
        public int indexOf(String key)
        {
            return keys.indexOf(key);
        }
    }

    private SyncBaseline()
    {
        // static utility
    }

    /**
     * Whether an index starts with the version prefix EDT 2026 writes.
     *
     * @param all the file's bytes
     * @return {@code true} for the versioned layout
     */
    public static boolean isVersioned(byte[] all)
    {
        return all.length >= 5 && all[0] == 0 && all[1] == 3 && all[2] == '1' && all[3] == '.' && all[4] == '0';
    }

    /**
     * Reads an index, either layout.
     *
     * @param file the index
     * @return its content
     * @throws IOException when the bytes do not read as an index
     */
    public static Index read(Path file) throws IOException
    {
        byte[] all = Files.readAllBytes(file);
        Index index = new Index();
        index.versioned = isVersioned(all);
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(all)))
        {
            if (index.versioned)
            {
                index.version = dis.readUTF();
            }
            index.timestamp = dis.readLong();
            int count = dis.readInt();
            if (count < 0 || count > MAX_SIGNATURE_COUNT)
            {
                throw new IOException("index.idx signature count out of range: " + count); //$NON-NLS-1$
            }
            for (int i = 0; i < count; i++)
            {
                index.keys.add(dis.readUTF());
                int len = dis.readInt();
                if (len < 0 || len > MAX_SIGNATURE_BYTES)
                {
                    throw new IOException("index.idx signature length out of range: " + len); //$NON-NLS-1$
                }
                byte[] b = new byte[len];
                dis.readFully(b);
                index.signatures.add(b);
                String uuid = null;
                if (index.versioned && dis.readBoolean())
                {
                    uuid = dis.readUTF();
                }
                index.resourceUuids.add(uuid);
            }
            index.generationId = dis.readUTF();
            index.configurationUuid = dis.readUTF();
        }
        return index;
    }

    /**
     * Writes an index in the layout it was read in, through a temporary file beside it.
     *
     * @param index the content
     * @param file the index to replace
     * @throws IOException when the write fails
     */
    public static void write(Index index, Path file) throws IOException
    {
        File tmp = new File(file.toFile().getAbsolutePath() + ".tmp"); //$NON-NLS-1$
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmp)))
        {
            if (index.versioned)
            {
                dos.writeUTF(index.version);
            }
            dos.writeLong(index.timestamp);
            dos.writeInt(index.keys.size());
            for (int i = 0; i < index.keys.size(); i++)
            {
                dos.writeUTF(index.keys.get(i));
                dos.writeInt(index.signatures.get(i).length);
                dos.write(index.signatures.get(i));
                if (index.versioned)
                {
                    String uuid = index.resourceUuids.get(i);
                    dos.writeBoolean(uuid != null);
                    if (uuid != null)
                    {
                        dos.writeUTF(uuid);
                    }
                }
            }
            dos.writeUTF(index.generationId);
            dos.writeUTF(index.configurationUuid);
        }
        Files.move(tmp.toPath(), file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Blanks the signature of one resource in an index, so the next update reads the resource as
     * changed. A signature that is already blank is left alone.
     *
     * @param file the index
     * @param key the resource path, {@code src/...}
     * @return whether the file was written
     * @throws IOException when the index cannot be read or written
     */
    public static boolean blankSignature(Path file, String key) throws IOException
    {
        Index index = read(file);
        int at = index.indexOf(key);
        if (at < 0)
        {
            return false;
        }
        byte[] signature = index.signatures.get(at);
        boolean blank = true;
        for (byte b : signature)
        {
            if (b != 0)
            {
                blank = false;
                break;
            }
        }
        if (blank)
        {
            return false;
        }
        index.signatures.set(at, new byte[signature.length]);
        write(index, file);
        return true;
    }

    /**
     * The store EDT 2026 keeps for a project: {@code ib-sync/ss} under the project's private
     * working location of the platform services plug-in.
     *
     * @param project the project
     * @return the store path; it need not exist
     */
    public static Path workspaceStore(IProject project)
    {
        return project.getWorkingLocation(STORE_PLUGIN).toFile().toPath().resolve("ib-sync").resolve("ss"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The store older EDT kept per user, outside any workspace.
     *
     * @return the store path; it need not exist
     */
    public static Path roamingStore()
    {
        String appData = System.getenv("APPDATA"); //$NON-NLS-1$
        Path base = (appData != null && !appData.isEmpty())
            ? Paths.get(appData)
            : Paths.get(System.getProperty("user.home")); //$NON-NLS-1$
        return base.resolve(".1cedt").resolve("ib-sync").resolve("ss"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The stores that exist for a project, the workspace one first.
     *
     * @param project the project
     * @return the existing store directories, possibly none
     */
    public static List<Path> stores(IProject project)
    {
        List<Path> roots = new ArrayList<>();
        for (Path candidate : new Path[] { workspaceStore(project), roamingStore() })
        {
            if (candidate.toFile().isDirectory())
            {
                roots.add(candidate);
            }
        }
        return roots;
    }

    /**
     * The index of an infobase: the first store that holds one, the workspace store's candidate
     * when none does, so a refusal names the place EDT 2026 would write.
     *
     * @param project the project
     * @param infobaseUuid the infobase
     * @return the index path; it need not exist
     */
    public static Path indexOf(IProject project, String infobaseUuid)
    {
        Path first = null;
        for (Path root : new Path[] { workspaceStore(project), roamingStore() })
        {
            Path idx = root.resolve(infobaseUuid).resolve(INDEX_FILE);
            if (first == null)
            {
                first = idx;
            }
            if (idx.toFile().isFile())
            {
                return idx;
            }
        }
        return first;
    }

    /**
     * The indexes of the project's own infobases - every one in the workspace store.
     *
     * @param project the project
     * @return the existing index files, keyed by nothing: the infobase id is the parent
     *         directory's name
     */
    public static List<Path> workspaceIndexes(IProject project)
    {
        List<Path> indexes = new ArrayList<>();
        File store = workspaceStore(project).toFile();
        File[] dirs = store.isDirectory() ? store.listFiles(File::isDirectory) : null;
        if (dirs != null)
        {
            for (File dir : dirs)
            {
                File idx = new File(dir, INDEX_FILE);
                if (idx.isFile())
                {
                    indexes.add(idx.toPath());
                }
            }
        }
        return indexes;
    }

    /**
     * Drops EDT's cached in-memory holder of an infobase's synchronization state, so the next
     * equality check and the next update reload the baseline from disk.
     *
     * <p>EDT only (re)loads the holder when it is absent, so removing it is what makes a rewritten
     * file count without a restart. 2026.1 keys the holders in a nested map
     * {@code projectInfobaseSyncStates: Map<projectName, Map<infobaseUuid, holder>>}; older EDT
     * used a flat {@code synchronizationStates: Map<infobaseUuid, holder>}. Both are handled.
     * Never throws.</p>
     *
     * @param ibUuid the infobase
     * @return what happened, for an answer
     */
    public static String dropCachedHolder(UUID ibUuid)
    {
        try
        {
            IInfobaseSynchronizationStateManager mgr = ServiceAccess.get(IInfobaseSynchronizationStateManager.class);
            if (mgr == null)
            {
                return "skipped (state manager unavailable)"; //$NON-NLS-1$
            }
            Object delegate = mgr.getClass().getMethod("getDelegate").invoke(mgr); //$NON-NLS-1$
            if (delegate == null)
            {
                return "skipped (delegate null)"; //$NON-NLS-1$
            }
            int dropped = dropNestedHolder(delegate, "projectInfobaseSyncStates", ibUuid) //$NON-NLS-1$
                + dropFlatHolder(delegate, "synchronizationStates", ibUuid); //$NON-NLS-1$
            return dropped > 0 ? "ok (dropped " + dropped + " cached holder(s), reloads from disk)" //$NON-NLS-1$ //$NON-NLS-2$
                : "ok (no cached holder for this infobase; disk baseline is authoritative)"; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "skipped (" + TextSuggest.safeMessage(e) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Removes the infobase holder from a 2026.1 nested {@code Map<projectName, Map<uuid, holder>>}. */
    private static int dropNestedHolder(Object delegate, String fieldName, UUID ibUuid)
    {
        Field field = findField(delegate.getClass(), fieldName);
        if (field == null)
        {
            return 0;
        }
        try
        {
            field.setAccessible(true);
            Object value = field.get(delegate);
            if (!(value instanceof Map))
            {
                return 0;
            }
            int dropped = 0;
            for (Object inner : ((Map<?, ?>)value).values())
            {
                if (inner instanceof Map && ((Map<?, ?>)inner).keySet().removeIf(k -> matchesUuid(ibUuid, k)))
                {
                    dropped++;
                }
            }
            return dropped;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /** Removes the infobase holder from an older flat {@code Map<uuid, holder>}. */
    private static int dropFlatHolder(Object delegate, String fieldName, UUID ibUuid)
    {
        Field field = findField(delegate.getClass(), fieldName);
        if (field == null)
        {
            return 0;
        }
        try
        {
            field.setAccessible(true);
            Object value = field.get(delegate);
            if (!(value instanceof Map))
            {
                return 0;
            }
            return ((Map<?, ?>)value).keySet().removeIf(k -> matchesUuid(ibUuid, k)) ? 1 : 0;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /**
     * True if {@code key} is the infobase UUID, as a java.util.UUID or its string form.
     *
     * @param ibUuid the infobase
     * @param key a map key
     * @return whether they name the same infobase
     */
    public static boolean matchesUuid(UUID ibUuid, Object key)
    {
        return ibUuid.equals(key) || ibUuid.toString().equalsIgnoreCase(String.valueOf(key));
    }

    /**
     * Finds a declared field by name, walking up the class hierarchy.
     *
     * @param type the class
     * @param name the field name
     * @return the field, or {@code null}
     */
    public static Field findField(Class<?> type, String name)
    {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                // try the superclass
            }
        }
        return null;
    }
}
