/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * What an operation declares about its arguments so a continuation can be rebuilt, or honestly
 * refused, after the pinned selection is gone.
 * <p>
 * The classification is deliberately explicit, not derived from a schema. An MCP schema does not
 * publish enums, so an operation's enumerated {@code intent} looks exactly like an arbitrary
 * {@code scope}; reading the schema would guess wrong in both directions. The operation that parsed
 * the argument already knows the answer, and states it here:
 * </p>
 * <ul>
 * <li>numbers and booleans are always kept - they are bounded by construction and a caller needs
 * them to reproduce the call;</li>
 * <li>an enumerated value is kept in the canonical spelling the operation itself resolved, so a
 * value padded with thousands of spaces costs the size of the canonical value, not of the
 * padding;</li>
 * <li>every other string, array or object is a candidate for elision, with no exception for a
 * short one: the contract is that only values that do not fit the envelope budget are removed;</li>
 * <li>a name the operation declares required by its tool schema is emptied to a value of its own
 * kind when elided, because a continuation with the key missing would fail the client's schema
 * check before it ever reached the server.</li>
 * </ul>
 * <p>
 * Whether the call changes state is likewise declared, never inferred from a key such as
 * {@code apply}: the window must not run a changing operation as a probe after its selection is
 * gone.
 * </p>
 */
public final class ResultArgumentProfile
{
    private final String operation;

    private final boolean changesState;

    private final Set<String> alwaysKept;

    private final Map<String, String> enumerables;

    private final Set<String> required;

    private ResultArgumentProfile(Builder builder)
    {
        this.operation = builder.operation;
        this.changesState = builder.changesState;
        this.alwaysKept = Collections.unmodifiableSet(new LinkedHashSet<>(builder.alwaysKept));
        this.enumerables = Collections.unmodifiableMap(new LinkedHashMap<>(builder.enumerables));
        this.required = Collections.unmodifiableSet(new LinkedHashSet<>(builder.required));
    }

    /**
     * Starts a profile for an operation.
     *
     * @param operation the operation name, used in the snapshot and in refusal messages
     * @return the builder
     */
    public static Builder builder(String operation)
    {
        return new Builder(operation);
    }

    /**
     * The operation this profile describes.
     *
     * @return the operation name
     */
    public String operation()
    {
        return operation;
    }

    /**
     * Whether the call this profile describes changes the project or an infobase.
     *
     * @return {@code true} when a continuation must never re-execute the call
     */
    public boolean changesState()
    {
        return changesState;
    }

    /**
     * The names explicitly kept regardless of their value's kind.
     *
     * @return the names, never {@code null}
     */
    public Set<String> alwaysKept()
    {
        return alwaysKept;
    }

    /**
     * The enumerated arguments and the canonical value of each.
     *
     * @return the map, never {@code null}
     */
    public Map<String, String> enumerables()
    {
        return enumerables;
    }

    /**
     * The names the operation's tool schema declares required.
     *
     * @return the names, never {@code null}
     */
    public Set<String> required()
    {
        return required;
    }

    /**
     * Whether an argument is kept whatever its value.
     * <p>
     * A number or a boolean is kept by its kind. The profile also keeps a declared name even when
     * the received value is a string, which is the normal shape of an MCP argument: the value was
     * parsed by the operation and only the text survived, and eliding {@code offset} or
     * {@code sectionsOffset} would make the continuation a different call.
     * </p>
     *
     * @param name the argument name
     * @param value its value
     * @return {@code true} when the argument must stay in the continuation
     */
    public boolean isKept(String name, Object value)
    {
        return value instanceof Number || value instanceof Boolean
            || alwaysKept.contains(name) || enumerables.containsKey(name);
    }

    /**
     * The canonical spelling of an enumerated argument, or the original value when the name is not
     * enumerated.
     *
     * @param name the argument name
     * @param original the value as the call carried it
     * @return the value the continuation has to carry
     */
    public Object canonicalValue(String name, Object original)
    {
        String canonical = enumerables.get(name);
        return canonical == null ? original : canonical;
    }

