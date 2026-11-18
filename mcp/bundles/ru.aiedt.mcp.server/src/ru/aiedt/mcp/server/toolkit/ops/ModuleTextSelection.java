/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.support.EditorBuffer;
import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * The one line sequence both source readers answer from.
 * <p>
 * A module address may be owned by a registered {@link IModuleSource} provider, held by an open
 * editor with unsaved changes, or be a plain file on disk. The precedence is fixed here so
 * {@code read_module_source} and {@code read_method_source} cannot disagree about which text they
 * are describing: the first applicable provider, otherwise the dirty editor buffer, otherwise the
 * file. A provider wins even when a physical file also exists; a clean buffer is not a source and
 * leaves the disk text in place. When there is neither a provider nor a file the selection is
 * {@link Origin#ABSENT} and the caller keeps its own refusal.
 * </p>
 * <p>
 * The selection is made once per tool call. The dirty lines are captured at that moment, so the
 * answer describes one revision of the text rather than a mixture of the buffer and the file. Only
 * the lines are selected here; each caller renders its own header, origin note and errors.
 * </p>
 */
public final class ModuleTextSelection
{
    /** Where the selected lines would come from. */
    public enum Origin
    {
        /** A registered module source provider owns the address. */
        PROVIDER,

        /** An open editor holds unsaved changes for the file. */
        EDITOR,

        /** The file on disk is the only source. */
        DISK,

        /** No provider owns the address and no file exists there. */
        ABSENT
    }

    private final Origin origin;

    private final IModuleSource provided;

    private final IFile file;

    private final List<String> editorLines;

    private ModuleTextSelection(Origin origin, IModuleSource provided, IFile file,
        List<String> editorLines)
    {
        this.origin = origin;
        this.provided = provided;
        this.file = file;
        this.editorLines = editorLines;
    }

    /**
     * Selects the source of a module address, capturing dirty editor lines immediately.
     * Provider and disk lines are read when {@link #lines()} is called.
     *
     * @param project the project that owns the module
     * @param modulePath the resolved {@code src}-relative module path
     * @param file the file the address names, or <code>null</code> when there is none
     * @return the selection, never <code>null</code>
     */
    public static ModuleTextSelection select(IProject project, String modulePath, IFile file)
    {
        IModuleSource provided = ModuleSources.locate(project, modulePath);
        if (provided != null)
        {
            return new ModuleTextSelection(Origin.PROVIDER, provided, file, null);
        }
        if (file != null && file.exists())
        {
            List<String> unsaved = EditorBuffer.unsavedLines(file);
            if (unsaved != null)
            {
                return new ModuleTextSelection(Origin.EDITOR, null, file, unsaved);
            }
            return new ModuleTextSelection(Origin.DISK, null, file, null);
        }
        return new ModuleTextSelection(Origin.ABSENT, null, file, null);
    }

    /** @return <code>true</code> when a provider owns the address */
    public boolean isProvider()
    {
        return origin == Origin.PROVIDER;
    }

    /** @return <code>true</code> when a dirty editor buffer is the source */
    public boolean isEditor()
    {
        return origin == Origin.EDITOR;
    }

    /** @return <code>true</code> when the file on disk is the source */
    public boolean isDisk()
    {
        return origin == Origin.DISK;
    }

    /** @return <code>true</code> when neither a provider nor a file exists at the address */
    public boolean isAbsent()
    {
        return origin == Origin.ABSENT;
    }

    /** @return the provider that owns the address, or <code>null</code> for another origin */
    public IModuleSource provider()
    {
        return provided;
    }

    /**
     * The selected text as lines without terminators.
     *
     * @return the lines; empty for an empty source
     * @throws Exception when the selected provider or file cannot be read
     * @throws IllegalStateException when the selection is {@link Origin#ABSENT}
     */
    public List<String> lines() throws Exception
    {
        switch (origin)
        {
            case PROVIDER:
                return provided.lines();
            case EDITOR:
                return editorLines;
            case DISK:
                return BslModuleAccess.readFileLines(file);
            default:
                throw new IllegalStateException("no module source at this address"); //$NON-NLS-1$
        }
    }
}
