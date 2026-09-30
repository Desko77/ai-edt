/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormFactory;

import ru.aiedt.mcp.server.support.BmFormHelper;

/**
 * An element asked to stand before a sibling lands in the container either way, and the answer
 * says which of the two happened.
 * <p>
 * A {@code beforeName} that matches no element used to place the item at the end with a plain
 * success answer, so a name taken from an outdated structure read back as the position being
 * taken. The answer now carries a warning in its front matter - the write stands, the caller is
 * told where the item actually went.
 * </p>
 */
public class AnInsertBeforeAMissingSiblingWarnsTest
{
    /**
     * @return an initialized helper, the same one a request would hold
     */
    private static BmFormHelper helper()
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue("the form model classes must be available", helper.init()); //$NON-NLS-1$
        return helper;
    }

    /**
     * @param siblingName the name of the one element the form carries
     * @return a form outside any model, holding that one element
     */
    private static Form formWithSibling(String siblingName)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField sibling = FormFactory.eINSTANCE.createFormField();
        sibling.setName(siblingName);
        form.getItems().add(sibling);
        return form;
    }

    /**
     * A group named to stand before a sibling that is not there is added at the end, and its
     * answer warns about the sibling alongside the group's own childless warning.
     *
     * @throws Exception when the model refuses the group
     */
    @Test
    public void aGroupBeforeAMissingSiblingWarnsAndLandsAtTheEnd() throws Exception
    {
        Form form = formWithSibling("Первый"); //$NON-NLS-1$

        String answer = new EditFormTool().executeAddGroup(helper(), form,
            "ГруппаИтогов", null, null, null, "НетТакогоСоседа"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("warning:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("НетТакогоСоседа")); //$NON-NLS-1$
        assertEquals("Первый", ((FormField)form.getItems().get(0)).getName()); //$NON-NLS-1$
        assertEquals("the group went to the end, not before a sibling that is not there", //$NON-NLS-1$
            "FormGroup", form.getItems().get(1).eClass().getName()); //$NON-NLS-1$
    }

    /**
     * A button named to stand before a sibling that is not there is added at the end, and its
     * answer carries the warning.
     *
     * @throws Exception when the model refuses the button
     */
    @Test
    public void aButtonBeforeAMissingSiblingWarnsAndLandsAtTheEnd() throws Exception
    {
        Form form = formWithSibling("Первый"); //$NON-NLS-1$

        String answer = new EditFormTool().executeAddButton(helper(), form, null,
            "КнопкаОк", null, null, "НетТакогоСоседа", null, null, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("warning:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("НетТакогоСоседа")); //$NON-NLS-1$
        assertEquals("Первый", ((FormField)form.getItems().get(0)).getName()); //$NON-NLS-1$
        assertEquals("Button", form.getItems().get(1).eClass().getName()); //$NON-NLS-1$
    }

    /**
     * A sibling that is found still takes the new element in front of it, and the answer owes no
     * warning - the regression the warning route must not introduce.
     *
     * @throws Exception when the model refuses the button
     */
    @Test
    public void aButtonBeforeAPresentSiblingLandsBeforeItWithoutAWarning() throws Exception
    {
        Form form = formWithSibling("Первый"); //$NON-NLS-1$

        String answer = new EditFormTool().executeAddButton(helper(), form, null,
            "КнопкаОк", null, null, "Первый", null, null, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertFalse("a sibling that was found owes no warning", answer.contains("warning:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Button", form.getItems().get(0).eClass().getName()); //$NON-NLS-1$
        assertEquals("Первый", ((FormField)form.getItems().get(1)).getName()); //$NON-NLS-1$
    }

    /**
     * A sibling named in another case is the same sibling: 1C element names are identifiers and
     * carry no case, so the anchor has to match them the same way the container lookup does - in
     * front of the sibling, not appended at the end with a warning about a name that did match.
     *
     * @throws Exception when the model refuses the button
     */
    @Test
    public void aButtonBeforeASiblingInAnotherCaseLandsBeforeItWithoutAWarning() throws Exception
    {
        Form form = formWithSibling("Товары"); //$NON-NLS-1$

        String answer = new EditFormTool().executeAddButton(helper(), form, null,
            "КнопкаОк", null, null, "товары", null, null, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertFalse("a name differing only in case did match", answer.contains("warning:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Button", form.getItems().get(0).eClass().getName()); //$NON-NLS-1$
        assertEquals("Товары", ((FormField)form.getItems().get(1)).getName()); //$NON-NLS-1$
    }
}
