/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the branch-to-infobase bindings kept beside a project.
 */
public class BranchInfobaseBookTest
{
    private Path project;

    @Before
    public void makeAProjectDirectory() throws Exception
    {
        project = Files.createTempDirectory("aiedt-branch"); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(project))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** A binding survives being written and read back. */
    @Test
    public void aBindingIsRemembered()
    {
        assertNull(BranchInfobaseBook.bindAt(project, "feature/tax-report", "demo-tax")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("demo-tax", BranchInfobaseBook.allAt(project).get("feature/tax-report")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Binding the same branch twice replaces rather than duplicates. */
    @Test
    public void bindingAgainReplaces()
    {
        BranchInfobaseBook.bindAt(project, "main", "first"); //$NON-NLS-1$ //$NON-NLS-2$
        BranchInfobaseBook.bindAt(project, "main", "second"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> all = BranchInfobaseBook.allAt(project);
        assertEquals(1, all.size());
        assertEquals("second", all.get("main")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Several branches coexist, and a branch name with slashes stays whole. */
    @Test
    public void severalBranchesCoexist()
    {
        BranchInfobaseBook.bindAt(project, "main", "prod-copy"); //$NON-NLS-1$ //$NON-NLS-2$
        BranchInfobaseBook.bindAt(project, "feature/a/b", "scratch"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> all = BranchInfobaseBook.allAt(project);
        assertEquals(2, all.size());
        assertEquals("scratch", all.get("feature/a/b")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Unbinding removes one and leaves the rest. */
    @Test
    public void unbindingRemovesOnlyThatOne()
    {
        BranchInfobaseBook.bindAt(project, "main", "prod-copy"); //$NON-NLS-1$ //$NON-NLS-2$
        BranchInfobaseBook.bindAt(project, "wip", "scratch"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(BranchInfobaseBook.unbindAt(project, "wip")); //$NON-NLS-1$

        Map<String, String> all = BranchInfobaseBook.allAt(project);
        assertEquals(1, all.size());
        assertTrue(all.containsKey("main")); //$NON-NLS-1$
    }

    /** Unbinding something that was never bound is not an error. */
    @Test
    public void unbindingNothingIsNotAFailure()
    {
        assertNull(BranchInfobaseBook.unbindAt(project, "never-existed")); //$NON-NLS-1$
    }

    /** A project with no file has no bindings, and does not have one created for it by asking. */
    @Test
    public void askingDoesNotCreateAnything()
    {
        assertTrue(BranchInfobaseBook.allAt(project).isEmpty());
        assertTrue("reading must not write", //$NON-NLS-1$
            !Files.exists(project.resolve(".settings").resolve(BranchInfobaseBook.FILE))); //$NON-NLS-1$
    }

    /** Half a binding is not a binding, and says so rather than writing a broken one. */
    @Test
    public void bothHalvesAreRequired()
    {
        assertNotNull(BranchInfobaseBook.bindAt(project, "main", null)); //$NON-NLS-1$
        assertNotNull(BranchInfobaseBook.bindAt(project, "", "app")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(BranchInfobaseBook.bindAt(null, "main", "app")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BranchInfobaseBook.allAt(project).isEmpty());
    }

    /**
     * A file somebody hand-edited into nonsense reads as no bindings.
     * <p>
     * Deliberately not an error. This guard sits in front of {@code update_database}, and a guard
     * that breaks the thing it guards - refusing every update because its own notes are unreadable
     * - is worse than no guard at all.
     * </p>
     */
    @Test
    public void nonsenseInTheFileIsNotAnObstacle() throws Exception
    {
        Path file = project.resolve(".settings").resolve(BranchInfobaseBook.FILE); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.write(file, "this: [is: not: valid".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertTrue(BranchInfobaseBook.allAt(project).isEmpty());
    }

    /** A file of the right shape but the wrong contents is also just empty. */
    @Test
    public void aFileWithoutBindingsIsEmpty() throws Exception
    {
        Path file = project.resolve(".settings").resolve(BranchInfobaseBook.FILE); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.write(file, "somethingElse:\n  a: b\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertTrue(BranchInfobaseBook.allAt(project).isEmpty());
    }

    /** What is written is readable as text, because a person may well open it. */
    @Test
    public void theFileIsPlainReadableYaml() throws Exception
    {
        BranchInfobaseBook.bindAt(project, "main", "prod-copy"); //$NON-NLS-1$ //$NON-NLS-2$

        String text = new String(Files.readAllBytes(
            project.resolve(".settings").resolve(BranchInfobaseBook.FILE)), StandardCharsets.UTF_8); //$NON-NLS-1$

        assertTrue("the branch should be legible in the file: " + text, //$NON-NLS-1$
            text.contains("main")); //$NON-NLS-1$
        assertTrue(text.contains("prod-copy")); //$NON-NLS-1$
        assertTrue("and it should be nested under something named: " + text, //$NON-NLS-1$
            text.contains("bindings")); //$NON-NLS-1$
    }
}
