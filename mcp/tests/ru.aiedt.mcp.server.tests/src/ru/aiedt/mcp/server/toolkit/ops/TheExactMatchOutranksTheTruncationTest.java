/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * An exact match found late in the walk still ranks first.
 * <p>
 * The walk used to stop collecting at the limit, before the whole configuration was visited, and
 * sorted only what it had. An exact name match - the strongest hit there is - sitting behind a cap
 * of partial matches was never collected at all. The walk now visits everything, sorts everything,
 * and only then takes the first {@code limit}; {@code matchCount} says how many were found in
 * total.
 * </p>
 */
public class TheExactMatchOutranksTheTruncationTest
{
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> body)
    {
        return (List<Map<String, Object>>)body.get("results"); //$NON-NLS-1$
    }

    /** The exact match visited after the cap still comes first, and the count says what was cut. */
    @Test
    public void theExactMatchBehindTheCapIsCollectedAndRankedFirst()
    {
        Configuration cfg = MdClassFactory.eINSTANCE.createConfiguration();
        for (String partial : new String[] { "SuperAgent1", "SuperAgent2", "SuperAgent3" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
            catalog.setName(partial);
            cfg.getCatalogs().add(catalog);
        }
        Catalog exact = MdClassFactory.eINSTANCE.createCatalog();
        exact.setName("Super"); //$NON-NLS-1$
        cfg.getCatalogs().add(exact);

        Map<String, Object> body = SemanticMetadataSearchTool.searchBody(cfg, "super", null, 3); //$NON-NLS-1$

        assertEquals("four matches were found in total", Integer.valueOf(4), //$NON-NLS-1$
            body.get("matchCount")); //$NON-NLS-1$
        assertEquals("the answer was truncated to the limit", Boolean.TRUE, body.get("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only the limit is returned", 3, results(body).size()); //$NON-NLS-1$
        assertEquals("the exact match ranks first despite being visited last", "Super", //$NON-NLS-1$ //$NON-NLS-2$
            results(body).get(0).get("name")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(200), results(body).get(0).get("score")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** With fewer matches than the limit nothing is cut and nothing claims truncation. */
    @Test
    public void underTheLimitNothingIsTruncated()
    {
        Configuration cfg = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Super"); //$NON-NLS-1$
        cfg.getCatalogs().add(catalog);

        Map<String, Object> body = SemanticMetadataSearchTool.searchBody(cfg, "super", null, 3); //$NON-NLS-1$

        assertEquals(Integer.valueOf(1), body.get("matchCount")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, body.get("truncated")); //$NON-NLS-1$
        assertEquals(1, results(body).size());
    }
}
