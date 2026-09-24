/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;

import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.FormExtensionDataPathGuard;

/**
 * The column loop of {@code add_table} with {@code autoGenerateColumns} lets a data-path refusal
 * out of its catch.
 * <p>
 * The loop catches every failure of a generated column and answers a warning, which is right for a
 * column that could not be built and wrong for a refused data path: a warning commits the table
 * and its earlier columns in a transaction whose borrow was rolled back. The handling the loop
 * runs is pinned here - the refusal leaves it, and every other failure keeps the old warning.
 * </p>
 */
public class TheColumnLoopLetsARefusalOutTest
{
    @After
    public void removeTheInstalledPort()
    {
        FormExtensionDataPathGuard.installPort(null);
    }

    /**
     * A refusal caught by the loop reaches the caller - directly, and through
     * the reflection wrapper a call inside the loop put it in.
     */
    @Test
    public void aRefusalIsNotAWarning() throws Exception
    {
        FormExtensionDataPathGuard.RefusalException refusal = refusal();
        List<String> warnings = new ArrayList<>();
        for (Exception caught : List.of(refusal,
            new java.lang.reflect.InvocationTargetException(refusal)))
        {
            try
            {
                EditFormTool.noteColumnFailure(caught, warnings);
                fail("a refusal must leave the column loop, not become a warning"); //$NON-NLS-1$
            }
            catch (FormExtensionDataPathGuard.RefusalException expected)
            {
                assertNotNull(expected.getOutcome().getRefusal());
            }
        }
        assertTrue("a refusal is not a warning: " + warnings, warnings.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Any other failure keeps the behaviour the loop had for it: a warning
     * naming the cause, and no exception.
     */
    @Test
    public void anyOtherFailureStaysAWarning()
    {
        List<String> warnings = new ArrayList<>();

        EditFormTool.noteColumnFailure(new IllegalStateException("колонка не создана"), warnings); //$NON-NLS-1$

        assertEquals("a plain failure is noted the way it was", //$NON-NLS-1$
            List.of("autogen failed: колонка не создана"), warnings); //$NON-NLS-1$
    }

    /**
     * Builds a refusal the way the write path produces it: the helper throws
     * it out of the data-path write when the borrow fails.
     *
     * @return the refusal from a real {@code BmFormHelper.setDataPath}
     */
    private static FormExtensionDataPathGuard.RefusalException refusal() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName("Объект"); //$NON-NLS-1$
        form.getAttributes().add(attribute);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("ПолеВвода"); //$NON-NLS-1$
        form.getItems().add(field);
        helper.formForTest(form);
        FormExtensionDataPathGuard.installPort(new FormExtensionDataPathGuard.Port()
        {
            @Override
            public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object attribute)
            {
                return FormExtensionDataPathGuard.Answer.FALSE;
            }

            @Override
            public void adoptObject(Object attribute) throws Exception
            {
                throw new IllegalStateException("нет прав на заимствование"); //$NON-NLS-1$
            }

            @Override
            public FormExtensionDataPathGuard.Answer exportSkip(Object form, Object dataPath)
            {
                return FormExtensionDataPathGuard.Answer.TRUE;
            }

            @Override
            public FormExtensionDataPathGuard.Answer pathResolved(Object form, Object dataPath)
            {
                return FormExtensionDataPathGuard.Answer.TRUE;
            }
        });
        try
        {
            helper.setDataPath(field, "Объект.Description"); //$NON-NLS-1$
            throw new AssertionError("the fixture must produce a refusal"); //$NON-NLS-1$
        }
        catch (FormExtensionDataPathGuard.RefusalException refused)
        {
            return refused;
        }
    }
}
