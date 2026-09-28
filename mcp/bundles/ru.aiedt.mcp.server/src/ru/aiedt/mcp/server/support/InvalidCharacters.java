/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Characters that arrive inside text written by a person or a model but cannot stand in BSL source,
 * and what each of them becomes.
 * <p>
 * A dash typed as a long dash, a minus sign copied from a formula, a non-breaking space taken from a
 * table, a soft hyphen left by a word processor: each of them looks like the character it stands
 * for and is not that character. The module compiles, the names in it differ from the names a search
 * asks for, and the difference is invisible in every editor. No check of the platform names it
 * either.
 * </p>
 * <p>
 * Only the characters with an obvious one-character equivalent are replaced. A long dash becomes a
 * hyphen because a reader takes both for the same separator, a non-breaking space becomes a space,
 * and a soft hyphen - which BSL has no use for - is dropped. Nothing else in the text is touched: a
 * quotation mark, a letter of the other alphabet and a curly brace are passed through, since
 * replacing them would be a guess about intent rather than transcription.
 * </p>
 * <p>
 * A string literal is data, and data is not rewritten. The characters inside a BSL literal -
 * between its opening quote and its closing one, an embedded {@code ""} being an escaped quote and
 * a literal that runs past the end of a line continuing on every following line that starts with
 * {@code |} - stand exactly as the caller wrote them: a no-break space inside a format string is the
 * group separator the code means, not a typo. The characters in code and in a {@code //} comment are
 * the ones replaced.
 * </p>
 */
public final class InvalidCharacters
{
    /** How many positions a report lists before it stops, and says how many it left out. */
    private static final int POSITION_LIMIT = 50;

    /** The dashes and the minus sign, every one of which becomes a hyphen. */
    private static final char[] DASHES = { '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' };

    /** The non-breaking space, which becomes a plain space. */
    private static final char NO_BREAK_SPACE = '\u00A0';

    /** The soft hyphen, which is dropped. */
    private static final char SOFT_HYPHEN = '\u00AD';

    private InvalidCharacters()
    {
        // utility
    }

    /**
     * What one pass over a piece of text changed.
     */
    public static final class Report
    {
        /** The text to write. Equal to the input when nothing was replaced. */
        public String text;

        /** How many characters were replaced or dropped. */
        public int count;

        /**
         * Where they stood, as {@code line:column}, the first {@link #POSITION_LIMIT} of them.
         * <p>
         * Lines and columns count from the start of the text this pass was handed - the fragment the
         * caller supplied, not the module file the fragment is bound for - and a column counts in
         * UTF-16 units, the units {@code String} addresses.
         * </p>
         */
        public final List<String> positions = new ArrayList<>();

        /** Whether the position list was cut short. */
        public boolean positionsTruncated;

        /** How many of each kind were changed, keyed by the phrase that describes the change. */
        public final Map<String, Integer> kinds = new LinkedHashMap<>();

        /**
         * Whether this pass changed anything.
         *
         * @return <code>true</code> when at least one character was replaced or dropped
         */
        public boolean changed()
        {
            return count > 0;
        }

        /**
         * Folds another pass into this one, so that a caller writing several pieces of text answers
         * with one count and one list of positions.
         * <p>
         * A position belongs to the piece it was measured in, so the piece is named next to it.
         * Without that label a position would read as a place in the finished text, where the piece
         * it came from is somewhere else.
         * </p>
         *
         * @param part the piece the other pass measured, or <code>null</code> when the text is the
         *        whole of what is written
         * @param other the pass to fold in; <code>null</code> and an unchanged pass add nothing
         */
        public void merge(String part, Report other)
        {
            if (other == null || !other.changed())
            {
                return;
            }
            count += other.count;
            for (Map.Entry<String, Integer> kind : other.kinds.entrySet())
            {
                kinds.merge(kind.getKey(), kind.getValue(),
                    (was, one) -> Integer.valueOf(was.intValue() + one.intValue()));
            }
            for (String position : other.positions)
            {
                if (positions.size() >= POSITION_LIMIT)
                {
                    positionsTruncated = true;
                    break;
                }
                positions.add(part == null || part.isEmpty() ? position : part + " " + position); //$NON-NLS-1$
            }
            positionsTruncated = positionsTruncated || other.positionsTruncated;
        }

        /**
         * One line naming what was changed, for a tool response.
         *
         * @return the summary, or an empty string when nothing was changed
         */
        public String describe()
        {
            if (count == 0)
            {
                return ""; //$NON-NLS-1$
            }
            StringBuilder sb = new StringBuilder();
            sb.append(count).append(count == 1 ? " character was" : " characters were") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" replaced with their BSL equivalents: "); //$NON-NLS-1$
            boolean first = true;
            for (Map.Entry<String, Integer> kind : kinds.entrySet())
            {
                if (!first)
                {
                    sb.append("; "); //$NON-NLS-1$
                }
                sb.append(kind.getKey()).append(" x").append(kind.getValue()); //$NON-NLS-1$
                first = false;
            }
            return sb.toString();
        }

        /**
         * The positions as one comma-separated line, for a tool response.
         *
         * @return the positions, or an empty string when nothing was changed
         */
        public String positionsAsText()
        {
            String joined = String.join(", ", positions); //$NON-NLS-1$
            return positionsTruncated ? joined + " (first " + POSITION_LIMIT + ")" : joined; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Replaces the characters BSL source cannot hold with the ones they stand for, everywhere except
     * inside string literals.
     * <p>
     * The pass reads the text as BSL is read: a {@code "} opens a literal that only a quote closes -
     * an embedded {@code ""} is an escaped quote, not a closing one, and a literal may run over
     * several lines whose first non-blank character is {@code |} - and a {@code //} outside a
     * literal comments to the end of the line. Inside a literal nothing is touched; in code and in a
     * comment the replacement runs as it always did. Text that carries none of the characters comes
     * back unchanged, and the report says so.
     * </p>
     *
     * @param source the text about to be written; may be <code>null</code>
     * @return what to write and what was changed; never <code>null</code>
     */
    public static Report normalize(String source)
    {
        Report report = new Report();
        report.text = source;
        if (source == null || source.isEmpty())
        {
            return report;
        }
        Scanner scanner = new Scanner(source);
        StringBuilder out = null;
        while (scanner.hasNext())
        {
            char c = scanner.peek();
            if (scanner.insideLiteral())
            {
                // Data: an escaped quote is consumed as a pair, any other character of the literal
                // stands as written, and the closing quote ends it.
                if (c == '"' && scanner.peekNext() == '"')
                {
                    out = appendRaw(appendRaw(out, c), '"');
                    scanner.step();
                    scanner.step();
                    continue;
                }
                if (c == '"')
                {
                    scanner.closeLiteral();
                }
                out = appendRaw(out, c);
                scanner.step();
                continue;
            }
            if (scanner.insideComment())
            {
                if (c == '\n')
                {
                    scanner.endComment();
                }
                // A comment is written by a person and is rewritten like code.
                out = replace(c, scanner, out, report);
                continue;
            }
            if (c == '"')
            {
                scanner.openLiteral();
                out = appendRaw(out, c);
                scanner.step();
                continue;
            }
            if (c == '/' && scanner.peekNext() == '/')
            {
                scanner.startComment();
                out = appendRaw(out, c);
                scanner.step();
                continue;
            }
            out = replace(c, scanner, out, report);
        }
        if (out != null)
        {
            report.text = out.toString();
        }
        return report;
    }

    /**
     * Applies the replacement rules to one character of code or comment.
     *
     * @param c the character at the scanner's position
     * @param scanner the scanner, positioned at the character; consumes it
     * @param out the text built so far, or <code>null</code> while nothing has changed
     * @param report the report the replacement records into
     * @return the text built so far
     */
    private static StringBuilder replace(char c, Scanner scanner, StringBuilder out, Report report)
    {
        char replacement;
        boolean dropped = false;
        if (isDash(c))
        {
            replacement = '-';
        }
        else if (c == NO_BREAK_SPACE)
        {
            replacement = ' ';
        }
        else if (c == SOFT_HYPHEN)
        {
            replacement = ' '; // unused: the character is dropped, not replaced
            dropped = true;
        }
        else
        {
            scanner.step();
            return appendRaw(out, c);
        }
        if (out == null)
        {
            // The first change is where the untouched prefix is copied; everything before it is
            // the text it already was.
            out = new StringBuilder(scanner.textLength());
            out.append(scanner.text(), 0, scanner.position());
        }
        if (!dropped)
        {
            out.append(replacement);
        }
        report.count++;
        String kind = nameOf(c) + " -> " + (dropped ? "removed" : "'" + replacement + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        report.kinds.merge(kind, Integer.valueOf(1),
            (was, one) -> Integer.valueOf(was.intValue() + 1));
        if (report.positions.size() < POSITION_LIMIT)
        {
            report.positions.add(scanner.line() + ":" + scanner.column()); //$NON-NLS-1$
        }
        else
        {
            report.positionsTruncated = true;
        }
        // A dropped character takes no room: the column after it is the one it stood at, and a
        // replaced one advances by the single character that took its place.
        if (dropped)
        {
            scanner.stepLeavingNoRoom();
        }
        else
        {
            scanner.step();
        }
        return out;
    }

    /**
     * Copies one unchanged character into the text being built.
     *
     * @param out the text built so far, or <code>null</code> while nothing has changed
     * @param c the character to copy
     * @return the text built so far
     */
    private static StringBuilder appendRaw(StringBuilder out, char c)
    {
        if (out != null)
        {
            out.append(c);
        }
        return out;
    }

    /**
     * One pass over the text that knows where it stands: which line and column the next character
     * sits at, and whether that character is inside a string literal or a comment.
     * <p>
     * The literal rules are the ones the BSL reader applies. A quote opens a literal; inside, a
     * doubled quote is one escaped quote and any single quote closes the literal. A literal may span
     * lines - its continuation lines start with {@code |} after optional blanks, and everything up
     * to the closing quote belongs to it, a newline included. A comment starts with {@code //}
     * outside a literal and ends at the newline.
     * </p>
     */
    private static final class Scanner
    {
        private final String text;

        private int at;

        private int line = 1;

        private int column = 1;

        private boolean literal;

        private boolean comment;

        /**
         * Starts at the beginning of the text.
         *
         * @param text the text to walk
         */
        Scanner(String text)
        {
            this.text = text;
        }

        /**
         * @return whether any character is left
         */
        boolean hasNext()
        {
            return at < text.length();
        }

        /**
         * @return the character at the current position
         */
        char peek()
        {
            return text.charAt(at);
        }

        /**
         * @return the character after the current position, or {@code '\0'} at the end
         */
        char peekNext()
        {
            return at + 1 < text.length() ? text.charAt(at + 1) : '\0';
        }

        /**
         * Consumes one character, counting the line and column it leaves the scanner at.
         */
        void step()
        {
            char c = text.charAt(at);
            at++;
            if (c == '\n')
            {
                line++;
                column = 1;
            }
            else
            {
                column++;
            }
        }

        /**
         * Consumes one character that leaves no room in the line: the next character's column is the
         * one this character stood at. A dropped character is never a newline.
         */
        void stepLeavingNoRoom()
        {
            at++;
        }

        /** Marks the scanner as inside a literal. */
        void openLiteral()
        {
            literal = true;
        }

        /** Marks the literal as closed. */
        void closeLiteral()
        {
            literal = false;
        }

        /**
         * @return whether the scanner stands inside a string literal
         */
        boolean insideLiteral()
        {
            return literal;
        }

        /** Marks the scanner as inside a comment. */
        void startComment()
        {
            comment = true;
        }

        /** Marks the comment as ended. */
        void endComment()
        {
            comment = false;
        }

        /**
         * @return whether the scanner stands inside a comment
         */
        boolean insideComment()
        {
            return comment;
        }

        /**
         * @return the line the current character sits on, counted from one
         */
        int line()
        {
            return line;
        }

        /**
         * @return the column the current character sits at, counted from one
         */
        int column()
        {
            return column;
        }

        /**
         * @return the position of the current character
         */
        int position()
        {
            return at;
        }

        /**
         * @return the whole text being walked
         */
        String text()
        {
            return text;
        }

        /**
         * @return the length of the text being walked
         */
        int textLength()
        {
            return text.length();
        }
    }

    /**
     * Whether a character is one of the dashes a person or a model types in place of a hyphen.
     *
     * @param c the character
     * @return <code>true</code> for the figure dash, en dash, em dash, horizontal bar and minus sign
     */
    private static boolean isDash(char c)
    {
        for (char dash : DASHES)
        {
            if (c == dash)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Names a character by its Unicode name and code point.
     *
     * @param c the character
     * @return the name, never <code>null</code>
     */
    private static String nameOf(char c)
    {
        String name;
        switch (c)
        {
            case '\u2012':
                name = "figure dash"; //$NON-NLS-1$
                break;
            case '\u2013':
                name = "en dash"; //$NON-NLS-1$
                break;
            case '\u2014':
                name = "em dash"; //$NON-NLS-1$
                break;
            case '\u2015':
                name = "horizontal bar"; //$NON-NLS-1$
                break;
            case '\u2212':
                name = "minus sign"; //$NON-NLS-1$
                break;
            case NO_BREAK_SPACE:
                name = "no-break space"; //$NON-NLS-1$
                break;
            case SOFT_HYPHEN:
                name = "soft hyphen"; //$NON-NLS-1$
                break;
            default:
                name = "character"; //$NON-NLS-1$
                break;
        }
        return name + " (U+" + String.format("%04X", Integer.valueOf(c)) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
