/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers reading an FQN as a request for the configuration root.
 * <p>
 * The root is an MdObject like any other but belongs to no collection, so the Type.Name
 * lookup could never find it and {@code get_metadata_details} answered "no such object"
 * for the one object every project certainly has. Anyone wanting to ask the model about
 * the configuration itself had to read the .mdo by hand instead.
 * </p>
 */
public class MetadataDetailsRootTest
{
    @Test
    public void theBareTypeAddressesTheRootWithoutNamingIt()
    {
        assertEquals("", MetadataDetailsReader.rootRequestName("Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", MetadataDetailsReader.rootRequestName("Конфигурация")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aNamedRootYieldsTheName()
    {
        assertEquals("УправлениеПредприятием", //$NON-NLS-1$
            MetadataDetailsReader.rootRequestName("Configuration.УправлениеПредприятием")); //$NON-NLS-1$
        assertEquals("Демо", MetadataDetailsReader.rootRequestName("Конфигурация.Демо")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void casingAndPaddingDoNotMatter()
    {
        assertEquals("", MetadataDetailsReader.rootRequestName("  configuration  ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Демо", MetadataDetailsReader.rootRequestName("CONFIGURATION. Демо ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void everyOtherObjectIsLeftToTheOrdinaryLookup()
    {
        // Returning "" here instead of null would swallow every ordinary FQN into the
        // root branch, so this is the assertion that matters most.
        assertNull(MetadataDetailsReader.rootRequestName("Catalog.Валюты")); //$NON-NLS-1$
        assertNull(MetadataDetailsReader.rootRequestName("Document.Order")); //$NON-NLS-1$
        assertNull(MetadataDetailsReader.rootRequestName("Subsystem.Configuration")); //$NON-NLS-1$
        assertNull(MetadataDetailsReader.rootRequestName(null));
        assertNull(MetadataDetailsReader.rootRequestName("")); //$NON-NLS-1$
    }

    @Test
    public void aTypeMerelyStartingWithTheWordIsNotTheRoot()
    {
        // "ConfigurationExtension" shares a prefix and is a different thing entirely.
        assertNull(MetadataDetailsReader.rootRequestName("ConfigurationExtension.X")); //$NON-NLS-1$
    }

    @Test
    public void theNameComesBackAsWrittenSoTheCallerCanBeToldWhatTheyAsked()
    {
        // Matching is case-insensitive downstream, like every other FQN lookup here, but
        // the parse must not normalise: a mismatch message quotes what the caller typed.
        assertEquals("дЕмО", MetadataDetailsReader.rootRequestName("Configuration.дЕмО")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
