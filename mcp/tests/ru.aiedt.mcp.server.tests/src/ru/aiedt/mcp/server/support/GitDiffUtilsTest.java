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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

/**
 * Tests for {@link GitDiffUtils}.
 * <p>
 * The reflective cases check the class shape. {@link #revisionAtReadsTheNamedCommit} builds a
 * temporary repository and reads a named commit back, which needs no Eclipse workspace.
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

    /**
     * A named commit is read back as the bytes that commit stored, and a later commit does not
     * replace that answer. The same repository answers a line diff of the file between the two.
     */
    @Test
    public void revisionAtReadsTheNamedCommit() throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-revision-at"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            git.getRepository().getConfig().setString("core", null, "autocrlf", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().save();
            Path file = root.resolve("Object.mdo"); //$NON-NLS-1$
            Files.writeString(file, "<name>Before</name>\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            git.add().addFilepattern("Object.mdo").call(); //$NON-NLS-1$
            RevCommit first = git.commit().setAuthor("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
                .setMessage("Before").call(); //$NON-NLS-1$
            Files.writeString(file, "<name>After</name>\n", StandardCharsets.UTF_8); //$NON-NLS-1$
            git.add().addFilepattern("Object.mdo").call(); //$NON-NLS-1$
            git.commit().setAuthor("Probe", "probe@example.invalid").setMessage("After").call(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            PreviousRevision atFirst = GitDiffUtils.revisionAt(git.getRepository(), "Object.mdo", //$NON-NLS-1$
                first.getName());
            assertTrue(atFirst.note(), atFirst.isFound());
            assertEquals("<name>Before</name>\n", atFirst.text()); //$NON-NLS-1$

            PreviousRevision atHead = GitDiffUtils.headRevision(git.getRepository(), "Object.mdo"); //$NON-NLS-1$
            assertEquals("<name>After</name>\n", atHead.text()); //$NON-NLS-1$

            GitFileDiff.Answer diff = GitFileDiff.between(git.getRepository(), first.getName(), "HEAD", //$NON-NLS-1$
                "Object.mdo", GitFileDiff.LINE); //$NON-NLS-1$
            assertNull(diff.error);
            assertEquals(1, diff.files.size());
            assertEquals(Integer.valueOf(1), diff.files.get(0).get("linesAdded")); //$NON-NLS-1$
            assertEquals(Integer.valueOf(1), diff.files.get(0).get("linesRemoved")); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(root);
        }
    }

    /**
     * Removes a temporary repository. Loose git objects are read-only on Windows, so each path is
     * made writable before it is deleted, and the walk is closed before any deletion starts.
     *
     * @param root directory to remove, ignored when it does not exist
     * @throws java.io.IOException when a path cannot be removed
     */
    private static void deleteTree(Path root) throws java.io.IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        java.util.List<Path> paths;
        try (var walk = Files.walk(root))
        {
            paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path path : paths)
        {
            path.toFile().setWritable(true);
            Files.deleteIfExists(path);
        }
    }
}
