/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a not-found featurePath refusal names beside the path.
 * <p>
 * A path that misses by one letter used to read exactly like one that misses by a whole
 * directory: a bare "featurePath not found" with nothing to retry against. The refusal now
 * carries the nearest .feature files of the directory the caller pointed into, and says which
 * directory it looked in when the one named does not exist.
 * </p>
 */
public class TheFeaturePathRefusalNamesCandidatesTest
{
    private Path dir;

    @Before
    public void makeADirectoryOfFeatures() throws Exception
    {
        dir = Files.createTempDirectory("vanessa-feature-candidates"); //$NON-NLS-1$
        for (String name : new String[] {"smoke.feature", "smoke2.feature", "checkout.feature", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "login.feature", "report.feature", "quarantine.feature"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Files.write(dir.resolve(name), new byte[] {1});
        }
        Files.write(dir.resolve("not-a-scenario.txt"), new byte[] {1}); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(dir))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** A misspelling is answered with the names nearest to it, and no more than five of them. */
    @Test
    public void aMisspelledFeatureIsAnsweredByTheNearestNames()
    {
        String refusal = refusalFor(dir.resolve("smokee.feature").toFile());

        assertTrue("the nearest name must come first: " + refusal, //$NON-NLS-1$
            refusal.contains("Nearest .feature files: smoke.feature, smoke2.feature")); //$NON-NLS-1$
        assertFalse("a name far past the fifth nearest is not listed: " + refusal, //$NON-NLS-1$
            refusal.contains("quarantine.feature")); //$NON-NLS-1$
        assertEquals("no more than five names are listed", 5, listedCount(refusal)); //$NON-NLS-1$
        assertFalse("a file that is not a scenario is not a candidate: " + refusal, //$NON-NLS-1$
            refusal.contains("not-a-scenario")); //$NON-NLS-1$
    }

    /**
     * A path into a directory that does not exist is answered from the nearest ancestor that
     * does, and says so.
     */
    @Test
    public void aMissingDirectoryIsAnsweredByItsNearestAncestor()
    {
        File named = dir.resolve("no/such/subdirectory/smoke.feature").toFile(); //$NON-NLS-1$

        String refusal = refusalFor(named);

        assertTrue("the refusal must say which directory it looked in: " + refusal, //$NON-NLS-1$
            refusal.contains("The directory named does not exist; the nearest one that does is " //$NON-NLS-1$
                + dir.toFile().getAbsolutePath()));
        assertTrue("the ancestor's nearest features are still named: " + refusal, //$NON-NLS-1$
            refusal.contains("smoke.feature")); //$NON-NLS-1$
    }

    /**
     * A relative path - what a call without a project resolves to - is looked up from the working
     * directory, the one the refusal names.
     */
    @Test
    public void aRelativePathIsAnsweredFromTheWorkingDirectory() throws Exception
    {
        String sub = "vanessa-relative-" + System.nanoTime(); //$NON-NLS-1$
        Path under = new File(sub).getAbsoluteFile().toPath();
        Files.createDirectories(under);
        try
        {
            Files.write(under.resolve("smoke.feature"), new byte[] {1}); //$NON-NLS-1$

            String refusal = refusalFor(new File(sub, "smokee.feature")); //$NON-NLS-1$

            assertTrue("the directory the relative path points into must be searched: " + refusal, //$NON-NLS-1$
                refusal.contains("Nearest .feature files: smoke.feature")); //$NON-NLS-1$
        }
        finally
        {
            try (Stream<Path> entries = Files.walk(under))
            {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** A directory with nothing to run says so, rather than listing nothing without a word. */
    @Test
    public void anEmptyDirectoryIsSaidToHoldNoFeatures() throws Exception
    {
        Path empty = Files.createTempDirectory("vanessa-no-features"); //$NON-NLS-1$
        try
        {
            String refusal = refusalFor(empty.resolve("smoke.feature").toFile());

            assertTrue("the refusal must say there is nothing there: " + refusal, //$NON-NLS-1$
                refusal.contains("No .feature file exists there.")); //$NON-NLS-1$
        }
        finally
        {
            try (Stream<Path> entries = Files.walk(empty))
            {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * The refusal document for one named path.
     *
     * @param named the path the caller asked for
     * @return the refusal's error message
     */
    private static String refusalFor(File named)
    {
        String answer = VanessaTool.featurePathNotFound(named);
        JsonObject body = JsonParser.parseString(answer).getAsJsonObject();
        assertEquals(Boolean.FALSE, body.get("success").getAsBoolean()); //$NON-NLS-1$
        return body.get("error").getAsString(); //$NON-NLS-1$
    }

    /**
     * How many names the candidate list carries.
     *
     * @param refusal the refusal's error message
     * @return the count of names after the list's opening
     */
    private static int listedCount(String refusal)
    {
        int opening = refusal.indexOf("Nearest .feature files: "); //$NON-NLS-1$
        if (opening < 0)
        {
            return 0;
        }
        String list = refusal.substring(opening + "Nearest .feature files: ".length()); //$NON-NLS-1$
        if (list.endsWith(".")) //$NON-NLS-1$
        {
            list = list.substring(0, list.length() - 1);
        }
        return list.split(", ").length; //$NON-NLS-1$
    }
}
