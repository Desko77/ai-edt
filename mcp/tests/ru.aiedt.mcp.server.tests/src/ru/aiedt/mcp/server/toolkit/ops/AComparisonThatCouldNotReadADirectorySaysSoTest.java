/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * A directory whose listing failed is named, and nothing under it is claimed either way.
 * <p>
 * Both export walks used to swallow a listing failure and keep whatever the stream had produced by
 * then. The answer looked complete - no failure, no note - while the files under the unread part
 * were missing from one side, so the comparison named every one of them removed, or created, about
 * a directory it had seen nothing of.
 * </p>
 * <p>
 * The unread directory is made by taking its listing right away from the account running the test:
 * {@code icacls /deny} on Windows, an empty permission set on POSIX. Where the file system refuses
 * to apply either - a test account with rights over the ACL, a file system without POSIX
 * permissions - the test says so and steps aside rather than passing over a case it never set up.
 * </p>
 */
public class AComparisonThatCouldNotReadADirectorySaysSoTest
{
    private static final String INSIDE = "Catalogs/Locked/Inside.mdo"; //$NON-NLS-1$

    private static final String READABLE = "Catalogs/Goods/Goods.mdo"; //$NON-NLS-1$

    private Path first;

    private Path second;

    private boolean firstDenied;

    private boolean secondDenied;

    @Before
    public void twoExportsSharingALockedDirectory() throws Exception
    {
        first = Files.createTempDirectory("aiedt-compare-locked-a"); //$NON-NLS-1$
        second = Files.createTempDirectory("aiedt-compare-locked-b"); //$NON-NLS-1$
        write(first, READABLE, "<mdo version='one'/>"); //$NON-NLS-1$
        write(second, READABLE, "<mdo version='two'/>"); //$NON-NLS-1$
        // Present and equal on both sides, so a comparison that could read it would say nothing
        // about it: any mention of it in the answer comes from a side that could not look.
        write(first, INSIDE, "<mdo/>"); //$NON-NLS-1$
        write(second, INSIDE, "<mdo/>"); //$NON-NLS-1$
    }

    @After
    public void removeThem() throws Exception
    {
        for (Path export : new Path[]{ first, second })
        {
            // Deleted by path and not walked: the listing right is still denied while this runs, and
            // walking the tree is what the denial is there to break. Removing a named path needs no
            // listing of the directory holding it.
            deleteQuietly(export.resolve(INSIDE));
            deleteQuietly(export.resolve(READABLE));
            deleteQuietly(export.resolve("Catalogs/Locked")); //$NON-NLS-1$
            deleteQuietly(export.resolve("Catalogs")); //$NON-NLS-1$
            deleteQuietly(export);
        }
    }

