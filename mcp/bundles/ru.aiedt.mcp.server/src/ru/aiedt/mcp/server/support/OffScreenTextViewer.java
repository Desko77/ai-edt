/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.jface.text.IAutoIndentStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IEventConsumer;
import org.eclipse.jface.text.IFindReplaceTarget;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextDoubleClickStrategy;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;

/**
 * Everything the language services actually need from a text viewer, and nothing that needs a screen.
 * <p>
 * Content assist is written against a viewer because in an editor that is where the text and the caret
 * live. Reading the environment's own code shows how little of the interface it really uses: the
 * context factory asks for the document and the current selection, and the BSL proposal provider asks
 * once for the text widget, only to key a cache of listeners by it. Everything else on the interface is
 * about painting, scrolling and editing.
 * </p>
 * <p>
 * So this answers those three and refuses the rest. The widget is <code>null</code> - see
 * {@link BslProposalAccess} for why that is safe and what is done to keep it safe. The remaining methods
 * throw rather than pretend: if the environment ever starts asking for a viewport or a colour, a clear
 * failure naming the method is worth more than a silent wrong answer.
 * </p>
 */
public final class OffScreenTextViewer
    implements ITextViewer
{
    private final IDocument document;

    private final ISelectionProvider selectionProvider;

    /**
     * @param document the module's text
     * @param caretOffset the position the question is asked at, reported as an empty selection
     */
    public OffScreenTextViewer(IDocument document, int caretOffset)
    {
        this.document = document;
        this.selectionProvider = new CaretOnly(document, caretOffset);
    }

    @Override
    public IDocument getDocument()
    {
        return this.document;
    }

    @Override
    public ISelectionProvider getSelectionProvider()
    {
        return this.selectionProvider;
    }

    @Override
    public StyledText getTextWidget()
    {
        return null;
    }

    @Override
    public Point getSelectedRange()
    {
        TextSelection selection = (TextSelection)this.selectionProvider.getSelection();
        return new Point(selection.getOffset(), selection.getLength());
    }

    @Override
    public IRegion getVisibleRegion()
    {
        return new Region(0, this.document.getLength());
    }

    @Override
    public boolean overlapsWithVisibleRegion(int offset, int length)
    {
        return offset >= 0 && offset + length <= this.document.getLength();
    }

    @Override
    public boolean isEditable()
    {
        return false;
    }

    @Override
    public int getTopIndex()
    {
        return 0;
    }

    @Override
    public int getTopIndexStartOffset()
    {
        return 0;
    }

    @Override
    public int getBottomIndex()
    {
        return Math.max(0, this.document.getNumberOfLines() - 1);
    }

    @Override
    public int getBottomIndexEndOffset()
    {
        return this.document.getLength();
    }

    @Override
    public int getTopInset()
    {
        return 0;
    }

    @Override
    public void setUndoManager(IUndoManager undoManager)
    {
        throw offScreen("setUndoManager"); //$NON-NLS-1$
    }

    @Override
    public void setTextDoubleClickStrategy(ITextDoubleClickStrategy strategy, String contentType)
    {
        throw offScreen("setTextDoubleClickStrategy"); //$NON-NLS-1$
    }

    @Override
    public void setAutoIndentStrategy(IAutoIndentStrategy strategy, String contentType)
    {
        throw offScreen("setAutoIndentStrategy"); //$NON-NLS-1$
    }

    @Override
    public void setTextHover(ITextHover hover, String contentType)
    {
        throw offScreen("setTextHover"); //$NON-NLS-1$
    }

    @Override
    public void activatePlugins()
    {
        throw offScreen("activatePlugins"); //$NON-NLS-1$
    }

    @Override
    public void resetPlugins()
    {
        throw offScreen("resetPlugins"); //$NON-NLS-1$
    }

    @Override
    public void addViewportListener(IViewportListener listener)
    {
        throw offScreen("addViewportListener"); //$NON-NLS-1$
    }

    @Override
    public void removeViewportListener(IViewportListener listener)
    {
        throw offScreen("removeViewportListener"); //$NON-NLS-1$
    }

    @Override
    public void addTextListener(ITextListener listener)
    {
        throw offScreen("addTextListener"); //$NON-NLS-1$
    }

    @Override
    public void removeTextListener(ITextListener listener)
    {
        throw offScreen("removeTextListener"); //$NON-NLS-1$
    }

    @Override
    public void addTextInputListener(ITextInputListener listener)
    {
        throw offScreen("addTextInputListener"); //$NON-NLS-1$
    }

    @Override
    public void removeTextInputListener(ITextInputListener listener)
    {
        throw offScreen("removeTextInputListener"); //$NON-NLS-1$
    }

    @Override
    public void setDocument(IDocument newDocument)
    {
        throw offScreen("setDocument"); //$NON-NLS-1$
    }

    @Override
    public void setDocument(IDocument newDocument, int modelRangeOffset, int modelRangeLength)
    {
        throw offScreen("setDocument"); //$NON-NLS-1$
    }

    @Override
    public void setEventConsumer(IEventConsumer consumer)
    {
        throw offScreen("setEventConsumer"); //$NON-NLS-1$
    }

    @Override
    public void setEditable(boolean editable)
    {
        throw offScreen("setEditable"); //$NON-NLS-1$
    }

    @Override
    public void setVisibleRegion(int offset, int length)
    {
        throw offScreen("setVisibleRegion"); //$NON-NLS-1$
    }

    @Override
    public void resetVisibleRegion()
    {
        throw offScreen("resetVisibleRegion"); //$NON-NLS-1$
    }

    @Override
    public void changeTextPresentation(TextPresentation presentation, boolean controlRedraw)
    {
        throw offScreen("changeTextPresentation"); //$NON-NLS-1$
    }

    @Override
    public void invalidateTextPresentation()
    {
        throw offScreen("invalidateTextPresentation"); //$NON-NLS-1$
    }

    @Override
    public void setTextColor(Color color)
    {
        throw offScreen("setTextColor"); //$NON-NLS-1$
    }

    @Override
    public void setTextColor(Color color, int offset, int length, boolean controlRedraw)
    {
        throw offScreen("setTextColor"); //$NON-NLS-1$
    }

    @Override
    public ITextOperationTarget getTextOperationTarget()
    {
        throw offScreen("getTextOperationTarget"); //$NON-NLS-1$
    }

    @Override
    public IFindReplaceTarget getFindReplaceTarget()
    {
        throw offScreen("getFindReplaceTarget"); //$NON-NLS-1$
    }

    @Override
    public void setDefaultPrefixes(String[] defaultPrefixes, String contentType)
    {
        throw offScreen("setDefaultPrefixes"); //$NON-NLS-1$
    }

    @Override
    public void setIndentPrefixes(String[] indentPrefixes, String contentType)
    {
        throw offScreen("setIndentPrefixes"); //$NON-NLS-1$
    }

    @Override
    public void setSelectedRange(int offset, int length)
    {
        throw offScreen("setSelectedRange"); //$NON-NLS-1$
    }

    @Override
    public void revealRange(int offset, int length)
    {
        throw offScreen("revealRange"); //$NON-NLS-1$
    }

    @Override
    public void setTopIndex(int index)
    {
        throw offScreen("setTopIndex"); //$NON-NLS-1$
    }

    private static UnsupportedOperationException offScreen(String method)
    {
        return new UnsupportedOperationException(
            method + " needs a real editor; this viewer exists so that content assist does not"); //$NON-NLS-1$
    }

    /**
     * A selection provider that only ever reports where the question was asked.
     */
    private static final class CaretOnly
        implements ISelectionProvider
    {
        private final TextSelection caret;

        CaretOnly(IDocument document, int offset)
        {
            this.caret = new TextSelection(document, Math.max(0, offset), 0);
        }

        @Override
        public ISelection getSelection()
        {
            return this.caret;
        }

        @Override
        public void setSelection(ISelection selection)
        {
            throw offScreen("setSelection"); //$NON-NLS-1$
        }

        @Override
        public void addSelectionChangedListener(ISelectionChangedListener listener)
        {
            // The caret never moves, so no listener would ever hear anything.
        }

        @Override
        public void removeSelectionChangedListener(ISelectionChangedListener listener)
        {
            // As above.
        }
    }
}
