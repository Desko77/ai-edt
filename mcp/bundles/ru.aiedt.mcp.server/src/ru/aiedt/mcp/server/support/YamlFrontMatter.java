/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The YAML front matter a Markdown tool result opens with, so that an agent can read the outcome of
 * a call without parsing the prose that follows.
 *
 * <pre>
 * return YamlFrontMatter.create()
 *     .put("tool", "write_module_source")
 *     .put("status", "success")
 *     .put("linesAfter", lineCount)
 *     .wrapContent(report);
 * </pre>
 *
 * <p>
 * The emitted block is a wire format, not decoration: {@code edit_metadata} delegates its form
 * operations to the form tool, which answers in Markdown, and then reads the front matter back to
 * recover the outcome as JSON. That reader wants exactly this shape - a bare {@code ---} fence, one
 * {@code key: value} per line, LF endings, no indentation. Changing the shape breaks the caller in a
 * way that surfaces far away, as an internal JSON-RPC error.
 * </p>
 * <p>
 * This is a key/value writer, not a YAML emitter: no nesting, no lists. Fields keep insertion order.
 * Numbers and booleans are written unquoted so they arrive as real YAML scalars; strings are quoted
 * only when leaving them bare would change how YAML reads them.
 * </p>
 */
public final class YamlFrontMatter
{
    /** Fence line, opening and closing. */
    private static final String FENCE = "---\n"; //$NON-NLS-1$

    /** Between a key and its value. */
    private static final String KEY_SEPARATOR = ": "; //$NON-NLS-1$

    /**
     * The characters that give a bare YAML scalar a second meaning: indicators, flow punctuation,
     * quotes and the escape character. Any of them forces the value into quotes.
     */
    private static final Pattern YAML_SPECIAL = Pattern.compile("[:\\#\\[\\]\\{\\},&*?|>@`!%'\"\\\\]"); //$NON-NLS-1$

    /** A value YAML would read as a number rather than as text. */
    private static final Pattern NUMERIC = Pattern.compile("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$"); //$NON-NLS-1$

    /** Words YAML reads as booleans or as the null value, in every spelling it accepts. */
    private static final Set<String> RESERVED_WORDS = Set.of("true", "false", "null", "yes", "no", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "True", "False", "Null", "Yes", "No", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "TRUE", "FALSE", "NULL", "YES", "NO"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private YamlFrontMatter()
    {
        // use create()
    }

    /**
     * Starts an empty block.
     *
     * @return a new builder, never <code>null</code>
     */
    public static YamlFrontMatter create()
    {
        return new YamlFrontMatter();
    }

    /**
     * Adds a text field, or replaces one already added under this key without moving it.
     *
     * @param key the field name; must not be <code>null</code>. It is written as given: no escaping,
     *            no quoting. Keep to plain identifiers
     * @param value the value; <code>null</code> writes an empty scalar
     * @return this builder
     * @throws IllegalArgumentException if the key is <code>null</code>
     */
    public YamlFrontMatter put(String key, String value)
    {
        return putField(key, value);
    }

    /**
     * Adds an int field, written unquoted.
     *
     * @param key the field name; must not be <code>null</code>
     * @param value the value
     * @return this builder
     * @throws IllegalArgumentException if the key is <code>null</code>
     */
    public YamlFrontMatter put(String key, int value)
    {
        return putField(key, Integer.valueOf(value));
    }

    /**
     * Adds a long field, written unquoted.
     *
     * @param key the field name; must not be <code>null</code>
     * @param value the value
     * @return this builder
     * @throws IllegalArgumentException if the key is <code>null</code>
     */
    public YamlFrontMatter put(String key, long value)
    {
        return putField(key, Long.valueOf(value));
    }

    /**
     * Adds a boolean field, written unquoted as {@code true} or {@code false}.
     *
     * @param key the field name; must not be <code>null</code>
     * @param value the value
     * @return this builder
     * @throws IllegalArgumentException if the key is <code>null</code>
     */
    public YamlFrontMatter put(String key, boolean value)
    {
        return putField(key, Boolean.valueOf(value));
    }

    /**
     * Renders the block.
     *
     * @return the fenced block, always ending in a newline; {@code ---\n---\n} when no field was added
     */
    public String build()
    {
        StringBuilder result = new StringBuilder();
        result.append(FENCE);
        for (Map.Entry<String, Object> field : fields.entrySet())
        {
            result.append(field.getKey())
                .append(KEY_SEPARATOR)
                .append(formatValue(field.getValue()))
                .append('\n');
        }
        result.append(FENCE);
        return result.toString();
    }

    /**
     * Renders the block and appends the body to it.
     * <p>
     * Nothing is inserted between the two: the block already ends in a newline, so the body starts on
     * its own line. A <code>null</code> body appends the four characters {@code null} rather than
     * being skipped - no caller passes one, and a guard here would hide the mistake rather than
     * report it.
     * </p>
     *
     * @param markdownBody the Markdown to follow the block
     * @return the block followed by the body
     */
    public String wrapContent(String markdownBody)
    {
        return build() + markdownBody;
    }

    /**
     * Stores one field, keeping the position a key already had.
     *
     * @param key the field name
     * @param value the value, already boxed
     * @return this builder
     */
    private YamlFrontMatter putField(String key, Object value)
    {
        if (key == null)
        {
            throw new IllegalArgumentException("YamlFrontMatter key must not be null"); //$NON-NLS-1$
        }
        fields.put(key, value);
        return this;
    }

    /**
     * Renders one stored value.
     *
     * @param value the value; may be <code>null</code>
     * @return the scalar to write after the key
     */
    private static String formatValue(Object value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long)
        {
            return value.toString();
        }
        return escapeYamlValue(value.toString());
    }

    /**
     * Renders a text value as a YAML scalar, quoting it when leaving it bare would change its meaning.
     * <p>
     * Quoting is forced by: whitespace at either end, which YAML would trim; a character that carries
     * YAML syntax; a word YAML reads as a boolean or as null; an embedded line break; or a value that
     * reads as a number. Everything else - most names, and every forward-slash path - is written as
     * it stands.
     * </p>
     * <p>
     * Package-private rather than private: the tests pin the rule set directly, one case per row.
     * </p>
     *
     * @param value the value; may be <code>null</code>
     * @return the scalar; the empty string for <code>null</code>, and the two-character quoted empty
     *         scalar for the empty string - the block must be able to tell "no value" from "a value
     *         that happens to be empty"
     */
    static String escapeYamlValue(String value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (value.isEmpty())
        {
            return "\"\""; //$NON-NLS-1$
        }
        if (!needsQuoting(value))
        {
            return value;
        }
        String escaped = value.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\r", "\\r"); //$NON-NLS-1$ //$NON-NLS-2$
        return "\"" + escaped + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Decides whether a non-empty value has to be quoted.
     *
     * @param value the value, neither <code>null</code> nor empty
     * @return <code>true</code> when the value must be written in quotes
     */
    private static boolean needsQuoting(String value)
    {
        if (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1)))
        {
            return true;
        }
        if (YAML_SPECIAL.matcher(value).find())
        {
            return true;
        }
        if (RESERVED_WORDS.contains(value))
        {
            return true;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
        {
            return true;
        }
        return NUMERIC.matcher(value).matches();
    }
}
