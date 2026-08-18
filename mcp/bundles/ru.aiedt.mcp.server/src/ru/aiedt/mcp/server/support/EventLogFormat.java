/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the brace format the 1C event log is written in.
 * <p>
 * Both files of a file infobase's log use it: the dictionary ({@code 1Cv8.lgf}) and the records
 * ({@code *.lgp}). A file opens with {@code 1CV8LOG(ver 2.0)} and an identifier, and everything
 * after that is a comma-separated sequence of groups. A group is {@code {}}-delimited and holds
 * values that are, in turn, quoted strings, bare tokens or further groups. Line breaks fall
 * wherever the writer felt like putting them and mean nothing.
 * </p>
 * <p>
 * Kept apart from what the values MEAN on purpose. The shape of the file is a fact that can be
 * checked against a real log; which field is the user and which the session is an interpretation,
 * and mixing the two would make a mistake in the second look like a mistake in the first.
 * </p>
 */
public final class EventLogFormat
{
    /** What a file has to start with to be one of these at all. */
    private static final String SIGNATURE = "1CV8LOG"; //$NON-NLS-1$

    private EventLogFormat()
    {
    }

    /**
     * Whether text looks like an event-log file rather than something else entirely.
     *
     * @param text the file contents.
     * @return true when it carries the signature.
     */
    public static boolean looksLikeLog(String text)
    {
        return text != null && text.stripLeading().startsWith(SIGNATURE);
    }

    /**
     * The version the file declares, for refusing one written in a shape this does not read.
     *
     * @param text the file contents.
     * @return the version as written, e.g. {@code 2.0}, or null when the header says none.
     */
    public static String declaredVersion(String text)
    {
        if (text == null)
        {
            return null;
        }
        int open = text.indexOf('(');
        int close = text.indexOf(')', open + 1);
        if (open < 0 || close < 0 || open > 40)
        {
            return null;
        }
        String header = text.substring(open + 1, close).trim();
        int space = header.lastIndexOf(' ');
        return space < 0 ? header : header.substring(space + 1);
    }

    /**
     * Every top-level group in the file, in the order written.
     * <p>
     * A group comes back as a list whose elements are {@code String} for a scalar and
     * {@code List} for a nested group. A truncated final group - the log is appended to while it
     * is being read - is dropped rather than half-returned: half a record decodes into a record
     * that looks whole.
     * </p>
     *
     * @param text the file contents.
     * @return the groups, empty when there are none.
     */
    public static List<List<Object>> groups(String text)
    {
        List<List<Object>> out = new ArrayList<>();
        if (text == null)
        {
            return out;
        }
        Cursor cursor = new Cursor(text);
        cursor.skipHeader();
        while (true)
        {
            cursor.skipToGroup();
            if (cursor.done())
            {
                return out;
            }
            List<Object> group = cursor.readGroup();
            if (group == null)
            {
                // Truncated tail: stop, keeping everything read so far.
                return out;
            }
            out.add(group);
        }
    }

    /**
     * A group element as text, for the many fields that are a bare number or a quoted name.
     *
     * @param group the group to read from.
     * @param index the position, 0-based.
     * @return the value, or an empty string when the group is shorter than that or holds a group
     *         there.
     */
    public static String text(List<Object> group, int index)
    {
        if (group == null || index < 0 || index >= group.size())
        {
            return ""; //$NON-NLS-1$
        }
        Object value = group.get(index);
        return value instanceof String ? (String)value : ""; //$NON-NLS-1$
    }

    /**
     * A group element as a whole number.
     *
     * @param group the group to read from.
     * @param index the position, 0-based.
     * @return the value, or -1 when it is not a number.
     */
    public static int number(List<Object> group, int index)
    {
        String raw = text(group, index);
        try
        {
            return raw.isEmpty() ? -1 : Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e)
        {
            // A field that should count but does not is reported as absent rather than as zero:
            // zero is a meaningful index in these files.
            return -1;
        }
    }

