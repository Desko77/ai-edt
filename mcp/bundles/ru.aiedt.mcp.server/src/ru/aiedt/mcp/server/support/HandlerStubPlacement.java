/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.regex.Pattern;

/**
 * Where event-handler stubs go in an object module, and in what wrapping.
 * <p>
 * The stubs land in the module's own handler region when it has one - before that region's closing
 * directive, counting nested regions, so a second region is never created. A module without the
 * region gets one new region: inside the module's top-level conditional-compilation framing when
 * there is one, before its top-level {@code #Иначе} or, without one, before its closing
 * {@code #КонецЕсли}; at the end of the module otherwise, without a framing of its own. A module
 * with nothing in it takes the whole shape: framing, region, stubs. A conditional-compilation
 * block counts as the module's framing only when it holds the whole module - nothing but blank
 * lines and comments before its opening directive and after its closing one; a block that opens
 * after code frames only what stands inside it, so the stubs go outside it.
 * </p>
 * <p>
 * Region and preprocessor directives are recognized in either language whatever the configuration
 * writes, because a module and its configuration may have been written at different times. The
 * directives this plan emits are the language given to it.
 * </p>
 */
public final class HandlerStubPlacement
{
    /** A region opening directive; group 1 is the region name. */
    private static final Pattern REGION_OPEN = Pattern.compile(
        "^\\s*#\\s*(?:Область|Region)\\s+(\\S+)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** A region closing directive. */
    private static final Pattern REGION_CLOSE = Pattern.compile(
        "^\\s*#\\s*(?:КонецОбласти|EndRegion)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** A conditional-compilation block opening. */
    private static final Pattern IF_OPEN = Pattern.compile(
        "^\\s*#\\s*(?:Если|If)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** A conditional-compilation block closing. */
    private static final Pattern IF_CLOSE = Pattern.compile(
        "^\\s*#\\s*(?:КонецЕсли|EndIf)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** The {@code #Иначе} branch of a conditional-compilation block. */
    private static final Pattern ELSE_BRANCH = Pattern.compile(
        "^\\s*#\\s*(?:Иначе|Else)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private HandlerStubPlacement()
    {
    }

    /** The write one placement asks for: the text and where it goes. */
    public static final class Plan
    {
        /** The source to hand to the module writer. */
        public final String text;

        /** The 1-based line the text goes before, or null when it goes to the end of the module. */
        public final Integer insertBeforeLine;

        /** Whether the module has nothing in it, so the text is its entire content. */
        public final boolean createsModule;

        Plan(String text, Integer insertBeforeLine, boolean createsModule)
        {
            this.text = text;
            this.insertBeforeLine = insertBeforeLine;
            this.createsModule = createsModule;
        }
    }

    /**
     * Places handler stubs in an object module.
     *
     * @param moduleText the module as it stands, or null when it has no file
     * @param procedures the stub procedures, separated by blank lines, without a wrapping
     * @param language the language the directives to emit are written in
     * @return the write to make
     */
    public static Plan plan(String moduleText, String procedures, BslScriptLanguage language)
    {
        String stubs = procedures.endsWith("\n") ? procedures : procedures + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        if (moduleText == null || moduleText.trim().isEmpty())
        {
            return new Plan(language.serverFramingOpen() + "\n" //$NON-NLS-1$
                + regionBlock(stubs, language)
                + language.framingEnd() + "\n", null, true); //$NON-NLS-1$
        }
        String[] lines = moduleText.replace("\r\n", "\n").split("\n", -1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        int regionEnd = handlerRegionEnd(lines);
        if (regionEnd > 0)
        {
            return new Plan(stubs, Integer.valueOf(regionEnd), false);
        }

        int framingBoundary = topLevelFramingBoundary(lines);
        if (framingBoundary > 0)
        {
            String pad = blankBefore(lines, framingBoundary);
            return new Plan(pad + regionBlock(stubs, language), Integer.valueOf(framingBoundary),
                false);
        }
        String pad = lastLineIsBlank(lines) ? "" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        return new Plan(pad + regionBlock(stubs, language), null, false);
    }

    /**
     * The region with the stubs in it, ready to stand on its own in a module.
     *
     * @param stubs the stub procedures, closed by a line break
     * @param language the language of the directives
     * @return the region block
     */
    private static String regionBlock(String stubs, BslScriptLanguage language)
    {
        return language.regionOpen(language.handlerRegionName()) + "\n" + stubs //$NON-NLS-1$
            + language.regionEnd() + "\n"; //$NON-NLS-1$
    }

    /**
     * The line that closes the module's handler region, counting nested regions.
     *
     * @param lines the module lines
     * @return the 1-based line number of the region's own closing directive, or 0 when the module
     *         has no handler region
     */
    static int handlerRegionEnd(String[] lines)
    {
        for (int i = 0; i < lines.length; i++)
        {
            java.util.regex.Matcher opening = REGION_OPEN.matcher(lines[i]);
            if (!opening.find() || !isHandlerRegionName(opening.group(1)))
            {
                continue;
            }
            int depth = 1;
            for (int j = i + 1; j < lines.length; j++)
            {
                if (REGION_OPEN.matcher(lines[j]).find())
                {
                    depth++;
                }
                else if (REGION_CLOSE.matcher(lines[j]).find() && --depth == 0)
                {
                    return j + 1;
                }
            }
            return 0;
        }
        return 0;
    }

    /**
     * Whether a region name is the handler region's, in either language and whatever the case.
     *
     * @param name the name as the module spells it
     * @return true when it names the handler region
     */
    private static boolean isHandlerRegionName(String name)
    {
        return BslScriptLanguage.HANDLER_REGION_RUSSIAN.equalsIgnoreCase(name)
            || BslScriptLanguage.HANDLER_REGION_ENGLISH.equalsIgnoreCase(name);
    }

    /**
     * Where a new region goes inside the module's top-level conditional-compilation framing: before
     * its top-level {@code #Иначе} when there is one, before its closing {@code #КонецЕсли}
     * otherwise. A block counts as the framing of the whole module only when nothing but blank
     * lines and comments stands before its opening directive and after its closing one; a block
     * that opens after code frames only what stands inside it and answers nothing here.
     *
     * @param lines the module lines
     * @return the 1-based line number, or 0 when the module carries no framing of its whole self
     */
    static int topLevelFramingBoundary(String[] lines)
    {
        int depth = 0;
        for (int i = 0; i < lines.length; i++)
        {
            if (!IF_OPEN.matcher(lines[i]).find())
            {
                continue;
            }
            depth = 1;
            int elseLine = 0;
            for (int j = i + 1; j < lines.length; j++)
            {
                if (IF_OPEN.matcher(lines[j]).find())
                {
                    depth++;
                }
                else if (ELSE_BRANCH.matcher(lines[j]).find() && depth == 1)
                {
                    elseLine = elseLine == 0 ? j + 1 : elseLine;
                }
                else if (IF_CLOSE.matcher(lines[j]).find() && --depth == 0)
                {
                    if (!onlyBlanksAndComments(lines, j + 1, lines.length)
                        || !onlyBlanksAndComments(lines, 0, i))
                    {
                        return 0;
                    }
                    return elseLine != 0 ? elseLine : j + 1;
                }
            }
            return 0;
        }
        return 0;
    }

    /**
     * Whether nothing but blank lines and comments stand in a span of lines, which is what makes a
     * conditional-compilation block the framing of the whole module rather than a block in the
     * middle of it.
     *
     * @param lines the module lines
     * @param from the 0-based index of the first line of the span
     * @param to the 0-based index one past the last line of the span
     * @return true when the span carries only blanks and comments
     */
    private static boolean onlyBlanksAndComments(String[] lines, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("//")) //$NON-NLS-1$
            {
                return false;
            }
        }
        return true;
    }

    /**
     * A leading blank line for a block that goes before code, so the two do not touch.
     *
     * @param lines the module lines
     * @param beforeLine the 1-based line the block goes before
     * @return a line break, or nothing when the line above is already blank
     */
    private static String blankBefore(String[] lines, int beforeLine)
    {
        return beforeLine >= 2 && !lines[beforeLine - 2].trim().isEmpty() ? "\n" : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether the module ends on a blank line, which is the only case that needs no blank line
     * between it and what follows. A module that merely closes its last line with a line break
     * does not: the code line above that break is the one the new block would touch.
     *
     * @param lines the module lines
     * @return true when the last line is blank
     */
    private static boolean lastLineIsBlank(String[] lines)
    {
        int last = lines.length - 1;
        if (last > 0 && lines[last].trim().isEmpty())
        {
            last--;
        }
        return lines[last].trim().isEmpty();
    }
}
