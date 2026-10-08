/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
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
import java.util.stream.Stream;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.RunReceipts;

/**
 * The answers of a YAXUnit run carry the update decision of the call that launched it, not the
 * decision of the call that comes back for the answer.
 * <p>
 * A pickup names the run by the same arguments the launch received, and the runKey does not carry
 * {@code updateBeforeLaunch}, so the picking call's own arguments and preset are the only thing a
 * re-deciding answer could read - and they are not facts about the launch. The run records its
 * decision where the launch and the undelivered mark are recorded, and every answer built through
 * {@code runContextOf} reads it from there. A run this server did not launch has no recorded
 * decision and states nothing about the update.
 * </p>
 */
public class APickedUpRunAnswersWithItsLaunchDecisionTest
{
    private IPreferenceStore store;

    private String presetBefore;

    /**
     * Takes the live preference store and remembers which preset it held.
     */
    @Before
    public void aStoreToHoldThePreset()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
    }

    /**
     * Puts the preset back.
     */
    @After
    public void thePresetGoesBack()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /**
     * A run launched without the argument under a preset that blocks update_database keeps its
     * {@code databaseUpdate=SKIPPED_BY_PRESET} in the answer of a call that named false explicitly:
     * the uncollected pickup and the recent report asked for again both answer about the launch,
     * and the launch was not updated.
     *
     * @throws Exception when a stand-in report cannot be written
     */
    @Test
    public void aSkippedLaunchKeepsItsMarkerInThePickupAnswer() throws Exception
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        DebugSessionStarter.LaunchUpdate launchedWith =
            DebugSessionStarter.launchUpdate((Boolean)null, true);
        assertTrue("the launch itself was skipped by the preset", launchedWith.skippedByPreset); //$NON-NLS-1$
        DebugSessionStarter.LaunchUpdate pickedWith = DebugSessionStarter.launchUpdate(Boolean.FALSE, true);
        assertFalse("the picking call's own decision names no skip", pickedWith.skippedByPreset); //$NON-NLS-1$

        Path root = Files.createTempDirectory("yaxunit-pickup-skip"); //$NON-NLS-1$
        try
        {
            File report = onePassingReport(root);
            Path receipts = root.resolve("receipts"); //$NON-NLS-1$
            String runKey = "pickup-skip-" + System.nanoTime(); //$NON-NLS-1$
            YaxunitTestRunner.noteLaunchSkipDecision(runKey, launchedWith.skippedByPreset);
            YaxunitTestRunner.noteUndelivered(runKey);
            try
            {
                JsonObject uncollected = pickedUp(runKey, report, false, receipts);
                assertEquals(DebugSessionStarter.DATABASE_UPDATE_SKIPPED_BY_PRESET,
                    uncollected.get("databaseUpdate").getAsString()); //$NON-NLS-1$
            }
            finally
            {
                YaxunitTestRunner.forgetUndelivered(runKey);
            }

            JsonObject askedForAgain = pickedUp(runKey, report, true, receipts);
            assertEquals("a recent report asked for again answers about the same launch", //$NON-NLS-1$
                DebugSessionStarter.DATABASE_UPDATE_SKIPPED_BY_PRESET,
                askedForAgain.get("databaseUpdate").getAsString()); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(root);
        }
    }

    /**
     * A run launched under a preset that allows updating stays silent in the answer of a call that
     * arrives after the preset changed to a blocking one: the picking call's own decision would
     * carry the skip, and the launch was updated - the answer states the launch, not the preset of
     * whoever happens to ask.
     *
     * @throws Exception when a stand-in report cannot be written
     */
    @Test
    public void anUpdatedLaunchStaysSilentAfterThePresetChanged() throws Exception
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        DebugSessionStarter.LaunchUpdate launchedWith =
            DebugSessionStarter.launchUpdate((Boolean)null, true);
        assertFalse(launchedWith.skippedByPreset);
        String runKey = "pickup-silent-" + System.nanoTime(); //$NON-NLS-1$
        YaxunitTestRunner.noteLaunchSkipDecision(runKey, launchedWith.skippedByPreset);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.DEBUG_AND_TEST.name());
        DebugSessionStarter.LaunchUpdate pickedWith =
            DebugSessionStarter.launchUpdate((Boolean)null, true);
        assertTrue("the preset change alone marks the picking call's own decision", //$NON-NLS-1$
            pickedWith.skippedByPreset);

        Path root = Files.createTempDirectory("yaxunit-pickup-silent"); //$NON-NLS-1$
        try
        {
            File report = onePassingReport(root);
            Path receipts = root.resolve("receipts"); //$NON-NLS-1$
            YaxunitTestRunner.noteUndelivered(runKey);
            try
            {
                JsonObject uncollected = pickedUp(runKey, report, false, receipts);
                assertFalse("the run was updated, whatever the preset says now", //$NON-NLS-1$
                    uncollected.has("databaseUpdate")); //$NON-NLS-1$
            }
            finally
            {
                YaxunitTestRunner.forgetUndelivered(runKey);
            }

            JsonObject askedForAgain = pickedUp(runKey, report, true, receipts);
            assertFalse(askedForAgain.has("databaseUpdate")); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(root);
        }
    }

    /**
     * A run this server did not launch - a report a previous session left behind and a call reads
     * as a recent one - has no recorded decision, and its answer states nothing about the update:
     * the picking call's own decision would be a claim about a launch it did not make.
     */
    @Test
    public void aRunWithNoRecordedDecisionStatesNothing()
    {
        assertFalse("a run this server did not launch answers with no skip marker", //$NON-NLS-1$
            YaxunitTestRunner.runContextOf("left-by-an-earlier-session-" + System.nanoTime(), //$NON-NLS-1$
                "Proj", null, null, null).skippedByPreset);
    }

    /**
     * Reads a report the way the pickup branches of the runner do: the run context built by
     * {@code runContextOf}, handed over as the uncollected pickup or the recent report.
     *
     * @param runKey the run
     * @param report the report it left
     * @param reuseRecent whether the reading asks for a recent report
     * @param receipts where the receipt goes
     * @return the parsed answer
     */
    private static JsonObject pickedUp(String runKey, File report, boolean reuseRecent, Path receipts)
    {
        String raw = YaxunitTestRunner.handOverCached(runKey, report, reuseRecent,
            YaxunitTestRunner.runContextOf(runKey, "Proj", null, null, null), //$NON-NLS-1$
            fields -> RunReceipts.writeTo(receipts, fields));
        return JsonParser.parseString(raw).getAsJsonObject();
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
}
