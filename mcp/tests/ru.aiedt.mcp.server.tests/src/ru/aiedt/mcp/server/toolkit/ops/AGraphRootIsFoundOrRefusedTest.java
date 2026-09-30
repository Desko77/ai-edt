/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * The root of a dependency graph is either found or refused, and the caps of the walk have a
 * ceiling.
 * <p>
 * An unknown scope, a scope without the argument naming its root and a root the project lacks all
 * answered with an empty graph reported as success, so a mistyped name read as an object with no
 * dependencies. {@code maxNodes} and {@code maxEdges} had no upper bound.
 * </p>
 */
public class AGraphRootIsFoundOrRefusedTest
{
    @Test
    public void anUnknownScopeIsRefusedWithTheValidOnes()
    {
        String refusal = DependencyGraphTool.scopeRefusal("objekt", Map.of()); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Invalid scope 'objekt'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Did you mean 'object'?")); //$NON-NLS-1$
    }

    @Test
    public void aScopeWithoutItsRootArgumentIsRefused()
    {
        String refusal = DependencyGraphTool.scopeRefusal("subsystem", Map.of()); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("subsystemName is required.")); //$NON-NLS-1$
        assertTrue(refusal, DependencyGraphTool.scopeRefusal("Object", Map.of("objectFqn", " ")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .startsWith("objectFqn is required.")); //$NON-NLS-1$
        assertTrue(DependencyGraphTool.scopeRefusal("module", Map.of()).startsWith("moduleFqn is required.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aScopeWithItsRootArgumentPassesToTheLookup()
    {
        assertNull(DependencyGraphTool.scopeRefusal("project", Map.of())); //$NON-NLS-1$
        assertNull(DependencyGraphTool.scopeRefusal("object", Map.of("objectFqn", "Catalog.Products"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(DependencyGraphTool.scopeRefusal("subsystem", Map.of("subsystemName", "Sales"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void anAbsentObjectIsNamedWithItsCloseNames()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog products = MdClassFactory.eINSTANCE.createCatalog();
        products.setName("Products"); //$NON-NLS-1$
        configuration.getCatalogs().add(products);

        String refusal = DependencyGraphTool.rootNotFound("object", //$NON-NLS-1$
            Map.of("objectFqn", "Catalog.Prodcts"), configuration); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(refusal, refusal.startsWith("objectFqn 'Catalog.Prodcts' names no object of the project.")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Close names: Catalog.Products.")); //$NON-NLS-1$
    }

    @Test
    public void aValueThatIsNotAnFqnIsSaidToBeOne()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();

        assertEquals("moduleFqn 'Products' is not an FQN of the form Type.Name.", //$NON-NLS-1$
            DependencyGraphTool.rootNotFound("module", Map.of("moduleFqn", "Products"), configuration)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void anAbsentSubsystemIsNamed()
    {
        String refusal = DependencyGraphTool.rootNotFound("subsystem", //$NON-NLS-1$
            Map.of("subsystemName", "Sales"), MdClassFactory.eINSTANCE.createConfiguration()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("subsystemName 'Sales' names no top-level subsystem of the project.", refusal); //$NON-NLS-1$
    }

    @Test
    public void theCapsAreCutToTheirCeiling()
    {
        assertEquals(DependencyGraphTool.MAX_NODES, DependencyGraphTool.nodeCap(Map.of("maxNodes", "100000"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DependencyGraphTool.MAX_EDGES, DependencyGraphTool.edgeCap(Map.of("maxEdges", "100000"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(200, DependencyGraphTool.nodeCap(Map.of()));
        assertEquals(500, DependencyGraphTool.edgeCap(Map.of()));
        assertEquals(1, DependencyGraphTool.nodeCap(Map.of("maxNodes", "0"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
