/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.OrdinaryFormModule;
import ru.aiedt.mcp.oform.OrdinaryFormModuleProvider;
import ru.aiedt.mcp.oform.Pacing;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.UnsupportedLayoutVersionException;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/**
 * Undo and redo of a {@link FormEditingSession}: ten layout records restore the texts the
 * session opened with and then the texts they left; a later edit drops the redo; the history
 * keeps {@link FormEditingSession#HISTORY_LIMIT} records; a form record undoes and redoes while
 * an agent's module stays; a history that touched the module is cleared when the agent writes
 * the module; an undo over a foreign write reconciles instead of editing a stale baseline; a
 * clean session and the agent's write lose nothing in either order; a gesture is one record;
 * two handler bindings in one gesture both keep their stubs; an undo stops when that
 * reconciliation has already cleared the history; the undo of a stub that created the module
 * entry can restore its absence after saving, and redo brings the stub back.
 */
public class LayoutChangeOperationTest
{
    private static final String PROJECT = "AiEdtOformHistoryProbe"; //$NON-NLS-1$

    private static final String MODULE_PATH = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String WRITTEN_BEFORE = "Сообщить(\"До записи\");"; //$NON-NLS-1$

    private static final String WRITTEN_AFTER = "Сообщить(\"После записи\");"; //$NON-NLS-1$

    private static final String OPENED_MODULE = "Процедура ПриОткрытии()\r\n\t" + WRITTEN_BEFORE //$NON-NLS-1$
        + "\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$

    /** The line a foreign module write shows when it drops a history that touched the module. */
    private static final String HISTORY_CLEARED = "история отмены очищена: модуль изменен вне редактора"; //$NON-NLS-1$

    /** The line a foreign layout shows when it discards the session's edits. */
    private static final String DISCARD = "Форма изменена вне редактора; несохраненные правки отброшены."; //$NON-NLS-1$

    private static SessionProbe probe;

    private static OrdinaryFormModuleProvider registeredHere;

