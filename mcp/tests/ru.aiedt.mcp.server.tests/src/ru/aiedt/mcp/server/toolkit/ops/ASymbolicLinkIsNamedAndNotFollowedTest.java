/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * A symbolic link is named and never read through.
 * <p>
 * A link is a path, not the file it points at. Reading through one compares a file the export does
 * not own - a file somewhere else on the disk, or one outside both exports - and answers about it
 * as though it were part of the comparison. The walk used to treat a link to a file as a file, and a
 * link to a directory as a directory to walk into, so both sides of the answer could come from
 * trees neither export named.
 * </p>
 * <p>
 * Creating a link needs a right Windows does not grant by default, so these tests step aside where
 * the file system refuses rather than passing over a case that was never set up.
 * </p>
 */
public class ASymbolicLinkIsNamedAndNotFollowedTest
{
    private Path first;

    private Path second;

    private Path elsewhere;

    @Before
    public void twoExports() throws Exception
    {
        first = Files.createTempDirectory("aiedt-compare-link-a"); //$NON-NLS-1$
        second = Files.createTempDirectory("aiedt-compare-link-b"); //$NON-NLS-1$
        elsewhere = Files.createTempDirectory("aiedt-compare-link-out"); //$NON-NLS-1$
        write(first, "Catalogs/Goods/Goods.mdo", "<mdo version='one'/>"); //$NON-NLS-1$
        write(second, "Catalogs/Goods/Goods.mdo", "<mdo version='two'/>"); //$NON-NLS-1$
    }

    @After
    public void removeThem() throws Exception
    {
        for (Path top : new Path[]{ first, second, elsewhere })
        {
            try (Stream<Path> entries = Files.walk(top))
            {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void write(Path export, String relative, String content) throws Exception
    {
        Path target = export.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Makes a symbolic link, or says that this machine will not.
     *
     * @param link the link to create.
     * @param target what it points at.
     * @return whether the link exists afterwards
     */
    private static boolean link(Path link, Path target)
    {
        try
        {
            Files.createSymbolicLink(link, target);
            return true;
        }
        catch (Exception noRight)
        {
            return false;
        }
    }

    private String compare(Path firstExport, Path secondExport)
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", firstExport.toString()); //$NON-NLS-1$
        params.put("target", secondExport.toString()); //$NON-NLS-1$
        return new CompareConfigurationsTool().execute(params);
    }

    /** An export named by a link is refused, not compared through. */
    @Test
    public void aLinkedExportIsRefused()
    {
        Path linked = elsewhere.resolve("LinkedExport"); //$NON-NLS-1$
        Assume.assumeTrue("this machine will not create a symbolic link", //$NON-NLS-1$
            link(linked, first));

        String answer = compare(linked, second);

        assertTrue("a link where an export was named has to be refused: " + answer, //$NON-NLS-1$
            answer.contains("symbolic link")); //$NON-NLS-1$
    }

    /** A link inside an export is named as unread, and its content is never read. */
    @Test
    public void aLinkInsideTheTreeIsNamedAndNotRead() throws Exception
    {
        Path outside = elsewhere.resolve("Outside.mdo"); //$NON-NLS-1$
        write(elsewhere, "Outside.mdo", "<mdo version='one'/>"); //$NON-NLS-1$
        Assume.assumeTrue("this machine will not create a symbolic link", //$NON-NLS-1$
            link(first.resolve("Catalogs/Goods/Linked.mdo"), outside)); //$NON-NLS-1$

        String answer = compare(first, second);

        assertTrue("the link has to be named: " + answer, //$NON-NLS-1$
            answer.contains("Catalogs/Goods/Linked.mdo")); //$NON-NLS-1$
        assertTrue("and named as a link this comparison does not follow: " + answer, //$NON-NLS-1$
            answer.contains("symbolic link, not followed")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"failedCount\":1")); //$NON-NLS-1$
        // A walk that followed the link read the file behind it and, finding no counterpart on the
        // other side, named it added - a file that is in neither export.
        assertFalse("the file behind the link is not a file of the export: " + answer, //$NON-NLS-1$
            answer.contains("\"added\":[\"Catalogs/Goods/Linked.mdo\"]")); //$NON-NLS-1$
    }

    /** A link to a directory is not walked into. */
    @Test
    public void aLinkedDirectoryIsNotWalkedInto() throws Exception
    {
        write(elsewhere, "Hidden/Secret.mdo", "<mdo/>"); //$NON-NLS-1$
        Assume.assumeTrue("this machine will not create a symbolic link", //$NON-NLS-1$
            link(first.resolve("Catalogs/Linked"), elsewhere.resolve("Hidden"))); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = compare(first, second);

        assertTrue("the link itself is named: " + answer, answer.contains("Catalogs/Linked")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nothing behind it is: " + answer, answer.contains("Secret.mdo")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
