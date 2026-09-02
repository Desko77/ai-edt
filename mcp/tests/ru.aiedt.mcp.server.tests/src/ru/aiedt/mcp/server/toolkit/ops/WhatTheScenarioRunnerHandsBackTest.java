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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What the scenario runner resolves and what it hands back.
 * <p>
 * Until now only its JUnit parser was covered, so the parts that decide WHAT runs and WHAT the
 * caller sees were untested. A census of the tool on 02.09 counted that as one of its gaps.
 * </p>
 * <p>
 * The masking tested here covers the plugin's log and the answer. It cannot cover the command line
 * of the running client, where the connection string - and with it the password the caller put in
 * it - is passed as one argument: that is a separate defect, recorded, and no test here should be
 * read as saying otherwise.
 * </p>
 */
public class WhatTheScenarioRunnerHandsBackTest
{
    private Path dir;

    @Before
    public void makeADirectory() throws Exception
    {
        dir = Files.createTempDirectory("vanessa-test"); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(dir))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void aQuotedPasswordIsMaskedInWhatIsShown()
    {
        String masked = VanessaTool.redactSecrets(
            "File=\"C:\\\\ib\";Usr=\"tester\";Pwd=\"s3cret\";"); //$NON-NLS-1$

        assertFalse("the password must not survive into the answer", //$NON-NLS-1$
            masked.contains("s3cret")); //$NON-NLS-1$
        assertTrue("the user name is not a secret and stays readable", //$NON-NLS-1$
            masked.contains("tester")); //$NON-NLS-1$
    }

    @Test
    public void anUnquotedPasswordIsMaskedToo()
    {
        String masked = VanessaTool.redactSecrets("Srvr=host;Ref=base;Pwd=s3cret;Usr=tester"); //$NON-NLS-1$

        assertFalse(masked.contains("s3cret")); //$NON-NLS-1$
        assertTrue(masked.contains("tester")); //$NON-NLS-1$
    }

    @Test
    public void theKeyIsMatchedWhateverItsCase()
    {
        assertFalse(VanessaTool.redactSecrets("pwd=s3cret").contains("s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(VanessaTool.redactSecrets("PWD = \"s3cret\"").contains("s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void textWithoutASecretIsLeftAlone()
    {
        String plain = "Srvr=\"host\";Ref=\"base\";"; //$NON-NLS-1$

        assertEquals(plain, VanessaTool.redactSecrets(plain));
        assertEquals("", VanessaTool.redactSecrets("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRelativeFeaturePathIsResolvedAgainstTheProject()
    {
        File resolved = VanessaTool.resolveFeaturePath("features/smoke.feature", //$NON-NLS-1$
            dir.toFile());

        assertEquals(dir.toFile(), resolved.getParentFile().getParentFile());
        assertTrue(resolved.getPath().endsWith("smoke.feature")); //$NON-NLS-1$
    }

    @Test
    public void anAbsoluteFeaturePathIsTakenAsGiven()
    {
        File absolute = dir.resolve("elsewhere.feature").toFile(); //$NON-NLS-1$

        File resolved = VanessaTool.resolveFeaturePath(absolute.getAbsolutePath(),
            new File("C:\\some\\other\\place")); //$NON-NLS-1$

        assertEquals("an absolute path must not be joined to a working directory", //$NON-NLS-1$
            absolute, resolved);
    }

    @Test
    public void screenshotsComeBackSortedSoTheOrderIsStable() throws Exception
    {
        Files.write(dir.resolve("03.png"), new byte[] {1}); //$NON-NLS-1$
        Files.write(dir.resolve("01.png"), new byte[] {1}); //$NON-NLS-1$
        Files.write(dir.resolve("02.png"), new byte[] {1}); //$NON-NLS-1$
        Files.write(dir.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> shots = VanessaTool.collectScreenshots(dir.toFile());

        assertEquals("only the images count", 3, shots.size()); //$NON-NLS-1$
        assertTrue(shots.get(0).endsWith("01.png")); //$NON-NLS-1$
        assertTrue(shots.get(2).endsWith("03.png")); //$NON-NLS-1$
    }

    @Test
    public void aRunThatCapturedNothingHandsBackAnEmptyList()
    {
        assertTrue(VanessaTool.collectScreenshots(dir.toFile()).isEmpty());
        assertTrue("a directory that is not there is not an error either", //$NON-NLS-1$
            VanessaTool.collectScreenshots(dir.resolve("no-such-dir").toFile()).isEmpty()); //$NON-NLS-1$
    }
}
