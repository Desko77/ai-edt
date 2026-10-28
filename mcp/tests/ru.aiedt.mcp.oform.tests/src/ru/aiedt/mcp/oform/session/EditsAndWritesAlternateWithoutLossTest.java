/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.OrdinaryFormModuleProvider;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.Pacing;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/**
 * The form editor's edits and the agent's module write alternate in both orders, and neither
 * loses the other's work. A write after the session's edits passes - it replaces the module
 * entry only - the session takes the written module over, keeps its layout edit, and its save
 * lands both. An edit after the agent's write does the same from the other side: the session
 * accepts the written module and its save lands the layout edit over it. More than one round of
 * the two loses nothing either.
 * <p>
 * The two orders also cross in real time, each stopped between its own steps: an edit stopped
 * between its baseline check and its fixation holds the agent's write back - the write cannot
 * land between the two - and a write stopped between its check and its file write holds the
 * edit back - the edit cannot fix itself over bytes the write is about to replace. Both sides
 * finish in both orders and lose nothing.
 * </p>
 */
public class EditsAndWritesAlternateWithoutLossTest
{
    private static final String PROJECT = "AiEdtOformAlternateProbe"; //$NON-NLS-1$

    private static final String MODULE_PATH = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String WRITTEN_BEFORE = "Сообщить(\"До записи\");"; //$NON-NLS-1$

    private static final String WRITTEN_AFTER = "Сообщить(\"После записи\");"; //$NON-NLS-1$

    private static final String FIRST_MODULE = "Процедура ПриОткрытии()" + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "\t" + WRITTEN_BEFORE + "\r\n" //$NON-NLS-1$
        + "КонецПроцедуры" + "\r\n"; //$NON-NLS-1$

    private static SessionProbe probe;

