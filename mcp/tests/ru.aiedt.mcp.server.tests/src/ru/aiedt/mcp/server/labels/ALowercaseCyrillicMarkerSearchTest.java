/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;

import org.junit.Test;

/**
 * A lowercase Cyrillic query has to find a marker whose name is written with capitals.
 */
public class ALowercaseCyrillicMarkerSearchTest
{
    @Test
    public void lowercaseCyrillicFindsCatalogGoods()
    {
        Pattern pattern = MarkerSearch.compile("товары"); //$NON-NLS-1$

        assertTrue(pattern.matcher("Catalog.Товары").find()); //$NON-NLS-1$
        assertTrue(pattern.matcher("catalog.товары").find()); //$NON-NLS-1$
        assertFalse(pattern.matcher("Catalog.Products").find()); //$NON-NLS-1$
    }
}
