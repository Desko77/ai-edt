/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;

/**
 * Where every AI-EDT server on this machine says who it is and which port it answers on.
 * <p>
 * One EDT is the simple case and needs none of this. Several at once is the normal case here,
 * and it has a cost that only shows up as confusion: which of them holds the workspace you
 * mean, which port belongs to which configuration, and - after an agent has been told a port
 * number once - whether that port is still the same EDT. Finding out meant opening preference
 * files under each workspace in turn.
 * </p>
 * <p>
 * So each server drops a small record under {@code ~/.aiedt/instances/} while it runs, named
 * by its process id, and removes it on the way out. A record whose process is gone is ignored
 * on read and deleted, which is what makes a crash self-healing: nothing has to be cleaned up
 * by hand, and a stale port is never reported as live.
 * </p>
 * <p>
 * The registry is advisory. It never gates a call, and a machine where the directory cannot be
 * written keeps working exactly as before - it just answers "unknown" when asked who else is
 * running.
 * </p>
 */
public final class InstanceRegistry
{
    /** Directory under the user's home where instances announce themselves. */
    private static final String REGISTRY_DIR = ".aiedt/instances"; //$NON-NLS-1$

    /**
     * Overrides where the records go. For a machine whose home directory is read-only or
     * roams between hosts - and for tests, which must not leave a record among the real ones.
     */
    private static final String REGISTRY_DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    /** Suffix of one instance record. */
    private static final String RECORD_SUFFIX = ".json"; //$NON-NLS-1$

    /** The record this process owns, once registered. */
    private static volatile Path ownRecord;

    private InstanceRegistry()
    {
    }