    /**
     * The package-private helper that empties an elided required argument without changing its
     * JSON kind.
     *
     * @param value the original value
     * @return the empty value of the same shape, or {@code null} when no such value exists
     */
    static Object emptyValueOf(Object value)
    {
        if (value instanceof String)
        {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof List)
        {
            return new ArrayList<>();
        }
        if (value instanceof Map)
        {
            return new LinkedHashMap<>();
        }
        return null;
    }

    /**
     * Orders the arguments a continuation may drop: longest serialized value first, then by name.
     * <p>
     * The length is the serialized JSON length, not the raw string length, and there is no
     * threshold below which a value is considered short. Ties are broken by name so two calls with
     * the same arguments always drop the same ones - a continuation that is not deterministic is a
     * continuation that cannot be reasoned about after the cache is gone.
     * </p>
     *
     * @param arguments the full argument map, already canonicalized
     * @param reserved names the window itself writes and therefore never elides
     * @return the candidate names in dropping order
     */
    List<String> elisionOrder(Map<String, Object> arguments, Set<String> reserved)
    {
        List<String> candidates = new ArrayList<>();
        for (String name : arguments.keySet())
        {
            if (reserved.contains(name) || isKept(name, arguments.get(name)))
            {
                continue;
            }
            candidates.add(name);
        }
        candidates.sort(Comparator
            .comparingInt((String name) -> GsonHolder.toJson(arguments.get(name)).length())
            .reversed()
            .thenComparing(Comparator.naturalOrder()));
        return candidates;
    }

    /**
     * Collects what an operation declares about its arguments.
     */
    public static final class Builder
    {
        private final String operation;

        private final Set<String> alwaysKept = new LinkedHashSet<>();

        private final Map<String, String> enumerables = new LinkedHashMap<>();

        private final Set<String> required = new LinkedHashSet<>();

        private boolean changesState;

        private Builder(String operation)
        {
            if (operation == null || operation.trim().isEmpty())
            {
                throw new IllegalArgumentException("a result argument profile needs an operation"); //$NON-NLS-1$
            }
            this.operation = operation.trim();
        }

        /**
         * Declares whether the call changes the project or an infobase.
         *
         * @param changes {@code true} when a continuation must not re-execute it
         * @return this builder
         */
        public Builder changesState(boolean changes)
        {
            this.changesState = changes;
            return this;
        }

        /**
         * Declares argument names kept whatever their received JSON kind.
         *
         * @param names the names
         * @return this builder
         */
        public Builder alwaysKept(String... names)
        {
            if (names != null)
            {
                for (String name : names)
                {
                    if (name != null && !name.trim().isEmpty())
                    {
                        alwaysKept.add(name.trim());
                    }
                }
            }
            return this;
        }

        /**
         * Declares an enumerated argument and the canonical spelling the operation resolved.
         *
         * @param name the argument name
         * @param canonicalValue the canonical spelling, never {@code null}
         * @return this builder
         */
        public Builder enumerable(String name, String canonicalValue)
        {
            if (name == null || name.trim().isEmpty() || canonicalValue == null)
            {
                throw new IllegalArgumentException("an enumerable needs a name and a canonical value"); //$NON-NLS-1$
            }
            enumerables.put(name.trim(), canonicalValue);
            return this;
        }

        /**
         * Declares the argument names the tool schema declares required.
         *
         * @param names the names
         * @return this builder
         */
        public Builder required(String... names)
        {
            if (names != null)
            {
                for (String name : names)
                {
                    if (name != null && !name.trim().isEmpty())
                    {
                        required.add(name.trim());
                    }
                }
            }
            return this;
        }

        /**
         * Builds the profile.
         *
         * @return the profile
         */
        public ResultArgumentProfile build()
        {
            return new ResultArgumentProfile(this);
        }
    }
}
