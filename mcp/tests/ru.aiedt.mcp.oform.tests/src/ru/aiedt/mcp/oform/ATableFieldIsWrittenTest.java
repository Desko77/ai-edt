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

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * A spreadsheet-document field is the record the corpus shares: the reader names the kind and the
 * document it carries, the write round-trips on versions 25, 26 and 27, a second element of the
 * same name is refused, and data of a type the corpus does not bind is refused by name.
 */
public class ATableFieldIsWrittenTest
{
    private static final int[] BOX = { 10, 200, 400, 400 };

    /** The field is written on a version-25 form and read back, byte for byte. */
    @Test
    public void aSpreadsheetFieldIsWrittenOnVersion25() throws Exception
    {
        added("fixtures/settings-form-v25.form"); //$NON-NLS-1$
    }

    /** The field is written on a version-26 form and read back, byte for byte. */
    @Test
    public void aSpreadsheetFieldIsWrittenOnVersion26() throws Exception
    {
        added("fixtures/settings-form-v26.form"); //$NON-NLS-1$
    }

    /** The field is written on a version-27 form and read back, byte for byte. */
    @Test
    public void aSpreadsheetFieldIsWrittenOnVersion27() throws Exception
    {
        added("fixtures/processing-form-v27.form"); //$NON-NLS-1$
    }

    /**
     * A tooltip is refused rather than dropped: the record the corpus writes for a spreadsheet
     * document field has no slot for one, and the caller is told instead of being answered success.
     */
    @Test
    public void aTooltipIsRefusedBecauseTheRecordHasNoSlot() throws Exception
    {
        LayoutEditor editor = added("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        String written = editor.text();
        LayoutEditor.Outcome asked = editor.addElement("TableField", "ПолеСПодсказкой", null, null, BOX, null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Подсказка", null); //$NON-NLS-1$
        assertFalse(asked.done);
        assertTrue(asked.message, asked.message.contains("no slot")); //$NON-NLS-1$
        assertEquals(written, editor.text());
    }

    /** A second field of the same name is refused, and the form is left as the first write made it. */
    @Test
    public void theNameInUseIsRefused() throws Exception
    {
        LayoutEditor editor = added("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        String written = editor.text();
        LayoutEditor.Outcome again = editor.addElement("TableField", "ПолеДокумента", null, null, BOX, null, null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(again.done);
        assertTrue(again.message, again.message.contains("already has")); //$NON-NLS-1$
        assertEquals(written, editor.text());
    }

    /** Data of a type the corpus does not bind is refused, and the form is not changed. */
    @Test
    public void dataOutsideTheMeasuredTypeIsRefused() throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = editor.text();
        refused(editor, "{\"Pattern\",{\"S\",10}}", "String(10)"); //$NON-NLS-1$ //$NON-NLS-2$
        refused(editor, "{\"Pattern\",{\"N\",10,2}}", "Number(10,2)"); //$NON-NLS-1$ //$NON-NLS-2$
        refused(editor, "{\"Pattern\",{\"#\",acf6192e-81ca-46ef-93a6-5a6968b78663}}", "ValueTable"); //$NON-NLS-1$ //$NON-NLS-2$
        refused(editor, "{\"Pattern\"}", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before, editor.text());
        assertNull(editor.layout().elementNamed("ПолеДокумента")); //$NON-NLS-1$
    }

    /** The event the corpus binds most often is written into the events list and read back. */
    @Test
    public void theDecryptionHandlerIsBound() throws Exception
    {
        LayoutEditor editor = added("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor.Outcome bound = editor.bindHandler("ПолеДокумента", "ОбработкаРасшифровки", //$NON-NLS-1$ //$NON-NLS-2$
            "ПолеДокументаОбработкаРасшифровки"); //$NON-NLS-1$
        assertTrue(bound.message, bound.done);
        String written = editor.text();
        assertEquals(written, BraceTree.serialize(BraceTree.parse(written)));
        FormLayout.Element field = LayoutReader.read(BraceTree.parse(written), "Report").elementNamed("ПолеДокумента"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, field.handlers.size());
        assertEquals("ОбработкаРасшифровки", field.handlers.get(0).event); //$NON-NLS-1$
        assertEquals("ПолеДокументаОбработкаРасшифровки", field.handlers.get(0).procedure); //$NON-NLS-1$
    }

    private static LayoutEditor added(String fixture) throws Exception
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture(fixture);
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome added = editor.addElement("TableField", "ПолеДокумента", null, null, BOX, null, null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(fixture + ": " + added.message, added.done); //$NON-NLS-1$
        String written = editor.text();
        assertEquals(written, BraceTree.serialize(BraceTree.parse(written)));
        assertTrue(written.contains("236a17b3-7f44-46d9-a907-75f9cdc61ab5")); //$NON-NLS-1$
        // [2] opens 16 and repeats the box, the copy 138 of 142 corpus fields carry
        assertTrue(fixture, written.contains("{16,10,200,400,400,5,5,1,1,")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(written), "Report"); //$NON-NLS-1$
        FormLayout.Element field = layout.elementNamed("ПолеДокумента"); //$NON-NLS-1$
        assertNotNull(fixture, field);
        assertEquals("TableField", field.type); //$NON-NLS-1$
        assertEquals("SpreadsheetDocument", field.valueType); //$NON-NLS-1$
        assertNull(field.data);
        assertEquals(List.of(BOX[0], BOX[1], BOX[2], BOX[3]), List.of(field.box[0], field.box[1], field.box[2], field.box[3]));
        String map = layout.toMap().toString();
        assertTrue(map, map.contains("TableField") && map.contains("SpreadsheetDocument")); //$NON-NLS-1$ //$NON-NLS-2$
        return editor;
    }

    private static void refused(LayoutEditor editor, String pattern, String type)
    {
        LayoutEditor.Outcome outcome = editor.addElement("TableField", "ПолеДокумента", null, null, BOX, null, null, //$NON-NLS-1$ //$NON-NLS-2$
            BraceTree.parse(pattern));
        assertFalse(outcome.message, outcome.done);
        assertTrue(outcome.message, outcome.message.contains("spreadsheet document")); //$NON-NLS-1$
        assertTrue(outcome.message, outcome.message.contains(type));
    }
}