    /**
     * Announces this server, replacing any record left under the same process id.
     * <p>
     * Failure is not propagated: a server that cannot write the record still serves, and
     * refusing to start over a directory permission would be a far worse trade than answering
     * "unknown" to the one question the registry exists for.
     * </p>
     *
     * @param port the port this server listens on.
     */
    public static void announce(int port)
    {
        try
        {
            Path dir = registryDirectory();
            Files.createDirectories(dir);
            Path record = dir.resolve(ProcessHandle.current().pid() + RECORD_SUFFIX);
            Files.write(record, describeSelf(port).toString().getBytes(StandardCharsets.UTF_8));
            ownRecord = record;
        }
        catch (IOException | RuntimeException e)
        {
            // Advisory by design - see the class comment. Logged once so a machine where this
            // never works can be told apart from one where nothing else is running.
            Activator.logWarning("Could not announce this instance: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Withdraws this server's record. Silent when there is none. */
    public static void withdraw()
    {
        Path record = ownRecord;
        ownRecord = null;
        if (record == null)
        {
            return;
        }
        try
        {
            Files.deleteIfExists(record);
        }
        catch (IOException | RuntimeException e)
        {
            // A record left behind by a failed delete is harmless: the next read sees the
            // process is gone and removes it.
            Activator.logWarning("Could not withdraw this instance: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Every server currently running on this machine, this one included.
     * <p>
     * Records whose process has ended are deleted rather than reported: a port that answers
     * nobody is worse than no answer, because it reads as a live server that is refusing.
     * </p>
     *
     * @return one entry per live instance, this one first, then by port.
     */
    public static List<JsonObject> live()
    {
        List<JsonObject> found = new ArrayList<>();
        Path dir = registryDirectory();
        if (!Files.isDirectory(dir))
        {
            return found;
        }
        try (DirectoryStream<Path> records = Files.newDirectoryStream(dir, "*" + RECORD_SUFFIX)) //$NON-NLS-1$
        {
            for (Path record : records)
            {
                JsonObject entry = readLiveRecord(record);
                if (entry != null)
                {
                    found.add(entry);
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logWarning("Could not read the instance registry: " + e.getMessage()); //$NON-NLS-1$
            return found;
        }
        long self = ProcessHandle.current().pid();
        found.sort(Comparator
            .comparing((JsonObject o) -> pidOf(o) == self ? 0 : 1)
            .thenComparingInt(o -> o.has("port") ? o.get("port").getAsInt() : 0)); //$NON-NLS-1$ //$NON-NLS-2$
        return found;
    }

    /**
     * A name for this instance a person can tell from the others at a glance - the workspace
     * it has open, which is the thing that actually differs between them.
     *
     * @return a label such as {@code AI-EDT @ my-workspace}.
     */
    public static String selfTitle()
    {
        String workspace = workspaceName();
        return workspace == null ? "AI-EDT" : "AI-EDT @ " + workspace; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads one record, keeping it only while its process is alive.
     *
     * @param record the file to read.
     * @return the record with liveness stamped on it, or null when the process has ended.
     */
    private static JsonObject readLiveRecord(Path record)
    {
        JsonObject entry;
        try
        {
            JsonElement parsed = JsonParser.parseString(
                new String(Files.readAllBytes(record), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject())
            {
                return null;
            }
            entry = parsed.getAsJsonObject();
        }
        catch (IOException | RuntimeException e)
        {
            // A half-written record from an instance starting right now. Not an error and not
            // reportable either - it will be readable a moment later.
            return null;
        }
        long pid = pidOf(entry);
        java.util.Optional<ProcessHandle> process =
            pid <= 0 ? java.util.Optional.empty() : ProcessHandle.of(pid);
        boolean sameProcess = process.map(ProcessHandle::isAlive).orElse(Boolean.FALSE)
            && startsWhenTheRecordSays(entry, process.get());
        if (!sameProcess)
        {
            try
            {
                Files.deleteIfExists(record);
            }
            catch (IOException | RuntimeException ignored)
            {
                // Left for the next reader; a dead record is never reported either way.
            }
            return null;
        }
        entry.addProperty("self", pid == ProcessHandle.current().pid()); //$NON-NLS-1$
        return entry;
    }

    /**
     * Whether the process now holding this pid is the one that wrote the record.
     * <p>
     * A record written before this check existed carries no start time; it is taken at its word
     * rather than discarded, since discarding it would drop a live instance.
     * </p>
     *
     * @param entry the record.
     * @param process the process holding that pid now.
     * @return true when they are the same process, or the record predates the check.
     */
    private static boolean startsWhenTheRecordSays(JsonObject entry, ProcessHandle process)
    {
        if (!entry.has("processStartedAt")) //$NON-NLS-1$
        {
            return true;
        }
        try
        {
            return startedAt(process)
                .map(now -> now.equals(entry.get("processStartedAt").getAsString())) //$NON-NLS-1$
                .orElse(Boolean.TRUE);
        }
        catch (RuntimeException e)
        {
            // A record whose start time is not a string is one we cannot compare. Kept, for the
            // same reason as one that has none: dropping a live instance is the worse mistake.
            return true;
        }
    }

    /**
     * When a process started, as a string that compares exactly.
     *
     * @param process the process to ask.
     * @return its start time, or empty when the platform will not say.
     */
    private static java.util.Optional<String> startedAt(ProcessHandle process)
    {
        try
        {
            return process.info().startInstant().map(java.time.Instant::toString);
        }
        catch (RuntimeException e)
        {
            // Some platforms refuse this for processes other than our own. Absent, not wrong.
            return java.util.Optional.empty();
        }
    }

    /**
     * What this server puts in its record.
     *
     * @param port the port it listens on.
     * @return the record body.
     */
    private static JsonObject describeSelf(int port)
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("pid", ProcessHandle.current().pid()); //$NON-NLS-1$
        // The pid alone does not identify a process for long: the operating system reuses it, and
        // after a crash the number can belong to something else before this ever runs again. The
        // start time tells them apart, so a stale record cannot pass itself off as live.
        startedAt(ProcessHandle.current()).ifPresent(
            started -> entry.addProperty("processStartedAt", started)); //$NON-NLS-1$
        entry.addProperty("port", port); //$NON-NLS-1$
        entry.addProperty("title", selfTitle()); //$NON-NLS-1$
        String workspace = workspacePath();
        if (workspace != null)
        {
            entry.addProperty("workspace", workspace); //$NON-NLS-1$
        }
        entry.addProperty("announcedAt", Instant.now().toString()); //$NON-NLS-1$
        List<String> projects = openProjects();
        if (!projects.isEmpty())
        {
            com.google.gson.JsonArray names = new com.google.gson.JsonArray();
            for (String name : projects)
            {
                names.add(name);
            }
            entry.add("projects", names); //$NON-NLS-1$
        }
        return entry;
    }

    /**
     * The process id a record belongs to.
     *
     * @param entry the record.
     * @return the pid, or 0 when the record does not name one.
     */
    private static long pidOf(JsonObject entry)
    {
        try
        {
            return entry.has("pid") ? entry.get("pid").getAsLong() : 0L; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            return 0L;
        }
    }

    /**
     * The projects this instance has open, for telling two workspaces apart by content.
     *
     * @return the open project names, empty when the workspace is not up yet.
     */
    private static List<String> openProjects()
    {
        List<String> names = new ArrayList<>();
        try
        {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (project.isOpen())
                {
                    names.add(project.getName());
                }
            }
        }
        catch (RuntimeException e)
        {
            // Registration can run before the workspace is usable; the record is still worth
            // writing without the project list.
            Activator.logDebug("Instance record written without projects: " + e.getMessage()); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * Absolute path of this instance's workspace.
     *
     * @return the path, or null when there is no workspace to ask.
     */
    private static String workspacePath()
    {
        try
        {
            org.eclipse.core.runtime.IPath location =
                ResourcesPlugin.getWorkspace().getRoot().getLocation();
            return location == null ? null : location.toOSString();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Last segment of the workspace path, which is what people call it.
     *
     * @return the name, or null when there is no workspace to ask.
     */
    private static String workspaceName()
    {
        String path = workspacePath();
        if (path == null || path.isEmpty())
        {
            return null;
        }
        Path asPath = Paths.get(path);
        Path name = asPath.getFileName();
        return name == null ? path : name.toString();
    }

    /**
     * Where the records live.
     *
     * @return the registry directory, whether or not it exists yet.
     */
    private static Path registryDirectory()
    {
        String override = System.getProperty(REGISTRY_DIR_PROPERTY);
        if (override != null && !override.trim().isEmpty())
        {
            return Paths.get(override.trim());
        }
        return Paths.get(System.getProperty("user.home", "."), REGISTRY_DIR.split("/")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
