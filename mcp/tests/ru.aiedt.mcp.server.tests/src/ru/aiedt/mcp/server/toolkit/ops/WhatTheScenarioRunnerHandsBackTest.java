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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.wire.ToolResult;

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

    /**
     * A run read from Vanessa's own result files names those files, not a JUnit report.
     * <p>
     * Measured: the tool asks Vanessa for an Allure result and never for a JUnit one, yet every
     * answer named {@code out/junit.xml} - a path nothing wrote. A caller that opened it found
     * nothing where the run's results were said to be.
     *
     * @throws Exception when the stand-in result files cannot be written
     */
    @Test
    public void aRunReadFromVanessasOwnFilesNamesThoseFiles() throws Exception
    {
        Files.write(dir.resolve("0f8c-result.json"), "{}".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(dir.resolve("1a2b-result.json"), "{}".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        File junitNotWritten = dir.resolve("junit.xml").toFile(); //$NON-NLS-1$

        String json = VanessaTool.withProducedPaths(ToolResult.success(), dir.toFile(),
            junitNotWritten, true).toJson();

        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(dir.toFile().getAbsolutePath(), answer.get("resultsDir").getAsString()); //$NON-NLS-1$
        assertEquals("the names Vanessa chose, not the one the tool invented", 2, //$NON-NLS-1$
            answer.getAsJsonArray("resultFiles").size()); //$NON-NLS-1$
        assertTrue(json, json.contains("0f8c-result.json") && json.contains("1a2b-result.json")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no file is named where none was read: " + json, json.contains("junitXmlPath")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A run read from a JUnit report - the path a caller-supplied {@code vanessaParams} document
     * takes by ordering JUnit output itself - names that report, and no Allure directory.
     *
     * @throws Exception when the stand-in report cannot be written
     */
    @Test
    public void aRunReadFromAJUnitReportNamesThatReport() throws Exception
    {
        File junit = dir.resolve("junit.xml").toFile(); //$NON-NLS-1$
        Files.write(junit.toPath(), "<testsuites/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String json = VanessaTool.withProducedPaths(ToolResult.success(), dir.toFile(), junit,
            false).toJson();

        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(junit.getAbsolutePath(), answer.get("junitXmlPath").getAsString()); //$NON-NLS-1$
        assertFalse("the Allure fields belong to the other branch: " + json, //$NON-NLS-1$
            json.contains("resultsDir")); //$NON-NLS-1$
    }
}
