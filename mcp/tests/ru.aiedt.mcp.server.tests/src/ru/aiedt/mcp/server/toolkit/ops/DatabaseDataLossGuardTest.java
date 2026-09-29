/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.e1c.g5.dt.applications.ApplicationCheckUnknownStateTreatment;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationArtifact;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.IUrlAccess;
import com.e1c.g5.dt.applications.LifecycleState;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.InfobaseIdentity;
import ru.aiedt.mcp.server.support.InfobaseUpdateQuestionGuard;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The data-loss guard of an update: what it classifies, what it answers, and what it leaves
 * alone.
 *
 * <p>Every behaviour here was absent before the guard existed: the platform restructured silently
 * (its ask-confirmation preference defaults to off), the update answered nothing about the data a
 * restructure would delete, and no record of a stopped question survived the call.</p>
 *
 * <p>The manager, the preference access and the question context are stand-ins whose calls are
 * recorded, so "the guard changed nothing it should not have" is read off the record rather than
 * inferred from an answer that claims it.</p>
 */
public class DatabaseDataLossGuardTest
{
    /**
     * The addresses the answer names come from the structure of the question text: dotted chains
     * of identifiers, in either alphabet, without reading a word of the platform's localized
     * wording.
     */
    @Test
    public void dataLossTablesComeFromTheStructureOfTheText()
    {
        List<String> cyrillic = InfobaseUpdateQuestionGuard.parseDataLossTables(
            "Реструктуризация удалит данные: Catalog.Товары.Attribute.Вес, а также " //$NON-NLS-1$
                + "Document.Приход.TabularSection.Строки.Attribute.Цена."); //$NON-NLS-1$
        assertEquals(List.of("Catalog.Товары.Attribute.Вес", //$NON-NLS-1$
            "Document.Приход.TabularSection.Строки.Attribute.Цена"), cyrillic); //$NON-NLS-1$

        List<String> latin = InfobaseUpdateQuestionGuard.parseDataLossTables(
            "Tables to drop: Catalog.X.Attribute.Y and Catalog.X.Attribute.Y again"); //$NON-NLS-1$
        assertEquals("the same address twice is one address", //$NON-NLS-1$
            List.of("Catalog.X.Attribute.Y"), latin); //$NON-NLS-1$

        assertTrue("a version number is not an address", //$NON-NLS-1$
            InfobaseUpdateQuestionGuard.parseDataLossTables("platform 8.3.27 build 1445").isEmpty()); //$NON-NLS-1$
        assertTrue("a lone identifier is not an address", //$NON-NLS-1$
            InfobaseUpdateQuestionGuard.parseDataLossTables("таблица Товары будет удалена").isEmpty()); //$NON-NLS-1$
        assertTrue(InfobaseUpdateQuestionGuard.parseDataLossTables(null).isEmpty());
    }

