/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.HistorySettings;

/**
 * Keeps the full text of a call beside the buffer that keeps a shortened one.
 *
 * <p>The in-memory buffer lives in the heap the IDE itself runs on, so it holds a few hundred
 * characters of each call and says how much it cut. That is the right size for a buffer and the
 * wrong size for reading what a tool actually answered, which is what the history window is opened
 * for. The full text goes here instead: one line per call, on disk, addressed by the id the record
 * carries.
 *
 * <p><b>What is not written.</b> Secrets are masked before anything reaches the history at all, and
 * personal data is masked the same way the journal masks it. An answer that is inline binary data -
 * a screenshot returned as base64 - is not stored: masking cannot read it, nobody reads it as text,
 * and it would be the largest thing on the disk. Its length is recorded and its content is not.
 *
 * <p><b>Clearing and writing at the same time.</b> A lock alone is not enough: a write that was
 * waiting for the lock would recreate the file the clear had just removed. Each clear raises a
 * generation, and a write that began in an older generation is dropped.
 */
public final class HistoryFullText
{
    /** Name of the store inside its directory. */
    public static final String FILE_NAME = "call-history-full.jsonl"; //$NON-NLS-1$

    /** Name of the previous store, kept by the single rotation. */
    public static final String ROTATED_NAME = FILE_NAME + ".1"; //$NON-NLS-1$

    /** Characters kept per field. A field longer than this is stored cut, and says so. */
    static final int MAX_FIELD_CHARS = 1_000_000;

    /** Bytes at which the store rotates. */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private static final Object LOCK = new Object();

    private static final AtomicLong GENERATION = new AtomicLong();

    private static final Gson GSON = new Gson();

    private HistoryFullText()
    {
    }

    /** @return the generation a write should carry, read before it starts */
    public static long generation()
    {
        return GENERATION.get();
    }

    /**
     * Where the store is written.
     *
     * @param settings the history settings in force.
     * @return the file, or <code>null</code> when there is nowhere to write
     */
    public static Path path(HistorySettings settings)
    {
        Path directory = directory(settings);
        return directory == null ? null : directory.resolve(FILE_NAME);
    }

    /**
     * The directory the store and the journal share.
     * <p>
     * The configured path when it is usable, and the plugin's own state location otherwise. A path
     * that cannot be created or written to is NOT a reason to lose the call: the store falls back
     * and {@link #pathProblem()} says what was wrong with it.
     * </p>
     *
     * @param settings the history settings in force.
     * @return the directory, or <code>null</code> when even the state location is unavailable
     */
    public static Path directory(HistorySettings settings)
    {
        String configured = settings == null ? null : settings.diskPath();
        if (configured != null && !configured.trim().isEmpty())
        {
            Path asked = Paths.get(configured.trim());
            if (asked.isAbsolute())
            {
                try
                {
                    Files.createDirectories(asked);
                    if (Files.isWritable(asked))
                    {
                        pathProblem = null;
                        return asked;
                    }
                    pathProblem = "the configured history directory is not writable: " + asked; //$NON-NLS-1$
                }
                catch (IOException e)
                {
                    pathProblem = "the configured history directory could not be created: " + asked //$NON-NLS-1$
                        + " (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                pathProblem = "the configured history directory must be an absolute path: " + configured; //$NON-NLS-1$
            }
        }
        else
        {
            pathProblem = null;
        }
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getStateLocation() == null)
        {
            return null;
        }
        return activator.getStateLocation().toFile().toPath();
    }

    private static volatile String pathProblem;

    /**
     * @return what is wrong with the configured directory, or <code>null</code> when nothing is
     */
    public static String pathProblem()
    {
        return pathProblem;
    }

