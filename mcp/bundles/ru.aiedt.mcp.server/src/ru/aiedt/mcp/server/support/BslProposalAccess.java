/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextSourceViewerConfiguration;
import org.eclipse.xtext.ui.editor.contentassist.IContentAssistantFactory;
import org.eclipse.xtext.ui.editor.model.XtextDocument;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * Asks the language what could be typed at a position, without opening the module in an editor.
 * <p>
 * The environment assembles its own content assistant for a text viewer, and that is what is asked
 * here. An earlier attempt took the assistant apart instead - fetch the context factory, build the
 * contexts, hand the proposal provider an acceptor - and every piece of that reconstruction measured
 * correct while the answer stayed empty. Nine rounds of measurement later the lesson is plain: call
 * the environment's machinery rather than rebuild it. Everything it needs is supplied except the
 * editor.
 * </p>
 * <p>
 * Two things it does need, and both are what the reconstruction got wrong. It runs on the thread that
 * draws the workbench - not because anything is drawn, but because that is where the toolkit allows
 * its objects to be touched. And it needs a real text widget to pin its bookkeeping to
 * ({@link OffScreenWidget}): withholding one fails outright, and faking a way around it turns a loud
 * failure into a silent empty list, which is the trap that cost those nine rounds.
 * </p>
 * <p>
 * What the caller is spared is the visible part: no tab opens, no focus moves, no caret jumps, and no
 * module is left open behind them.
 * </p>
 */
public final class BslProposalAccess
{
    private BslProposalAccess()
    {
    }

    /**
     * Collects the completion proposals the environment would offer at a position.
     *
     * @param text the module's text
     * @param resource the module's already-loaded resource
     * @param offset the character offset the question is asked at
     * @return the proposals, never <code>null</code>
     * @throws IllegalStateException when the language contributes no content assist
     */
    public static ICompletionProposal[] proposalsAt(String text, XtextResource resource, int offset)
    {
        IResourceServiceProvider services = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
        if (services == null)
        {
            throw new IllegalStateException("The BSL language services are not registered"); //$NON-NLS-1$
        }
        IContentAssistantFactory assistants = services.get(IContentAssistantFactory.class);
        SourceViewerConfiguration configuration = services.get(XtextSourceViewerConfiguration.class);
        if (assistants == null || configuration == null)
        {
            throw new IllegalStateException("The BSL language contributes no content assist"); //$NON-NLS-1$
        }

        IDocument document = languageDocument(services, text, resource);
        OffScreenTextViewer viewer = new OffScreenTextViewer(document, offset);

        AtomicReference<ICompletionProposal[]> answer = new AtomicReference<>(new ICompletionProposal[0]);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                answer.set(ask(assistants, configuration, viewer, document, offset));
            }
            catch (RuntimeException e)
            {
                failure.set(e);
            }
        });
        if (failure.get() != null)
        {
            throw failure.get();
        }
        return answer.get();
    }

    /**
     * Puts the question to the assistant the environment builds for this viewer.
     *
     * @param assistants the language's assistant factory
     * @param configuration the language's viewer configuration
     * @param viewer the off-screen viewer
     * @param document the document the viewer hands back
     * @param offset the character offset
     * @return the proposals, never <code>null</code>
     */
    private static ICompletionProposal[] ask(IContentAssistantFactory assistants,
        SourceViewerConfiguration configuration, OffScreenTextViewer viewer, IDocument document,
        int offset)
    {
        IContentAssistant assistant = assistants.createConfiguredAssistant(configuration, viewer);
        String partitioning = configuration.getConfiguredDocumentPartitioning(viewer);
        String contentType;
        try
        {
            contentType = TextUtilities.getContentType(document, partitioning, offset, true);
        }
        catch (BadLocationException e)
        {
            contentType = IDocument.DEFAULT_CONTENT_TYPE;
        }
        IContentAssistProcessor processor = assistant.getContentAssistProcessor(contentType);
        if (processor == null)
        {
            Activator.logWarning("No content assist is registered for " + contentType); //$NON-NLS-1$
            return new ICompletionProposal[0];
        }
        ICompletionProposal[] proposals = processor.computeCompletionProposals(viewer, offset);
        return proposals == null ? new ICompletionProposal[0] : proposals;
    }

    /**
     * Builds the document the language expects to be handed back.
     * <p>
     * Not any document will do: the language casts the one it is given to its own concrete class while
     * working out which keyword closes the block around the caret, so a stand-in implementing only the
     * interface fails on most interesting positions. Its own document is bound in its injector, so it
     * is asked for one.
     * </p>
     * <p>
     * The resource handed to it is the one this plugin loaded, from its own resource set - not the one
     * an open editor owns - so pointing a document at it disturbs nobody's editing session.
     * </p>
     *
     * @param services the language's services
     * @param text the module's text
     * @param resource the module's already-loaded resource
     * @return the document to ask through
     */
    private static IDocument languageDocument(IResourceServiceProvider services, String text,
        XtextResource resource)
    {
        XtextDocument document = services.get(XtextDocument.class);
        if (document == null)
        {
            throw new IllegalStateException("The BSL language provides no document"); //$NON-NLS-1$
        }
        document.set(text);
        try
        {
            document.setInput(resource);
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("The module could not be paired with its document: " + e.getMessage()); //$NON-NLS-1$
        }
        return document;
    }
}
