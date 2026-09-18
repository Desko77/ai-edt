/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Asking to borrow a form item borrows its form, and never the owner by accident.
 *
 * <p>Measured on the stand 17.09: <code>borrow_form_item objectFqn=Catalog.Валюты
 * itemName=Наименование</code> answered "already borrowed" about <em>Catalog.Валюты</em> - the item
 * name was read by nothing and the catalog was what got borrowed. The same pass measured the
 * addressing: a form is borrowed by its own full name and works, and there is no address below a
 * form, because a form item is not a metadata object.
 *
 * <p>So the item cannot be borrowed on its own, and the honest answer is the form: after that the
 * item inside it is the extension's to change.
 */
public class AFormItemBorrowsItsFormTest
{
    @Test
    public void anOwnerAndAFormNameAddressTheForm()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formName", "ФормаЭлемента"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("itemName", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Catalog.Валюты.Form.ФормаЭлемента", //$NON-NLS-1$
            MiscOps.formToBorrowFor("Catalog.Валюты", params)); //$NON-NLS-1$
    }

    @Test
    public void aFullFormAddressIsTakenAsItIs()
    {
        Map<String, String> params = new HashMap<>();
        params.put("itemName", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the address a caller already spelled out is not rebuilt", //$NON-NLS-1$
            "Catalog.Валюты.Form.ФормаЭлемента", //$NON-NLS-1$
            MiscOps.formToBorrowFor("Catalog.Валюты.Form.ФормаЭлемента", params)); //$NON-NLS-1$
    }

    @Test
    public void anOwnerWithNoFormNamedIsNotBorrowed()
    {
        Map<String, String> params = new HashMap<>();
        params.put("itemName", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("borrowing the owner in silence is the defect this replaces", //$NON-NLS-1$
            MiscOps.formToBorrowFor("Catalog.Валюты", params)); //$NON-NLS-1$
    }

    @Test
    public void anEmptyFormNameCountsAsNoneAtAll()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formName", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(MiscOps.formToBorrowFor("Catalog.Валюты", params)); //$NON-NLS-1$
    }

    /**
     * The arguments edit_metadata documents for this operation - ownerFqn, childKind and name -
     * address the form as surely as formName does.
     */
    @Test
    public void theChildArgumentsNameTheFormToo()
    {
        Map<String, String> params = new HashMap<>();
        params.put("childKind", "Form"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "ФормаЭлемента"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("itemName", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Catalog.Валюты.Form.ФормаЭлемента", //$NON-NLS-1$
            MiscOps.formToBorrowFor("Catalog.Валюты", params)); //$NON-NLS-1$
    }

    /**
     * Both dispatchers reach the borrow through this one function, so the decision cannot hold on
     * one route and be missing on the other.
     *
     * <p>Measured: with the refusal held beside the metadata dispatcher alone,
     * <code>extension_workshop operation=borrow_form_item</code> borrowed the owner and answered
     * "already borrowed".
     */
    @Test
    public void bothSpellingsOfTheOperationAskTheSameQuestion()
    {
        Map<String, String> owner = new HashMap<>();
        owner.put("itemName", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> named = new HashMap<>();
        named.put("formName", "ФормаЭлемента"); //$NON-NLS-1$ //$NON-NLS-2$

        for (String op : new String[] { "borrow_form_item", "adopt_form_item" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(op, MiscOps.borrowsAFormItem(op));
            assertNull(op + " must not compose the owner", //$NON-NLS-1$
                MiscOps.composeChildFqn(op, "Catalog.Валюты", owner)); //$NON-NLS-1$
            assertEquals(op, "Catalog.Валюты.Form.ФормаЭлемента", //$NON-NLS-1$
                MiscOps.composeChildFqn(op, "Catalog.Валюты", named)); //$NON-NLS-1$
        }
    }

    /**
     * The refusal names what was asked for, so the caller can see their own call in it.
     */
    @Test
    public void theRefusalCarriesWhatWasAsked()
    {
        Map<String, String> params = new HashMap<>();
        params.put("itemName", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = MiscOps.noFormNamed("Catalog.Валюты", params); //$NON-NLS-1$

        assertTrue(answer, answer.contains("Catalog.Валюты")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Наименование")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("formName")); //$NON-NLS-1$
    }

    /**
     * A borrow that is not about a form item keeps composing the way it did.
     */
    @Test
    public void aChildBorrowIsUntouched()
    {
        Map<String, String> params = new HashMap<>();
        params.put("childKind", "Template"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Печать"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Catalog.Валюты.Template.Печать", //$NON-NLS-1$
            MiscOps.composeChildFqn("borrow_child", "Catalog.Валюты", params)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Валюты", //$NON-NLS-1$
            MiscOps.composeChildFqn("borrow_object", "Catalog.Валюты", params)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
