/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.aiedt.mcp.server.Activator;

/**
 * The format of a {@code ConfigDumpInfo.xml} - read off the file, and the one recorded for an
 * infobase by a rebuild of it.
 *
 * <p>EDT stores the file beside {@code index.idx} in the per-infobase synchronization store, and the
 * PLATFORM reads it from disk on every {@code config dump-files}: the 1C Designer answers
 * {@code FullDump} when the file's {@code version} attribute is a format it does not understand, and
 * the update silently becomes a full configuration load. Which format a base understands is a
 * property of the BASE rather than of the platform version: measured 23.09 on platform 8.3.27.2214,
 * a base created empty and loaded from a {@code .cf} answered {@code version="2.20"}, while the
 * stand's base on the same platform answered {@code version="2.7"}. So nothing is expected of a base
 * until one of its own dumps has been recorded here, and a base with no record is not compared at
 * all rather than compared against a version's reputation.</p>
 */
public final class DumpInfoProbe
{
    /** The file name inside the per-infobase store. */
    public static final String FILE_NAME = "ConfigDumpInfo.xml"; //$NON-NLS-1$

    /**
     * The root element with its version attribute. The file's head is enough: the element opens
     * the document and the attribute sits on it, so a multi-megabyte dump is not read whole just to
     * learn three characters.
     */
    private static final Pattern ROOT_WITH_VERSION =
        Pattern.compile("<ConfigDumpInfo\\s[^>]*\\bversion\\s*=\\s*\"([^\"]+)\"", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    /** Bytes of the file head that are read to find the root element. */
    private static final int HEAD_BYTES = 8192;

    /** Where recorded formats live, unless a test points somewhere else. */
    private static final String STATE_FILE = "dump-info-formats.properties"; //$NON-NLS-1$

    private DumpInfoProbe()
    {
        // static utility
    }

    /**
     * What one read of the stored file established.
     * <p>
     * A {@code mismatch} is the only thing this class claims a right to refuse on: no file, or no
     * format recorded for this infobase, leaves the decision to EDT and the platform rather than to
     * a guess dressed as a check.
     * </p>
     */
    public static final class Reading
    {
        /** The stored file's path, named in answers whatever it says. */
        public final String file;

        /** The {@code version} attribute the file carries, or {@code null} when it was not read. */
        public final String actualFormat;

        /**
         * The format recorded for this infobase by its own Designer, or {@code null} when none has
         * been recorded.
         */
        public final String expectedFormat;

        /**
         * The platform whose Designer wrote the recorded format, or {@code null} - said in answers
         * as the origin of the record, not as the reason for it.
         */
        public final String platformVersion;

        Reading(String file, String actualFormat, String expectedFormat, String platformVersion)
        {
            this.file = file;
            this.actualFormat = actualFormat;
            this.expectedFormat = expectedFormat;
            this.platformVersion = platformVersion;
        }

        /**
         * @return whether the file carries a format other than the one recorded for this infobase
         */
        public boolean mismatch()
        {
            return actualFormat != null && expectedFormat != null && !actualFormat.equals(expectedFormat);
        }
    }

    /**
     * A {@link Reading} assembled from its parts - the seam the update path reads through, so the
     * gate can be asked about a reading without EDT standing behind it.
     *
     * @param file the stored file's path
     * @param actualFormat its version attribute, or {@code null}
     * @param expectedFormat the format recorded for this infobase, or {@code null}
     * @param platformVersion the platform that wrote the recorded format, or {@code null}
     * @return the reading
     */
    public static Reading reading(String file, String actualFormat, String expectedFormat,
        String platformVersion)
    {
        return new Reading(file, actualFormat, expectedFormat, platformVersion);
    }

    /**
     * The {@code version} attribute of the file's root {@code ConfigDumpInfo} element.
     *
     * @param file the dump-info file; need not exist
     * @return the version, or {@code null} when there is no file or no root with a version
     */
    public static String formatOf(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return null;
        }
        try (InputStream in = Files.newInputStream(file))
        {
            byte[] buf = new byte[HEAD_BYTES];
            int total = 0;
            int n;
            while (total < HEAD_BYTES && (n = in.read(buf, total, HEAD_BYTES - total)) > 0)
            {
                total += n;
            }
            return formatOfHead(new String(buf, 0, total, StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * The root element's version attribute, read off a file head.
     *
     * @param head the first bytes of the file as text
     * @return the version, or {@code null}
     */
    public static String formatOfHead(String head)
    {
        if (head == null)
        {
            return null;
        }
        Matcher m = ROOT_WITH_VERSION.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /**
     * How many object records the file carries. One record per {@code Metadata} element; counted by
     * occurrences rather than by parsing, because the count is a report figure, not a decision.
     *
     * @param file the dump-info file
     * @return the record count, or {@code -1} when the file does not read
     */
    public static int recordsIn(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return -1;
        }
        int count = 0;
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            String line;
            int at;
            while ((line = reader.readLine()) != null)
            {
                at = 0;
                while ((at = line.indexOf("<Metadata", at)) >= 0) //$NON-NLS-1$
                {
                    count++;
                    at += 9;
                }
            }
            return count;
        }
        catch (IOException | RuntimeException e)
        {
            return -1;
        }
    }

    /**
     * The format recorded for an infobase, keyed by {@link InfobaseIdentity} - what its own Designer
     * was measured to write, which is the only format its update may expect.
     * <p>
     * The key is where the infobase is rather than what some workspace calls it, so a record made by
     * one EDT instance answers a check run by another, and a base whose file has moved is a base
     * with no record rather than a base compared against someone else's.
     * </p>
     *
     * @param infobaseIdentity the base's identity ({@link InfobaseIdentity}), or {@code null}
     * @param recordedFormats the properties file a rebuild writes, or {@code null} for nowhere
     * @return the recorded format, or {@code null} when this base has no record yet
     */
    public static String expectedFormat(String infobaseIdentity, Path recordedFormats)
    {
        Properties recorded = readPairs(recordedFormats);
        return infobaseIdentity == null || recorded == null
            ? null : recorded.getProperty(infobaseIdentity);
    }

    /**
     * Records what format an infobase's own Designer wrote, so the next check of that base expects
     * it.
     * <p>
     * Written last-wins: the rebuild says what the Designer wrote just now, and an older entry for
     * the same base is a fact that has been superseded, not a fact to preserve. The file also
     * carries the answer to the question it is read for rather than a list of every base ever
     * rebuilt, so nothing removes an entry except a later rebuild of the same base.
     * </p>
     *
     * @param infobaseIdentity the base's identity ({@link InfobaseIdentity})
     * @param format the {@code version} attribute its Designer wrote
     * @param recordedFormats the properties file to write, or {@code null} for nowhere (tests)
     * @throws IOException when the file cannot be written
     */
    public static void rememberPair(String infobaseIdentity, String format, Path recordedFormats)
        throws IOException
    {
        if (recordedFormats == null || infobaseIdentity == null || format == null)
        {
            return;
        }
        Properties existing = readPairs(recordedFormats);
        Properties recorded = existing != null ? existing : new Properties();
        recorded.setProperty(infobaseIdentity, format);
        Files.createDirectories(recordedFormats.toAbsolutePath().getParent());
        try (java.io.OutputStream out = Files.newOutputStream(recordedFormats))
        {
            recorded.store(out, "ConfigDumpInfo formats written by the Designer of an infobase"); //$NON-NLS-1$
        }
    }

    /**
     * Where recorded formats live in this plugin's state area, or {@code null} outside EDT.
     *
     * @return the properties file path, or {@code null}
     */
    public static Path stateFile()
    {
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getStateLocation() == null)
        {
            return null;
        }
        return activator.getStateLocation().append(STATE_FILE).toFile().toPath();
    }

    /**
     * Reads the recorded formats, unreadable treated as absent - a file this cannot parse is a file
     * a rebuild will rewrite.
     */
    private static Properties readPairs(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return null;
        }
        try (InputStream in = Files.newInputStream(file))
        {
            Properties pairs = new Properties();
            pairs.load(in);
            return pairs;
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }
}
