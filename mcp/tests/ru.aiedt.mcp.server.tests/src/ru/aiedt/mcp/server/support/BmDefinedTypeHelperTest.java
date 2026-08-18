/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Covers Russian-to-English type name normalisation, and which carrier each half of it
 * is allowed to reach.
 * <p>
 * The shape check behind type resolution is ASCII-only, so a correct Russian name was
 * rejected while an ASCII typo sailed through. Primitives were mapped to fix that; the
 * collection types a form attribute may carry were not, so
 * {@code add_form_attribute type=ДеревоЗначений} produced an attribute with no type at
 * all - and still answered success.
 * </p>
 * <p>
 * The collection half deliberately does NOT live in the shared primitive map. That map
 * feeds every carrier, and a catalog attribute typed ValueTable is invalid metadata;
 * worse, the object path's typo warning inspects the ORIGINAL token, so a Cyrillic name
 * translated in the shared path would be applied without even the warning its English
 * spelling earns. These tests pin the split, both directions.
 * </p>
 */
public class BmDefinedTypeHelperTest
{
    @Test
    public void russianPrimitivesBecomeTheirEnglishNames()
    {
        assertEquals("String", BmDefinedTypeHelper.normalizePrimitiveFqn("Строка")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number", BmDefinedTypeHelper.normalizePrimitiveFqn("Число")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Date", BmDefinedTypeHelper.normalizePrimitiveFqn("Дата")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Boolean", BmDefinedTypeHelper.normalizePrimitiveFqn("Булево")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ValueStorage", //$NON-NLS-1$
            BmDefinedTypeHelper.normalizePrimitiveFqn("ХранилищеЗначения")); //$NON-NLS-1$
    }

    @Test
    public void theSharedPrimitiveMapLeavesCollectionsAlone()
    {
        // The object-attribute path goes through this one. A collection name must come
        // out untouched here, so that a catalog attribute cannot be typed ValueTable by
        // spelling it in Russian.
        assertEquals("ДеревоЗначений", //$NON-NLS-1$
            BmDefinedTypeHelper.normalizePrimitiveFqn("ДеревоЗначений")); //$NON-NLS-1$
        assertEquals("ТаблицаЗначений", //$NON-NLS-1$
            BmDefinedTypeHelper.normalizePrimitiveFqn("ТаблицаЗначений")); //$NON-NLS-1$
    }

    @Test
    public void theFormPathTranslatesTheCollectionTypesAnAttributeMayCarry()
    {
        // These three are what a real configuration actually uses on form attributes:
        // a census of the demo config's forms counted ValueTable 295, ValueList 282 and
        // ValueTree 83. Before this mapping every one of them was rejected in Russian.
        assertEquals(Arrays.asList("ValueTree"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("ДеревоЗначений"))); //$NON-NLS-1$
        assertEquals(Arrays.asList("ValueTable"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("ТаблицаЗначений"))); //$NON-NLS-1$
        assertEquals(Arrays.asList("ValueList"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("СписокЗначений"))); //$NON-NLS-1$
    }

    @Test
    public void collectionsThatAreNotLegalFormAttributeTypesStayUntranslated()
    {
        // Deliberate omission, not an oversight. The same census found Array, Structure,
        // Map and FixedArray ZERO times among form attribute types - the platform does
        // not accept them there. Translating them would only make it easier to write an
        // attribute the platform rejects, because the shape check waves any ASCII word
        // through and the tool then reports the type as applied.
        assertEquals(Arrays.asList("Массив"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("Массив"))); //$NON-NLS-1$
        assertEquals(Arrays.asList("Структура"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("Структура"))); //$NON-NLS-1$
        assertEquals(Arrays.asList("Соответствие"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("Соответствие"))); //$NON-NLS-1$
    }

    @Test
    public void aCompositeKeepsItsShapeAndTranslatesPartByPart()
    {
        // Callers pass a composite comma-joined in one element and the code downstream
        // splits it itself, so the element has to come back joined the same way.
        assertEquals(Arrays.asList("ValueTable,CatalogRef.Валюты"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(
                Arrays.asList("ТаблицаЗначений,CatalogRef.Валюты"))); //$NON-NLS-1$
    }

    @Test
    public void namesAreMatchedWhateverTheCasingOrPadding()
    {
        assertEquals("String", BmDefinedTypeHelper.normalizePrimitiveFqn("СТРОКА")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("ValueTree"), //$NON-NLS-1$
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Arrays.asList("  деревозначений  "))); //$NON-NLS-1$
    }

