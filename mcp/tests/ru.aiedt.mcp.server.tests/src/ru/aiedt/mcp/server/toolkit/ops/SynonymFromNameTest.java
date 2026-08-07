/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Covers the synonym a new metadata object is given when the caller does not supply one.
 * <p>
 * An attribute with no synonym is not a cosmetic gap: the technical name is what a user then reads
 * on a form, in a report and in a choice list. The editor never leaves it blank - it splits the
 * name into words - and neither does a reference configuration, where every one of the 2795
 * attributes, 561 resources, 433 dimensions and 437 enum values carries one. These tests pin the
 * splitting, since it has to work on Cyrillic names, which is what the configurations this plugin
 * edits are written in.
 * </p>
 */
public class SynonymFromNameTest
{
    @Test
    public void aCyrillicCompoundNameBecomesWords()
    {
        assertEquals("Валюта документа", //$NON-NLS-1$
            EditMetadataTool.generateSynonymFromName("ВалютаДокумента")); //$NON-NLS-1$
        assertEquals("Дата начала периода действия", //$NON-NLS-1$
            EditMetadataTool.generateSynonymFromName("ДатаНачалаПериодаДействия")); //$NON-NLS-1$
    }

    @Test
    public void aLatinCompoundNameBecomesWords()
    {
        assertEquals("Document currency", //$NON-NLS-1$
            EditMetadataTool.generateSynonymFromName("DocumentCurrency")); //$NON-NLS-1$
    }

    @Test
    public void aSingleWordIsLeftAsItIs()
    {
        assertEquals("Организация", EditMetadataTool.generateSynonymFromName("Организация")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Amount", EditMetadataTool.generateSynonymFromName("Amount")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anAcronymKeepsItsCapitals()
    {
        // Splitting inside an acronym would produce "Сумма Р К С Н общая" - the run of capitals is
        // one word, and only the word that follows it starts a new one.
        assertEquals("Сумма РКСН общая", //$NON-NLS-1$
            EditMetadataTool.generateSynonymFromName("СуммаРКСНОбщая")); //$NON-NLS-1$
    }

    @Test
    public void nothingIsInventedForAnEmptyName()
    {
        assertEquals("", EditMetadataTool.generateSynonymFromName("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, EditMetadataTool.generateSynonymFromName(null));
    }
}
