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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * The optional on-disk copy of the call history: one JSON object per line, in the plugin's state
 * location.
 * <p>
 * The in-memory buffer answers "what has the agent been doing", and loses everything when EDT
 * closes. This answers "what did it do yesterday", which is the question asked after something has
 * already gone wrong. Off unless switched on, so an installation that never asks for it writes
 * nothing.
 * </p>
 * <p>
 * Bounded by size with a single rotation: at the cap the current file becomes {@code .1} and a new
 * one starts, so the journal holds between one and two caps' worth and can never fill the disk.
 * Rotating rather than truncating matters here - a truncation at the cap would throw away precisely
 * the older calls the file exists to keep.
 * </p>
 * <p>
 * Credentials never reach this class: argument values under a password- or token-like key are
 * already replaced with {@code ***} where the arguments are flattened, before anything is recorded.
 * What masking here adds is personal data (see {@link SensitiveTextMasker}) inside the arguments and
 * the response, which is the part that varies with the configuration being worked on.
 * </p>
 */
public final class HistoryJournal
{
    /** Name of the journal file inside the plugin state location. */
    public static final String FILE_NAME = "call-history.jsonl"; //$NON-NLS-1$

    /** Name of the previous journal, kept by the single rotation. */
    public static final String ROTATED_NAME = FILE_NAME + ".1"; //$NON-NLS-1$

    static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final Object LOCK = new Object();

    private HistoryJournal()
    {
    }

    /**
     * Where the journal is written.
     *
     * @return the file path, or <code>null</code> when there is no plugin state location to write
     *         into (during shutdown, or in a runtime with no bundle)
     */
    public static Path path()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IPath state = activator.getStateLocation();
        return state == null ? null : state.append(FILE_NAME).toFile().toPath();
    }

    /**
     * Appends one call to the journal. Never throws: failing to write a record of a call must not
     * fail the call.
     *
     * @param entry the call, as the history buffer describes it
     * @param redact whether personal data is masked on the way out
     */
    public static void append(Map<String, Object> entry, boolean redact)
    {
        appendTo(path(), entry, redact);
    }

    /**
     * Appends one call to a named journal, which is what makes the format and the rotation checkable
     * without a plugin state location to write into.
     *
     * @param file where to write, or <code>null</code> to do nothing
     * @param entry the call, as the history buffer describes it
     * @param redact whether personal data is masked on the way out
     */
    static void appendTo(Path file, Map<String, Object> entry, boolean redact)
    {
        appendTo(file, entry, redact, MAX_BYTES);
    }

    /**
     * As {@link #appendTo(Path, Map, boolean)}, with the rotation cap given rather than fixed, so a
     * test can reach the rotation without writing the eight megabytes the shipped cap asks for.
     *
     * @param file where to write, or <code>null</code> to do nothing
     * @param entry the call, as the history buffer describes it
     * @param redact whether personal data is masked on the way out
     * @param cap the size at which the journal is moved aside, in bytes
     */
    static void appendTo(Path file, Map<String, Object> entry, boolean redact, long cap)
    {
        if (file == null || entry == null)
        {
            return;
        }
        String line;
        try
        {
            line = GsonHolder.toJson(redact ? masked(entry) : entry) + System.lineSeparator();
        }
        catch (RuntimeException e)
        {
            return;
        }
        synchronized (LOCK)
        {
            try
            {
                rotateIfLarge(file, cap);
                Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            }
            catch (IOException | RuntimeException e)
            {
                // A journal that cannot be written is a lost diagnostic, not a failed tool call.
            }
        }
    }

    /**
     * Moves the journal aside once it reaches the cap, replacing whatever was set aside before.
     *
     * @param file the current journal
     * @param cap the size at which to rotate, in bytes
     * @throws IOException when the file cannot be read or moved
     */
    private static void rotateIfLarge(Path file, long cap) throws IOException
    {
        if (!Files.exists(file) || Files.size(file) < cap)
        {
            return;
        }
        Files.move(file, file.resolveSibling(ROTATED_NAME), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Copies the entry with personal data masked out of the two free-text fields.
     * <p>
     * Only those two: the rest are a tool name, a timestamp, a duration and two flags, which carry
     * nothing to mask and would only be at risk of being mangled by a pattern that matched a number.
     * </p>
     *
     * @param entry the call
     * @return a copy safe to write down
     */
    private static Map<String, Object> masked(Map<String, Object> entry)
    {
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        maskField(copy, "args"); //$NON-NLS-1$
        maskField(copy, "result"); //$NON-NLS-1$
        return copy;
    }

    private static void maskField(Map<String, Object> entry, String key)
    {
        Object value = entry.get(key);
        if (value instanceof String)
        {
            entry.put(key, SensitiveTextMasker.redact((String)value));
        }
    }
}
