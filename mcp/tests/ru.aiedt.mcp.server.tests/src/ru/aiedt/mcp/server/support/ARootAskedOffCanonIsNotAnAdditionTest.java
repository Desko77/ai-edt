/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Holds a root asked for in a spelling the resolver still answers to being counted as an
 * addition of its own closure.
 * <p>
 * The resolver accepts a name in any spelling of its type, while the walk keys its nodes by the
 * canonical FQN. The exclusion of the roots compared the caller's raw spelling against those
 * canonical keys, so a root named the Russian way came back as newly reached and the answer read
 * as if the move dragged its own starting object along.
 * </p>
 */
public class ARootAskedOffCanonIsNotAnAdditionTest
{
    private final Configuration configuration = configuration();

    private final Catalog goods;

    private final Catalog units;

    /**
     * Builds a configuration of two catalogues to ask about and to reach.
     */
    public ARootAskedOffCanonIsNotAnAdditionTest()
    {
        goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Товары"); //$NON-NLS-1$
        configuration.getCatalogs().add(goods);
        units = MdClassFactory.eINSTANCE.createCatalog();
        units.setName("Единицы"); //$NON-NLS-1$
        configuration.getCatalogs().add(units);
    }

    /** A root asked for by its Russian type name is not among the additions. */
    @Test
    public void aRootAskedByItsRussianTypeIsNotAnAddition()
    {
        ScopeClosure.Closure closure = closeOver("Справочник.Товары"); //$NON-NLS-1$

        assertFalse("the asked root itself: " + closure.added, closure.added.contains("Catalog.Товары")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an object reached beside it", closure.added.contains("Catalog.Единицы")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A root asked for by its plural English type name is not an addition either. */
    @Test
    public void aRootAskedByItsPluralEnglishTypeIsNotAnAddition()
    {
        ScopeClosure.Closure closure = closeOver("Catalogs.Товары"); //$NON-NLS-1$

        assertFalse(closure.added.contains("Catalog.Товары")); //$NON-NLS-1$
        assertTrue(closure.added.contains("Catalog.Единицы")); //$NON-NLS-1$
    }

    /** The spelling the caller used stays theirs in the answer, whichever form it was. */
    @Test
    public void theRequestedListKeepsTheCallersSpelling()
    {
        ScopeClosure.Closure closure = closeOver("Справочник.Товары"); //$NON-NLS-1$

        assertEquals(List.of("Справочник.Товары"), closure.requested); //$NON-NLS-1$
    }

    /**
     * Runs the two halves a closure is made of - resolving the roots, then sorting the nodes the
     * walk reached - over the configuration of this test.
     *
     * @param name the root, in the spelling the caller would write
     * @return the built closure
     */
    private ScopeClosure.Closure closeOver(String name)
    {
        ScopeClosure.Closure closure = new ScopeClosure.Closure();
        Collection<IBmObject> roots = new ArrayList<>();
        Set<String> asked = new LinkedHashSet<>();
        ScopeClosure.resolveRoots(configuration, List.of(name), closure, roots, asked);
        assertSame("the off-canon name resolved to the object", goods, roots.iterator().next()); //$NON-NLS-1$
        BmReferencesHelper.BfsResult found = new BmReferencesHelper.BfsResult();
        // The interfaces of the model do not carry the BM base; every object the model stores
        // does, so the cast states what the walk's map asks for.
        found.nodes.put("Catalog.Товары", (IBmObject)goods); //$NON-NLS-1$
        found.nodes.put("Catalog.Единицы", (IBmObject)units); //$NON-NLS-1$
        ScopeClosure.classifyReached(found, asked, closure);
        return closure;
    }

    /**
     * Builds the configuration the roots resolve against.
     *
     * @return a configuration with two catalogues
     */
    private static Configuration configuration()
    {
        return MdClassFactory.eINSTANCE.createConfiguration();
    }
}