    /**
     * Writes the full text of one call.
     * <p>
     * Never throws: failing to keep a copy of a call must not fail the call.
     * </p>
     *
     * @param entryId the id the buffer record carries.
     * @param toolName the tool that ran.
     * @param fullArgs its arguments, masked and NOT shortened; may be <code>null</code>.
     * @param fullResult what it answered, NOT shortened; may be <code>null</code>.
     * @param binaryResult whether the answer is inline binary data, which is not stored.
     * @param settings the history settings in force.
     * @param bornInGeneration the generation read before the call started.
     */
    public static void write(String entryId, String toolName, String fullArgs, String fullResult,
        boolean binaryResult, HistorySettings settings, long bornInGeneration)
    {
        if (entryId == null || settings == null || !settings.isDiskEnabled())
        {
            return;
        }
        try
        {
            Path file = path(settings);
            if (file == null)
            {
                return;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entryId", entryId); //$NON-NLS-1$
            entry.put("tool", toolName); //$NON-NLS-1$
            entry.put("at", Long.valueOf(System.currentTimeMillis())); //$NON-NLS-1$
            putField(entry, "args", fullArgs); //$NON-NLS-1$
            if (binaryResult)
            {
                entry.put("result", null); //$NON-NLS-1$
                entry.put("resultBinary", Boolean.TRUE); //$NON-NLS-1$
                entry.put("resultChars", Integer.valueOf(fullResult == null ? 0 : fullResult.length())); //$NON-NLS-1$
            }
            else
            {
                putField(entry, "result", fullResult); //$NON-NLS-1$
            }
            String line = GSON.toJson(entry) + "\n"; //$NON-NLS-1$
            synchronized (LOCK)
            {
                if (bornInGeneration != GENERATION.get())
                {
                    // The history was cleared while this call was running: its text belongs to the
                    // store that no longer exists, and recreating the file would undo the clear.
                    return;
                }
                rotateIfLarge(file);
                Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            }
        }
        catch (Exception e)
        {
            Activator.logDebug("full-text history not written: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void putField(Map<String, Object> entry, String name, String value)
    {
        if (value == null)
        {
            entry.put(name, null);
            return;
        }
        if (value.length() > MAX_FIELD_CHARS)
        {
            entry.put(name, value.substring(0, MAX_FIELD_CHARS));
            entry.put(name + "Chars", Integer.valueOf(value.length())); //$NON-NLS-1$
            entry.put(name + "StoredChars", Integer.valueOf(MAX_FIELD_CHARS)); //$NON-NLS-1$
            return;
        }
        entry.put(name, value);
        entry.put(name + "Chars", Integer.valueOf(value.length())); //$NON-NLS-1$
        entry.put(name + "StoredChars", Integer.valueOf(value.length())); //$NON-NLS-1$
    }

    private static void rotateIfLarge(Path file) throws IOException
    {
        if (Files.exists(file) && Files.size(file) >= MAX_BYTES)
        {
            Files.move(file, file.resolveSibling(ROTATED_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads back the full text of one call.
     *
     * @param entryId the id to look for; may be <code>null</code>.
     * @param settings the history settings in force.
     * @return the stored fields, or <code>null</code> when the store no longer carries that call
     */
    public static Map<String, Object> read(String entryId, HistorySettings settings)
    {
        if (entryId == null || entryId.isEmpty() || settings == null)
        {
            return null;
        }
        Path file = path(settings);
        if (file == null)
        {
            return null;
        }
        long keepMs = settings.diskDays() <= 0 ? 0L : settings.diskDays() * 24L * 60 * 60 * 1000;
        long oldestAllowed = keepMs == 0L ? 0L : System.currentTimeMillis() - keepMs;
        for (Path candidate : new Path[] {file, file.resolveSibling(ROTATED_NAME)})
        {
            Map<String, Object> found = findIn(candidate, entryId, oldestAllowed);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private static Map<String, Object> findIn(Path file, String entryId, long oldestAllowed)
    {
        if (!Files.exists(file))
        {
            return null;
        }
        try
        {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                if (line.isEmpty() || !line.contains(entryId))
                {
                    continue;
                }
                JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                if (!json.has("entryId") || !entryId.equals(json.get("entryId").getAsString())) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    continue;
                }
                if (oldestAllowed > 0 && json.has("at") //$NON-NLS-1$
                    && json.get("at").getAsLong() < oldestAllowed) //$NON-NLS-1$
                {
                    // Older than the caller chose to keep: gone as far as anyone reading is
                    // concerned, whether or not the file has been swept yet.
                    return null;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                for (String key : json.keySet())
                {
                    entry.put(key, json.get(key).isJsonNull() ? null : json.get(key).getAsString());
                }
                return entry;
            }
        }
        catch (Exception e)
        {
            Activator.logDebug("full-text history not read: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Drops everything the store holds, so that clearing the history clears it here too.
     *
     * @param settings the history settings in force.
     */
    public static void clear(HistorySettings settings)
    {
        synchronized (LOCK)
        {
            GENERATION.incrementAndGet();
            Path file = path(settings);
            if (file == null)
            {
                return;
            }
            for (Path candidate : new Path[] {file, file.resolveSibling(ROTATED_NAME)})
            {
                try
                {
                    Files.deleteIfExists(candidate);
                }
                catch (IOException e)
                {
                    Activator.logDebug("full-text history not cleared: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }
    }

    /**
     * Removes entries older than the configured number of days.
     * <p>
     * Runs when the server starts and when the store rotates, not on every write: the age of a
     * record changes once a day, and reading the file to answer that on every call would cost more
     * than it saves.
     * </p>
     *
     * @param settings the history settings in force.
     * @return how many entries were dropped
     */
    public static int sweepOld(HistorySettings settings)
    {
        if (settings == null || settings.diskDays() <= 0)
        {
            return 0;
        }
        long oldestAllowed = System.currentTimeMillis() - settings.diskDays() * 24L * 60 * 60 * 1000;
        int dropped = 0;
        synchronized (LOCK)
        {
            Path file = path(settings);
            if (file == null || !Files.exists(file))
            {
                return 0;
            }
            try
            {
                List<String> kept = new ArrayList<>();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                {
                    if (line.isEmpty())
                    {
                        continue;
                    }
                    if (isOlderThan(line, oldestAllowed))
                    {
                        dropped++;
                        continue;
                    }
                    kept.add(line);
                }
                if (dropped > 0)
                {
                    Path temporary = file.resolveSibling(FILE_NAME + ".tmp"); //$NON-NLS-1$
                    Files.write(temporary, kept, StandardCharsets.UTF_8);
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch (Exception e)
            {
                Activator.logDebug("full-text history not swept: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return dropped;
    }

    /**
     * Whether a stored line is older than a moment.
     *
     * @param line one line of the store.
     * @param oldestAllowed the earliest moment worth keeping, in milliseconds.
     * @return whether the line is older than that
     */
    static boolean isOlderThan(String line, long oldestAllowed)
    {
        try
        {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            return json.has("at") && json.get("at").getAsLong() < oldestAllowed; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            // A line that cannot be read carries no date either; keeping it is the safer answer.
            return false;
        }
    }

    /**
     * A fresh id for a history record.
     *
     * @return sixteen hexadecimal characters
     */
    public static String newEntryId()
    {
        return Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
            + Long.toHexString(System.nanoTime() & 0xFFFFL);
    }
}