    @Test
    public void everythingElsePassesThroughUnchanged()
    {
        // Reference and defined types are dotted and must never be touched.
        assertEquals("CatalogRef.Валюты", //$NON-NLS-1$
            BmDefinedTypeHelper.normalizePrimitiveFqn("CatalogRef.Валюты")); //$NON-NLS-1$
        assertEquals("НеизвестныйТип", //$NON-NLS-1$
            BmDefinedTypeHelper.normalizePrimitiveFqn("НеизвестныйТип")); //$NON-NLS-1$
        assertEquals(null, BmDefinedTypeHelper.normalizePrimitiveFqn(null));
        assertEquals(null, BmDefinedTypeHelper.normalizeFormCollectionFqns(null));
        // Nothing to translate: the very same list comes back, no copy.
        List<String> plain = Arrays.asList("String", "CatalogRef.Валюты"); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(plain, BmDefinedTypeHelper.normalizeFormCollectionFqns(plain));
        assertSame(Collections.<String> emptyList(),
            BmDefinedTypeHelper.normalizeFormCollectionFqns(Collections.<String> emptyList()));
    }

    @Test
    public void aCollectionTypeIsStillFlaggedAsNoPrimitive()
    {
        // The object-attribute path warns about a bare ASCII word that is not a known
        // primitive. ValueTable is legal on a FORM attribute but not on an object one,
        // so it must keep warning there - the form aliases must not have quietly landed
        // in the primitive allowlist.
        assertTrue(BmDefinedTypeHelper.isUnrecognizedPrimitive("ValueTable")); //$NON-NLS-1$
        assertTrue(BmDefinedTypeHelper.isUnrecognizedPrimitive("ValueTree")); //$NON-NLS-1$
        assertFalse(BmDefinedTypeHelper.isUnrecognizedPrimitive("String")); //$NON-NLS-1$
        assertFalse(BmDefinedTypeHelper.isUnrecognizedPrimitive("CatalogRef.Валюты")); //$NON-NLS-1$
    }

    @Test
    public void theRegisterDecidesABareNameWhenItCan()
    {
        // A name no hand-written allowlist held, accepted because the platform says so.
        assertTrue(BmDefinedTypeHelper.isAcceptableBareName("StandardPeriod", //$NON-NLS-1$
            PlatformTypeNames.Verdict.KNOWN));
        // And the typo this whole change exists for.
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName("Stirng", //$NON-NLS-1$
            PlatformTypeNames.Verdict.UNKNOWN));
    }

    @Test
    public void theOldPrimitivesSurviveARegisterThatDoesNotListThem()
    {
        // Arbitrary and Null are names the language has and the register need not
        // carry. They were accepted before there was a register to ask, and a verdict
        // of UNKNOWN must not be what takes them away.
        assertTrue(BmDefinedTypeHelper.isAcceptableBareName("Arbitrary", //$NON-NLS-1$
            PlatformTypeNames.Verdict.UNKNOWN));
        assertTrue(BmDefinedTypeHelper.isAcceptableBareName("Null", //$NON-NLS-1$
            PlatformTypeNames.Verdict.UNKNOWN));
        // The floor is a floor, not a bypass: it holds the names it always held.
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName("ValueTable", //$NON-NLS-1$
            PlatformTypeNames.Verdict.UNKNOWN));
    }

    @Test
    public void withNoRegisterToAskTheOldRuleStands()
    {
        // A runtime without the version bundle must keep working exactly as before -
        // typo and all. Rejecting here would refuse every type in the language.
        assertTrue(BmDefinedTypeHelper.isAcceptableBareName("Stirng", //$NON-NLS-1$
            PlatformTypeNames.Verdict.CANNOT_TELL));
        assertTrue(BmDefinedTypeHelper.isAcceptableBareName("StandardPeriod", //$NON-NLS-1$
            PlatformTypeNames.Verdict.CANNOT_TELL));
        // The old rule's own limits are unchanged: lower case was never a type name.
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName("stirng", //$NON-NLS-1$
            PlatformTypeNames.Verdict.CANNOT_TELL));
    }

    @Test
    public void theRegisterDoesNotGetToReopenTheRussianDoor()
    {
        // The platform register answers to Russian type names too, so asking it first
        // made "ДеревоЗначений" acceptable on an OBJECT attribute - where a value tree
        // is not valid metadata, and where the ASCII-only shape rule had been the thing
        // refusing it. Measured on the stand: the unit tests were green and the stand
        // said applied:true.
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName("ДеревоЗначений", //$NON-NLS-1$
            PlatformTypeNames.Verdict.KNOWN));
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName("Строка", //$NON-NLS-1$
            PlatformTypeNames.Verdict.KNOWN));
        // What the form path sends instead, having translated it first, still passes.
        assertTrue(BmDefinedTypeHelper.isAcceptableBareName("ValueTree", //$NON-NLS-1$
            PlatformTypeNames.Verdict.KNOWN));
    }

    @Test
    public void anEmptyNameIsNoName()
    {
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName(null, PlatformTypeNames.Verdict.KNOWN));
        assertFalse(BmDefinedTypeHelper.isAcceptableBareName("", PlatformTypeNames.Verdict.KNOWN)); //$NON-NLS-1$
    }
}
