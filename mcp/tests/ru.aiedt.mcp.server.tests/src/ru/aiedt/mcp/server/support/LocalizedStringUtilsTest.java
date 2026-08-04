/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Covers reading a synonym out of whatever shape the model happens to hand over.
 * <p>
 * There are three, and the awkward one is the reason this helper exists: an EMF EMap is not a
 * {@code java.util.Map} but an {@code EList} of entry objects, so the obvious {@code get("ru")}
 * returns the entry wrapper rather than the text. The doubles below reproduce all three shapes
 * structurally - an entry type with {@code getKey}/{@code getValue}, a wrapper with
 * {@code getContent} - which is exactly what the helper reaches for reflectively, so the tests
 * exercise the real lookup without an EDT model behind them.
 * </p>
 */
public class LocalizedStringUtilsTest
{
    /** Stands in for an EMF map entry: read by name, never by interface. */
    public static final class Entry
    {
        private final String key;
        private final String value;

        Entry(String key, String value)
        {
            this.key = key;
            this.value = value;
        }

        public String getKey()
        {
            return key;
        }

        public String getValue()
        {
            return value;
        }
    }

    /** Stands in for a LocalString, which keeps its entries one level down. */
    public static final class Wrapper
    {
        private final Object content;

        Wrapper(Object content)
        {
            this.content = content;
        }

        public Object getContent()
        {
            return content;
        }
    }

    private static List<Entry> entries(String... pairs)
    {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            list.add(new Entry(pairs[i], pairs[i + 1]));
        }
        return list;
    }

    @Test
    public void nothingYieldsNothing()
    {
        assertNull(LocalizedStringUtils.text(null));
    }

    @Test
    public void aPlainMapIsReadByLanguage()
    {
        Map<String, String> synonym = new LinkedHashMap<>();
        synonym.put("en", "Products"); //$NON-NLS-1$ //$NON-NLS-2$
        synonym.put("ru", "Товары"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Товары", LocalizedStringUtils.text(synonym)); //$NON-NLS-1$
        assertEquals("Products", LocalizedStringUtils.text(synonym, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEntryListIsWalkedRatherThanIndexed()
    {
        // The EMF EMap case: entries are objects, and the value has to come off getValue().
        assertEquals("Товары", LocalizedStringUtils.text(entries("en", "Products", "ru", "Товары"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void aWrapperIsUnpackedFirst()
    {
        assertEquals("Товары", //$NON-NLS-1$
            LocalizedStringUtils.text(new Wrapper(entries("ru", "Товары")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anotherLanguageIsBetterThanNothing()
    {
        // A synonym written only in English still tells the reader what the object is called.
        assertEquals("Products", LocalizedStringUtils.text(entries("en", "Products"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void thePreferredLanguageWinsOverTheFirstEntry()
    {
        assertEquals("Products", //$NON-NLS-1$
            LocalizedStringUtils.text(entries("ru", "Товары", "en", "Products"), "en")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void anEmptySynonymYieldsNothing()
    {
        assertNull(LocalizedStringUtils.text(entries()));
        assertNull(LocalizedStringUtils.text(new LinkedHashMap<String, String>()));
    }

    @Test
    public void anObjectThatIsNoneOfTheThreeShapesYieldsNothing()
    {
        // Reflection is a guess about the object's shape; guessing wrong must return nothing rather
        // than throw into the tool that asked.
        assertNull(LocalizedStringUtils.text(new Object()));
    }
}
