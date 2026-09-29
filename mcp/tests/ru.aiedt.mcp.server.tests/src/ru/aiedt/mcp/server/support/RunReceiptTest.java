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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    /**
     * The directory keeps the newest receipts and drops the oldest once the keep limit is passed.
     *
     * @throws IOException when a receipt cannot be written or listed
     */
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

    /**
     * The receipt stores the counters, the filters and the report path the caller filed.
     *
     * @throws IOException when the receipt cannot be written or read
     */
    @Test
    public void theNumbersAndFiltersAreTheOnesTheCallerPassed() throws IOException
    {
        RunReceipts.Outcome written = RunReceipts.writeTo(dir, receiptFields(7));
        assertNull(written.error);
        assertNotNull(written.path);

        JsonObject receipt = readReceipt(written.path);
        assertEquals("yaxunit_tests", receipt.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DemoProject", receipt.get("projectName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
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

    /**
     * A Vanessa receipt keeps the screenshot paths the run produced.
     *
     * @throws IOException when the receipt cannot be written or read
     */
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

    /**
     * A password or a scenario text filed at the top or one level down is left out of the file.
     *
     * @throws IOException when the receipt cannot be written or read
     */
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

    /**
     * A directory that cannot be created comes back as text on the outcome and is not thrown.
     *
     * @throws IOException when the blocking file cannot be written
     */
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
     * Resolving the directory is part of the write. A lookup that throws, the way
     * {@code getStateLocation} does when the plugin is stopping, becomes the outcome's error.
     */
    @Test
    public void aDirectoryThatCannotBeResolvedBecomesAReceiptError()
    {
        RunReceipts.Outcome written = RunReceipts.writeResolving(receiptFields(0), tool -> {
            throw new IllegalStateException("plugin state is gone"); //$NON-NLS-1$
        });

        assertNull(written.path);
        assertNotNull(written.error);
        assertTrue(written.error, written.error.contains("plugin state is gone")); //$NON-NLS-1$
    }

    /**
     * A secret key is dropped however deep it sits: a map three levels down, and a map inside a
     * list. An honest field beside it is kept.
     *
     * @throws IOException when the receipt cannot be written or read
     */
    @Test
    public void aSecretNestedThreeDeepOrInsideAListNeverReachesTheFile() throws IOException
    {
        Map<String, Object> fields = receiptFields(0);
        Map<String, Object> level3 = new LinkedHashMap<>();
        level3.put("password", "deep-secret"); //$NON-NLS-1$ //$NON-NLS-2$
        level3.put("kept", "visible"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> level2 = new LinkedHashMap<>();
        level2.put("inner", level3); //$NON-NLS-1$
        Map<String, Object> level1 = new LinkedHashMap<>();
        level1.put("nested", level2); //$NON-NLS-1$
        fields.put("context", level1); //$NON-NLS-1$

        Map<String, Object> inList = new LinkedHashMap<>();
        inList.put("pwd", "list-secret"); //$NON-NLS-1$ //$NON-NLS-2$
        inList.put("name", "step"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> steps = new ArrayList<>();
        steps.add(inList);
        fields.put("steps", steps); //$NON-NLS-1$

        Map<String, Object> inArray = new LinkedHashMap<>();
        inArray.put("scenarioText", "array-secret"); //$NON-NLS-1$ //$NON-NLS-2$
        fields.put("batch", new Object[] { inArray }); //$NON-NLS-1$

        RunReceipts.Outcome written = RunReceipts.writeTo(dir, fields);
        assertNull(written.error);

        String text = new String(Files.readAllBytes(written.path), StandardCharsets.UTF_8);
        assertFalse(text, text.contains("deep-secret")); //$NON-NLS-1$
        assertFalse(text, text.contains("list-secret")); //$NON-NLS-1$
        assertFalse(text, text.contains("array-secret")); //$NON-NLS-1$
        assertTrue(text, text.contains("visible")); //$NON-NLS-1$
        assertTrue(text, text.contains("step")); //$NON-NLS-1$
    }

    /**
     * A prune that throws after the file has been moved still returns the file. The cleanup
     * error is not a failed write.
     *
     * @throws IOException when the receipt cannot be written
     */
    @Test
    public void aPruneFailureAfterTheFileIsMovedStillNamesTheReceipt() throws IOException
    {
        RunReceipts.Outcome written = RunReceipts.writeTo(dir, receiptFields(1), () -> {
            throw new IOException("prune failed"); //$NON-NLS-1$
        });

        assertNull(written.error);
        assertNotNull(written.path);
        assertTrue(Files.exists(written.path));
        String text = new String(Files.readAllBytes(written.path), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("DemoProject")); //$NON-NLS-1$
    }

    /**
     * Writes that run together each land in their own file. A shared clock is not a shared name.
     *
     * @throws Exception when a writer fails or the directory cannot be listed
     */
    @Test
    public void concurrentWritesEachLeaveTheirOwnFile() throws Exception
    {
        int writers = 12;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try
        {
            List<Future<RunReceipts.Outcome>> tasks = new ArrayList<>();
            for (int i = 0; i < writers; i++)
            {
                int seed = i;
                tasks.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return RunReceipts.writeTo(dir, receiptFields(seed));
                }));
            }
            for (Future<RunReceipts.Outcome> task : tasks)
            {
                RunReceipts.Outcome written = task.get(30, TimeUnit.SECONDS);
                assertNull(written.error);
                assertNotNull(written.path);
            }
        }
        finally
        {
            pool.shutdownNow();
        }

        List<Path> kept = receiptsOnDisk();
        assertEquals(writers, kept.size());
        StringBuilder all = new StringBuilder();
        for (Path path : kept)
        {
            all.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        }
        for (int i = 0; i < writers; i++)
        {
            assertTrue("receipt " + i + " was overwritten", //$NON-NLS-1$ //$NON-NLS-2$
                all.indexOf("out/report-" + i + ".md") >= 0); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A file that will not delete is left, and the next oldest goes, until the directory itself
     * holds the keep limit.
     *
     * @throws IOException when the stand-in receipts cannot be written or listed
     */
    @Test
    public void aReceiptThatWillNotDeleteIsSkippedAndTheNextOldestGoes() throws IOException
    {
        Files.createDirectories(dir);
        List<Path> created = new ArrayList<>();
        for (int i = 0; i < RunReceipts.KEEP + 2; i++)
        {
            Path file = dir.resolve(String.format(Locale.ROOT, "%013d.json", Integer.valueOf(i))); //$NON-NLS-1$
            Files.write(file, new byte[] { '{' });
            created.add(file);
        }
        Path oldest = created.get(0);
        Path next = created.get(1);
        Path afterNext = created.get(2);

        RunReceipts.prune(dir, path -> {
            if (path.getFileName().equals(oldest.getFileName()))
            {
                throw new IOException("locked"); //$NON-NLS-1$
            }
            Files.deleteIfExists(path);
        });

        List<Path> kept = receiptsOnDisk();
        assertEquals(RunReceipts.KEEP, kept.size());
        assertTrue("the file that would not delete stays", Files.exists(oldest)); //$NON-NLS-1$
        assertFalse(Files.exists(next));
        assertFalse(Files.exists(afterNext));
    }

    /**
     * The window snapshots a timeout left in the receipt directory are trimmed with the receipts:
     * the same limit applies, the newest by modification time stay, and a picture that is not a
     * snapshot is left where it is.
     *
     * @throws IOException when the stand-in files cannot be written or listed
     */
    @Test
    public void snapshotsArePrunedWithTheReceipts() throws IOException
    {
        Files.createDirectories(dir);
        long written = 1_600_000_000_000L;
        List<Path> snapshots = new ArrayList<>();
        for (int i = 0; i < RunReceipts.KEEP + 3; i++)
        {
            // The name carries the process and the read order, so it does not sort the way the
            // snapshots were taken: the modification time is what orders them.
            Path snapshot = dir.resolve(String.format(Locale.ROOT, "blocking-%d-0.png", Integer.valueOf(i))); //$NON-NLS-1$
            Files.write(snapshot, new byte[] { 1 });
            Files.setLastModifiedTime(snapshot, FileTime.fromMillis(written + i * 1000L));
            snapshots.add(snapshot);
        }
        Path notASnapshot = dir.resolve("run-report.png"); //$NON-NLS-1$
        Files.write(notASnapshot, new byte[] { 1 });
        Files.setLastModifiedTime(notASnapshot, FileTime.fromMillis(written));
        for (int i = 0; i < RunReceipts.KEEP + 3; i++)
        {
            Files.write(dir.resolve(String.format(Locale.ROOT, "%013d.json", Integer.valueOf(i))), //$NON-NLS-1$
                new byte[] { '{' });
        }

        RunReceipts.Outcome outcome = RunReceipts.writeTo(dir, receiptFields(99));
        assertNull(outcome.error);

        List<Path> kept = snapshotsOnDisk();
        assertEquals(RunReceipts.KEEP, kept.size());
        assertFalse("the least recently written snapshot goes", Files.exists(snapshots.get(0))); //$NON-NLS-1$
        assertFalse(Files.exists(snapshots.get(2)));
        assertTrue("the newest snapshot stays", Files.exists(snapshots.get(snapshots.size() - 1))); //$NON-NLS-1$
        assertTrue("a picture that is not a snapshot is left alone", Files.exists(notASnapshot)); //$NON-NLS-1$
    }

    /**
     * Lists the window snapshots of the test directory.
     *
     * @return the {@code blocking-*.png} files, sorted by name
     * @throws IOException when the directory cannot be listed
     */
    private List<Path> snapshotsOnDisk() throws IOException
    {
        List<Path> snapshots = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir))
        {
            entries.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("blocking-") && name.endsWith(".png"); //$NON-NLS-1$ //$NON-NLS-2$
            }).forEach(snapshots::add);
        }
        snapshots.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return snapshots;
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
        fields.put("projectName", "DemoProject"); //$NON-NLS-1$ //$NON-NLS-2$
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
