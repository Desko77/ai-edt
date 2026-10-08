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

/**
 * A selector names the root of a dependency graph, whether or not the caller spelled the scope
 * out, and a scope that disagrees with its selector is refused.
 * <p>
 * A selector used to be dropped in silence when {@code scope} stayed at its project default, so a
 * call that named an object walked the whole project instead and read as a project with thousands
 * of nodes and not one edge.
 * </p>
 */
public class TheSelectorsDecideTheRootOfTheWalkTest
{
    /** A selector without its scope selects the root on its own, as the other walks here do. */
    @Test
    public void aSelectorWithoutScopeSelectsItsRoot()
    {
        assertEquals("object", DependencyGraphTool.scopeWord(null, //$NON-NLS-1$
            Map.of("objectFqn", "Catalog.Products"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("module", DependencyGraphTool.scopeWord(null, //$NON-NLS-1$
            Map.of("moduleFqn", "CommonModule.Sales.Module"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("subsystem", DependencyGraphTool.scopeWord(null, //$NON-NLS-1$
            Map.of("subsystemName", "Sales"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("project", DependencyGraphTool.scopeWord(null, Map.of())); //$NON-NLS-1$
        assertEquals("an asked scope governs", "subsystem", //$NON-NLS-1$
            DependencyGraphTool.scopeWord("Subsystem", Map.of("objectFqn", "Catalog.Products"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("nothing to refuse: the selector decides", //$NON-NLS-1$
            DependencyGraphTool.scopeRefusal(null, Map.of("objectFqn", "Catalog.Products"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A selector the scope does not take is refused by name, not walked around. */
    @Test
    public void aProjectScopeRejectsEverySelector()
    {
        String refusal = DependencyGraphTool.scopeRefusal("project", //$NON-NLS-1$
            Map.of("objectFqn", "Catalog.Products")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("scope=project does not accept " //$NON-NLS-1$
            + "objectFqn 'Catalog.Products'.")); //$NON-NLS-1$
    }

    /** A scope with the selector of another root names two roots and answers for it. */
    @Test
    public void aScopeThatDisagreesWithItsSelectorIsRefused()
    {
        String refusal = DependencyGraphTool.scopeRefusal("object", //$NON-NLS-1$
            Map.of("objectFqn", "Catalog.Products", "subsystemName", "Sales")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("scope=object does not accept " //$NON-NLS-1$
            + "subsystemName 'Sales'.")); //$NON-NLS-1$
    }

    /** Selectors that name more than one root are refused together, scope or no scope. */
    @Test
    public void twoSelectorsWithoutScopeAreRefusedTogether()
    {
        String refusal = DependencyGraphTool.scopeRefusal(null, //$NON-NLS-1$
            Map.of("objectFqn", "Catalog.Products", "moduleFqn", "CommonModule.Sales.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("conflicts with")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Pass one of them.")); //$NON-NLS-1$
    }

    /** The refusals the walk already gave stay as they were. */
    @Test
    public void theRefusalsOfTheScopeItselfStay()
    {
        assertTrue(DependencyGraphTool.scopeRefusal("subsystem", Map.of()) //$NON-NLS-1$
            .startsWith("subsystemName is required.")); //$NON-NLS-1$
        assertTrue(DependencyGraphTool.scopeRefusal("module", Map.of()).startsWith("moduleFqn is required.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DependencyGraphTool.scopeRefusal("project", Map.of())); //$NON-NLS-1$
        assertNull(DependencyGraphTool.scopeRefusal("object", //$NON-NLS-1$
            Map.of("objectFqn", "Catalog.Products"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
