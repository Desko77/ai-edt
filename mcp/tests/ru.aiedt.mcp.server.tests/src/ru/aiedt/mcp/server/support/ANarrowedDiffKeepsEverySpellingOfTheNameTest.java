/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Narrowing a diff to one object keeps its entries, whichever spelling the caller used.
 * <p>
 * The diff names its entries after the metadata model's collections - {@code Catalogs.Goods} - while
 * a caller names the type the way the catalogue spells it - {@code Catalog.Goods} - and a Russian
 * configuration is authored under {@code Справочник}. Comparing the two as text dropped every entry
 * for the object that was actually asked for, and the answer read as an object with nothing under
 * it: the caller narrowing to one catalogue was told that catalogue had no changes.
 * </p>
 */
public class ANarrowedDiffKeepsEverySpellingOfTheNameTest
{
    private static final String GOODS = "Catalogs.Goods"; //$NON-NLS-1$

    private static final String GOODS_ATTRIBUTE = "Catalogs.Goods.Attribute.Article"; //$NON-NLS-1$

    private static final String OTHER = "Catalogs.Other"; //$NON-NLS-1$

    private static MetadataDiffEngine.DiffResult diffOf(String... names)
    {
        MetadataDiffEngine.DiffResult result = new MetadataDiffEngine.DiffResult();
        for (String name : names)
        {
            result.added.add(name);
        }
        return result;
    }

    private static void assertKept(MetadataDiffEngine.DiffResult result, String... kept)
    {
        assertEquals("the narrowed diff names exactly the object it kept to", List.of(kept), //$NON-NLS-1$
            result.added);
    }

    /** The plural spelling the model uses is kept when the caller names the singular. */
    @Test
    public void theSingularNameKeepsThePluralEntry()
    {
        MetadataDiffEngine.DiffResult result = diffOf(GOODS, OTHER);

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertKept(result, GOODS);
    }

    /** The plural spelling the caller used matches the entry it names. */
    @Test
    public void thePluralNameKeepsThePluralEntry()
    {
        MetadataDiffEngine.DiffResult result = diffOf(GOODS, OTHER);

        result.retainOnly("Catalogs.Goods"); //$NON-NLS-1$

        assertKept(result, GOODS);
    }

    /** A Russian configuration is authored under Справочник, and that spelling is understood. */
    @Test
    public void theRussianNameKeepsTheEnglishEntry()
    {
        MetadataDiffEngine.DiffResult result = diffOf(GOODS, OTHER);

        result.retainOnly("Справочник.Goods"); //$NON-NLS-1$

        assertKept(result, GOODS);
    }

    /** An entry under the object - an attribute, a form, a tabular section - is kept with it. */
    @Test
    public void entriesUnderTheObjectAreKept()
    {
        MetadataDiffEngine.DiffResult result = diffOf(GOODS, GOODS_ATTRIBUTE, OTHER);

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertKept(result, GOODS, GOODS_ATTRIBUTE);
    }

    /** An object whose name merely starts the same is not the object. */
    @Test
    public void aNameThatOnlyStartsWithTheObjectIsNotKept()
    {
        MetadataDiffEngine.DiffResult result = diffOf(GOODS, "Catalogs.GoodsArchive"); //$NON-NLS-1$

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertKept(result, GOODS);
    }

    /** A removed entry is judged the same way the added one is. */
    @Test
    public void removedEntriesAreNarrowedToo()
    {
        MetadataDiffEngine.DiffResult result = diffOf();
        result.removed.add(GOODS);
        result.removed.add(OTHER);

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertEquals(List.of(GOODS), result.removed);
    }

    /** A modified entry is judged on its own name, not on the spelling the caller used. */
    @Test
    public void modifiedEntriesAreNarrowedToo()
    {
        MetadataDiffEngine.DiffResult result = diffOf();
        result.modified.add(entry(GOODS));
        result.modified.add(entry(OTHER));

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertEquals(1, result.modified.size());
        assertEquals(GOODS, result.modified.get(0).get("fqn")); //$NON-NLS-1$
    }

    /** A rename is kept when either end names the object: the pair is the object's own change. */
    @Test
    public void aRenameIsKeptWhenEitherHalfNamesTheObject()
    {
        MetadataDiffEngine.DiffResult result = diffOf();
        result.renamed.add(rename(GOODS, "Catalogs.GoodsRenamed")); //$NON-NLS-1$
        result.renamed.add(rename(OTHER, "Catalogs.OtherRenamed")); //$NON-NLS-1$

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertEquals(1, result.renamed.size());
        assertEquals(GOODS, result.renamed.get(0).get("from")); //$NON-NLS-1$
    }

    /** A name with no type this catalogue knows is compared as it stands. */
    @Test
    public void aNameWithNoKnownTypeIsComparedAsItStands()
    {
        MetadataDiffEngine.DiffResult result = diffOf("Configuration.Root");

        result.retainOnly("Configuration.Root"); //$NON-NLS-1$

        assertKept(result, "Configuration.Root"); //$NON-NLS-1$
    }

    /** A name that is null keeps the entries: there is nothing to narrow to. */
    @Test
    public void aNullNameKeepsEverything()
    {
        MetadataDiffEngine.DiffResult result = diffOf(GOODS, OTHER);

        result.retainOnly(null);

        assertKept(result, GOODS, OTHER);
    }

    /** A modified entry carries a name of its own; the narrowing reads it from there. */
    @Test
    public void aModifiedEntryWithoutANameIsNotKept()
    {
        MetadataDiffEngine.DiffResult result = diffOf();
        result.modified.add(new LinkedHashMap<>());

        result.retainOnly("Catalog.Goods"); //$NON-NLS-1$

        assertTrue("an entry naming nothing is nobody's object", result.modified.isEmpty()); //$NON-NLS-1$
    }

    private static Map<String, Object> entry(String fqn)
    {
        Map<String, Object> modified = new LinkedHashMap<>();
        modified.put("fqn", fqn); //$NON-NLS-1$
        return modified;
    }

    private static Map<String, Object> rename(String from, String to)
    {
        Map<String, Object> renamed = new LinkedHashMap<>();
        renamed.put("from", from); //$NON-NLS-1$
        renamed.put("to", to); //$NON-NLS-1$
        return renamed;
    }
}
