/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which screenshot belongs to which broken step.
 * <p>
 * A scenario run hands back a directory of images and a report of what went wrong, and nothing in
 * either says which image is of which step. A list of paths leaves that reading to whoever gets the
 * answer, who has less to go on than this does.
 * </p>
 * <p>
 * Two rules, tried in order. A report that writes the file name into its own message or stack says
 * the link outright, and that is taken as given. Otherwise a file whose name carries the step's
 * name is attributed to it. Neither rule is a claim about how the runner names its files: what
 * neither rule reaches is returned as unattributed, so a caller sees the images that were left
 * over rather than a tidy list that quietly dropped them.
 * </p>
 */
public final class FailureScreenshots
{
    /**
     * The shortest text either rule may look for inside another: a step name inside a file name,
     * or a file name inside a report's message.
     * <p>
     * Two characters occur in almost anything, and a wrong attribution is worse than none: it
     * points at the wrong step with the same confidence as a right one points at the right one.
     * </p>
     */
    private static final int SHORTEST_USABLE_NAME = 4;

    /**
     * Whether a piece of text is long enough to be looked for inside another.
     *
     * @param text what would be searched for.
     * @return whether looking for it says anything
     */
    private static boolean meansSomething(String text)
    {
        return text != null && text.length() >= SHORTEST_USABLE_NAME;
    }

    private final Map<String, List<String>> byStep;

    private final List<String> unattributed;

    private FailureScreenshots(Map<String, List<String>> byStep, List<String> unattributed)
    {
        this.byStep = byStep;
        this.unattributed = unattributed;
    }

    /**
     * Reads the images against the steps that broke.
     *
     * @param broken the failures and errors, in report order; null counts as none.
     * @param files the image file names, in the order they should be reported; null counts as none.
     * @return the attribution, including whatever nothing claimed
     */
    public static FailureScreenshots attribute(List<JUnitRunOutcome.TestCase> broken,
        List<String> files)
    {
        Map<String, List<String>> byStep = new LinkedHashMap<>();
        List<String> unattributed = new ArrayList<>();
        if (files == null || files.isEmpty())
        {
            return new FailureScreenshots(byStep, unattributed);
        }
        List<JUnitRunOutcome.TestCase> cases = broken == null ? Collections.emptyList() : broken;
        for (String file : files)
        {
            JUnitRunOutcome.TestCase owner = named(cases, file);
            if (owner == null)
            {
                owner = carryingTheStepName(cases, file);
            }
            if (owner == null)
            {
                unattributed.add(file);
                continue;
            }
            byStep.computeIfAbsent(owner.name, k -> new ArrayList<>()).add(file);
        }
        return new FailureScreenshots(byStep, unattributed);
    }

    /**
     * The step whose own text names this file.
     *
     * @param cases the steps that broke.
     * @param file the image file name.
     * @return the first step naming it, or {@code null}
     */
    private static JUnitRunOutcome.TestCase named(List<JUnitRunOutcome.TestCase> cases, String file)
    {
        String bare = withoutExtension(file);
        for (JUnitRunOutcome.TestCase broken : cases)
        {
            if (mentions(broken.message, file, bare) || mentions(broken.trace, file, bare))
            {
                return broken;
            }
        }
        return null;
    }

    /**
     * The step whose name the file name carries.
     *
     * @param cases the steps that broke.
     * @param file the image file name.
     * @return the first step whose name is in it, or {@code null}
     */
    private static JUnitRunOutcome.TestCase carryingTheStepName(
        List<JUnitRunOutcome.TestCase> cases, String file)
    {
        String haystack = folded(file);
        JUnitRunOutcome.TestCase owner = null;
        int longest = 0;
        for (JUnitRunOutcome.TestCase broken : cases)
        {
            // The longest match, not the first. Step names overlap - one step's name is another's
            // beginning - and report order then decides the owner, which it has no business doing.
            int matched = Math.max(matchLength(haystack, folded(broken.name)),
                matchLength(haystack, folded(withoutItsModule(broken.name))));
            if (matched > longest)
            {
                longest = matched;
                owner = broken;
            }
        }
        return owner;
    }

