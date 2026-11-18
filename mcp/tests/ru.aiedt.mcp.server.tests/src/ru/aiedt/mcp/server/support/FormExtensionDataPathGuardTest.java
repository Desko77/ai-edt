/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;

/**
 * A data path an element of an extension form receives reaches the database, or the write is
 * refused.
 * <p>
 * Measured on the stand (spec 2.5, section 1): {@code edit_metadata add_field} with
 * {@code dataPath=Объект.Description} answered success and wrote the path into Form.form, while the
 * exported XML carried no DataPath at all - the base-form attribute was never borrowed into the
 * extension. Both halves of the decision are questions for EDT services, so the guard asks them
 * behind {@link FormExtensionDataPathGuard.Port} and this class installs its own port and watches
 * every question.
 * </p>
 * <p>
 * The model objects are real EMF forms, not stand-ins: an extension form is a form with a base form
 * and the guard reads exactly that, so a fake would prove the fake.
 * </p>
 * <p>
 * The port answers in three states, not two: a question a runtime cannot ask is
 * {@link FormExtensionDataPathGuard.Answer#NOT_ASKED}, and the tests below pin both what the guard
 * does with such an answer on each of its three questions and the fact that the answer names the
 * check instead of passing it off as passed.
 * </p>
 */
public class FormExtensionDataPathGuardTest
{
    private static final String PATH = "Объект.Description"; //$NON-NLS-1$

    /** The finding's example: a second segment no attribute of the form carries. */
    private static final String UNRESOLVED_PATH = "Объект.НесуществующийРеквизит"; //$NON-NLS-1$

    private static final String ITEM = "ПолеВвода"; //$NON-NLS-1$

    /** A write path of the helper, as one call the operations make. */
    @FunctionalInterface
    private interface WritePath
    {
        void call() throws Exception;
    }

    /**
     * The port a test installs. It records every question and answers from what the test set.
     * <p>
     * {@code adoptObject} borrows through a real reflective call, the way the runtime does, so a
     * failure reaches the guard wrapped in {@code InvocationTargetException} - which is what EDT
     * hands over and what the refusal has to see through.
     * </p>
     */
    private static final class RecordingPort implements FormExtensionDataPathGuard.Port
    {
        final List<String> borrowed = new ArrayList<>();

        final Set<Object> belonging = new HashSet<>();

        /** What the port says about an attribute that is not in {@link #belonging}. */
        FormExtensionDataPathGuard.Answer notOwned = FormExtensionDataPathGuard.Answer.FALSE;

        /** The answer about export; TRUE is what a real port says about a path built here. */
        FormExtensionDataPathGuard.Answer export = FormExtensionDataPathGuard.Answer.TRUE;

        /** The answer about whether the form resolves the path. */
        FormExtensionDataPathGuard.Answer resolves = FormExtensionDataPathGuard.Answer.TRUE;

        boolean borrowFails;

        int belongingAsked;

        int borrowAsked;

        int skipAsked;

        int resolvedAsked;

        @Override
        public FormExtensionDataPathGuard.Answer isExtensionBelongingObject(Object attribute)
        {
            belongingAsked++;
            return belonging.contains(attribute) ? FormExtensionDataPathGuard.Answer.TRUE : notOwned;
        }

        @Override
        public void adoptObject(Object attribute) throws Exception
        {
            borrowAsked++;
            Method adopt = RecordingPort.class.getDeclaredMethod("borrow", Object.class); //$NON-NLS-1$
            adopt.invoke(this, attribute);
        }

        private void borrow(Object attribute)
        {
            if (borrowFails)
            {
                throw new IllegalStateException("нет прав на заимствование"); //$NON-NLS-1$
            }
            borrowed.add(((FormAttribute) attribute).getName());
            belonging.add(attribute);
        }

        @Override
        public FormExtensionDataPathGuard.Answer exportSkip(Object form, Object dataPath)
        {
            skipAsked++;
            return export;
        }

        @Override
        public FormExtensionDataPathGuard.Answer pathResolved(Object form, Object dataPath)
        {
            resolvedAsked++;
            return resolves;
        }
    }

    @After
    public void removeTheInstalledPort()
    {
        FormExtensionDataPathGuard.installPort(null);
        FormExtensionDataPathGuard.installProjectKind(null);
    }


    @Test
    public void aConfigurationProjectWinsOverANonNullBaseForm()
    {
        // A base form is declared, and the project says configuration.
        Form form = extensionForm("Объект");
        //$NON-NLS-1$
        FormExtensionDataPathGuard.installProjectKind(f -> Boolean.FALSE);
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(UNRESOLVED_PATH), UNRESOLVED_PATH, ITEM);

