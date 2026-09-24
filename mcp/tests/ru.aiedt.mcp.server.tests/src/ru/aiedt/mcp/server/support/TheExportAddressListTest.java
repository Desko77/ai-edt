/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.AddressRefusal;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ModelResolver;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ParsedAddress;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.Resolved;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.Validation;

/**
 * The addresses an export accepts, and the list file it renders them into: the shapes, the kind
 * translation (the catalog's own tables, not a second one), the refusals before any Designer
 * starts, and the collapse of repeats.
 */
public class TheExportAddressListTest
{
    /**
     * The accepted shapes: a top object in either language, a form in either language, a common
     * form.
     */
    @Test
    public void theAcceptedShapesParseIntoTheirCanonicalParts()
    {
        ParsedAddress top = parse("Catalog.Банки"); //$NON-NLS-1$
        assertEquals(ParsedAddress.Shape.TOP, top.shape);
        assertEquals("Catalog", top.typeEnglish); //$NON-NLS-1$

        ParsedAddress russianTop = parse("Справочник.Банки"); //$NON-NLS-1$
        assertEquals(ParsedAddress.Shape.TOP, russianTop.shape);
        assertEquals("Catalog", russianTop.typeEnglish); //$NON-NLS-1$

        ParsedAddress form = parse("Catalog.Банки.Form.ФормаЭлемента"); //$NON-NLS-1$
        assertEquals(ParsedAddress.Shape.FORM, form.shape);
        assertEquals("ФормаЭлемента", form.formName); //$NON-NLS-1$

        ParsedAddress russianForm = parse("Справочник.Банки.Форма.ФормаЭлемента"); //$NON-NLS-1$
        assertEquals(ParsedAddress.Shape.FORM, russianForm.shape);
        assertEquals("Catalog", russianForm.typeEnglish); //$NON-NLS-1$

        ParsedAddress common = parse("ОбщаяФорма.Панель"); //$NON-NLS-1$
        assertEquals(ParsedAddress.Shape.TOP, common.shape);
        assertEquals("CommonForm", common.typeEnglish); //$NON-NLS-1$
    }

    /**
     * A child kind other than a form is refused with the supported kinds named; so is an unknown
     * type and a shape with the wrong number of segments.
     */
    @Test
    public void unsupportedKindsAndUnknownTypesAreRefusedWithTheReason()
    {
        AddressRefusal[] refusal = new AddressRefusal[1];

        assertNull(parseInto("Catalog.Банки.Attribute.Код", refusal)); //$NON-NLS-1$
        assertTrue(refusal[0].reason.contains("not a supported child kind")); //$NON-NLS-1$

        assertNull(parseInto("Catalog.Банки.Реквизит.Код", refusal)); //$NON-NLS-1$
        assertTrue(refusal[0].reason.contains("not a supported child kind")); //$NON-NLS-1$

        assertNull(parseInto("Нечто.Банки", refusal)); //$NON-NLS-1$
        assertTrue(refusal[0].reason.contains("is not a metadata type")); //$NON-NLS-1$

        assertNull(parseInto("Catalog", refusal)); //$NON-NLS-1$
        assertNull(parseInto("Catalog.Банки.Form", refusal)); //$NON-NLS-1$
        assertNull(parseInto("Catalog.Банки.Form.Ф.Лишнее", refusal)); //$NON-NLS-1$
    }

    /**
     * The validation refuses an empty list, an address the model does not have, and a model that
     * is not there at all - all before any Designer starts.
     */
    @Test
    public void theValidationRefusesEmptyAndUnknownAddresses()
    {
        Validation empty = InfobaseObjectsExporter.validate(null, knowingNothing());
        assertEquals(1, empty.refusals.size());
        assertTrue(empty.refusals.get(0).reason.contains("objects list is empty")); //$NON-NLS-1$

        Validation unknown = InfobaseObjectsExporter.validate(Arrays.asList("Catalog.НетТакого"), //$NON-NLS-1$
            known("Справочник.Банки", null));
        assertEquals(1, unknown.refusals.size());
        assertEquals("Catalog.НетТакого", unknown.refusals.get(0).address); //$NON-NLS-1$
        assertTrue(unknown.refusals.get(0).reason.contains("no Catalog named")); //$NON-NLS-1$
    }

