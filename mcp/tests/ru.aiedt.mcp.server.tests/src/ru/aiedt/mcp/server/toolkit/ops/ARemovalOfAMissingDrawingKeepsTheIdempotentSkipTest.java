/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

import ru.aiedt.mcp.server.support.BmTemplateHelper;
import ru.aiedt.mcp.server.support.MetadataGuards;

/**
 * A removal that matches nothing keeps its idempotent answer under the picture guard.
 * <p>
 * {@code remove_drawing} of an id that is not there is a no-op success with an
 * {@code idempotentSkip} tag, and neither the document nor {@code Template.mxlx} moves: the
 * operation only persists what it removed. The unresolved-picture guard exists to stop a write
 * that would not persist, so asking it before the removal knows there is nothing to remove would
 * trade that no-op success for a refusal about a write that was never going to happen. The guard
 * asks exactly the removal that will change something, so a template with unresolved references
 * still refuses to lose a drawing it has.
 * </p>
 */
public class ARemovalOfAMissingDrawingKeepsTheIdempotentSkipTest
{
    /**
     * A document with an unresolved picture reference - the state the guard exists for.
     *
     * @return the document
     */
    private static SpreadsheetDocument templateWithAnUnresolvedPicture()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Stamp/Stamp.mdo#//")); //$NON-NLS-1$
        return doc;
    }

    /**
     * On a template whose picture reference does not resolve, a drawing that is not there is the
     * idempotent skip it always was: nothing thrown, nothing moved.
     */
    @Test
    public void aMissingDrawingIsTheSkipEvenUnderTheUnresolvedTemplate()
    {
        SpreadsheetDocument doc = templateWithAnUnresolvedPicture();
        int kept = BmTemplateHelper.addDrawing(doc, "Line", 1, 1, 2, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);
        int drawingsBefore = doc.getDrawings().size();
        int picturesBefore = doc.getPictures().size();

        boolean removed = MxlWorkshopTool.guardedRemoveDrawing(doc, kept + 1);

        assertFalse("a drawing that is not there removes nothing", removed); //$NON-NLS-1$
        assertEquals("the model is unchanged", drawingsBefore, doc.getDrawings().size()); //$NON-NLS-1$
        assertEquals("the unresolved picture stays for the guard to see", //$NON-NLS-1$
            picturesBefore, doc.getPictures().size());
        assertTrue(BmTemplateHelper.hasDrawing(doc, kept));
    }

    /**
     * The same template still refuses to lose a drawing it has: the guard asks the removal that
     * would really change the document, and that removal is refused with nothing changed.
     */
    @Test
    public void anExistingDrawingOnTheUnresolvedTemplateIsStillRefused()
    {
        SpreadsheetDocument doc = templateWithAnUnresolvedPicture();
        int kept = BmTemplateHelper.addDrawing(doc, "Line", 1, 1, 2, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);
        int drawingsBefore = doc.getDrawings().size();

        try
        {
            MxlWorkshopTool.guardedRemoveDrawing(doc, kept);
            fail("a real removal on an unresolved template is refused"); //$NON-NLS-1$
        }
        catch (MetadataGuards.BlockedGuardException refused)
        {
            assertTrue(refused.verdict.error, refused.verdict.error.contains("Stamp")); //$NON-NLS-1$
            assertTrue(refused.verdict.error, refused.verdict.error.contains("Nothing was changed")); //$NON-NLS-1$
        }
        assertEquals("the refusal changed nothing", drawingsBefore, doc.getDrawings().size()); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.hasDrawing(doc, kept));
    }

    /**
     * A template without unresolved references removes a drawing it has and skips one it has not.
     */
    @Test
    public void aResolvedTemplateRemovesAndSkipsAsBefore()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        int id = BmTemplateHelper.addDrawing(doc, "Rectangle", 1, 1, 3, 3, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);

        assertTrue(MxlWorkshopTool.guardedRemoveDrawing(doc, id));
        assertFalse(BmTemplateHelper.hasDrawing(doc, id));
        assertFalse(MxlWorkshopTool.guardedRemoveDrawing(doc, id));
    }

    /**
     * Whether a drawing is there is read off the document alone.
     */
    @Test
    public void hasDrawingReadsTheDocument()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertFalse(BmTemplateHelper.hasDrawing(doc, 1));
        assertFalse(BmTemplateHelper.hasDrawing(null, 1));
        int id = BmTemplateHelper.addDrawing(doc, "Text", 1, 1, 2, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            "caption", null); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.hasDrawing(doc, id));
    }

    private static PictureRef proxy(String uri)
    {
        // The pictures list is a containment and rejects a proxy element. The unresolved
        // common picture is the reference's target, which is not a containment.
        PictureRef holder = McoreFactory.eINSTANCE.createPictureRef();
        PictureRef target = McoreFactory.eINSTANCE.createPictureRef();
        ((InternalEObject) target).eSetProxyURI(URI.createURI(uri));
        holder.setPicture(target);
        return holder;
    }
}
