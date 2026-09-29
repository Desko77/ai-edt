/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.PictureDecorationExtInfo;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * The picture of a Picture decoration and of a button is written as a named {@code PictureRef}.
 * Where the reference cannot be built - here, a form outside any project - the write is refused
 * and the picture stays as it was; an empty name clears it.
 */
public class APictureIsWrittenAsANamedReferenceTest
{
    /**
     * A common picture is referenced by a proxy carrying the URI of the configuration's picture,
     * whatever the case of the name; a name the configuration does not carry gives no proxy.
     */
    @Test
    public void aCommonPictureIsReferencedFromTheConfiguration()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        CommonPicture logo = MdClassFactory.eINSTANCE.createCommonPicture();
        logo.setName("Логотип"); //$NON-NLS-1$
        configuration.getCommonPictures().add(logo);

        Object proxy = BmFormHelper.commonPictureProxy(configuration, "логотип"); //$NON-NLS-1$

        assertTrue(proxy instanceof CommonPicture);
        assertTrue(((CommonPicture)proxy).eIsProxy());
        assertEquals(EcoreUtil.getURI(logo), ((InternalEObject)proxy).eProxyURI());
        assertNull(BmFormHelper.commonPictureProxy(configuration, "Другая")); //$NON-NLS-1$
        assertNull(BmFormHelper.commonPictureProxy(null, "Логотип")); //$NON-NLS-1$
    }

    /**
     * The validator finds a common picture whatever the case of the name, as the write does.
     */
    @Test
    public void theValidatorFindsACommonPictureInAnyCase()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        CommonPicture logo = MdClassFactory.eINSTANCE.createCommonPicture();
        logo.setName("Логотип"); //$NON-NLS-1$
        configuration.getCommonPictures().add(logo);

        assertTrue(PictureValidator.carriesCommonPicture(configuration, "логотип")); //$NON-NLS-1$
        assertTrue(!PictureValidator.carriesCommonPicture(configuration, "Другая")); //$NON-NLS-1$
    }

    /**
     * A decoration whose picture cannot be built gets a refusal, not a decoration without a
     * picture.
     *
     * @throws Exception on a reflective failure of the helper
     */
    @Test
    public void aDecorationPictureThatCannotBeBuiltIsRefused() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Decoration decoration = (Decoration)helper.createDecoration("Pic", "Pic", "Picture", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String refusal = helper.setDecorationPicture(decoration, null, "StdPicture.Print"); //$NON-NLS-1$

        assertNotNull("a picture nothing can build is refused", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
        assertNull(((PictureDecorationExtInfo)decoration.getExtInfo()).getPicture());
    }

    /**
     * An empty name clears the picture a decoration had.
     *
     * @throws Exception on a reflective failure of the helper
     */
    @Test
    public void anEmptyNameClearsTheDecorationPicture() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Decoration decoration = (Decoration)helper.createDecoration("Pic", "Pic", "Picture", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        PictureDecorationExtInfo extInfo = (PictureDecorationExtInfo)decoration.getExtInfo();
        extInfo.setPicture(McoreFactory.eINSTANCE.createPictureRef());

        assertNull(helper.setDecorationPicture(decoration, null, "")); //$NON-NLS-1$
        assertNull(extInfo.getPicture());
    }

    /**
     * {@code set_form_item_property picture} on a button builds the named reference instead of
     * refusing the name as a value of the wrong type.
     *
     * @throws Exception on a reflective failure of the helper
     */
    @Test
    public void aButtonPictureGoesThroughTheNamedReference() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        Button button = FormFactory.eINSTANCE.createButton();
        button.setName("PrintButton"); //$NON-NLS-1$
        form.getItems().add(button);

        String refusal = helper.setItemProperty(form, "PrintButton", "picture", "StdPicture.Print"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("named picture reference")); //$NON-NLS-1$
        assertNull(button.getPicture());
    }

    /**
     * {@code set_form_item_property picture} on a Picture decoration reaches the picture of its
     * extInfo; an empty name clears it.
     *
     * @throws Exception on a reflective failure of the helper
     */
    @Test
    public void aDecorationPictureIsReachedThroughItsExtInfo() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        Decoration decoration = (Decoration)helper.createDecoration("Pic", "Pic", "Picture", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        form.getItems().add(decoration);
        PictureDecorationExtInfo extInfo = (PictureDecorationExtInfo)decoration.getExtInfo();
        extInfo.setPicture(McoreFactory.eINSTANCE.createPictureRef());

        assertNull(helper.setItemProperty(form, "Pic", "picture", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(extInfo.getPicture());
    }
}
