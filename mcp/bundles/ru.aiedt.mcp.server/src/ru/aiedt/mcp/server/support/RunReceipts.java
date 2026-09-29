/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * The on-disk receipt of a finished test run: one JSON file per run, in the plugin's state
 * location, under {@code run-receipts/<tool>/}.
 * <p>
 * The outcome of a run otherwise lives only in the answer of the call that waited for it, and a
 * context compression discards that answer. The receipt is the copy that survives: the counters,
 * the filters that chose the run, and the path of the report the numbers were read from.
 * </p>
 * <p>
 * Three rules hold the class together:
 * </p>
 * <ul>
 * <li>a receipt reaches its final name by a temporary file and a move, so a file cut short by a
 * crash is never read as a receipt;</li>
 * <li>after a successful write at most {@link #KEEP} files remain for the tool, the oldest by name
 * leaving first - the directory is a record of recent runs, not an archive;</li>
 * <li>a write that fails fails nothing else: the caller gets the error as text and the run keeps
 * its answer.</li>
 * </ul>
 * <p>
 * Secrets never reach the file. The callers already keep passwords and scenario text out of the
 * fields they hand over; on top of that, any key that reads as a secret is dropped here, at the
 * last point every receipt passes through.
 * </p>
 */
public final class RunReceipts
{
    /** How many receipts of one tool are kept; older ones are deleted after each write. */
    public static final int KEEP = 20;

    /** Directory under the state location the per-tool receipt directories live in. */
    private static final String ROOT_DIR = "run-receipts"; //$NON-NLS-1$

    private RunReceipts()
    {
    }

    /**
     * What a write attempt came to. Exactly one of the two members is set: {@link #path} when the
     * receipt is on disk, {@link #error} when it is not.
     */
    public static final class Outcome
    {
        /** The receipt file, or <code>null</code> when nothing was written. */
        public final Path path;

        /** Why nothing was written, or <code>null</code> on success. */
        public final String error;

        private Outcome(Path path, String error)
        {
            this.path = path;
            this.error = error;
        }
    }

    /**
     * Where the receipts of one tool are written.
     *
     * @param tool the tool name the directory is named after
     * @return the directory, or <code>null</code> when there is no plugin state location to write
     *         into (during shutdown, or in a runtime with no bundle)
     */
    public static Path directoryFor(String tool)
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IPath state = activator.getStateLocation();
        return state == null ? null : state.append(ROOT_DIR).append(tool).toFile().toPath();
    }

    /**
     * Writes one receipt into the directory of the tool the fields name. Never throws: failing to
     * keep a record of a run must not fail the run.
     *
     * @param fields the receipt fields; {@code tool} selects the directory
     * @return the outcome - the file, or the reason there is none
     */
    public static Outcome write(Map<String, Object> fields)
    {
        Object tool = fields == null ? null : fields.get("tool"); //$NON-NLS-1$
        Path directory = tool == null ? null : directoryFor(String.valueOf(tool));
        if (directory == null)
        {
            return new Outcome(null,
                "There is no plugin state location to write the run receipt into."); //$NON-NLS-1$
        }
        return writeTo(directory, fields);
    }

    /**
     * Writes one receipt into a named directory, which is what makes the naming, the pruning and
     * the failure handling checkable without a plugin state location to write into.
     *
     * @param directory where the receipt goes; created when missing
     * @param fields the receipt fields
     * @return the outcome - the file, or the reason there is none
     */
    static Outcome writeTo(Path directory, Map<String, Object> fields)
    {
        Path temp = null;
        try
        {
            Files.createDirectories(directory);
            // Zero-padded, so the name sorts the way the runs happened: first by the millisecond,
            // then - inside one millisecond - by the clock that orders the writes themselves.
            String name = String.format(Locale.ROOT, "%013d-%016x.json", //$NON-NLS-1$
                Long.valueOf(System.currentTimeMillis()), Long.valueOf(System.nanoTime()));
            Path target = directory.resolve(name);
            temp = directory.resolve(name + ".tmp"); //$NON-NLS-1$
            String json = GsonHolder.toJson(documentOf(fields));
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            moveIntoPlace(temp, target);
            temp = null;
            prune(directory);
            return new Outcome(target, null);
        }
        catch (Exception e)
        {
            if (temp != null)
            {
                try
                {
                    Files.deleteIfExists(temp);
                }
                catch (IOException ignored)
                {
                    // The write already failed; the temporary file is for the operating system
                    // to reclaim, and a second failure here adds nothing to the first.
                }
            }
            return new Outcome(null, "The run receipt could not be written: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * The document a receipt carries: the fields as given, with {@code at} stamped in and every
     * key that reads as a secret left out - at the top level and one level down, where the
     * filters of the call sit. A scenario text or a password has no place in a file that outlives
     * the answer, whatever the caller handed over.
     *
     * @param fields the fields the caller filed
     * @return the document to serialize
     */
    private static Map<String, Object> documentOf(Map<String, Object> fields)
    {
        Map<String, Object> document = new LinkedHashMap<>();
        Object tool = fields.get("tool"); //$NON-NLS-1$
        if (tool != null)
        {
            document.put("tool", tool); //$NON-NLS-1$
        }
        document.put("at", Instant.now().toString()); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : fields.entrySet())
        {
            String key = entry.getKey();
            if ("tool".equals(key) || isSecretKey(key)) //$NON-NLS-1$
            {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map)
            {
                Map<String, Object> inner = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nested : ((Map<?, ?>)value).entrySet())
                {
                    String nestedKey = String.valueOf(nested.getKey());
                    if (!isSecretKey(nestedKey))
                    {
                        inner.put(nestedKey, nested.getValue());
                    }
                }
                value = inner;
            }
            document.put(key, value);
        }
        return document;
    }

    /**
     * Whether a key reads as one a receipt must not carry.
     *
     * @param key the field name
     * @return <code>true</code> for password-like keys and the full scenario text
     */
    private static boolean isSecretKey(String key)
    {
        String folded = key.toLowerCase(Locale.ROOT);
        return folded.contains("password") || folded.contains("pwd") //$NON-NLS-1$ //$NON-NLS-2$
            || folded.equals("scenariotext"); //$NON-NLS-1$
    }

    /**
     * Gives a written receipt its final name, atomically where the file system allows.
     *
     * @param temp the temporary file the receipt was written to
     * @param target the name the receipt is read under
     * @throws IOException when the move fails
     */
    private static void moveIntoPlace(Path temp, Path target) throws IOException
    {
        try
        {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException notAtomic)
        {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes the oldest receipts until at most {@link #KEEP} remain. Names are zero-padded, so
     * the name order is the order the runs happened. A file that cannot be deleted is skipped:
     * the next write tries again, and one stubborn file is no reason to lose the receipt just
     * written.
     *
     * @param directory the tool's receipt directory
     * @throws IOException when the directory cannot be listed
     */
    private static void prune(Path directory) throws IOException
    {
        List<Path> receipts = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory))
        {
            entries.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(receipts::add); //$NON-NLS-1$
        }
        receipts.sort(Comparator.comparing(p -> p.getFileName().toString()));
        while (receipts.size() > KEEP)
        {
            Path oldest = receipts.remove(0);
            try
            {
                Files.deleteIfExists(oldest);
            }
            catch (IOException ignored)
            {
                // Left for the next write; see the javadoc.
            }
        }
    }
}
