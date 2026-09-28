/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;

import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Adds a handler procedure to the module of a form, unless the module already declares it.
 * <p>
 * The procedure goes into the module region that holds handlers of its kind: before the end of that
 * region when the module has it, in a new region at the end of the module when it does not. The text
 * goes through {@link ModuleSourceWriter#execute}, so the write gets the writer's refusal on an editor
 * with unsaved changes, its syntax check and the line delimiters the file already uses. A procedure the
 * module already declares is left as it is; when its compilation directive or its number of parameters
 * differs from what the event calls, the outcome says so. Does not bind the handler to the form: the
 * caller does that in its own transaction before calling this.
 * </p>
 */
final class FormModuleStubs
{
    /** The reason given when the module already declares the procedure. */
    static final String ALREADY_PRESENT = "alreadyPresent"; //$NON-NLS-1$

    /** The reason given when the call was a preview. */
    static final String DRY_RUN = "dryRun"; //$NON-NLS-1$

    /** The body {@link ModuleSourceWriter} opens a successful answer with. */
    private static final String WRITE_FINISHED = "Write finished successfully"; //$NON-NLS-1$

    /** Handlers of form events. */
    static final Region FORM_EVENTS = new Region("ОбработчикиСобытийФормы", "FormEventHandlers"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Handlers of events of the items in the form header. */
    static final Region HEADER_ITEM_EVENTS =
        new Region("ОбработчикиСобытийЭлементовШапкиФормы", "FormHeaderItemsEventHandlers"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Handlers of form commands. */
    static final Region COMMANDS = new Region("ОбработчикиКомандФормы", "FormCommandsEventHandlers"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Pattern REGION_OPEN =
        Pattern.compile("^\\s*#\\s*(Область|Region)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern REGION_CLOSE =
        Pattern.compile("^\\s*#\\s*(КонецОбласти|EndRegion)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Compilation directives, English spelling to the Russian one the stubs are written in. */
    private static final Map<String, String> DIRECTIVES = Map.of(
        "&atclient", "&НаКлиенте", //$NON-NLS-1$ //$NON-NLS-2$
        "&atserver", "&НаСервере", //$NON-NLS-1$ //$NON-NLS-2$
        "&atservernocontext", "&НаСервереБезКонтекста", //$NON-NLS-1$ //$NON-NLS-2$
        "&atclientatservernocontext", "&НаКлиентеНаСервереБезКонтекста", //$NON-NLS-1$ //$NON-NLS-2$
        "&atclientatserver", "&НаКлиентеНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * A module region that holds one kind of handler, by its Russian and English names.
     */
    static final class Region
    {
        /** The Russian name, used when the region is created in a module written in Russian. */
        final String russian;

        /** The English name. */
        final String english;

        /**
         * @param russian the Russian name
         * @param english the English name
         */
        Region(String russian, String english)
        {
            this.russian = russian;
            this.english = english;
        }

        /**
         * The region of handlers of the items of one form table.
         *
         * @param table the table's name
         * @return the region
         */
        static Region tableItemEvents(String table)
        {
            return new Region("ОбработчикиСобытийЭлементовТаблицыФормы" + table, //$NON-NLS-1$
                "FormTableItemsEventHandlers" + table); //$NON-NLS-1$
        }
    }

    private FormModuleStubs()
    {
    }

    /**
     * What happened to one stub.
     */
    static final class Outcome
    {
        /** Whether the procedure was appended to the module. */
        final boolean written;

        /** Why nothing was written without an error, or <code>null</code>. */
        final String skippedReason;

        /** The module's path under {@code src/}, or <code>null</code> when it could not be named. */
        final String modulePath;

        /** Why the write failed, or <code>null</code>. */
        final String error;

        /** The region the procedure was written into, or <code>null</code>. */
        String region;

        /** Whether that region was created for it. */
        boolean regionCreated;

        /**
         * How a procedure the module already declares differs from what the event calls, or
         * <code>null</code> when it does not.
         */
        String existingMismatch;

        private Outcome(boolean written, String skippedReason, String modulePath, String error)
        {
            this.written = written;
            this.skippedReason = skippedReason;
            this.modulePath = modulePath;
            this.error = error;
        }

        /**
         * Adds the outcome to an answer as {@code stubWritten}, {@code stubSkippedReason},
         * {@code modulePath}, {@code stubError}, {@code stubRegion}, {@code stubRegionCreated} and
         * {@code existingProcedureMismatch}.
         *
         * @param answer the answer to extend
         * @return the same answer
         */
        ToolResult putInto(ToolResult answer)
        {
            answer.put("stubWritten", written); //$NON-NLS-1$
            if (skippedReason != null)
            {
                answer.put("stubSkippedReason", skippedReason); //$NON-NLS-1$
            }
            if (modulePath != null)
            {
                answer.put("modulePath", "src/" + modulePath); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (error != null)
            {
                answer.put("stubError", error); //$NON-NLS-1$
            }
            if (region != null)
            {
                answer.put("stubRegion", region); //$NON-NLS-1$
                answer.put("stubRegionCreated", regionCreated); //$NON-NLS-1$
            }
            if (existingMismatch != null)
            {
                answer.put("existingProcedureMismatch", existingMismatch); //$NON-NLS-1$
            }
            return answer;
        }
    }

    /**
     * Names the module file of a form.
     * <p>
     * Accepts {@code Type.Name.Form.FormName}, the same with a trailing {@code .Form} segment the BM
     * model uses, and {@code CommonForm.Name}; types and the form keyword in either language.
     * </p>
     *
     * @param formFqn the form's FQN
     * @return the path under {@code src/}, or <code>null</code> when the FQN names no form module
     */
    static String modulePathOf(String formFqn)
    {
        if (formFqn == null || formFqn.isEmpty())
        {
            return null;
        }
        String[] parts = formFqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        String dir = MetadataTypeCatalog.getDirectoryName(parts[0]);
        if (dir == null || parts[1].isEmpty())
        {
            return null;
        }
        if ("CommonForm".equals(MetadataTypeCatalog.toEnglishSingular(parts[0]))) //$NON-NLS-1$
        {
            boolean bare = parts.length == 2
                || parts.length == 3 && BslModuleAccess.isFormKeyword(parts[2]);
            return bare ? dir + "/" + parts[1] + "/Module.bsl" : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean formAddress = (parts.length == 4
            || parts.length == 5 && BslModuleAccess.isFormKeyword(parts[4]))
            && BslModuleAccess.isFormKeyword(parts[2]) && !parts[3].isEmpty();
        return formAddress ? dir + "/" + parts[1] + "/Forms/" + parts[3] + "/Module.bsl" : null; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The procedure a form command calls, as the platform declares it: on the client, taking the
     * command.
     *
     * @param handler the procedure name
     * @return the procedure text
     */
    static String commandHandlerStub(String handler)
    {
        return "\n&НаКлиенте\nПроцедура " + handler + "(Команда)\n    \nКонецПроцедуры\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Appends a procedure to the end of the form's module when the module does not declare it yet.
     *
     * @param project the project holding the form
     * @param formFqn the form's FQN
     * @param procedureName the procedure the stub declares
     * @param stub the procedure text
     * @param dryRun <code>true</code> to report the module without writing
     * @return what happened; never <code>null</code>
     */
    static Outcome append(IProject project, String formFqn, String procedureName, String stub,
        boolean dryRun)
    {
        return append(project, formFqn, procedureName, stub, null, dryRun);
    }

    /**
     * Adds a procedure to the form's module when the module does not declare it yet: before the end
     * of the given region when the module has it, in that region created at the end of the module
     * when it does not.
     *
     * @param project the project holding the form
     * @param formFqn the form's FQN
     * @param procedureName the procedure the stub declares
     * @param stub the procedure text, opening with a line break
     * @param region the region the procedure belongs to, or <code>null</code> to append it to the end
     *            of the module outside any region
     * @param dryRun <code>true</code> to report the module without writing
     * @return what happened; never <code>null</code>
     */
    static Outcome append(IProject project, String formFqn, String procedureName, String stub,
        Region region, boolean dryRun)
    {
        String modulePath = modulePathOf(formFqn);
        if (modulePath == null)
        {
            return new Outcome(false, null, null,
                "formFqn '" + formFqn + "' names no form module"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (dryRun)
        {
            return new Outcome(false, DRY_RUN, modulePath, null);
        }
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        String text;
        try
        {
            text = file.exists() ? BslModuleAccess.readFileText(file) : ""; //$NON-NLS-1$
        }
        catch (Exception unreadable)
        {
            return new Outcome(false, null, modulePath,
                "the module could not be read: " + unreadable.getMessage()); //$NON-NLS-1$
        }
        text = text.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (GenerateEventHandlersTool.declares(text, procedureName))
        {
            Outcome present = new Outcome(false, ALREADY_PRESENT, modulePath, null);
            present.existingMismatch = shapeMismatch(text, procedureName, stub);
            return present;
        }
        Map<String, String> write = new LinkedHashMap<>();
        write.put("projectName", project.getName()); //$NON-NLS-1$
        write.put("modulePath", modulePath); //$NON-NLS-1$
        int regionEnd = region == null ? -1 : regionEndLine(text, region);
        String[] lines = text.split("\n", -1); //$NON-NLS-1$
        if (regionEnd > 0)
        {
            boolean blankAbove = regionEnd >= 2 && lines[regionEnd - 2].isBlank();
            write.put("mode", ModuleSourceWriter.MODE_INSERT_BEFORE); //$NON-NLS-1$
            write.put("line", String.valueOf(regionEnd)); //$NON-NLS-1$
            write.put("source", (blankAbove && stub.startsWith("\n") ? stub.substring(1) : stub) + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else
        {
            write.put("mode", ModuleSourceWriter.MODE_APPEND); //$NON-NLS-1$
            write.put("source", region == null ? stub : wrapInRegion(stub, region, text)); //$NON-NLS-1$
        }
        String answer = new ModuleSourceWriter().execute(write);
        if (answer != null && answer.contains(WRITE_FINISHED))
        {
            Outcome done = new Outcome(true, null, modulePath, null);
            if (region != null)
            {
                done.region = regionEnd > 0 ? regionNameIn(text, region) : newRegionName(region, text);
                done.regionCreated = regionEnd <= 0;
            }
            return done;
        }
        return new Outcome(false, null, modulePath,
            answer == null ? "the module writer answered nothing" : firstLine(answer)); //$NON-NLS-1$
    }

    /**
     * The line that closes a region of the module, counting nested regions.
     *
     * @param text the module text with {@code \n} line breaks
     * @param region the region, found by either of its names, ignoring case
     * @return the 1-based number of the line closing the first region of that name, or {@code -1} when
     *         the module has no such region or it is not closed
     */
    static int regionEndLine(String text, Region region)
    {
        Pattern opening = Pattern.compile("^\\s*#\\s*(Область|Region)\\s+(" //$NON-NLS-1$
            + Pattern.quote(region.russian) + "|" + Pattern.quote(region.english) + ")\\s*$", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
        String[] lines = text.split("\n", -1); //$NON-NLS-1$
        for (int i = 0; i < lines.length; i++)
        {
            if (!opening.matcher(lines[i]).find())
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
            return -1;
        }
        return -1;
    }

    /**
     * The procedure text inside a new region, in the language of the module's own regions.
     *
     * @param stub the procedure text, opening with a line break
     * @param region the region
     * @param text the module text
     * @return the region with the procedure, opening with a line break
     */
    private static String wrapInRegion(String stub, Region region, String text)
    {
        boolean english = usesEnglishRegions(text);
        return "\n" + (english ? "#Region " : "#Область ") + newRegionName(region, text) + "\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + stub + "\n" + (english ? "#EndRegion" : "#КонецОбласти") + "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Whether the module writes its regions in English.
     *
     * @param text the module text
     * @return <code>true</code> when a region opens with {@code #Region}
     */
    private static boolean usesEnglishRegions(String text)
    {
        return Pattern.compile("^\\s*#\\s*Region\\b", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE) //$NON-NLS-1$
            .matcher(text).find();
    }

    /**
     * The name a new region gets in this module.
     *
     * @param region the region
     * @param text the module text
     * @return the English name in a module with English regions, the Russian one otherwise
     */
    private static String newRegionName(Region region, String text)
    {
        return usesEnglishRegions(text) ? region.english : region.russian;
    }

    /**
     * The name of a region as the module spells it.
     *
     * @param text the module text
     * @param region the region the module has
     * @return the Russian name when the module uses it, the English one otherwise
     */
    private static String regionNameIn(String text, Region region)
    {
        return text.toLowerCase(Locale.ROOT).contains(region.russian.toLowerCase(Locale.ROOT))
            ? region.russian : region.english;
    }

    /**
     * How the procedure a module declares differs from the stub the event would have got: its
     * compilation directive and its number of parameters.
     * <p>
     * A procedure without a directive is read as {@code &НаСервере}, which is what a form module
     * compiles it as. Directives are compared in Russian spelling.
     * </p>
     *
     * @param text the module text with {@code \n} line breaks
     * @param procedureName the procedure
     * @param stub the stub the event would have got
     * @return a sentence naming both shapes, or <code>null</code> when they agree or either cannot be
     *         read
     */
    static String shapeMismatch(String text, String procedureName, String stub)
    {
        Declaration existing = Declaration.find(text.replace("\r\n", "\n"), procedureName); //$NON-NLS-1$ //$NON-NLS-2$
        Declaration expected = Declaration.find(stub.replace("\r\n", "\n"), procedureName); //$NON-NLS-1$ //$NON-NLS-2$
        if (existing == null || expected == null)
        {
            return null;
        }
        if (existing.directive.equals(expected.directive) && existing.parameters == expected.parameters)
        {
            return null;
        }
        return "the module declares " + existing.directive + " " + procedureName + " with " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + existing.parameters + " parameter(s); the event calls " + expected.directive + " " //$NON-NLS-1$ //$NON-NLS-2$
            + procedureName + "(" + expected.parameterText + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * One procedure declaration: its directive and parameters.
     */
    private static final class Declaration
    {
        final String directive;

        final int parameters;

        final String parameterText;

        private Declaration(String directive, int parameters, String parameterText)
        {
            this.directive = directive;
            this.parameters = parameters;
            this.parameterText = parameterText;
        }

        /**
         * Reads the declaration of a procedure or function and the directive above it.
         *
         * @param text module text with {@code \n} line breaks
         * @param name the procedure name, matched ignoring case
         * @return the declaration, or <code>null</code> when the text does not declare it
         */
        static Declaration find(String text, String name)
        {
            Matcher header = Pattern.compile("^[ \\t]*(Процедура|Функция|Procedure|Function)[ \\t]+" //$NON-NLS-1$
                + Pattern.quote(name) + "[ \\t]*\\(([^)]*)\\)", //$NON-NLS-1$
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS).matcher(text);
            if (!header.find())
            {
                return null;
            }
            String parameterText = header.group(2).trim();
            int parameters = parameterText.isEmpty() ? 0 : parameterText.split(",").length; //$NON-NLS-1$
            String directive = "&НаСервере"; //$NON-NLS-1$
            String[] above = text.substring(0, header.start()).split("\n", -1); //$NON-NLS-1$
            for (int i = above.length - 1; i >= 0; i--)
            {
                String line = above[i].trim();
                if (line.isEmpty() && i == above.length - 1)
                {
                    continue;
                }
                if (!line.startsWith("&") && !line.startsWith("//")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    break;
                }
                if (line.startsWith("&") && !line.contains("(")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    directive = canonicalDirective(line);
                    break;
                }
            }
            return new Declaration(directive, parameters, parameterText);
        }

        /**
         * A directive in the Russian spelling the stubs are written in.
         *
         * @param line the directive line
         * @return the Russian spelling, with the case the stubs use when the directive is known
         */
        private static String canonicalDirective(String line)
        {
            String key = line.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
            String russian = DIRECTIVES.get(key);
            if (russian != null)
            {
                return russian;
            }
            for (String known : DIRECTIVES.values())
            {
                if (known.toLowerCase(Locale.ROOT).equals(key))
                {
                    return known;
                }
            }
            return line;
        }
    }

    /**
     * The first line of a writer's refusal, which carries its reason.
     *
     * @param answer the writer's answer
     * @return the first non-empty line
     */
    private static String firstLine(String answer)
    {
        for (String line : answer.split("\r?\n")) //$NON-NLS-1$
        {
            if (!line.isBlank())
            {
                return line.trim();
            }
        }
        return answer.trim();
    }
}
