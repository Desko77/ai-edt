/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether an extension's controlled change still matches the code it controls.
 * <p>
 * <b>What a controlled change is, and why it is the one that breaks quietly.</b> An interceptor
 * annotated {@code &ИзменениеИКонтроль} does not merely run beside the base method - it carries a
 * copy of that method's code and edits it in place, with {@code #Вставка} marking what the extension
 * adds and {@code #Удаление} marking what it takes out. The platform will only apply such an
 * extension if the code around those markers is still, character for character, the code the base
 * method has. A release that reformats one line of that method makes the extension refuse to load,
 * and nothing about the extension itself changed.
 * </p>
 * <p>
 * <b>What this can and cannot promise.</b> A mismatch found here is real: the text the extension
 * controls is not the text the delivery has. A match is not a guarantee, because the comparison
 * here normalises what an author would call unimportant - line endings, trailing blanks, the case
 * of identifiers - and the platform's own comparison is its own business. So a finding is worth
 * acting on and a clean answer is worth nothing more than what it says.
 * </p>
 */
public final class ControlledFragment
{
    /** Opens code the extension adds. Its contents are the extension's, not the base's. */
    private static final String[] INSERT_OPEN = {"#вставка", "#insert"}; //$NON-NLS-1$ //$NON-NLS-2$

    /** Closes an insertion. */
    private static final String[] INSERT_CLOSE = {"#конецвставки", "#endinsert"}; //$NON-NLS-1$ //$NON-NLS-2$

    /** Opens base code the extension removes. It is still base code and still compared. */
    private static final String[] DELETE_OPEN = {"#удаление", "#delete"}; //$NON-NLS-1$ //$NON-NLS-2$

    /** Closes a deletion. */
    private static final String[] DELETE_CLOSE = {"#конецудаления", "#enddelete"}; //$NON-NLS-1$ //$NON-NLS-2$

    private ControlledFragment()
    {
        // Static rule.
    }

    /**
     * Takes out of a handler the part that has to match the base method.
     * <p>
     * Everything except what the extension inserted. The lines inside {@code #Удаление} stay: they
     * are the base's own code, marked for removal, and the platform checks them against the base
     * exactly like the untouched lines around them. Dropping them would let a release change the
     * very lines an extension deletes and pass unnoticed.
     * </p>
     *
     * @param handlerBody the body of the controlling handler.
     * @return the lines that must match the base, in order
     */
    public static List<String> controlledPartOf(String handlerBody)
    {
        List<String> controlled = new ArrayList<>();
        if (handlerBody == null)
        {
            return controlled;
        }
        boolean insideInsert = false;
        for (String line : handlerBody.split("\r?\n", -1)) //$NON-NLS-1$
        {
            String marker = line.trim().toLowerCase(Locale.ROOT).replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (startsWithAny(marker, INSERT_OPEN))
            {
                insideInsert = true;
                continue;
            }
            if (startsWithAny(marker, INSERT_CLOSE))
            {
                insideInsert = false;
                continue;
            }
            if (startsWithAny(marker, DELETE_OPEN) || startsWithAny(marker, DELETE_CLOSE))
            {
                // The markers themselves are the extension's; what they wrap is the base's.
                continue;
            }
            if (!insideInsert)
            {
                controlled.add(line);
            }
        }
        return controlled;
    }

    /**
     * Says where the controlled code and the delivery's code part company.
     * <p>
     * Reported as the first place they differ rather than as a whole diff. An extension refuses to
     * load on the first mismatch, so the first one is the one to fix, and a wall of differences
     * would bury it.
     * </p>
     *
     * @param handlerBody the body of the controlling handler.
     * @param baseBody the body of the method in the delivery.
     * @return what differs, or <code>null</code> when the controlled part is still there
     */
    public static String describeDrift(String handlerBody, String baseBody)
    {
        List<String> controlled = normalise(controlledPartOf(handlerBody));
        List<String> delivered = normalise(java.util.Arrays.asList(
            baseBody == null ? new String[0] : baseBody.split("\r?\n", -1))); //$NON-NLS-1$
        if (controlled.isEmpty())
        {
            return null;
        }
        // The controlled lines have to appear in the delivery, in order and unbroken. Anything else
        // and the platform has nothing to graft the insertions onto.
        int at = indexOfRun(delivered, controlled);
        if (at >= 0)
        {
            return null;
        }
        // Reported as the author wrote it, not as the comparison normalised it. Somebody who
        // reads "возврат;" then searches their module for it finds nothing, because what they
        // wrote was "Возврат;".
        List<String> asWritten = new ArrayList<>();
        for (String line : controlledPartOf(handlerBody))
        {
            if (line != null && !line.trim().isEmpty())
            {
                asWritten.add(line.trim());
            }
        }
        for (int i = 0; i < controlled.size(); i++)
        {
            if (!delivered.contains(controlled.get(i)))
            {
                return "the controlled code no longer matches the delivery. The first line the " //$NON-NLS-1$
                    + "delivery does not have: " + (i < asWritten.size() ? asWritten.get(i) //$NON-NLS-1$
                        : controlled.get(i));
            }
        }
        return "every controlled line still exists in the delivery, but no longer as one " //$NON-NLS-1$
            + "unbroken run - the method was reordered or something was inserted into the middle " //$NON-NLS-1$
            + "of what the extension controls"; //$NON-NLS-1$
    }

    /**
     * Finds where one run of lines sits inside another, whole and in order.
     *
     * @param haystack the delivery's lines.
     * @param needle the controlled lines.
     * @return the index where the run starts, or -1 when it is not there as a run
     */
    private static int indexOfRun(List<String> haystack, List<String> needle)
    {
        if (needle.isEmpty() || needle.size() > haystack.size())
        {
            return -1;
        }
        outer: for (int i = 0; i + needle.size() <= haystack.size(); i++)
        {
            for (int j = 0; j < needle.size(); j++)
            {
                if (!haystack.get(i + j).equals(needle.get(j)))
                {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Drops what an author would not call a change.
     * <p>
     * Blank lines, indentation and the case of identifiers. BSL does not distinguish case, and
     * reporting a reindent as a break would make this check unusable on the first release that
     * touched formatting. What it costs is stated where it matters: a clean answer here is not a
     * promise that the platform will agree.
     * </p>
     *
     * @param lines the lines to normalise.
     * @return the lines that carry code, comparable
     */
    private static List<String> normalise(List<String> lines)
    {
        List<String> out = new ArrayList<>();
        for (String line : lines)
        {
            String trimmed = line == null ? "" : line.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
            if (!trimmed.isEmpty())
            {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Says whether a line opens or closes one of the directives.
     *
     * @param marker the line, already lowered and stripped of spaces.
     * @param options the directive spellings.
     * @return <code>true</code> when it is one of them
     */
    private static boolean startsWithAny(String marker, String[] options)
    {
        for (String option : options)
        {
            if (marker.startsWith(option))
            {
                return true;
            }
        }
        return false;
    }
}
