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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.aiedt.mcp.server.Activator;

/**
 * The format of a {@code ConfigDumpInfo.xml} - read off the file, expected for a platform version,
 * remembered after a rebuild.
 *
 * <p>EDT stores the file beside {@code index.idx} in the per-infobase synchronization store, and the
 * PLATFORM reads it from disk on every {@code config dump-files}: EDT passes the path and the 1C
 * Designer answers {@code FullDump} when the file's {@code version} attribute is a format it does
 * not understand (measured 22.09: EDT 2026.2 writes {@code 2.20}, the 8.3.27 Designer reads and
 * writes {@code 2.7}). The update then silently becomes a full configuration load. Which format a
 * platform version understands is a measured fact, so the pairs live in a table plus whatever a
 * rebuild has recorded for its platform.</p>
 */
public final class DumpInfoProbe
{
    /** The file name inside the per-infobase store. */
    public static final String FILE_NAME = "ConfigDumpInfo.xml"; //$NON-NLS-1$

    /**
     * Measured pairs: platform version (major.minor.patch) - the {@code version} attribute its
     * Designer reads and writes. A platform absent here has no expectation and the format check
     * steps aside rather than guessing.
     */
    private static final Map<String, String> MEASURED = Map.of("8.3.27", "2.7"); //$NON-NLS-1$ //$NON-NLS-2$

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

    /** Where remembered pairs live, unless a test points somewhere else. */
    private static final String STATE_FILE = "dump-info-formats.properties"; //$NON-NLS-1$

    private DumpInfoProbe()
    {
        // static utility
    }

    /**
     * What one read of the stored file established.
     * <p>
     * A {@code mismatch} is the only thing this class claims a right to refuse on: no file, or no
     * expectation for the platform, leaves the decision to EDT and the platform rather than to a
     * guess dressed as a check.
     * </p>
     */
    public static final class Reading
    {
        /** The stored file's path, named in answers whatever it says. */
        public final String file;

        /** The {@code version} attribute the file carries, or {@code null} when it was not read. */
        public final String actualFormat;

        /** The format the platform is known to understand, or {@code null} when nothing is known. */
        public final String expectedFormat;

        /** The platform version the expectation is for, or {@code null}. */
        public final String platformVersion;

        Reading(String file, String actualFormat, String expectedFormat, String platformVersion)
        {
            this.file = file;
            this.actualFormat = actualFormat;
            this.expectedFormat = expectedFormat;
            this.platformVersion = platformVersion;
        }

        /**
         * @return whether the file carries a format the platform is known NOT to understand
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
     * @param expectedFormat the platform's known format, or {@code null}
     * @param platformVersion the platform the expectation is for, or {@code null}
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
     * The platform version without its build number, which is what the pairs are keyed by.
     *
     * @param versionWithBuild the version as {@code RuntimeInstallation.getVersionWithBuild()} gives
     *            it, for example {@code 8.3.27.2214}
     * @return the first three components, or the input when it does not split that way
     */
    public static String withoutBuild(String versionWithBuild)
    {
        if (versionWithBuild == null)
        {
            return null;
        }
        String[] parts = versionWithBuild.split("\\."); //$NON-NLS-1$
        if (parts.length <= 3)
        {
            return versionWithBuild;
        }
        return parts[0] + "." + parts[1] + "." + parts[2]; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The format expected of a platform version: the table first, then what a rebuild remembered.
     *
     * @param platformVersion the platform version WITHOUT build ({@link #withoutBuild}), or the
     *            with-build form - both are accepted
     * @param rememberedPairs the properties file a rebuild writes, or {@code null} to consult the
     *            table only
     * @return the expected format, or {@code null} when nothing is known for this platform
     */
    public static String expectedFormat(String platformVersion, Path rememberedPairs)
    {
        String normalized = withoutBuild(platformVersion);
        if (normalized == null)
        {
            return null;
        }
        String measured = MEASURED.get(normalized);
        if (measured != null)
        {
            return measured;
        }
        Properties remembered = readPairs(rememberedPairs);
        return remembered == null ? null : remembered.getProperty(normalized);
    }

    /**
     * Records what format a platform's own Designer wrote, so the next check expects it.
     * <p>
     * Written last-wins: a rebuild says the platform writes what it just wrote, and an older entry
     * for the same version is a fact that has been superseded, not a fact to preserve.
     * </p>
     *
     * @param platformVersion the platform version, with or without build
     * @param format the {@code version} attribute the platform wrote
     * @param rememberedPairs the properties file to write, or {@code null} for nowhere (tests)
     * @throws IOException when the file cannot be written
     */
    public static void rememberPair(String platformVersion, String format, Path rememberedPairs)
        throws IOException
    {
        if (rememberedPairs == null || platformVersion == null || format == null)
        {
            return;
        }
        Properties existing = readPairs(rememberedPairs);
        Properties pairs = existing != null ? existing : new Properties();
        pairs.setProperty(withoutBuild(platformVersion), format);
        Files.createDirectories(rememberedPairs.toAbsolutePath().getParent());
        try (java.io.OutputStream out = Files.newOutputStream(rememberedPairs))
        {
            pairs.store(out, "ConfigDumpInfo formats written by platform Designers"); //$NON-NLS-1$
        }
    }

    /**
     * Where remembered pairs live in this plugin's state area, or {@code null} outside EDT.
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
     * Reads the stored pairs, unreadable treated as absent - a file this cannot parse is a file a
     * rebuild will rewrite.
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

    /**
     * The pairs this class would consult for a platform, as a map - for answers and tests.
     *
     * @param platformVersion the platform version
     * @param rememberedPairs the rebuild-remembered pairs file, or {@code null}
     * @return the pairs in consult order (table first), possibly empty
     */
    public static Map<String, String> pairsFor(String platformVersion, Path rememberedPairs)
    {
        Map<String, String> ordered = new LinkedHashMap<>();
        String normalized = withoutBuild(platformVersion);
        String measured = normalized == null ? null : MEASURED.get(normalized);
        if (measured != null)
        {
            ordered.put(normalized, measured);
        }
        Properties remembered = readPairs(rememberedPairs);
        if (remembered != null && normalized != null && remembered.getProperty(normalized) != null)
        {
            ordered.put(normalized, remembered.getProperty(normalized));
        }
        return ordered;
    }
}
