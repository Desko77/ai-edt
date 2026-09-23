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
     * {@code adoptObject} copies the real behaviour that matters here: after it returns, the
     * attribute belongs to the extension - the service model marks it adopted.
     * </p>
     */
    private static final class RecordingPort implements FormExtensionDataPathGuard.Port
    {
        final List<String> borrowed = new ArrayList<>();

        final Set<Object> belonging = new HashSet<>();

        String skipReason;

        boolean borrowFails;

        /** What the form answers about the path's segments. */
        boolean resolved = true;

        int belongingAsked;

        int borrowAsked;

        int skipAsked;

        int resolvedAsked;

        @Override
        public boolean isExtensionBelongingObject(Object attribute)
        {
            belongingAsked++;
            return belonging.contains(attribute);
        }

        @Override
        public void adoptObject(Object attribute) throws Exception
        {
            borrowAsked++;
            if (borrowFails)
            {
                throw new IllegalStateException("нет прав на заимствование"); //$NON-NLS-1$
            }
            borrowed.add(((FormAttribute) attribute).getName());
            belonging.add(attribute);
        }

        @Override
        public String exportSkipReason(Object form, Object dataPath)
        {
            skipAsked++;
            return skipReason;
        }

        @Override
        public boolean isPathResolved(Object form, Object dataPath)
        {
            resolvedAsked++;
            return resolved;
        }
    }

    @After
    public void removeTheInstalledPort()
    {
        FormExtensionDataPathGuard.installPort(null);
    }

    @Test
    public void aPathToABaseAttributeBorrowsItOnce()
    {
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(PATH), PATH, ITEM);

        assertTrue("the path is accepted once the attribute is borrowed", outcome.isAccepted());
        assertEquals("the answer names the borrowed attribute", List.of("Объект"), //$NON-NLS-1$
            outcome.getAdoptedAttributes());
        assertEquals("borrowed exactly once", 1, port.borrowAsked);
        assertNull("no refusal is reported for an accepted path", outcome.getRefusal());
    }

    @Test
    public void aBorrowThatCannotBeMadeIsRefused()
    {
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        port.borrowFails = true;
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(PATH), PATH, ITEM);

        assertFalse("an unborrowed attribute leaves the path unreachable", outcome.isAccepted());
        assertNotNull(outcome.getRefusal());
        assertTrue("the refusal carries the element, the path and the borrowed attribute", //$NON-NLS-1$
            outcome.getRefusal().startsWith(ITEM + ": путь данных " + PATH //$NON-NLS-1$
                + " не будет выгружен с формой расширения (заимствовать реквизит Объект не удалось: ")); //$NON-NLS-1$
        assertTrue("the refusal says nothing was written", //$NON-NLS-1$
            outcome.getRefusal().endsWith("; ничего не записано")); //$NON-NLS-1$
        assertEquals("the export is not asked about a path whose attribute is still absent", 0, //$NON-NLS-1$
            port.skipAsked);
        assertTrue("nothing is reported as borrowed", outcome.getAdoptedAttributes().isEmpty()); //$NON-NLS-1$
    }

    /**
     * A segment the form does not resolve is refused, whatever the export says.
     * <p>
     * The path is built here from segments, so the referred-object list the export reads first is
     * empty and the export answers "dropped" about any path at all. That answer says nothing about
     * the path, and only a path the form really resolves may be accepted on the strength of its
     * first segment: {@code Объект.НесуществующийРеквизит} names an attribute that does not exist,
     * and EDT would drop it on export - it must not reach Form.form.
     * </p>
     */
    @Test
    public void aPathTheFormDoesNotResolveIsRefused()
    {
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        port.skipReason = "путь не выгружается расширением"; //$NON-NLS-1$
        port.resolved = false;
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(UNRESOLVED_PATH), UNRESOLVED_PATH, ITEM);

        assertFalse("a path the form does not resolve must not be written", outcome.isAccepted());
        assertEquals("the attribute is borrowed before the export is asked about", 1, //$NON-NLS-1$
            port.borrowAsked);
        assertEquals("the export is asked once", 1, port.skipAsked); //$NON-NLS-1$
        assertEquals("and the resolution is asked before the answer is honoured", 1, //$NON-NLS-1$
            port.resolvedAsked);
        assertEquals("what was borrowed is still reported with the refusal", List.of("Объект"), //$NON-NLS-1$ //$NON-NLS-2$
            outcome.getAdoptedAttributes());
        assertEquals(ITEM + ": путь данных " + UNRESOLVED_PATH //$NON-NLS-1$
            + " не будет выгружен с формой расширения (" + port.skipReason //$NON-NLS-1$
            + "); ничего не записано", outcome.getRefusal()); //$NON-NLS-1$
    }

    /**
     * The same answer about a path the form does resolve is accepted.
     * <p>
     * This is the case the write paths of the tools are in: the export says "dropped" because the
     * referred-object list of a path built here is empty, while the segments resolve and the first
     * segment was borrowed just above. Refusing it would refuse every write of a data path.
     * </p>
     */
    @Test
    public void aPathTheFormResolvesSurvivesTheExportAnswer()
    {
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        port.skipReason = "путь не выгружается расширением"; //$NON-NLS-1$
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(PATH), PATH, ITEM);

        assertTrue("a path that resolves is written", outcome.isAccepted()); //$NON-NLS-1$
        assertNull("nothing is refused", outcome.getRefusal()); //$NON-NLS-1$
        assertEquals("the borrow is still reported", List.of("Объект"), //$NON-NLS-1$ //$NON-NLS-2$
            outcome.getAdoptedAttributes());
        assertEquals("the export is asked once", 1, port.skipAsked); //$NON-NLS-1$
        assertEquals("and the resolution is asked once", 1, port.resolvedAsked); //$NON-NLS-1$
    }

    @Test
    public void anAttributeOfTheExtensionIsNeitherBorrowedNorRefused()
    {
        Form form = extensionForm("СобственныйРеквизит"); //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        port.belonging.add(attribute(form, "СобственныйРеквизит")); //$NON-NLS-1$
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome = FormExtensionDataPathGuard.assign(
            form, path("СобственныйРеквизит.Поле"), "СобственныйРеквизит.Поле", ITEM); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(outcome.isAccepted());
        assertTrue("an own attribute is not borrowed", outcome.getAdoptedAttributes().isEmpty()); //$NON-NLS-1$
        assertEquals(0, port.borrowAsked);
        assertEquals("the export is still asked", 1, port.skipAsked); //$NON-NLS-1$
    }

    @Test
    public void aConfigurationFormNeverReachesTheSeam()
    {
        Form form = configurationForm("Объект"); //$NON-NLS-1$
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(PATH), PATH, ITEM);

        assertTrue(outcome.isAccepted());
        assertEquals("a configuration form asks nothing about extension adoption", 0, //$NON-NLS-1$
            port.belongingAsked);
        assertEquals(0, port.borrowAsked);
        assertEquals("and nothing about export of an extension form", 0, port.skipAsked); //$NON-NLS-1$
        assertTrue(outcome.getAdoptedAttributes().isEmpty());
    }

    @Test
    public void aFirstSegmentThatNamesNoAttributeIsLeftToTheFormCheck()
    {
        Form form = extensionForm();
        RecordingPort port = new RecordingPort();
        FormExtensionDataPathGuard.installPort(port);

        FormExtensionDataPathGuard.Outcome outcome =
            FormExtensionDataPathGuard.assign(form, path(PATH), PATH, ITEM);

        assertTrue("a path that names no form attribute is not this guard's question", //$NON-NLS-1$
            outcome.isAccepted());
        assertEquals(0, port.belongingAsked);
        assertEquals(0, port.skipAsked);
    }

    /**
     * The write paths of the tools, each named with the operations that take it.
     * <p>
     * Built rather than read off the operations: the operations need a project, a BM transaction
     * and a form reference, while the funnel each of them ends in is one call. The list is the
     * section 2.3 enumeration; a new funnel that assigns a data path belongs here too, and the
     * seam checks below run over every entry.
     * </p>
     */
    private static Map<String, WritePath> writePaths(BmFormHelper helper, Form form, FormField field)
    {
        Map<String, WritePath> paths = new LinkedHashMap<>();
        paths.put("add_field, add_table, add_form_attribute_column: BmFormHelper.setDataPath", //$NON-NLS-1$
            () -> helper.setDataPath(field, PATH));
        paths.put("set_property, set_form_item_property: dataPath", //$NON-NLS-1$
            () -> helper.setItemProperty(form, ITEM, "dataPath", PATH)); //$NON-NLS-1$
        paths.put("set_property, set_form_item_property: footerDataPath (data-path-valued property)", //$NON-NLS-1$
            () -> helper.setItemProperty(form, ITEM, "footerDataPath", PATH)); //$NON-NLS-1$
        return paths;
    }

    @Test
    public void everyWritePathOfTheToolsAsksTheSeam() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue("the helper must resolve the form model for this to prove anything", helper.init()); //$NON-NLS-1$
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        FormField field = fieldIn(form);
        helper.formForTest(form);

        for (Map.Entry<String, WritePath> entry : writePaths(helper, form, field).entrySet())
        {
            RecordingPort port = new RecordingPort();
            // The answer a real port gives about a path built here, where the
            // referred-object list the export reads first is empty.
            port.skipReason = "путь не выгружается расширением"; //$NON-NLS-1$
            FormExtensionDataPathGuard.installPort(port);

            entry.getValue().call();

            assertEquals(entry.getKey() + ": the guard asks whether the attribute belongs to the " //$NON-NLS-1$
                + "extension, and asks again to confirm the borrow took", 2, port.belongingAsked); //$NON-NLS-1$
            assertEquals(entry.getKey() + ": and borrows it", 1, port.borrowAsked); //$NON-NLS-1$
            assertEquals(entry.getKey() + ": and asks about export", 1, port.skipAsked); //$NON-NLS-1$
            assertEquals(entry.getKey() + ": and asks the form to resolve the path, which is what " //$NON-NLS-1$
                + "decides the export answer", 1, port.resolvedAsked); //$NON-NLS-1$
            assertTrue(entry.getKey() + ": the borrow is reported back to the caller", //$NON-NLS-1$
                helper.getAdoptedFormAttributes().contains("Объект")); //$NON-NLS-1$
        }
    }

    @Test
    public void everyWritePathLetsTheRefusalOut() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект"); //$NON-NLS-1$
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
                    + "into an error string - the write rolls back on the exception"); //$NON-NLS-1$
            }
            catch (FormExtensionDataPathGuard.RefusalException expected)
            {
                assertNotNull(entry.getKey() + ": the refusal carries what to report", //$NON-NLS-1$
                    expected.getOutcome().getRefusal());
            }
        }
    }

    /**
     * A write that was refused and rolled back leaves no borrowed names behind.
     * <p>
     * The guard records the borrow before the export answers, and a refusal throws out of the
     * transaction afterwards: the borrow happened in the model, but the model was rolled back, so
     * the form the caller finds on disk has no borrowed attribute and the answer must not name one.
     * </p>
     */
    @Test
    public void aRefusedWriteLeavesNoBorrowedNames() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        FormField field = fieldIn(form);
        helper.formForTest(form);
        RecordingPort port = new RecordingPort();
        port.skipReason = "путь не выгружается расширением"; //$NON-NLS-1$
        port.resolved = false;
        FormExtensionDataPathGuard.installPort(port);

        try
        {
            helper.setDataPath(field, PATH);
            fail("the write must be refused, nothing reaches Form.form"); //$NON-NLS-1$
        }
        catch (FormExtensionDataPathGuard.RefusalException expected)
        {
            assertEquals("the refusal still reports what it borrowed before giving up", //$NON-NLS-1$
                List.of("Объект"), expected.getOutcome().getAdoptedAttributes()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        assertTrue("nothing was borrowed in a form that was rolled back", //$NON-NLS-1$
            helper.getAdoptedFormAttributes().isEmpty());
    }

    /**
     * A second write through the same helper does not answer with the first one's borrow.
     * <p>
     * A tool keeps its helper across requests (EditFormTool holds one for its lifetime), so the names
     * of one write were still there for the next one - a name from another form, and after a rollback
     * a name of an attribute that was never borrowed at all.
     * </p>
     */
    @Test
    public void aSecondWriteDoesNotNameTheFirstOnesBorrow() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());

        Form first = extensionForm("Объект"); //$NON-NLS-1$
        FormField firstField = fieldIn(first);
        helper.formForTest(first);
        FormExtensionDataPathGuard.installPort(new RecordingPort());
        helper.setDataPath(firstField, PATH);

        assertEquals("the first write names what it borrowed", List.of("Объект"), //$NON-NLS-1$ //$NON-NLS-2$
            helper.getAdoptedFormAttributes());

        // The next write is another form, and it borrows nothing: its own
        // attribute already belongs to the extension.
        Form second = extensionForm("СобственныйРеквизит"); //$NON-NLS-1$
        FormField secondField = fieldIn(second);
        RecordingPort port = new RecordingPort();
        port.belonging.add(attribute(second, "СобственныйРеквизит")); //$NON-NLS-1$
        helper.formForTest(second);
        FormExtensionDataPathGuard.installPort(port);
        helper.setDataPath(secondField, "СобственныйРеквизит.Поле"); //$NON-NLS-1$

        assertEquals("the second write borrows nothing", 0, port.borrowAsked); //$NON-NLS-1$
        assertTrue("the second write must not answer with the first one's borrow", //$NON-NLS-1$
            helper.getAdoptedFormAttributes().isEmpty());
    }

    /**
     * The names the guard collected are written where the caller reads the outcome.
     * <p>
     * Only the writer is checked here: the helper owns the list, and the operations that read the
     * line back live in another package. The reader is pinned by
     * {@code TheAnswerNamesWhatWasBorrowedTest} against this exact line.
     * </p>
     */
    @Test
    public void theBorrowedNamesAreWrittenIntoTheFrontMatter() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = extensionForm("Объект"); //$NON-NLS-1$
        FormField field = fieldIn(form);
        helper.formForTest(form);
        FormExtensionDataPathGuard.installPort(new RecordingPort());

        helper.setDataPath(field, PATH);
        String answer = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("ok"); //$NON-NLS-1$

        String annotated = helper.annotateAdopted(answer);

        assertTrue("the borrowed name must go into the front matter, where the caller reads the " //$NON-NLS-1$
            + "outcome: " + annotated, annotated.contains("\nadoptedFormAttributes: Объект\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a helper that borrowed nothing leaves the answer alone", answer, //$NON-NLS-1$
            new BmFormHelper().annotateAdopted(answer));
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
        throw new AssertionError("the form has no attribute named " + name); //$NON-NLS-1$
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
        path.getSegments().addAll(Arrays.asList(text.split("\\."))); //$NON-NLS-1$
        return path;
    }
}
