/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * A directory that could not be read is not a directory that is not there.
 * <p>
 * A read failure used to leave the directory's files out of the reading entirely, the way an
 * absent directory does - and the comparison then named every file under it removed, or the
 * directory's own new files created, about a scope it had seen nothing of.
 * </p>
 */
public class AReadFailureIsNotAnAbsenceTest
{
    /**
     * The marker an unread file is recorded with. A constant of the class under test, repeated
     * here because the marker is what the reading looks like from outside.
     */
    private static final String NOT_READ = "?"; //$NON-NLS-1$

    private Path elsewhere;

    @Before
    public void makeAnUnreadableDirectory() throws Exception
    {
        // A directory the reading cannot digest: its walk succeeds, but every file key it tries
        // to relativize against a RELATIVE root throws, the way a locked or unreadable directory
        // fails inside the same catch - the one that used to swallow the difference between a
        // read failure and an absence. The root never touches the disk; relativize looks at the
        // shapes of the paths only.
        elsewhere = Files.createTempDirectory("aiedt-read-failure"); //$NON-NLS-1$
        Files.write(elsewhere.resolve("Goods.mdo"), "<mdo/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(elsewhere))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** The reading marks a directory it could not read, rather than leaving it out as absent. */
    @Test
    public void aDirectoryThatCouldNotBeReadIsMarkedNotOmitted()
    {
        Map<String, String> reading = ExportedFiles.underDirectories(
            java.nio.file.Paths.get("relative-root"), List.of(elsewhere)); //$NON-NLS-1$

        assertEquals("one entry, standing for the whole unread directory", 1, reading.size()); //$NON-NLS-1$
        assertTrue("the entry is marked as not read: " + reading, reading.containsValue(NOT_READ)); //$NON-NLS-1$
    }

    /** Files under a directory one side could not read are named neither removed nor created. */
    @Test
    public void anUnreadDirectoryNamesNothingUnderItRemoved()
    {
        Map<String, String> before = new HashMap<>();
        before.put("src/Catalogs/Goods/Goods.mdo", "h1"); //$NON-NLS-1$ //$NON-NLS-2$
        before.put("src/Catalogs/Goods/Ext/Form.form", "h2"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> now = new HashMap<>();
        now.put("src/Catalogs/Goods", NOT_READ); //$NON-NLS-1$

        ExportedFiles.Changes changes = ExportedFiles.between(before, now);

        assertTrue("the unread directory itself is nobody's created path", changes.created().isEmpty()); //$NON-NLS-1$
        assertTrue("and its files are nobody's removed paths", changes.removed().isEmpty()); //$NON-NLS-1$
        assertTrue(changes.written().isEmpty());
    }

    /** A directory the earlier reading could not see says nothing about what appeared under it. */
    @Test
    public void anUnreadEarlierDirectoryNamesNothingUnderItCreated()
    {
        Map<String, String> before = new HashMap<>();
        before.put("src/Catalogs/Goods", NOT_READ); //$NON-NLS-1$
        Map<String, String> now = new HashMap<>();
        now.put("src/Catalogs/Goods/Goods.mdo", "h1"); //$NON-NLS-1$ //$NON-NLS-2$

        ExportedFiles.Changes changes = ExportedFiles.between(before, now);

        assertTrue("nothing under an unknown scope is named created", changes.created().isEmpty()); //$NON-NLS-1$
        assertTrue(changes.removed().isEmpty());
        assertTrue(changes.written().isEmpty());
    }

    /**
     * A path whose state cannot even be asked for is marked, not treated as absent.
     * <p>
     * {@code Files.isDirectory} answers {@code false} both for a directory that is not there and
     * for one whose attributes cannot be read: a name the file system refuses to answer for, a
     * share that went away, a directory whose own rights deny the query. The two are not the same
     * fact - an absent directory holds no objects, and an unanswerable one may hold anything - so
     * the difference is what the caller needs the reading to keep.
     * </p>
     */
    @Test
    public void aStateThatCannotBeAskedForIsMarkedNotOmitted()
    {
        // A name longer than the file system will answer for. Where it does answer - a POSIX file
        // system takes a component this long for the ordinary answer "not there" - the path is
        // absent rather than undetermined, which is a different case and not this one.
        Path unanswerable = elsewhere.resolve("x".repeat(300)); //$NON-NLS-1$
        Assume.assumeFalse("this file system answers for a name this long: the path is simply absent", //$NON-NLS-1$
            answers(unanswerable));

        Map<String, String> reading = ExportedFiles.underDirectories(elsewhere,
            List.of(unanswerable));

        assertEquals("one entry, standing for the directory whose state is unknown", 1, //$NON-NLS-1$
            reading.size());
        assertTrue("the entry is marked as not read: " + reading, reading.containsValue(NOT_READ)); //$NON-NLS-1$

        assertTrue("the control - an absent directory contributes nothing", //$NON-NLS-1$
            ExportedFiles.underDirectories(elsewhere, List.of(elsewhere.resolve("nothing-here"))) //$NON-NLS-1$
                .isEmpty());
    }

    /**
     * Whether the file system has an answer for this path at all.
     *
     * @param path the path.
     * @return whether asking for its attributes settles the question one way or the other
     */
    private static boolean answers(Path path)
    {
        try
        {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            return true;
        }
        catch (java.nio.file.NoSuchFileException absent)
        {
            return true;
        }
        catch (Exception undetermined)
        {
            return false;
        }
    }

    /** The marking is scoped: directories both sides read keep naming their changes. */
    @Test
    public void aReadDirectoryStillNamesItsChanges()
    {
        Map<String, String> before = new HashMap<>();
        before.put("src/Catalogs/Goods/Goods.mdo", "h1"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> now = new HashMap<>();
        now.put("src/Catalogs/Goods/Goods.mdo", "h2"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("src/Catalogs/Goods/Goods.mdo"), //$NON-NLS-1$
            ExportedFiles.between(before, now).written());

        Map<String, String> moved = new HashMap<>();
        moved.put("src/Documents/Order/Order.mdo", "h1"); //$NON-NLS-1$ //$NON-NLS-2$
        ExportedFiles.Changes relocated = ExportedFiles.between(before, moved);
        assertEquals(List.of("src/Documents/Order/Order.mdo"), relocated.created()); //$NON-NLS-1$
        assertEquals(List.of("src/Catalogs/Goods/Goods.mdo"), relocated.removed()); //$NON-NLS-1$
    }
}
