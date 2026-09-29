/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which infobase a store's stored dump-info copy belongs to, and what that copy held when this
 * server last left it there.
 * <p>
 * An infobase changes without EDT: a {@code .dt} is loaded into it, its file is replaced, the
 * application is pointed at another base. An incremental update is decided against the stored
 * {@code ConfigDumpInfo.xml} of the last synchronization, which EDT holds - so an update can be
 * decided against a base the file no longer describes, and answer that nothing has to be loaded.
 * </p>
 * <p>
 * This record answers whether the file being read is still the one this server left: the identity
 * of the base it was written for ({@link InfobaseIdentity}) and a fingerprint of its content. It
 * sits beside the copy, because it is a fact about that file and travels with it.
 * </p>
 * <p>
 * The fingerprint hashes the records the file carries, each reduced to the object it names and the
 * version the base held for that object, sorted. A file carrying the same records in another order
 * is the same content; a file whose versions moved is not.
 * </p>
 */
public final class InfobaseOutsideChange
{
    /** The record file's name, in the store directory beside the copy it describes. */
    public static final String FILE_NAME = "ConfigDumpInfo.record.properties"; //$NON-NLS-1$

    /** One {@code Metadata} element, with its attributes. */
    private static final Pattern RECORD_ELEMENT = Pattern.compile("<Metadata\\b[^>]*>"); //$NON-NLS-1$

    /** The {@code name} attribute of a record: the object it is about. */
    private static final Pattern NAME_ATTRIBUTE = Pattern.compile("\\bname\\s*=\\s*\"([^\"]*)\""); //$NON-NLS-1$

    /** The {@code configVersion} attribute of a record: the version the base holds. */
    private static final Pattern VERSION_ATTRIBUTE =
        Pattern.compile("\\bconfigVersion\\s*=\\s*\"([^\"]*)\""); //$NON-NLS-1$

    /** The record file's key for the base the copy was written for. */
    private static final String IDENTITY_KEY = "infobase"; //$NON-NLS-1$

    /** The record file's key for the copy's content. */
    private static final String CONTENT_KEY = "content"; //$NON-NLS-1$

    /** The record file's key for how many records the copy carried. */
    private static final String RECORDS_KEY = "records"; //$NON-NLS-1$

    /** The record file's key for the {@code .dt} a load replaced the infobase from. */
    private static final String REPLACED_BY_KEY = "replacedBy"; //$NON-NLS-1$

    /** The record file's key for when that load finished. */
    private static final String REPLACED_AT_KEY = "replacedAt"; //$NON-NLS-1$

    /** What a reading that counted nothing reports as its record count. */
    public static final int UNKNOWN_RECORDS = -1;

    /** The base this reading is about, or {@code null} when it does not name one. */
    public final String identity;

    /** The copy's content, or {@code null} when the copy was not read. */
    public final String fingerprint;

    /** How many records the copy carried, or {@link #UNKNOWN_RECORDS} when it was not read. */
    public final int records;

    /**
     * The {@code .dt} a load replaced this infobase from, or {@code null} when no load is recorded
     * on this reading.
     */
    public final String replacedBy;

    /** When that load finished, as text, or {@code null} when {@link #replacedBy} is not set. */
    public final String replacedAt;

    private InfobaseOutsideChange(String identity, String fingerprint, int records, String replacedBy,
        String replacedAt)
    {
        this.identity = identity;
        this.fingerprint = fingerprint;
        this.records = records;
        this.replacedBy = replacedBy;
        this.replacedAt = replacedAt;
    }

    /**
     * An {@link InfobaseOutsideChange} assembled from its parts - the seam the checks read through,
     * so a comparison can be asked about a reading without a store standing behind it.
     *
     * @param identity the base the reading is about, or {@code null}
     * @param fingerprint the copy's content, or {@code null}
     * @param records how many records the copy carried, or {@link #UNKNOWN_RECORDS}
     * @return the reading
     */
    public static InfobaseOutsideChange of(String identity, String fingerprint, int records)
    {
        return new InfobaseOutsideChange(identity, fingerprint, records, null, null);
    }

    /**
     * This reading with a load recorded on it. The fingerprint of the stored copy stays as it was:
     * a load replaces the infobase, and the copy on disk is still the one the last update left.
     *
     * @param source the {@code .dt} that was loaded
     * @param when the moment the load finished, as text
     * @return the reading
     */
    public InfobaseOutsideChange withLoad(String source, String when)
    {
        return new InfobaseOutsideChange(identity, fingerprint, records, source, when);
    }

    /**
     * Writes a load onto the store's record, keeping whatever that record already said about the
     * copy. A record that was not there yet is created carrying the load alone, which is enough
     * for the next incremental update to see that the infobase was replaced.
     *
     * @param recordFile the sidecar beside the copy, or {@code null} for nowhere
     * @param source the {@code .dt} that was loaded
     * @param when the moment the load finished, as text
     * @throws IOException when the record cannot be written; the previous file is left as it was
     */
    public static void markLoaded(Path recordFile, String source, String when) throws IOException
    {
        if (recordFile == null || source == null || source.trim().isEmpty())
        {
            return;
        }
        read(recordFile).withLoad(source, when).writeTo(recordFile);
    }