        assertTrue("a configuration form is not the guard's business", outcome.isAccepted());
        //$NON-NLS-1$
        assertNull("no extension refusal may name a configuration form", outcome.getRefusal());
        //$NON-NLS-1$
        assertEquals("the port is asked nothing about belonging", 0, port.belongingAsked);
        //$NON-NLS-1$
        assertEquals(0, port.borrowAsked);
        assertEquals("and nothing about export of an extension form", 0, port.skipAsked);
        //$NON-NLS-1$
        assertEquals("and nothing about the path's resolution", 0, port.resolvedAsked);
        //$NON-NLS-1$
    }

@Test
    public void aFormWithoutAProjectIsClassifiedByItsBaseForm()
    {
        Form form = extensionForm("Object");
        FormExtensionDataPathGuard.installProjectKind(f -> null);
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        assertFalse(FormExtensionDataPathGuard.assign(form, path("Object.Description"), "Object.Description", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
    }

    @Test
    public void aPathToABaseAttributeRefusesWithoutBorrowing()
    {
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        FormExtensionDataPathGuard.Outcome outcome = FormExtensionDataPathGuard.assign(extensionForm("Object"), path("Object.Description"), "Object.Description", ITEM);
        assertFalse(outcome.isAccepted());
        assertTrue(outcome.getAdoptedAttributes().isEmpty());
        assertEquals(0, port.borrowAsked);
    }

@Test
    public void aRootInAnotherCaseNamesTheSameAttribute()
    {
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        assertFalse(FormExtensionDataPathGuard.assign(extensionForm("Object"), path("object.Description"), "object.Description", ITEM).isAccepted());
        assertEquals(1, port.belongingAsked);
        assertEquals(0, port.borrowAsked);
    }

@Test
    public void aBindingNeverCallsEvenAFailingBorrowService()
    {
        RecordingPort port = new RecordingPort();
        port.borrowFails = true;
        FormExtensionDataPathGuard.installPort(port);
        FormExtensionDataPathGuard.Outcome outcome = FormExtensionDataPathGuard.assign(extensionForm("Object"), path("Object.Description"), "Object.Description", ITEM);
        assertFalse(outcome.isAccepted());
        assertTrue(outcome.getRefusal().contains("EXTENSION_BINDING_UNSUPPORTED"));
        assertEquals(0, port.borrowAsked);
    }

@Test
    public void aPathTheFormDoesNotResolveIsRefused()
    {
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.TRUE;
        port.resolves = FormExtensionDataPathGuard.Answer.FALSE;
        FormExtensionDataPathGuard.installPort(port);
        assertFalse(FormExtensionDataPathGuard.assign(extensionForm("Object"), path("Object.Missing"), "Object.Missing", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
        assertEquals(1, port.resolvedAsked);
    }

@Test
    public void aResolvedInheritedRootStillRefuses()
    {
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        assertFalse("resolution must not authorize implicit root adoption", FormExtensionDataPathGuard.assign(extensionForm("Object"), path("Object.Description"), "Object.Description", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
    }

@Test
    public void anAttributeOfTheExtensionIsNeitherBorrowedNorRefused()
    {
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.TRUE;
        port.export = FormExtensionDataPathGuard.Answer.FALSE;
        FormExtensionDataPathGuard.installPort(port);
        assertTrue(FormExtensionDataPathGuard.assign(extensionForm("Own"), path("Own.Field"), "Own.Field", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
        assertEquals(1, port.resolvedAsked);
    }

    @Test
    public void aConfigurationFormNeverReachesTheSeam()
    {
        Form form = configurationForm("Объект");
        //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(PATH), PATH, ITEM);

        assertTrue(outcome.isAccepted());
        assertEquals("a configuration form asks nothing about extension adoption", 0, //$NON-NLS-1$
            port.belongingAsked);
        assertEquals(0, port.borrowAsked);
        assertEquals("and nothing about export of an extension form", 0, port.skipAsked);
        //$NON-NLS-1$
        assertEquals("and nothing about the path's resolution", 0, port.resolvedAsked);
        //$NON-NLS-1$
        assertTrue(outcome.getAdoptedAttributes().isEmpty());
    }

@Test
    public void aRootThatNamesNoFormAttributeIsStillAskedAboutTheExport()
    {
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        assertFalse(FormExtensionDataPathGuard.assign(extensionForm(), path("Missing.Field"), "Missing.Field", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
    }

@Test
    public void aResolvedRootWithoutOwnershipProofRefuses()
    {
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        assertFalse("unknown ownership must not be inferred from derived resolution", FormExtensionDataPathGuard.assign(extensionForm(), path("Object.Rows"), "Object.Rows", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
    }

@Test
    public void anUnansweredExportQuestionDoesNotHideAnUnresolvedPath()
    {
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.TRUE;
        port.export = FormExtensionDataPathGuard.Answer.NOT_ASKED;
        port.resolves = FormExtensionDataPathGuard.Answer.FALSE;
        FormExtensionDataPathGuard.installPort(port);
        assertFalse(FormExtensionDataPathGuard.assign(extensionForm("Own"), path("Own.Missing"), "Own.Missing", ITEM).isAccepted());
        assertEquals(0, port.borrowAsked);
    }

    @Test
    public void anUnansweredResolutionQuestionIsNamedAndRefused()
    {
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.TRUE;
        port.resolves = FormExtensionDataPathGuard.Answer.NOT_ASKED;
        FormExtensionDataPathGuard.installPort(port);
        FormExtensionDataPathGuard.Outcome outcome = FormExtensionDataPathGuard.assign(extensionForm("Own"), path("Own.Field"), "Own.Field", ITEM);
        assertFalse(outcome.isAccepted());
        assertEquals(List.of(FormExtensionDataPathGuard.CHECK_RESOLUTION), outcome.getNotAskedChecks());
    }

@Test
    public void anUnansweredOwnershipQuestionRefusesWithoutAdoption()
    {
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.NOT_ASKED;
        FormExtensionDataPathGuard.installPort(port);
        FormExtensionDataPathGuard.Outcome outcome = FormExtensionDataPathGuard.assign(extensionForm("Object"), path("Object.Field"), "Object.Field", ITEM);
        assertFalse(outcome.isAccepted());
        assertEquals(List.of(FormExtensionDataPathGuard.CHECK_BELONGING), outcome.getNotAskedChecks());
        assertEquals(0, port.borrowAsked);
    }

private static Map<String, WritePath> writePaths(BmFormHelper helper, Form form, FormField field)
    {
        Map<String, WritePath> paths = new LinkedHashMap<>();
        paths.put("add_field, add_table, add_form_attribute_column: BmFormHelper.setDataPath", //$NON-NLS-1$
            () -> helper.setDataPath(field, PATH));
        paths.put("set_property, set_form_item_property: dataPath", //$NON-NLS-1$
            () -> helper.setItemProperty(form, ITEM, "dataPath", PATH));
        //$NON-NLS-1$
        paths.put("set_property, set_form_item_property: footerDataPath (data-path-valued property)", //$NON-NLS-1$
            () -> helper.setItemProperty(form, ITEM, "footerDataPath", PATH));
        //$NON-NLS-1$
        return paths;
    }

    @Test
    public void everyWritePathOfTheToolsAsksTheSeam() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект");
        FormField field = fieldIn(form);
        helper.formForTest(form);
        for (WritePath write : writePaths(helper, form, field).values()) { RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);
        try { write.call();
        fail("inherited root must refuse");
        } catch (FormExtensionDataPathGuard.RefusalException expected) { assertNotNull(expected.getOutcome().getRefusal());
        } assertEquals(0, port.borrowAsked);
        assertNull(field.getDataPath());
        }
    }

    @Test
    public void everyWritePathLetsTheRefusalOut() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект");
        //$NON-NLS-1$
        FormField field = fieldIn(form);
        helper.formForTest(form);

        for (Map.Entry<String, WritePath> entry : writePaths(helper, form, field).entrySet())
        {
            RecordingPort port = new RecordingPort();
            port.borrowFails = true;
            FormExtensionDataPathGuard.installPort(port);

            try
            {
                entry.getValue().call();
                fail(entry.getKey() + ": a refusal must reach the transaction, not be turned " //$NON-NLS-1$
                    + "into an error string - the write rolls back on the exception");
        //$NON-NLS-1$
            }
            catch (FormExtensionDataPathGuard.RefusalException expected)
            {
                assertNotNull(entry.getKey() + ": the refusal carries what to report", //$NON-NLS-1$
                    expected.getOutcome().getRefusal());
            }
        }
    }

@Test
    public void theRefusalRuleLetsARefusalThroughAnyCatchBlock() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект");
        //$NON-NLS-1$
        FormField field = fieldIn(form);
        helper.formForTest(form);
        RecordingPort port = new RecordingPort();
        port.borrowFails = true;
        FormExtensionDataPathGuard.installPort(port);
        FormExtensionDataPathGuard.RefusalException refusal;
        try
        {
            helper.setDataPath(field, PATH);
            throw new AssertionError("the fixture must produce a refusal");
        //$NON-NLS-1$
        }
        catch (FormExtensionDataPathGuard.RefusalException refused)
        {
            refusal = refused;
        }

        // A caught refusal goes out, whether it was caught directly or through the
        // reflection wrapper a call inside the try block put it in.
        for (Exception caught : List.of(refusal,
            new java.lang.reflect.InvocationTargetException(refusal)))
        {
            try
            {
                FormExtensionDataPathGuard.rethrowIfRefusal(caught);
                fail("a refusal must go out of the catch block, not be turned into a note");
        //$NON-NLS-1$
            }
            catch (FormExtensionDataPathGuard.RefusalException expected)
            {
                assertNotNull(expected.getOutcome().getRefusal());
            }
        }
        // Every other exception keeps the behaviour the caller had for it: no
        // exception at all means no exception leaves.
        FormExtensionDataPathGuard.rethrowIfRefusal(new IllegalStateException("нет прав"));
        //$NON-NLS-1$
    }

@Test
    public void aRefusedWriteLeavesNoBorrowedNames() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект");
        FormField field = fieldIn(form);
        helper.formForTest(form);
        FormExtensionDataPathGuard.installPort(new RecordingPort());
        try { helper.setDataPath(field, PATH);
        fail("must refuse");
        } catch (FormExtensionDataPathGuard.RefusalException expected) { assertTrue(expected.getOutcome().getAdoptedAttributes().isEmpty());
        } assertTrue(helper.getAdoptedFormAttributes().isEmpty());
        assertNull(field.getDataPath());
    }

@Test
    public void aSecondWriteDoesNotNameTheFirstOnesBorrow() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        for (int i=0;
        i<2;
        i++) { Form form = extensionForm("Объект");
        FormField field = fieldIn(form);
        helper.formForTest(form);
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.TRUE;
        port.export = FormExtensionDataPathGuard.Answer.FALSE;
        FormExtensionDataPathGuard.installPort(port);
        helper.setDataPath(field, PATH);
        assertTrue(helper.getAdoptedFormAttributes().isEmpty());
        assertEquals(0, port.borrowAsked);
        }
    }

@Test
    public void aSafeBindingDoesNotReportImplicitAdoption() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект");
        //$NON-NLS-1$
        FormField field = fieldIn(form);
        helper.formForTest(form);
        RecordingPort owned = new RecordingPort();
        owned.notOwned = FormExtensionDataPathGuard.Answer.TRUE;
        owned.export = FormExtensionDataPathGuard.Answer.FALSE;
        FormExtensionDataPathGuard.installPort(owned);

        helper.setDataPath(field, PATH);
        String answer = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("ok");
        //$NON-NLS-1$

        String annotated = helper.annotateAdopted(answer);

        assertFalse("binding must not report implicit adoption", annotated.contains("adoptedFormAttributes:"));
        assertEquals("a helper that borrowed nothing leaves the answer alone", answer, //$NON-NLS-1$
            new BmFormHelper().annotateAdopted(answer));
    }

@Test
    public void unavailableChecksRemainOnTheRefusedOutcome() throws Exception
    {
        RecordingPort port = new RecordingPort();
        port.notOwned = FormExtensionDataPathGuard.Answer.NOT_ASKED;
        FormExtensionDataPathGuard.installPort(port);
        FormExtensionDataPathGuard.Outcome outcome = FormExtensionDataPathGuard.assign(extensionForm("Object"), path("Object.Field"), "Object.Field", ITEM);
        assertFalse(outcome.isAccepted());
        assertTrue(outcome.getNotAskedChecks().contains(FormExtensionDataPathGuard.CHECK_BELONGING));
    }

    private static Form extensionForm(String... attributes)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setBaseForm(FormFactory.eINSTANCE.createForm());
        addAttributes(form, attributes);
        return form;
    }

    private static Form configurationForm(String... attributes)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        addAttributes(form, attributes);
        return form;
    }

    private static void addAttributes(Form form, String... names)
    {
        for (String name : names)
        {
            FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
            attribute.setName(name);
            form.getAttributes().add(attribute);
        }
    }

    private static FormAttribute attribute(Form form, String name)
    {
        for (FormAttribute attribute : form.getAttributes())
        {
            if (name.equals(attribute.getName()))
            {
                return attribute;
            }
        }
        throw new AssertionError("the form has no attribute named " + name);
        //$NON-NLS-1$
    }

    private static FormField fieldIn(Form form)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(ITEM);
        form.getItems().add(field);
        return field;
    }

    private static AbstractDataPath path(String text)
    {
        AbstractDataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList(text.split("\\.")));
        //$NON-NLS-1$
        return path;
    }
}
