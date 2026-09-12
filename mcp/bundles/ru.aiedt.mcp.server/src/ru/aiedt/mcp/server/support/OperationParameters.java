/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import ru.aiedt.mcp.server.Activator;

/**
 * Which parameters one facade operation reads, answered from a map derived from the sources.
 * <p>
 * A facade advertises one schema for every operation it accepts, and the largest carries over a
 * hundred parameters of which any single operation reads a handful. Help that answers per operation
 * needs to know which handful, and nothing at runtime does: the operation registry knows a name, a
 * group, a summary and a handler, while the parameters are known only to the handler's code.
 * </p>
 * <p>
 * So the map is worked out from the sources by {@code scripts/check-operation-params.py} and shipped
 * as a resource, with a check in CI that fails when an operation's parameters cannot be established.
 * Deriving it beats maintaining it: a hand-written list is right on the day it is written.
 * </p>
 * <p>
 * A resource rather than generated Java on purpose - generating Java from a script has cost this
 * project two builds, once to a {@code $NON-NLS} marker swallowing a closing bracket and once to
 * text taken out of a Java literal being escaped a second time on the way back in.
 * </p>
 */
public final class OperationParameters
{
    private static final String RESOURCE = "schema/operation-parameters.tsv"; //$NON-NLS-1$

    private static volatile Map<String, List<String>> loaded;

    /** Filled by the same read as {@link #loaded}, and only under its lock. */
    private static final Map<String, List<String>> establishedByKey = new LinkedHashMap<>();

    private OperationParameters()
    {
    }

    /**
     * The parameters an operation reads.
     *
     * @param facadeClass the simple name of the facade class, as the map keys it.
     * @param operation the operation name.
     * @return the parameters, or an empty list when the map has nothing for that pair - which is not
     *         the same as the operation taking none, so callers say "not recorded" rather than "none"
     */
    public static List<String> of(String facadeClass, String operation)
    {
        List<String> declared = withKinds(facadeClass, operation);
        if (declared.isEmpty())
        {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(declared.size());
        for (String entry : declared)
        {
            names.add(nameOf(entry));
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * The parameters an operation reads, each as {@code name:kind}.
     * <p>
     * The kind is read from the schema that declares the parameter - the builder that writes it is
     * the kind - so it is a fact about what the tool advertises rather than a reading of how the
     * value is used. A parameter no class declares carries {@code ?}: 29 of 4411 entries, and
     * writing a plausible kind for those would put an invention where a caller looks for a fact.
     * </p>
     *
     * @param facadeClass the simple name of the facade class, as the map keys it.
     * @param operation the operation name.
     * @return the entries, or an empty list when the map has nothing for that pair
     */
    public static List<String> withKinds(String facadeClass, String operation)
    {
        if (facadeClass == null || operation == null)
        {
            return Collections.emptyList();
        }
        List<String> found = map().get(facadeClass + ":" + operation); //$NON-NLS-1$
        return found == null ? Collections.emptyList() : found;
    }

    /**
     * The name out of a {@code name:kind} entry.
     *
     * @param entry one entry of the map.
     * @return the name alone
     */
    public static String nameOf(String entry)
    {
        if (entry == null)
        {
            return null;
        }
        int colon = entry.lastIndexOf(':');
        return colon < 0 ? entry : entry.substring(0, colon);
    }

    /**
     * The kind out of a {@code name:kind} entry.
     *
     * @param entry one entry of the map.
     * @return the kind, or <code>null</code> when the entry carries none
     */
    public static String kindOf(String entry)
    {
        if (entry == null)
        {
            return null;
        }
        int colon = entry.lastIndexOf(':');
        return colon < 0 ? null : entry.substring(colon + 1);
    }

    /** True when the map was packaged and read, so an empty answer can be told from a missing map. */
    public static boolean available()
    {
        return !map().isEmpty();
    }

    private static Map<String, List<String>> map()
    {
        Map<String, List<String>> current = loaded;
        if (current != null)
        {
            return current;
        }
        synchronized (OperationParameters.class)
        {
            if (loaded == null)
            {
                loaded = read();
            }
            return loaded;
        }
    }

    /**
     * The parameters established as this operation's own, without the ones the facade reads for
     * every operation it has.
     * <p>
     * What per-operation help may print. The wider set {@link #of} returns is right for refusing a
     * call over an argument nobody reads, and wrong for telling a caller what one operation takes:
     * a facade that resolves its subject before dispatching reads those arguments on every call,
     * and listing them under one operation says that operation takes them.
     * </p>
     * <p>
     * Narrow rather than exact. The derivation follows one level into the handler, so an argument
     * read deeper lands in the wider set instead - help prints both, labelled, so nothing is hidden
     * and nothing is attributed to an operation that does not take it.
     * </p>
     *
     * @param facadeClass the simple name of the facade class, as the map keys it.
     * @param operation the operation name.
     * @return the established parameters as {@code name:kind}, or an empty list
     */
    public static List<String> establishedFor(String facadeClass, String operation)
    {
        map();
        List<String> found = establishedByKey.get(facadeClass + ":" + operation); //$NON-NLS-1$
        return found == null ? Collections.emptyList() : found;
    }

    private static List<String> entriesOf(String cell)
    {
        List<String> out = new ArrayList<>();
        for (String entry : cell.split(",")) //$NON-NLS-1$
        {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty())
            {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Map<String, List<String>> read()
    {
        establishedByKey.clear();
        Map<String, List<String>> map = new LinkedHashMap<>();
        Bundle bundle = FrameworkUtil.getBundle(OperationParameters.class);
        if (bundle == null)
        {
            return map;
        }
        URL url = bundle.getEntry(RESOURCE);
        if (url == null)
        {
            return map;
        }
        try (InputStream stream = url.openStream())
        {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) //$NON-NLS-1$
            {
                String row = line.trim();
                if (row.isEmpty() || row.startsWith("#")) //$NON-NLS-1$
                {
                    continue;
                }
                String[] cells = row.split("\t", -1); //$NON-NLS-1$
                if (cells.length < 3)
                {
                    continue;
                }
                List<String> established = entriesOf(cells[2]);
                List<String> wide = cells.length >= 5 ? entriesOf(cells[4]) : new ArrayList<>();
                String key = cells[0] + ":" + cells[1]; //$NON-NLS-1$
                // Both columns together are what the map used to hold in one, and what
                // UnreadArguments must keep seeing: it refuses a call over an argument nobody
                // reads, so a set short of the truth turns away calls that work.
                //
                // Sorted and deduplicated, so the answer is the one column that was here before
                // down to its order. Concatenating returned a name twice when both columns held
                // it, and put the established ones first instead of leaving the whole sorted.
                Set<String> together = new TreeSet<>(established);
                together.addAll(wide);
                map.put(key, Collections.unmodifiableList(new ArrayList<>(together)));
                establishedByKey.put(key, Collections.unmodifiableList(established));
            }
        }
        catch (Exception cannotRead)
        {
            // An absent map degrades help to what it said before; it must not take a call down.
            Activator.logError("Operation parameter map could not be read", cannotRead); //$NON-NLS-1$
            return new LinkedHashMap<>();
        }
        return map;
    }
}
