/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * One pinned selection: everything a continuation needs, and nothing that points back at the
 * caller's mutable state.
 * <p>
 * A selection holds the whole collected sequence, not the page that happened to be sent first - a
 * page is a view of this, and pages of one selection are taken from this same list. It also holds
 * the answer to the two questions a later call cannot answer from the page alone: was the
 * collection finished, and does the content still match what the caller was given. The second is
 * the snapshot, a digest over the whole sequence, the project context, the arguments that shaped
 * the collection and an optional fingerprint of the source text.
 * </p>
 * <p>
 * Every string, map and list arriving from the caller is copied through the shared Gson instance.
 * A caller's later mutation of its own argument map or result list therefore cannot alter a
 * selection that has already been pinned, and the page it reads next is the page it was promised.
 * </p>
 */
public final class PinnedResult
{
    private final String operation;

    private final Set<String> projects;

    private final Map<String, Object> arguments;

    private final List<ResultUnit> units;

    private final boolean complete;

    private final String coverage;

    private final String sourceFingerprint;

    private final String snapshot;

    private final long estimatedBytes;

    private PinnedResult(Builder builder)
    {
        this.operation = builder.operation;
        this.projects = Collections.unmodifiableSet(new LinkedHashSet<>(builder.projects));
        this.arguments = copyArguments(builder.arguments);
        this.units = Collections.unmodifiableList(new ArrayList<>(builder.units));
        this.complete = builder.complete;
        this.coverage = builder.coverage == null || builder.coverage.trim().isEmpty()
            ? null : builder.coverage;
        this.sourceFingerprint = builder.sourceFingerprint;
        if (!complete && coverage == null)
        {
            throw new IllegalArgumentException(
                "an incomplete collection has to name its coverage"); //$NON-NLS-1$
        }
        this.snapshot = snapshotOf(operation, projects, complete, coverage, sourceFingerprint,
            arguments, units);
        this.estimatedBytes = estimateMemory(operation, projects, coverage, sourceFingerprint,
            snapshot, arguments, units);
    }

    /**
     * Starts a selection for an operation.
     *
     * @param operation the operation name
     * @return the builder
     */
    public static Builder builder(String operation)
    {
        return new Builder(operation);
    }

    /**
     * A copy of the selection whose enumerated arguments carry their canonical spelling.
     * <p>
     * The snapshot of the copy is computed over the canonical arguments, so the snapshot a
     * continuation envelope carries and the snapshot of a selection rebuilt from those same
     * arguments describe the same call even when the original call spelled a value with padding.
     * The selection handed in is returned unchanged when no enumerated argument needs replacing.
     * Package-private on purpose: the argument profile that says which names are enumerated
     * belongs to the window's request, not to the selection.
     * </p>
     *
     * @param source the selection as the operation built it
     * @param enumerables the enumerated names and the canonical value of each for this call
     * @return the selection with canonical arguments, never {@code null}
     */
    static PinnedResult withCanonicalArguments(PinnedResult source, Map<String, String> enumerables)
    {
        Map<String, Object> canonical = null;
        if (enumerables != null && !enumerables.isEmpty() && !source.arguments.isEmpty())
        {
            for (Map.Entry<String, String> declared : enumerables.entrySet())
            {
                Object current = source.arguments.get(declared.getKey());
                if (current != null && !declared.getValue().equals(String.valueOf(current)))
                {
                    if (canonical == null)
                    {
                        canonical = source.arguments();
                    }
                    canonical.put(declared.getKey(), declared.getValue());
                }
            }
        }
        if (canonical == null)
        {
            return source;
        }
        Builder builder = new Builder(source.operation);
        builder.projects.addAll(source.projects);
        builder.units.addAll(source.units);
        builder.arguments = canonical;
        builder.complete = source.complete;
        builder.coverage = source.coverage;
        builder.sourceFingerprint = source.sourceFingerprint;
        return builder.build();
    }

    /**
     * The operation this selection belongs to.
     *
     * @return the operation name
     */
    public String operation()
    {
        return operation;
    }

