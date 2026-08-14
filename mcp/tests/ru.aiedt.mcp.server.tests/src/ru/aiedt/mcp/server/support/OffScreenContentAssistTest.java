/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.junit.Test;

/**
 * Covers the three pieces that let content assist be asked without an editor: the read-only pairing of
 * text and model, the viewer that has no screen behind it, and the seeding that keeps the language's
 * widget-keyed cache from reaching for a widget that is not there.
 */
public class OffScreenContentAssistTest
{
    private static final String SOURCE = "Процедура Тест()\n\tА = 1;\nКонецПроцедуры\n"; //$NON-NLS-1$

    @Test
    public void theDocumentCarriesTheTextAndHandsBackTheResource()
    {
        ReadOnlyModuleDocument document = new ReadOnlyModuleDocument(SOURCE, null);
        assertEquals(SOURCE, document.get());
        assertEquals("the resource is passed straight through to the unit of work", //$NON-NLS-1$
            "seen", document.readOnly(resource -> "seen")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a priority read is the same read", //$NON-NLS-1$
            "seen", document.priorityReadOnly(resource -> "seen")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theDocumentRefusesToBeWritten()
    {
        ReadOnlyModuleDocument document = new ReadOnlyModuleDocument(SOURCE, null);
        try
        {
            document.modify(resource -> "written"); //$NON-NLS-1$
            fail("answering a question must never modify the module"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            assertTrue(expected.getMessage().contains("modified")); //$NON-NLS-1$
        }
    }

    @Test
    public void theViewerAnswersWhatTheLanguageAsksFor()
    {
        ReadOnlyModuleDocument document = new ReadOnlyModuleDocument(SOURCE, null);
        OffScreenTextViewer viewer = new OffScreenTextViewer(document, 20);

        assertSame(document, viewer.getDocument());
        assertNull("there is no widget, and that is the whole point", viewer.getTextWidget()); //$NON-NLS-1$
        assertFalse(viewer.isEditable());
        assertEquals(new Point(20, 0), viewer.getSelectedRange());
        assertEquals("the caret is reported as an empty selection at the offset", //$NON-NLS-1$
            20, ((org.eclipse.jface.text.ITextSelection)viewer.getSelectionProvider().getSelection())
                .getOffset());
        assertEquals(SOURCE.length(), viewer.getVisibleRegion().getLength());
    }

    @Test
    public void theViewerRefusesToPaintRatherThanPretend()
    {
        OffScreenTextViewer viewer = new OffScreenTextViewer(new ReadOnlyModuleDocument(SOURCE, null), 0);
        try
        {
            viewer.revealRange(0, 1);
            fail("scrolling has no meaning without a screen and must not silently succeed"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            assertTrue("the message names the method so a failure is traceable", //$NON-NLS-1$
                expected.getMessage().contains("revealRange")); //$NON-NLS-1$
        }
    }

    @Test
    public void aWidgetKeyedCacheIsSeededUnderTheAbsentWidget()
    {
        WithCache provider = new WithCache();
        ITextViewer viewer = new OffScreenTextViewer(new ReadOnlyModuleDocument(SOURCE, null), 0);

        assertTrue(BslProposalAccess.seedWidgetKeyedCache(provider, viewer));
        assertTrue("the provider must find an entry instead of building one", //$NON-NLS-1$
            provider.cache.containsKey(null));
        assertSame("the entry is built for the viewer the question is asked through", //$NON-NLS-1$
            viewer, provider.cache.get(null).viewer);
    }

    @Test
    public void seedingIsDoneOnceAndIsNotRedone()
    {
        WithCache provider = new WithCache();
        ITextViewer first = new OffScreenTextViewer(new ReadOnlyModuleDocument(SOURCE, null), 0);
        ITextViewer second = new OffScreenTextViewer(new ReadOnlyModuleDocument(SOURCE, null), 1);

        assertTrue(BslProposalAccess.seedWidgetKeyedCache(provider, first));
        Listener seeded = provider.cache.get(null);
        assertTrue(BslProposalAccess.seedWidgetKeyedCache(provider, second));
        assertSame("a second call leaves the first entry alone", seeded, provider.cache.get(null)); //$NON-NLS-1$
    }

    @Test
    public void aProviderWithoutSuchACacheIsLeftAlone()
    {
        ITextViewer viewer = new OffScreenTextViewer(new ReadOnlyModuleDocument(SOURCE, null), 0);
        assertFalse("nothing to seed is not a failure - the environment simply changed", //$NON-NLS-1$
            BslProposalAccess.seedWidgetKeyedCache(new WithoutCache(), viewer));
    }

    @Test
    public void theCollectorStopsAtItsCeiling()
    {
        BslProposalAccess.Collector collector = new BslProposalAccess.Collector();
        assertTrue(collector.canAcceptMoreProposals());
        for (int i = 0; i < BslProposalAccess.HARD_CEILING + 10; i++)
        {
            collector.accept(new NullProposal());
        }
        assertFalse("a position in a large module can offer more than the heap has room for", //$NON-NLS-1$
            collector.canAcceptMoreProposals());
        assertEquals(BslProposalAccess.HARD_CEILING, collector.collected().length);
    }

    @Test
    public void theCollectorIgnoresNothing()
    {
        BslProposalAccess.Collector collector = new BslProposalAccess.Collector();
        collector.accept(null);
        assertEquals(0, collector.collected().length);
    }

    /** Stands in for the language's proposal provider: same field shape, none of the behaviour. */
    static final class WithCache
    {
        final Map<StyledText, Listener> cache = new HashMap<>();
    }

    /** A provider that keeps no such cache, as a future environment might not. */
    static final class WithoutCache
    {
        @SuppressWarnings("unused")
        private final Map<String, String> unrelated = new LinkedHashMap<>();
    }

    /** Stands in for the cached document listener, built the same way: owner first, viewer second. */
    static final class Listener
    {
        final ITextViewer viewer;

        Listener(WithCache owner, ITextViewer viewer)
        {
            this.viewer = viewer;
        }
    }

    /** The collector only counts and stores; a proposal that answers nothing is enough. */
    private static final class NullProposal
        implements ICompletionProposal
    {
        @Override
        public void apply(org.eclipse.jface.text.IDocument document)
        {
        }

        @Override
        public Point getSelection(org.eclipse.jface.text.IDocument document)
        {
            return null;
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return null;
        }

        @Override
        public String getDisplayString()
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public org.eclipse.swt.graphics.Image getImage()
        {
            return null;
        }

        @Override
        public org.eclipse.jface.text.contentassist.IContextInformation getContextInformation()
        {
            return null;
        }
    }
}
