/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;

import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.FormExtensionDataPathGuard;

/**
 * {@code add_field} with a data-path root the form does not carry is refused
 * with the form's attribute list, as {@code add_table} already is - not with
 * the extension-export refusal.
 * <p>
 * The form declares a base form while its project is a configuration one, and
 * the installed port would answer with the extension-export refusal if the
 * path reached it.
 * </p>
 */
public class AFieldNeedsARootThatExistsTest
{
    private static final String DATA_PATH = "Объект.BBWeight"; //$NON-NLS-1$

    @After
    public void removeTheInstalledSeams()
    {
        FormExtensionDataPathGuard.installPort(null);
        FormExtensionDataPathGuard.installProjectKind(null);
    }

    @Test
    public void anUnknownRootIsRefusedWithTheAttributeListNotTheExtensionText() throws Exception
    {
        EditFormTool tool = new EditFormTool();
        Form form = formWithBaseForm("Object"); //$NON-NLS-1$
        injectHelper(tool, form);
        // The form declares a base form, its project is a configuration one,
        // and the port would answer with the extension-export refusal.
        FormExtensionDataPathGuard.installProjectKind(f -> Boolean.FALSE);
        FormExtensionDataPathGuard.installPort(new FormExtensionDataPathGuard.Port()
        {
            @Override
            public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object attribute)
            {
                return FormExtensionDataPathGuard.Answer.TRUE;
            }

            @Override
            public void adoptObject(Object attribute)
            {
            }

            @Override
            public FormExtensionDataPathGuard.Answer exportSkip(Object form, Object dataPath)
            {
                return FormExtensionDataPathGuard.Answer.TRUE;
            }

            @Override
            public FormExtensionDataPathGuard.Answer pathResolved(Object form, Object dataPath)
            {
                return FormExtensionDataPathGuard.Answer.FALSE;
            }
        });

        String answer = addField(tool, form, DATA_PATH);

        assertTrue("the write is refused: " + answer, answer.contains("status: error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the path: " + answer, answer.contains(DATA_PATH)); //$NON-NLS-1$
        assertTrue("the refusal lists the attributes the form has: " + answer, //$NON-NLS-1$
            answer.contains("Form attributes: Object")); //$NON-NLS-1$
        assertFalse("the refusal must not speak of an extension this form is not: " + answer, //$NON-NLS-1$
            answer.contains("расширен")); //$NON-NLS-1$
    }

    /**
     * The same call with the root the form carries is still written.
     * <p>
     * The precheck judges the root only: {@code Object.BBWeight} names the form
     * attribute and passes to the write path, where the configuration-project
     * form is none of the extension guard's business.
     * </p>
     */
    @Test
    public void aKnownRootIsWritten() throws Exception
    {
        EditFormTool tool = new EditFormTool();
        Form form = formWithBaseForm("Object"); //$NON-NLS-1$
        injectHelper(tool, form);
        FormExtensionDataPathGuard.installProjectKind(f -> Boolean.FALSE);
        FormExtensionDataPathGuard.installPort(new FormExtensionDataPathGuard.Port()
        {
            @Override
            public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object attribute)
            {
                return FormExtensionDataPathGuard.Answer.TRUE;
            }

            @Override
            public void adoptObject(Object attribute)
            {
            }

            @Override
            public FormExtensionDataPathGuard.Answer exportSkip(Object form, Object dataPath)
            {
                return FormExtensionDataPathGuard.Answer.TRUE;
            }

            @Override
            public FormExtensionDataPathGuard.Answer pathResolved(Object form, Object dataPath)
            {
                return FormExtensionDataPathGuard.Answer.FALSE;
            }
        });

        String answer = addField(tool, form, "Object.BBWeight"); //$NON-NLS-1$

        assertTrue("the field is added: " + answer, answer.contains("status: success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String addField(EditFormTool tool, Form form, String dataPath) throws Exception
    {
        Method addField = EditFormTool.class.getDeclaredMethod("executeAddField", //$NON-NLS-1$
            Object.class, Object.class, String.class, String.class, String.class, String.class,
            String.class, String.class, boolean.class);
        addField.setAccessible(true);
        try
        {
            return (String) addField.invoke(tool, form, new Object(), "ПолеВеса", null, //$NON-NLS-1$
                "InputField", dataPath, null, null, false); //$NON-NLS-1$
        }
        catch (InvocationTargetException e)
        {
            // The old refusal leaves the guard as an exception, so the write is
            // rolled back: surface its text as the assertion failure, because
            // that text is the defect this class pins.
            fail("the answer must be a refusal string, not the guard's exception: " //$NON-NLS-1$
                + e.getCause());
            return null;
        }
    }

    private static void injectHelper(EditFormTool tool, Form form) throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue("the helper must resolve the form model for this to prove anything", //$NON-NLS-1$
            helper.init());
        helper.formForTest(form);
        Field field = EditFormTool.class.getDeclaredField("helper"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(tool, helper);
    }

    private static Form formWithBaseForm(String... attributes)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        for (String name : attributes)
        {
            FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
            attribute.setName(name);
            form.getAttributes().add(attribute);
        }
        return form;
    }
}