    /**
     * The projects the collected content came from.
     * <p>
     * Empty means the selection did not record a project, which happens for a workspace-wide read.
     * Such a selection is dropped when any project closes, because nothing here can prove the
     * closed project was not part of it.
     * </p>
     *
     * @return the project names, never {@code null}
     */
    public Set<String> projects()
    {
        return projects;
    }

    /**
     * The arguments that shaped the collection, as a detached copy.
     *
     * @return the arguments, never {@code null}
     */
    public Map<String, Object> arguments()
    {
        return copyArguments(arguments);
    }

    /**
     * The whole collected sequence.
     *
     * @return the units, in the order the operation built them
     */
    public List<ResultUnit> units()
    {
        return units;
    }

    /**
     * How many units the collection holds.
     *
     * @return the unit count
     */
    public int unitCount()
    {
        return units.size();
    }

    /**
     * Whether the collection ran to its end.
     * <p>
     * A completed collection may still be too large for one answer; the window pages it. An
     * incomplete one was stopped by time, cancellation or an unreadable area, so its count is a
     * lower bound and its continuation may only page what was collected.
     * </p>
     *
     * @return {@code true} when nothing more was left unread by the collector itself
     */
    public boolean complete()
    {
        return complete;
    }

    /**
     * Why the collection is not complete, or {@code null} when it is.
     *
     * @return the reason and the area not covered
     */
    public String coverage()
    {
        return coverage;
    }

    /**
     * The caller's fingerprint of the text the collection was built from, when it supplied one.
     *
     * @return the fingerprint, or {@code null}
     */
    public String sourceFingerprint()
    {
        return sourceFingerprint;
    }

    /**
     * The digest of the whole selection: units, context, arguments and source fingerprint.
     *
     * @return the hex digest
     */
    public String snapshot()
    {
        return snapshot;
    }

    /**
     * The estimated heap cost of everything this selection retains.
     * <p>
     * The account walks the retained structures themselves, not only their serialized text: the
     * argument map with every nested map, list and string, each unit's serialized string and its
     * retained JSON tree, the project names and the wrapper collections around them. Every
     * retained string is counted at forty bytes plus two per character (the worst case of a JVM
     * string whose characters are not compacted to one byte), every map entry at forty plus the
     * container, every list slot at eight, every JSON node at thirty-two to forty-eight.
     * </p>
     * <p>
     * It is an estimate, not a measurement: exact object headers, aligned allocation, duplicate
     * references inside a nested structure and string interning are not modelled, and a real heap
     * may be lower (compact strings) or higher (padding). The per-object allowances are chosen
     * generously so that a structure of many short values - the shape a serialized-only count
     * underestimates the most - is not systematically cheap. It is nevertheless the number the
     * cache caps, because a cap that only counted serialized text would let a wide selection pin
     * several times its budget while looking small.
     * </p>
     *
     * @return the estimated retained bytes
     */
    public long estimatedBytes()
    {
        return estimatedBytes;
    }

    private static String snapshotOf(String operation, Set<String> projects, boolean complete,
        String coverage, String sourceFingerprint, Map<String, Object> arguments,
        List<ResultUnit> units)
    {
        JsonObject root = new JsonObject();
        root.addProperty("operation", operation); //$NON-NLS-1$
        List<String> sortedProjects = new ArrayList<>(projects);
        Collections.sort(sortedProjects);
        JsonArray projectArray = new JsonArray();
        for (String project : sortedProjects)
        {
            projectArray.add(project);
        }
        root.add("projects", projectArray); //$NON-NLS-1$
        root.addProperty("complete", complete); //$NON-NLS-1$
        if (coverage != null)
        {
            root.addProperty("coverage", coverage); //$NON-NLS-1$
        }
        if (sourceFingerprint != null)
        {
            root.addProperty("sourceFingerprint", sourceFingerprint); //$NON-NLS-1$
        }
        root.add("arguments", canonical(arguments)); //$NON-NLS-1$
        JsonArray unitArray = new JsonArray();
        for (ResultUnit unit : units)
        {
            JsonObject one = new JsonObject();
            one.addProperty("kind", unit.kind().name()); //$NON-NLS-1$
            one.add("value", unit.value()); //$NON-NLS-1$
            unitArray.add(one);
        }
        root.add("units", unitArray); //$NON-NLS-1$
        return sha256(GsonHolder.toJson(root));
    }

