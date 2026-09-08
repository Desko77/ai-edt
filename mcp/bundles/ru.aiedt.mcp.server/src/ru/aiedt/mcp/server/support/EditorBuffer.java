/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;

import ru.aiedt.mcp.server.Activator;

/**
 * What an open editor holds for a file, which is not always what the file holds.
 * <p>
 * An editor with unsaved changes keeps them in a document that the file does not have yet. A tool
 * reading the file therefore answers about a state the user is no longer looking at, and a tool
 * writing the file overwrites work the user has not saved - in silence, because the resource API
 * neither knows nor asks about editors.
 * </p>
 * <p>
 * Read through the platform's file buffers rather than through the workbench: a buffer is connected
 * by whoever opened the file and is readable from any thread, so this needs neither the user
 * interface nor the display thread.
 * </p>
 */
public final class EditorBuffer
{
    private EditorBuffer()
    {
        // Read through the static entry points.
    }

    /**
     * The buffer connected for a file, or <code>null</code> when nothing holds it open.
     *
     * @param file the file; may be <code>null</code>.
     * @return the buffer, or <code>null</code>
     */
    private static ITextFileBuffer bufferOf(IFile file)
    {
        if (file == null)
        {
            return null;
        }
        try
        {
            ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
            return manager == null ? null
                : manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        }
        catch (RuntimeException | LinkageError absent)
        {
            // No buffer manager in this runtime: every caller then reads and writes the file, which
            // is what happened before this existed. Said out loud rather than swallowed, because a
            // silent "no editor holds it" is the answer that loses work.
            Activator.logDebug("file buffers unavailable: " + absent); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Whether an editor holds this file with changes the file does not have.
     *
     * @param file the file; may be <code>null</code>.
     * @return <code>true</code> when a connected buffer is dirty
     */
    public static boolean hasUnsavedChanges(IFile file)
    {
        ITextFileBuffer buffer = bufferOf(file);
        return buffer != null && buffer.isDirty();
    }

    /**
     * What the editor holds, when it holds something the file does not.
     *
     * @param file the file; may be <code>null</code>.
     * @return the unsaved text, or <code>null</code> when no editor holds this file or its buffer
     *         matches the file
     */
    public static String unsavedText(IFile file)
    {
        ITextFileBuffer buffer = bufferOf(file);
        if (buffer == null || !buffer.isDirty() || buffer.getDocument() == null)
        {
            return null;
        }
        return buffer.getDocument().get();
    }

    /**
     * The unsaved text split the way the file readers split it, or <code>null</code>.
     * <p>
     * A trailing line delimiter ends the last line rather than starting an empty one, which is what
     * reading the file line by line gives - the two have to agree or a line number would mean one
     * thing from the editor and another from disk.
     * </p>
     *
     * @param file the file; may be <code>null</code>.
     * @return the lines, or <code>null</code> when nothing unsaved is held
     */
    public static List<String> unsavedLines(IFile file)
    {
        String text = unsavedText(file);
        return text == null ? null : linesOf(text);
    }

    /**
     * Splits text the way the file readers split it.
     *
     * @param text the text.
     * @return one entry per line, a trailing delimiter ending the last rather than starting an
     *         empty one
     */
    static List<String> linesOf(String text)
    {
        List<String> lines = new ArrayList<>();
        int from = 0;
        for (int at = 0; at < text.length(); at++)
        {
            char one = text.charAt(at);
            if (one == '\n' || one == '\r')
            {
                lines.add(text.substring(from, at));
                if (one == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n')
                {
                    at++;
                }
                from = at + 1;
            }
        }
        if (from < text.length())
        {
            lines.add(text.substring(from));
        }
        return lines;
    }
}
