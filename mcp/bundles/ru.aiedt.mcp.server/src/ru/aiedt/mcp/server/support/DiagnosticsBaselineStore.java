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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;

/**
 * The recorded diagnostics of one project, and the arithmetic over two such records.
 * <p>
 * An agent that has just edited code wants the findings that appeared since its edit, not the
 * whole catalogue of a project it did not touch. The catalogue is what
 * {@code get_project_errors} answers; this store holds the "before" it is subtracted from, one
 * file per project in the plugin's own state area - never inside the project, never in version
 * control.
 * </p>
 * <p>
 * A finding is identified by the triple {@code object, check, message} plus a count of identical
 * triples. The line a finding sits on is deliberately not part of the identity: code shifts
 * constantly, and the same finding one line lower is the same finding. Two severity levels of one
 * triple are also one triple - the severity of the first finding stands for the record and feeds
 * only the counts.
 * </p>
 * <p>
 * Everything here runs without EDT: the inputs are plain lists, the storage is a directory handed
 * in. Production points it at {@link #stateDir()}; tests point it at a temporary folder.
 * </p>
 */
public final class DiagnosticsBaselineStore
{
    /** The version of the file format this build writes and reads. */
    public static final int FORMAT_VERSION = 1;

    /** The subdirectory of the plugin state area the baselines live in. */
    public static final String STATE_SUBDIR = "diagnostics-baseline"; //$NON-NLS-1$

    /**
     * The per-project locks. One EDT instance holds the workspace, so an in-process lock per
     * project name is enough to serialize its own reads, writes and clears against each other.
     */
    private static final ConcurrentHashMap<String, Object> PROJECT_LOCKS = new ConcurrentHashMap<>();

    private DiagnosticsBaselineStore()
    {
        // static utility
    }

    /**
     * One recorded finding. Carries the identity triple and nothing positional: the line an error
     * sits on moves with the code, and a finding that moved is not a new finding.
     */
    public static final class Diagnostic
    {
        /** The object presentation or file path the finding stands on; never <code>null</code>. */
        public final String objectRef;

        /** The check identity - the symbolic id, or the short code when there is no symbolic one. */
        public final String checkId;

        /** The finding's message text; never <code>null</code>. */
        public final String message;

        /**
         * The native severity name of the first finding of this triple. Part of no identity: it
         * feeds the counts a saved answer reports.
         */
        public final String severity;

        /** How many identical triples this one record stands for; at least one. */
        public final int count;

        /**
         * A record for one finding.
         *
         * @param objectRef the object presentation or file path
         * @param checkId the check identity
         * @param message the message text
         * @param severity the native severity name; may be <code>null</code>
         */
        public Diagnostic(String objectRef, String checkId, String message, String severity)
        {
            this(objectRef, checkId, message, severity, 1);
        }

        /**
         * A record standing for several identical findings.
         *
         * @param objectRef the object presentation or file path
         * @param checkId the check identity
         * @param message the message text
         * @param severity the native severity name; may be <code>null</code>
         * @param count how many identical findings; at least one
         */
        public Diagnostic(String objectRef, String checkId, String message, String severity,
            int count)
        {
            this.objectRef = Objects.requireNonNull(objectRef);
            this.checkId = Objects.requireNonNull(checkId);
            this.message = Objects.requireNonNull(message);
            this.severity = severity;
            this.count = Math.max(1, count);
        }

        /**
         * The identity two findings are the same by: the triple, joined by a separator that
         * cannot occur inside a Java string read from a marker presentation.
         *
         * @return the identity key, never <code>null</code>
         */
        public String identity()
        {
            return objectRef + "\u0000" + checkId + "\u0000" + message; //$NON-NLS-1$
        }

        /**
         * This record with another count.
         *
         * @param newCount the count the copy carries
         * @return the copy
         */
        public Diagnostic withCount(int newCount)
        {
            return new Diagnostic(objectRef, checkId, message, severity, newCount);
        }

