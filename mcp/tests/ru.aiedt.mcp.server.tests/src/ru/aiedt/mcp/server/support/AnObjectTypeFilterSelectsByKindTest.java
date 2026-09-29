/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The {@code objectType} filter of the role audit selects objects by their kind, whatever the case,
 * language and number of the value, and {@code Register} selects every register kind.
 */
public class AnObjectTypeFilterSelectsByKindTest
{
    /**
     * One kind in several spellings, the register family, and kinds the value does not name.
     */
    @Test
    public void aKindIsSelectedWhateverTheSpelling()
    {
        assertTrue(RoleRightsAnalyzer.kindSelected("Catalog", "catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RoleRightsAnalyzer.kindSelected("Catalog", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RoleRightsAnalyzer.kindSelected("Catalog", "Справочник")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RoleRightsAnalyzer.kindSelected("Catalog", "all")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RoleRightsAnalyzer.kindSelected("Catalog", null)); //$NON-NLS-1$
        assertTrue(RoleRightsAnalyzer.kindSelected("InformationRegister", "Register")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RoleRightsAnalyzer.kindSelected("AccumulationRegister", "регистры")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(RoleRightsAnalyzer.kindSelected("Document", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(RoleRightsAnalyzer.kindSelected("DocumentJournal", "Document")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(RoleRightsAnalyzer.kindSelected("Catalog", "Register")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A value that names no metadata type is not a filter the audit understands.
     */
    @Test
    public void aValueThatNamesNoTypeIsUnknown()
    {
        assertTrue(RoleRightsAnalyzer.isKnownObjectType("Register")); //$NON-NLS-1$
        assertTrue(RoleRightsAnalyzer.isKnownObjectType("документы")); //$NON-NLS-1$
        assertTrue(RoleRightsAnalyzer.isKnownObjectType("ALL")); //$NON-NLS-1$
        assertTrue(RoleRightsAnalyzer.isKnownObjectType(null));
        assertFalse(RoleRightsAnalyzer.isKnownObjectType("Catlog")); //$NON-NLS-1$

        String refusal = RoleRightsAnalyzer.unknownObjectTypeMessage("Catlog"); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'Catlog'") && refusal.contains("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, RoleRightsAnalyzer.unknownObjectTypeMessage("Register")); //$NON-NLS-1$
    }

    /**
     * mode=missing keeps the objects of the selected kind: a lower-case value, {@code all} and
     * {@code Register} each select what they name.
     */
    @Test
    public void missingObjectsAreFilteredByKind()
    {
        RoleRightsAnalyzer.RightsTable table = new RoleRightsAnalyzer.RightsTable("Clerk"); //$NON-NLS-1$
        Map<String, RoleRightsAnalyzer.Verdict> nothing = Map.of("Read", RoleRightsAnalyzer.Verdict.UNSPECIFIED); //$NON-NLS-1$
        table.rights.put("Catalog.Goods", nothing); //$NON-NLS-1$
        table.rights.put("Document.Order", nothing); //$NON-NLS-1$
        table.rights.put("InformationRegister.Prices", nothing); //$NON-NLS-1$

        assertEquals(List.of("Catalog.Goods"), RoleRightsAnalyzer.missingObjects(table, "catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, RoleRightsAnalyzer.missingObjects(table, "all").size()); //$NON-NLS-1$
        assertEquals(List.of("InformationRegister.Prices"), //$NON-NLS-1$
            RoleRightsAnalyzer.missingObjects(table, "Register")); //$NON-NLS-1$
    }
}
