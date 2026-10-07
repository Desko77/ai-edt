/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.RunReceipts;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unit tests for {@link YaxunitTestRunner}.
 * <p>
 * Covers tool metadata, the schema contents, the two required-parameter gates at the top of
 * {@code execute} (projectName and applicationId, which fire <em>before</em> the launch manager is
 * touched), and the decisions taken before a launch: the pre-launch infobase update, which report
 * answers a call, and which filter arguments the tool applies. The launch and polling flow itself
 * needs a running Eclipse debug runtime and is not exercised here; the decisions above are reached
 * through the seams they are made in, so what production passes at those seams is read, not proved.
 * </p>
 */
public class YaxunitTestRunnerTest
{
    private static YaxunitTestRunner newTool()
    {
        return new YaxunitTestRunner();
    }

    private static Map<String, String> params(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void nameIsRunYaxunitTests()
    {
        assertEquals("run_yaxunit_tests", newTool().getName());
    }

    @Test
    public void descriptionIsNonBlank()
    {
        String description = newTool().getDescription();
        assertNotNull(description);
        assertTrue(description.length() > 0);
    }

    /**
     * The alias declares JSON, the same type it answers on a refusal, a pending run and a result.
     */
    @Test
    public void responseTypeIsJson()
    {
        assertEquals(IMcpTool.ResponseType.JSON, newTool().getResponseType());
    }

    @Test
    public void schemaListsEveryFilterParameter()
    {
        String schema = newTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema should declare launchConfigurationName",
            schema.contains("\"launchConfigurationName\""));
        assertTrue("schema should declare projectName", schema.contains("\"projectName\""));
        assertTrue("schema should declare applicationId", schema.contains("\"applicationId\""));
        assertTrue("schema should declare extensions", schema.contains("\"extensions\""));
        assertTrue("schema should declare modules", schema.contains("\"modules\""));
        assertTrue("schema should declare tests", schema.contains("\"tests\""));
        assertTrue("schema should declare timeoutSeconds", schema.contains("\"timeoutSeconds\""));
    }

