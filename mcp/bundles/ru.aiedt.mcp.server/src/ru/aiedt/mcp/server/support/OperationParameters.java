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
        if (facadeClass == null || operation == null)
        {
            return Collections.emptyList();
        }
        Map<String, List<String>> map = map();
        List<String> found = map.get(facadeClass + ":" + operation); //$NON-NLS-1$
        return found == null ? Collections.emptyList() : found;
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

    private static Map<String, List<String>> read()
    {
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
                List<String> names = new ArrayList<>();
                for (String name : cells[2].split(",")) //$NON-NLS-1$
                {
                    String trimmed = name.trim();
                    if (!trimmed.isEmpty())
                    {
                        names.add(trimmed);
                    }
                }
                map.put(cells[0] + ":" + cells[1], Collections.unmodifiableList(names)); //$NON-NLS-1$
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
