/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.jface.text.Document;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.IXtextDocumentContentObserver;
import org.eclipse.xtext.ui.editor.model.IXtextModelListener;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

/**
 * A module's text and its parsed model, presented together the way the language services expect,
 * without an editor behind it.
 * <p>
 * BSL's proposal provider asks its context for a document several dozen times, and that document has
 * to be the Xtext kind - the one that hands out the parsed resource on request. The environment's own
 * implementation of that pairing belongs to an open editor: it owns the resource, drives reconciling
 * and writes changes back. Borrowing it for a question would mean pointing a second owner at a
 * resource an editor may already hold.
 * </p>
 * <p>
 * This is the read half of that contract and nothing else. The text is a snapshot, the resource is the
 * one already loaded, and writing is refused rather than quietly ignored - a caller that tries to
 * modify a module through a question deserves to hear about it.
 * </p>
 */
public final class ReadOnlyModuleDocument
    extends Document
    implements IXtextDocument
{
    private final XtextResource resource;

    /**
     * @param text the module's text
     * @param resource the module's already-loaded resource
     */
    public ReadOnlyModuleDocument(String text, XtextResource resource)
    {
        super(text);
        this.resource = resource;
    }

    @Override
    public <T> T readOnly(IUnitOfWork<T, XtextResource> work)
    {
        return exec(work);
    }

    @Override
    public <T> T priorityReadOnly(IUnitOfWork<T, XtextResource> work)
    {
        return exec(work);
    }

    @Override
    public <T> T modify(IUnitOfWork<T, XtextResource> work)
    {
        throw new UnsupportedOperationException("A module is never modified while answering a question"); //$NON-NLS-1$
    }

    @Override
    public void addModelListener(IXtextModelListener listener)
    {
        // Nothing changes here, so there is nothing to announce.
    }

    @Override
    public void removeModelListener(IXtextModelListener listener)
    {
        // Nothing was ever registered.
    }

    @Override
    public void addXtextDocumentContentObserver(IXtextDocumentContentObserver observer)
    {
        // As above: a snapshot has no content events to observe.
    }

    @Override
    public void removeXtextDocumentContentObserver(IXtextDocumentContentObserver observer)
    {
        // As above.
    }

    /**
     * Runs a unit of work against the resource.
     * <p>
     * No lock is taken. The editor's document serializes readers against its own writer; here there is
     * no writer, and the resource is read the same way the plugin's other tools read it.
     * </p>
     *
     * @param work the unit of work
     * @return whatever the work returns
     */
    private <T> T exec(IUnitOfWork<T, XtextResource> work)
    {
        try
        {
            return work.exec(this.resource);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