    /**
     * Two spellings of one object collapse onto one list line: the line is built from the model's
     * authored names, so the file carries it once and the answer maps each spelling to it.
     */
    @Test
    public void repeatsCollapseOntoOneListLine()
    {
        Validation validation = InfobaseObjectsExporter.validate(
            Arrays.asList("Catalog.Банки", "Справочник.Банки", "catalog.банки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Catalog.Банки.Form.ФормаЭлемента", "Справочник.Банки.Форма.ФормаЭлемента"), //$NON-NLS-1$ //$NON-NLS-2$
            known("Справочник.Банки", "ФормаЭлемента"));

        assertEquals(0, validation.refusals.size());
        Map<String, String> distinct = new LinkedHashMap<>();
        validation.lines.forEach((address, line) -> distinct.put(line, address));
        assertEquals("repeats collapse: the file carries the catalog once and the form once", 2, //$NON-NLS-1$
            distinct.size());
        assertTrue(validation.lines.containsValue("Справочник.Банки")); //$NON-NLS-1$
        assertTrue(validation.lines.containsValue("Справочник.Банки.Форма.ФормаЭлемента")); //$NON-NLS-1$
    }

    /**
     * The Russian kind name comes from the same child-kind table the address walk uses, with the
     * capitalization 1C writes in full names. A kind the table has no Russian spelling for comes
     * back unchanged, the contract the caller relies on for kinds this table never translated.
     */
    @Test
    public void theFormKindCarriesTheRussianNameThePlatformWrites()
    {
        assertEquals("Форма", BmObjectHelper.russianChildKindName("Form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("NoSuchKind", //$NON-NLS-1$
            BmObjectHelper.russianChildKindName("NoSuchKind")); //$NON-NLS-1$
    }

    /**
     * The list file's bytes: a UTF-8 BOM, then one name per line, newline-terminated.
     */
    @Test
    public void theListFileBytesCarryABomAndOneNamePerLine()
    {
        byte[] bytes = InfobaseObjectsExporter.listFileBytes(
            Arrays.asList("Справочник.Банки", "ОбщаяФорма.Панель")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0xEF, bytes[0] & 0xFF);
        assertEquals(0xBB, bytes[1] & 0xFF);
        assertEquals(0xBF, bytes[2] & 0xFF);
        assertEquals("Справочник.Банки\nОбщаяФорма.Панель\n", //$NON-NLS-1$
            new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }

    private static ParsedAddress parse(String address)
    {
        return InfobaseObjectsExporter.parseAddress(address, new AddressRefusal[1]);
    }

    private static ParsedAddress parseInto(String address, AddressRefusal[] refusal)
    {
        return InfobaseObjectsExporter.parseAddress(address, refusal);
    }

    /** A resolver that knows nothing; every address resolves to a refusal. */
    private static ModelResolver knowingNothing()
    {
        return address -> new Resolved(null, null, null, "nothing is known to this resolver"); //$NON-NLS-1$
    }

    /**
     * A resolver that knows one catalog by the name Банки, its form, and nothing else. The name is
     * compared the way the production resolver's model lookup compares it
     * ({@code MetadataTypeCatalog.findObject}), case-insensitively - a lower-case spelling of an
     * object that exists is the same object, not an unknown one.
     */
    private static ModelResolver known(String russianLine, String formName)
    {
        return address -> {
            boolean form = address.shape == ParsedAddress.Shape.FORM;
            if (form && formName == null)
            {
                return new Resolved(null, null, null, "no form in this resolver"); //$NON-NLS-1$
            }
            if (!address.objectName.equalsIgnoreCase("Банки")) //$NON-NLS-1$
            {
                return new Resolved(null, null, null,
                    "no " + address.typeEnglish + " named '" + address.objectName //$NON-NLS-1$ //$NON-NLS-2$
                        + "' in the project's configuration"); //$NON-NLS-1$
            }
            String line = form ? russianLine + ".Форма." + formName : russianLine; //$NON-NLS-1$
            return new Resolved(line, "Банки", form ? formName : null, null); //$NON-NLS-1$
        };
    }
}