        @Override
        public String toString()
        {
            return objectRef + " / " + checkId + " / " + message + " x" + count; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /** A baseline as it lives in the file: when it was taken, over what, and what it holds. */
    public static final class Baseline
    {
        /** The moment the baseline was recorded, as an ISO-8601 instant. */
        public final String savedAt;

        /** The object FQNs the baseline covers; empty means the whole project. */
        public final List<String> objects;

        /** The findings the baseline holds, collapsed to one record per identical triple. */
        public final List<Diagnostic> diagnostics;

        Baseline(String savedAt, List<String> objects, List<Diagnostic> diagnostics)
        {
            this.savedAt = savedAt;
            this.objects = objects;
            this.diagnostics = diagnostics;
        }

        /**
         * Whether the baseline covers a named set of objects rather than the whole project.
         *
         * @return <code>true</code> when a non-empty object list scopes it
         */
        public boolean isScoped()
        {
            return objects != null && !objects.isEmpty();
        }
    }

    /** What subtracting one record from another leaves. */
    public static final class Diff
    {
        /** The findings that stand now and did not stand at the baseline, in current order. */
        public final List<Diagnostic> appeared;

        /** How many baseline findings no longer stand. */
        public final int resolvedCount;

        Diff(List<Diagnostic> appeared, int resolvedCount)
        {
            this.appeared = appeared;
            this.resolvedCount = resolvedCount;
        }

        /**
         * The total number of new findings, counting each record's multiplicity.
         *
         * @return the sum of the appeared records' counts
         */
        public int appearedCount()
        {
            int total = 0;
            for (Diagnostic diagnostic : appeared)
            {
                total += diagnostic.count;
            }
            return total;
        }
    }

    /**
     * A baseline file that exists but cannot be used: another format version, or content this
     * build cannot parse. An unreadable file is never an empty baseline - empty reads as "the
     * project was clean", and a file that cannot be read says nothing of the sort.
     */
    public static final class UnreadableBaselineException extends IOException
    {
        private static final long serialVersionUID = 1L;

        /**
         * The refusal a caller turns into an answer.
         *
         * @param reason what is wrong with the file, named for the answer
         */
        public UnreadableBaselineException(String reason)
        {
            super(reason);
        }
    }

    /**
     * Where the baselines live in this plugin's state area.
     *
     * @return the directory, or <code>null</code> outside a running EDT
     */
    public static Path stateDir()
    {
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getStateLocation() == null)
        {
            return null;
        }
        return activator.getStateLocation().append(STATE_SUBDIR).toFile().toPath();
    }

