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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.server.Activator;

/**
 * Reads the event log of a file infobase.
 * <p>
 * The log is the record of what actually happened in a base - who logged in, what was posted, what
 * the platform refused - and there is no way to it through the EDT model: the model describes the
 * configuration, not the events. It is, however, sitting on disk beside the {@code .1CD} in a
 * documented shape, and reading it there needs nothing from the platform.
 * </p>
 * <p>
 * Two files make it up. {@code 1Cv8.lgf} holds the dictionaries - users, computers, applications,
 * event names, metadata - each entry ending in the number the records refer to it by. The
 * {@code *.lgp} files hold the records, one per event, referring to those dictionaries by number
 * and carrying the free text inline. Both are read by {@link EventLogFormat}.
 * </p>
 * <p>
 * A newer platform can keep the same log in a single SQLite {@code .lgd} instead. That is a
 * different format needing a different reader, and this one says so rather than reporting an empty
 * log - an empty answer over a base full of events is the worst of both worlds.
 * </p>
 * <p>
 * Every free-text field passes through the sensitive-data masker on the way out, because an event
 * log carries logins, contract numbers and whatever a developer put in a message. What that
 * catches is what its pattern library defines and no more: a value it has no pattern for - a user
 * name, a machine name - comes through as written. Saying "masked" without that qualification
 * would be the kind of assurance this project keeps removing, so the caller is told the same thing
 * in the answer.
 * </p>
 */
public final class EventLogReader
{
    /** Directory the platform keeps a file infobase's log in. */
    public static final String LOG_DIRECTORY = "1Cv8Log"; //$NON-NLS-1$

    /** The dictionary file that the records refer into. */
    private static final String DICTIONARY = "1Cv8.lgf"; //$NON-NLS-1$

    /** The single-file SQLite form of the same log, which this reader does not read. */
    private static final String SQLITE_LOG = "1Cv8.lgd"; //$NON-NLS-1$

    /** Record files, named after the moment they start at. */
    private static final String RECORD_SUFFIX = ".lgp"; //$NON-NLS-1$

    /** The only layout this reader knows how to take apart. */
    private static final String SUPPORTED_VERSION = "2.0"; //$NON-NLS-1$

    /** Dictionary kinds, by the number each entry opens with. */
    private static final int KIND_USER = 1;

    private static final int KIND_COMPUTER = 2;

    private static final int KIND_APPLICATION = 3;

    private static final int KIND_EVENT = 4;

    private static final int KIND_METADATA = 11;

    /** Field positions inside one record, confirmed against a real log. */
    private static final int F_MOMENT = 0;

    private static final int F_TRANSACTION_STATUS = 1;

    private static final int F_USER = 3;

    private static final int F_COMPUTER = 4;

    private static final int F_APPLICATION = 5;

    private static final int F_CONNECTION = 6;

    private static final int F_EVENT = 7;

    private static final int F_SEVERITY = 8;

    private static final int F_COMMENT = 9;

    private static final int F_METADATA = 10;

    private static final int F_PRESENTATION = 12;

    private static final int F_SESSION = 16;

    /** How many fields a whole record has. A shorter one is not one. */
    private static final int RECORD_FIELDS = 17;

    private EventLogReader()
    {
    }

    /** What to read and what to leave out. */
    public static final class Query
    {
        /** Earliest moment to report, as {@code yyyyMMddHHmmss}. Empty means from the start. */
        public String from = ""; //$NON-NLS-1$

        /** Latest moment to report, as {@code yyyyMMddHHmmss}. Empty means to the end. */
        public String to = ""; //$NON-NLS-1$

        /** Keep only events whose name contains this, case-insensitively. */
        public String event = ""; //$NON-NLS-1$

        /** Keep only records of this user. */
        public String user = ""; //$NON-NLS-1$

        /** Keep only this severity: I, W, E or N. */
        public String severity = ""; //$NON-NLS-1$

        /** Most rows to return. */
        public int limit = 100;
    }

    /** What a read found, or why it found nothing. */
    public static final class Result
    {
        /** True when the log was read, even if the filters left no rows. */
        public boolean ok;

        /** The rows, newest last, as the log itself is ordered. */
        public final List<Map<String, Object>> rows = new ArrayList<>();

        /** Why nothing could be read. Null when {@link #ok}. */
        public String error;

        /** Set when the log exists in a shape this reader does not take apart. */
        public String unsupported;

        /** How many records were looked at before the limit cut the answer short. */
        public int scanned;

        /** True when the limit stopped the answer before the records ran out. */
        public boolean truncated;

        /** The record files consulted, in the order read. */
        public final List<String> files = new ArrayList<>();
    }