    private static OrdinaryFormModuleProvider registeredHere;

    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, FIRST_MODULE);
        if (ModuleSources.providers().stream().noneMatch(OrdinaryFormModuleProvider.class::isInstance))
        {
            registeredHere = new OrdinaryFormModuleProvider();
            ModuleSources.register(registeredHere);
        }
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (registeredHere != null)
        {
            ModuleSources.unregister(registeredHere);
        }
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /**
     * The session edits, the agent writes, the session goes on: the write passes over the
     * session's layout edit, the session accepts the written module and keeps its edit, and the
     * save lands both.
     */
    @Test
    public void theAgentWritesAfterTheSessionsEdits() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theTitleEdit(session, "Правка до записи").done); //$NON-NLS-1$
                String answer = theAgentWritesTheModule();
                assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                assertTrue("the write's answer names the editor", answer.contains("unsavedInEditor")); //$NON-NLS-1$

                FormEditingSession.Reconciliation takenOver = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, takenOver.outcome);
                assertTrue("the layout edit survived the write", session.formChanged()); //$NON-NLS-1$
                assertFalse("the written module is now the session's own", session.moduleChanged()); //$NON-NLS-1$

                assertTrue(theTitleEdit(session, "Правка после записи").done); //$NON-NLS-1$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                OrdinaryFormFile written = theContainersFile();
                assertTrue(written.formText(), written.formText().contains("Правка после записи")); //$NON-NLS-1$
                assertTrue(written.moduleText(), written.moduleText().contains(WRITTEN_AFTER));
                assertFalse(written.moduleText(), written.moduleText().contains(WRITTEN_BEFORE));
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /**
     * The agent writes, the session edits over it: the write lands on a clean session, the
     * session accepts it, its edit runs over what was written and its save lands both.
     */
    @Test
    public void theSessionEditsAfterTheAgentsWrite() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertFalse(session.isDirty());
                String answer = theAgentWritesTheModule();
                assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$

                FormEditingSession.Reconciliation takenOver = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, takenOver.outcome);
                assertTrue(theTitleEdit(session, "Правка поверх записи").done); //$NON-NLS-1$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                OrdinaryFormFile written = theContainersFile();
                assertTrue(written.formText(), written.formText().contains("Правка поверх записи")); //$NON-NLS-1$
                assertTrue(written.moduleText(), written.moduleText().contains(WRITTEN_AFTER));
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** Rounds of the two, one after the other, keep every change: both ends of the last round stay. */
    @Test
    public void theyAlternateMoreThanOnce() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theTitleEdit(session, "Чередование 1").done); //$NON-NLS-1$
                assertTrue(theAgentWritesTheModule().contains("status: success")); //$NON-NLS-1$
                FormEditingSession.Reconciliation first = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, first.outcome);

                assertTrue(theTitleEdit(session, "Чередование 2").done); //$NON-NLS-1$
                String answer = theAgentWritesTheModuleAgain();
                assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                FormEditingSession.Reconciliation second = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, second.outcome);

                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                OrdinaryFormFile written = theContainersFile();
                assertTrue(written.formText(), written.formText().contains("Чередование 2")); //$NON-NLS-1$
                assertTrue(written.moduleText(), written.moduleText().contains("Оборот 2")); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /**
     * The session's edit, stopped between its baseline check and its fixation, holds the
     * agent's write back: the write cannot land its module between the two. Once the edit goes
     * on, the write enters over the session's layout edit, passes - it replaces the module entry
     * only - and the session takes the written module over and its save lands both.
     */
    @Test
    public void theStoppedEditHoldsTheWriteBackAndBothLand() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                CountDownLatch editAtPoint = new CountDownLatch(1);
                CountDownLatch editGoesOn = new CountDownLatch(1);
                AtomicBoolean atTheFixation = new AtomicBoolean(true);
                AtomicReference<FormEditingSession.Edit> editOf = new AtomicReference<>();
                Pacing.install(point -> {
                    if (Pacing.BEFORE_EDIT_FIXATION.equals(point) && atTheFixation.compareAndSet(true, false))
                    {
                        editAtPoint.countDown();
                        await(editGoesOn);
                    }
                });
                Thread editor = new Thread(() -> editOf.set(session.edit("set_property", //$NON-NLS-1$
                    Map.of("property", "title", "value", "Правка под точкой"))), "the-editing-session"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                CountDownLatch writeAnswered = new CountDownLatch(1);
                AtomicReference<String> writeOf = new AtomicReference<>();
                Thread agent = new Thread(() -> {
                    writeOf.set(theAgentWritesTheModule());
                    writeAnswered.countDown();
                }, "the-writing-agent"); //$NON-NLS-1$
                try
                {
                    editor.start();
                    assertTrue("the edit never reached its fixation", editAtPoint.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
                    agent.start();
                    assertFalse("the write finished while the edit held the registry lock", //$NON-NLS-1$
                        writeAnswered.await(700, TimeUnit.MILLISECONDS));

                    editGoesOn.countDown();
                    assertTrue("the write never finished once the edit went on", //$NON-NLS-1$
                        writeAnswered.await(10, TimeUnit.SECONDS));
                    editor.join(10_000);
                    agent.join(10_000);
                    FormEditingSession.Edit edit = editOf.get();
                    assertTrue(edit.message, edit.done);
                    String answer = writeOf.get();
                    assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                    assertTrue("the write's answer names the editor", answer.contains("unsavedInEditor")); //$NON-NLS-1$

                    FormEditingSession.Reconciliation takenOver = session.reconcile();
                    assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, takenOver.outcome);
                    assertTrue("the layout edit survived the write", session.formChanged()); //$NON-NLS-1$
                    FormEditingSession.Save save = session.save();
                    assertTrue(String.valueOf(save.reconciliation), save.written);
                    OrdinaryFormFile written = theContainersFile();
                    assertTrue(written.formText(), written.formText().contains("Правка под точкой")); //$NON-NLS-1$
                    assertTrue(written.moduleText(), written.moduleText().contains(WRITTEN_AFTER));
                }
                finally
                {
                    Pacing.clear();
                }
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /**
     * The agent's write, stopped between its check and its file write, holds the session's edit
     * back: the edit cannot fix itself over bytes the write has not written yet. Once the write
     * goes on, the edit enters over the written module, reconciles it - its first round is
     * refused with what the reconciliation found - and its second round and its save land both.
     */
    @Test
    public void theStoppedWriteHoldsTheEditBackAndBothLand() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                CountDownLatch writeAtPoint = new CountDownLatch(1);
                CountDownLatch writeGoesOn = new CountDownLatch(1);
                AtomicBoolean atTheWrite = new AtomicBoolean(true);
                AtomicReference<String> writeOf = new AtomicReference<>();
                Thread agent = new Thread(() -> writeOf.set(theAgentWritesTheModule()), "the-writing-agent"); //$NON-NLS-1$
                Pacing.install(point -> {
                    if (Pacing.BEFORE_CONTAINER_WRITE.equals(point) && atTheWrite.compareAndSet(true, false))
                    {
                        writeAtPoint.countDown();
                        await(writeGoesOn);
                    }
                });
                CountDownLatch editAnswered = new CountDownLatch(1);
                AtomicReference<FormEditingSession.Edit> editOf = new AtomicReference<>();
                Thread editor = new Thread(() -> {
                    editOf.set(session.edit("set_property", //$NON-NLS-1$
                        Map.of("property", "title", "value", "Правка поверх записи"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    editAnswered.countDown();
                }, "the-editing-session"); //$NON-NLS-1$
                try
                {
                    agent.start();
                    assertTrue("the write never reached its file write", writeAtPoint.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
                    editor.start();
                    assertFalse("the edit finished while the write held the registry lock", //$NON-NLS-1$
                        editAnswered.await(700, TimeUnit.MILLISECONDS));

                    writeGoesOn.countDown();
                    assertTrue("the edit never finished once the write went on", //$NON-NLS-1$
                        editAnswered.await(10, TimeUnit.SECONDS));
                    agent.join(10_000);
                    editor.join(10_000);
                    String answer = writeOf.get();
                    assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$

                    FormEditingSession.Edit firstRound = editOf.get();
                    assertFalse(firstRound.message, firstRound.done);
                    assertTrue(firstRound.message, firstRound.message.contains("changed outside")); //$NON-NLS-1$
                    assertFalse("the written module is now the session's own", session.moduleChanged()); //$NON-NLS-1$
                    FormEditingSession.Edit secondRound = session.edit("set_property", //$NON-NLS-1$
                        Map.of("property", "title", "value", "Правка поверх записи")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    assertTrue(secondRound.message, secondRound.done);
                    FormEditingSession.Save save = session.save();
                    assertTrue(String.valueOf(save.reconciliation), save.written);
                    OrdinaryFormFile written = theContainersFile();
                    assertTrue(written.formText(), written.formText().contains("Правка поверх записи")); //$NON-NLS-1$
                    assertTrue(written.moduleText(), written.moduleText().contains(WRITTEN_AFTER));
                }
                finally
                {
                    Pacing.clear();
                }
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            assertTrue("the pause never lifted", latch.await(60, TimeUnit.SECONDS)); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static FormEditingSession.Edit theTitleEdit(FormEditingSession session, String title)
    {
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", title)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(edit.message, edit.done);
        return edit;
    }

    /** @return the agent's answer of writing the module, the first variant of its text */
    private static String theAgentWritesTheModule()
    {
        return theAgentWrites(WRITTEN_BEFORE, WRITTEN_AFTER);
    }

    /** @return the agent's answer of the second variant of its module write */
    private static String theAgentWritesTheModuleAgain()
    {
        return theAgentWrites(WRITTEN_AFTER, "Сообщить(\"Оборот 2\");"); //$NON-NLS-1$
    }

    /**
     * Writes the module through the agent's module tool.
     *
     * @param oldText the text to replace
     * @param newText the replacement
     * @return the tool's answer
     */
    private static String theAgentWrites(String oldText, String newText)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", MODULE_PATH); //$NON-NLS-1$
        params.put("mode", "searchReplace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("oldSource", oldText); //$NON-NLS-1$
        params.put("source", newText); //$NON-NLS-1$
        return new ModuleSourceWriter().execute(params);
    }

    private static OrdinaryFormFile theContainersFile() throws java.io.IOException
    {
        return OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath());
    }
}
