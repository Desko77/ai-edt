/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a Vanessa run left behind, read from the files it actually writes.
 * <p>
 * Vanessa has no JUnit parameters of its own. Asked for a machine readable result through the
 * documented pair - {@code ДелатьОтчетВФорматеАллюр} and {@code КаталогOutputAllureБазовый} - it
 * writes one {@code <uuid>-result.json} per scenario, and names the files itself. A caller looking
 * for a single file of its own naming finds nothing and reads the run as one that produced no
 * result.
 * </p>
 */
public final class AllureResultReader
{
    /** What Vanessa calls a scenario that failed on an assertion. */
    private static final String FAILED = "failed"; //$NON-NLS-1$

    /** What Vanessa calls a scenario that stopped on an error. */
    private static final String BROKEN = "broken"; //$NON-NLS-1$

    /** What Vanessa calls a scenario it did not run. */
    private static final String SKIPPED = "skipped"; //$NON-NLS-1$

    /** The tail every result file carries. */
    static final String RESULT_SUFFIX = "-result.json"; //$NON-NLS-1$

    private AllureResultReader()
    {
        // Read through the static entry points.
    }

    /**
     * The result files of a run, newest last.
     *
     * @param dir the run directory; may be <code>null</code>.
     * @return the files, empty when the directory holds none
     */
    public static File[] resultsIn(File dir)
    {
        File[] found = dir == null ? null
            : dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(RESULT_SUFFIX));
        if (found == null)
        {
            return new File[0];
        }
        Arrays.sort(found, Comparator.comparingLong(File::lastModified));
        return found;
    }

    /**
     * Reads every scenario of a run into the shape the rest of this plugin reports on.
     *
     * @param dir the run directory.
     * @return the outcome; a run with no result files reads as one with no tests
     * @throws IOException if a file is there and cannot be read
     */
    public static JUnitRunOutcome parse(File dir) throws IOException
    {
        JUnitRunOutcome outcome = new JUnitRunOutcome();
        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (File file : resultsIn(dir))
        {
            JsonObject one = read(file);
            if (one == null)
            {
                continue;
            }
            total++;
            String name = text(one, "name"); //$NON-NLS-1$
            String status = text(one, "status"); //$NON-NLS-1$
            JsonObject details = one.has("statusDetails") && one.get("statusDetails").isJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
                ? one.getAsJsonObject("statusDetails") : null; //$NON-NLS-1$
            String message = details == null ? null : text(details, "message"); //$NON-NLS-1$
            String trace = details == null ? null : text(details, "trace"); //$NON-NLS-1$
            JUnitRunOutcome.TestCase testCase = new JUnitRunOutcome.TestCase(name, message, trace);
            if (FAILED.equalsIgnoreCase(status))
            {
                failures++;
                outcome.addFailure(testCase);
            }
            else if (BROKEN.equalsIgnoreCase(status))
            {
                errors++;
                outcome.addError(testCase);
            }
            else if (SKIPPED.equalsIgnoreCase(status))
            {
                skipped++;
                outcome.addSkipped(testCase);
            }
        }
        outcome.addToTotals(total, failures, errors, skipped);
        return outcome;
    }

    /**
     * One result file as an object.
     *
     * @param file the file.
     * @return its content, or <code>null</code> when it is not an object
     * @throws IOException if it cannot be read
     */
    private static JsonObject read(File file) throws IOException
    {
        try (Reader reader =
            new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))
        {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException malformed)
        {
            throw new IOException("the result file is not readable json: " //$NON-NLS-1$
                + file.getAbsolutePath(), malformed);
        }
    }

    /**
     * A string member, or <code>null</code>.
     *
     * @param owner the object.
     * @param member the member name.
     * @return the text, or <code>null</code> when absent or not a string
     */
    private static String text(JsonObject owner, String member)
    {
        JsonElement value = owner.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