    /**
     * Reads the log of the infobase in a directory.
     *
     * @param infobaseDirectory the directory holding the {@code .1CD}.
     * @param query what to keep.
     * @return the rows, or the reason there are none.
     */
    public static Result read(Path infobaseDirectory, Query query)
    {
        Result r = new Result();
        Query q = query == null ? new Query() : query;
        if (infobaseDirectory == null)
        {
            r.error = "no infobase directory to read the log from"; //$NON-NLS-1$
            return r;
        }
        Path logDir = infobaseDirectory.resolve(LOG_DIRECTORY);
        if (!Files.isDirectory(logDir))
        {
            r.error = "no " + LOG_DIRECTORY + " directory beside the infobase at " //$NON-NLS-1$ //$NON-NLS-2$
                + infobaseDirectory + " - either nothing has been logged yet, or logging is off. " //$NON-NLS-1$
                + "Check the event log settings in the Designer, under Administration: with every " //$NON-NLS-1$
                + "event switched off the directory is never created at all."; //$NON-NLS-1$
            return r;
        }
        if (Files.exists(logDir.resolve(SQLITE_LOG)) && !Files.exists(logDir.resolve(DICTIONARY)))
        {
            r.unsupported = SQLITE_LOG;
            r.error = "this infobase keeps its log in " + SQLITE_LOG + ", the single-file SQLite " //$NON-NLS-1$ //$NON-NLS-2$
                + "form. This reader takes apart the older " + RECORD_SUFFIX + " files and cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "read that one. Reporting nothing found would have been the wrong answer, so " //$NON-NLS-1$
                + "this says it plainly instead. Two ways forward: switch the infobase back to the " //$NON-NLS-1$
                + "separate-files form in the Designer, under the event log settings in " //$NON-NLS-1$
                + "Administration - new records then land in " + RECORD_SUFFIX + " files this can " //$NON-NLS-1$ //$NON-NLS-2$
                + "read, though the records already in " + SQLITE_LOG + " stay where they are; or " //$NON-NLS-1$ //$NON-NLS-2$
                + "open " + SQLITE_LOG + " with any SQLite client, since it is an ordinary SQLite " //$NON-NLS-1$ //$NON-NLS-2$
                + "database and not an encrypted one."; //$NON-NLS-1$
            return r;
        }

        Map<Integer, Map<Integer, String>> dictionaries;
        try
        {
            dictionaries = readDictionaries(logDir.resolve(DICTIONARY));
        }
        catch (IOException e)
        {
            r.error = "the dictionary " + DICTIONARY + " could not be read: " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }
        if (dictionaries == null)
        {
            r.error = DICTIONARY + " is missing or is not an event-log file, so the records cannot " //$NON-NLS-1$
                + "be resolved to names."; //$NON-NLS-1$
            return r;
        }

        List<Path> recordFiles = recordFiles(logDir);
        if (recordFiles.isEmpty())
        {
            r.ok = true;
            return r;
        }
        for (Path file : recordFiles)
        {
            if (r.rows.size() >= q.limit)
            {
                r.truncated = true;
                break;
            }
            r.files.add(file.getFileName().toString());
            readFileInto(file, dictionaries, q, r);
        }
        r.ok = true;
        return r;
    }

