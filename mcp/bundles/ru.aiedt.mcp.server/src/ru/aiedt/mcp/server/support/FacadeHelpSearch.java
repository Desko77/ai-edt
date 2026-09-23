/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import ru.aiedt.mcp.server.wire.JsonUtils;

/**
 * Search across a facade's own help, and the nearest names for a refusal.
 * <p>
 * A facade's help answers two questions a caller has: what the facade can do (the catalog, help
 * without a topic) and what one part of it takes (a topic). Finding WHERE something is said was a
 * third question with no answer shorter than reading both. {@code operation=help find=<word>}
 * answers it with the pieces of the help that carry the word, each labeled with the topic to ask
 * for when the whole section is wanted.
 * </p>
 * <p>
 * The search runs over the help as the facade renders it, so a hand-written topic and one built
 * from a schema are searched by the same rule: the facade hands over its topics and its own help
 * function, and the logic lives here once.
 * </p>
 */
public final class FacadeHelpSearch
{
    /**
     * The one sentence every facade's schema says about {@code find}.
     * <p>
     * Eleven facades declare the argument and each wrote the rule in its own words until the
     * differences became the documentation. One sentence, shared, so the rule is stated once and
     * weighed once: the parameter-prose budget counts every copy.
     * </p>
     */
    public static final String FIND_DESCRIPTION =
        "With help: search this help for a case-insensitive substring - all the words must appear " //$NON-NLS-1$
            + "inside one chunk, and with topic only that topic is searched."; //$NON-NLS-1$

    /** How many matching chunks one answer shows; the rest is reported as the count alone. */
    private static final int SHOWN = 10;

    /** How many near names a refusal suggests. */
    private static final int CLOSEST = 3;

    /**
     * How a chunk of the catalog is labelled: the catalog has no topic of its own to be named by,
     * so it is named by what a caller asks for to receive it whole.
     */
    public static final String CATALOG_ORIGIN = "operation=help with no topic"; //$NON-NLS-1$

    private FacadeHelpSearch()
    {
    }

