/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the letter of one alphabet hiding inside a word of the other.
 * <p>
 * A Latin C at the front of Сумма makes an object that looks right everywhere it is printed and is
 * found by no search for the name a person types. The platform accepts it, the editor shows it, and
 * the only sign is that references to it come back empty. The name is checked when it is given, the
 * character and its position are named, and the name in one alphabet is offered.
 * </p>
 * <p>
 * A name that mixes alphabets is not wrong by itself: ЗагрузкаXML, ОбменSMS and HTTPЗапрос are how
 * configurations are written. What is reported is narrower. A word - the letters standing together
 * with nothing between them - is written in the alphabet most of its letters are in; what is named
 * is a run of one or two letters that is NOT in that alphabet and whose every letter has a twin in
 * it. A run carrying a letter with no twin (the L of XML, the S of SMS) is a word in its own right,
 * and ВТ_Data is two words rather than one that mixes.
 * </p>
 */
public final class OneAlphabetPerWord
{
    /** Letters that look the same in both alphabets, Latin to Cyrillic. */
    private static final Map<Character, Character> LATIN_TWIN = buildLatinTwins();

    /** The same pairs read the other way, Cyrillic to Latin. */
    private static final Map<Character, Character> CYRILLIC_TWIN = buildCyrillicTwins();

    /** How long a run may be and still read as a letter that slipped in rather than a word. */
    private static final int SLIP_LENGTH = 2;

    private OneAlphabetPerWord()
    {
    }

    /**
     * What is wrong with the name, or <code>null</code> when nothing is.
     *
     * @param name the name as the caller gave it
     * @return a sentence naming every suspect character, its position and the name in one alphabet
     */
    public static String whatIsWrong(String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        List<int[]> runs = runsOf(name);
        if (!hasBothAlphabets(name, runs))
        {
            return null;
        }
        StringBuilder mended = new StringBuilder(name);
        List<String> named = new ArrayList<>();
        for (int at = 0; at < runs.size(); at++)
        {
            int[] run = runs.get(at);
            if (!isSlip(name, run) || run[2] == alphabetOfTheWord(runs, at))
            {
                continue;
            }
            boolean latin = run[2] == 1;
            for (int position = run[0]; position < run[1]; position++)
            {
                char character = name.charAt(position);
                Character twin = latin ? LATIN_TWIN.get(Character.valueOf(character))
                    : CYRILLIC_TWIN.get(Character.valueOf(character));
                named.add("'" + character + "' at position " + (position + 1) + " is " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + (latin ? "Latin" : "Cyrillic")); //$NON-NLS-1$ //$NON-NLS-2$
                if (twin != null)
                {
                    mended.setCharAt(position, twin.charValue());
                }
            }
        }
        if (named.isEmpty())
        {
            return null;
        }
        return "the name '" + name + "' mixes alphabets inside one word: " //$NON-NLS-1$ //$NON-NLS-2$
            + String.join(", ", named) + ". Such a name is accepted by the platform, printed the " //$NON-NLS-1$ //$NON-NLS-2$
            + "same as the one it imitates, and found by no search for it. Did you mean '" //$NON-NLS-1$
            + mended + "'?"; //$NON-NLS-1$
    }

    /**
     * The runs of same-alphabet letters in the name.
     *
     * @param name the name
     * @return one entry per run: start, end (exclusive), and 1 for Latin or 2 for Cyrillic
     */
    private static List<int[]> runsOf(String name)
    {
        List<int[]> runs = new ArrayList<>();
        int at = 0;
        while (at < name.length())
        {
            int alphabet = alphabetOf(name.charAt(at));
            if (alphabet == 0)
            {
                at++;
                continue;
            }
            int start = at;
            while (at < name.length() && alphabetOf(name.charAt(at)) == alphabet)
            {
                at++;
            }
            runs.add(new int[] {start, at, alphabet});
        }
        return runs;
    }

    /**
     * Which alphabet a character belongs to.
     *
     * @param character the character
     * @return 1 for Latin, 2 for Cyrillic, 0 for anything else
     */
    private static int alphabetOf(char character)
    {
        if (character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z')
        {
            return 1;
        }
        if (Character.UnicodeBlock.of(character) == Character.UnicodeBlock.CYRILLIC)
        {
            return 2;
        }
        return 0;
    }

    /**
     * Whether the name has letters of both alphabets at all.
     *
     * @param name the name
     * @param runs its runs
     * @return whether both are present
     */
    private static boolean hasBothAlphabets(String name, List<int[]> runs)
    {
        boolean latin = false;
        boolean cyrillic = false;
        for (int[] run : runs)
        {
            latin = latin || run[2] == 1;
            cyrillic = cyrillic || run[2] == 2;
        }
        return latin && cyrillic;
    }

    /**
     * Whether a run reads as a letter that slipped in rather than as a word.
     *
     * @param name the name
     * @param run the run
     * @return whether every character of a short run has a twin in the other alphabet
     */
    private static boolean isSlip(String name, int[] run)
    {
        if (run[1] - run[0] > SLIP_LENGTH)
        {
            return false;
        }
        for (int at = run[0]; at < run[1]; at++)
        {
            Character character = Character.valueOf(name.charAt(at));
            boolean hasTwin = run[2] == 1 ? LATIN_TWIN.containsKey(character)
                : CYRILLIC_TWIN.containsKey(character);
            if (!hasTwin)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Which alphabet the word around this run is written in.
     * <p>
     * A word is the run and every run touching it with nothing in between - ВТ_Data is two words,
     * Кoнтрагент is one. The alphabet most of its letters are in is the one it is written in; a tie
     * reads as Cyrillic, because the letter that slips into a Russian name is the Latin one.
     * </p>
     *
     * @param runs every run of the name
     * @param at the run to ask about
     * @return 1 for Latin, 2 for Cyrillic
     */
    private static int alphabetOfTheWord(List<int[]> runs, int at)
    {
        int first = at;
        while (first > 0 && runs.get(first - 1)[1] == runs.get(first)[0])
        {
            first--;
        }
        int last = at;
        while (last + 1 < runs.size() && runs.get(last + 1)[0] == runs.get(last)[1])
        {
            last++;
        }
        int latin = 0;
        int cyrillic = 0;
        for (int each = first; each <= last; each++)
        {
            int[] run = runs.get(each);
            int letters = run[1] - run[0];
            if (run[2] == 1)
            {
                latin += letters;
            }
            else
            {
                cyrillic += letters;
            }
        }
        return latin > cyrillic ? 1 : 2;
    }

    /**
     * Builds the Latin-to-Cyrillic twins.
     *
     * @return the pairs
     */
    private static Map<Character, Character> buildLatinTwins()
    {
        Map<Character, Character> twins = new HashMap<>();
        String latin = "aceopxyABCEHKMOPTX"; //$NON-NLS-1$
        String cyrillic = "асеорху" + "АВСЕНКМОРТХ"; //$NON-NLS-1$ //$NON-NLS-2$
        for (int at = 0; at < latin.length(); at++)
        {
            twins.put(Character.valueOf(latin.charAt(at)), Character.valueOf(cyrillic.charAt(at)));
        }
        return twins;
    }

    /**
     * The same pairs read the other way.
     *
     * @return the pairs
     */
    private static Map<Character, Character> buildCyrillicTwins()
    {
        Map<Character, Character> twins = new HashMap<>();
        for (Map.Entry<Character, Character> pair : LATIN_TWIN.entrySet())
        {
            twins.put(pair.getValue(), pair.getKey());
        }
        return twins;
    }
}