    private static long estimateMemory(String operation, Set<String> projects, String coverage,
        String sourceFingerprint, String snapshot, Map<String, Object> arguments,
        List<ResultUnit> units)
    {
        long bytes = 64; // the object itself and its reference fields
        bytes += retainedText(operation);
        bytes += retainedText(snapshot);
        bytes += retainedText(coverage);
        bytes += retainedText(sourceFingerprint);
        bytes += 64; // the unmodifiable set over the retained project names
        for (String project : projects)
        {
            bytes += retainedText(project) + 40; // one hash node and its slot
        }
        bytes += 64 + 8L * units.size(); // the unmodifiable list over the units
        bytes += retainedValue(arguments);
        for (ResultUnit unit : units)
        {
            bytes += 40; // the unit object and its fields
            bytes += retainedText(unit.json());
            bytes += retainedTree(unit.value());
        }
        return bytes;
    }

    /**
     * A retained string: its object and its character storage, at the two-byte-per-character worst
     * case.
     *
     * @param text the string, may be {@code null}
     * @return the estimated bytes
     */
    private static long retainedText(String text)
    {
        return text == null ? 0 : 40 + 2L * text.length();
    }

    /**
     * The retained cost of an argument value: the containers themselves and everything nested in
     * them, not only their serialized text.
     *
     * @param value the value, may be {@code null}
     * @return the estimated bytes
     */
    private static long retainedValue(Object value)
    {
        if (value == null)
        {
            return 0;
        }
        if (value instanceof Map)
        {
            Map<?, ?> map = (Map<?, ?>)value;
            long bytes = 48 + 40L * map.size();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                bytes += retainedText(String.valueOf(entry.getKey()))
                    + retainedValue(entry.getValue());
            }
            return bytes;
        }
        if (value instanceof List)
        {
            List<?> list = (List<?>)value;
            long bytes = 40 + 8L * list.size();
            for (Object element : list)
            {
                bytes += retainedValue(element);
            }
            return bytes;
        }
        if (value instanceof String)
        {
            return retainedText((String)value);
        }
        if (value instanceof JsonElement)
        {
            return retainedTree((JsonElement)value);
        }
        return 24; // a boxed number, boolean or character
    }

    /**
     * The retained cost of a JSON tree: every node and every key, because the tree stays reachable
     * from the unit beside the serialized string.
     *
     * @param node the node, never {@code null}
     * @return the estimated bytes
     */
    private static long retainedTree(JsonElement node)
    {
        if (node.isJsonNull())
        {
            return 16;
        }
        if (node.isJsonPrimitive())
        {
            JsonPrimitive primitive = node.getAsJsonPrimitive();
            if (primitive.isString())
            {
                return 32 + retainedText(primitive.getAsString());
            }
            if (primitive.isNumber())
            {
                return 32 + retainedText(primitive.getAsString());
            }
            return 32;
        }
        if (node.isJsonArray())
        {
            JsonArray array = node.getAsJsonArray();
            long bytes = 40 + 8L * array.size();
            for (JsonElement element : array)
            {
                bytes += retainedTree(element);
            }
            return bytes;
        }
        JsonObject object = node.getAsJsonObject();
        long bytes = 48 + 40L * object.size();
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
        {
            bytes += retainedText(entry.getKey()) + retainedTree(entry.getValue());
        }
        return bytes;
    }

    private static String sha256(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte one : digest)
            {
                hex.append(Character.forDigit((one >> 4) & 0xF, 16));
                hex.append(Character.forDigit(one & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException absent)
        {
            // SHA-256 is required of every Java runtime. Failing loudly is right: a snapshot that
            // silently became the text itself would compare unequal across pages for no reason.
            throw new IllegalStateException("SHA-256 is unavailable", absent); //$NON-NLS-1$
        }
    }

    /**
     * A detached copy of an argument map, with nested maps and lists copied as well.
     *
     * @param source the map to copy; may be {@code null}
     * @return the copy, never {@code null}
     */
    static Map<String, Object> copyArguments(Map<String, Object> source)
    {
        if (source == null || source.isEmpty())
        {
            return new LinkedHashMap<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> copy =
            (Map<String, Object>)GsonHolder.fromJson(GsonHolder.toJson(source), Object.class);
        return copy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(copy);
    }

    /**
     * A JSON value with map keys in a fixed order, so the same selection always hashes the same.
     *
     * @param value the value to canonicalize
     * @return the canonical JSON
     */
    static JsonElement canonical(Object value)
    {
        if (value == null)
        {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Map)
        {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet())
            {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            JsonObject object = new JsonObject();
            for (Map.Entry<String, Object> entry : sorted.entrySet())
            {
                object.add(entry.getKey(), canonical(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Iterable)
        {
            JsonArray array = new JsonArray();
            for (Object element : (Iterable<?>)value)
            {
                array.add(canonical(element));
            }
            return array;
        }
        if (value instanceof String)
        {
            return new JsonPrimitive((String)value);
        }
        if (value instanceof Number)
        {
            return new JsonPrimitive((Number)value);
        }
        if (value instanceof Boolean)
        {
            return new JsonPrimitive((Boolean)value);
        }
        if (value instanceof Character)
        {
            return new JsonPrimitive((Character)value);
        }
        if (value instanceof JsonElement)
        {
            return ((JsonElement)value).deepCopy();
        }
        return JsonParser.parseString(GsonHolder.toJson(value));
    }

    /**
     * Collects a pinned selection.
     */
    public static final class Builder
    {
        private final String operation;

        private final Set<String> projects = new LinkedHashSet<>();

        private final List<ResultUnit> units = new ArrayList<>();

        private Map<String, Object> arguments = new LinkedHashMap<>();

        private boolean complete = true;

        private String coverage;

        private String sourceFingerprint;

        private Builder(String operation)
        {
            if (operation == null || operation.trim().isEmpty())
            {
                throw new IllegalArgumentException("a pinned result needs an operation"); //$NON-NLS-1$
            }
            this.operation = operation.trim();
        }

        /**
         * Records the projects the content came from.
         *
         * @param names the project names; may be {@code null}
         * @return this builder
         */
        public Builder projects(Collection<String> names)
        {
            if (names != null)
            {
                for (String name : names)
                {
                    if (name != null && !name.trim().isEmpty())
                    {
                        projects.add(name);
                    }
                }
            }
            return this;
        }

        /**
         * Sets the arguments that shaped the collection.
         *
         * @param values the arguments; may be {@code null}
         * @return this builder
         */
        public Builder arguments(Map<String, Object> values)
        {
            this.arguments = values == null ? new LinkedHashMap<>() : values;
            return this;
        }

        /**
         * Sets the whole collected sequence.
         *
         * @param collected the units, in the operation's order; may be {@code null}
         * @return this builder
         */
        public Builder units(List<ResultUnit> collected)
        {
            this.units.clear();
            if (collected != null)
            {
                this.units.addAll(collected);
            }
            return this;
        }

        /**
         * Declares whether the collector ran to its end.
         *
         * @param finished {@code true} when it did
         * @return this builder
         */
        public Builder complete(boolean finished)
        {
            this.complete = finished;
            return this;
        }

        /**
         * Names why an incomplete collection stopped and what it did not reach.
         *
         * @param reason the reason and the uncovered area
         * @return this builder
         */
        public Builder coverage(String reason)
        {
            this.coverage = reason;
            return this;
        }

        /**
         * Records the caller's fingerprint of the text the collection was built from.
         *
         * @param fingerprint the fingerprint, or {@code null}
         * @return this builder
         */
        public Builder sourceFingerprint(String fingerprint)
        {
            this.sourceFingerprint = fingerprint;
            return this;
        }

        /**
         * Builds the selection and computes its snapshot and memory estimate.
         *
         * @return the selection
         */
        public PinnedResult build()
        {
            return new PinnedResult(this);
        }
    }
}
