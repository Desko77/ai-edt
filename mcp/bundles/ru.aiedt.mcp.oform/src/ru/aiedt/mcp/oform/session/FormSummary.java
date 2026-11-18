/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.OrdinaryFormLocator;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * What a {@code Form.oform} holds, counted: the layout version, the number of elements the
 * reader recognised and the number of records of kinds it did not. Read only; nothing here
 * writes the container.
 */
public final class FormSummary
{
    /** The version token the layout opens with, or {@code null} when there is none. */
    public final String version;

    /** Elements the reader recognised, nested ones included. */
    public final int elementCount;

    /** Records of kinds the reader does not recognise, all kinds summed. */
    public final int unreadRecords;

    /** Whether the version is one the layout writers were measured on. */
    public final boolean versionSupported;

    /** Why the container could not be read, or {@code null} when it was. */
    public final String problem;

    /** Holds the counts; whether the version is supported follows from it. */
    private FormSummary(String version, int elementCount, int unreadRecords, String problem)
    {
        this.version = version;
        this.elementCount = elementCount;
        this.unreadRecords = unreadRecords;
        this.versionSupported = version != null && LayoutEditor.SUPPORTED_VERSIONS.contains(version);
        this.problem = problem;
    }

    /**
     * Reads the summary of a form file in the workspace. The owner kind, which names the standard
     * attributes of the main attribute, is taken from where the file lies.
     *
     * @param file the {@code Form.oform}
     * @return the summary; a file that cannot be read gives a summary with {@link #problem}
     */
    public static FormSummary read(IFile file)
    {
        byte[] bytes;
        try (InputStream in = file.getContents(true))
        {
            bytes = in.readAllBytes();
        }
        catch (CoreException | IOException e)
        {
            return failed(null, e.getMessage());
        }
        return of(bytes, ownerKindOf(file));
    }

    /**
     * Reads the summary of container bytes.
     *
     * @param bytes the content of a {@code Form.oform}
     * @param ownerKind the owner's metadata kind in EDT's English spelling, or {@code null}
     * @return the summary; bytes that are not a form give a summary with {@link #problem}
     */
    public static FormSummary of(byte[] bytes, String ownerKind)
    {
        OrdinaryFormFile file;
        try
        {
            file = OrdinaryFormFile.of(null, V8Container.read(bytes));
        }
        catch (IllegalArgumentException e)
        {
            return failed(null, e.getMessage());
        }
        String version = file.formVersion();
        try
        {
            FormLayout layout = LayoutReader.read(file.formTree(), ownerKind);
            int unread = 0;
            for (Map.Entry<String, Integer> entry : layout.unread.entrySet())
            {
                unread += entry.getValue().intValue();
            }
            return new FormSummary(version, layout.allElements().size(), unread, null);
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            return failed(version, e.getMessage());
        }
    }

    /** A summary of a container that could not be read, with the reason. */
    private static FormSummary failed(String version, String message)
    {
        return new FormSummary(version, 0, 0, message == null ? "unreadable" : message); //$NON-NLS-1$
    }

    /** The owner's metadata kind by where the file lies, or {@code null} for a common form or an unknown path. */
    private static String ownerKindOf(IFile file)
    {
        IPath location = file.getLocation();
        IPath project = file.getProject().getLocation();
        if (location == null || project == null)
        {
            return null;
        }
        Path src = project.append("src").toPath(); //$NON-NLS-1$
        String fqn;
        try
        {
            fqn = OrdinaryFormLocator.fqnOf(src, location.toPath());
        }
        catch (IllegalArgumentException e)
        {
            // A linked file on another root does not relativize against src.
            return null;
        }
        if (fqn == null)
        {
            return null;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        return parts.length == 4 ? parts[0] : null;
    }
}
