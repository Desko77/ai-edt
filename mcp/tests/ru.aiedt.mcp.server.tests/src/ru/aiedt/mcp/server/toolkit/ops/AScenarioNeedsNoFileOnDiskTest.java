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

        File playing = VanessaTool.scenarioFileFor(dir.toFile(), named, null);

        assertEquals("a file the caller named is not rewritten", named, playing); //$NON-NLS-1$
    }

    @Test
    public void composedTextBecomesAFileInTheRunsOwnDirectory() throws Exception
    {
        String scenario = "#language: ru\n\nФункционал: Снимок формы\n\nСценарий: Открыть\n" //$NON-NLS-1$
            + "    Когда Я открываю общую форму \"ИИА_Агент\"\n"; //$NON-NLS-1$

        File playing = VanessaTool.scenarioFileFor(dir.toFile(), null, scenario);

        assertTrue("the run plays a file, so the text has to become one", playing.isFile()); //$NON-NLS-1$
        assertEquals("it belongs to this run, beside its report and its screenshots", //$NON-NLS-1$
            dir.toFile(), playing.getParentFile());
        String written = new String(Files.readAllBytes(playing.toPath()), StandardCharsets.UTF_8);
        assertTrue("the scenario reaches the file whole: " + written, //$NON-NLS-1$
            written.contains("Я открываю общую форму")); //$NON-NLS-1$
        assertTrue("1C reads a feature file by its byte order mark", //$NON-NLS-1$
            written.startsWith("﻿") || written.startsWith("#language")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
