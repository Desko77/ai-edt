/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.Test;

/**
 * Tests for {@link GitDiffUtils}.
 * <p>
 * GitDiffUtils is a static utility class that requires Eclipse workspace
 * (IFile, IProject) for all operations. Only structural/reflective tests
 * are possible without a running Eclipse runtime.
 */
public class GitDiffUtilsTest
{
    @Test
    public void testClassExists()
    {
        // Verify the class can be loaded and referenced
        assertNotNull(GitDiffUtils.class);
    }

    @Test
    public void testClassIsFinal()
    {
        assertTrue("GitDiffUtils should be final", //$NON-NLS-1$
            Modifier.isFinal(GitDiffUtils.class.getModifiers()));
    }

    @Test
    public void testConstructorIsPrivate() throws Exception
    {
        // Utility class should have private constructor
        Constructor<?>[] constructors = GitDiffUtils.class.getDeclaredConstructors();
        assertEquals("Should have exactly one constructor", 1, constructors.length); //$NON-NLS-1$
        assertTrue("Constructor should be private", //$NON-NLS-1$
            Modifier.isPrivate(constructors[0].getModifiers()));
    }

    @Test
    public void testGetPreviousVersionMethodExists() throws Exception
    {
        // Verify the getPreviousVersion method is declared and public static
        Method method = GitDiffUtils.class.getMethod(
            "getPreviousVersion", //$NON-NLS-1$
            org.eclipse.core.resources.IFile.class,
            org.eclipse.core.resources.IProject.class);

        assertNotNull(method);
        assertTrue("getPreviousVersion should be static", //$NON-NLS-1$
            Modifier.isStatic(method.getModifiers()));
        assertTrue("getPreviousVersion should be public", //$NON-NLS-1$
            Modifier.isPublic(method.getModifiers()));
        assertEquals("getPreviousVersion should return String", //$NON-NLS-1$
            String.class, method.getReturnType());
    }
}
