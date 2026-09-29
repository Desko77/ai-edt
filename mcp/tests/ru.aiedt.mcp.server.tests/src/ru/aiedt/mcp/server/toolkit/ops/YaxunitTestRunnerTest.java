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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.support.ApplicationUpdater;
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

    @Test
    public void responseTypeIsMarkdown()
    {
        assertEquals(IMcpTool.ResponseType.MARKDOWN, newTool().getResponseType());
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
        String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(true, "Proj", "app-1",
            (project, application) -> {
                asked.add(project + "/" + application); //$NON-NLS-1$
                return anInfobaseAlreadyUpToDate();
            });

        assertNull("an infobase already up to date lets the launch go on: " + refusal, refusal);
        assertEquals("the step runs once, for this project and application", 1, asked.size());
        assertEquals("Proj/app-1", asked.get(0));

        String notAsked = YaxunitTestRunner.preLaunchUpdateRefusal(false, "Proj", "app-1",
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
        String refusal = YaxunitTestRunner.preLaunchUpdateRefusal(true, "Proj", "app-1",
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
            YaxunitTestRunner.updateBeforeLaunch(new HashMap<String, String>()));
        assertTrue(YaxunitTestRunner.updateBeforeLaunch(params("updateBeforeLaunch", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(YaxunitTestRunner.updateBeforeLaunch(params("updateBeforeLaunch", "false"))); //$NON-NLS-1$ //$NON-NLS-2$

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
