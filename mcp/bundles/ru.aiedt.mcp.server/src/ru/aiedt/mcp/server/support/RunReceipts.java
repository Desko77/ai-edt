/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.concurrent.atomic.AtomicLong;
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
 * its answer. A failure while deleting old receipts is recorded and does not take back the
 * receipt just written;</li>
 * <li>two writes that land in the same instant take two names, and a name already on disk is
 * left where it is.</li>
 * </ul>
 * <p>
 * Secrets never reach the file. The callers already keep passwords and scenario text out of the
 * fields they hand over; on top of that, any key that reads as a secret is dropped here, at the
 * last point every receipt passes through, however deep it sits and whether it is inside a map,
 * a list or an array.
 * </p>
 */
public final class RunReceipts
{
    /** How many receipts of one tool are kept; older ones are deleted after each write. */
    public static final int KEEP = 20;

    /** Directory under the state location the per-tool receipt directories live in. */
    private static final String ROOT_DIR = "run-receipts"; //$NON-NLS-1$

    /**
     * Distinguishes two receipts written in the same millisecond. The clock alone does not: two
     * threads can read the same milli and the same nano, and the second move would then land on
     * the first file.
     */
    private static final AtomicLong NAME_SEQUENCE = new AtomicLong();

    private RunReceipts()
    {
    }

    /**
     * Where one tool's receipts are written. A caller substitutes a lookup that throws when the
     * plugin state location cannot be asked, which is what keeps that failure inside the write.
     */
    @FunctionalInterface
    public interface DirectoryLookup
    {
        /**
         * Resolves the directory for one tool.
         *
         * @param tool the tool name the directory is named after
         * @return the directory, or <code>null</code> when there is nowhere to write
         * @throws Exception when the location cannot be resolved
         */
        Path directory(String tool) throws Exception;
    }

    /**
     * Deletes one receipt file. A test substitutes a delete that refuses the oldest file, which is
     * what shows the next one being tried.
     */
    @FunctionalInterface
    interface ReceiptDeleter
    {
        /**
         * Deletes one receipt.
         *
         * @param path the file
         * @throws IOException when this file will not go
         */
        void delete(Path path) throws IOException;
    }

    /**
     * The cleanup that follows a successful move. A test substitutes one that throws, which is
     * what shows a prune failure leaving the receipt in place.
     */
    @FunctionalInterface
    interface Cleanup
    {
        /**
         * Deletes receipts past the keep limit.
         *
         * @throws IOException when the cleanup cannot finish
         */
        void run() throws IOException;
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

        /**
         * Records which of the two a write came to. Exactly one argument is set.
         *
         * @param path the receipt file, or <code>null</code> when nothing was written
         * @param error why nothing was written, or <code>null</code> on success
         */
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
     * keep a record of a run must not fail the run. Resolving the directory is part of the write,
     * so a state location that cannot be asked becomes {@code receiptError} rather than an
     * exception in place of the run's answer.
     *
     * @param fields the receipt fields; {@code tool} selects the directory
     * @return the outcome - the file, or the reason there is none
     */
    public static Outcome write(Map<String, Object> fields)
    {
        return writeResolving(fields, RunReceipts::directoryFor);
    }

