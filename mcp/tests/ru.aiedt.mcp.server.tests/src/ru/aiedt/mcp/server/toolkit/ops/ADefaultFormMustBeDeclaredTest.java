/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * A default form that names a form the object never declares.
 * <p>
 * Measured 31.08: a document kept its {@code defaultObjectForm} after the whole {@code forms} block
 * was gone - EDT had rewritten the .mdo from its own model after the process was killed - and every
 * check passed. The export validator answered no findings on 254 files, revalidation and the
 * project's own errors were clean, and the infobase update died in the platform with "Неизвестный
 * объект метаданных". This is the class of fault the validator exists for: valid in EDT, refused by
 * the infobase.
 * </p>
 */
public class ADefaultFormMustBeDeclaredTest
{
    private static String mdo(String... lines)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:Catalog>\n"
            + String.join("\n", lines) + "\n</mdclass:Catalog>\n";
    }

    private static String declares(String formName)
    {
        return "  <forms uuid=\"93ad0ecb-5275-45b2-afed-4d4abf6b36f2\">\n    <name>" + formName
            + "</name>\n  </forms>";
    }

    @Test
    public void aReferenceWithNoDeclarationIsFound()
    {
        List<String> dangling = ValidateForExportTool.danglingDefaultForms(
            mdo("  <defaultObjectForm>Catalog.Товары.Form.ФормаЭлемента</defaultObjectForm>"),
            "Catalog.Товары");
        assertEquals(Arrays.asList("ФормаЭлемента"), dangling);
    }

    @Test
    public void aDeclaredFormIsNotAFinding()
    {
        assertTrue(ValidateForExportTool.danglingDefaultForms(
            mdo("  <defaultObjectForm>Catalog.Товары.Form.ФормаЭлемента</defaultObjectForm>",
                declares("ФормаЭлемента")),
            "Catalog.Товары").isEmpty());
    }

    /** Every default-form property is judged, not only the object one. */
    @Test
    public void everyKindOfDefaultFormIsJudged()
    {
        List<String> dangling = ValidateForExportTool.danglingDefaultForms(
            mdo("  <defaultObjectForm>Catalog.Товары.Form.Ф1</defaultObjectForm>",
                "  <defaultListForm>Catalog.Товары.Form.Ф2</defaultListForm>",
                "  <defaultChoiceForm>Catalog.Товары.Form.Ф3</defaultChoiceForm>",
                "  <defaultFolderForm>Catalog.Товары.Form.Ф4</defaultFolderForm>",
                declares("Ф2")),
            "Catalog.Товары");
        assertEquals(Arrays.asList("Ф1", "Ф3", "Ф4"), dangling);
    }

    /**
     * A reference reaching another object resolves elsewhere and is not this file's to answer for.
     * Judging it here would refuse a configuration that loads.
     */
    @Test
    public void aFormOfAnotherObjectIsNotJudged()
    {
        assertTrue(ValidateForExportTool.danglingDefaultForms(
            mdo("  <defaultObjectForm>Catalog.Другой.Form.ФормаЭлемента</defaultObjectForm>"),
            "Catalog.Товары").isEmpty());
    }

    /** A common form is named without a Form segment of its own, and belongs to no object here. */
    @Test
    public void aCommonFormIsNotJudged()
    {
        assertTrue(ValidateForExportTool.danglingDefaultForms(
            mdo("  <auxiliaryReportForm>CommonForm.ОбщаяФорма</auxiliaryReportForm>"),
            "Catalog.Товары").isEmpty());
    }

    /** The same missing form named by two properties is one finding, not two. */
    @Test
    public void oneMissingFormIsReportedOnce()
    {
        assertEquals(Arrays.asList("Ф1"), ValidateForExportTool.danglingDefaultForms(
            mdo("  <defaultObjectForm>Catalog.Товары.Form.Ф1</defaultObjectForm>",
                "  <defaultChoiceForm>Catalog.Товары.Form.Ф1</defaultChoiceForm>"),
            "Catalog.Товары"));
    }

    @Test
    public void anObjectWithNoDefaultFormIsNotAFinding()
    {
        assertTrue(ValidateForExportTool.danglingDefaultForms(
            mdo("  <name>Товары</name>", declares("ФормаЭлемента")), "Catalog.Товары").isEmpty());
    }

    @Test
    public void nothingToReadIsNotAFinding()
    {
        assertTrue(ValidateForExportTool.danglingDefaultForms(null, "Catalog.Товары").isEmpty());
        assertTrue(ValidateForExportTool.danglingDefaultForms("", "Catalog.Товары").isEmpty());
        assertTrue(ValidateForExportTool.danglingDefaultForms(
            mdo("  <defaultObjectForm>Catalog.Товары.Form.Ф1</defaultObjectForm>"), null).isEmpty());
    }
}