    /**
     * Reads one record file, appending what passes the filters.
     *
     * @param file the {@code .lgp} to read.
     * @param dictionaries the dictionaries the records refer into.
     * @param q what to keep.
     * @param r the result being built.
     */
    private static void readFileInto(Path file, Map<Integer, Map<Integer, String>> dictionaries,
        Query q, Result r)
    {
        String text;
        try
        {
            text = stripBom(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            // Named rather than skipped: a file that could not be read is a hole in the answer,
            // and an answer with a hole in it must not look complete.
            r.rows.add(unreadable(file, e));
            return;
        }
        for (List<Object> record : EventLogFormat.groups(text))
        {
            if (record.size() < RECORD_FIELDS)
            {
                continue;
            }
            r.scanned++;
            Map<String, Object> row = decode(record, dictionaries);
            if (!keep(row, q))
            {
                continue;
            }
            if (r.rows.size() >= q.limit)
            {
                r.truncated = true;
                return;
            }
            r.rows.add(row);
        }
    }

    /**
     * Turns one record into named values, resolving the dictionary references.
     *
     * @param record the record as parsed.
     * @param dictionaries the dictionaries.
     * @return the row.
     */
    private static Map<String, Object> decode(List<Object> record,
        Map<Integer, Map<Integer, String>> dictionaries)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("moment", moment(EventLogFormat.text(record, F_MOMENT))); //$NON-NLS-1$
        row.put("event", lookup(dictionaries, KIND_EVENT, //$NON-NLS-1$
            EventLogFormat.number(record, F_EVENT)));
        row.put("severity", severityName(EventLogFormat.text(record, F_SEVERITY))); //$NON-NLS-1$
        row.put("user", lookup(dictionaries, KIND_USER, //$NON-NLS-1$
            EventLogFormat.number(record, F_USER)));
        row.put("computer", lookup(dictionaries, KIND_COMPUTER, //$NON-NLS-1$
            EventLogFormat.number(record, F_COMPUTER)));
        row.put("application", lookup(dictionaries, KIND_APPLICATION, //$NON-NLS-1$
            EventLogFormat.number(record, F_APPLICATION)));
        String metadata = lookup(dictionaries, KIND_METADATA, EventLogFormat.number(record, F_METADATA));
        if (!metadata.isEmpty())
        {
            row.put("metadata", metadata); //$NON-NLS-1$
        }
        String comment = EventLogFormat.text(record, F_COMMENT);
        if (!comment.isEmpty())
        {
            row.put("comment", SensitiveTextMasker.redact(comment)); //$NON-NLS-1$
        }
        String presentation = EventLogFormat.text(record, F_PRESENTATION);
        if (!presentation.isEmpty())
        {
            row.put("presentation", SensitiveTextMasker.redact(presentation)); //$NON-NLS-1$
        }
        row.put("session", EventLogFormat.number(record, F_SESSION)); //$NON-NLS-1$
        row.put("connection", EventLogFormat.number(record, F_CONNECTION)); //$NON-NLS-1$
        String transaction = EventLogFormat.text(record, F_TRANSACTION_STATUS);
        if (!transaction.isEmpty() && !"N".equals(transaction)) //$NON-NLS-1$
        {
            row.put("transaction", transactionName(transaction)); //$NON-NLS-1$
        }
        return row;
    }