    /**
     * The refusal is the platform's own default answer, and the acceptance is its single
     * non-default one - decided by position, not by the label's wording.
     */
    @Test
    public void theGuardAnswersByPositionAndParksTheRawQuestion()
    {
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        InfobaseUpdateQuestionGuard.Run refused =
            guard.beginRun("file:/base", false, null, () -> List.of(), 60_000L);
        try
        {
            Optional<InfobaseSynchonizationQuestionAnswer> answer = guard
                .handleQuestion(question("Данные будут удалены. Продолжить?", //$NON-NLS-1$
                    new InfobaseSynchonizationQuestionAnswer("Yes", "Да", false), //$NON-NLS-1$ //$NON-NLS-2$
                    new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(answer.isPresent());
            assertEquals("No", answer.get().getAnswer()); //$NON-NLS-1$
            InfobaseUpdateQuestionGuard.ParkedQuestion parked = refused.parked().orElseThrow();
            assertEquals("service", parked.route); //$NON-NLS-1$
            assertEquals("Нет", parked.answerGiven); //$NON-NLS-1$
            assertFalse(parked.accepted);
            assertEquals(List.of("Да", "Нет"), parked.answers); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(parked.dataLossTables.isEmpty());
        }
        finally
        {
            refused.end();
        }

        InfobaseUpdateQuestionGuard.Run accepted =
            guard.beginRun("file:/base", true, null, () -> List.of(), 60_000L);
        try
        {
            Optional<InfobaseSynchonizationQuestionAnswer> answer = guard
                .handleQuestion(question("Данные будут удалены. Продолжить?", //$NON-NLS-1$
                    new InfobaseSynchonizationQuestionAnswer("Yes", "Да", false), //$NON-NLS-1$ //$NON-NLS-2$
                    new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(answer.isPresent());
            assertEquals("Yes", answer.get().getAnswer()); //$NON-NLS-1$
            assertTrue("the acceptance is recorded as accepted", //$NON-NLS-1$
                accepted.parked().orElseThrow().accepted);
        }
        finally
        {
            accepted.end();
        }
    }

    /**
     * A question whose answers do not split into one default and one non-default is left to the
     * environment's own handler - a guess here accepts a loss, which is not the guard's to make.
     * The same goes for a question that arrives with nothing armed.
     */
    @Test
    public void anUndecidableQuestionIsLeftToTheEnvironment()
    {
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);

        assertFalse("two defaults answer nothing", guard.handleQuestion(question("?", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("a", "a", true), //$NON-NLS-1$ //$NON-NLS-2$
            new InfobaseSynchonizationQuestionAnswer("b", "b", true))).isPresent()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("two non-defaults answer nothing", guard.handleQuestion(question("?", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("a", "a", false), //$NON-NLS-1$ //$NON-NLS-2$
            new InfobaseSynchonizationQuestionAnswer("b", "b", false))).isPresent()); //$NON-NLS-1$ //$NON-NLS-2$

        InfobaseUpdateQuestionGuard.Run run =
            guard.beginRun("file:/base", false, null, () -> List.of(), 60_000L);
        run.end();
        assertFalse("a question after the run ended is not this guard's", //$NON-NLS-1$
            guard.handleQuestion(question("?", //$NON-NLS-1$
                new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))).isPresent()); //$NON-NLS-1$
    }

    /**
     * The dialog route is watched and parked, never answered: the parked record carries the
     * dialog's own words and buttons and the addresses parsed out of them, and no answer is
     * recorded for it.
     */
    @Test
    public void theDialogRouteIsParkedNotAnswered() throws InterruptedException
    {
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        Map<String, Object> dialog = new LinkedHashMap<>();
        dialog.put("title", "Изменение структуры информационной базы"); //$NON-NLS-1$ //$NON-NLS-2$
        dialog.put("message", "Будут удалены данные: Catalog.Номенклатура.Attribute.Вес"); //$NON-NLS-1$ //$NON-NLS-2$
        dialog.put("buttons", List.of("Да", "Нет", "Отмена")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        CountDownLatch parked = new CountDownLatch(1);

        InfobaseUpdateQuestionGuard.Run run = guard.beginRun("file:/base", false, null,
            () -> {
                parked.countDown();
                return List.of(dialog);
            },
            10L);
        try
        {
            assertTrue("the watcher did not read the dialog in time", //$NON-NLS-1$
                parked.await(20, TimeUnit.SECONDS));
            InfobaseUpdateQuestionGuard.ParkedQuestion record = waitForParked(run, 20_000L);
            assertEquals("dialog", record.route); //$NON-NLS-1$
            assertTrue(record.question.contains("Изменение структуры")); //$NON-NLS-1$
            assertTrue(record.question.contains("Catalog.Номенклатура.Attribute.Вес")); //$NON-NLS-1$
            assertEquals(List.of("Да", "Нет", "Отмена"), record.answers); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(List.of("Catalog.Номенклатура.Attribute.Вес"), record.dataLossTables); //$NON-NLS-1$
            assertNull("the dialog route answers nothing", record.answerGiven); //$NON-NLS-1$
        }
        finally
        {
            run.end();
        }
    }

    /**
     * Waits for the watcher to have parked the question it saw.
     *
     * @param run the armed run
     * @param timeoutMs how long to wait
     * @return the parked question
     */
    private static InfobaseUpdateQuestionGuard.ParkedQuestion waitForParked(
        InfobaseUpdateQuestionGuard.Run run, long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            if (run.parked().isPresent())
            {
                return run.parked().get();
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("the run parked no question"); //$NON-NLS-1$
    }

    /**
     * A parked question survives the run it stopped, keeps its place until a newer question of
     * the same base replaces it, and expires by age - the window is bounded, not permanent.
     */
    @Test
    public void aParkedQuestionSurvivesTheRunAndExpiresByAge()
    {
        AtomicLong clock = new AtomicLong(1_000_000L);
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(clock::get);

        InfobaseUpdateQuestionGuard.Run run =
            guard.beginRun("file:/base", false, null, () -> List.of(), 60_000L);
        guard.handleQuestion(question("Данные будут удалены: Catalog.X.Attribute.Y", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$
        run.end();

        assertTrue("the record outlives the run it stopped", //$NON-NLS-1$
            guard.parkedOf("file:/base").isPresent()); //$NON-NLS-1$

        InfobaseUpdateQuestionGuard.Run second =
            guard.beginRun("file:/base", false, null, () -> List.of(), 60_000L);
        guard.handleQuestion(question("Данные будут удалены: Catalog.Z.TabularSection.T", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$
        second.end();

        assertEquals("the newer question of the same base replaces the older", //$NON-NLS-1$
            List.of("Catalog.Z.TabularSection.T"), //$NON-NLS-1$
            guard.parkedOf("file:/base").orElseThrow().dataLossTables); //$NON-NLS-1$

        clock.addAndGet(31 * 60_000L);
        assertFalse("a record past its window is gone", //$NON-NLS-1$
            guard.parkedOf("file:/base").isPresent()); //$NON-NLS-1$
    }

    /**
     * The registry holds one record per base and bounds how many bases it holds at all: the
     * ninth base retires the oldest record, not none.
     */
    @Test
    public void theRegistryIsBoundedPerBase()
    {
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        for (int index = 0; index < 9; index++)
        {
            InfobaseUpdateQuestionGuard.Run run =
                guard.beginRun("file:/base" + index, false, null, () -> List.of(), 60_000L); //$NON-NLS-1$
            guard.handleQuestion(question("Catalog.B" + index + ".Attribute.A", //$NON-NLS-1$
                new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$
            run.end();
        }
        assertFalse("the oldest record retired", guard.parkedOf("file:/base0").isPresent()); //$NON-NLS-1$
        assertTrue("the newest stays", guard.parkedOf("file:/base8").isPresent()); //$NON-NLS-1$
    }

    /**
     * The restructure-confirmation preference is restored only after the awaited state has
     * settled: the update call can return BEING_UPDATED with the restructure still running, and a
     * preference restored on the return would leave the question of the still-running work
     * unanswered. The record interleaves both stand-ins, so the order is the assertion.
     */
    @Test
    public void thePreferenceIsRestoredOnlyAfterTheAwaitedStateSettles()
    {
        List<String> calls = new ArrayList<>();
        SequencedStates manager = new SequencedStates(calls,
            ApplicationUpdateState.BEING_UPDATED, ApplicationUpdateState.UPDATED);
        RecordingAccess preferences = new RecordingAccess(calls, Boolean.FALSE);

        DatabaseUpdater.RestructurePromptGuard guard =
            DatabaseUpdater.RestructurePromptGuard.engage(preferences, UUID.randomUUID());
        assertNotNull(guard);

        ApplicationUpdateState settled = DatabaseUpdater.updateAwaitAndRestore(manager,
            new StubApplication("app-1"), ApplicationUpdateType.INCREMENTAL, null, null, guard); //$NON-NLS-1$

        assertEquals(ApplicationUpdateState.UPDATED, settled);
        assertEquals("the restore is the last call, after the state settled", //$NON-NLS-1$
            List.of("read:false", "write:true", "update", "state:BEING_UPDATED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "state:UPDATED", "write:false"), //$NON-NLS-1$ //$NON-NLS-2$
            calls);
    }

    /**
     * A base that was already asking is left exactly as it was: nothing written on the way in,
     * nothing written on the way out.
     */
    @Test
    public void aBaseThatWasAlreadyAskingIsNotWritten()
    {
        List<String> calls = new ArrayList<>();
        SequencedStates manager = new SequencedStates(calls, ApplicationUpdateState.UPDATED);
        RecordingAccess preferences = new RecordingAccess(calls, Boolean.TRUE);

        DatabaseUpdater.RestructurePromptGuard guard =
            DatabaseUpdater.RestructurePromptGuard.engage(preferences, UUID.randomUUID());
        assertNotNull(guard);
        DatabaseUpdater.updateAwaitAndRestore(manager, new StubApplication("app-1"), //$NON-NLS-1$
            ApplicationUpdateType.INCREMENTAL, null, null, guard);

        assertEquals("a base already asking is read and never written", //$NON-NLS-1$
            List.of("read:true", "update"), calls); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A preference that cannot be read or written engages nothing, and the update refuses rather
     * than running silently restructured: the refusal names the way out.
     */
    @Test
    public void anUnreadablePreferenceRefusesTheUpdate()
    {
        assertNull(DatabaseUpdater.RestructurePromptGuard.engage(new RecordingAccess(new ArrayList<>(), null),
            UUID.randomUUID()));
        assertNull(DatabaseUpdater.RestructurePromptGuard.engage(null, UUID.randomUUID()));

        String refusal = DatabaseUpdater.dataLossProtectionRefusal();
        assertTrue("the refusal names the way out", //$NON-NLS-1$
            refusal.contains("protectData=false")); //$NON-NLS-1$
        assertTrue(refusal.contains("not started")); //$NON-NLS-1$
    }

    /**
     * The restore is idempotent: the normal path restores after the wait, the closing step
     * restores again, and the base sees one write, not two.
     */
    @Test
    public void theRestoreIsIdempotent()
    {
        List<String> calls = new ArrayList<>();
        RecordingAccess preferences = new RecordingAccess(calls, Boolean.FALSE);
        DatabaseUpdater.RestructurePromptGuard guard =
            DatabaseUpdater.RestructurePromptGuard.engage(preferences, UUID.randomUUID());

        guard.restore();
        guard.restore();

        assertEquals("one write on the way in, one on the way out, twice restored", //$NON-NLS-1$
            List.of("read:false", "write:true", "write:false"), calls); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The refused call and its accepted resend are two runs: a caller who accepted the loss must
     * never be served the answer of the call that refused it.
     */
    @Test
    public void theRunKeySeparatesTheAcceptedResendFromTheRefusedCall()
    {
        assertFalse(DatabaseUpdater.runKeyFor("proj", "app", false, true, false, false, false, true, false)
            .equals(DatabaseUpdater.runKeyFor("proj", "app", false, true, false, false, false, true,
                true)));
        assertFalse(DatabaseUpdater.runKeyFor("proj", "app", false, true, false, false, false, true, false)
            .equals(DatabaseUpdater.runKeyFor("proj", "app", false, true, false, false, false, false,
                false)));
    }

    /**
     * An update stopped by a refused question answers confirmationRequired with the addresses;
     * an accepted one answers without it; an unguarded update says just that.
     */
    @Test
    public void theAnswerNamesTheStopAndTheWayThrough()
    {
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        InfobaseUpdateQuestionGuard.Run refused =
            guard.beginRun("file:/base", false, null, () -> List.of(), 60_000L);
        guard.handleQuestion(question("Данные будут удалены: Catalog.X.Attribute.Y", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$
        refused.end();

        ToolResult stopped = ToolResult.success();
        DatabaseUpdater.putDataLossReport(stopped, guard, refused, "file:/base", true, false); //$NON-NLS-1$
        JsonObject stoppedJson = JsonParser.parseString(stopped.toJson()).getAsJsonObject();
        assertEquals("service", stoppedJson.get("questionRoute").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stoppedJson.get("dataLossTables").getAsJsonArray().size() == 1); //$NON-NLS-1$
        assertEquals("confirmationRequired", stoppedJson.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stoppedJson.get("nextStep").getAsString().contains("acceptDataLoss=true")); //$NON-NLS-1$ //$NON-NLS-2$

        InfobaseUpdateQuestionGuard.Run accepted =
            guard.beginRun("file:/base2", true, null, () -> List.of(), 60_000L);
        guard.handleQuestion(question("Данные будут удалены: Catalog.X.Attribute.Y", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("Yes", "Да", false), //$NON-NLS-1$ //$NON-NLS-2$
            new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$
        accepted.end();

        ToolResult carried = ToolResult.success();
        DatabaseUpdater.putDataLossReport(carried, guard, accepted, "file:/base2", true, true); //$NON-NLS-1$
        JsonObject carriedJson = JsonParser.parseString(carried.toJson()).getAsJsonObject();
        assertTrue("an accepted update answers without the stop", //$NON-NLS-1$
            !carriedJson.has("status")); //$NON-NLS-1$

        ToolResult unguarded = ToolResult.success();
        DatabaseUpdater.putDataLossReport(unguarded, guard, null, null, false, false);
        JsonObject unguardedJson = JsonParser.parseString(unguarded.toJson()).getAsJsonObject();
        assertEquals("an unguarded update says that and nothing else", //$NON-NLS-1$
            Boolean.FALSE, Boolean.valueOf(unguardedJson.get("protectData").getAsBoolean())); //$NON-NLS-1$
        assertEquals(2, unguardedJson.keySet().size());
    }

    /**
     * A guarded update that met no question says the route was none and whether the question
     * service was registered at all - the two facts a stand measurement reads apart.
     */
    @Test
    public void aGuardedUpdateWithoutAQuestionNamesTheRouteNone()
    {
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        InfobaseUpdateQuestionGuard.Run run =
            guard.beginRun("file:/base", false, handler -> () -> { }, () -> List.of(), 60_000L);
        run.end();

        ToolResult answer = ToolResult.success();
        DatabaseUpdater.putDataLossReport(answer, guard, run, "file:/base", true, true); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(answer.toJson()).getAsJsonObject();
        assertEquals("none", json.get("questionRoute").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, Boolean.valueOf(json.get("questionServiceRegistered").getAsBoolean())); //$NON-NLS-1$
    }

    /**
     * The inspection reads two things and writes none: the update state and the
     * restructure-confirmation preference. No update, no preference write, no claim - the answer
     * says it started nothing, and the stand-ins' record says the same.
     */
    @Test
    public void theInspectionChangesNothingItReads()
    {
        List<String> calls = new ArrayList<>();
        SequencedStates manager = new SequencedStates(calls,
            ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        RecordingAccess preferences = new RecordingAccess(calls, Boolean.FALSE);
        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        infobase.setUuid(UUID.randomUUID());

        String answer = DatabaseSyncInspector.inspect(manager, preferences, guard,
            new StubInfobaseApplication("app-1", infobase), "proj", "app-1", false, "proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the inspection reads the state and the preference, nothing else", //$NON-NLS-1$
            List.of("state:INCREMENTAL_UPDATE_REQUIRED", "read:false"), calls); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", json.get("updateState").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("wouldUpdate").getAsBoolean()); //$NON-NLS-1$
        assertEquals("off", json.get("restructureConfirmationPrompt").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("nothingStarted").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the composition is named as unreadable, not guessed", //$NON-NLS-1$
            json.get("composition").getAsString().contains("not available")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.getAsJsonObject("dataLossProtection").has("nextUpdateProtectsData")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(!json.getAsJsonObject("dataLossProtection").has("parkedQuestion")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The inspection reads back the question a stopped update parked, with the addresses the
     * platform said it would delete - the record outlives the call that was stopped by it.
     */
    @Test
    public void theInspectionReadsTheParkedQuestion()
    {
        InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        infobase.setUuid(UUID.randomUUID());
        FileConnectionString connection = ModelFactory.eINSTANCE.createFileConnectionString();
        connection.setFile("E:/bases/stub"); //$NON-NLS-1$
        infobase.setConnectionString(connection);
        String identity = InfobaseIdentity.of(infobase);

        InfobaseUpdateQuestionGuard guard = new InfobaseUpdateQuestionGuard(System::currentTimeMillis);
        InfobaseUpdateQuestionGuard.Run run =
            guard.beginRun(identity, false, null, () -> List.of(), 60_000L);
        guard.handleQuestion(question("Данные будут удалены: Catalog.X.Attribute.Y", //$NON-NLS-1$
            new InfobaseSynchonizationQuestionAnswer("No", "Нет", true))); //$NON-NLS-1$ //$NON-NLS-2$
        run.end();

        String answer = DatabaseSyncInspector.inspect(
            new SequencedStates(new ArrayList<>(), ApplicationUpdateState.UPDATED),
            new RecordingAccess(new ArrayList<>(), Boolean.TRUE), guard,
            new StubInfobaseApplication("app-1", infobase), "proj", "app-1", false, "proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject parked = JsonParser.parseString(answer).getAsJsonObject()
            .getAsJsonObject("dataLossProtection").getAsJsonObject("parkedQuestion"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(parked);
        assertEquals("service", parked.get("route").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(parked.get("dataLossTables").getAsJsonArray().size() == 1); //$NON-NLS-1$
    }

    /**
     * A question context stand-in: the message and the answers the platform offered.
     *
     * @param message the question text
     * @param answers the offered answers
     * @return the context
     */
    private static IInfobaseSynchonizationQuestionContext question(String message,
        InfobaseSynchonizationQuestionAnswer... answers)
    {
        return new IInfobaseSynchonizationQuestionContext()
        {
            @Override
            public String getMessage()
            {
                return message;
            }

            @Override
            public List<InfobaseSynchonizationQuestionAnswer> getAnswers()
            {
                return List.of(answers);
            }
        };
    }

    /**
     * An application manager that records its calls and answers a fixed sequence of update
     * states. The record is shared with the other stand-ins of a test, so one list carries the
     * interleaved order the assertions read.
     */
    private static final class SequencedStates implements IApplicationManager
    {
        private final List<String> calls;

        private final ApplicationUpdateState[] states;

        private int read;

        SequencedStates(List<String> calls, ApplicationUpdateState... states)
        {
            this.calls = calls;
            this.states = states;
        }

        @Override
        public ApplicationUpdateState getUpdateState(IApplication application)
        {
            ApplicationUpdateState state = states[Math.min(read, states.length - 1)];
            read++;
            calls.add("state:" + state.name()); //$NON-NLS-1$
            return state;
        }

        @Override
        public ApplicationUpdateState update(IApplication application, ApplicationUpdateType type,
            ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("update"); //$NON-NLS-1$
            return states[Math.min(read, states.length - 1)];
        }

        @Override
        public IStatus check(IApplication application, ApplicationCheckUnknownStateTreatment treatment,
            ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("check"); //$NON-NLS-1$
            return Status.OK_STATUS;
        }

        @Override
        public Optional<IApplication> getApplication(IProject project, String applicationId)
        {
            return Optional.empty();
        }

        @Override
        public List<IApplication> getApplications(IProject project)
        {
            return List.of();
        }

        @Override
        public Optional<IProject> getDefaultProject()
        {
            return Optional.empty();
        }

        @Override
        public Optional<IApplication> getDefaultApplication(IProject project)
        {
            return Optional.empty();
        }

        @Override
        public Optional<IUrlAccess> getDefaultUrlAccess(IApplication application)
        {
            return Optional.empty();
        }

        @Override
        public LifecycleState getLifecycleState(com.e1c.g5.dt.applications.ILifecycleAware lifecycleAware)
        {
            return null;
        }

        @Override
        public List<IApplicationType> getApplicationTypes()
        {
            return List.of();
        }

        @Override
        public List<IApplicationArtifact> getApplicationArtifacts(Object owner)
        {
            return List.of();
        }

        @Override
        public List<IUrlAccess> getUrlAccesses(IApplication application)
        {
            return List.of();
        }

        @Override
        public void setDefaultApplication(IProject project, IApplication application)
        {
            // unused by this suite
        }

        @Override
        public void setDefaultUrlAccess(IApplication application, IUrlAccess urlAccess)
        {
            // unused by this suite
        }

        @Override
        public void prepare(IApplication application, String mode, ExecutionContext context,
            IProgressMonitor monitor)
        {
            // unused by this suite
        }

        @Override
        public void cleanup(IApplication application, ExecutionContext context, IProgressMonitor monitor)
        {
            // unused by this suite
        }

        @Override
        public Optional<Process> start(IApplication application, ExecutionContext context,
            IProgressMonitor monitor)
        {
            return Optional.empty();
        }

        @Override
        public void addAppllicationListener(IApplicationListener listener)
        {
            // unused by this suite
        }

        @Override
        public void removeAppllicationListener(IApplicationListener listener)
        {
            // unused by this suite
        }

        @Override
        public void delete(IApplication application, boolean deleteFiles)
        {
            calls.add("delete"); //$NON-NLS-1$
        }

        @Override
        public Optional<IApplication> findApplicationByInfobase(
            com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase)
        {
            return Optional.empty();
        }

        @Override
        public Optional<IApplication> findApplicationByInfobaseAndProject(
            com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase, IProject project)
        {
            return Optional.empty();
        }

        @Override
        public String suggestNewApplicationName(String projectName, String applicationType)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * A preference access that records what was asked of it and answers a fixed value, or no
     * value at all when the fixed value is null - which is how an unreachable preference service
     * is staged.
     */
    private static final class RecordingAccess
        implements DatabaseUpdater.RestructurePromptGuard.Access
    {
        private final List<String> calls;

        private final Boolean answers;

        RecordingAccess(List<String> calls, Boolean answers)
        {
            this.calls = calls;
            this.answers = answers;
        }

        @Override
        public Boolean promptConfirmationOnRestructure(UUID infobaseId)
        {
            calls.add("read:" + answers); //$NON-NLS-1$
            return answers;
        }

        @Override
        public boolean setPromptConfirmationOnRestructure(UUID infobaseId, boolean value)
        {
            calls.add("write:" + value); //$NON-NLS-1$
            return true;
        }
    }

    /**
     * An application with an id and a name, whose identity the parked-record tests address.
     * Not final: the infobase application below extends it.
     */
    private static class StubApplication implements IApplication
    {
        private final String id;

        StubApplication(String id)
        {
            this.id = id;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public String getName()
        {
            return id;
        }

        @Override
        public IProject getProject()
        {
            return null;
        }

        @Override
        public IApplicationType getType()
        {
            return null;
        }

        @Override
        public List<IApplicationArtifact> getArtifacts()
        {
            return List.of();
        }

        @Override
        public Optional<String> getRequiredVersion()
        {
            return Optional.empty();
        }

        @Override
        public IApplication getApplication()
        {
            return this;
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }
    }

    /**
     * An infobase application: the same stub, carrying the infobase reference whose identity the
     * parked question is filed under.
     */
    private static final class StubInfobaseApplication extends StubApplication
        implements IInfobaseApplication
    {
        private final InfobaseReference infobase;

        StubInfobaseApplication(String id, InfobaseReference infobase)
        {
            super(id);
            this.infobase = infobase;
        }

        @Override
        public InfobaseReference getInfobase()
        {
            return infobase;
        }

        @Override
        public Optional<java.net.URL> getUrl()
        {
            return Optional.empty();
        }
    }
}
