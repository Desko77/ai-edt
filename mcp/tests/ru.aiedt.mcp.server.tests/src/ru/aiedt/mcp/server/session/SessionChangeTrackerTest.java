/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.session;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link SessionChangeTracker}.
 * <p>
 * Tests cover: class structure, initial state, and pure static accessors.
 * <p>
 * Note: initialize() and shutdown() require Eclipse workspace
 * (ResourcesPlugin.getWorkspace()) and are not testable without
 * a running Eclipse runtime. Event handling requires IResourceChangeEvent
 * mocks which are also unavailable. Only the static API contract
 * for read operations is tested.
 */
public class SessionChangeTrackerTest
{
    // -- Class structure --

    @Test
    public void testClassIsFinal()
    {
        assertTrue("SessionChangeTracker should be final", //$NON-NLS-1$
            Modifier.isFinal(SessionChangeTracker.class.getModifiers()));
    }

    @Test
    public void testConstructorIsPrivate() throws Exception
    {
        // Static utility class should have private constructor
        Constructor<?>[] constructors = SessionChangeTracker.class.getDeclaredConstructors();
        assertEquals("Should have exactly one constructor", 1, constructors.length); //$NON-NLS-1$
        assertTrue("Constructor should be private", //$NON-NLS-1$
            Modifier.isPrivate(constructors[0].getModifiers()));
    }

    // -- Initial state (before initialize) --

    @Test
    public void testInitialStateIsNotActive()
    {
        // Before initialize(), tracker should not be active
        // Note: if another test called initialize() this may differ,
        // but in standard unit test env no Eclipse workspace is available
        // so initialize() would have failed, leaving listener null
        assertFalse(SessionChangeTracker.isActive());
    }

    @Test
    public void testInitialSizeIsZero()
    {
        // No modifications tracked before initialization
        // clear() first to ensure clean state between test runs
        SessionChangeTracker.clear();
        assertEquals(0, SessionChangeTracker.size());
    }

    @Test
    public void testInitialModifiedPathsEmpty()
    {
        SessionChangeTracker.clear();
        Set<String> paths = SessionChangeTracker.getModifiedPaths();
        assertNotNull(paths);
        assertTrue(paths.isEmpty());
    }

    // -- Pure static accessors --

    @Test
    public void testContainsReturnsFalseForUnknownPath()
    {
        SessionChangeTracker.clear();
        assertFalse(SessionChangeTracker.contains("/NonExistent/path.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testClearResetsSize()
    {
        // clear() should reset tracked paths
        SessionChangeTracker.clear();
        assertEquals(0, SessionChangeTracker.size());
        assertTrue(SessionChangeTracker.getModifiedPaths().isEmpty());
    }

    @Test
    public void testGetEventCountIsNonNegative()
    {
        // Event count should be >= 0
        assertTrue(SessionChangeTracker.getEventCount() >= 0);
    }

    @Test
    public void testGetModifiedPathsReturnsUnmodifiableSet()
    {
        SessionChangeTracker.clear();
        Set<String> paths = SessionChangeTracker.getModifiedPaths();

        try
        {
            paths.add("test"); //$NON-NLS-1$
            fail("getModifiedPaths should return unmodifiable set"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException e)
        {
            // Expected - set is unmodifiable
        }
    }
}