    private static String table;

    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, OPENED_MODULE);
        for (FormLayout.Element element : LayoutReader.read(BraceTree.parse(layout), "Catalog").allElements()) //$NON-NLS-1$
        {
            if ("Table".equals(element.type) && element.name != null) //$NON-NLS-1$
            {
                table = element.name;
                break;
            }
        }
        assertNotNull(table);
        if (ModuleSources.providers().stream().noneMatch(OrdinaryFormModuleProvider.class::isInstance))
        {
            registeredHere = new OrdinaryFormModuleProvider();
            ModuleSources.register(registeredHere);
        }
    }

    @After
    public void noPauseSurvivesATest()
    {
        Pacing.clear();
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

    /** History snapshots retain only touched entries instead of keeping obsolete texts alive. */
    @Test
    public void aRecordRetainsOnlyTheTextsItChanged() throws Exception
    {
        String formBefore = "layout before"; //$NON-NLS-1$
        String formAfter = "layout after"; //$NON-NLS-1$
        String moduleBefore = "module before"; //$NON-NLS-1$
        String moduleAfter = "module after"; //$NON-NLS-1$
        LayoutChangeOperation layoutOnly = new LayoutChangeOperation(null, formBefore, formAfter, true,
            moduleBefore, moduleBefore, false);
        LayoutChangeOperation moduleOnly = new LayoutChangeOperation(null, formBefore, formBefore, false,
            moduleBefore, moduleAfter, true);
        assertEquals(Set.of(formBefore, formAfter), Set.copyOf(retainedTexts(layoutOnly)));
        assertEquals(Set.of(moduleBefore, moduleAfter), Set.copyOf(retainedTexts(moduleOnly)));
        assertTrue(layoutOnly.matches(formAfter, "foreign module", true)); //$NON-NLS-1$
        assertTrue(moduleOnly.matches("foreign layout", moduleAfter, true)); //$NON-NLS-1$
        assertFalse(layoutOnly.matches(formBefore, moduleAfter, true));
        assertFalse(moduleOnly.matches(formAfter, moduleBefore, true));
    }

    /** Inspects retention because replay behavior alone cannot detect an unused snapshot. */
    private static List<String> retainedTexts(LayoutChangeOperation operation) throws Exception
    {
        List<String> texts = new ArrayList<>();
        for (java.lang.reflect.Field field : LayoutChangeOperation.class.getDeclaredFields())
        {
            if (field.getType() == String.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            {
                field.setAccessible(true);
                String text = (String)field.get(operation);
                if (text != null)
                {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    /** The command handlers' direct history route reconciles before replaying touched entries. */
    @Test
    public void theOperationHistoryReconcilesWithoutTheSessionUndoWrapper() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String opened = session.currentFormText();
                edit(session, "set_property", "property", "title", "value", "History route"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                String edited = session.currentFormText();
                byte[] foreignModule = withForeignModule(probe.original(), WRITTEN_AFTER);
                probe.anotherWriterWrites(foreignModule);
                IOperationHistory history = OperationHistoryFactory.getOperationHistory();
                IStatus undo = history.undo(session.undoContext(), new NullProgressMonitor(), null);
                assertTrue(undo.toString(), undo.isOK());
                assertEquals(opened, session.currentFormText());
                assertEquals(WRITTEN_AFTER, session.currentModuleText());
                assertFalse(session.isDirty());
                IStatus redo = history.redo(session.undoContext(), new NullProgressMonitor(), null);
                assertTrue(redo.toString(), redo.isOK());
                assertEquals(edited, session.currentFormText());
                assertEquals(WRITTEN_AFTER, session.currentModuleText());
                assertFalse(session.moduleChanged());
                assertTrue(session.formChanged());
                assertArrayEquals(foreignModule, probe.bytes());

                byte[] foreignLayout = withForeignLayout("Foreign layout"); //$NON-NLS-1$
                probe.anotherWriterWrites(foreignLayout);
                IStatus refused = history.undo(session.undoContext(), new NullProgressMonitor(), null);
                assertFalse(refused.toString(), refused.isOK());
                assertEquals("Foreign layout", session.snapshot().title); //$NON-NLS-1$
                assertFalse(session.isDirty());
                assertFalse(session.canUndo());
                assertFalse(session.canRedo());
                assertArrayEquals(foreignLayout, probe.bytes());
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

    /** Ten layout operations undo to the opened texts and redo to the texts they left. */
    @Test
    public void tenOperationsUndoToTheOriginalTextsAndRedoToTheFinal() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String openedForm = session.currentFormText();
                String openedModule = session.currentModuleText();
                List<Map<String, String>> operations = tenLayoutOnly();
                String[] forms = new String[operations.size() + 1];
                forms[0] = openedForm;
                for (int index = 0; index < operations.size(); index++)
                {
                    Map<String, String> one = new LinkedHashMap<>(operations.get(index));
                    String operation = one.remove("operation"); //$NON-NLS-1$
                    FormEditingSession.Edit edit = session.edit(operation, one);
                    assertTrue(operation + ": " + edit.message, edit.done); //$NON-NLS-1$
                    forms[index + 1] = session.currentFormText();
                    assertEquals(openedModule, session.currentModuleText());
                }
                LayoutChangeOperation[] records = session.undoRecords();
                assertEquals(operations.size(), records.length);
                for (LayoutChangeOperation record : records)
                {
                    assertTrue(record instanceof LayoutChangeOperation);
                    assertFalse(record.touchesModule());
                }
                assertArrayEquals("the file was not saved", probe.original(), probe.bytes()); //$NON-NLS-1$
                for (int undone = 1; undone <= operations.size(); undone++)
                {
                    FormEditingSession.Reversal reversal = session.undo();
                    assertTrue(reversal.message, reversal.done);
                    assertEquals(forms[operations.size() - undone], session.currentFormText());
                    assertEquals(openedModule, session.currentModuleText());
                }
                assertFalse(session.isDirty());
                FormEditingSession.Save save = session.save();
                assertFalse(save.written);
                assertArrayEquals("undo wrote nothing", probe.original(), probe.bytes()); //$NON-NLS-1$
                for (int redone = 1; redone <= operations.size(); redone++)
                {
                    FormEditingSession.Reversal reversal = session.redo();
                    assertTrue(reversal.message, reversal.done);
                    assertEquals(forms[redone], session.currentFormText());
                    assertEquals(openedModule, session.currentModuleText());
                }
                assertEquals(operations.size(), session.undoRecords().length);
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

    /** An edit after an undo drops the redo, so the next undo does not bring the dropped text back. */
    @Test
    public void anEditAfterUndoDropsTheRedo() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                edit(session, "set_property", "property", "title", "value", "Заголовок А"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                String atA = session.currentFormText();
                edit(session, "set_property", "property", "title", "value", "Заголовок Б"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FormEditingSession.Reversal undone = session.undo();
                assertTrue(undone.message, undone.done);
                assertEquals(atA, session.currentFormText());
                assertTrue(session.canRedo());
                edit(session, "set_property", "property", "title", "value", "Заголовок В"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertFalse(session.canRedo());
                assertEquals(0, session.redoRecords().length);
                FormEditingSession.Reversal back = session.undo();
                assertTrue(back.message, back.done);
                assertEquals(atA, session.currentFormText());
                assertEquals("Заголовок А", session.snapshot().title); //$NON-NLS-1$
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

    /** The history keeps the limit and drops the oldest record past it. */
    @Test
    public void theHistoryLimitDropsTheOldest() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String afterFirst = null;
                int kept = FormEditingSession.HISTORY_LIMIT;
                for (int number = 1; number <= kept + 1; number++)
                {
                    edit(session, "set_property", "property", "title", "value", "Заголовок " + number); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    if (number == 1)
                    {
                        afterFirst = session.currentFormText();
                    }
                }
                assertEquals(kept, session.undoRecords().length);
                for (int number = 0; number < kept; number++)
                {
                    FormEditingSession.Reversal reversal = session.undo();
                    assertTrue(reversal.message, reversal.done);
                }
                assertEquals(afterFirst, session.currentFormText());
                assertEquals("Заголовок 1", session.snapshot().title); //$NON-NLS-1$
                assertFalse(session.canUndo());
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

    /** A move undoes and redoes; the module the agent wrote in between stays. */
    @Test
    public void aMoveUndoesAndRedoesWhileTheAgentsModuleStays() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                edit(session, "add_page", "name", "СтраницаХод", "title", "Ход"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                edit(session, "add_element", "type", "Label", "name", "НадписьХод", "caption", "Ход", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    "left", "10", "top", "400", "width", "120", "height", "19"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                String beforeMove = session.currentFormText();
                edit(session, "move_element", "elementName", "НадписьХод", "parent", "form", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "page", "СтраницаХод"); //$NON-NLS-1$ //$NON-NLS-2$
                String afterMove = session.currentFormText();
                assertFalse(beforeMove.equals(afterMove));
                LayoutChangeOperation[] pending = session.undoRecords();
                LayoutChangeOperation move = pending[pending.length - 1];
                assertTrue(move instanceof LayoutChangeOperation);
                assertFalse(move.touchesModule());
                String answer = theAgentWrites(WRITTEN_BEFORE, WRITTEN_AFTER);
                assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                FormEditingSession.Reconciliation taken = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, taken.outcome);
                assertNull(taken.message);
                FormEditingSession.Reversal undone = session.undo();
                assertTrue(undone.message, undone.done);
                assertEquals(beforeMove, session.currentFormText());
                assertTrue(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_AFTER));
                assertFalse(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_BEFORE));
                assertFalse(session.moduleChanged());
                FormEditingSession.Reversal redone = session.redo();
                assertTrue(redone.message, redone.done);
                assertEquals(afterMove, session.currentFormText());
                assertTrue(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_AFTER));
                assertFalse(session.moduleChanged());
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

    /** A saved history that touched the module is cleared, with its line, when the agent writes the module. */
    @Test
    public void aModuleTouchingHistoryIsClearedWhenTheAgentWritesTheModule() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                edit(session, "set_property", "property", "title", "value", "Заголовок А"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                edit(session, "bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(session.moduleChanged());
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                assertTrue(session.canUndo());
                String answer = theAgentWrites(WRITTEN_BEFORE, WRITTEN_AFTER);
                assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                FormEditingSession.Reconciliation taken = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, taken.outcome);
                assertEquals(HISTORY_CLEARED, taken.message);
                assertFalse(session.canUndo());
                assertFalse(session.canRedo());
                assertEquals(0, session.undoRecords().length);
                assertEquals(0, session.redoRecords().length);
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

    /** Undo over a foreign layout, with no observer event yet, reconciles and does not write the old text. */
    @Test
    public void anUndoAfterAForeignWriteReconciles() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                edit(session, "set_property", "property", "title", "value", "Проба"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(session.isDirty());
                byte[] foreign = withForeignLayout("Чужой заголовок"); //$NON-NLS-1$
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Reversal reversal = session.undo();
                assertFalse(reversal.done);
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, reversal.reconciliation);
                assertEquals(DISCARD, reversal.message);
                assertEquals(0, session.undoRecords().length);
                assertEquals(0, session.redoRecords().length);
                assertEquals("Чужой заголовок", session.snapshot().title); //$NON-NLS-1$
                assertArrayEquals(foreign, probe.bytes());
                assertFalse(session.isDirty());
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
     * Undo of a clean session, stopped before it fixes the text, holds the agent's write. Both
     * finish: the form is the text from before the edit, the module is the agent's.
     */
    @Test
    public void anUndoOnACleanSessionHoldsTheAgentsWriteAndBothLand() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String opened = session.currentFormText();
                edit(session, "set_property", "property", "title", "value", "Правка сеанса"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                assertFalse(session.isDirty());
                assertTrue(session.canUndo());
                CountDownLatch undoAtPoint = new CountDownLatch(1);
                CountDownLatch undoGoesOn = new CountDownLatch(1);
                AtomicBoolean atTheFixation = new AtomicBoolean(true);
                AtomicReference<FormEditingSession.Reversal> reversalOf = new AtomicReference<>();
                Pacing.install(point -> {
                    if (Pacing.BEFORE_EDIT_FIXATION.equals(point) && atTheFixation.compareAndSet(true, false))
                    {
                        undoAtPoint.countDown();
                        await(undoGoesOn);
                    }
                });
                Thread undoing = new Thread(() -> {
                    try
                    {
                        reversalOf.set(session.undo());
                    }
                    finally
                    {
                        undoAtPoint.countDown();
                    }
                }, "the-undo"); //$NON-NLS-1$
                CountDownLatch writeAnswered = new CountDownLatch(1);
                AtomicReference<String> writeOf = new AtomicReference<>();
                Thread agent = new Thread(() -> {
                    try
                    {
                        writeOf.set(theAgentWrites(WRITTEN_BEFORE, WRITTEN_AFTER));
                    }
                    finally
                    {
                        writeAnswered.countDown();
                    }
                }, "the-writing-agent"); //$NON-NLS-1$
                try
                {
                    undoing.start();
                    assertTrue("the undo never reached its fixation", undoAtPoint.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
                    agent.start();
                    assertFalse("the write finished while the undo held the registry lock", //$NON-NLS-1$
                        writeAnswered.await(700, TimeUnit.MILLISECONDS));
                    undoGoesOn.countDown();
                    assertTrue("the write never finished once the undo went on", //$NON-NLS-1$
                        writeAnswered.await(10, TimeUnit.SECONDS));
                    undoing.join(10_000);
                    agent.join(10_000);
                    FormEditingSession.Reversal reversal = reversalOf.get();
                    assertTrue(reversal.message, reversal.done);
                    assertNull(reversal.reconciliation);
                    String answer = writeOf.get();
                    assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                    assertTrue("the write's answer names the editor", answer.contains("unsavedInEditor")); //$NON-NLS-1$
                    FormEditingSession.Reconciliation taken = session.reconcile();
                    assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, taken.outcome);
                    assertNull(taken.message);
                    assertEquals(opened, session.currentFormText());
                    assertTrue(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_AFTER));
                    assertFalse(session.moduleChanged());
                    assertTrue(session.formChanged());
                    assertTrue(session.canRedo());
                }
                finally
                {
                    undoGoesOn.countDown();
                    Pacing.clear();
                    undoing.join(10_000);
                    agent.join(10_000);
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
     * The agent's write, stopped before it replaces the file, holds the undo of a clean session.
     * The undo then accepts the written module and still applies the form record.
     */
    @Test
    public void theAgentsWriteHoldsTheUndoOnACleanSessionAndBothLand() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String opened = session.currentFormText();
                edit(session, "set_property", "property", "title", "value", "Правка сеанса"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                assertFalse(session.isDirty());
                CountDownLatch writeAtPoint = new CountDownLatch(1);
                CountDownLatch writeGoesOn = new CountDownLatch(1);
                AtomicBoolean atTheWrite = new AtomicBoolean(true);
                AtomicReference<String> writeOf = new AtomicReference<>();
                Thread agent = new Thread(() -> {
                    try
                    {
                        writeOf.set(theAgentWrites(WRITTEN_BEFORE, WRITTEN_AFTER));
                    }
                    finally
                    {
                        writeAtPoint.countDown();
                    }
                }, "the-writing-agent"); //$NON-NLS-1$
                Pacing.install(point -> {
                    if (Pacing.BEFORE_CONTAINER_WRITE.equals(point) && atTheWrite.compareAndSet(true, false))
                    {
                        writeAtPoint.countDown();
                        await(writeGoesOn);
                    }
                });
                CountDownLatch undoAnswered = new CountDownLatch(1);
                AtomicReference<FormEditingSession.Reversal> reversalOf = new AtomicReference<>();
                Thread undoing = new Thread(() -> {
                    try
                    {
                        reversalOf.set(session.undo());
                    }
                    finally
                    {
                        undoAnswered.countDown();
                    }
                }, "the-undo"); //$NON-NLS-1$
                try
                {
                    agent.start();
                    assertTrue("the write never reached its file write", writeAtPoint.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
                    undoing.start();
                    assertFalse("the undo finished while the write held the registry lock", //$NON-NLS-1$
                        undoAnswered.await(700, TimeUnit.MILLISECONDS));
                    writeGoesOn.countDown();
                    assertTrue("the undo never finished once the write went on", //$NON-NLS-1$
                        undoAnswered.await(10, TimeUnit.SECONDS));
                    agent.join(10_000);
                    undoing.join(10_000);
                    String answer = writeOf.get();
                    assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
                    FormEditingSession.Reversal reversal = reversalOf.get();
                    assertTrue(reversal.message, reversal.done);
                    assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, reversal.reconciliation);
                    assertEquals(opened, session.currentFormText());
                    assertTrue(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_AFTER));
                    assertFalse(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_BEFORE));
                    assertFalse(session.moduleChanged());
                    assertTrue(session.formChanged());
                }
                finally
                {
                    writeGoesOn.countDown();
                    Pacing.clear();
                    agent.join(10_000);
                    undoing.join(10_000);
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

    /** Two layout operations fixed together are one history record. */
    @Test
    public void aGestureIsOneHistoryRecord() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String before = session.currentFormText();
                List<FormEditingSession.Step> steps = List.of(
                    new FormEditingSession.Step("set_property", args("property", "title", "value", "Жест")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new FormEditingSession.Step("add_page", args("name", "СтраницаЖест", "title", "Жест"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FormEditingSession.Edit gesture = session.gesture(steps);
                assertTrue(gesture.message, gesture.done);
                assertEquals(1, session.undoRecords().length);
                assertTrue(session.undoRecords()[0] instanceof LayoutChangeOperation);
                String after = session.currentFormText();
                assertEquals("Жест", session.snapshot().title); //$NON-NLS-1$
                assertTrue(after, after.contains("СтраницаЖест")); //$NON-NLS-1$
                FormEditingSession.Reversal undone = session.undo();
                assertTrue(undone.message, undone.done);
                assertEquals(before, session.currentFormText());
                assertFalse(session.currentFormText().contains("СтраницаЖест")); //$NON-NLS-1$
                FormEditingSession.Reversal redone = session.redo();
                assertTrue(redone.message, redone.done);
                assertEquals(after, session.currentFormText());
                assertEquals(1, session.undoRecords().length);
                assertEquals("Жест", session.snapshot().title); //$NON-NLS-1$
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
     * Two {@code bind_handler} steps with different procedures and {@code stub=true}, fixed as
     * one gesture, keep both stubs. Each step has to read the module the previous step produced:
     * reading the file's original module on every step leaves only the last procedure, while both
     * bindings stay in the layout.
     */
    @Test
    public void aGestureOfTwoHandlerStubsKeepsBothProcedures() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                String openedModule = session.currentModuleText();
                String rowProcedure = "ТаблицаПриАктивизацииСтроки"; //$NON-NLS-1$
                String columnProcedure = "ТаблицаПриАктивизацииКолонки"; //$NON-NLS-1$
                List<FormEditingSession.Step> steps = List.of(
                    new FormEditingSession.Step("bind_handler", args("elementName", table, //$NON-NLS-1$
                        "event", "ПриАктивизацииСтроки", "procedure", rowProcedure, "stub", "true")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    new FormEditingSession.Step("bind_handler", args("elementName", table, //$NON-NLS-1$
                        "event", "ПриАктивизацииКолонки", "procedure", columnProcedure, "stub", "true"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                FormEditingSession.Edit gesture = session.gesture(steps);
                assertTrue(gesture.message, gesture.done);
                assertEquals(1, session.undoRecords().length);
                assertTrue(session.undoRecords()[0].touchesModule());
                assertBothHandlers(session, rowProcedure, columnProcedure);
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                String savedModule = moduleOnDisk();
                assertTrue(savedModule, savedModule.contains("Процедура " + rowProcedure)); //$NON-NLS-1$
                assertTrue(savedModule, savedModule.contains("Процедура " + columnProcedure)); //$NON-NLS-1$
                assertBothHandlers(session, rowProcedure, columnProcedure);
                FormEditingSession.Reversal undone = session.undo();
                assertTrue(undone.message, undone.done);
                assertEquals(openedModule, session.currentModuleText());
                assertFalse(handlerBound(session.snapshot(), table, "ПриАктивизацииСтроки", rowProcedure)); //$NON-NLS-1$
                assertFalse(handlerBound(session.snapshot(), table, "ПриАктивизацииКолонки", columnProcedure)); //$NON-NLS-1$
                FormEditingSession.Reversal redone = session.redo();
                assertTrue(redone.message, redone.done);
                assertBothHandlers(session, rowProcedure, columnProcedure);
                assertEquals(1, session.undoRecords().length);
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
     * Undo after a foreign module write, before any observer runs, stops when the reconciliation
     * clears the history. The history holds a handler record and then a title record; the title
     * still matches, and applying it would put that record back and drop the saved title.
     */
    @Test
    public void anUndoStopsWhenReconciliationClearsTheHistory() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                edit(session, "bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                edit(session, "set_property", "property", "title", "value", "Заголовок Вне"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                assertFalse(session.isDirty());
                LayoutChangeOperation[] pending = session.undoRecords();
                assertEquals(2, pending.length);
                assertTrue(pending[0].touchesModule());
                assertFalse(pending[1].touchesModule());
                String savedModule = session.currentModuleText();
                assertTrue(savedModule, savedModule.contains(WRITTEN_BEFORE));
                byte[] foreign = withForeignModule(probe.bytes(), savedModule.replace(WRITTEN_BEFORE, WRITTEN_AFTER));
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Reversal reversal = session.undo();
                assertFalse(reversal.done);
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, reversal.reconciliation);
                assertEquals(HISTORY_CLEARED, reversal.message);
                assertEquals(0, session.undoRecords().length);
                assertEquals(0, session.redoRecords().length);
                assertFalse(session.canUndo());
                assertFalse(session.canRedo());
                assertEquals("Заголовок Вне", session.snapshot().title); //$NON-NLS-1$
                assertTrue(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_AFTER));
                assertFalse(session.currentModuleText(), session.currentModuleText().contains(WRITTEN_BEFORE));
                assertFalse(session.isDirty());
                assertArrayEquals(foreign, probe.bytes());
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

    /** The saved stub can be undone to absence and redone without losing other entries. */
    @Test
    public void anUndoOfASavedCreatedModuleEntryRestoresAbsence() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        SessionProbe moduleless = SessionProbe.withModuleless(PROJECT + "NoModule", layout); //$NON-NLS-1$
        try
        {
            FormEditingSession session = OpenFormSessions.open(moduleless.file());
            try
            {
                byte[] unknown = V8Container.read(moduleless.original()).find("unknown").getData(); //$NON-NLS-1$
                assertNull(session.currentModuleText());
                edit(session, "bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                String stub = session.currentModuleText();
                String boundForm = session.currentFormText();
                assertTrue(stub, stub.contains("Процедура ТаблицаПриАктивизацииСтроки")); //$NON-NLS-1$
                assertTrue(session.save().written);
                assertEquals(stub, OrdinaryFormFile.of(null, V8Container.read(moduleless.bytes()), moduleless.bytes()).moduleText());
                FormEditingSession.Reversal undone = session.undo();
                assertTrue(undone.message, undone.done);
                assertNull(session.currentModuleText());
                assertTrue(session.moduleChanged());
                assertFalse(handlerBound(session.snapshot(), table, "ПриАктивизацииСтроки", //$NON-NLS-1$
                    "ТаблицаПриАктивизацииСтроки")); //$NON-NLS-1$
                assertTrue(session.save().written);
                V8Container removed = V8Container.read(moduleless.bytes());
                assertNull(removed.find(OrdinaryFormFile.MODULE_ENTRY));
                assertArrayEquals(unknown, removed.find("unknown").getData()); //$NON-NLS-1$
                assertEquals(layout, session.currentFormText());
                FormEditingSession.Reversal redone = session.redo();
                assertTrue(redone.message, redone.done);
                assertEquals(stub, session.currentModuleText());
                assertEquals(boundForm, session.currentFormText());
                assertTrue(session.save().written);
                V8Container restored = V8Container.read(moduleless.bytes());
                assertEquals(stub, OrdinaryFormFile.of(null, restored, moduleless.bytes()).moduleText());
                assertArrayEquals(unknown, restored.find("unknown").getData()); //$NON-NLS-1$
                assertFalse(session.isDirty());
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            moduleless.dispose();
        }
    }

    /** An agent cannot replace a module while the session holds its absence after undo. */
    @Test
    public void anAgentCannotOverwriteAHeldAbsentModule() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        SessionProbe moduleless = SessionProbe.withModuleless(PROJECT + "HeldAbsence", layout); //$NON-NLS-1$
        try
        {
            FormEditingSession session = OpenFormSessions.open(moduleless.file());
            try
            {
                assertNull(session.currentModuleText());
                edit(session, "bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                String stub = session.currentModuleText();
                assertTrue(session.save().written);
                FormEditingSession.Reversal undo = session.undo();
                assertTrue(undo.message, undo.done);
                assertNull(session.currentModuleText());
                assertTrue(session.moduleChanged());
                byte[] before = moduleless.bytes();
                LayoutChangeOperation[] undoBefore = session.undoRecords();
                LayoutChangeOperation[] redoBefore = session.redoRecords();
                assertTrue(session.canRedo());

                OrdinaryFormModule agent = OrdinaryFormModule.locate(moduleless.project(), MODULE_PATH);
                try
                {
                    agent.writeEntries(null, null, "Agent module"); //$NON-NLS-1$
                    fail("the agent write must be refused"); //$NON-NLS-1$
                }
                catch (IOException refusal)
                {
                    assertTrue(refusal.getMessage(), refusal.getMessage().contains("unsaved changes")); //$NON-NLS-1$
                }
                assertArrayEquals(before, moduleless.bytes());
                assertNull(session.currentModuleText());
                assertTrue(session.moduleChanged());
                assertArrayEquals(undoBefore, session.undoRecords());
                assertArrayEquals(redoBefore, session.redoRecords());
                assertTrue(session.canRedo());

                assertTrue(session.save().written);
                assertNull(V8Container.read(moduleless.bytes()).find(OrdinaryFormFile.MODULE_ENTRY));
                assertNull(session.currentModuleText());
                assertFalse(session.moduleChanged());
                FormEditingSession.Reversal redo = session.redo();
                assertTrue(redo.message, redo.done);
                assertEquals(stub, session.currentModuleText());
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            moduleless.dispose();
        }
    }

    /** An empty existing module must remain an entry after undo and save. */
    @Test
    public void anUndoOfASavedStubRestoresAnEmptyModule() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        SessionProbe empty = SessionProbe.with(PROJECT + "EmptyModule", layout, ""); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            FormEditingSession session = OpenFormSessions.open(empty.file());
            try
            {
                edit(session, "bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(session.save().written);
                assertTrue(session.undo().done);
                assertEquals("", session.currentModuleText()); //$NON-NLS-1$
                assertTrue(session.save().written);
                OrdinaryFormFile saved = OrdinaryFormFile.of(null, V8Container.read(empty.bytes()), empty.bytes());
                assertEquals("", saved.moduleText()); //$NON-NLS-1$
                assertNotNull(saved.getContainer().find(OrdinaryFormFile.MODULE_ENTRY));
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            empty.dispose();
        }
    }

    /**
     * The same record over the same moduleless container, undone before any save: the module
     * entry the stub created is the session's own edit, the baseline still holds no entry, so
     * the undo returns the absent entry, the session is clean, a save writes nothing, and the
     * redo brings the stub back.
     */
    @Test
    public void anUndoBeforeASaveReturnsTheAbsentModuleEntry() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        SessionProbe moduleless = SessionProbe.withModuleless(PROJECT + "NoModuleUndo", layout); //$NON-NLS-1$
        try
        {
            FormEditingSession session = OpenFormSessions.open(moduleless.file());
            try
            {
                assertNull(session.currentModuleText());
                edit(session, "bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                String stub = session.currentModuleText();
                FormEditingSession.Reversal undone = session.undo();
                assertTrue(undone.message, undone.done);
                assertNull(session.currentModuleText());
                assertFalse(session.moduleChanged());
                assertFalse(handlerBound(session.snapshot(), table, "ПриАктивизацииСтроки", //$NON-NLS-1$
                    "ТаблицаПриАктивизацииСтроки")); //$NON-NLS-1$
                assertFalse(session.isDirty());
                FormEditingSession.Save save = session.save();
                assertFalse(save.written);
                assertArrayEquals("the undo wrote nothing", moduleless.original(), moduleless.bytes()); //$NON-NLS-1$
                FormEditingSession.Reversal redone = session.redo();
                assertTrue(redone.message, redone.done);
                assertEquals(stub, session.currentModuleText());
                assertTrue(session.moduleChanged());
                assertTrue(handlerBound(session.snapshot(), table, "ПриАктивизацииСтроки", //$NON-NLS-1$
                    "ТаблицаПриАктивизацииСтроки")); //$NON-NLS-1$
                assertEquals(1, session.undoRecords().length);
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            moduleless.dispose();
        }
    }

    /** How long one undo takes on the largest ordinary-form fixture. */
    @Test
    public void theUndoOfTheLargestFixtureIsTimed() throws Exception
    {
        Pacing.clear();
        String layout = SessionProbe.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        SessionProbe largest = SessionProbe.with("AiEdtOformHistoryLargestProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession session = null;
        try
        {
            session = OpenFormSessions.open(largest.file());
            String before = session.currentFormText();
            FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
                args("property", "title", "value", "Замер")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(edit.message, edit.done);
            long started = System.nanoTime();
            FormEditingSession.Reversal reversal = session.undo();
            long nanos = System.nanoTime() - started;
            String millis = String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0); //$NON-NLS-1$
            System.out.println("undo of fixtures/processing-form-v27.form took " + millis + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(reversal.message, reversal.done);
            assertEquals(before, session.currentFormText());
        }
        finally
        {
            if (session != null)
            {
                OpenFormSessions.release(session);
            }
            largest.dispose();
        }
    }

    // ---- the chain and the writers ---------------------------------------------------------

    /** Ten layout operations that do not write the module. The button is added with no stub. */
    private static List<Map<String, String>> tenLayoutOnly()
    {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(named("set_property", "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(named("add_page", "name", "СтраницаПроба", "title", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(named("add_element", "type", "Label", "name", "НадписьПроба", "caption", "Проба", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "left", "10", "top", "400", "width", "120", "height", "19")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        out.add(named("move_element", "elementName", "НадписьПроба", "parent", "form", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "page", "СтраницаПроба")); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(named("remove_element", "elementName", "НадписьПроба")); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(named("remove_page", "name", "СтраницаПроба")); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(named("add_command_panel", "name", "ПанельПроба", "left", "0", "top", "500", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "width", "400", "height", "25")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(named("add_button", "panel", "ПанельПроба", "name", "КнопкаПроба", "caption", "Проба", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "procedure", "ПробаНажатие", "stub", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(named("add_submenu", "panel", "ПанельПроба", "name", "ПодменюПроба", "caption", "Еще")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(named("add_separator", "panel", "ПанельПроба", "name", "РазделительПроба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return out;
    }

    private static Map<String, String> named(String operation, String... pairs)
    {
        Map<String, String> map = args(pairs);
        map.put("operation", operation); //$NON-NLS-1$
        return map;
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("language", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int index = 0; index + 1 < pairs.length; index += 2)
        {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    /** Applies one layout operation and requires it to land. */
    private static FormEditingSession.Edit edit(FormEditingSession session, String operation, String... pairs)
    {
        FormEditingSession.Edit edit = session.edit(operation, args(pairs));
        assertTrue(operation + ": " + edit.message, edit.done); //$NON-NLS-1$
        return edit;
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

    /**
     * The original container with another title in its layout.
     *
     * @param title the foreign title
     * @return the container's bytes
     */
    private static byte[] withForeignLayout(String title)
    {
        OrdinaryFormFile file = OrdinaryFormFile.of(null, V8Container.read(probe.original()), probe.original());
        try
        {
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(file.formText()), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome set = editor.setTitle(title);
            assertTrue(set.message, set.done);
            file.setFormText(editor.text());
        }
        catch (UnsupportedLayoutVersionException e)
        {
            throw new AssertionError(e.getMessage(), e);
        }
        return file.toBytes();
    }

    /**
     * The saved container with another module text and the same layout.
     *
     * @param saved the container bytes the session saved
     * @param module the foreign module text
     * @return the container's bytes
     */
    private static byte[] withForeignModule(byte[] saved, String module)
    {
        OrdinaryFormFile file = OrdinaryFormFile.of(null, V8Container.read(saved), saved);
        file.setModuleText(module);
        return file.toBytes();
    }

    /** @return the module text the container on disk holds now */
    private static String moduleOnDisk()
    {
        byte[] bytes = probe.bytes();
        return OrdinaryFormFile.of(null, V8Container.read(bytes), bytes).moduleText();
    }

    /** Both gesture bindings are in the layout and both procedures are in the module. */
    private static void assertBothHandlers(FormEditingSession session, String rowProcedure, String columnProcedure)
    {
        String module = session.currentModuleText();
        assertTrue(module, module.contains("Процедура " + rowProcedure)); //$NON-NLS-1$
        assertTrue(module, module.contains("Процедура " + columnProcedure)); //$NON-NLS-1$
        FormSnapshot snapshot = session.snapshot();
        assertTrue(handlerBound(snapshot, table, "ПриАктивизацииСтроки", rowProcedure)); //$NON-NLS-1$
        assertTrue(handlerBound(snapshot, table, "ПриАктивизацииКолонки", columnProcedure)); //$NON-NLS-1$
    }

    /** @return whether the named element has that handler */
    private static boolean handlerBound(FormSnapshot snapshot, String elementName, String event, String procedure)
    {
        for (FormSnapshot.Element element : snapshot.allElements)
        {
            if (!elementName.equals(element.name))
            {
                continue;
            }
            for (FormSnapshot.Handler handler : element.handlers)
            {
                if (event.equals(handler.event) && procedure.equals(handler.procedure))
                {
                    return true;
                }
            }
        }
        return false;
    }
}
