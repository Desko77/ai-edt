/**
 * AI-EDT - 1C AI tools for EDT
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.RunReceipts;

/**
 * What a run answers when the call named {@code tests} or {@code modules}.
 * <p>
 * A name the filter was given and the report does not carry is the one thing a green report cannot
 * show: the run happened, every test it ran passed, and the tests that were asked for are not among
 * them. The answer says so rather than passing, and it carries the numbers either way.
 * </p>
 */
public class ANameTheReportDoesNotCarryTest
{
    private final List<Path> written = new ArrayList<>();

    @After
    public void removeTheReportDirectories() throws Exception
    {
        for (Path directory : written)
        {
            deleteTree(directory);
        }
    }

    /**
     * A named test the report does not carry turns the answer into a discrepancy, even though every
     * test the report holds passed.
     *
     * @throws Exception when the report cannot be written
     */
    @Test
    public void aNamedTestTheReportDoesNotCarryIsADiscrepancy() throws Exception
    {
        JsonObject answer = answerFor(null, "МодульТестов.Тест_Сумма,МодульТестов.Тест_Опоздал"); //$NON-NLS-1$

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = answer.get("error").getAsString();
        assertTrue("the missing name is called out: " + error, //$NON-NLS-1$
            error.contains("МодульТестов.Тест_Опоздал")); //$NON-NLS-1$
        assertFalse("and the name that did run is not called missing: " + error, //$NON-NLS-1$
            error.contains("Тест_Сумма")); //$NON-NLS-1$
        assertTrue("the words say what a missing name means: " + error, //$NON-NLS-1$
            error.contains("not registered in the module")); //$NON-NLS-1$
        assertTrue("the words say it was not found: " + error, error.contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, answer.get("namedInCall").getAsInt()); //$NON-NLS-1$
        assertEquals(1, answer.get("foundInReport").getAsInt()); //$NON-NLS-1$
        assertEquals(2, answer.get("testsInReport").getAsInt()); //$NON-NLS-1$
        JsonArray missing = answer.get("missingInReport").getAsJsonArray(); //$NON-NLS-1$
        assertEquals(1, missing.size());
        assertEquals("МодульТестов.Тест_Опоздал", missing.get(0).getAsString()); //$NON-NLS-1$
        // The report of the run is still the report of the run: its counters stand beside the
        // discrepancy, and the markdown the caller reads repeats it.
        assertEquals(2, answer.get("total").getAsInt()); //$NON-NLS-1$
        assertEquals(2, answer.get("passed").getAsInt()); //$NON-NLS-1$
        assertTrue(answer.get("output").getAsString(), //$NON-NLS-1$
            answer.get("output").getAsString().contains("МодульТестов.Тест_Опоздал")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Every name the call gave being in the report leaves the answer a success, with the numbers.
     *
     * @throws Exception when the report cannot be written
     */
    @Test
    public void everyNamedTestInTheReportPassesWithItsNumbers() throws Exception
    {
        JsonObject answer = answerFor(null, "МодульТестов.Тест_Сумма"); //$NON-NLS-1$

        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.has("error")); //$NON-NLS-1$
        assertEquals(1, answer.get("namedInCall").getAsInt()); //$NON-NLS-1$
        assertEquals(1, answer.get("foundInReport").getAsInt()); //$NON-NLS-1$
        assertEquals(2, answer.get("testsInReport").getAsInt()); //$NON-NLS-1$
        assertFalse(answer.has("missingInReport")); //$NON-NLS-1$
    }

    /**
     * A module filter is answered for the same way, by the suite name the report wrote and by the
     * class name beside the tests; the case of a name does not decide it.
     *
     * @throws Exception when the report cannot be written
     */
    @Test
    public void aNamedModuleTheReportDoesNotCarryIsADiscrepancy() throws Exception
    {
        JsonObject absent = answerFor("МодульТестов,МодульДругой", null); //$NON-NLS-1$

        assertFalse(absent.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(absent.get("error").getAsString(), //$NON-NLS-1$
            absent.get("error").getAsString().contains("МодульДругой")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, absent.get("namedInCall").getAsInt()); //$NON-NLS-1$
        assertEquals(1, absent.get("foundInReport").getAsInt()); //$NON-NLS-1$

        JsonObject found = answerFor("модультестов", null); //$NON-NLS-1$
        assertTrue(found.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, found.get("namedInCall").getAsInt()); //$NON-NLS-1$
        assertEquals(1, found.get("foundInReport").getAsInt()); //$NON-NLS-1$
    }

    /**
     * A call that named no test and no module is not reconciled, and still learns how many tests
     * the report carries.
     *
     * @throws Exception when the report cannot be written
     */
    @Test
    public void aCallThatNamedNothingIsNotReconciled() throws Exception
    {
        JsonObject answer = answerFor(null, null);

        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, answer.get("testsInReport").getAsInt()); //$NON-NLS-1$
        assertFalse(answer.has("namedInCall")); //$NON-NLS-1$
        assertFalse(answer.has("foundInReport")); //$NON-NLS-1$
        assertFalse(answer.has("missingInReport")); //$NON-NLS-1$
    }

    /**
     * Runs one delivered launch answer over a report of two passing tests in one module.
     *
     * @param modules the {@code modules} filter, or <code>null</code> when the call named none
     * @param tests the {@code tests} filter, or <code>null</code> when the call named none
     * @return the parsed answer
     * @throws Exception when the report or the receipt cannot be written
     */
    private JsonObject answerFor(String modules, String tests) throws Exception
    {
        Path root = Files.createTempDirectory("yaxunit-named"); //$NON-NLS-1$
        written.add(root);
        Path report = root.resolve("junit.xml"); //$NON-NLS-1$
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //$NON-NLS-1$
            + "<testsuite name=\"МодульТестов\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">" //$NON-NLS-1$
            + "<testcase name=\"Тест_Сумма\" classname=\"МодульТестов\"/>" //$NON-NLS-1$
            + "<testcase name=\"Тест_Разность\" classname=\"МодульТестов\"/>" //$NON-NLS-1$
            + "</testsuite>"; //$NON-NLS-1$
        Files.write(report, xml.getBytes(StandardCharsets.UTF_8));

        String runKey = "named-" + System.nanoTime(); //$NON-NLS-1$
        Path receipts = root.resolve("receipts"); //$NON-NLS-1$
        YaxunitTestRunner.noteUndelivered(runKey);
        try
        {
            YaxunitTestRunner.RunContext context =
                new YaxunitTestRunner.RunContext("Proj", null, modules, tests, false); //$NON-NLS-1$
            File junitXml = report.toFile();
            return JsonParser.parseString(YaxunitTestRunner.handOverFinishedLaunch(runKey, junitXml,
                context, fields -> RunReceipts.writeTo(receipts, fields))).getAsJsonObject();
        }
        finally
        {
            YaxunitTestRunner.forgetUndelivered(runKey);
        }
    }

    /**
     * @param directory the directory to remove
     * @throws Exception when a path cannot be deleted
     */
    private static void deleteTree(Path directory) throws Exception
    {
        if (directory == null || !Files.exists(directory))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