    /**
     * Whether a row survives the filters.
     *
     * @param row the decoded row.
     * @param q what to keep.
     * @return true when it is wanted.
     */
    private static boolean keep(Map<String, Object> row, Query q)
    {
        String moment = String.valueOf(row.get("moment")); //$NON-NLS-1$
        String compact = moment.replaceAll("[^0-9]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!q.from.isEmpty() && compact.compareTo(digitsOf(q.from)) < 0)
        {
            return false;
        }
        if (!q.to.isEmpty() && compact.compareTo(digitsOf(q.to)) > 0)
        {
            return false;
        }
        if (!q.event.isEmpty() && !contains(row.get("event"), q.event)) //$NON-NLS-1$
        {
            return false;
        }
        if (!q.user.isEmpty() && !contains(row.get("user"), q.user)) //$NON-NLS-1$
        {
            return false;
        }
        return q.severity.isEmpty() || contains(row.get("severity"), q.severity); //$NON-NLS-1$
    }

    /**
     * Whether a row value contains a filter word, ignoring case.
     *
     * @param value the value from the row.
     * @param needle what to look for.
     * @return true when it is there.
     */
    private static boolean contains(Object value, String needle)
    {
        return value != null
            && String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * The digits of a moment as written, so a caller may pass a date with or without separators.
     *
     * @param moment as the caller wrote it.
     * @return the digits, padded to the full width so a short date compares as its own start.
     */
    private static String digitsOf(String moment)
    {
        String digits = moment.replaceAll("[^0-9]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder sb = new StringBuilder(digits);
        while (sb.length() < 14)
        {
            sb.append('0');
        }
        return sb.substring(0, 14);
    }

    /**
     * Reads the dictionaries, keyed by kind and then by the number records refer to.
     *
     * @param dictionary the {@code 1Cv8.lgf} path.
     * @return the dictionaries, or null when the file is absent or is not one.
     * @throws IOException when it cannot be read.
     */
    private static Map<Integer, Map<Integer, String>> readDictionaries(Path dictionary) throws IOException
    {
        if (!Files.isRegularFile(dictionary))
        {
            return null;
        }
        String text = stripBom(new String(Files.readAllBytes(dictionary), StandardCharsets.UTF_8));
        if (!EventLogFormat.looksLikeLog(text))
        {
            return null;
        }
        String version = EventLogFormat.declaredVersion(text);
        if (version != null && !SUPPORTED_VERSION.equals(version))
        {
            // Read anyway: the shape has been stable, and refusing a version merely because it is
            // new would turn a working log into no log. The version travels to the caller.
            Activator.logInfo("Event log declares version " + version + ", reading it as " //$NON-NLS-1$ //$NON-NLS-2$
                + SUPPORTED_VERSION);
        }
        Map<Integer, Map<Integer, String>> out = new HashMap<>();
        for (List<Object> entry : EventLogFormat.groups(text))
        {
            if (entry.size() < 3)
            {
                continue;
            }
            int kind = EventLogFormat.number(entry, 0);
            int index = EventLogFormat.number(entry, entry.size() - 1);
            if (kind < 0 || index < 0)
            {
                continue;
            }
            out.computeIfAbsent(kind, k -> new HashMap<>()).put(index, nameOf(entry));
        }
        return out;
    }

    /**
     * The readable name in a dictionary entry.
     * <p>
     * A user entry carries a uuid and a name, and the name is sometimes empty - an administrator
     * who never got one. The uuid is then all there is, and giving it back beats giving nothing.
     * </p>
     *
     * @param entry the dictionary entry.
     * @return the best name available.
     */
    private static String nameOf(List<Object> entry)
    {
        for (int i = entry.size() - 2; i >= 1; i--)
        {
            String candidate = EventLogFormat.text(entry, i);
            if (!candidate.isEmpty())
            {
                return candidate;
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * A dictionary value, or an empty string when there is none under that number.
     *
     * @param dictionaries all dictionaries.
     * @param kind which dictionary.
     * @param index the number the record refers by.
     * @return the name, masked, or an empty string.
     */
    private static String lookup(Map<Integer, Map<Integer, String>> dictionaries, int kind, int index)
    {
        if (index <= 0)
        {
            return ""; //$NON-NLS-1$
        }
        Map<Integer, String> byIndex = dictionaries.get(kind);
        String name = byIndex == null ? null : byIndex.get(index);
        return name == null ? "" : SensitiveTextMasker.redact(name); //$NON-NLS-1$
    }

    /**
     * The moment as the log writes it, spaced out to be read.
     *
     * @param raw fourteen digits, {@code yyyyMMddHHmmss}.
     * @return {@code yyyy-MM-dd HH:mm:ss}, or the raw value when it is not that shape.
     */
    private static String moment(String raw)
    {
        if (raw == null || raw.length() != 14 || !raw.chars().allMatch(Character::isDigit))
        {
            return raw == null ? "" : raw; //$NON-NLS-1$
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8) //$NON-NLS-1$ //$NON-NLS-2$
            + " " + raw.substring(8, 10) + ":" + raw.substring(10, 12) + ":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + raw.substring(12, 14);
    }

    /**
     * The single letter the log uses for severity, spelled out.
     *
     * @param raw the letter.
     * @return a word, or the letter when it is not one of the known ones.
     */
    private static String severityName(String raw)
    {
        switch (raw)
        {
            case "I": //$NON-NLS-1$
                return "Information"; //$NON-NLS-1$
            case "W": //$NON-NLS-1$
                return "Warning"; //$NON-NLS-1$
            case "E": //$NON-NLS-1$
                return "Error"; //$NON-NLS-1$
            case "N": //$NON-NLS-1$
                return "Note"; //$NON-NLS-1$
            default:
                return raw;
        }
    }

    /**
     * The transaction letter, spelled out.
     *
     * @param raw the letter.
     * @return a word, or the letter when it is not one of the known ones.
     */
    private static String transactionName(String raw)
    {
        switch (raw)
        {
            case "U": //$NON-NLS-1$
                return "Committed"; //$NON-NLS-1$
            case "C": //$NON-NLS-1$
                return "Cancelled"; //$NON-NLS-1$
            case "R": //$NON-NLS-1$
                return "InProgress"; //$NON-NLS-1$
            default:
                return raw;
        }
    }

    /**
     * The record files, oldest first - their names are the moment each starts at.
     *
     * @param logDir the log directory.
     * @return the files in reading order.
     */
    private static List<Path> recordFiles(Path logDir)
    {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*" + RECORD_SUFFIX)) //$NON-NLS-1$
        {
            for (Path file : stream)
            {
                files.add(file);
            }
        }
        catch (IOException e)
        {
            // Reported as no files, with the reason in the log: the caller learns from the empty
            // answer plus the error that follows it, and never from silence.
            Activator.logWarning("Could not list the event log files in " + logDir + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return files;
        }
        Collections.sort(files);
        return files;
    }

    /**
     * A row standing in for a file that could not be read, so a hole in the answer is visible.
     *
     * @param file the file concerned.
     * @param e why it could not be read.
     * @return the row.
     */
    private static Map<String, Object> unreadable(Path file, IOException e)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("unreadableFile", file.getFileName().toString()); //$NON-NLS-1$
        row.put("reason", e.getMessage()); //$NON-NLS-1$
        return row;
    }

    /**
     * Drops the byte-order mark these files are written with.
     *
     * @param text as read.
     * @return the text without it.
     */
    private static String stripBom(String text)
    {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }
}
