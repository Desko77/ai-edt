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
    /** How many matching chunks one answer shows; the rest is reported as the count alone. */
    private static final int SHOWN = 10;

    /** How many near names a refusal suggests. */
    private static final int CLOSEST = 3;

    private FacadeHelpSearch()
    {
    }

    /**
     * The chunks of a facade's help that carry every word of the query.
     * <p>
     * A chunk is one section of one rendered topic: from a heading to the next heading, which for
     * an operation's parameters is one parameter's description. The catalog counts as one chunk,
     * so a word that names an operation finds the catalog entry first.
     * </p>
     *
     * @param facade the facade's wire name, for the heading.
     * @param query what to look for; the caller refuses a blank one.
     * @param topic confine the search to this one topic; <code>null</code> or blank for all of it.
     * @param topics every topic of the facade, in the order the catalog names them.
     * @param helpOf renders one topic (<code>null</code> for the catalog), as the facade itself
     *            does.
     * @return markdown: the count and the first chunks with where each came from, or the list of
     *         topics when nothing matched - not an error either way
     */
    public static String search(String facade, String query, String topic,
        List<String> topics, Function<String, String> helpOf)
    {
        List<String> words = wordsOf(query);
        String scoped = topic == null || topic.isBlank() ? null : topic.trim();
        List<String> origins = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        if (scoped != null)
        {
            String text = helpOf.apply(scoped);
            if (text != null && text.startsWith("# Unknown topic")) //$NON-NLS-1$
            {
                // The refusal already says what can be asked; searching its text would find
                // nothing and read as if the topic existed but carried no match.
                return text;
            }
            collect(text, "topic=" + scoped, words, origins, chunks); //$NON-NLS-1$
        }
        else
        {
            collect(helpOf.apply(null), "the operation catalog", words, origins, chunks); //$NON-NLS-1$
            for (String named : topics)
            {
                collect(helpOf.apply(named), "topic=" + named, words, origins, chunks); //$NON-NLS-1$
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
                .append("behind it - search by the stem of the word.\n\n"); //$NON-NLS-1$
            answer.append("Ask for a whole topic with topic=<name>: ") //$NON-NLS-1$
                .append(String.join(" / ", topics)).append(".\n"); //$NON-NLS-1$ //$NON-NLS-2$
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
     * line after the names is then what the catalog says about each of them.
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
        for (String line : catalog.split("\n", -1)) //$NON-NLS-1$
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
        return descriptions;
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
     * The chunks of one rendered topic that carry every searched word, appended to the answer.
     *
     * @param text one rendered topic; may be <code>null</code>.
     * @param origin how a chunk from it is labeled.
     * @param words the query, already lowercased and split.
     * @param origins receives the label of every matching chunk.
     * @param chunks receives every matching chunk
     */
    private static void collect(String text, String origin, List<String> words,
        List<String> origins, List<String> chunks)
    {
        if (text == null)
        {
            return;
        }
        for (String chunk : chunksOf(text))
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
     * One help text as chunks: a chunk starts at a heading and ends where the next heading
     * starts.
     * <p>
     * A topic's own heading begins its first chunk, and the parameters of an operation come one
     * chunk apiece, because each is rendered under a heading of its own. Text before the first
     * heading, when there is any, is a chunk too.
     * </p>
     *
     * @param text one rendered topic or catalog.
     * @return the chunks, never <code>null</code>
     */
    private static List<String> chunksOf(String text)
    {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            if (isHeading(line) && current.toString().strip().length() > 0)
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
     * Whether a line opens a markdown section.
     *
     * @param line one line of help text.
     * @return <code>true</code> when the line is a heading
     */
    private static boolean isHeading(String line)
    {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#')
        {
            hashes++;
        }
        return hashes > 0 && hashes < line.length() && line.charAt(hashes) == ' ';
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
