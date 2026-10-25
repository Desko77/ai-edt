/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * The four kinds a handful of the corpus forms hold are written from the record the corpus shares:
 * an HTML field (21 fields on 18 forms), a list field (17 on 16), a text-document field (17 on
 * 10) and a chart (12 on 9). The reader names each kind; a chart record is seven items, with a
 * version tag {11} before the own list, and the reader takes its name and its box from one item
 * further than another kind's. The list field is the only one bound to data - a value list - and
 * the other three refuse data by name; the text-document field is the only one the corpus holds
 * no event on.
 */
public class ARareKindIsWrittenFromAMeasuredRecordTest
{
    private static final int[] BOX = { 10, 200, 400, 400 };

    private static final String VALUE_LIST = "{\"Pattern\",{\"#\",4772b3b4-f4a3-49c0-a1a5-8cb5961511a3}}"; //$NON-NLS-1$

    // ---- an HTML field -----------------------------------------------------------------------

    /** An HTML field is written on a version-25 form and read back, byte for byte. */
    @Test
    public void anHtmlFieldIsWrittenOnVersion25() throws Exception
    {
        htmlAdded("fixtures/settings-form-v25.form"); //$NON-NLS-1$
    }

    /** An HTML field is written on a version-26 form and read back, byte for byte. */
    @Test
    public void anHtmlFieldIsWrittenOnVersion26() throws Exception
    {
        htmlAdded("fixtures/settings-form-v26.form"); //$NON-NLS-1$
    }

    /** An HTML field is written on a version-27 form and read back, byte for byte. */
    @Test
    public void anHtmlFieldIsWrittenOnVersion27() throws Exception
    {
        htmlAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
    }

