/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A scenario composed by the caller, played without a file the caller could not have placed.
 * <p>
 * {@code featurePath} needs a file that already exists on the machine. A caller reaching this
 * server over MCP cannot put one there, so it could run only scenarios somebody had written by
 * hand - which is what kept a form snapshot in a running 1C out of reach: composing three lines of
 * Gherkin is easy, getting them onto the machine was not.
 * </p>
 */
public class AScenarioNeedsNoFileOnDiskTest
{
    private Path dir;

    @Before
    public void makeADirectory() throws Exception
    {
        dir = Files.createTempDirectory("vanessa-scenario-test"); //$NON-NLS-1$
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
    public void namingBothWaysIsRefusedRatherThanResolvedByPrecedence()
    {
        String why = VanessaTool.whyTheScenarioIsNotNamed(true, true);

        assertNotNull("nothing in the call says which was meant", why); //$NON-NLS-1$
        assertTrue(why, why.contains("featurePath") && why.contains("scenarioText")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void namingNeitherWayIsRefusedAndBothWaysAreOffered()
    {
        String why = VanessaTool.whyTheScenarioIsNotNamed(false, false);

        assertNotNull(why);
        assertTrue("a caller that cannot write a file has to be told the other way exists: " + why, //$NON-NLS-1$
            why.contains("scenarioText")); //$NON-NLS-1$
    }

    @Test
    public void eitherWayOnItsOwnGoesThrough()
    {
        assertNull(VanessaTool.whyTheScenarioIsNotNamed(true, false));
        assertNull(VanessaTool.whyTheScenarioIsNotNamed(false, true));
    }

    @Test
    public void aNamedFileIsPlayedAsItIs() throws Exception
    {
        File named = new File(dir.toFile(), "given.feature"); //$NON-NLS-1$
        Files.write(named.toPath(), "#language: ru\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        File playing = VanessaTool.scenarioFileFor(dir.toFile(), named);

        assertEquals("a file the caller named is not rewritten", named, playing); //$NON-NLS-1$
    }

    @Test
    public void composedTextBecomesAFileInTheRunsOwnDirectory() throws Exception
    {
        String scenario = "#language: ru\n\nФункционал: Снимок формы\n\nСценарий: Открыть\n" //$NON-NLS-1$
            + "    Когда Я открываю общую форму \"ИИА_Агент\"\n"; //$NON-NLS-1$

        File playing = VanessaTool.scenarioFileFor(dir.toFile(), null);
        assertEquals("the place is named before anything is written, so a write that fails " //$NON-NLS-1$
            + "halfway still leaves something to remove", dir.toFile(), playing.getParentFile()); //$NON-NLS-1$
        Files.write(playing.toPath(), scenario.getBytes(StandardCharsets.UTF_8));

        assertTrue("the run plays a file, so the text has to become one", playing.isFile()); //$NON-NLS-1$
        assertEquals("it belongs to this run, beside its report and its screenshots", //$NON-NLS-1$
            dir.toFile(), playing.getParentFile());
        String written = new String(Files.readAllBytes(playing.toPath()), StandardCharsets.UTF_8);
        assertTrue("the scenario reaches the file whole: " + written, //$NON-NLS-1$
            written.contains("Я открываю общую форму")); //$NON-NLS-1$
    }

    @Test
    public void aLaunchThatNeverHappenedTakesItsScenarioAway() throws Exception
    {
        File composed = new File(dir.toFile(), "never-launched.feature"); //$NON-NLS-1$
        Files.write(composed.toPath(), "#language: ru".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String stillHere = VanessaTool.scenarioAfterFailure(false, null, composed);

        assertNull("nobody is reading it, so it does not stay: " + stillHere, stillHere); //$NON-NLS-1$
        assertTrue("a missing executable throws before a process exists, and the scenario - " //$NON-NLS-1$
            + "which may carry a password - must not be left in the run directory", //$NON-NLS-1$
            !composed.exists());
    }

    @Test
    public void aLaunchedClientKeepsItsScenarioAndIsSaidTo() throws Exception
    {
        File composed = new File(dir.toFile(), "being-read.feature"); //$NON-NLS-1$
        Files.write(composed.toPath(), "#language: ru".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String stillHere = VanessaTool.scenarioAfterFailure(true, null, composed);

        assertEquals("a client already started may be reading it", //$NON-NLS-1$
            composed.getAbsolutePath(), stillHere);
        assertTrue("so it stays where it is", composed.isFile()); //$NON-NLS-1$
    }

    @Test
    public void aRemovalAlreadyReportedIsNotContradicted() throws Exception
    {
        assertEquals("what an earlier removal said stands", "C:/somewhere/left.feature", //$NON-NLS-1$ //$NON-NLS-2$
            VanessaTool.scenarioAfterFailure(true, "C:/somewhere/left.feature", null)); //$NON-NLS-1$
        assertNull("and a caller who named their own file has nothing composed to answer for", //$NON-NLS-1$
            VanessaTool.scenarioAfterFailure(true, null, null));
    }

    @Test
    public void aFileThatWouldNotGoIsNamedRatherThanForgotten() throws Exception
    {
        File composed = new File(dir.toFile(), "composed.feature"); //$NON-NLS-1$
        Files.write(composed.toPath(), "#language: ru".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertNull("a file that goes is not reported", VanessaTool.removeComposed(composed)); //$NON-NLS-1$
        assertTrue("and it is actually gone", !composed.exists()); //$NON-NLS-1$
        assertNull("nothing to remove is not a failure to remove", //$NON-NLS-1$
            VanessaTool.removeComposed(null));
        assertNull("neither is a file that was never there", //$NON-NLS-1$
            VanessaTool.removeComposed(new File(dir.toFile(), "never.feature"))); //$NON-NLS-1$
    }
}
