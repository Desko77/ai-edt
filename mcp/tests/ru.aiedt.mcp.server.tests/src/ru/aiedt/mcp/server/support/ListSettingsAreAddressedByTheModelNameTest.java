/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;

/**
 * The settings of a dynamic list are exported under the FQN EDT registers them by, and the
 * attribute's name in it is the model's spelling, not the caller's.
 */
public class ListSettingsAreAddressedByTheModelNameTest
{
    private static final String FORM_FQN = "Catalog.Товары.Form.ФормаСписка.Form"; //$NON-NLS-1$

    /**
     * An attribute named in another case is found, and the FQN carries the name as the model
     * spells it.
     *
     * @throws Exception on a reflective failure of the helper
     */
    @Test
    public void theFqnCarriesTheModelSpelling() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());

        assertEquals(FORM_FQN + ".Attributes.Список.ExtInfo.ListSettings", //$NON-NLS-1$
            helper.listSettingsFqn(FORM_FQN, formWith("Список"), "список")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An attribute the form does not have, and a form without an FQN, give no address.
     *
     * @throws Exception on a reflective failure of the helper
     */
    @Test
    public void noAttributeOrNoFormFqnGivesNoAddress() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());

        assertNull(helper.listSettingsFqn(FORM_FQN, formWith("Список"), "Другой")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(helper.listSettingsFqn("", formWith("Список"), "Список")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * @param attributeName the one attribute the form carries
     * @return a form outside any model
     */
    private static Form formWith(String attributeName)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName(attributeName);
        form.getAttributes().add(attribute);
        return form;
    }
}
