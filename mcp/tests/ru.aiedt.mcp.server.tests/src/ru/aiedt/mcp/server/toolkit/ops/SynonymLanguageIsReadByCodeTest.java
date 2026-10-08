/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

import ru.aiedt.mcp.server.support.DefaultLanguage;

/**
 * The synonym of an object with several languages is read by language CODE.
 * <p>
 * The synonym map is keyed by the code ({@code ru}, {@code en}), but a configuration's default
 * language object carries a NAME ({@code Русский}), and a caller may pass either form. Reading by
 * name missed the entry and answered whichever synonym happened to sit first in the map, so both
 * readers resolve the request to a code before any lookup.
 * </p>
 */
public class SynonymLanguageIsReadByCodeTest
{
    private static Configuration configuration()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Language russian = MdClassFactory.eINSTANCE.createLanguage();
        russian.setName("Русский"); //$NON-NLS-1$
        russian.setLanguageCode("ru"); //$NON-NLS-1$
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        configuration.getLanguages().add(russian);
        configuration.getLanguages().add(english);
        configuration.setDefaultLanguage(russian);
        return configuration;
    }

    private static Catalog catalogWithTwoSynonyms()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Items"); //$NON-NLS-1$
        // English first on purpose: a lookup that misses its language falls back to the first
        // non-empty entry, so with "ru" first a name-based miss would accidentally answer right.
        catalog.getSynonym().put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        catalog.getSynonym().put("ru", "Номенклатура"); //$NON-NLS-1$ //$NON-NLS-2$
        return catalog;
    }

    /** An unset request answers the default language's code, not its name. */
    @Test
    public void theDefaultLanguageComesBackAsACode()
    {
        assertEquals("ru", MetadataObjectsReader.effectiveLanguage(null, configuration())); //$NON-NLS-1$
        assertEquals("ru", MetadataDetailsReader.effectiveLanguage(null, configuration())); //$NON-NLS-1$
        assertEquals("ru", DefaultLanguage.codeOfDefault(configuration())); //$NON-NLS-1$
    }

    /** A request naming the language by name is answered by that language's code. */
    @Test
    public void aLanguageNameIsTranslatedToItsCode()
    {
        assertEquals("ru", MetadataObjectsReader.effectiveLanguage("Русский", configuration())); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("en", MetadataDetailsReader.effectiveLanguage("English", configuration())); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ru", DefaultLanguage.resolve("Русский", configuration())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A request that is already a code comes back unchanged, either case. */
    @Test
    public void aCodeComesBackAsTheCanonicalCode()
    {
        assertEquals("en", DefaultLanguage.resolve("en", configuration())); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ru", DefaultLanguage.resolve("RU", configuration())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A value no language of the configuration answers to is left as it came. */
    @Test
    public void anUnknownValuePassesThrough()
    {
        assertEquals("kz", DefaultLanguage.resolve("kz", configuration())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Without a configuration there is nothing to translate against, so the value stands. */
    @Test
    public void noConfigurationLeavesTheRequestAlone()
    {
        assertEquals("ru", DefaultLanguage.resolve("ru", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ru", DefaultLanguage.codeOfDefault(null)); //$NON-NLS-1$
    }

    /** The synonym read itself picks the entry under the code asked for. */
    @Test
    public void aMultiLanguageSynonymIsReadByCode()
    {
        Catalog catalog = catalogWithTwoSynonyms();
        assertEquals("Номенклатура", MetadataObjectsReader.synonymForLanguage(catalog, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Goods", MetadataObjectsReader.synonymForLanguage(catalog, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The full path - a name handed to a reader - reaches the entry the code keys. */
    @Test
    public void aNameHandedToAReaderReadsTheCodeEntry()
    {
        Catalog catalog = catalogWithTwoSynonyms();
        String code = MetadataObjectsReader.effectiveLanguage("Русский", configuration()); //$NON-NLS-1$
        assertEquals("Номенклатура", MetadataObjectsReader.synonymForLanguage(catalog, code)); //$NON-NLS-1$
    }
}
