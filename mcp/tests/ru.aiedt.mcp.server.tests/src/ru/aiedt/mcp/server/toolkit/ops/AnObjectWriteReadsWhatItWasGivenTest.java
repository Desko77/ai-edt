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
}
