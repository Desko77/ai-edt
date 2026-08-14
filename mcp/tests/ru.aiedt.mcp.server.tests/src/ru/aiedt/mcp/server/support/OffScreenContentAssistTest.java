/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.graphics.Point;
import org.junit.Test;

/**
 * Covers the viewer that lets content assist be asked without an editor.
 * <p>
 * The interesting part of this viewer is what it refuses. Reading the environment's own code showed it
 * takes only a document and a selection from a viewer while building the question, so those are the
 * two that must be right. Everything else belongs to painting and scrolling, and answering those with
 * a plausible-looking default is how a wrong answer gets built quietly - so they throw instead.
 * </p>
 */
public class OffScreenContentAssistTest
{
    private static final String SOURCE = "Процедура Тест()\n\tА = 1;\nКонецПроцедуры\n"; //$NON-NLS-1$

    @Test
    public void theViewerAnswersWhatTheLanguageAsksFor()
    {
        IDocument document = new Document(SOURCE);
        OffScreenTextViewer viewer = new OffScreenTextViewer(document, 20);

        assertSame("the document is the one question and answer are counted in", //$NON-NLS-1$
            document, viewer.getDocument());
        assertFalse("nothing here is being edited", viewer.isEditable()); //$NON-NLS-1$
        assertEquals(new Point(20, 0), viewer.getSelectedRange());
        assertEquals("the caret is reported as an empty selection at the offset", //$NON-NLS-1$
            20, ((ITextSelection)viewer.getSelectionProvider().getSelection()).getOffset());
        assertEquals(SOURCE.length(), viewer.getVisibleRegion().getLength());
    }

    @Test
    public void theWholeTextCountsAsVisible()
    {
        OffScreenTextViewer viewer = new OffScreenTextViewer(new Document(SOURCE), 0);
        assertTrue(viewer.overlapsWithVisibleRegion(0, SOURCE.length()));
        assertFalse("a range past the end is not part of anything", //$NON-NLS-1$
            viewer.overlapsWithVisibleRegion(0, SOURCE.length() + 1));
    }

    @Test
    public void thereIsNoAnnotationModelAndThatIsAnAnswerNotAFailure()
    {
        OffScreenTextViewer viewer = new OffScreenTextViewer(new Document(SOURCE), 0);
        // Nothing here is annotated - there are no markers to show and nobody to show them to - so
        // this one answers rather than throws.
        assertEquals(null, viewer.getAnnotationModel());
        assertEquals(null, viewer.getRangeIndication());
    }

    @Test
    public void paintingIsRefusedRatherThanPretended()
    {
        OffScreenTextViewer viewer = new OffScreenTextViewer(new Document(SOURCE), 0);
        refuses("revealRange", () -> viewer.revealRange(0, 1)); //$NON-NLS-1$
        refuses("setTopIndex", () -> viewer.setTopIndex(3)); //$NON-NLS-1$
        refuses("showAnnotations", () -> viewer.showAnnotations(true)); //$NON-NLS-1$
        refuses("configure", () -> viewer.configure(null)); //$NON-NLS-1$
    }

    @Test
    public void theCaretNeverMovesSoNobodyIsToldAboutIt()
    {
        OffScreenTextViewer viewer = new OffScreenTextViewer(new Document(SOURCE), 5);
        // Registering is harmless and silent; setting a selection is not, and says so.
        viewer.getSelectionProvider().addSelectionChangedListener(event -> fail("nothing moves here")); //$NON-NLS-1$
        viewer.getSelectionProvider().removeSelectionChangedListener(event -> {
        });
        refuses("setSelection", () -> viewer.getSelectionProvider().setSelection(null)); //$NON-NLS-1$
    }

    private static void refuses(String method, Runnable call)
    {
        try
        {
            call.run();
            fail(method + " has no meaning without a screen and must not silently succeed"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            assertTrue("the message names the method so a failure is traceable, got: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains(method));
        }
    }
}
