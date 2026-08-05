/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Which line ending a file written by this plugin gets.
 * <p>
 * Tools here read a module, normalise it to <code>\n</code> so searching and line addressing have
 * one form to reason about, edit it, and write it back. Writing it back is where the normalisation
 * has to be undone: joined with <code>\n</code>, every module the plugin touches silently converts
 * to LF, while the twenty-odd thousand around it - written by EDT, by the Designer, by an import
 * from the repository - stay CRLF. The result is a diff on every line of an otherwise one-line
 * change.
 * </p>
 * <p>
 * A <code>.gitattributes</code> rule does not cover this. Git applies <code>eol</code> on checkout;
 * a file the plugin puts on disk never passes through that.
 * </p>
 * <p>
 * The answer comes from the file itself whenever the file can give one. That beats any preference:
 * it keeps a module in the form its neighbours are already in, and it holds even in a workspace
 * where nobody ever set a line-separator preference - which is the normal case, since EDT does not
 * write one. Only when there is nothing to copy - a new file, or one with no line break in it - is
 * the Eclipse preference chain asked, exactly as the platform's own editors ask it: project scope,
 * then workspace, then the running JVM's default.
 * </p>
 */
public final class LineDelimiters
{
    /** Windows line ending, and what EDT writes on it. */
    public static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** Unix line ending, and the form every tool here works in internally. */
    public static final String LF = "\n"; //$NON-NLS-1$

    /** Read granularity while looking for the first line break. */
    private static final int SNIFF_CHUNK = 8 * 1024;

    private LineDelimiters()
    {
        // utility
    }

    /**
     * The line ending to write {@code file} with.
     *
     * @param file the target, may be <code>null</code> and need not exist
     * @return the delimiter, never <code>null</code>
     */
    public static String of(IFile file)
    {
        String fromFile = sniff(file);
        return fromFile != null ? fromFile
            : forNewContent(file != null ? file.getProject() : null);
    }

    /**
     * The delimiter for content with nothing to copy from - a file being created from scratch.
     * <p>
     * Worth calling even for a one-line placeholder. A new module seeded with the wrong ending
     * keeps it: {@link #of(IFile)} copies whatever the file already has, so the first write decides
     * the form of every write after it.
     * </p>
     *
     * @param project the project the file will live in, may be <code>null</code>
     * @return the delimiter, never <code>null</code>
     */
    public static String forNewContent(IProject project)
    {
        return preferred(project);
    }

    /**
     * Rewrites every line ending in {@code content} as {@code delimiter}.
     * <p>
     * Accepts CRLF, LF and a lone CR on input, so content assembled from several sources ends up in
     * one form rather than a mixture.
     * </p>
     *
     * @param content the text, may be <code>null</code>
     * @param delimiter the ending to produce
     * @return the rewritten text, or <code>null</code> when {@code content} was
     */
    public static String rewrite(String content, String delimiter)
    {
        if (content == null || content.isEmpty())
        {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length() + 16);
        int length = content.length();
        for (int i = 0; i < length; i++)
        {
            char c = content.charAt(i);
            if (c == '\r')
            {
                out.append(delimiter);
                if (i + 1 < length && content.charAt(i + 1) == '\n')
                {
                    i++;
                }
            }
            else if (c == '\n')
            {
                out.append(delimiter);
            }
            else
            {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Reads the first line break out of an existing file.
     * <p>
     * The first one decides, rather than a count of each kind. A file with mixed endings is already
     * damaged, and following its majority would rewrite the minority lines - turning a small edit
     * into a whole-file diff, which is the very thing this class exists to avoid.
     * </p>
     * <p>
     * The scan runs to the end of the file rather than giving up after a prefix. Any real module
     * answers in its first line, so a cap would only ever be reached by a file that is one enormous
     * line - and that is precisely where guessing wrong rewrites the most.
     * </p>
     *
     * @param file the file to look at, may be <code>null</code>
     * @return the delimiter found, or <code>null</code> when there is none to copy
     */
    private static String sniff(IFile file)
    {
        if (file == null || !file.exists())
        {
            return null;
        }
        try (InputStream in = file.getContents(true);
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
        {
            char[] buffer = new char[SNIFF_CHUNK];
            boolean pendingCr = false;
            int count;
            while ((count = reader.read(buffer)) != -1)
            {
                for (int i = 0; i < count; i++)
                {
                    char c = buffer[i];
                    if (pendingCr)
                    {
                        return c == '\n' ? CRLF : "\r"; //$NON-NLS-1$
                    }
                    if (c == '\n')
                    {
                        return LF;
                    }
                    if (c == '\r')
                    {
                        pendingCr = true;
                    }
                }
            }
            // A carriage return as the very last character: nothing followed it, so it stands alone.
            return pendingCr ? "\r" : null; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            // An unreadable file is not a reason to fail the write - the caller is about to replace
            // its contents anyway. Fall through to the preference.
            return null;
        }
    }

    /**
     * The configured delimiter for new content, by the platform's own resolution order.
     *
     * @param project the project the content belongs to, may be <code>null</code>
     * @return the delimiter, never <code>null</code>
     */
    private static String preferred(IProject project)
    {
        IScopeContext[] scopes = project != null
            ? new IScopeContext[] {new ProjectScope(project), InstanceScope.INSTANCE}
            : new IScopeContext[] {InstanceScope.INSTANCE};
        try
        {
            String configured = Platform.getPreferencesService().getString(Platform.PI_RUNTIME,
                Platform.PREF_LINE_SEPARATOR, System.lineSeparator(), scopes);
            return configured == null || configured.isEmpty() ? System.lineSeparator() : configured;
        }
        catch (Exception e)
        {
            return System.lineSeparator();
        }
    }
}