    /**
     * The file name a project's baseline lives under.
     * <p>
     * Every character outside letters, digits, the hyphen and the underscore is written as a
     * percent-escaped four-digit code unit. The escape starts with a character the kept set
     * excludes and that no file system reads as a path separator, so no project name can produce
     * another project's escaped form: two names that differ only in characters a file name cannot
     * hold land in two different files.
     * </p>
     *
     * @param projectName the project name; not <code>null</code>
     * @return the file name, never <code>null</code> or empty
     */
    public static String fileNameFor(String projectName)
    {
        Objects.requireNonNull(projectName);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < projectName.length(); i++)
        {
            char c = projectName.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '-' || c == '_')
            {
                name.append(c);
            }
            else
            {
                name.append('%').append(String.format("%04X", Integer.valueOf(c))); //$NON-NLS-1$
            }
        }
        return name.append(".json").toString(); //$NON-NLS-1$
    }

    /**
     * The file one project's baseline lives in.
     *
     * @param dir the baseline directory
     * @param projectName the project name
     * @return the file inside the directory
     */
    public static Path fileFor(Path dir, String projectName)
    {
        return dir.resolve(fileNameFor(projectName));
    }

    /**
     * Records the baseline of a project, replacing any earlier one.
     * <p>
     * The findings are collapsed first - one record per identical triple - and the file is written
     * to a temporary file beside the destination and moved into place, so a failure leaves the
     * previous baseline standing rather than an empty or half-written file.
     * </p>
     *
     * @param dir the baseline directory; created when missing
     * @param projectName the project the baseline belongs to
     * @param objects the object FQNs the baseline covers; empty for the whole project
     * @param findings the findings to record, each standing for itself
     * @return the baseline as it was written, with its moment and collapsed records
     * @throws IOException when the file cannot be written; the previous file is left as it was
     */
    public static Baseline save(Path dir, String projectName, List<String> objects,
        List<Diagnostic> findings) throws IOException
    {
        Objects.requireNonNull(dir);
        Objects.requireNonNull(projectName);
        List<String> scope = objects == null ? Collections.emptyList() : List.copyOf(objects);
        List<Diagnostic> collapsed = collapse(findings);
        String savedAt = Instant.now().toString();
        synchronized (lockFor(projectName))
        {
            Files.createDirectories(dir);
            Path target = fileFor(dir, projectName);
            Path temporary = Files.createTempFile(dir, target.getFileName().toString(), ".tmp"); //$NON-NLS-1$
            try
            {
                Files.writeString(temporary, toJson(savedAt, scope, collapsed), StandardCharsets.UTF_8);
                moveIntoPlace(temporary, target);
            }
            catch (IOException failed)
            {
                try
                {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored)
                {
                    // The destination was never opened; the previous baseline still stands.
                }
                throw failed;
            }
        }
        return new Baseline(savedAt, scope, collapsed);
    }

    /**
     * Reads the baseline of a project.
     *
     * @param dir the baseline directory
     * @param projectName the project the baseline belongs to
     * @return the baseline, or <code>null</code> when none is recorded
     * @throws UnreadableBaselineException when a file exists but is of another format version or
     *             cannot be parsed - which is a refusal to answer, not an empty baseline
     * @throws IOException when the file cannot be read at all
     */
    public static Baseline load(Path dir, String projectName)
        throws IOException, UnreadableBaselineException
    {
        Path file = fileFor(dir, projectName);
        synchronized (lockFor(projectName))
        {
            if (!Files.isRegularFile(file))
            {
                return null;
            }
            String text;
            try
            {
                text = Files.readString(file, StandardCharsets.UTF_8);
            }
            catch (IOException unreadable)
            {
                throw new UnreadableBaselineException(
                    "the file cannot be read: " + unreadable.getMessage()); //$NON-NLS-1$
            }
            return fromJson(text);
        }
    }

    /**
     * Removes the baseline of a project.
     *
     * @param dir the baseline directory
     * @param projectName the project whose baseline is removed
     * @return whether a baseline was there to remove
     * @throws IOException when the file cannot be deleted
     */
    public static boolean clear(Path dir, String projectName) throws IOException
    {
        synchronized (lockFor(projectName))
        {
            Path file = fileFor(dir, projectName);
            if (!Files.isRegularFile(file))
            {
                return false;
            }
            Files.delete(file);
            return true;
        }
    }

    /**
     * Subtracts one record from another.
     * <p>
     * A triple that stands more often now than at the baseline contributes the difference as new;
     * one that stands less often contributes the difference as resolved. The line a finding sits
     * on takes no part - it is not part of the identity.
     * </p>
     *
     * @param baseline the recorded findings
     * @param current the findings standing now, over the same area
     * @return what appeared and how many resolved, never <code>null</code>
     */
    public static Diff compare(List<Diagnostic> baseline, List<Diagnostic> current)
    {
        Map<String, Long> before = countsOf(baseline);
        Map<String, Long> now = countsOf(current);
        List<Diagnostic> appeared = new ArrayList<>();
        Set<String> told = Collections.newSetFromMap(new LinkedHashMap<>());
        for (Diagnostic diagnostic : current)
        {
            String key = diagnostic.identity();
            if (!told.add(key))
            {
                continue;
            }
            long more = now.get(key).longValue() - countIn(before, key);
            if (more > 0)
            {
                appeared.add(diagnostic.withCount((int)more));
            }
        }
        int resolved = 0;
        for (Map.Entry<String, Long> entry : before.entrySet())
        {
            long gone = entry.getValue().longValue() - countIn(now, entry.getKey());
            if (gone > 0)
            {
                resolved += (int)gone;
            }
        }
        return new Diff(appeared, resolved);
    }

    /**
     * Collapses findings to one record per identical triple, counting the multiplicities.
     *
     * @param findings the findings; may be <code>null</code>
     * @return the collapsed records, in first-appearance order
     */
    public static List<Diagnostic> collapse(List<Diagnostic> findings)
    {
        if (findings == null)
        {
            return new ArrayList<>();
        }
        Map<String, Diagnostic> first = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Diagnostic diagnostic : findings)
        {
            String key = diagnostic.identity();
            first.putIfAbsent(key, diagnostic);
            counts.merge(key, Integer.valueOf(diagnostic.count), Integer::sum);
        }
        List<Diagnostic> collapsed = new ArrayList<>();
        for (Map.Entry<String, Diagnostic> entry : first.entrySet())
        {
            Diagnostic one = entry.getValue();
            collapsed.add(one.withCount(counts.get(entry.getKey()).intValue()));
        }
        return collapsed;
    }

    /**
     * The findings whose object stands under one of the given FQNs.
     * <p>
     * Segment boundaries, not substrings, for the same reason every object filter here uses them:
     * {@code "Catalog.Products"} must not answer for {@code "Catalog.ProductsExtra"}. This is the
     * rule that keeps a baseline scoped to named objects from naming the findings of every other
     * object new - the others were never compared against it.
     * </p>
     *
     * @param diagnostics the findings
     * @param fqnVariantsLower the lowercased FQN variants to keep; <code>null</code> or empty
     *            keeps everything, because an empty set names no restriction
     * @return the findings whose object is under one of the FQNs, in order
     */
    public static List<Diagnostic> withinObjects(List<Diagnostic> diagnostics,
        Set<String> fqnVariantsLower)
    {
        List<Diagnostic> kept = new ArrayList<>();
        if (diagnostics == null)
        {
            return kept;
        }
        if (fqnVariantsLower == null || fqnVariantsLower.isEmpty())
        {
            kept.addAll(diagnostics);
            return kept;
        }
        for (Diagnostic diagnostic : diagnostics)
        {
            String presentation = diagnostic.objectRef.toLowerCase(Locale.ROOT);
            for (String fqn : fqnVariantsLower)
            {
                if (FileMarkers.matchesAtSegmentBoundary(presentation, fqn))
                {
                    kept.add(diagnostic);
                    break;
                }
            }
        }
        return kept;
    }

    /**
     * Narrows a baseline to the frame of a query, so the subtraction happens between two answers
     * to the same question.
     * <p>
     * A query that asks about errors must not count the MINOR findings of the baseline as
     * resolved - they were never in the question. Severity, the check-id substring, the
     * presentation substring and the object set are applied to the baseline records exactly as
     * the reader applies them to the markers it collects.
     * </p>
     *
     * @param diagnostics the baseline records
     * @param severityNames the native severity names the query keeps; <code>null</code> or empty
     *            for no severity filter
     * @param checkIdNeedle the substring a check identity must contain; <code>null</code> or
     *            blank for any
     * @param fileFilterNeedle the substring the object presentation must contain;
     *            <code>null</code> or blank for any
     * @param objectVariantsLower the lowercased FQN variants of the query's object filter;
     *            <code>null</code> or empty for the whole area
     * @return the records that pass every filter, in order
     */
    public static List<Diagnostic> frame(List<Diagnostic> diagnostics, Set<String> severityNames,
        String checkIdNeedle, String fileFilterNeedle, Set<String> objectVariantsLower)
    {
        List<Diagnostic> framed = new ArrayList<>();
        if (diagnostics == null)
        {
            return framed;
        }
        String needle = blankToNull(checkIdNeedle);
        String fileNeedle = blankToNull(fileFilterNeedle);
        for (Diagnostic diagnostic : withinObjects(diagnostics, objectVariantsLower))
        {
            if (severityNames != null && !severityNames.isEmpty()
                && (diagnostic.severity == null
                    || !severityNames.contains(diagnostic.severity.toUpperCase(Locale.ROOT))))
            {
                continue;
            }
            if (needle != null && !containsLower(diagnostic.checkId, needle))
            {
                continue;
            }
            if (fileNeedle != null && !containsLower(diagnostic.objectRef, fileNeedle))
            {
                continue;
            }
            framed.add(diagnostic);
        }
        return framed;
    }

    /**
     * The findings of a list counted per severity name, multiplicities included.
     *
     * @param diagnostics the findings
     * @return severity name to count, in first-appearance order; a <code>null</code> severity
     *         counts under {@code NONE}
     */
    public static Map<String, Integer> countBySeverity(List<Diagnostic> diagnostics)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (diagnostics == null)
        {
            return counts;
        }
        for (Diagnostic diagnostic : diagnostics)
        {
            String name = diagnostic.severity == null ? "NONE" //$NON-NLS-1$
                : diagnostic.severity.toUpperCase(Locale.ROOT);
            counts.merge(name, Integer.valueOf(diagnostic.count), Integer::sum);
        }
        return counts;
    }

    /**
     * @param candidate where to look; may be <code>null</code>
     * @param lowercaseNeedle what to find, already lowercased
     * @return whether the candidate contains the needle, either case
     */
    private static boolean containsLower(String candidate, String lowercaseNeedle)
    {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    /**
     * @param value the value
     * @return the value, or <code>null</code> when it is <code>null</code> or blank
     */
    private static String blankToNull(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * The lock one project's baseline operations run under.
     *
     * @param projectName the project name
     * @return the lock object, the same one for the same name
     */
    private static Object lockFor(String projectName)
    {
        return PROJECT_LOCKS.computeIfAbsent(projectName, key -> new Object());
    }

    /**
     * Replaces the destination with the temporary file: atomically, or with a plain replacing
     * move where the file system has no atomic move.
     *
     * @param temporary the written temporary file
     * @param destination the baseline file
     * @throws IOException when the destination cannot be replaced
     */
    private static void moveIntoPlace(Path temporary, Path destination) throws IOException
    {
        try
        {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException unsupported)
        {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The multiplicities of a list, by identity.
     *
     * @param diagnostics the findings
     * @return identity to total count
     */
    private static Map<String, Long> countsOf(List<Diagnostic> diagnostics)
    {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (diagnostics != null)
        {
            for (Diagnostic diagnostic : diagnostics)
            {
                counts.merge(diagnostic.identity(), Long.valueOf(diagnostic.count), Long::sum);
            }
        }
        return counts;
    }

    /**
     * @param counts the multiplicities
     * @param key the identity
     * @return the count, or zero when the key is absent
     */
    private static long countIn(Map<String, Long> counts, String key)
    {
        Long value = counts.get(key);
        return value == null ? 0L : value.longValue();
    }

    /**
     * The file text of a baseline.
     *
     * @param savedAt the moment of recording
     * @param objects the object FQNs covered
     * @param diagnostics the collapsed records
     * @return the JSON text
     */
    private static String toJson(String savedAt, List<String> objects, List<Diagnostic> diagnostics)
    {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", Integer.valueOf(FORMAT_VERSION)); //$NON-NLS-1$
        root.addProperty("savedAt", savedAt); //$NON-NLS-1$
        JsonArray scope = new JsonArray();
        for (String object : objects)
        {
            scope.add(object);
        }
        root.add("objects", scope); //$NON-NLS-1$
        JsonArray records = new JsonArray();
        for (Diagnostic diagnostic : diagnostics)
        {
            JsonObject record = new JsonObject();
            record.addProperty("object", diagnostic.objectRef); //$NON-NLS-1$
            record.addProperty("check", diagnostic.checkId); //$NON-NLS-1$
            record.addProperty("message", diagnostic.message); //$NON-NLS-1$
            if (diagnostic.severity != null)
            {
                record.addProperty("severity", diagnostic.severity); //$NON-NLS-1$
            }
            record.addProperty("count", Integer.valueOf(diagnostic.count)); //$NON-NLS-1$
            records.add(record);
        }
        root.add("diagnostics", records); //$NON-NLS-1$
        return root.toString();
    }

    /**
     * The baseline of a file's text.
     *
     * @param text the file text
     * @return the baseline
     * @throws UnreadableBaselineException when the text is not this format's JSON
     */
    private static Baseline fromJson(String text) throws UnreadableBaselineException
    {
        JsonElement root;
        try
        {
            root = JsonParser.parseString(text);
        }
        catch (RuntimeException notJson)
        {
            throw new UnreadableBaselineException("the file is not valid JSON"); //$NON-NLS-1$
        }
        if (!root.isJsonObject())
        {
            throw new UnreadableBaselineException("the file is not a JSON object"); //$NON-NLS-1$
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement version = object.get("formatVersion"); //$NON-NLS-1$
        if (version == null || !version.isJsonPrimitive()
            || version.getAsNumber().intValue() != FORMAT_VERSION)
        {
            throw new UnreadableBaselineException("the file carries format version " //$NON-NLS-1$
                + (version == null ? "none" : version.getAsString()) + ", this build reads " //$NON-NLS-1$ //$NON-NLS-2$
                + FORMAT_VERSION);
        }
        JsonElement savedAt = object.get("savedAt"); //$NON-NLS-1$
        if (savedAt == null || !savedAt.isJsonPrimitive())
        {
            throw new UnreadableBaselineException("the file carries no recording moment"); //$NON-NLS-1$
        }
        List<String> scope = new ArrayList<>();
        JsonElement objects = object.get("objects"); //$NON-NLS-1$
        if (objects != null && objects.isJsonArray())
        {
            for (JsonElement element : objects.getAsJsonArray())
            {
                if (element.isJsonPrimitive())
                {
                    scope.add(element.getAsString());
                }
            }
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        JsonElement records = object.get("diagnostics"); //$NON-NLS-1$
        if (records == null || !records.isJsonArray())
        {
            throw new UnreadableBaselineException("the file carries no diagnostics array"); //$NON-NLS-1$
        }
        try
        {
            for (JsonElement element : records.getAsJsonArray())
            {
                JsonObject record = element.getAsJsonObject();
                diagnostics.add(new Diagnostic(
                    record.get("object").getAsString(), //$NON-NLS-1$
                    record.get("check").getAsString(), //$NON-NLS-1$
                    record.get("message").getAsString(), //$NON-NLS-1$
                    record.has("severity") ? record.get("severity").getAsString() : null, //$NON-NLS-1$ //$NON-NLS-2$
                    record.has("count") ? Math.max(1, record.get("count").getAsInt()) : 1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (RuntimeException broken)
        {
            throw new UnreadableBaselineException("a record in the file cannot be read"); //$NON-NLS-1$
        }
        return new Baseline(savedAt.getAsString(), scope, diagnostics);
    }
}
