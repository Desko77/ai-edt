/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The receipt a finished test run leaves on disk.
 * <p>
 * The run's outcome used to live only in the answer of the call, which a context compression
 * discards. The receipt is the copy that survives, so what is held down here is the bound that
 * keeps the directory from growing without end, the fidelity of the numbers and filters, and the
 * rule that a secret never reaches the file.
 * </p>
 */
public class RunReceiptTest
{
    private Path root;

    private Path dir;

    /**
     * Gives each test a receipt directory of its own.
     *
     * @throws IOException when the directory cannot be made
     */
    @Before
    public void createReceiptDirectory() throws IOException
    {
        root = Files.createTempDirectory("aiedt-receipts"); //$NON-NLS-1$
        dir = root.resolve("run-receipts").resolve("yaxunit_tests"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removes the directory and every receipt written into it.
     *
     * @throws IOException when it cannot be walked
     */
    @After
    public void removeReceiptDirectory() throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(root))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void theTwentyFirstReceiptRemovesTheOldest() throws IOException
    {
        Path first = null;
        for (int i = 0; i < RunReceipts.KEEP + 1; i++)
        {
            RunReceipts.Outcome written = RunReceipts.writeTo(dir, receiptFields(i));
            assertNull("write " + i + " failed: " + written.error, written.error); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(written.path);
            if (first == null)
            {
                first = written.path;
            }
        }

        List<Path> kept = receiptsOnDisk();
        assertEquals(RunReceipts.KEEP, kept.size());
        assertFalse("the oldest receipt is the one that leaves", Files.exists(first)); //$NON-NLS-1$
        for (Path path : kept)
        {
            assertFalse("a half-written receipt is not left behind: " + path, //$NON-NLS-1$
                path.getFileName().toString().endsWith(".tmp")); //$NON-NLS-1$
        }
    }

    @Test
    public void theNumbersAndFiltersAreTheOnesTheCallerPassed() throws IOException
    {
        RunReceipts.Outcome written = RunReceipts.writeTo(dir, receiptFields(7));
        assertNull(written.error);
        assertNotNull(written.path);

        JsonObject receipt = readReceipt(written.path);
        assertEquals("yaxunit_tests", receipt.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SSL_Demo", receipt.get("projectName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the receipt says when the run happened", receipt.has("at")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(17, receipt.get("total").getAsInt()); //$NON-NLS-1$
        assertEquals(15, receipt.get("passed").getAsInt()); //$NON-NLS-1$
        assertEquals(1, receipt.get("failures").getAsInt()); //$NON-NLS-1$
        assertEquals(1, receipt.get("errors").getAsInt()); //$NON-NLS-1$
        assertEquals(0, receipt.get("skipped").getAsInt()); //$NON-NLS-1$
        assertEquals("out/report-7.md", receipt.get("reportPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject filters = receipt.getAsJsonObject("filters"); //$NON-NLS-1$
        assertNotNull(filters);
        assertEquals("run", filters.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Ext7", filters.get("extensions").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Module7", filters.get("modules").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Module7.Test", filters.get("tests").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aVanessaReceiptCarriesItsScreenshots() throws IOException
    {
        Map<String, Object> fields = receiptFields(3);
        fields.put("tool", "vanessa"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> shots = new ArrayList<>();
        shots.add("out/shots/step-1.png"); //$NON-NLS-1$
        shots.add("out/shots/step-2.png"); //$NON-NLS-1$
        fields.put("screenshots", shots); //$NON-NLS-1$

        RunReceipts.Outcome written = RunReceipts.writeTo(dir, fields);
        assertNull(written.error);

        JsonObject receipt = readReceipt(written.path);
        assertEquals("vanessa", receipt.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, receipt.getAsJsonArray("screenshots").size()); //$NON-NLS-1$
        assertEquals("out/shots/step-1.png", //$NON-NLS-1$
            receipt.getAsJsonArray("screenshots").get(0).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aSecretKeyNeverReachesTheFile() throws IOException
    {
        Map<String, Object> fields = receiptFields(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>)fields.get("filters"); //$NON-NLS-1$
        filters.put("password", "s3cret"); //$NON-NLS-1$ //$NON-NLS-2$
        filters.put("scenarioText", "И я ввожу пароль"); //$NON-NLS-1$ //$NON-NLS-2$
        fields.put("password", "s3cret"); //$NON-NLS-1$ //$NON-NLS-2$

        RunReceipts.Outcome written = RunReceipts.writeTo(dir, fields);
        assertNull(written.error);

        String text = new String(Files.readAllBytes(written.path), StandardCharsets.UTF_8);
        assertFalse("a password value must not survive into the file", text.contains("s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the scenario text must not survive into the file", //$NON-NLS-1$
            text.contains("scenarioText")); //$NON-NLS-1$
        JsonObject receipt = readReceipt(written.path);
        assertFalse(receipt.has("password")); //$NON-NLS-1$
        assertFalse(receipt.getAsJsonObject("filters").has("password")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an honest filter still passes through", //$NON-NLS-1$
            receipt.getAsJsonObject("filters").has("extensions")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnwritableDirectoryAnswersAnErrorAndThrowsNothing() throws IOException
    {
        // A regular file standing where the receipt directory would be made: creating the
        // directory fails on every platform, which a read-only flag does not guarantee.
        Path blocker = root.resolve("blocked"); //$NON-NLS-1$
        Files.write(blocker, new byte[] { 1 });

        RunReceipts.Outcome written = RunReceipts.writeTo(blocker, receiptFields(0));

        assertNull(written.path);
        assertNotNull("the failure is handed back as text, not thrown", written.error); //$NON-NLS-1$
    }

    /**
     * Lists the receipt files of the test directory, oldest first.
     *
     * @return the {@code .json} files, sorted by name
     * @throws IOException when the directory cannot be listed
     */
    private List<Path> receiptsOnDisk() throws IOException
    {
        List<Path> receipts = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir))
        {
            entries.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(receipts::add); //$NON-NLS-1$
        }
        receipts.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return receipts;
    }

    /**
     * Reads a receipt back as an object.
     *
     * @param path the file the write returned
     * @return the parsed document
     * @throws IOException when the file cannot be read
     */
    private static JsonObject readReceipt(Path path) throws IOException
    {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /**
     * The fields of one receipt, in the shape the run-mode YAxUnit runner files them.
     *
     * @param seed makes the values of one call differ from the values of another
     * @return the receipt fields
     */
    private static Map<String, Object> receiptFields(int seed)
    {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("mode", "run"); //$NON-NLS-1$ //$NON-NLS-2$
        filters.put("extensions", "Ext" + seed); //$NON-NLS-1$ //$NON-NLS-2$
        filters.put("modules", "Module" + seed); //$NON-NLS-1$ //$NON-NLS-2$
        filters.put("tests", "Module" + seed + ".Test"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", "yaxunit_tests"); //$NON-NLS-1$ //$NON-NLS-2$
        fields.put("projectName", "SSL_Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        fields.put("filters", filters); //$NON-NLS-1$
        fields.put("total", Integer.valueOf(10 + seed)); //$NON-NLS-1$
        fields.put("passed", Integer.valueOf(8 + seed)); //$NON-NLS-1$
        fields.put("failures", Integer.valueOf(1)); //$NON-NLS-1$
        fields.put("errors", Integer.valueOf(1)); //$NON-NLS-1$
        fields.put("skipped", Integer.valueOf(0)); //$NON-NLS-1$
        fields.put("reportPath", "out/report-" + seed + ".md"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return fields;
    }
}
