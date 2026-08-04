/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A quick structural sanity check that block openers and closers balance in a BSL module.
 * <p>
 * This is a guard, not a parser. It knows nothing about {@code Then}, {@code Do}, expressions or
 * semicolons; it only counts that every {@code Procedure}, {@code Function}, {@code If}, loop and
 * {@code Try} is eventually closed by its own matching keyword, and that no closer stands alone. It is
 * run right before a module is written, so a module with a stray closer or a missing {@code EndIf}
 * never reaches disk.
 * </p>
 * <p>
 * The scan is deliberately left-to-right within a line. Every keyword found on a line is handed to the
 * stack in the order it appears, so an opener and its own closer on the same line cancel out, while an
 * opener with no closer stays pending. Counting one keyword per line instead - or skipping the push
 * whenever the line also holds the closer - misreads a balanced one-line block as broken and lets an
 * outer block that never closes slip through, so it is not done that way.
 * </p>
 * <p>
 * The keyword names are written as {@code \}{@code uXXXX} escapes to keep this file pure ASCII, so the
 * byte-exact messages the caller re-emits cannot be silently mangled by an editor guessing an encoding.
 * </p>
 * <p>
 * Stateless and thread-safe: every call works on its own locals, and the two compiled patterns are
 * built once and only ever read.
 * </p>
 */
public final class BslSyntaxValidator
{
    /** Tag left on the stack by a {@code Procedure} opener. */
    private static final String PROCEDURE = "PROCEDURE"; //$NON-NLS-1$

    /** Tag left on the stack by a {@code Function} opener. */
    private static final String FUNCTION = "FUNCTION"; //$NON-NLS-1$

    /** Tag left on the stack by an {@code If} opener. */
    private static final String IF = "IF"; //$NON-NLS-1$

    /** Tag left on the stack by a {@code While} or {@code For} opener - both loops close the same way. */
    private static final String LOOP = "LOOP"; //$NON-NLS-1$

    /** Tag left on the stack by a {@code Try} opener. */
    private static final String TRY = "TRY"; //$NON-NLS-1$

