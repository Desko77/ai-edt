/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmExportHelper;

/**
 * What the object operations read from their arguments before they write.
 * <p>
 * A list property whose array is only partly read, an enumeration target named by the wrong
 * collection, a tabular section named by its documented alias and a predefined item named in
 * another case: each was either written in part or not found at all.
 * </p>
 */
public class AnObjectWriteReadsWhatItWasGivenTest
{
    /** A predefined item as the reflective lookup sees it. */
    public static final class Item
    {
        private final String name;

        /**
         * @param name the stored name
         */
        public Item(String name)
        {
            this.name = name;
        }

        /**
         * @return the stored name
         */
        public String getName()
        {
            return name;
        }
    }

    private static Map<String, String> map(String... pairs)
    {
        Map<String, String> built = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            built.put(pairs[i], pairs[i + 1]);
        }
        return built;
    }

    @Test
    public void aChoiceArrayReadOnlyInPartIsRefused()
    {
        String raw = "[{\"name\":\"A\",\"value\":\"1\"},\"garbage\",{\"name\":\"B\",\"value\":\"2\"}]"; //$NON-NLS-1$
        int parsed = EditMetadataTool.parseStructArray(raw).size();

        String refusal = ObjectOps.partialStructArrayRefusal("choiceParameters", raw, parsed); //$NON-NLS-1$

        assertNotNull("a prefix of the array is not written as the whole of it", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("3 elements")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("first " + parsed)); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
    }

    @Test
    public void aChoiceArrayWhoseFirstElementIsNotAnObjectIsRefusedByCount()
    {
        String raw = "[\"garbage\",{\"name\":\"A\",\"value\":\"1\"}]"; //$NON-NLS-1$
        int parsed = EditMetadataTool.parseStructArray(raw).size();

        String refusal = ObjectOps.partialStructArrayRefusal("choiceParameters", raw, parsed); //$NON-NLS-1$

        assertEquals(0, parsed);
        assertNotNull("an array of two is not refused as if it were no array", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("2 elements")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("none of them")); //$NON-NLS-1$
    }

    @Test
    public void theExportFailureCarriesTheExportsOwnError()
    {
        BmExportHelper.Result failed = new BmExportHelper.Result();
        failed.error = "IDtProject not resolved"; //$NON-NLS-1$

        assertTrue(ObjectOps.exportFailureText(failed).endsWith(": IDtProject not resolved")); //$NON-NLS-1$
        assertEquals("the export returned no result", ObjectOps.exportFailureText(null)); //$NON-NLS-1$
        assertEquals("the export did not confirm the form", //$NON-NLS-1$
            ObjectOps.exportFailureText(new BmExportHelper.Result()));
    }

    @Test
    public void theFormFallbackSaysWhatReachedTheDisk()
    {
        String form = "CommonForm.F.Form"; //$NON-NLS-1$

        Map.Entry<String, String> written = ObjectOps.formFallbackOutcome(form, "x", false, null); //$NON-NLS-1$
        Map.Entry<String, String> kept = ObjectOps.formFallbackOutcome(form, "x", true, null); //$NON-NLS-1$
        Map.Entry<String, String> failed = ObjectOps.formFallbackOutcome(form, "x", false, "disk full"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("emptyFormStubWritten", written.getKey()); //$NON-NLS-1$
        assertEquals("an existing Form.form is not reported as an empty stub", //$NON-NLS-1$
            "formExportUnconfirmed", kept.getKey()); //$NON-NLS-1$
        assertEquals("a failed write is not reported as a written stub", //$NON-NLS-1$
            "formNotWritten", failed.getKey()); //$NON-NLS-1$
        assertTrue(failed.getValue(), failed.getValue().contains("disk full")); //$NON-NLS-1$
    }

    @Test
    public void aChoiceArrayReadWhollyPasses()
    {
        String raw = "[{\"name\":\"A\",\"value\":\"1\"},{\"name\":\"B\",\"value\":\"2\"}]"; //$NON-NLS-1$
        int parsed = EditMetadataTool.parseStructArray(raw).size();

        assertEquals(2, parsed);
        assertNull(ObjectOps.partialStructArrayRefusal("choiceParameters", raw, parsed)); //$NON-NLS-1$
    }

    @Test
    public void anEnumReferenceNamesTheEnumObject()
    {
        assertEquals("Enum.Colors", ObjectOps.refFqnForSegment("EnumRef.Colors")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Items", ObjectOps.refFqnForSegment("CatalogRef.Items")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theTabularSectionAliasIsReadWhenTheLongFormIsAbsent()
    {
        assertEquals("Goods", ObjectOps.tabularSectionNameOf(map("tabularSection", "Goods"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Goods", ObjectOps.tabularSectionNameOf( //$NON-NLS-1$
            map("tabularSectionName", "Goods", "tabularSection", "Other"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(ObjectOps.tabularSectionNameOf(map("name", "Price"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aPredefinedItemNamedInAnotherCaseIsTheSameItem()
    {
        List<Item> items = List.of(new Item("Основной"), new Item("Резервный")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Основной", PredefinedOps.existingItemName(items, "ОСНОВНОЙ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Резервный", PredefinedOps.existingItemName(items, "Резервный")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(PredefinedOps.existingItemName(items, "Запасной")); //$NON-NLS-1$
    }

    @Test
    public void aSkippedPredefinedItemIsReportedUnderItsStoredName()
    {
        List<Item> items = List.of(new Item("Основной")); //$NON-NLS-1$

        Map<String, Object> tag = PredefinedOps.idempotentSkipTag("Основной", "ОСНОВНОЙ"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Основной", tag.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ОСНОВНОЙ", tag.get("requestedName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(PredefinedOps.idempotentSkipTag("Основной", "Основной").get("requestedName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("a later lookup finds the item under either case", //$NON-NLS-1$
            PredefinedOps.itemNamed(items, "основной") == items.get(0)); //$NON-NLS-1$
    }
}