    /**
     * A group element that is itself a group.
     *
     * @param group the group to read from.
     * @param index the position, 0-based.
     * @return the nested group, or null when there is none there.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> nested(List<Object> group, int index)
    {
        if (group == null || index < 0 || index >= group.size())
        {
            return null;
        }
        Object value = group.get(index);
        return value instanceof List ? (List<Object>)value : null;
    }

    /** Position in the text, with the reading rules of this format. */
    private static final class Cursor
    {
        private final String text;

        private int at;

        Cursor(String text)
        {
            this.text = text;
        }

        boolean done()
        {
            return at >= text.length();
        }

        /** Steps past the signature line and the identifier line that follows it. */
        void skipHeader()
        {
            int firstBrace = text.indexOf('{');
            at = firstBrace < 0 ? text.length() : firstBrace;
        }

        /** Steps forward to the next group opening, over commas and whitespace. */
        void skipToGroup()
        {
            while (at < text.length() && text.charAt(at) != '{')
            {
                at++;
            }
        }

        /**
         * Reads one group, assuming the cursor sits on its opening brace.
         *
         * @return the group, or null when the text ends before the group closes.
         */
        List<Object> readGroup()
        {
            if (done() || text.charAt(at) != '{')
            {
                return null;
            }
            at++;
            List<Object> group = new ArrayList<>();
            Slot slot = new Slot();
            while (at < text.length())
            {
                char ch = text.charAt(at);
                if (ch == '}')
                {
                    at++;
                    slot.flushInto(group, false);
                    return group;
                }
                if (ch == '{')
                {
                    List<Object> child = readGroup();
                    if (child == null)
                    {
                        return null;
                    }
                    slot.group = child;
                    continue;
                }
                if (ch == ',')
                {
                    at++;
                    slot.flushInto(group, true);
                    continue;
                }
                if (ch == '"')
                {
                    String quoted = readQuoted();
                    if (quoted == null)
                    {
                        return null;
                    }
                    slot.token.append(quoted);
                    slot.quoted = true;
                    continue;
                }
                slot.token.append(ch);
                at++;
            }
            // Ran out of text before the group closed.
            return null;
        }

        /**
         * Reads a quoted string, assuming the cursor sits on the opening quote. A doubled quote
         * inside stands for one quote, which is how this format escapes.
         *
         * @return the text between the quotes, or null when the text ends inside it.
         */
        private String readQuoted()
        {
            at++;
            StringBuilder sb = new StringBuilder();
            while (at < text.length())
            {
                char ch = text.charAt(at);
                if (ch == '"')
                {
                    if (at + 1 < text.length() && text.charAt(at + 1) == '"')
                    {
                        sb.append('"');
                        at += 2;
                        continue;
                    }
                    at++;
                    return sb.toString();
                }
                sb.append(ch);
                at++;
            }
            return null;
        }
    }

    /**
     * The value being read between two separators.
     * <p>
     * One slot holds exactly one value, and a value is a nested group OR text - never both. The
     * distinction matters at the separator: a group already read must not be followed by the empty
     * text buffer as a second value, which is how an extra blank field creeps in and shifts every
     * field after it by one.
     * </p>
     */
    private static final class Slot
    {
        /** Text read so far for this value. */
        final StringBuilder token = new StringBuilder();

        /** A nested group read for this value, when the value is one. */
        List<Object> group;

        /** True once a quoted string was read, so an empty one is still a value. */
        boolean quoted;

        /**
         * Puts the value into the group and starts a new one.
         *
         * @param into the group being built.
         * @param atSeparator true at a comma, where an empty slot is still a value; false at the
         *            closing brace, where it is the absence of one.
         */
        void flushInto(List<Object> into, boolean atSeparator)
        {
            if (group != null)
            {
                into.add(group);
            }
            else if (atSeparator || quoted || token.length() > 0)
            {
                into.add(token.toString().trim());
            }
            group = null;
            quoted = false;
            token.setLength(0);
        }
    }
}