    /** A double-quoted run, used to blank string literals before keywords are counted. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"[^\"]*\""); //$NON-NLS-1$

    /**
     * The eleven block keywords, bilingual, as one ordered alternation of named groups.
     * <p>
     * Closers come first so a closer token is never claimed by an opener alternative, and every group
     * is flanked by {@code \b} so a keyword buried inside a longer word - the {@code If} inside
     * {@code ElsIf}, for one - does not open a block of its own.
     * </p>
     */
    private static final Pattern BLOCK_KEYWORD = Pattern.compile(
        "\\b(?:(?<endProc>\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|EndProcedure)" //$NON-NLS-1$
            + "|(?<endFunc>\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndFunction)" //$NON-NLS-1$
            + "|(?<endIf>\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438|EndIf)" //$NON-NLS-1$
            + "|(?<endDo>\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430|EndDo)" //$NON-NLS-1$
            + "|(?<endTry>\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438|EndTry)" //$NON-NLS-1$
            + "|(?<proc>\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|Procedure)" //$NON-NLS-1$
            + "|(?<func>\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)" //$NON-NLS-1$
            + "|(?<ifKw>\u0415\u0441\u043B\u0438|If)" //$NON-NLS-1$
            + "|(?<whileKw>\u041F\u043E\u043A\u0430|While)" //$NON-NLS-1$
            + "|(?<forKw>\u0414\u043B\u044F|For)" //$NON-NLS-1$
            + "|(?<tryKw>\u041F\u043E\u043F\u044B\u0442\u043A\u0430|Try))\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private BslSyntaxValidator()
    {
        // utility
    }

    /**
     * The outcome of a check: whether the module balanced, and the messages when it did not.
     * <p>
     * Both the flag and the list are kept by reference, exactly as they were handed in. The list is
     * shared, not copied - harmless, because each result belongs to a single call.
     * </p>
     */
    public static class CheckResult
    {
        private final boolean valid;

        private final List<String> errors;

        /**
         * @param valid whether the module balanced
         * @param errors the messages; empty when it balanced
         */
        public CheckResult(boolean valid, List<String> errors)
        {
            this.valid = valid;
            this.errors = errors;
        }

        /**
         * @return <code>true</code> when every block balanced
         */
        public boolean isValid()
        {
            return valid;
        }

        /**
         * @return the messages, one per unbalanced block, in the order they were found; empty when the
         *         module balanced
         */
        public List<String> getErrors()
        {
            return errors;
        }
    }

    /**
     * Checks that the block openers and closers in a module balance.
     *
     * @param lines the module, one entry per line; assumed non-<code>null</code> entries. Not modified.
     *            An empty list is fine and always balances
     * @return the outcome: valid with no messages when everything closed, otherwise invalid with a
     *         message for each stray closer, mismatched closer or block left open
     */
    public static CheckResult check(List<String> lines)
    {
        List<String> errors = new ArrayList<>();
        // Two-element entries {tag, openingLineNumber}, most recent on top.
        Deque<String[]> stack = new ArrayDeque<>();

        for (int i = 0; i < lines.size(); i++)
        {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("|")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // Blank, a whole-line comment, or a multiline-string continuation line - nothing to count.
                continue;
            }

            int lineNum = i + 1;

            // Blank the string literals first, so a "//" living inside a string cannot be read as the
            // start of a comment; only then drop a trailing comment.
            String code = STRING_LITERAL.matcher(trimmed).replaceAll(" "); //$NON-NLS-1$
            int commentIdx = code.indexOf("//"); //$NON-NLS-1$
            if (commentIdx >= 0)
            {
                code = code.substring(0, commentIdx);
            }

            Matcher matcher = BLOCK_KEYWORD.matcher(code);
            while (matcher.find())
            {
                dispatch(matcher, lineNum, stack, errors);
            }
        }

        // Whatever is still pending never closed. Draining an ArrayDeque is last-in first-out, so the
        // innermost open block is reported first.
        while (!stack.isEmpty())
        {
            String[] entry = stack.pop();
            errors.add("Block left open: " + tagToKeyword(entry[0]) + ", started at line " + entry[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return new CheckResult(errors.isEmpty(), errors);
    }

    /**
     * Acts on one matched keyword: pushes an opener's tag, or pops and verifies a closer's.
     *
     * @param matcher positioned on a match; exactly one named group is set
     * @param lineNum the 1-based line the match is on
     * @param stack the pending openers
     * @param errors where a stray or mismatched closer is recorded
     */
    private static void dispatch(Matcher matcher, int lineNum, Deque<String[]> stack, List<String> errors)
    {
        if (matcher.group("endProc") != null) //$NON-NLS-1$
        {
            popAndCheck(stack, errors, PROCEDURE,
                "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B/EndProcedure", //$NON-NLS-1$
                lineNum);
        }
        else if (matcher.group("endFunc") != null) //$NON-NLS-1$
        {
            popAndCheck(stack, errors, FUNCTION,
                "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438/EndFunction", lineNum); //$NON-NLS-1$
        }
        else if (matcher.group("endIf") != null) //$NON-NLS-1$
        {
            popAndCheck(stack, errors, IF,
                "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438/EndIf", lineNum); //$NON-NLS-1$
        }
        else if (matcher.group("endDo") != null) //$NON-NLS-1$
        {
            popAndCheck(stack, errors, LOOP,
                "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430/EndDo", lineNum); //$NON-NLS-1$
        }
        else if (matcher.group("endTry") != null) //$NON-NLS-1$
        {
            popAndCheck(stack, errors, TRY,
                "\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438/EndTry", lineNum); //$NON-NLS-1$
        }
        else if (matcher.group("proc") != null) //$NON-NLS-1$
        {
            stack.push(new String[] {PROCEDURE, String.valueOf(lineNum)});
        }
        else if (matcher.group("func") != null) //$NON-NLS-1$
        {
            stack.push(new String[] {FUNCTION, String.valueOf(lineNum)});
        }
        else if (matcher.group("ifKw") != null) //$NON-NLS-1$
        {
            stack.push(new String[] {IF, String.valueOf(lineNum)});
        }
        else if (matcher.group("whileKw") != null || matcher.group("forKw") != null) //$NON-NLS-1$ //$NON-NLS-2$
        {
            stack.push(new String[] {LOOP, String.valueOf(lineNum)});
        }
        else if (matcher.group("tryKw") != null) //$NON-NLS-1$
        {
            stack.push(new String[] {TRY, String.valueOf(lineNum)});
        }
    }

    /**
     * Handles a closing keyword against the top of the stack.
     * <p>
     * An empty stack means the closer has no opener at all; a top with a different tag means the block
     * that is open expected a different closer. Either way the top entry is consumed, never put back.
     * </p>
     *
     * @param stack the pending openers
     * @param errors where the message is recorded
     * @param expectedTag the tag this closer should be closing
     * @param keyword the closer's bilingual name, for the message
     * @param lineNum the 1-based line of the closer
     */
    private static void popAndCheck(Deque<String[]> stack, List<String> errors, String expectedTag,
        String keyword, int lineNum)
    {
        if (stack.isEmpty())
        {
            errors.add("Found an unexpected " + keyword + " on line " + lineNum //$NON-NLS-1$ //$NON-NLS-2$
                + " with no opening keyword to match"); //$NON-NLS-1$
            return;
        }
        String[] top = stack.pop();
        if (!top[0].equals(expectedTag))
        {
            errors.add("Closing keyword does not match: " + keyword + " on line " + lineNum //$NON-NLS-1$ //$NON-NLS-2$
                + ", it should close " + tagToKeyword(top[0]) + " opened on line " + top[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Gives the readable, bilingual opener name for a stack tag.
     *
     * @param tag one of the internal tags
     * @return the opener's name for a message, or the tag itself when it is not one of the five
     */
    private static String tagToKeyword(String tag)
    {
        switch (tag)
        {
            case PROCEDURE:
                return "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430/Procedure"; //$NON-NLS-1$
            case FUNCTION:
                return "\u0424\u0443\u043D\u043A\u0446\u0438\u044F/Function"; //$NON-NLS-1$
            case IF:
                return "\u0415\u0441\u043B\u0438/If"; //$NON-NLS-1$
            case LOOP:
                return "\u041F\u043E\u043A\u0430|\u0414\u043B\u044F / While|For"; //$NON-NLS-1$
            case TRY:
                return "\u041F\u043E\u043F\u044B\u0442\u043A\u0430/Try"; //$NON-NLS-1$
            default:
                return tag;
        }
    }
}