    /**
     * The record the corpus writes for an HTML field holds no style block, so there is no slot
     * for a tooltip; the caller is told instead of being answered success.
     */
    @Test
    public void anHtmlFieldCarriesNoTooltip() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome asked = editor.addElement("FieldHtml", "ПолеHTML", null, null, BOX, null, //$NON-NLS-1$ //$NON-NLS-2$
            "Подсказка", null); //$NON-NLS-1$
        assertFalse(asked.done);
        assertTrue(asked.message, asked.message.contains("no slot")); //$NON-NLS-1$
    }

    /** The corpus binds no form attribute to an HTML field, so any data is refused by name. */
    @Test
    public void anHtmlFieldRefusesData() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = editor.text();
        for (String pattern : new String[] { "{\"Pattern\",{\"S\",10}}", "{\"Pattern\",{\"N\",10,2}}", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"Pattern\",{\"#\",acf6192e-81ca-46ef-93a6-5a6968b78663}}", "{\"Pattern\"}" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            LayoutEditor.Outcome outcome = editor.addElement("FieldHtml", "ПолеHTML", null, null, BOX, null, null, //$NON-NLS-1$ //$NON-NLS-2$
                BraceTree.parse(pattern));
            assertFalse(pattern, outcome.done);
            assertTrue(pattern, outcome.message.contains("HTML document set in code")); //$NON-NLS-1$
        }
        assertEquals(before, editor.text());
    }

    /** The event the corpus binds most often on an HTML field - onclick, code 2 - is bound and read back. */
    @Test
    public void anHtmlFieldBindsOnClick() throws Exception
    {
        LayoutEditor editor = htmlAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor.Outcome bound = editor.bindHandler("ПолеHTML", "onclick", "ПолеHTMLonclick"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(bound.message, bound.done);
        String written = editor.text();
        assertEquals(written, BraceTree.serialize(BraceTree.parse(written)));
        FormLayout.Element field = LayoutReader.read(BraceTree.parse(written), "Report").elementNamed("ПолеHTML"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, field.handlers.size());
        assertEquals("onclick", field.handlers.get(0).event); //$NON-NLS-1$
    }

    // ---- a list field ------------------------------------------------------------------------

    /** A list field is written on a version-25 form, bound to a value list, and read back. */
    @Test
    public void aListFieldIsWrittenOnVersion25() throws Exception
    {
        listAdded("fixtures/settings-form-v25.form"); //$NON-NLS-1$
    }

    /** A list field is written on a version-26 form, bound to a value list, and read back. */
    @Test
    public void aListFieldIsWrittenOnVersion26() throws Exception
    {
        listAdded("fixtures/settings-form-v26.form"); //$NON-NLS-1$
    }

    /** A list field is written on a version-27 form, bound to a value list, and read back. */
    @Test
    public void aListFieldIsWrittenOnVersion27() throws Exception
    {
        listAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
    }

    /**
     * Every one of the 17 list fields of the corpus is bound to a value list, so a list field
     * without data is refused, and so is data of any other type - by name.
     */
    @Test
    public void aListFieldRefusesDataOutsideTheValueList() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = editor.text();
        LayoutEditor.Outcome bare = editor.addElement("ListField", "ПолеСписка", null, null, BOX, null, null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(bare.done);
        assertTrue(bare.message, bare.message.contains("value list")); //$NON-NLS-1$
        for (String pattern : new String[] { "{\"Pattern\",{\"S\",10}}", "{\"Pattern\",{\"N\",10,2}}", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"Pattern\",{\"B\"}}", "{\"Pattern\",{\"#\",acf6192e-81ca-46ef-93a6-5a6968b78663}}", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"Pattern\",{\"#\",4772b3b4-f4a3-49c0-a1a5-8cb5961511a3},{\"B\"}}", "{\"Pattern\"}" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            LayoutEditor.Outcome outcome = editor.addElement("ListField", "ПолеСписка", null, null, BOX, null, null, //$NON-NLS-1$ //$NON-NLS-2$
                BraceTree.parse(pattern));
            assertFalse(pattern, outcome.done);
            assertTrue(pattern, outcome.message.contains("A list field shows a value list")); //$NON-NLS-1$
        }
        assertEquals(before, editor.text());
    }

    /** The event the corpus binds on a list field - ПриАктивизацииСтроки, code 37 - is bound and read back. */
    @Test
    public void aListFieldBindsTheRowActivation() throws Exception
    {
        LayoutEditor editor = listAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor.Outcome bound = editor.bindHandler("ПолеСписка", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
            "ПолеСпискаПриАктивизацииСтроки"); //$NON-NLS-1$
        assertTrue(bound.message, bound.done);
        FormLayout.Element field = LayoutReader.read(BraceTree.parse(editor.text()), "Report") //$NON-NLS-1$
            .elementNamed("ПолеСписка"); //$NON-NLS-1$
        assertEquals("ПриАктивизацииСтроки", field.handlers.get(0).event); //$NON-NLS-1$
    }

    // ---- a text-document field ---------------------------------------------------------------

    /** A text-document field is written on a version-25 form and read back, byte for byte. */
    @Test
    public void aTextDocumentFieldIsWrittenOnVersion25() throws Exception
    {
        textAdded("fixtures/settings-form-v25.form"); //$NON-NLS-1$
    }

    /** A text-document field is written on a version-26 form and read back, byte for byte. */
    @Test
    public void aTextDocumentFieldIsWrittenOnVersion26() throws Exception
    {
        textAdded("fixtures/settings-form-v26.form"); //$NON-NLS-1$
    }

    /** A text-document field is written on a version-27 form and read back, byte for byte. */
    @Test
    public void aTextDocumentFieldIsWrittenOnVersion27() throws Exception
    {
        textAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
    }

    /** The style block of a text-document field carries a tooltip, and it is read back. */
    @Test
    public void aTextDocumentFieldCarriesATooltip() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.addElement("TextDocumentField", "ПолеТекста", null, null, BOX, null, "Подсказка", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        FormLayout.Element field = LayoutReader.read(BraceTree.parse(editor.text()), "Report") //$NON-NLS-1$
            .elementNamed("ПолеТекста"); //$NON-NLS-1$
        assertEquals("Подсказка", field.tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The corpus binds no form attribute and no event on a text-document field; both are refused by name. */
    @Test
    public void aTextDocumentFieldRefusesDataAndEvents() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome withData = editor.addElement("TextDocumentField", "ПолеТекста", null, null, BOX, null, null, //$NON-NLS-1$ //$NON-NLS-2$
            BraceTree.parse(VALUE_LIST));
        assertFalse(withData.done);
        assertTrue(withData.message, withData.message.contains("text document set in code")); //$NON-NLS-1$
        assertTrue(editor.addElement("TextDocumentField", "ПолеТекста", null, null, BOX, null, null, null).done); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome bound = editor.bindHandler("ПолеТекста", "ПриИзменении", "ПолеТекстаПриИзменении"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(bound.done);
        assertTrue(bound.message, bound.message.contains("no event")); //$NON-NLS-1$
    }

    // ---- a chart -----------------------------------------------------------------------------

    /** A chart is written on a version-25 form and read back, byte for byte. */
    @Test
    public void aChartIsWrittenOnVersion25() throws Exception
    {
        chartAdded("fixtures/settings-form-v25.form"); //$NON-NLS-1$
    }

    /** A chart is written on a version-26 form and read back, byte for byte. */
    @Test
    public void aChartIsWrittenOnVersion26() throws Exception
    {
        chartAdded("fixtures/settings-form-v26.form"); //$NON-NLS-1$
    }

    /**
     * A chart is written on a version-27 form and read back, byte for byte. The reader takes the
     * name and the box of a seven-item record from one item further than another kind's.
     */
    @Test
    public void aChartIsWrittenOnVersion27() throws Exception
    {
        chartAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
    }

    /** The corpus binds no form attribute to a chart, so any data is refused by name. */
    @Test
    public void aChartRefusesData() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = editor.text();
        LayoutEditor.Outcome outcome = editor.addElement("Chart", "ДиаграммаНовая", null, null, BOX, null, null, //$NON-NLS-1$ //$NON-NLS-2$
            BraceTree.parse(VALUE_LIST));
        assertFalse(outcome.done);
        assertTrue(outcome.message, outcome.message.contains("series and points of its own record")); //$NON-NLS-1$
        assertEquals(before, editor.text());
    }

    /**
     * The template's own block is the version-25 record, which carries no events list; the
     * refusal names that, and 6 of the 11 charts of versions 25, 26 and 27 carry the list the
     * corpus holds at own[201].
     */
    @Test
    public void theChartTemplateCarriesNoEventsList() throws Exception
    {
        LayoutEditor editor = chartAdded("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor.Outcome bound = editor.bindHandler("ДиаграммаНовая", "ОбработкаРасшифровки", //$NON-NLS-1$ //$NON-NLS-2$
            "ДиаграммаНоваяОбработкаРасшифровки"); //$NON-NLS-1$
        assertFalse(bound.done);
        assertTrue(bound.message, bound.message.contains("no events list")); //$NON-NLS-1$
    }

    // ---- the shared writes -------------------------------------------------------------------

    private static LayoutEditor htmlAdded(String fixture) throws Exception
    {
        return added(fixture, "FieldHtml", "ПолеHTML", null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LayoutEditor listAdded(String fixture) throws Exception
    {
        LayoutEditor editor = added(fixture, "ListField", "ПолеСписка", BraceTree.parse(VALUE_LIST)); //$NON-NLS-1$ //$NON-NLS-2$
        // the tool binds the path after the write; the editor takes the pattern alone. The
        // binding of 13 of the 17 corpus list fields is one segment naming the attribute.
        assertTrue(fixture, editor.bindData(editor.layout().elementNamed("ПолеСписка").id, //$NON-NLS-1$
            List.of(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, "0"))).done); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(editor.text()), "Report"); //$NON-NLS-1$
        layout.resolve(LayoutNames.NONE);
        String data = layout.elementNamed("ПолеСписка").data; //$NON-NLS-1$
        assertNotNull(fixture, data);
        assertTrue(fixture, data.length() > 0);
        return editor;
    }

    private static LayoutEditor textAdded(String fixture) throws Exception
    {
        return added(fixture, "TextDocumentField", "ПолеТекста", null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LayoutEditor chartAdded(String fixture) throws Exception
    {
        return added(fixture, "Chart", "ДиаграммаНовая", null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A chart record holds seven items, so its common list does not sit where every other record
     * keeps one: a page holding a chart alone is read as occupied, and a chart keeps its page when
     * an earlier page goes.
     *
     * @throws Exception when the fixture cannot be read
     */
    @Test
    public void aPageHoldingAChartIsOccupied() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.addElement("Panel", "ПанельГрафиков", null, null, BOX, null, null, null).done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.addPage("ПанельГрафиков", "Вторая", "Вторая").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(editor.addElement("Chart", "ДиаграммаНаВторой", "ПанельГрафиков", "Вторая", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new int[] { 5, 5, 200, 120 }, null, null, null).done);
        LayoutEditor.Outcome refused = editor.removePage("ПанельГрафиков", "Вторая"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refused.message, refused.done);
        assertTrue(refused.message, refused.message.contains("ДиаграммаНаВторой")); //$NON-NLS-1$
        // the first page is empty and goes; the chart moves down to the page that is left
        assertTrue(editor.removePage("ПанельГрафиков", LayoutEditor.BORN_PAGE_NAME).done); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(editor.text()), "Report"); //$NON-NLS-1$
        assertEquals("Вторая", layout.elementNamed("ДиаграммаНаВторой").page); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LayoutEditor added(String fixture, String kind, String name, ListNode data) throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture(fixture);
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome added = editor.addElement(kind, name, null, null, BOX, null, null, data);
        assertTrue(fixture + ": " + added.message, added.done); //$NON-NLS-1$
        String written = editor.text();
        assertEquals(written, BraceTree.serialize(BraceTree.parse(written)));
        FormLayout layout = LayoutReader.read(BraceTree.parse(written), "Report"); //$NON-NLS-1$
        FormLayout.Element element = layout.elementNamed(name);
        assertNotNull(fixture, element);
        assertEquals(kind, element.type);
        assertEquals(name, element.name);
        assertEquals(List.of(BOX[0], BOX[1], BOX[2], BOX[3]), List.of(element.box[0], element.box[1], element.box[2], element.box[3]));
        String again = editor.addElement(kind, name, null, null, BOX, null, null, data).message;
        assertTrue(again, again.contains("already has")); //$NON-NLS-1$
        return editor;
    }
}