    /**
     * Removes one path, leaving whatever will not go to the operating system.
     *
     * @param path the path to remove.
     */
    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (Exception stillThere)
        {
            // A temporary tree the operating system collects on its own: a test has nothing to do
            // with a path it cannot remove, and failing here would report the tidying rather than
            // the comparison the test is about.
        }
    }

    private static void write(Path export, String relative, String content) throws Exception
    {
        Path target = export.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private String compare()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.toString()); //$NON-NLS-1$
        params.put("target", second.toString()); //$NON-NLS-1$
        return new CompareConfigurationsTool().execute(params);
    }

    /** A directory one side could not list is named as failed, not passed over. */
    @Test
    public void anUnreadDirectoryOnOneSideIsFailed()
    {
        secondDenied = denyListing(second.resolve("Catalogs/Locked")); //$NON-NLS-1$
        Assume.assumeTrue("this file system refused to make the directory unreadable", //$NON-NLS-1$
            secondDenied);

        String answer = compare();

        assertTrue("the directory that could not be listed has to be named: " + answer, //$NON-NLS-1$
            answer.contains("Catalogs/Locked")); //$NON-NLS-1$
        assertTrue("the entry has to say what happened to it: " + answer, //$NON-NLS-1$
            answer.contains("directory not read")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"failedCount\":1")); //$NON-NLS-1$
    }

    /** Nothing under the unread directory is claimed removed or created. */
    @Test
    public void nothingUnderTheUnreadDirectoryIsClaimed()
    {
        secondDenied = denyListing(second.resolve("Catalogs/Locked")); //$NON-NLS-1$
        Assume.assumeTrue("this file system refused to make the directory unreadable", //$NON-NLS-1$
            secondDenied);

        String answer = compare();

        // The file is on both sides and the same in both. A walk that dropped the unread part
        // without saying so named it removed, which is a claim about a directory nothing was
        // seen of.
        assertFalse("a file under an unread directory is nobody's removed path: " + answer, //$NON-NLS-1$
            answer.contains("\"removed\":[\"" + INSIDE + "\"]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor anybody's added one: " + answer, //$NON-NLS-1$
            answer.contains("\"added\":[\"" + INSIDE + "\"]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the rest of the export is still compared: " + answer, //$NON-NLS-1$
            answer.contains(READABLE));
    }

    /** Two sides that could not be read are two failures, not one. */
    @Test
    public void bothSidesUnreadAreBothNamed()
    {
        firstDenied = denyListing(first.resolve("Catalogs/Locked")); //$NON-NLS-1$
        secondDenied = denyListing(second.resolve("Catalogs/Locked")); //$NON-NLS-1$
        Assume.assumeTrue("this file system refused to make the directory unreadable", //$NON-NLS-1$
            firstDenied && secondDenied);

        String answer = compare();

        assertTrue("each side names the directory it could not read: " + answer, //$NON-NLS-1$
            answer.contains("\"failedCount\":2")); //$NON-NLS-1$
    }

    /**
     * A path one side could not read is not claimed by the other side either: a file standing where
     * the unread directory stands is neither added nor removed.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aFileWhereTheOtherSideCouldNotReadIsNotClaimed() throws Exception
    {
        Files.delete(second.resolve(INSIDE));
        Files.delete(second.resolve("Catalogs/Locked")); //$NON-NLS-1$
        write(second, "Catalogs/Locked", "<mdo/>"); //$NON-NLS-1$ //$NON-NLS-2$
        firstDenied = denyListing(first.resolve("Catalogs/Locked")); //$NON-NLS-1$
        Assume.assumeTrue("this file system refused to make the directory unreadable", //$NON-NLS-1$
            firstDenied);

        String answer = compare();

        assertTrue("the unread directory is named: " + answer, //$NON-NLS-1$
            answer.contains("directory not read")); //$NON-NLS-1$
        assertFalse("the path the first side could not read is not added by the second: " + answer, //$NON-NLS-1$
            answer.contains("\"added\":[\"Catalogs/Locked\"]")); //$NON-NLS-1$
        assertFalse("nor removed: " + answer, //$NON-NLS-1$
            answer.contains("\"removed\":[\"Catalogs/Locked\"]")); //$NON-NLS-1$
    }

    /** An export that reads cleanly says so: the control for the three above. */
    @Test
    public void anExportThatReadsCleanlyNamesNoFailure()
    {
        String answer = compare();

        assertTrue("nothing was unreadable, so nothing is named: " + answer, //$NON-NLS-1$
            answer.contains("\"failedCount\":0")); //$NON-NLS-1$
        assertFalse(answer, answer.contains(INSIDE));
    }

    /**
     * Takes a directory's listing away from this account.
     *
     * @param directory the directory to make unreadable.
     * @return whether the listing really fails afterwards
     */
    private static boolean denyListing(Path directory)
    {
        try
        {
            if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class))
            {
                Files.setPosixFilePermissions(directory, java.util.Collections.emptySet());
                return !listable(directory);
            }
        }
        catch (Exception notPosix)
        {
            // Windows, or a POSIX view this account may not write. Falls through to the ACL.
        }
        try
        {
            Process denied = new ProcessBuilder("icacls", directory.toString(), "/deny", //$NON-NLS-1$ //$NON-NLS-2$
                System.getProperty("user.name") + ":(RD)") //$NON-NLS-1$ //$NON-NLS-2$
                    .redirectErrorStream(true).start();
            denied.waitFor();
            return !listable(directory);
        }
        catch (Exception noAcl)
        {
            return false;
        }
    }

    /**
     * Whether the directory can be listed right now.
     *
     * @param directory the directory.
     * @return whether a listing succeeds
     */
    private static boolean listable(Path directory)
    {
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory))
        {
            return listing.iterator().hasNext();
        }
        catch (Exception denied)
        {
            return false;
        }
    }
}
