/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * Vanessa has no JUnit parameters of its own. Asked for a machine readable result through the
 * documented Allure pair it writes one {@code <uuid>-result.json} per scenario and names the files
 * itself, so a caller looking for a single file of its own naming reads a finished run as one that
 * produced nothing.
 */
public class WhatVanessaActuallyWritesTest
{
    /** A scenario that failed on a step, as Vanessa writes it. */
    private static final String FAILED_ONE = "{\"name\":\"Снимок формы X\",\"status\":\"failed\"," //$NON-NLS-1$
        + "\"statusDetails\":{\"message\":\"Тип не определен\",\"trace\":\"...\"}," //$NON-NLS-1$
        + "\"steps\":[{\"name\":\"Когда Я открываю форму\",\"status\":\"skipped\"}]}"; //$NON-NLS-1$

    /** A scenario that passed. */
    private static final String PASSED_ONE = "{\"name\":\"Проба\",\"status\":\"passed\"}"; //$NON-NLS-1$

    /** A scenario that stopped on an error rather than an assertion. */
    private static final String BROKEN_ONE = "{\"name\":\"Сломанный\",\"status\":\"broken\"," //$NON-NLS-1$
        + "\"statusDetails\":{\"message\":\"нет соединения\"}}"; //$NON-NLS-1$

    /**
     * Every scenario of the run is counted, and a failure keeps the message the platform gave -
     * that message is the only thing that says why the run did not do what was asked.
     *
     * @throws IOException if the temporary run directory cannot be written
     */
    @Test
    public void everyScenarioIsCountedAndTheReasonSurvives() throws IOException
    {
        File dir = Files.createTempDirectory("aiedt-va").toFile(); //$NON-NLS-1$
        try
        {
            write(dir, "a" + AllureResultReader.RESULT_SUFFIX, FAILED_ONE); //$NON-NLS-1$
            write(dir, "b" + AllureResultReader.RESULT_SUFFIX, PASSED_ONE); //$NON-NLS-1$
            write(dir, "c" + AllureResultReader.RESULT_SUFFIX, BROKEN_ONE); //$NON-NLS-1$
            write(dir, "d-container.json", "{\"name\":\"не результат\"}"); //$NON-NLS-1$ //$NON-NLS-2$

            JUnitRunOutcome outcome = AllureResultReader.parse(dir);
            assertEquals(3, outcome.getTotal());
            assertEquals(1, outcome.getFailures());
            assertEquals(1, outcome.getErrors());
            assertEquals(1, outcome.getFailureDetails().size());
            assertEquals("Снимок формы X", outcome.getFailureDetails().get(0).name); //$NON-NLS-1$
            assertEquals("Тип не определен", outcome.getFailureDetails().get(0).message); //$NON-NLS-1$
            assertEquals("нет соединения", outcome.getErrorDetails().get(0).message); //$NON-NLS-1$
        }
        finally
        {
            remove(dir);
        }
    }

    /**
     * A directory Vanessa never wrote into reads as a run with no tests rather than as a failure
     * of its own: the caller decides what an empty run means.
     *
     * @throws IOException if the temporary directory cannot be written
     */
    @Test
    public void anEmptyDirectoryIsARunWithNoTests() throws IOException
    {
        File dir = Files.createTempDirectory("aiedt-va-empty").toFile(); //$NON-NLS-1$
        try
        {
            assertEquals(0, AllureResultReader.resultsIn(dir).length);
            assertEquals(0, AllureResultReader.parse(dir).getTotal());
            assertEquals(0, AllureResultReader.resultsIn(null).length);
        }
        finally
        {
            remove(dir);
        }
    }

    /**
     * Writes one file of the run.
     *
     * @param dir the run directory.
     * @param name the file name.
     * @param body its content.
     * @throws IOException if it cannot be written
     */
    private static void write(File dir, String name, String body) throws IOException
    {
        Files.write(new File(dir, name).toPath(), body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Removes the directory and what it holds.
     *
     * @param dir the directory.
     */
    private static void remove(File dir)
    {
        File[] kids = dir.listFiles();
        if (kids != null)
        {
            for (File kid : kids)
            {
                assertTrue(kid.delete());
            }
        }
        dir.delete();
    }
}
