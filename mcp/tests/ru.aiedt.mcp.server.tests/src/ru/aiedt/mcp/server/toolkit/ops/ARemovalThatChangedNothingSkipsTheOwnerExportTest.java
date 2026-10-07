/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * A drawing removal that changed nothing answers as a write that changed nothing.
 * <p>
 * {@code remove_drawing} of an id the template does not hold is a no-op success, and the write
 * entry treats it as one: the action answers with the {@link BmObjectHelper.Unchanged} marker, for
 * which the owner export is skipped - the operation promises nothing was written, so it neither
 * rewrites the owner from a model that did not move nor waits out a synchronization with nothing
 * to carry. The marker is earned only when the document was already attached: a get-or-create that
 * attached a fresh document changed the model even though the removal matched nothing.
 * </p>
 */
public class ARemovalThatChangedNothingSkipsTheOwnerExportTest
{
    private static final String NAME = "ПечатнаяФорма"; //$NON-NLS-1$

    /**
     * A common template holding the given document in its content slot, the way a template that
     * has been written to holds it.
     *
     * @param doc the document
     * @return the template
     */
    private static CommonTemplate templateHolding(SpreadsheetDocument doc)
    {
        CommonTemplate template = MdClassFactory.eINSTANCE.createCommonTemplate();
        template.setName(NAME);
        BmObjectHelper.setProperty(template, "templateType", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            template.getClass().getMethod("setTemplate", EObject.class).invoke(template, doc); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot attach the document: " + e.getMessage(), e); //$NON-NLS-1$
        }
        return template;
    }

    /**
     * A document with an unresolved picture reference - the state the write guard exists for.
     *
     * @return the document
     */
    private static SpreadsheetDocument documentWithAnUnresolvedPicture()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Stamp/Stamp.mdo#//")); //$NON-NLS-1$
        return doc;
    }

    /**
     * A removal that matches nothing on a template that already holds its document answers with
     * the unchanged marker and its idempotent wording, and the document is exactly as it was.
     */
    @Test
    public void aMissingDrawingOnAnAttachedDocumentAnswersUnchanged()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        CommonTemplate template = templateHolding(doc);

        MxlWorkshopTool.DrawingRemoval removal =
            MxlWorkshopTool.removeDrawingIn(template, NAME, 1);

        assertFalse(removal.removed);
        assertTrue("the document was there before the call", removal.documentExisted); //$NON-NLS-1$
        assertSame("no fresh document was attached", doc, removal.doc); //$NON-NLS-1$
        Object answer = removal.actionAnswer(1);
        assertTrue("a removal that moved nothing is the unchanged answer", //$NON-NLS-1$
            answer instanceof BmObjectHelper.Unchanged);
        assertEquals("no drawing with id 1 (idempotent skip)", answer.toString()); //$NON-NLS-1$
        assertEquals("the model is unchanged", 0, doc.getDrawings().size()); //$NON-NLS-1$
    }

    /**
     * The same answer on a template whose picture reference does not resolve: the guard asks only
     * a removal that will change something, and nothing else in the template moved either.
     */
    @Test
    public void aMissingDrawingOnTheUnresolvedTemplateIsStillUnchanged()
    {
        SpreadsheetDocument doc = documentWithAnUnresolvedPicture();
        int picturesBefore = doc.getPictures().size();
        CommonTemplate template = templateHolding(doc);

        MxlWorkshopTool.DrawingRemoval removal =
            MxlWorkshopTool.removeDrawingIn(template, NAME, 1);

        assertFalse(removal.removed);
        assertTrue(removal.actionAnswer(1) instanceof BmObjectHelper.Unchanged);
        assertEquals("the unresolved picture stays for the guard to see", //$NON-NLS-1$
            picturesBefore, doc.getPictures().size());
    }

    /**
     * A template whose document this very call attached is not claimed unchanged: the attachment
     * is a model change the owner export has to carry, whatever the removal matched.
     */
    @Test
    public void aMissingDrawingOnATemplateWithoutItsDocumentIsNotClaimedUnchanged()
    {
        CommonTemplate template = MdClassFactory.eINSTANCE.createCommonTemplate();
        template.setName(NAME);
        BmObjectHelper.setProperty(template, "templateType", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        MxlWorkshopTool.DrawingRemoval removal =
            MxlWorkshopTool.removeDrawingIn(template, NAME, 1);

        assertFalse(removal.removed);
        assertFalse("the document was attached by the call itself", removal.documentExisted); //$NON-NLS-1$
        assertFalse("an attached document is a change, not an unchanged write", //$NON-NLS-1$
            removal.actionAnswer(1) instanceof BmObjectHelper.Unchanged);
        assertEquals("no drawing with id 1 (idempotent skip)", removal.actionAnswer(1).toString()); //$NON-NLS-1$
        assertTrue("the attachment reached the template", //$NON-NLS-1$
            BmTemplateHelper.existingSpreadsheetOf(template) == removal.doc);
    }

    /**
     * A drawing that is there is removed and answered as a removal - a real write the owner export
     * still follows.
     */
    @Test
    public void anExistingDrawingAnswersTheRemoval()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        int id = BmTemplateHelper.addDrawing(doc, "Line", 1, 1, 2, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);
        CommonTemplate template = templateHolding(doc);

        MxlWorkshopTool.DrawingRemoval removal = MxlWorkshopTool.removeDrawingIn(template, NAME, id);

        assertTrue(removal.removed);
        Object answer = removal.actionAnswer(id);
        assertEquals("removed drawing #" + id, answer.toString()); //$NON-NLS-1$
        assertFalse("a real removal is not the unchanged answer", //$NON-NLS-1$
            answer instanceof BmObjectHelper.Unchanged);
        assertFalse(BmTemplateHelper.hasDrawing(doc, id));
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