    /**
     * The chunks of a facade's help that carry every word of the query.
     * <p>
     * A chunk is a piece of the help the caller can ask for on its own: the catalog and a named
     * topic are documents of sections, so a chunk of them is one section, and a topic that names
     * an operation is that operation's parameters, so a chunk of it is one argument's description.
     * Which of the two applies is decided by what was asked and never by the shape of the text,
     * because one facade draws a section of subsections and the next draws arguments as headings
     * and the one after that as bullets.
     * </p>
     *
     * @param facade the facade's wire name, for the heading.
     * @param query what to look for; the caller refuses a blank one.
     * @param topic confine the search to this one topic; <code>null</code> or blank for all of it.
     * @param topics every topic of the facade, in the order the catalog names them.
     * @param operations every operation the facade dispatches; a topic that names one of them is
     *            searched as an argument list.
     * @param helpOf renders one topic (<code>null</code> for the catalog), as the facade itself
     *            does.
     * @return markdown: the count and the first chunks with where each came from, or what can be
     *         asked instead when nothing matched - not an error either way
     */
    public static String search(String facade, String query, String topic, List<String> topics,
        Collection<String> operations, Function<String, String> helpOf)
    {
        List<String> words = wordsOf(query);
        String scoped = topic == null || topic.isBlank() ? null : topic.trim();
        List<String> origins = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        List<String> sections = new ArrayList<>();
        if (scoped != null)
        {
            String text = helpOf.apply(scoped);
            if (text != null && text.startsWith("# Unknown topic")) //$NON-NLS-1$
            {
                // The refusal already says what can be asked; searching its text would find
                // nothing and read as if the topic existed but carried no match.
                return text;
            }
            collect(text, "topic=" + scoped, isOperation(operations, scoped), words, origins, //$NON-NLS-1$
                chunks);
        }
        else
        {
            String catalog = helpOf.apply(null);
            collect(catalog, CATALOG_ORIGIN, false, words, origins, chunks);
            if (topics.isEmpty())
            {
                sections = sectionNames(catalog);
            }
            for (String named : topics)
            {
                collect(helpOf.apply(named), "topic=" + named, isOperation(operations, named), //$NON-NLS-1$
                    words, origins, chunks);
            }
        }

        StringBuilder answer = new StringBuilder("# ").append(facade).append(" - find ") //$NON-NLS-1$ //$NON-NLS-2$
            .append('"').append(query.trim()).append('"');
        if (scoped != null)
        {
            answer.append(" in topic=").append(scoped); //$NON-NLS-1$
        }
        answer.append("\n\n"); //$NON-NLS-1$
        if (chunks.isEmpty())
        {
            answer.append("Nothing matches. The match is a plain substring with no word forms ") //$NON-NLS-1$
                .append("behind it - search by the stem of the word.\n"); //$NON-NLS-1$
            if (!topics.isEmpty())
            {
                answer.append("\nAsk for a whole topic with topic=<name>: ") //$NON-NLS-1$
                    .append(String.join(" / ", topics)).append(".\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (!sections.isEmpty())
            {
                // A facade without topics searched its own single document, so what can be asked
                // instead is that document's sections, each of them searchable in turn.
                answer.append("\nThis help is one document, and these are its sections: ") //$NON-NLS-1$
                    .append(String.join(" / ", sections)).append(".\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return answer.toString();
        }
        answer.append(chunks.size())
            .append(chunks.size() == 1 ? " chunk matches" : " chunks match"); //$NON-NLS-1$ //$NON-NLS-2$
        if (chunks.size() > SHOWN)
        {
            answer.append("; the first ").append(SHOWN).append(" of them"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        answer.append(":\n\n"); //$NON-NLS-1$
        for (int i = 0; i < chunks.size() && i < SHOWN; i++)
        {
            answer.append("## From ").append(origins.get(i)).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
                .append(chunks.get(i).strip()).append("\n\n"); //$NON-NLS-1$
        }
        return answer.toString();
    }

    /**
     * The nearest names to one a caller misspelt, with what the catalog says about each.
     * <p>
     * A refusal that only lists every name leaves the caller to find their typo in the list by
     * eye. The names closest by edit distance are usually what was meant, and the one-line
     * description says which of them does what - so the retry is chosen, not guessed.
     * </p>
     *
     * @param name what was asked for and nothing answered to; may be <code>null</code>.
     * @param candidates every name that could have been meant.
     * @param descriptions what the catalog says about a candidate, as {@link #describe} returns
     *            it; a candidate absent from it is named without a description.
     * @return the suggestion block to splice into the refusal, or an empty string when there are
     *         no candidates
     */
    public static String closestMatches(String name, Collection<String> candidates,
        Map<String, String> descriptions)
    {
        List<String> nearest = new ArrayList<>();
        for (String candidate : candidates)
        {
            // Suggesting "help" adds nothing: the refusal already ends by naming it.
            if (!"help".equals(candidate)) //$NON-NLS-1$
            {
                nearest.add(candidate);
            }
        }
        if (nearest.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        final String asked = name == null ? "" : name; //$NON-NLS-1$
        nearest.sort((left, right) ->
        {
            int byDistance =
                Integer.compare(editDistance(asked, left), editDistance(asked, right));
            return byDistance != 0 ? byDistance : left.compareTo(right);
        });
        StringBuilder block = new StringBuilder("\n\nClosest matches:"); //$NON-NLS-1$
        for (int i = 0; i < nearest.size() && i < CLOSEST; i++)
        {
            String candidate = nearest.get(i);
            String described = descriptions.get(candidate);
            block.append("\n- ").append(candidate); //$NON-NLS-1$
            if (described != null && !described.isEmpty())
            {
                block.append(" - ").append(described); //$NON-NLS-1$
            }
        }
        return block.toString();
    }

    /**
     * What the catalog says about each name, one line apiece.
     * <p>
     * The catalog's bullets are its table of contents. A bullet may carry several names, and the
     * line after the names is then what the catalog says about each of them. A catalog that draws
     * a name as a heading instead - edit_form does, one heading per operation under its own
     * section - says what it does on the line below the heading.
     * </p>
     *
     * @param catalog the facade's answer to help without a topic; may be <code>null</code>.
     * @return operation name to its one-line description, never <code>null</code>
     */
    public static Map<String, String> describe(String catalog)
    {
        Map<String, String> descriptions = new LinkedHashMap<>();
        if (catalog == null)
        {
            return descriptions;
        }
        String[] lines = catalog.split("\n", -1); //$NON-NLS-1$
        for (String line : lines)
        {
            if (!line.startsWith("- **")) //$NON-NLS-1$
            {
                continue;
            }
            int close = line.indexOf("**", 4); //$NON-NLS-1$
            if (close < 0)
            {
                continue;
            }
            String rest = line.substring(close + 2).trim();
            if (rest.startsWith("- ")) //$NON-NLS-1$
            {
                rest = rest.substring(2).trim();
            }
            else if (rest.startsWith(".")) //$NON-NLS-1$
            {
                rest = rest.substring(1).trim();
            }
            for (String name : line.substring(4, close).split(" / ")) //$NON-NLS-1$
            {
                if (name.matches("[a-z0-9_]+")) //$NON-NLS-1$
                {
                    descriptions.put(name, rest);
                }
            }
        }
        for (int i = 0; i < lines.length; i++)
        {
            int level = headingLevel(lines[i]);
            // Only a heading deeper than a section names something: a section is the facade's own
            // table of contents, and edit_form names both kinds of heading in one document.
            String name = level > 2 ? lines[i].substring(level).trim() : ""; //$NON-NLS-1$
            if (!name.matches("[A-Za-z0-9_]+")) //$NON-NLS-1$
            {
                continue;
            }
            for (int below = i + 1; below < lines.length && headingLevel(lines[below]) == 0; below++)
            {
                String said = lines[below].strip();
                if (!said.isEmpty() && !said.startsWith("- ")) //$NON-NLS-1$
                {
                    // Absent rather than replaced: a bullet that already describes the name is
                    // where other facades keep it, and a heading saying it again must not win.
                    descriptions.putIfAbsent(name, said);
                    break;
                }
            }
        }
        return descriptions;
    }

    /**
     * Whether a topic names one of the facade's operations, which is asked for its parameters
     * rather than as a document of its own.
     *
     * @param operations every operation the facade dispatches.
     * @param topic the topic asked for.
     * @return <code>true</code> when the topic is one of the operations
     */
    private static boolean isOperation(Collection<String> operations, String topic)
    {
        return operations.contains(JsonUtils.normalizeOperationToken(topic));
    }

    /**
     * The Levenshtein distance between two names: how many single-character edits turn one into
     * the other.
     *
     * @param a one name.
     * @param b the other name.
     * @return the edit count
     */
    static int editDistance(String a, String b)
    {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
        {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    /**
     * The chunks of one rendered document that carry every searched word, appended to the answer.
     *
     * @param text one rendered document; may be <code>null</code>.
     * @param origin how a chunk from it is labeled.
     * @param arguments <code>true</code> when the document is one operation's parameters and its
     *            chunks are its arguments, <code>false</code> when it is a document of sections.
     * @param words the query, already lowercased and split.
     * @param origins receives the label of every matching chunk.
     * @param chunks receives every matching chunk
     */
    private static void collect(String text, String origin, boolean arguments, List<String> words,
        List<String> origins, List<String> chunks)
    {
        if (text == null)
        {
            return;
        }
        for (String chunk : arguments ? chunksOfArguments(text) : chunksOfSections(text))
        {
            String lowered = chunk.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words)
            {
                if (!lowered.contains(word))
                {
                    all = false;
                    break;
                }
            }
            if (all)
            {
                origins.add(origin);
                chunks.add(chunk);
            }
        }
    }

    /**
     * One rendered document as its sections: a heading of level one or two, and everything after
     * it up to the next heading of the same or a higher level.
     * <p>
     * A deeper heading belongs inside the section it stands under: a subsection is part of what the
     * section is about, and cutting at every heading separated a word said under one subsection
     * from a word said under the next however close the two stood.
     * </p>
     *
     * @param text one rendered document.
     * @return the sections, never <code>null</code>
     */
    private static List<String> chunksOfSections(String text)
    {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            int level = headingLevel(line);
            if (level > 0 && level < 3 && current.toString().strip().length() > 0)
            {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (current.toString().strip().length() > 0)
        {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * One operation's help as its arguments: a chunk starts at the line naming an argument and runs
     * to the line naming the next one.
     * <p>
     * An argument is named either by a heading deeper than a section or by a bullet in a list of
     * names, because facades draw them both ways. The operation's own heading is kept, alone: it
     * names what was asked, and the paragraph under it names every argument in passing rather than
     * describing one - a chunk carrying all of them would match any two of them.
     * </p>
     *
     * @param text one rendered topic.
     * @return the chunks, never <code>null</code>
     */
    private static List<String> chunksOfArguments(String text)
    {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean named = false;
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            boolean argument = namesAnArgument(line);
            if (current.length() > 0 && (argument || headingLevel(line) > 0))
            {
                flushArgument(chunks, current, named);
                current = new StringBuilder();
                named = false;
            }
            if (current.length() == 0)
            {
                named = argument;
            }
            current.append(line).append('\n');
        }
        flushArgument(chunks, current, named);
        return chunks;
    }

    /**
     * Keeps one chunk of an operation's help: an argument's own lines whole, and the operation's
     * heading alone. Anything else is the opening paragraph and is left out.
     *
     * @param chunks receives the chunk, when there is one to keep.
     * @param chunk the lines gathered so far.
     * @param named whether the chunk began at a line naming an argument.
     */
    private static void flushArgument(List<String> chunks, StringBuilder chunk, boolean named)
    {
        String text = chunk.toString();
        if (named)
        {
            chunks.add(text);
            return;
        }
        int firstBreak = text.indexOf('\n'); //$NON-NLS-1$
        String first = firstBreak < 0 ? text : text.substring(0, firstBreak);
        if (headingLevel(first) > 0)
        {
            chunks.add(first);
        }
    }

    /**
     * Whether a line begins the description of one argument.
     *
     * @param line one line of help text.
     * @return <code>true</code> for a heading deeper than a section, or for a bullet
     */
    private static boolean namesAnArgument(String line)
    {
        return headingLevel(line) > 2 || line.startsWith("- **"); //$NON-NLS-1$
    }

    /**
     * The sections of one rendered document, for an answer that found nothing and has no topics to
     * offer instead.
     * <p>
     * A section that has subsections is represented by those subsections: its own name stands over
     * them and says nothing to search by.
     * </p>
     *
     * @param text one rendered document; may be <code>null</code>.
     * @return the names, in the order they appear, never <code>null</code>
     */
    private static List<String> sectionNames(String text)
    {
        List<String> names = new ArrayList<>();
        if (text == null)
        {
            return names;
        }
        List<String> titles = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            int level = headingLevel(line);
            if (level > 0)
            {
                titles.add(line.substring(level).trim());
                levels.add(level);
            }
        }
        for (int i = 1; i < titles.size(); i++)
        {
            // From one on: the first heading is the document's own title.
            boolean overSubsections = i + 1 < titles.size() && levels.get(i + 1) > levels.get(i);
            if (!overSubsections)
            {
                names.add(titles.get(i));
            }
        }
        return names;
    }

    /**
     * How many hashes open a markdown heading line.
     *
     * @param line one line of help text.
     * @return the level, or 0 when the line is not a heading
     */
    private static int headingLevel(String line)
    {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#')
        {
            hashes++;
        }
        return hashes > 0 && hashes < line.length() && line.charAt(hashes) == ' ' ? hashes : 0;
    }

    /**
     * The query as separate lowercased words, every one of which a matching chunk must carry.
     * <p>
     * Folded with the root locale rather than the default one, so a Russian word matches in any
     * case whatever the machine's locale is.
     * </p>
     *
     * @param query what the caller asked for.
     * @return the words, never <code>null</code>
     */
    private static List<String> wordsOf(String query)
    {
        List<String> words = new ArrayList<>();
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) //$NON-NLS-1$
        {
            if (!word.isEmpty())
            {
                words.add(word);
            }
        }
        return words;
    }
}
