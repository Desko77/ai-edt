/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.support.BmSubsystemHelper;

/**
 * Subsystem content is changed on a subsystem only: any other owner is refused by name before
 * anything changes, and "not a subsystem" never reads as "already present".
 */
public class ContentIsOnlyAddedToASubsystemTest
{
    /**
     * Builds a catalog with a name.
     *
     * @param name the name.
     * @return the catalog
     */
    private static Catalog catalog(String name)
    {
        Catalog c = MdClassFactory.eINSTANCE.createCatalog();
        c.setName(name);
        return c;
    }

    /**
     * Builds a subsystem with a name.
     *
     * @param name the name.
     * @return the subsystem
     */
    private static Subsystem subsystem(String name)
    {
        Subsystem s = MdClassFactory.eINSTANCE.createSubsystem();
        s.setName(name);
        return s;
    }

    /**
     * A subsystem holds content, a catalog does not.
     */
    @Test
    public void onlyASubsystemHoldsContent()
    {
        assertTrue(BmSubsystemHelper.canHoldContent(subsystem("Sales"))); //$NON-NLS-1$
        assertFalse(BmSubsystemHelper.canHoldContent(catalog("Goods"))); //$NON-NLS-1$
        assertFalse(BmSubsystemHelper.canHoldContent(null));
    }

    /**
     * Adding to a catalog fails outright instead of answering false, which the operation read as
     * "already in subsystem".
     */
    @Test
    public void addingToACatalogIsNotReportedAsAlreadyPresent()
    {
        try
        {
            BmSubsystemHelper.addContent(catalog("Goods"), catalog("Other")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a catalog has no subsystem content to add to"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Goods")); //$NON-NLS-1$
        }
    }

    /**
     * On a subsystem the first addition happens and the second one is the idempotent skip.
     */
    @Test
    public void aSubsystemTakesTheObjectOnce()
    {
        Subsystem sales = subsystem("Sales"); //$NON-NLS-1$
        Catalog goods = catalog("Goods"); //$NON-NLS-1$
        assertTrue(BmSubsystemHelper.addContent(sales, goods));
        assertFalse(BmSubsystemHelper.addContent(sales, goods));
    }

    /**
     * The operation's refusal names the owner kind and says nothing was changed; a subsystem owner
     * passes.
     */
    @Test
    public void theRefusalNamesTheOwner()
    {
        String refusal = ContentOps.notASubsystem(catalog("Goods"), "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            "add_subsystem_content"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Catalog")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Catalog.Goods")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("already")); //$NON-NLS-1$
        assertNull(ContentOps.notASubsystem(subsystem("Sales"), "Subsystem.Sales", //$NON-NLS-1$ //$NON-NLS-2$
            "add_subsystem_content")); //$NON-NLS-1$
    }

    /**
     * An unknown project is refused with the name the caller passed, the way every other operation
     * refuses it, rather than with a bare "Project not found".
     */
    @Test
    public void anUnknownProjectIsNamedInTheRefusal()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "NoSuchProjectForContent"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Subsystem.Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ContentOps().opAddSubsystemContent(params);
        assertTrue(answer, answer.contains("NoSuchProjectForContent")); //$NON-NLS-1$
    }
}