    /**
     * The copy of an infobase's dump-info as it is on disk now: which base it is claimed for and
     * what it holds. A file that cannot be read - absent, empty, without records - gives a reading
     * that names no content, which is a comparison not made rather than a comparison failed.
     *
     * @param dumpInfoFile the store's {@code ConfigDumpInfo.xml}, or {@code null}
     * @param identity the base the copy belongs to ({@link InfobaseIdentity}), or {@code null} when
     *            the application does not say where its infobase is
     * @return the reading; never {@code null}
     */
    public static InfobaseOutsideChange copyOf(Path dumpInfoFile, String identity)
    {
        Content content = contentOf(dumpInfoFile);
        return new InfobaseOutsideChange(identity, content.fingerprint, content.records, null, null);
    }

    /**
     * The record written beside a copy earlier, read back. An unreadable file is treated as no
     * record: the next update that finishes writes one.
     *
     * @param recordFile the record file, or {@code null}
     * @return the reading; never {@code null}, and naming nothing when there is no record
     */
    public static InfobaseOutsideChange read(Path recordFile)
    {
        if (recordFile == null || !Files.isRegularFile(recordFile))
        {
            return new InfobaseOutsideChange(null, null, UNKNOWN_RECORDS, null, null);
        }
        Properties pairs = new Properties();
        try (Reader reader = Files.newBufferedReader(recordFile, StandardCharsets.UTF_8))
        {
            pairs.load(reader);
        }
        catch (IOException | RuntimeException unreadable)
        {
            return new InfobaseOutsideChange(null, null, UNKNOWN_RECORDS, null, null);
        }
        String identity = trimmed(pairs.getProperty(IDENTITY_KEY));
        String fingerprint = trimmed(pairs.getProperty(CONTENT_KEY));
        return new InfobaseOutsideChange(identity, fingerprint, asInt(pairs.getProperty(RECORDS_KEY)),
            trimmed(pairs.getProperty(REPLACED_BY_KEY)), trimmed(pairs.getProperty(REPLACED_AT_KEY)));
    }

    /**
     * The record file that belongs to a copy: the sidecar this class writes, beside the file it
     * describes, so a store directory carries both or neither.
     *
     * @param dumpInfoFile the store's {@code ConfigDumpInfo.xml}, or {@code null}
     * @return the record file's path, or {@code null} when there is no copy to describe
     */
    public static Path recordFileOf(Path dumpInfoFile)
    {
        return dumpInfoFile == null ? null : dumpInfoFile.resolveSibling(FILE_NAME);
    }

    /**
     * @return whether this reading read a copy: it names content and how many records it carried
     */
    public boolean known()
    {
        return fingerprint != null;
    }

    /**
     * @return whether a load of a {@code .dt} is recorded on this reading
     */
    public boolean replacedByLoad()
    {
        return replacedBy != null;
    }

    /**
     * Whether this reading is about another infobase than the one given.
     * <p>
     * False when either reading does not name a base: an application whose infobase has no
     * connection string says nothing, and nothing is claimed from a silence.
     * </p>
     *
     * @param now the reading of the copy as it is now
     * @return whether both name a base and the names differ
     */
    public boolean describesAnotherInfobaseThan(InfobaseOutsideChange now)
    {
        return now != null && identity != null && now.identity != null
            && !identity.equals(now.identity);
    }

    /**
     * Whether the copy holds content other than the content this reading recorded.
     *
     * @param now the reading of the copy as it is now
     * @return whether both readings hold content and the content differs
     */
    public boolean contentDiffersFrom(InfobaseOutsideChange now)
    {
        return now != null && fingerprint != null && now.fingerprint != null
            && !fingerprint.equals(now.fingerprint);
    }

    /**
     * Rewrites the store's record without a load mark, keeping every other key the record already
     * carried.
     * <p>
     * A record that carries no mark is left as it was. A record that cannot be rewritten is left
     * as it was too, and the reason is the return value: the caller names that failure rather than
     * treating the mark as gone.
     * </p>
     *
     * @param recordFile the sidecar beside the copy, or {@code null}
     * @return why the record could not be rewritten, or {@code null} when there was no mark or the
     *         mark was cleared
     */
    public static String clearTheLoad(Path recordFile)
    {
        if (recordFile == null)
        {
            return null;
        }
        InfobaseOutsideChange existing = read(recordFile);
        if (!existing.replacedByLoad())
        {
            return null;
        }
        try
        {
            new InfobaseOutsideChange(existing.identity, existing.fingerprint, existing.records,
                null, null).writeRecord(recordFile, true);
            return null;
        }
        catch (IOException failed)
        {
            return failed.toString();
        }
    }