    @Test
    public void executeWithoutProjectNameReportsItAsRequired()
    {
        // applicationId alone (no launch config, no projectName) trips the projectName gate.
        String result = newTool().execute(params("applicationId", "app-1"));
        assertNotNull(result);
        assertTrue(result.contains("projectName"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void executeWithoutApplicationIdReportsItAsRequired()
    {
        // projectName alone (no launch config, no applicationId) trips the applicationId gate.
        String result = newTool().execute(params("projectName", "Proj"));
        assertNotNull(result);
        assertTrue(result.contains("applicationId"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void executeWithNoArgumentsAtAllReportsError()
    {
        String result = newTool().execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }

    @Test
    public void schemaDeclaresTheUpdateAndTheCacheFlags()
    {
        String schema = newTool().getInputSchema();
        assertTrue("schema should declare updateBeforeLaunch",
            schema.contains("\"updateBeforeLaunch\""));
        assertTrue("schema should declare reuseRecent", schema.contains("\"reuseRecent\""));
        String description = newTool().getDescription();
        assertTrue("the description says the infobase is updated first",
            description.contains("updated before the launch"));
        assertTrue("and says a report already handed over is not reused",
            description.contains("reuseRecent=true"));
    }

    /**
     * The update runs before the launch, and only when the call asked for it.
     * <p>
     * The step is a parameter so its decision, its default and the sentence it produces can be
     * exercised without an infobase: the launch path itself needs a running EDT.
     */
    @Test
    public void theInfobaseIsUpdatedBeforeTheLaunchUnlessTheCallTurnsItOff()
    {
        List<String> asked = new ArrayList<>();
        String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(
            DebugSessionStarter.launchUpdate(Boolean.TRUE, true), "Proj", "app-1",
            (project, application) -> {
                asked.add(project + "/" + application); //$NON-NLS-1$
                return anInfobaseAlreadyUpToDate();
            });

        assertNull("an infobase already up to date lets the launch go on: " + refusal, refusal);
        assertEquals("the step runs once, for this project and application", 1, asked.size());
        assertEquals("Proj/app-1", asked.get(0));

        String notAsked = YaxunitTestRunner.preLaunchUpdateRefusal(
            DebugSessionStarter.launchUpdate(Boolean.FALSE, true), "Proj", "app-1",
            (project, application) -> {
                asked.add("called anyway"); //$NON-NLS-1$
                return ApplicationUpdater.Result.failed("boom"); //$NON-NLS-1$
            });

        assertNull("updateBeforeLaunch=false is not a refusal", notAsked);
        assertEquals("the step must not run at all", 1, asked.size());
    }

    /**
     * An update that did not finish refuses the launch, names what stopped it, and names the way
     * out: the tests do not start, and the answer says how to start them anyway.
     */
    @Test
    public void anUpdateThatDidNotFinishRefusesTheLaunchAndNamesTheWayOut()
    {
        String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(
            DebugSessionStarter.launchUpdate(Boolean.TRUE, true), "Proj", "app-1",
            (project, application) -> ApplicationUpdater.Result.failed("the load stopped at row 40")); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("the load stopped at row 40")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("FAILED")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was launched")); //$NON-NLS-1$
        assertTrue("the caller is told how to launch against the infobase as it stands: " + refusal, //$NON-NLS-1$
            refusal.contains("updateBeforeLaunch=false")); //$NON-NLS-1$
    }

    /**
     * The two flags default the way the schema says they do.
     */
    @Test
    public void theUpdateDefaultsToOnAndTheCacheDefaultsToOff()
    {
        assertTrue("the infobase is updated when the call does not say", //$NON-NLS-1$
            YaxunitTestRunner.updateBeforeLaunch(new HashMap<String, String>()).asks);
        assertTrue(YaxunitTestRunner.updateBeforeLaunch(
            params("updateBeforeLaunch", "true")).asks); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(YaxunitTestRunner.updateBeforeLaunch(
            params("updateBeforeLaunch", "false")).asks); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a second call runs the tests again unless it asks for the report", //$NON-NLS-1$
            YaxunitTestRunner.reuseRecent(new HashMap<String, String>()));
        assertTrue(YaxunitTestRunner.reuseRecent(params("reuseRecent", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(YaxunitTestRunner.reuseRecent(params("reuseRecent", "false"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Which report answers a call: the pickup of a run whose result was never handed over, and the
     * recent report a call asked for - never the report of a call that already got one.
     */
    @Test
    public void aReportAnswersOnlyTheRunThatIsStillWaitingForIt()
    {
        assertTrue("the pickup of a run started here is answered from its report", //$NON-NLS-1$
            YaxunitTestRunner.servesFromCache(true, false, true, false));
        assertTrue(YaxunitTestRunner.servesFromCache(true, true, false, true));
        assertFalse("a report already handed over does not answer a new call", //$NON-NLS-1$
            YaxunitTestRunner.servesFromCache(true, true, false, false));
        assertFalse("asked for, but older than the window: the tests run", //$NON-NLS-1$
            YaxunitTestRunner.servesFromCache(true, false, false, true));
        assertFalse(YaxunitTestRunner.servesFromCache(false, true, true, true));
        assertFalse(YaxunitTestRunner.servesFromCache(false, false, false, false));
    }

    /**
     * An answer taken from a report carries the mark and the run it belongs to, and the two ways
     * into the report are named apart.
     */
    @Test
    public void anAnswerFromTheCacheSaysSoAndNamesTheRunItComesFrom()
    {
        long when = 1_700_000_000_000L;
        String mark = YaxunitTestRunner.cacheMark(false, when);
        assertTrue(mark, mark.contains(YaxunitTestRunner.CACHED_MARK));
        assertTrue("the report's own time is on the answer: " + mark, //$NON-NLS-1$
            mark.contains(java.time.Instant.ofEpochMilli(when).toString()));
        assertTrue(mark, mark.contains("started no tests")); //$NON-NLS-1$
        assertTrue("the pickup is named as one: " + mark, mark.contains("never handed over")); //$NON-NLS-1$
        assertTrue("a report the call asked for is named as that: " //$NON-NLS-1$
            + YaxunitTestRunner.cacheMark(true, when), //$NON-NLS-1$
            YaxunitTestRunner.cacheMark(true, when).contains("asked for a recent report")); //$NON-NLS-1$

        assertTrue(YaxunitTestRunner.isCachedAnswer("report" + mark)); //$NON-NLS-1$
        assertTrue(YaxunitTestRunner.isCachedAnswer(YaxunitTestRunner.CACHED_MARK));
        assertFalse(YaxunitTestRunner.isCachedAnswer("a fresh run")); //$NON-NLS-1$
        assertFalse(YaxunitTestRunner.isCachedAnswer(null));
    }

    /**
     * A filter this tool does not apply is refused rather than dropped, and the three it does apply
     * pass through.
     */
    @Test
    public void anUnknownFilterIsRefusedRatherThanIgnored()
    {
        assertNull(YaxunitTestRunner.unsupportedFilter(params()));
        assertNull("the launch configuration's own three filters", //$NON-NLS-1$
            YaxunitTestRunner.unsupportedFilter(params("extensions", "Расширение", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "modules", "ТестыПримеры", "tests", "ТестыПримеры.Тест_Сумма"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // An argument carried with no value names no filter, and is not a refusal.
        assertNull(YaxunitTestRunner.unsupportedFilter(params("tags", "  "))); //$NON-NLS-1$ //$NON-NLS-2$

        for (String filter : new String[] {"suites", "tags", "contexts"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String refusal = YaxunitTestRunner.unsupportedFilter(params(filter, "smoke")); //$NON-NLS-1$
            assertNotNull(filter + " is not applied by this tool and must be refused", refusal); //$NON-NLS-1$
            assertTrue(refusal, refusal.startsWith("**Error:**")); //$NON-NLS-1$
            assertTrue("the refused argument is named: " + refusal, refusal.contains(filter)); //$NON-NLS-1$
            assertTrue("and so are the ones that work: " + refusal, refusal.contains("extensions")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A refusal reaches the caller of {@code execute}, not only the helper above.
     */
    @Test
    public void aCallCarryingAnUnappliedFilterIsRefusedByTheToolItself()
    {
        String answer = newTool().execute(params("projectName", "Proj", "applicationId", "app-1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "tags", "smoke")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(answer);
        assertTrue(answer, answer.contains("tags")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("extensions")); //$NON-NLS-1$
    }

    /**
     * A refusal, a run still in progress and a finished result are all JSON objects.
     *
     * @throws Exception when the stand-in report cannot be written
     */
    @Test
    public void theAliasAnswersJsonOnARefusalAPendingRunAndAResult() throws Exception
    {
        JsonObject refusal = JsonParser.parseString(newTool().execute(new HashMap<String, String>()))
            .getAsJsonObject();
        assertFalse(refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refusal.get("error").getAsString(), //$NON-NLS-1$
            refusal.get("error").getAsString().contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject pending = JsonParser.parseString(
            YaxunitTestRunner.publish(YaxunitTestRunner.pendingText(Path.of("report-dir"), false))) //$NON-NLS-1$
            .getAsJsonObject();
        assertTrue(pending.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(pending.get("output").getAsString(), //$NON-NLS-1$
            pending.get("output").getAsString().contains("**Pending:**")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("yaxunit_tests", pending.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        Path report = Files.createTempDirectory("yaxunit-json"); //$NON-NLS-1$
        Path receipts = report.resolve("receipts"); //$NON-NLS-1$
        String runKey = "json-" + System.nanoTime(); //$NON-NLS-1$
        YaxunitTestRunner.noteUndelivered(runKey);
        try
        {
            String raw = YaxunitTestRunner.handOverFinishedLaunch(runKey, onePassingReport(report),
                new YaxunitTestRunner.RunContext("Proj", null, null, null, false), //$NON-NLS-1$
                fields -> RunReceipts.writeTo(receipts, fields));
            JsonObject result = JsonParser.parseString(YaxunitTestRunner.publish(raw)).getAsJsonObject();
            assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(1, result.get("total").getAsInt()); //$NON-NLS-1$
            assertTrue(result.has("receiptPath")); //$NON-NLS-1$
        }
        finally
        {
            YaxunitTestRunner.forgetUndelivered(runKey);
            deleteTree(report);
        }
    }

    /**
     * The first time a run started here is handed over, the answer is not cached, whether the
     * report is read from the launch that just finished or from the directory after that launch
     * was cleared. Asking for a recent report does not change that while the run is still
     * undelivered.
     *
     * @throws Exception when a stand-in report cannot be written
     */
    @Test
    public void theFirstDeliveryAfterPendingIsNotCachedOnEitherBranch() throws Exception
    {
        Path root = Files.createTempDirectory("yaxunit-first"); //$NON-NLS-1$
        try
        {
            JsonObject fromLaunch = firstDelivery(root.resolve("launch"), false, true); //$NON-NLS-1$
            assertFalse(fromLaunch.has("cached")); //$NON-NLS-1$
            assertFalse(fromLaunch.get("output").getAsString().contains(YaxunitTestRunner.CACHED_MARK)); //$NON-NLS-1$
            assertTrue(fromLaunch.has("receiptPath")); //$NON-NLS-1$

            JsonObject fromCache = firstDelivery(root.resolve("cache"), false, false); //$NON-NLS-1$
            assertFalse(fromCache.has("cached")); //$NON-NLS-1$
            assertTrue(fromCache.has("receiptPath")); //$NON-NLS-1$

            JsonObject askedForRecent = firstDelivery(root.resolve("recent"), true, false); //$NON-NLS-1$
            assertFalse("a run still undelivered is not a reused report", askedForRecent.has("cached")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(askedForRecent.has("receiptPath")); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(root);
        }
    }

    /**
     * A second reading of a result already handed over is cached and does not file another receipt.
     *
     * @throws Exception when the stand-in report cannot be written
     */
    @Test
    public void aRepeatDeliveryIsCachedAndFilesNothing() throws Exception
    {
        Path root = Files.createTempDirectory("yaxunit-repeat"); //$NON-NLS-1$
        Path receipts = root.resolve("receipts"); //$NON-NLS-1$
        String runKey = "repeat-" + System.nanoTime(); //$NON-NLS-1$
        YaxunitTestRunner.noteUndelivered(runKey);
        File report = onePassingReport(root);
        YaxunitTestRunner.RunContext context = new YaxunitTestRunner.RunContext("Proj", null, null, null, false); //$NON-NLS-1$
        try
        {
            JsonObject first = JsonParser.parseString(YaxunitTestRunner.handOverFinishedLaunch(runKey, report,
                context, fields -> RunReceipts.writeTo(receipts, fields))).getAsJsonObject();
            assertFalse(first.has("cached")); //$NON-NLS-1$
            assertTrue(first.has("receiptPath")); //$NON-NLS-1$

            JsonObject again = JsonParser.parseString(YaxunitTestRunner.handOverCached(runKey, report, true,
                context, fields -> RunReceipts.writeTo(receipts, fields))).getAsJsonObject();
            assertTrue(again.get("cached").getAsBoolean()); //$NON-NLS-1$
            assertFalse(again.has("receiptPath")); //$NON-NLS-1$
            assertEquals(1, jsonFiles(receipts).size());
        }
        finally
        {
            YaxunitTestRunner.forgetUndelivered(runKey);
            deleteTree(root);
        }
    }

    /**
     * Two deliveries of one finished run, released together, file one receipt. The caller that
     * does not claim the run is the repeat and is marked cached.
     *
     * @throws Exception when a delivery fails or the directory cannot be listed
     */
    @Test
    public void twoThreadsDeliveringOneRunFileASingleReceipt() throws Exception
    {
        Path root = Files.createTempDirectory("yaxunit-race"); //$NON-NLS-1$
        Path receipts = root.resolve("receipts"); //$NON-NLS-1$
        String runKey = "race-" + System.nanoTime(); //$NON-NLS-1$
        File report = onePassingReport(root);
        YaxunitTestRunner.RunContext context = new YaxunitTestRunner.RunContext("Proj", null, null, null, false); //$NON-NLS-1$
        CyclicBarrier barrier = new CyclicBarrier(2);
        YaxunitTestRunner.noteUndelivered(runKey);
        YaxunitTestRunner.beforeClaim = () -> {
            try
            {
                barrier.await(10, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> fromLaunch = pool.submit(() -> YaxunitTestRunner.handOverFinishedLaunch(runKey,
                report, context, fields -> RunReceipts.writeTo(receipts, fields)));
            Future<String> fromCache = pool.submit(() -> YaxunitTestRunner.handOverCached(runKey, report,
                false, context, fields -> RunReceipts.writeTo(receipts, fields)));
            String launchAnswer = fromLaunch.get(30, TimeUnit.SECONDS);
            String cacheAnswer = fromCache.get(30, TimeUnit.SECONDS);

            int withPath = (hasReceiptPath(launchAnswer) ? 1 : 0) + (hasReceiptPath(cacheAnswer) ? 1 : 0);
            assertEquals(1, withPath);
            assertEquals(1, jsonFiles(receipts).size());
            String repeat = hasReceiptPath(launchAnswer) ? cacheAnswer : launchAnswer;
            JsonObject repeatJson = JsonParser.parseString(repeat).getAsJsonObject();
            assertTrue(repeatJson.get("cached").getAsBoolean()); //$NON-NLS-1$
            String winner = hasReceiptPath(launchAnswer) ? launchAnswer : cacheAnswer;
            assertFalse(JsonParser.parseString(winner).getAsJsonObject().has("cached")); //$NON-NLS-1$
        }
        finally
        {
            YaxunitTestRunner.beforeClaim = () -> {
                // restored
            };
            pool.shutdownNow();
            YaxunitTestRunner.forgetUndelivered(runKey);
            deleteTree(root);
        }
    }

    /**
     * A directory lookup that throws becomes {@code receiptError}. The run's counters stay on a
     * successful answer.
     *
     * @throws Exception when the stand-in report cannot be written
     */
    @Test
    public void aReceiptDirectoryThatThrowsLeavesTheRunAnswerIntact() throws Exception
    {
        Path root = Files.createTempDirectory("yaxunit-state"); //$NON-NLS-1$
        String runKey = "state-" + System.nanoTime(); //$NON-NLS-1$
        YaxunitTestRunner.noteUndelivered(runKey);
        try
        {
            String raw = YaxunitTestRunner.handOverFinishedLaunch(runKey, onePassingReport(root),
                new YaxunitTestRunner.RunContext("Proj", "Ext", "Module", "Module.Test", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                fields -> RunReceipts.writeResolving(fields, tool -> {
                    throw new IllegalStateException("plugin state is gone"); //$NON-NLS-1$
                }));
            JsonObject answer = JsonParser.parseString(raw).getAsJsonObject();
            assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(1, answer.get("total").getAsInt()); //$NON-NLS-1$
            assertEquals(1, answer.get("passed").getAsInt()); //$NON-NLS-1$
            assertTrue(answer.get("receiptError").getAsString(), //$NON-NLS-1$
                answer.get("receiptError").getAsString().contains("plugin state is gone")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(answer.has("receiptPath")); //$NON-NLS-1$
        }
        finally
        {
            YaxunitTestRunner.forgetUndelivered(runKey);
            deleteTree(root);
        }
    }

    /**
     * Hands a staged run over once, through the launch path or the cache path.
     *
     * @param directory where the report and the receipt go
     * @param reuseRecent whether the cache path is asked for a recent report
     * @param fromLaunch whether the delivery is the one an active launch returns
     * @return the parsed answer
     * @throws Exception when the report cannot be written
     */
    private static JsonObject firstDelivery(Path directory, boolean reuseRecent, boolean fromLaunch)
        throws Exception
    {
        String runKey = "first-" + directory.getFileName() + "-" + System.nanoTime(); //$NON-NLS-1$ //$NON-NLS-2$
        YaxunitTestRunner.noteUndelivered(runKey);
        try
        {
            File report = onePassingReport(directory);
            YaxunitTestRunner.RunContext context = new YaxunitTestRunner.RunContext("Proj", null, null, null, false); //$NON-NLS-1$
            Path receipts = directory.resolve("receipts"); //$NON-NLS-1$
            String raw = fromLaunch
                ? YaxunitTestRunner.handOverFinishedLaunch(runKey, report, context,
                    fields -> RunReceipts.writeTo(receipts, fields))
                : YaxunitTestRunner.handOverCached(runKey, report, reuseRecent, context,
                    fields -> RunReceipts.writeTo(receipts, fields));
            return JsonParser.parseString(raw).getAsJsonObject();
        }
        finally
        {
            YaxunitTestRunner.forgetUndelivered(runKey);
        }
    }

    /**
     * Writes a one-test JUnit report.
     *
     * @param directory where {@code junit.xml} is written; created when missing
     * @return the report file
     * @throws Exception when the file cannot be written
     */
    private static File onePassingReport(Path directory) throws Exception
    {
        Files.createDirectories(directory);
        Path report = directory.resolve("junit.xml"); //$NON-NLS-1$
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //$NON-NLS-1$
            + "<testsuite name=\"Run\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">" //$NON-NLS-1$
            + "<testcase name=\"passes\" classname=\"Module\"/>" //$NON-NLS-1$
            + "</testsuite>"; //$NON-NLS-1$
        Files.write(report, xml.getBytes(StandardCharsets.UTF_8));
        return report.toFile();
    }

    /**
     * Whether an answer names a receipt file.
     *
     * @param json the answer
     * @return whether {@code receiptPath} is present
     */
    private static boolean hasReceiptPath(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject().has("receiptPath"); //$NON-NLS-1$
    }

    /**
     * The receipt files in a directory.
     *
     * @param directory the directory
     * @return the {@code .json} files
     * @throws Exception when the directory cannot be listed
     */
    private static List<Path> jsonFiles(Path directory) throws Exception
    {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory))
        {
            return files;
        }
        try (Stream<Path> entries = Files.list(directory))
        {
            entries.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add); //$NON-NLS-1$
        }
        return files;
    }

    /**
     * Deletes a temporary tree.
     *
     * @param root the tree
     * @throws Exception when a file cannot be deleted
     */
    private static void deleteTree(Path root) throws Exception
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
     * An application whose infobase is already up to date, so the update reports nothing to do.
     * <p>
     * Built through the shared step the runner calls, so the result under test is the one production
     * produces rather than a hand-made one.
     *
     * @return the update outcome
     */
    private static ApplicationUpdater.Result anInfobaseAlreadyUpToDate()
    {
        IApplicationManager manager = (IApplicationManager)Proxy.newProxyInstance(
            YaxunitTestRunnerTest.class.getClassLoader(), new Class<?>[] { IApplicationManager.class },
            (proxy, method, args) -> "getUpdateState".equals(method.getName()) //$NON-NLS-1$
                ? ApplicationUpdateState.UPDATED : null);
        IApplication application = (IApplication)Proxy.newProxyInstance(
            YaxunitTestRunnerTest.class.getClassLoader(), new Class<?>[] { IApplication.class },
            (proxy, method, args) -> "getId".equals(method.getName()) //$NON-NLS-1$
                || "getName".equals(method.getName()) ? "app-1" : null); //$NON-NLS-1$ //$NON-NLS-2$
        return DebugSessionStarter.updateDatabase(manager, application, null);
    }
}
