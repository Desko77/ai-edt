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
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

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
 * made outside EDT's own tracking reaches the infobase through the object that owns it.</p>
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

    /** The root {@code uuid} attribute of a {@code Configuration.mdo}. */
    private static final Pattern UUID_ATTR =
        Pattern.compile("uuid=\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\""); //$NON-NLS-1$

    /** Bytes read from the head of {@code Configuration.mdo} to find the root uuid attribute. */
    private static final int MDO_HEAD_BYTES = 8192;

    /**
     * Serializes every read-modify-write of an index: two writers reading the same file before
     * either writes it would each keep only its own change, and a shared temporary file could
     * be moved by the writer that did not fill it.
     */
    private static final Object WRITE_LOCK = new Object();

    /**
     * Why {@link #indexes(IProject)} left the per-user store unread on this thread, or {@code null}
     * when that store was read. Set on every call, so a later success does not keep an earlier failure.
     */
    private static final ThreadLocal<String> PER_USER_STORE_OMISSION = new ThreadLocal<>();

    /** Named when the application manager cannot be asked. */
    private static final String PER_USER_STORE_UNAVAILABLE =
        "the per-user sync store was not read: the application manager is unavailable"; //$NON-NLS-1$

    /**
     * The per-user store {@link #indexes(IProject)} reads. {@code null} uses {@link #roamingStore()}.
     * Tests point this at a temporary directory so a real per-user baseline is never consulted.
     */
    static volatile Path sharedStoreForTests;

    /**
     * The application manager {@link #indexes(IProject)} asks. {@code null} asks EDT. A callable that
     * returns {@code null} is an unavailable manager; one that throws is a manager that failed.
     * Tests install one and clear it afterwards.
     */
    static volatile Callable<IApplicationManager> applicationsForTests;

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
     * Writes an index in the layout it was read in, through a temporary file of its own beside
     * it. Writes are serialized within this server.
     *
     * @param index the content
     * @param file the index to replace
     * @throws IOException when the write fails
     */
    public static void write(Index index, Path file) throws IOException
    {
        synchronized (WRITE_LOCK)
        {
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), INDEX_FILE, ".tmp"); //$NON-NLS-1$
            try
            {
                writeTo(index, tmp);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            finally
            {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static void writeTo(Index index, Path tmp) throws IOException
    {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmp.toFile())))
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
    }

    /**
     * Blanks the signature of one resource in an index, so the next update reads the resource as
     * changed. A signature that is already blank is left alone. The read and the write are one
     * step under the lock every write takes, so two callers blanking two resources of one index
     * both land.
     *
     * @param file the index
     * @param key the resource path, {@code src/...}
     * @return whether the file was written
     * @throws IOException when the index cannot be read or written
     */
    public static boolean blankSignature(Path file, String key) throws IOException
    {
        synchronized (WRITE_LOCK)
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
    }

    /**
     * The root {@code uuid} of {@code src/Configuration/Configuration.mdo} - the id EDT's update
     * flow compares with the one a baseline recorded. {@code sync_control} status uses that
     * comparison to predict a full reload. It does not select which baselines belong to the
     * project; {@link #indexes(IProject)} does that from the project's applications.
     *
     * @param project the project
     * @return the id, or {@code null} when the file is missing or carries none
     */
    public static String configurationUuid(IProject project)
    {
        if (project.getLocation() == null)
        {
            return null;
        }
        Path mdo = Paths.get(project.getLocation().toOSString(), "src", "Configuration", "Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!mdo.toFile().isFile())
        {
            return null;
        }
        try (InputStream in = Files.newInputStream(mdo))
        {
            byte[] buf = new byte[MDO_HEAD_BYTES];
            int total = 0;
            int n;
            while (total < MDO_HEAD_BYTES && (n = in.read(buf, total, MDO_HEAD_BYTES - total)) > 0)
            {
                total += n;
            }
            Matcher m = UUID_ATTR.matcher(new String(Arrays.copyOf(buf, total), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        }
        catch (IOException e)
        {
            Activator.logError("Configuration.mdo of " + project.getName() + " was not read", e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
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
     * The indexes of the infobases this project is bound to. Every index in the workspace store is
     * included: that store belongs to this project alone. From the per-user store, only an index
     * whose directory name is the uuid of an infobase application of this project. The recorded
     * configuration id is not consulted.
     *
     * <p>When the application manager is unavailable or throws, the per-user store is not read at
     * all and {@link #perUserStoreOmission()} returns the reason, for the caller to name in its
     * answer. Otherwise that method returns {@code null}.</p>
     *
     * @param project the project
     * @return the existing index files; the infobase id is the parent directory's name
     */
    public static List<Path> indexes(IProject project)
    {
        List<Path> indexes = workspaceIndexes(project);
        ProjectApplications applications = projectApplications(project);
        PER_USER_STORE_OMISSION.set(applications.omission);
        if (applications.omission != null)
        {
            Activator.logWarning(applications.omission);
            return indexes;
        }
        indexes.addAll(indexesNamed(sharedStore(), applications.uuids));
        return indexes;
    }

    /**
     * Why the last {@link #indexes(IProject)} on this thread left the per-user store unread, or
     * {@code null} when that store was read. The caller names a non-null reason in its answer, so an
     * unavailable application manager is not mistaken for a project with no per-user baselines.
     *
     * @return the reason, or {@code null}
     */
    public static String perUserStoreOmission()
    {
        return PER_USER_STORE_OMISSION.get();
    }

    /**
     * The indexes in the workspace store of a project - every one, since that store holds the
     * baselines of this project's infobases and no other's.
     *
     * @param project the project
     * @return the existing index files; the infobase id is the parent directory's name
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
     * The indexes in a store whose recorded configuration id equals the given one. An index that
     * does not read is left out. {@code null} matches nothing.
     *
     * <p>This is the comparison {@code sync_control} status shows as {@code matchesProject}: whether
     * a stored baseline was recorded for this configuration. It is not the set of baselines that
     * belong to a project. {@link #indexes(IProject)} does not use it.</p>
     *
     * @param store the store, {@code ib-sync/ss}; it need not exist
     * @param configurationUuid the id to match; {@code null} matches nothing
     * @return the matching index files; the infobase id is the parent directory's name
     */
    public static List<Path> matchingIndexes(Path store, String configurationUuid)
    {
        List<Path> indexes = new ArrayList<>();
        File[] dirs = configurationUuid != null && store.toFile().isDirectory()
            ? store.toFile().listFiles(File::isDirectory) : null;
        if (dirs != null)
        {
            for (File dir : dirs)
            {
                File idx = new File(dir, INDEX_FILE);
                if (!idx.isFile())
                {
                    continue;
                }
                try
                {
                    if (configurationUuid.equals(read(idx.toPath()).configurationUuid))
                    {
                        indexes.add(idx.toPath());
                    }
                }
                catch (IOException e)
                {
                    Activator.logWarning("Baseline " + idx + " was not read: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
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

    /** The per-user store {@link #indexes(IProject)} reads, or the test double standing in for it. */
    private static Path sharedStore()
    {
        Path override = sharedStoreForTests;
        return override != null ? override : roamingStore();
    }

    /**
     * The infobases the project is bound to, or why that question could not be asked.
     * A failure here is the whole answer: a partial list would still hand a foreign baseline to a
     * caller that blanks every path it is given.
     */
    private static ProjectApplications projectApplications(IProject project)
    {
        IApplicationManager manager;
        try
        {
            manager = applicationManager();
        }
        catch (Exception failed)
        {
            return ProjectApplications.omitted(applicationManagerFailed(failed));
        }
        if (manager == null)
        {
            return ProjectApplications.omitted(PER_USER_STORE_UNAVAILABLE);
        }
        try
        {
            return ProjectApplications.read(infobaseUuids(manager.getApplications(project)));
        }
        catch (Exception failed)
        {
            return ProjectApplications.omitted(applicationManagerFailed(failed));
        }
    }

    private static IApplicationManager applicationManager() throws Exception
    {
        Callable<IApplicationManager> installed = applicationsForTests;
        if (installed != null)
        {
            return installed.call();
        }
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getApplicationManager();
    }

    private static Set<String> infobaseUuids(List<IApplication> applications)
    {
        Set<String> uuids = new HashSet<>();
        if (applications == null)
        {
            return uuids;
        }
        for (IApplication application : applications)
        {
            if (!(application instanceof IInfobaseApplication))
            {
                continue;
            }
            InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
            if (infobase == null || infobase.getUuid() == null)
            {
                continue;
            }
            uuids.add(infobase.getUuid().toString());
        }
        return uuids;
    }

    private static String applicationManagerFailed(Exception failed)
    {
        return "the per-user sync store was not read: the application manager failed (" //$NON-NLS-1$
            + TextSuggest.safeMessage(failed) + ")"; //$NON-NLS-1$
    }

    /**
     * Indexes in {@code store} whose directory name is one of {@code infobaseUuids}. The recorded
     * configuration id is not read.
     */
    private static List<Path> indexesNamed(Path store, Set<String> infobaseUuids)
    {
        List<Path> indexes = new ArrayList<>();
        if (infobaseUuids == null || infobaseUuids.isEmpty() || !store.toFile().isDirectory())
        {
            return indexes;
        }
        Set<String> wanted = new HashSet<>();
        for (String uuid : infobaseUuids)
        {
            if (uuid != null)
            {
                wanted.add(uuid.toLowerCase(Locale.ROOT));
            }
        }
        File[] dirs = store.toFile().listFiles(File::isDirectory);
        if (dirs == null)
        {
            return indexes;
        }
        for (File dir : dirs)
        {
            if (!wanted.contains(dir.getName().toLowerCase(Locale.ROOT)))
            {
                continue;
            }
            File idx = new File(dir, INDEX_FILE);
            if (idx.isFile())
            {
                indexes.add(idx.toPath());
            }
        }
        return indexes;
    }

    /** Infobase uuids of the project's applications, or why they could not be asked. */
    private static final class ProjectApplications
    {
        /** Present when {@link #omission} is {@code null}. */
        final Set<String> uuids;

        /** Why the per-user store stays unread, or {@code null} when it may be read. */
        final String omission;

        private ProjectApplications(Set<String> uuids, String omission)
        {
            this.uuids = uuids;
            this.omission = omission;
        }

        static ProjectApplications read(Set<String> uuids)
        {
            return new ProjectApplications(uuids, null);
        }

        static ProjectApplications omitted(String omission)
        {
            return new ProjectApplications(Set.of(), omission);
        }
    }
}
