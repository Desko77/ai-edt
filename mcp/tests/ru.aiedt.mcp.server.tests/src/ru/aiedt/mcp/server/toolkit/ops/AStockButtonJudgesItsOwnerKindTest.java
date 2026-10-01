/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.StandardCommandRegistry;

/**
 * A button bound to a platform stock command is judged on the owner object resolved from the form
 * FQN.
 * <p>
 * The form itself used to be the source of the owner kind: {@code readFormOwnerEClassName} walked
 * {@code eContainer()} off the form, and a BM top-object form answers null there, so the
 * compatibility check never saw the actual owner - it refused or passed on nothing. The owner is
 * resolved once per transaction alongside the field and table routes, and the button route reads
 * its EClass name off that object.
 * </p>
 */
public class AStockButtonJudgesItsOwnerKindTest
{
    /**
     * A data processor owns no standard command list, so the button route refuses before anything
     * is created - the same owner the field and table routes already judge.
     *
     * @throws Exception when the button route fails before its refusal
     */
    @Test
    public void aDataProcessorOwnerRefusesTheStockCommandButton() throws Exception
    {
        String answer = new EditFormTool().executeAddButton(new BmFormHelper(),
            FormFactory.eINSTANCE.createForm(), MdClassFactory.eINSTANCE.createDataProcessor(),
            "КнопкаПровести", null, null, null, "Post", null, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: error")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("not supported on DataProcessor")); //$NON-NLS-1$
    }

    /**
     * A catalog owner accepts a stock command, as it did before: the refusal is about the owner
     * kind, not about stock commands in general.
     */
    @Test
    public void aCatalogOwnerKeepsAcceptingTheStockCommandButton()
    {
        assertNull(StandardCommandRegistry.checkOwnerKindCompatibility(
            EditFormTool.readOwnerEClassName(MdClassFactory.eINSTANCE.createCatalog()), "Post")); //$NON-NLS-1$
    }

    /**
     * The owner kind is the EClass simple name of the resolved owner, and an owner that cannot be
     * resolved reads as none - the check then refuses with its own explanation.
     */
    @Test
    public void theOwnerKindIsReadFromTheOwnerObject()
    {
        assertEquals("DataProcessor", //$NON-NLS-1$
            EditFormTool.readOwnerEClassName(MdClassFactory.eINSTANCE.createDataProcessor()));
        assertEquals("Document", //$NON-NLS-1$
            EditFormTool.readOwnerEClassName(MdClassFactory.eINSTANCE.createDocument()));
        assertNull("a form without an owner reads as none", //$NON-NLS-1$
            EditFormTool.readOwnerEClassName(null));
        assertNull("an object that answers no eClass reads as none", //$NON-NLS-1$
            EditFormTool.readOwnerEClassName(new Object()));
    }
}
