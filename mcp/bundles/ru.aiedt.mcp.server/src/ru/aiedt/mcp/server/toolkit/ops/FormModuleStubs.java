/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;

import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Appends a handler procedure to the module of a form, unless the module already declares it.
 * <p>
 * The text goes through {@link ModuleSourceWriter#execute} in {@code append} mode, so the write gets
 * the writer's refusal on an editor with unsaved changes, its syntax check and the line delimiters
 * the file already uses. Does not bind the handler to the form: the caller does that in its own
 * transaction before calling this.
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

        private Outcome(boolean written, String skippedReason, String modulePath, String error)
        {
            this.written = written;
            this.skippedReason = skippedReason;
            this.modulePath = modulePath;
            this.error = error;
        }

        /**
         * Adds the outcome to an answer as {@code stubWritten}, {@code stubSkippedReason},
         * {@code modulePath} and {@code stubError}.
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
     * Appends a procedure to the form's module when the module does not declare it yet.
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
        try
        {
            if (file.exists() && GenerateEventHandlersTool.declares(BslModuleAccess.readFileText(file),
                procedureName))
            {
                return new Outcome(false, ALREADY_PRESENT, modulePath, null);
            }
        }
        catch (Exception unreadable)
        {
            return new Outcome(false, null, modulePath,
                "the module could not be read: " + unreadable.getMessage()); //$NON-NLS-1$
        }
        Map<String, String> write = new LinkedHashMap<>();
        write.put("projectName", project.getName()); //$NON-NLS-1$
        write.put("modulePath", modulePath); //$NON-NLS-1$
        write.put("mode", ModuleSourceWriter.MODE_APPEND); //$NON-NLS-1$
        write.put("source", stub); //$NON-NLS-1$
        String answer = new ModuleSourceWriter().execute(write);
        if (answer != null && answer.contains(WRITE_FINISHED))
        {
            return new Outcome(true, null, modulePath, null);
        }
        return new Outcome(false, null, modulePath,
            answer == null ? "the module writer answered nothing" : firstLine(answer)); //$NON-NLS-1$
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
