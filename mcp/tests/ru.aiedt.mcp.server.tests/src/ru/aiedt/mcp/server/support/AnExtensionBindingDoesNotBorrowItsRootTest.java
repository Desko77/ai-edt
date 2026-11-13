/* Copyright (c) 2026 Desko77. SPDX-License-Identifier: AGPL-3.0-or-later */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;
import com._1c.g5.v8.dt.form.model.*;

/** Regression for report 6: binding never expands the extension metadata graph. */
public class AnExtensionBindingDoesNotBorrowItsRootTest
{
    private int adoptions;
    @After public void reset()
    {
        FormExtensionDataPathGuard.installPort(null);
        FormExtensionDataPathGuard.installProjectKind(null);
    }

    private FormExtensionDataPathGuard.Outcome assign(FormExtensionDataPathGuard.Answer belongs,
        FormExtensionDataPathGuard.Answer resolves)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName("Object");
        form.getAttributes().add(attribute);
        FormExtensionDataPathGuard.installProjectKind(f -> Boolean.TRUE);
        FormExtensionDataPathGuard.installPort(new FormExtensionDataPathGuard.Port()
        {
            public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object value)
            { return belongs; }
            public void adoptObject(Object value) { adoptions++; }
            public FormExtensionDataPathGuard.Answer exportSkip(Object f, Object p)
            { return FormExtensionDataPathGuard.Answer.TRUE; }
            public FormExtensionDataPathGuard.Answer pathResolved(Object f, Object p)
            { return resolves; }
        });
        AbstractDataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList("Object", "OwnField"));
        FormExtensionDataPathGuard.Outcome result =
            FormExtensionDataPathGuard.assign(form, path, "Object.OwnField", "Input");
        assertEquals("the binding must not add or remove metadata children", 1, form.getAttributes().size());
        assertSame(attribute, form.getAttributes().get(0));
        return result;
    }

    /** Both assignment funnels must check safety before replacing an existing path. */
    @Test public void refusedSetterPreservesThePreviousDataPath() throws Exception
    {
        assertRefusedSetterPreservesPath(false, false);
        assertRefusedSetterPreservesPath(true, false);
    }

    @Test public void refusedSetterDoesNotInstallAPathOnAnUnboundField() throws Exception
    {
        assertRefusedSetterPreservesPath(false, true);
        assertRefusedSetterPreservesPath(true, true);
    }

    private void assertRefusedSetterPreservesPath(boolean viaProperty, boolean initiallyUnbound)
        throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName("Object");
        form.getAttributes().add(attribute);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Input");
        form.getItems().add(field);
        AbstractDataPath previous = null;
        if (!initiallyUnbound)
        {
            previous = FormFactory.eINSTANCE.createDataPath();
            previous.getSegments().add("Previous");
            field.setDataPath(previous);
        }
        helper.formForTest(form);
        FormExtensionDataPathGuard.installProjectKind(f -> Boolean.TRUE);
        FormExtensionDataPathGuard.installPort(new FormExtensionDataPathGuard.Port()
        {
            public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object value)
            { return FormExtensionDataPathGuard.Answer.FALSE; }
            public void adoptObject(Object value) { adoptions++; }
            public FormExtensionDataPathGuard.Answer exportSkip(Object f, Object p)
            { return FormExtensionDataPathGuard.Answer.TRUE; }
            public FormExtensionDataPathGuard.Answer pathResolved(Object f, Object p)
            { return FormExtensionDataPathGuard.Answer.FALSE; }
        });
        try
        {
            if (viaProperty)
            {
                helper.setItemProperty(form, "Input", "dataPath", "Object.Missing");
            }
            else
            {
                helper.setDataPath(field, "Object.Missing");
            }
            fail("a refused extension binding must escape the setter");
        }
        catch (FormExtensionDataPathGuard.RefusalException expected)
        {
            assertNotNull(expected.getOutcome().getRefusal());
        }
        assertSame("preflight refusal must preserve the original path identity", previous,
            field.getDataPath());
        if (previous != null)
        {
            assertEquals(Arrays.asList("Previous"), previous.getSegments());
        }
        assertEquals("refusal must not expand extension metadata", 0, adoptions);
        assertEquals(1, form.getAttributes().size());
        assertEquals(1, form.getItems().size());
        assertSame(field, form.getItems().get(0));
    }

    @Test public void aTypedOwnScalarDoesNotNeedTheDerivedPathIndex()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        FormAttribute own = FormFactory.eINSTANCE.createFormAttribute();
        own.setName("OwnScalar");
        com._1c.g5.v8.dt.mcore.TypeDescription type =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeDescription();
        com._1c.g5.v8.dt.mcore.Type string = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
        string.setName("String");
        type.getTypes().add(string);
        own.setValueType(type);
        form.getAttributes().add(own);
        FormExtensionDataPathGuard.installProjectKind(f -> Boolean.TRUE);
        FormExtensionDataPathGuard.installPort(new FormExtensionDataPathGuard.Port()
        {
            public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object value)
            { return FormExtensionDataPathGuard.Answer.TRUE; }
            public void adoptObject(Object value) { fail("no adoption"); }
            public FormExtensionDataPathGuard.Answer exportSkip(Object f, Object p)
            { return FormExtensionDataPathGuard.Answer.NOT_ASKED; }
            public FormExtensionDataPathGuard.Answer pathResolved(Object f, Object p)
            { return FormExtensionDataPathGuard.Answer.NOT_ASKED; }
        });
        AbstractDataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().add("OwnScalar");
        assertTrue("a locally typed own scalar needs no derived path index",
            FormExtensionDataPathGuard.assign(form, path, "OwnScalar", "Input").isAccepted());
    }

    @Test public void anOwnTypedColumnWorksBeforeDerivedResolution() throws Exception
    {
        aTypedOwnScalarDoesNotNeedTheDerivedPathIndex();
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        FormAttribute table = FormFactory.eINSTANCE.createFormAttribute();
        table.setName("Rows");
        FormAttributeColumn column = FormFactory.eINSTANCE.createFormAttributeColumn();
        column.setName("Text");
        com._1c.g5.v8.dt.mcore.TypeDescription type =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeDescription();
        com._1c.g5.v8.dt.mcore.Type string = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
        string.setName("String");
        type.getTypes().add(string);
        column.setValueType(type);
        table.getColumns().add(column);
        form.getAttributes().add(table);
        AbstractDataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList("Rows", "Text"));
        assertTrue(FormExtensionDataPathGuard.assign(form, path, "Rows.Text", "Input").isAccepted());
    }

    @Test public void resolvedButExportUnverifiedTailStillRefuses()
    {
        assertFalse("derived resolution alone cannot prove extension persistence",
            assign(FormExtensionDataPathGuard.Answer.TRUE,
                FormExtensionDataPathGuard.Answer.TRUE).isAccepted());
    }

    @Test public void inheritedRootRefusesWithoutCallingAdoption()
    {
        assertFalse(assign(FormExtensionDataPathGuard.Answer.FALSE,
            FormExtensionDataPathGuard.Answer.TRUE).isAccepted());
        assertEquals("no implicit root or dependency adoption", 0, adoptions);
    }

    @Test public void unavailableOwnershipCheckRefusesWithoutAdoption()
    {
        FormExtensionDataPathGuard.Outcome outcome = assign(
            FormExtensionDataPathGuard.Answer.NOT_ASKED, FormExtensionDataPathGuard.Answer.TRUE);
        assertFalse("unknown safety must not become success", outcome.isAccepted());
        assertTrue(outcome.getNotAskedChecks().contains(FormExtensionDataPathGuard.CHECK_BELONGING));
        assertEquals("EXTENSION_BINDING_UNVERIFIED", outcome.getErrorCode());
        assertTrue(BmFormHelper.formatDataPathRefusal(outcome)
            .startsWith("EXTENSION_BINDING_UNVERIFIED: "));
        assertEquals(0, adoptions);
    }

    @Test public void unresolvedOwnTailMustNotBeAcceptedMerelyBecauseRootIsOwn()
    {
        assertFalse(assign(FormExtensionDataPathGuard.Answer.TRUE,
            FormExtensionDataPathGuard.Answer.FALSE).isAccepted());
        assertEquals(0, adoptions);
    }
}
