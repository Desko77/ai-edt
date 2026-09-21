/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.util.ArrayList;
import java.util.List;

/**
 * The brace text the platform describes a form in - {@code {27,{18,{"ru","..."},...}}} - read
 * into a tree and written back byte for byte.
 *
 * <p>The grammar is small: a list opens with {@code {} and closes with {@code }}, its items are
 * separated by commas; an item is a nested list, a quoted string (a quote inside is doubled and
 * a line break stays as it is), a bare token (a number, a UUID, a word), or a base64 blob -
 * {@code {#base64:...}} - kept verbatim with the {@code \r\r\n} the platform puts between its
 * lines. Whitespace between tokens carries no meaning, so the reader drops it and the writer
 * puts it back by the platform's own rule, which the corpus of a whole configuration confirmed
 * on every form: a CRLF before every {@code {} except the first, and a CRLF before a {@code }}
 * when the last item of the list is itself a list. The text has no trailing line break.</p>
 *
 * <p>A form file stores this text with a UTF-8 BOM; the BOM is not part of the text and is
 * handled by {@link OrdinaryFormFile}.</p>
 */
public final class BraceTree
{
    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String BLOB_OPENER = "{#base64:"; //$NON-NLS-1$

    /** One node of the tree. */
    public interface Node
    {
        // marker
    }

    /** A list of nodes - the only structure the format has. */
    public static final class ListNode implements Node
    {
        private final List<Node> items = new ArrayList<>();

        /** @return the items, mutable - this is how a form is edited */
        public List<Node> items()
        {
            return items;
        }

        /**
         * A shorthand for the item at an index.
         *
         * @param index the index
         * @return the item
         */
        public Node get(int index)
        {
            return items.get(index);
        }

        /** @return how many items the list holds */
        public int size()
        {
            return items.size();
        }
    }

    /** A bare or quoted token, kept as written - the quotes of a string included. */
    public static final class Token implements Node
    {
        private final String text;

        /**
         * Creates a token from its text as written.
         *
         * @param text the text, quotes and doubled quotes included for a string
         */
        public Token(String text)
        {
            this.text = text;
        }

        /** @return the text as written */
        public String text()
        {
            return text;
        }

        /** @return whether the token is a quoted string */
        public boolean isString()
        {
            return text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"';
        }

        /**
         * The value of a quoted string with the doubled quotes undone; a bare token as is.
         *
         * @return the value
         */
        public String value()
        {
            if (!isString())
            {
                return text;
            }
            return text.substring(1, text.length() - 1).replace("\"\"", "\""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * A quoted string token for a value.
         *
         * @param value the value
         * @return the token, quotes doubled inside
         */
        public static Token string(String value)
        {
            return new Token("\"" + value.replace("\"", "\"\"") + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        @Override
        public String toString()
        {
            return text;
        }
    }

    /** A base64 blob - everything between {@code {} and {@code }}, verbatim. */
    public static final class Blob implements Node
    {
        private final String raw;

        /**
         * Creates a blob from the text between its braces.
         *
         * @param raw the text, {@code #base64:} prefix and line breaks included
         */
        public Blob(String raw)
        {
            this.raw = raw;
        }

        /** @return the text between the braces */
        public String raw()
        {
            return raw;
        }
    }

    private BraceTree()
    {
        // static utility
    }

    /**
     * Reads the text into a tree.
     *
     * @param text the brace text, without a BOM
     * @return the root list
     * @throws IllegalArgumentException when the text is not one well-formed list
     */
    public static ListNode parse(String text)
    {
        List<ListNode> stack = new ArrayList<>();
        ListNode holder = new ListNode();
        stack.add(holder);
        int i = 0;
        int n = text.length();
        while (i < n)
        {
            char c = text.charAt(i);
            if (c == '{')
            {
                if (text.startsWith(BLOB_OPENER, i))
                {
                    int close = text.indexOf('}', i);
                    if (close < 0)
                    {
                        throw new IllegalArgumentException("Unclosed blob at " + i); //$NON-NLS-1$
                    }
                    ListNode blobList = new ListNode();
                    blobList.items.add(new Blob(text.substring(i + 1, close)));
                    stack.get(stack.size() - 1).items.add(blobList);
                    i = close + 1;
                }
                else
                {
                    ListNode list = new ListNode();
                    stack.get(stack.size() - 1).items.add(list);
                    stack.add(list);
                    i++;
                }
            }
            else if (c == '}')
            {
                if (stack.size() < 2)
                {
                    throw new IllegalArgumentException("Unbalanced '}' at " + i); //$NON-NLS-1$
                }
                stack.remove(stack.size() - 1);
                i++;
            }
            else if (c == ',' || c == '\r' || c == '\n' || c == ' ' || c == '\t')
            {
                i++;
            }
            else if (c == '"')
            {
                int j = i + 1;
                while (true)
                {
                    j = text.indexOf('"', j);
                    if (j < 0)
                    {
                        throw new IllegalArgumentException("Unclosed string at " + i); //$NON-NLS-1$
                    }
                    if (j + 1 < n && text.charAt(j + 1) == '"')
                    {
                        j += 2;
                        continue;
                    }
                    break;
                }
                stack.get(stack.size() - 1).items.add(new Token(text.substring(i, j + 1)));
                i = j + 1;
            }
            else
            {
                int j = i;
                while (j < n && !isDelimiter(text.charAt(j)))
                {
                    j++;
                }
                stack.get(stack.size() - 1).items.add(new Token(text.substring(i, j)));
                i = j;
            }
        }
        if (stack.size() != 1 || holder.items.size() != 1 || !(holder.items.get(0) instanceof ListNode))
        {
            throw new IllegalArgumentException("The text is not one list: depth " + stack.size() //$NON-NLS-1$
                + ", top-level items " + holder.items.size()); //$NON-NLS-1$
        }
        return (ListNode)holder.items.get(0);
    }

    private static boolean isDelimiter(char c)
    {
        return c == ',' || c == '{' || c == '}' || c == '\r' || c == '\n' || c == ' ' || c == '\t';
    }

    /**
     * Writes a tree as the platform writes it.
     *
     * @param root the root list
     * @return the text, without a BOM and without a trailing line break
     */
    public static String serialize(ListNode root)
    {
        StringBuilder out = new StringBuilder();
        emit(root, out, true);
        return out.toString();
    }

    private static void emit(Node node, StringBuilder out, boolean first)
    {
        if (node instanceof ListNode)
        {
            ListNode list = (ListNode)node;
            if (!first)
            {
                out.append(CRLF);
            }
            out.append('{');
            if (list.items.size() == 1 && list.items.get(0) instanceof Blob)
            {
                out.append(((Blob)list.items.get(0)).raw).append('}');
                return;
            }
            for (int k = 0; k < list.items.size(); k++)
            {
                if (k > 0)
                {
                    out.append(',');
                }
                emit(list.items.get(k), out, false);
            }
            if (!list.items.isEmpty() && list.items.get(list.items.size() - 1) instanceof ListNode)
            {
                out.append(CRLF);
            }
            out.append('}');
        }
        else if (node instanceof Token)
        {
            out.append(((Token)node).text);
        }
        else
        {
            // A blob outside its own list has no place in the format.
            throw new IllegalStateException("A blob must be the only item of its list"); //$NON-NLS-1$
        }
    }
}