    /**
     * How much of the file name this name accounts for.
     *
     * @param haystack the folded file name.
     * @param needle the folded name to look for.
     * @return the needle's length when it is there and long enough to mean anything, else zero
     */
    private static int matchLength(String haystack, String needle)
    {
        return carries(haystack, needle) ? needle.length() : 0;
    }

    /**
     * Whether the folded file name carries this folded name.
     *
     * @param haystack the folded file name.
     * @param needle the folded name to look for.
     * @return whether it is there and long enough to mean anything
     */
    private static boolean carries(String haystack, String needle)
    {
        return meansSomething(needle) && haystack.contains(needle);
    }

    /**
     * The step's own name, without the module the report qualified it with.
     * <p>
     * A report names a step as its module and its name joined with a dot, and a file named after
     * the step carries the second half only. Looking for the whole thing misses that, which is the
     * commonest way these files are named.
     * </p>
     *
     * @param name the name as the report gave it.
     * @return what follows the last dot, or the whole name when there is none
     */
    private static String withoutItsModule(String name)
    {
        if (name == null)
        {
            return ""; //$NON-NLS-1$
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
    }

    private static boolean mentions(String text, String file, String bare)
    {
        if (text == null)
        {
            return false;
        }
        return text.contains(file) || (meansSomething(bare) && containsWhole(text, bare));
    }

    /**
     * Whether the text contains this name and not merely the start of a longer one.
     * <p>
     * Without the boundaries, {@code step-0007} is found inside {@code step-00070.png} and the
     * image is attributed to a step that never mentioned it - confidently, and wrongly.
     * </p>
     *
     * @param text where to look.
     * @param name what to look for.
     * @return whether the name appears with nothing alphanumeric against either end
     */
    private static boolean containsWhole(String text, String name)
    {
        int at = text.indexOf(name);
        while (at >= 0)
        {
            int after = at + name.length();
            boolean openLeft = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean openRight =
                after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
            if (openLeft && openRight)
            {
                return true;
            }
            at = text.indexOf(name, at + 1);
        }
        return false;
    }

    private static String withoutExtension(String file)
    {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /**
     * Reduces a name to the letters and digits in it, lower-cased.
     * <p>
     * A step name and the file named after it rarely agree on the spaces, punctuation and case in
     * between, and none of that carries meaning here.
     * </p>
     *
     * @param text what to fold; null counts as empty.
     * @return the letters and digits, lower-cased
     */
    private static String folded(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c))
            {
                folded.append(c);
            }
        }
        return folded.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the images per step name, in the order the files were given; never null
     */
    public Map<String, List<String>> byStep()
    {
        return Collections.unmodifiableMap(byStep);
    }

    /**
     * @return the images no step claimed, in the order they were given; never null
     */
    public List<String> unattributed()
    {
        return Collections.unmodifiableList(unattributed);
    }

    /**
     * Writes the attribution up as Markdown, for the answer an agent reads.
     *
     * @param full the full path of each file, by its name, so the caller can open it.
     * @return a section, or an empty string when nothing was attributed and nothing was left over
     */
    public String toMarkdown(Map<String, String> full)
    {
        if (byStep.isEmpty() && unattributed.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder md = new StringBuilder();
        md.append("\n## Screenshots\n\n"); //$NON-NLS-1$
        for (Map.Entry<String, List<String>> step : byStep.entrySet())
        {
            md.append("### ").append(step.getKey()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String file : step.getValue())
            {
                md.append("- ").append(pathOf(full, file)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\n"); //$NON-NLS-1$
        }
        if (!unattributed.isEmpty())
        {
            md.append("### Not attributed to a step\n\n"); //$NON-NLS-1$
            for (String file : unattributed)
            {
                md.append("- ").append(pathOf(full, file)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\n"); //$NON-NLS-1$
        }
        return md.toString();
    }

    private static String pathOf(Map<String, String> full, String file)
    {
        String path = full == null ? null : full.get(file);
        return path != null ? path : file;
    }
}