    /**
     * Writes this reading as the record beside the copy.
     * <p>
     * Through a temporary file in the same directory, then a replacing move: opening the
     * destination first would truncate it, and a failure would leave the store with no record of
     * what its copy holds. No lock is taken - the callers write under the infobase's own claim or
     * under the rebuild's, and a lost race between two writers of one file leaves a record that is
     * still a true reading of that copy.
     * </p>
     * <p>
     * Written as UTF-8 through a writer rather than as ISO-8859-1 through a stream: an identity
     * carries the path of the base, and a path outside one workspace's ASCII is a base whose
     * record has to name it back.
     * </p>
     *
     * @param recordFile where to write, or {@code null} for nowhere (tests)
     * @throws IOException when the record cannot be written; the previous file is left as it was
     */
    public void writeTo(Path recordFile) throws IOException
    {
        writeRecord(recordFile, false);
    }

    /**
     * Writes this reading, optionally replacing a file even when the reading names neither content
     * nor a load. Clearing a mark that was the record's only key is that case: the file has to be
     * replaced, or the mark stays.
     *
     * @param recordFile where to write, or {@code null} for nowhere
     * @param replaceWhenEmpty whether a reading that names neither content nor a load still
     *            replaces the file
     * @throws IOException when the record cannot be written; the previous file is left as it was
     */
    private void writeRecord(Path recordFile, boolean replaceWhenEmpty) throws IOException
    {
        if (recordFile == null || (!replaceWhenEmpty && fingerprint == null && replacedBy == null))
        {
            return;
        }
        Properties pairs = new Properties();
        if (identity != null)
        {
            pairs.setProperty(IDENTITY_KEY, identity);
        }
        if (fingerprint != null)
        {
            pairs.setProperty(CONTENT_KEY, fingerprint);
            pairs.setProperty(RECORDS_KEY, String.valueOf(records));
        }
        if (replacedBy != null)
        {
            pairs.setProperty(REPLACED_BY_KEY, replacedBy);
            if (replacedAt != null)
            {
                pairs.setProperty(REPLACED_AT_KEY, replacedAt);
            }
        }

        Path absolute = recordFile.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp"); //$NON-NLS-1$
        try
        {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))
            {
                pairs.store(writer,
                    "What this store's ConfigDumpInfo.xml held when it was last written"); //$NON-NLS-1$
            }
            try
            {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException unsupported)
            {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException failed)
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException ignored)
            {
                // The destination was not opened, so the record read before still stands.
            }
            throw failed;
        }
    }

    /**
     * The content of a dump-info file: a fingerprint over its records and how many there were, from
     * one read of the file.
     *
     * @param dumpInfoFile the file, or {@code null}
     * @return the content; a file that cannot be read gives a null fingerprint
     */
    private static Content contentOf(Path dumpInfoFile)
    {
        if (dumpInfoFile == null || !Files.isRegularFile(dumpInfoFile))
        {
            return new Content(null, UNKNOWN_RECORDS);
        }
        String text;
        try
        {
            text = new String(Files.readAllBytes(dumpInfoFile), StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException unreadable)
        {
            return new Content(null, UNKNOWN_RECORDS);
        }
        List<String> records = new ArrayList<>();
        Matcher element = RECORD_ELEMENT.matcher(text);
        while (element.find())
        {
            String attributes = element.group();
            String name = attributeOf(NAME_ATTRIBUTE, attributes);
            if (name == null)
            {
                continue;
            }
            String version = attributeOf(VERSION_ATTRIBUTE, attributes);
            records.add(name + '\u0000' + (version == null ? "" : version)); //$NON-NLS-1$
        }
        if (records.isEmpty())
        {
            return new Content(null, UNKNOWN_RECORDS);
        }
        String[] sorted = records.toArray(new String[0]);
        Arrays.sort(sorted);
        MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException noDigest)
        {
            return new Content(null, sorted.length);
        }
        for (String record : sorted)
        {
            digest.update(record.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)'\n');
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest())
        {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return new Content(hex.toString(), sorted.length);
    }

    /**
     * One attribute's value inside a record element.
     *
     * @param pattern the attribute's pattern, whose first group is its value
     * @param element the record element's text
     * @return the value, or {@code null} when the attribute is not there
     */
    private static String attributeOf(Pattern pattern, String element)
    {
        Matcher m = pattern.matcher(element);
        return m.find() ? m.group(1) : null;
    }

    /**
     * A property's value, blank treated as absent.
     *
     * @param value the raw value, or {@code null}
     * @return the trimmed value, or {@code null} when it is empty
     */
    private static String trimmed(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * A property's record count.
     *
     * @param value the raw value, or {@code null}
     * @return the count, or {@link #UNKNOWN_RECORDS} when it is not a number
     */
    private static int asInt(String value)
    {
        try
        {
            return value == null ? UNKNOWN_RECORDS : Integer.parseInt(value.trim());
        }
        catch (NumberFormatException notANumber)
        {
            return UNKNOWN_RECORDS;
        }
    }

    /**
     * A dump-info file's content: its fingerprint and how many records produced it.
     */
    private static final class Content
    {
        /** The fingerprint, or {@code null} when no record was read. */
        private final String fingerprint;

        /** How many records were read, or {@link #UNKNOWN_RECORDS}. */
        private final int records;

        Content(String fingerprint, int records)
        {
            this.fingerprint = fingerprint;
            this.records = records;
        }
    }
}