    /**
     * Writes one receipt, asking {@code directories} where it goes. The lookup runs inside the
     * same protection as the write: anything it throws, including {@link IllegalStateException}
     * from the plugin state location, comes back as the outcome's error and is not thrown.
     *
     * @param fields the receipt fields; {@code tool} selects the directory
     * @param directories where that tool's receipts go; a test supplies one that throws
     * @return the outcome - the file, or the reason there is none
     */
    public static Outcome writeResolving(Map<String, Object> fields, DirectoryLookup directories)
    {
        try
        {
            if (fields == null)
            {
                return new Outcome(null,
                    "There is no plugin state location to write the run receipt into."); //$NON-NLS-1$
            }
            Object tool = fields.get("tool"); //$NON-NLS-1$
            Path directory = null;
            if (tool != null && directories != null)
            {
                directory = directories.directory(String.valueOf(tool));
            }
            if (directory == null)
            {
                return new Outcome(null,
                    "There is no plugin state location to write the run receipt into."); //$NON-NLS-1$
            }
            return writeTo(directory, fields);
        }
        catch (Exception e)
        {
            return new Outcome(null, "The run receipt could not be written: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Writes one receipt into a named directory, which is what makes the naming, the pruning and
     * the failure handling checkable without a plugin state location to write into.
     *
     * @param directory where the receipt goes; created when missing
     * @param fields the receipt fields
     * @return the outcome - the file, or the reason there is none
     */
    public static Outcome writeTo(Path directory, Map<String, Object> fields)
    {
        return writeTo(directory, fields, () -> prune(directory));
    }

    /**
     * Writes one receipt and then runs {@code cleanup}. A cleanup that throws leaves the receipt
     * where the move put it and reports nothing in the outcome: the file is already the record.
     *
     * @param directory where the receipt goes; created when missing
     * @param fields the receipt fields
     * @param cleanup deletes receipts past {@link #KEEP}; a test supplies one that throws
     * @return the outcome - the file, or the reason there is none
     */
    static Outcome writeTo(Path directory, Map<String, Object> fields, Cleanup cleanup)
    {
        Path temp = null;
        try
        {
            Files.createDirectories(directory);
            String json = GsonHolder.toJson(documentOf(fields));
            temp = Files.createTempFile(directory, "receipt-", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            Path target = moveIntoPlace(temp, directory);
            temp = null;
            try
            {
                cleanup.run();
            }
            catch (Exception pruneFailed)
            {
                Activator.logWarning("Could not prune old run receipts in " + directory //$NON-NLS-1$
                    + ": " + pruneFailed.getMessage()); //$NON-NLS-1$
            }
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
     * key that reads as a secret left out, at every depth, including a map inside a list or an
     * array. A scenario text or a password has no place in a file that outlives the answer,
     * whatever the caller handed over.
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
            document.put(key, withoutSecrets(entry.getValue()));
        }
        return document;
    }

    /**
     * The value with every secret key removed. Maps are walked at any depth; a list or an array
     * is walked element by element, so a map sitting inside one is cleaned the same way.
     *
     * @param value a field value, which may itself contain maps, lists or arrays
     * @return the value with secret-keyed entries left out
     */
    private static Object withoutSecrets(Object value)
    {
        if (value instanceof Map)
        {
            Map<String, Object> inner = new LinkedHashMap<>();
            for (Map.Entry<?, ?> nested : ((Map<?, ?>)value).entrySet())
            {
                String nestedKey = String.valueOf(nested.getKey());
                if (!isSecretKey(nestedKey))
                {
                    inner.put(nestedKey, withoutSecrets(nested.getValue()));
                }
            }
            return inner;
        }
        if (value instanceof List)
        {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>)value)
            {
                copy.add(withoutSecrets(item));
            }
            return copy;
        }
        if (value instanceof Object[])
        {
            Object[] array = (Object[])value;
            List<Object> copy = new ArrayList<>(array.length);
            for (Object item : array)
            {
                copy.add(withoutSecrets(item));
            }
            return copy;
        }
        return value;
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
     * Gives a written receipt its final name, atomically where the file system allows. The name
     * carries the time and a counter, and a name that is already taken is not replaced: the move
     * is tried again under the next name, so two writers cannot land on one file.
     *
     * @param temp the temporary file the receipt was written to
     * @param directory the tool's receipt directory
     * @return the path the receipt was moved to
     * @throws IOException when the move fails for a reason other than the name being taken
     */
    private static Path moveIntoPlace(Path temp, Path directory) throws IOException
    {
        while (true)
        {
            Path target = directory.resolve(nextName());
            try
            {
                moveWithoutReplacing(temp, target);
                return target;
            }
            catch (FileAlreadyExistsException occupied)
            {
                // Another write took this name between the choice and the move. The next name is
                // free, and the receipt already written stays in the temporary file until it lands.
            }
        }
    }

    /**
     * The next receipt name. Zero-padded, so the name sorts the way the runs happened: first by
     * the millisecond, then by the nano clock, then by the counter that separates two writes the
     * clocks could not.
     *
     * @return the file name, ending in {@code .json}
     */
    private static String nextName()
    {
        return String.format(Locale.ROOT, "%013d-%016x-%08x.json", //$NON-NLS-1$
            Long.valueOf(System.currentTimeMillis()), Long.valueOf(System.nanoTime()),
            Long.valueOf(NAME_SEQUENCE.incrementAndGet()));
    }

    /**
     * Moves a receipt onto its final name without replacing a file that is already there.
     *
     * @param temp the temporary file
     * @param target the name the receipt is read under
     * @throws IOException when the move fails, including when {@code target} already exists
     */
    private static void moveWithoutReplacing(Path temp, Path target) throws IOException
    {
        try
        {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException notAtomic)
        {
            Files.move(temp, target);
        }
    }

    /**
     * Deletes the oldest receipts until at most {@link #KEEP} remain. Names are zero-padded, so
     * the name order is the order the runs happened. A file that cannot be deleted is skipped and
     * the next oldest is tried; the count is taken from the directory after each deletion, so a
     * file the local list has forgotten but the disk still holds is still counted.
     *
     * @param directory the tool's receipt directory
     * @throws IOException when the directory cannot be listed
     */
    private static void prune(Path directory) throws IOException
    {
        prune(directory, Files::deleteIfExists);
    }

    /**
     * Deletes the oldest receipts until the directory itself holds at most {@link #KEEP}, using
     * {@code deleter} for each file. A delete that throws or leaves the file in place is skipped
     * and the next oldest is tried. The loop stops when a fresh listing shows the limit, or when
     * a pass deletes nothing.
     *
     * @param directory the tool's receipt directory
     * @param deleter deletes one file; a test supplies one that refuses the oldest
     * @throws IOException when the directory cannot be listed
     */
    static void prune(Path directory, ReceiptDeleter deleter) throws IOException
    {
        while (true)
        {
            List<Path> receipts = listReceipts(directory);
            if (receipts.size() <= KEEP)
            {
                return;
            }
            boolean deleted = false;
            for (Path candidate : receipts)
            {
                try
                {
                    deleter.delete(candidate);
                }
                catch (IOException ignored)
                {
                    // This file stays. The next oldest is the one that has to go, or the directory
                    // would keep more than KEEP because one deletion failed.
                    continue;
                }
                if (!Files.exists(candidate))
                {
                    deleted = true;
                    break;
                }
            }
            if (!deleted)
            {
                return;
            }
        }
    }

    /**
     * The receipt files in a directory, oldest name first.
     *
     * @param directory the tool's receipt directory
     * @return the {@code .json} files, sorted by name
     * @throws IOException when the directory cannot be listed
     */
    private static List<Path> listReceipts(Path directory) throws IOException
    {
        List<Path> receipts = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory))
        {
            entries.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(receipts::add); //$NON-NLS-1$
        }
        receipts.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return receipts;
    }
}
