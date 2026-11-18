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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.OrdinaryFormModule;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.server.support.SyncBaseline;

/**
 * A live editing session follows a real workspace move of its owner folder, and a removal that
 * is not a move refuses later operations. The moves are {@link IFolder#move}; the tests do not
 * build a resource delta by hand.
 */
public class SessionResourceListenerTest
{
    private static final String REMOVED = "the form was removed; the session is not bound to a file"; //$NON-NLS-1$

    private static final String TITLE = "ПробаПереноса"; //$NON-NLS-1$

    private static final String TITLE_AGAIN = "ПробаПереноса2"; //$NON-NLS-1$

    private static final String PROCEDURE = "ПробаПереноса"; //$NON-NLS-1$

    private static final String AGENT_MODULE = "Процедура ЧужойАгент()\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$

    private static final byte[] UNKNOWN = { 9, 8, 7, 6 };

    private static final byte[] OLD_SIGNATURE = { 1, 2, 3, 4 };

    private static final byte[] NEW_SIGNATURE = { 4, 3, 2, 1 };

    private static final String OLD_OWNER = "src/Catalogs/Products/Products.mdo"; //$NON-NLS-1$

    private static final String NEW_OWNER = "src/Catalogs/Renamed/Renamed.mdo"; //$NON-NLS-1$

    private static final long WAIT_MS = 20_000L;

    private static String layout;

    private static String table;

    /** Loads the fixture text. Each test builds and deletes its own project. */
    @BeforeClass
    public static void theFixture() throws Exception
    {
        layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        for (FormLayout.Element element : LayoutReader.read(BraceTree.parse(layout), "Catalog").allElements()) //$NON-NLS-1$
        {
            if ("Table".equals(element.type) && element.name != null) //$NON-NLS-1$
            {
                table = element.name;
                break;
            }
        }
        assertNotNull(table);
    }

    /** Drops the static seams a test may have installed. */
    @After
    public void theSeamsGo()
    {
        OpenFormSessions.setNotice(null);
        OpenFormSessions.setDeferNotices(false);
        OpenFormSessions.discardDeferredNotices();
        OpenFormSessions.setLockStep(null);
        FormEditingSession.setSaveBarrier(null);
    }

    /**
     * Renames the owner while the form and the module are both dirty. The same session, its
     * texts and its history stay on the new file. An agent write at the new path is refused
     * before the move notice runs, and the save writes only the new file.
     */
    @Test
    public void aMoveKeepsTheDirtySessionAndRefusesAnAgentWriteUntilTheNoticeRuns() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveKeepProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession session = null;
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        try
        {
            V8Container seeded = V8Container.read(probe.bytes());
            seeded.add(V8Container.Entry.of("unknown", UNKNOWN.clone())); //$NON-NLS-1$
            probe.anotherWriterWrites(seeded.write());
            Path index = writeBaseline(probe.project(), OLD_OWNER, NEW_OWNER);
            session = OpenFormSessions.open(probe.file());
            assertTrue(OpenFormSessions.listenerInstalled());
            dirtyFormAndModule(session);
            String id = session.id();
            String formText = session.currentFormText();
            String moduleText = session.currentModuleText();
            assertTrue(formText.contains(TITLE));
            assertTrue(moduleText.contains(PROCEDURE));
            assertTrue(session.canUndo());

            OpenFormSessions.setDeferNotices(true);
            OpenFormSessions.setNotice(recording(events));
            IFile destination = moveOwner(probe.project(), "Products", "Renamed"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the notice waits until the test asks for it", events.isEmpty()); //$NON-NLS-1$
            assertSame(session, boundSession(session));
            assertEquals(id, session.id());
            assertEquals(destination.getFullPath().toPortableString(), session.file().getFullPath().toPortableString());
            assertEquals(formText, session.currentFormText());
            assertEquals(moduleText, session.currentModuleText());
            assertTrue(session.isDirty());
            assertTrue(session.canUndo());
            assertTrue(OpenFormSessions.holdsChanges(destination));
            assertEquals(0, OpenFormSessions.editorsOf(probe.file()));
            assertEquals(1, OpenFormSessions.editorsOf(destination));
            assertFalse(probe.file().exists());

            byte[] beforePreview = readBytes(destination);
            FormEditingSession.Preview preview = session.preview("set_property", Map.of( //$NON-NLS-1$
                "property", "title", "value", TITLE_AGAIN)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(preview.message, preview.done);
            assertArrayEquals(beforePreview, readBytes(destination));

            FormEditingSession.Edit gesture = session.gesture(List.of(new FormEditingSession.Step("set_property", //$NON-NLS-1$
                Map.of("property", "title", "value", TITLE_AGAIN)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(gesture.message, gesture.done);
            FormEditingSession.Reversal undo = session.undo();
            assertTrue(undo.message, undo.done);
            assertEquals(formText, session.currentFormText());
            FormEditingSession.Reversal redo = session.redo();
            assertTrue(redo.message, redo.done);
            assertTrue(session.currentFormText().contains(TITLE_AGAIN));
            FormEditingSession.Reversal back = session.undo();
            assertTrue(back.message, back.done);
            assertEquals(formText, session.currentFormText());
            assertEquals(moduleText, session.currentModuleText());

            byte[] beforeAgent = readBytes(destination);
            OrdinaryFormModule module = moduleAt(destination);
            assertNotNull(module);
            try
            {
                module.writeEntriesReturning(null, null, AGENT_MODULE, null, false);
                fail("the guarded writer changed a dirty form"); //$NON-NLS-1$
            }
            catch (IOException e)
            {
                assertTrue(e.getMessage(), e.getMessage().contains(
                    "src/Catalogs/Renamed/Forms/ItemForm/Form.oform")); //$NON-NLS-1$
                assertTrue(e.getMessage(), e.getMessage().contains("Nothing was written.")); //$NON-NLS-1$
            }
            assertArrayEquals(beforeAgent, readBytes(destination));
            assertTrue("the notice is still waiting", events.isEmpty()); //$NON-NLS-1$

            FormEditingSession.Reconciliation reconciliation = session.reconcile();
            assertEquals(FormEditingSession.Reconciliation.NOTHING, reconciliation.outcome);
            assertTrue(session.isDirty());
            FormEditingSession.Save save = session.save();
            assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
            assertFalse(probe.file().exists());
            assertTrue(destination.exists());
            OrdinaryFormFile written = readContainer(destination);
            assertTrue(written.formText().contains(TITLE));
            assertTrue(written.moduleText().contains(PROCEDURE));
            assertArrayEquals(UNKNOWN, V8Container.read(readBytes(destination)).find("unknown").getData()); //$NON-NLS-1$
            assertOwnerMarks(index, OLD_OWNER, NEW_OWNER);
            assertNotNull(save.delivery);
            assertTrue(save.delivery, save.delivery.contains(NEW_OWNER));

            OpenFormSessions.deliverDeferredNotices();
            assertTrue(events.contains("moved")); //$NON-NLS-1$
            assertFalse(events.contains("removed")); //$NON-NLS-1$
        }
        finally
        {
            OpenFormSessions.release(session);
            if (OpenFormSessions.liveBindings().isEmpty())
            {
                assertFalse(OpenFormSessions.listenerInstalled());
            }
            probe.dispose();
        }
    }

    /**
     * Two editors of one form still share the session after the owner folder moves. Closing one
     * leaves the other's protection and history in place.
     */
    @Test
    public void twoEditorsStillShareTheMovedSession() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveShareProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession first = null;
        FormEditingSession second = null;
        try
        {
            first = OpenFormSessions.open(probe.file());
            second = OpenFormSessions.open(probe.file());
            assertSame(first, second);
            dirtyFormAndModule(first);
            IFile destination = moveOwner(probe.project(), "Products", "Renamed"); //$NON-NLS-1$ //$NON-NLS-2$
            assertSame(first, second);
            assertSame(first, boundSession(first));
            assertEquals(1, countBindings(first));
            assertEquals(destination.getFullPath().toPortableString(), first.file().getFullPath().toPortableString());
            assertEquals(2, OpenFormSessions.editorsOf(destination));
            assertEquals(0, OpenFormSessions.editorsOf(probe.file()));

            OpenFormSessions.release(second);
            second = null;
            assertEquals(1, OpenFormSessions.editorsOf(destination));
            assertTrue(OpenFormSessions.isHeld(destination));
            assertTrue(first.isDirty());
            assertTrue(first.canUndo());
            assertTrue(OpenFormSessions.holdsChanges(destination));
            byte[] before = readBytes(destination);
            try
            {
                moduleAt(destination).writeEntriesReturning(null, null, AGENT_MODULE, null, false);
                fail("one editor closing must not drop the other editor's protection"); //$NON-NLS-1$
            }
            catch (IOException e)
            {
                assertTrue(e.getMessage(), e.getMessage().contains("Nothing was written.")); //$NON-NLS-1$
            }
            assertArrayEquals(before, readBytes(destination));

            OpenFormSessions.release(first);
            first = null;
            assertEquals(0, OpenFormSessions.editorsOf(destination));
            assertFalse(OpenFormSessions.isHeld(destination));
            assertFalse(OpenFormSessions.holdsChanges(destination));
            if (OpenFormSessions.liveBindings().isEmpty())
            {
                assertFalse(OpenFormSessions.listenerInstalled());
            }
        }
        finally
        {
            OpenFormSessions.release(second);
            OpenFormSessions.release(first);
            probe.dispose();
        }
    }

    /**
     * Two owner folders move in opposite lexicographic directions at the same time. Each move
     * takes its registry locks in path order, and both moves finish.
     */
    @Test
    public void opposingMovesFinishInLexicographicLockOrder() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveOpposeProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession alpha = null;
        FormEditingSession zulu = null;
        List<String[]> pairs = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Thread alphaMove = null;
        Thread zuluMove = null;
        try
        {
            IFile alphaFile = plant(probe.project(), "Alpha", probe.original()); //$NON-NLS-1$
            IFile zuluFile = plant(probe.project(), "Zulu", probe.original()); //$NON-NLS-1$
            alpha = OpenFormSessions.open(alphaFile);
            zulu = OpenFormSessions.open(zuluFile);
            OpenFormSessions.setLockStep((first, second) -> pairs.add(new String[] {
                first.getFullPath().toPortableString(), second.getFullPath().toPortableString() }));
            IProject project = probe.project();
            alphaMove = new Thread(() -> moveCatalog(project, "Alpha", "Mover", failures), "alpha-move"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zuluMove = new Thread(() -> moveCatalog(project, "Zulu", "Beta", failures), "zulu-move"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            alphaMove.start();
            zuluMove.start();
            alphaMove.join(WAIT_MS);
            zuluMove.join(WAIT_MS);
            assertFalse("Alpha to Mover did not finish", alphaMove.isAlive()); //$NON-NLS-1$
            assertFalse("Zulu to Beta did not finish", zuluMove.isAlive()); //$NON-NLS-1$
            if (!failures.isEmpty())
            {
                fail(String.valueOf(failures.get(0)));
            }
            List<String[]> recorded;
            synchronized (pairs)
            {
                recorded = new ArrayList<>(pairs);
            }
            assertTrue("a move recorded no lock pair: " + recorded.size(), recorded.size() >= 2); //$NON-NLS-1$
            for (String[] pair : recorded)
            {
                assertTrue(pair[0] + " then " + pair[1], pair[0].compareTo(pair[1]) <= 0); //$NON-NLS-1$
            }
            IFile mover = probe.project().getFile(new org.eclipse.core.runtime.Path("src/Catalogs/Mover/Forms/ItemForm/Form.oform")); //$NON-NLS-1$
            IFile beta = probe.project().getFile(new org.eclipse.core.runtime.Path("src/Catalogs/Beta/Forms/ItemForm/Form.oform")); //$NON-NLS-1$
            assertEquals(mover.getFullPath().toPortableString(), alpha.file().getFullPath().toPortableString());
            assertEquals(beta.getFullPath().toPortableString(), zulu.file().getFullPath().toPortableString());
            assertTrue(mover.exists());
            assertTrue(beta.exists());
            assertFalse(alphaFile.exists());
            assertFalse(zuluFile.exists());
            assertSame(alpha, boundSession(alpha));
            assertSame(zulu, boundSession(zulu));
        }
        finally
        {
            stop(alphaMove);
            stop(zuluMove);
            OpenFormSessions.release(alpha);
            OpenFormSessions.release(zulu);
            if (alphaMove == null || !alphaMove.isAlive())
            {
                if (zuluMove == null || !zuluMove.isAlive())
                {
                    probe.dispose();
                }
            }
        }
    }

    /**
     * An undo that is already waiting on the file's old registry lock follows the session onto
     * the new file. It does not write the old path.
     */
    @Test
    public void anUndoWaitingOnTheOldKeyFollowsTheNewBinding() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveUndoProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession session = null;
        CountDownLatch gate = new CountDownLatch(1);
        Thread mover = null;
        Thread undo = null;
        try
        {
            session = OpenFormSessions.open(probe.file());
            String beforeEdit = session.currentFormText();
            FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
                "property", "title", "value", TITLE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(edit.message, edit.done);
            assertTrue(session.canUndo());
            byte[] beforeMove = readBytes(probe.file());
            List<String[]> pairs = Collections.synchronizedList(new ArrayList<>());
            OpenFormSessions.setLockStep((first, second) -> {
                pairs.add(new String[] {
                    first.getFullPath().toPortableString(), second.getFullPath().toPortableString() });
                awaitGate(gate);
            });
            IProject project = probe.project();
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            mover = new Thread(() -> moveCatalog(project, "Products", "ZRenamed", failures), "undo-move"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            mover.start();
            Thread parked = mover;
            spinUntil(() -> !pairs.isEmpty() && isParked(parked), "the move did not stop between the two locks"); //$NON-NLS-1$

            FormEditingSession open = session;
            AtomicReference<IStatus> status = new AtomicReference<>();
            AtomicReference<Throwable> undoFailure = new AtomicReference<>();
            undo = new Thread(() -> {
                try
                {
                    status.set(OperationHistoryFactory.getOperationHistory().undo(open.undoContext(),
                        new NullProgressMonitor(), null));
                }
                catch (Throwable t)
                {
                    undoFailure.set(t);
                }
            }, "history-undo"); //$NON-NLS-1$
            undo.start();
            Thread undoThread = undo;
            spinUntil(() -> OpenFormSessions.waiterQueued(probe.file(), undoThread) || !undoThread.isAlive(),
                "the undo did not queue on the old file"); //$NON-NLS-1$
            assertTrue("the undo finished before it queued on the old file", undo.isAlive()); //$NON-NLS-1$
            gate.countDown();
            mover.join(WAIT_MS);
            undo.join(WAIT_MS);
            assertFalse(mover.isAlive());
            assertFalse(undo.isAlive());
            if (!failures.isEmpty())
            {
                fail(String.valueOf(failures.get(0)));
            }
            if (undoFailure.get() != null)
            {
                fail(String.valueOf(undoFailure.get()));
            }
            assertNotNull(status.get());
            assertTrue(status.get().getMessage(), status.get().isOK());
            IFile destination = probe.project().getFile(new org.eclipse.core.runtime.Path("src/Catalogs/ZRenamed/Forms/ItemForm/Form.oform")); //$NON-NLS-1$
            assertFalse(probe.file().exists());
            assertTrue(destination.exists());
            assertArrayEquals(beforeMove, readBytes(destination));
            assertEquals(beforeEdit, session.currentFormText());
            assertEquals(destination.getFullPath().toPortableString(), session.file().getFullPath().toPortableString());
            boolean sawOldThenNew = false;
            synchronized (pairs)
            {
                for (String[] pair : pairs)
                {
                    assertTrue(pair[0] + " then " + pair[1], pair[0].compareTo(pair[1]) <= 0); //$NON-NLS-1$
                    if (pair[0].equals(probe.file().getFullPath().toPortableString())
                        && pair[1].equals(destination.getFullPath().toPortableString()))
                    {
                        sawOldThenNew = true;
                    }
                }
            }
            assertTrue("the first locks were not the old file and then the new file", sawOldThenNew); //$NON-NLS-1$
        }
        finally
        {
            gate.countDown();
            stop(mover);
            stop(undo);
            OpenFormSessions.release(session);
            if ((mover == null || !mover.isAlive()) && (undo == null || !undo.isAlive()))
            {
                probe.dispose();
            }
        }
    }

    /**
     * A save that has chosen the old file, and is waiting before it takes that file's workspace
     * rule, writes the file the session is bound to after the move.
     */
    @Test
    public void aSaveWaitingBeforeItsRuleFollowsTheNewBinding() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveSaveProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession session = null;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread saver = null;
        try
        {
            session = OpenFormSessions.open(probe.file());
            dirtyFormAndModule(session);
            Path index = writeBaseline(probe.project(), OLD_OWNER, NEW_OWNER);
            AtomicInteger hits = new AtomicInteger();
            AtomicReference<String> firstTarget = new AtomicReference<>();
            FormEditingSession.setSaveBarrier(target -> {
                if (hits.incrementAndGet() != 1)
                {
                    return;
                }
                firstTarget.set(target.getFullPath().toPortableString());
                entered.countDown();
                awaitGate(release);
            });
            FormEditingSession open = session;
            AtomicReference<FormEditingSession.Save> saved = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            saver = new Thread(() -> {
                try
                {
                    saved.set(open.save());
                }
                catch (Throwable t)
                {
                    failure.set(t);
                }
            }, "form-save"); //$NON-NLS-1$
            saver.start();
            assertTrue("the save did not choose a file", entered.await(WAIT_MS, TimeUnit.MILLISECONDS)); //$NON-NLS-1$
            Thread saving = saver;
            spinUntil(() -> isParked(saving), "the save did not wait before its rule"); //$NON-NLS-1$
            assertEquals(probe.file().getFullPath().toPortableString(), firstTarget.get());
            IFile destination = moveOwner(probe.project(), "Products", "Renamed"); //$NON-NLS-1$ //$NON-NLS-2$
            release.countDown();
            saver.join(WAIT_MS);
            assertFalse(saver.isAlive());
            if (failure.get() != null)
            {
                fail(String.valueOf(failure.get()));
            }
            FormEditingSession.Save save = saved.get();
            assertNotNull(save);
            assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
            assertTrue("the save did not retry onto the new file", hits.get() >= 2); //$NON-NLS-1$
            assertFalse(probe.file().exists());
            assertTrue(destination.exists());
            OrdinaryFormFile written = readContainer(destination);
            assertTrue(written.formText().contains(TITLE));
            assertTrue(written.moduleText().contains(PROCEDURE));
            assertOwnerMarks(index, OLD_OWNER, NEW_OWNER);
            assertNotNull(save.delivery);
            assertTrue(save.delivery, save.delivery.contains(NEW_OWNER));
            assertEquals(destination.getFullPath().toPortableString(), session.file().getFullPath().toPortableString());
        }
        finally
        {
            release.countDown();
            stop(saver);
            OpenFormSessions.release(session);
            if (saver == null || !saver.isAlive())
            {
                probe.dispose();
            }
        }
    }

    /**
     * Deleting the container, with no {@code MOVED_TO}, refuses edit, preview, reconcile, save,
     * undo and gesture, and the listener is gone once the editor releases the session.
     */
    @Test
    public void aRemovalWithoutAMoveRefusesAndTheListenerGoes() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveRemoveProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession session = null;
        try
        {
            session = OpenFormSessions.open(probe.file());
            dirtyFormAndModule(session);
            assertTrue(session.canUndo());
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            OpenFormSessions.setNotice(recording(events));
            probe.file().delete(IResource.FORCE, new NullProgressMonitor());
            assertRefusals(session, events);
            OpenFormSessions.release(session);
            session = null;
            assertSessionGone(null);
        }
        finally
        {
            OpenFormSessions.release(session);
            probe.dispose();
        }
    }

    /**
     * Deleting the owner folder, with no {@code MOVED_TO}, is the same refusal as deleting the
     * container itself.
     */
    @Test
    public void aRemovedOwnerFolderRefusesWithoutAMove() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOformMoveRemoveFolderProbe", layout, "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        FormEditingSession session = null;
        try
        {
            session = OpenFormSessions.open(probe.file());
            dirtyFormAndModule(session);
            assertTrue(session.canUndo());
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            OpenFormSessions.setNotice(recording(events));
            probe.project().getFolder(new org.eclipse.core.runtime.Path("src/Catalogs/Products")).delete( //$NON-NLS-1$
                IResource.FORCE, new NullProgressMonitor());
            assertRefusals(session, events);
            OpenFormSessions.release(session);
            session = null;
            assertSessionGone(null);
        }
        finally
        {
            OpenFormSessions.release(session);
            probe.dispose();
        }
    }

    @Test
    public void aMissingBindingReturnsWithoutWaitingForTheResourceCallback() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtMissingBindingProbe", layout, "\r\n");
        FormEditingSession session = OpenFormSessions.open(probe.file());
        try
        {
            dirtyFormAndModule(session);
            // Simulate the interval after the tree changed but before POST_CHANGE rebinds it.
            SessionResourceListener.uninstall();
            probe.file().delete(true, new NullProgressMonitor());
            AtomicReference<FormEditingSession.Edit> answer = new AtomicReference<>();
            Thread editing = new Thread(() -> answer.set(session.edit("set_property",
                Map.of("property", "title", "value", TITLE_AGAIN))));
            editing.setDaemon(true);
            editing.start();
            editing.join(1500);
            boolean finished = !editing.isAlive();
            if (!finished)
            {
                editing.interrupt();
                session.markRemoved();
                editing.join(2000);
            }
            assertTrue("an absent binding must refuse, not wait for a callback on the UI thread", finished);
            assertNotNull(answer.get());
            assertFalse(answer.get().done);
        }
        finally
        {
            OpenFormSessions.release(session);
            probe.dispose();
        }
    }

    @Test
    public void anOccupiedDestinationMakesTheSourceTerminalInsteadOfStale() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtOccupiedMoveProbe", layout, "\r\n");
        FormEditingSession source = OpenFormSessions.open(probe.file());
        IFile destination = plant(probe.project(), "Renamed", probe.original());
        FormEditingSession occupant = OpenFormSessions.open(destination);
        try
        {
            dirtyFormAndModule(source);
            String retainedForm = source.currentFormText();
            String retainedModule = source.currentModuleText();
            assertEquals(OpenFormSessions.Move.REFUSED, OpenFormSessions.rebind(source, probe.file(), destination));
            assertEquals(retainedForm, source.currentFormText());
            assertEquals(retainedModule, source.currentModuleText());
            assertTrue(source.isDirty());
            assertTrue(source.canUndo());
            assertTrue("a refused move must not leave a usable stale source", source.isRemoved());
            assertFalse(source.preview("set_property", Map.of("property", "title", "value", TITLE)).done);
            assertSame(occupant, OpenFormSessions.open(destination));
            OpenFormSessions.release(occupant);
        }
        finally
        {
            OpenFormSessions.release(source);
            OpenFormSessions.release(occupant);
            probe.dispose();
        }
    }

    @Test
    public void aPinnedDestinationLockIsNeverEvicted() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtPinnedMoveProbe", layout, "\r\n");
        FormEditingSession source = OpenFormSessions.open(probe.file());
        IFile destination = plant(probe.project(), "Renamed", probe.original());
        OpenFormSessions.SessionLock pinned = OpenFormSessions.lockOf(destination);
        pinned.lock();
        try
        {
            assertEquals("refuse rather than split the destination lock", OpenFormSessions.Move.REFUSED,
                OpenFormSessions.rebind(source, probe.file(), destination));
            OpenFormSessions.SessionLock after = OpenFormSessions.lockOf(destination);
            try
            {
                assertTrue("the original lock must remain registered", after.heldByCurrentThread());
            }
            finally
            {
                OpenFormSessions.releasePin(after);
            }
        }
        finally
        {
            pinned.unlock();
            OpenFormSessions.release(source);
            probe.dispose();
        }
    }

    @Test
    public void deletingAFileInsideAMovedAncestorMarksItRemoved() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtDeletedChildMoveProbe", layout, "\r\n");
        FormEditingSession session = OpenFormSessions.open(probe.file());
        try
        {
            probe.project().getWorkspace().run(monitor -> {
                probe.file().delete(true, monitor);
                moveOwner(probe.project(), "Products", "Renamed");
            }, probe.project(), 0, new NullProgressMonitor());
            assertTrue("a deleted child must not bind to a nonexistent moved path", session.isRemoved());
            assertEquals(probe.file(), session.file());
        }
        finally
        {
            OpenFormSessions.release(session);
            probe.dispose();
        }
    }

    @Test
    public void theLastReleaseDuringDispatchRemovesTheListenerAtDispatchEnd() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtDispatchEndProbe", layout, "\r\n");
        FormEditingSession session = OpenFormSessions.open(probe.file());
        OpenFormSessions.beginDispatch();
        try
        {
            OpenFormSessions.release(session);
            assertTrue(OpenFormSessions.listenerInstalled());
        }
        finally
        {
            OpenFormSessions.endDispatch();
            probe.dispose();
        }
        assertFalse(OpenFormSessions.listenerInstalled());
    }

    @Test
    public void aThrowingNoticeDoesNotDropTheRestOfTheBatch()
    {
        AtomicInteger calls = new AtomicInteger();
        OpenFormSessions.setNotice(new OpenFormSessions.Notice()
        {
            public void moved(FormEditingSession session, IFile from, IFile to)
            {
                calls.incrementAndGet();
                throw new IllegalStateException("broken editor callback");
            }
            public void removed(FormEditingSession session, IFile file)
            {
                calls.incrementAndGet();
            }
        });
        OpenFormSessions.acceptNotices(List.of(OpenFormSessions.PendingNotice.moved(null, null, null),
            OpenFormSessions.PendingNotice.removed(null, null)));
        assertEquals(2, calls.get());
    }

    /**
     * Applies the title edit and a handler stub, so both entries are dirty.
     *
     * @param session the open session
     */
    private static void dirtyFormAndModule(FormEditingSession session)
    {
        FormEditingSession.Edit title = session.edit("set_property", Map.of( //$NON-NLS-1$
            "property", "title", "value", TITLE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(title.message, title.done);
        FormEditingSession.Edit handler = session.edit("bind_handler", Map.of( //$NON-NLS-1$
            "elementName", table, //$NON-NLS-1$
            "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
            "procedure", PROCEDURE, //$NON-NLS-1$
            "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(handler.message, handler.done);
        assertTrue(session.formChanged());
        assertTrue(session.moduleChanged());
    }

    /**
     * The refusals a removal answers with, and the notice that names the removal.
     *
     * @param session the session whose container was removed
     * @param events the notices recorded for that removal
     */
    private static void assertRefusals(FormEditingSession session, List<String> events)
    {
        assertTrue(events.contains("removed")); //$NON-NLS-1$
        assertFalse(events.contains("moved")); //$NON-NLS-1$
        assertTrue(session.isRemoved());
        FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
            "property", "title", "value", TITLE_AGAIN)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(edit.done);
        assertEquals(REMOVED, edit.message);
        FormEditingSession.Preview preview = session.preview("set_property", Map.of( //$NON-NLS-1$
            "property", "title", "value", TITLE_AGAIN)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(preview.done);
        assertEquals(REMOVED, preview.message);
        FormEditingSession.Reconciliation reconciliation = session.reconcile();
        assertEquals(REMOVED, reconciliation.outcome);
        assertEquals(REMOVED, reconciliation.message);
        assertFalse(session.readOnly());
        FormEditingSession.Save save = session.save();
        assertFalse(save.written);
        assertNull(save.delivery);
        assertEquals(REMOVED, save.reconciliation);
        FormEditingSession.Reversal undo = session.undo();
        assertFalse(undo.done);
        assertEquals(REMOVED, undo.message);
        FormEditingSession.Edit gesture = session.gesture(List.of(new FormEditingSession.Step("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", TITLE_AGAIN)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(gesture.done);
        assertEquals(REMOVED, gesture.message);
    }

    /**
     * @param session a session that should no longer be registered; {@code null} checks only the
     *        listener once nothing is open
     */
    private static void assertSessionGone(FormEditingSession session)
    {
        for (OpenFormSessions.Binding binding : OpenFormSessions.liveBindings())
        {
            assertFalse(binding.session() == session);
        }
        if (OpenFormSessions.liveBindings().isEmpty())
        {
            assertFalse(OpenFormSessions.listenerInstalled());
        }
    }

    /**
     * Moves {@code src/Catalogs/from} onto {@code src/Catalogs/to} and returns the form file at
     * the new path.
     *
     * @param project the project
     * @param from the catalog folder name
     * @param to the catalog folder name to move onto
     * @return the {@code Form.oform} at the destination
     * @throws CoreException when the workspace refuses the move
     */
    private static IFile moveOwner(IProject project, String from, String to) throws CoreException
    {
        project.getFolder(new org.eclipse.core.runtime.Path("src/Catalogs/" + from)).move( //$NON-NLS-1$
            project.getFullPath().append(new org.eclipse.core.runtime.Path("src/Catalogs/" + to)), true, new NullProgressMonitor()); //$NON-NLS-1$
        return project.getFile(new org.eclipse.core.runtime.Path("src/Catalogs/" + to + "/Forms/ItemForm/Form.oform")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Moves one catalog folder and records a failure instead of escaping the thread.
     *
     * @param project the project
     * @param from the catalog folder name
     * @param to the catalog folder name to move onto
     * @param failures where a throwable is recorded
     */
    private static void moveCatalog(IProject project, String from, String to, List<Throwable> failures)
    {
        try
        {
            moveOwner(project, from, to);
        }
        catch (Throwable t)
        {
            failures.add(t);
        }
    }

    /**
     * Writes a second copy of the probe's container under {@code src/Catalogs/catalog}.
     *
     * @param project the project
     * @param catalog the catalog folder name
     * @param bytes the container bytes
     * @return the new {@code Form.oform}
     * @throws CoreException when a folder or the file cannot be created
     */
    private static IFile plant(IProject project, String catalog, byte[] bytes) throws CoreException
    {
        IFolder folder = project.getFolder(new org.eclipse.core.runtime.Path("src/Catalogs/" + catalog + "/Forms/ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        createFolder(folder);
        IFile file = folder.getFile("Form.oform"); //$NON-NLS-1$
        file.create(new ByteArrayInputStream(bytes), true, new NullProgressMonitor());
        return file;
    }

    /**
     * Creates a folder and the parents it needs.
     *
     * @param folder the folder
     * @throws CoreException when a folder cannot be created
     */
    private static void createFolder(IFolder folder) throws CoreException
    {
        if (folder.exists())
        {
            return;
        }
        if (folder.getParent() instanceof IFolder parent)
        {
            createFolder(parent);
        }
        folder.create(true, true, new NullProgressMonitor());
    }

    /**
     * The ordinary-form module at a container, addressed the way a tool finds it.
     *
     * @param container the {@code Form.oform}
     * @return the module, or {@code null} when the path is not a form's
     */
    private static OrdinaryFormModule moduleAt(IFile container)
    {
        String relative = container.getProjectRelativePath().removeFirstSegments(1).toPortableString();
        return OrdinaryFormModule.locate(container.getProject(), OrdinaryFormModule.moduleAddressOf(relative));
    }

    /**
     * @param file a file that has a location
     * @return its bytes
     * @throws IOException when the file cannot be read
     */
    private static byte[] readBytes(IFile file) throws IOException
    {
        return Files.readAllBytes(file.getLocation().toFile().toPath());
    }

    /**
     * @param file a container that has a location
     * @return the container read from those bytes
     * @throws IOException when the file cannot be read
     */
    private static OrdinaryFormFile readContainer(IFile file) throws IOException
    {
        byte[] bytes = readBytes(file);
        return OrdinaryFormFile.of(null, V8Container.read(bytes), bytes);
    }

    /**
     * Writes a two-key infobase index into the project's workspace store.
     *
     * @param project the project
     * @param oldKey the owner's key before the move
     * @param newKey the owner's key after the move
     * @return the index file
     * @throws IOException when the index cannot be written
     */
    private static Path writeBaseline(IProject project, String oldKey, String newKey) throws IOException
    {
        Path store = SyncBaseline.workspaceStore(project).resolve("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"); //$NON-NLS-1$
        Files.createDirectories(store);
        Path index = store.resolve(SyncBaseline.INDEX_FILE);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(index.toFile())))
        {
            out.writeUTF("1.0"); //$NON-NLS-1$
            out.writeLong(1_700_000_000_000L);
            out.writeInt(2);
            writeKey(out, oldKey, OLD_SIGNATURE);
            writeKey(out, newKey, NEW_SIGNATURE);
            out.writeUTF("generation-7"); //$NON-NLS-1$
            out.writeUTF("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"); //$NON-NLS-1$
        }
        return index;
    }

    /**
     * Writes one versioned index entry.
     *
     * @param out the stream
     * @param key the resource key
     * @param signature the signature bytes
     * @throws IOException when the stream refuses a write
     */
    private static void writeKey(DataOutputStream out, String key, byte[] signature) throws IOException
    {
        out.writeUTF(key);
        out.writeInt(signature.length);
        out.write(signature);
        out.writeBoolean(true);
        out.writeUTF("2a8d5f3e-0f0c-4a4c-9a5d-2f6a4c8e9b10"); //$NON-NLS-1$
    }

    /**
     * The new owner's signature is blank and the old owner's signature is the bytes written for it.
     *
     * @param index the index file
     * @param oldKey the owner's key before the move
     * @param newKey the owner's key after the move
     * @throws IOException when the index cannot be read
     */
    private static void assertOwnerMarks(Path index, String oldKey, String newKey) throws IOException
    {
        SyncBaseline.Index read = SyncBaseline.read(index);
        int oldAt = read.indexOf(oldKey);
        int newAt = read.indexOf(newKey);
        assertTrue(oldAt >= 0);
        assertTrue(newAt >= 0);
        assertArrayEquals(OLD_SIGNATURE, read.signatures.get(oldAt));
        assertArrayEquals(new byte[NEW_SIGNATURE.length], read.signatures.get(newAt));
    }

    /**
     * @param events where each notice appends {@code moved} or {@code removed}
     * @return a notice that only records
     */
    private static OpenFormSessions.Notice recording(List<String> events)
    {
        return new OpenFormSessions.Notice()
        {
            @Override
            public void moved(FormEditingSession session, IFile from, IFile to)
            {
                events.add("moved"); //$NON-NLS-1$
            }

            @Override
            public void removed(FormEditingSession session, IFile file)
            {
                events.add("removed"); //$NON-NLS-1$
            }
        };
    }

    /**
     * @param session a session
     * @return that same session when the registry still holds it; otherwise {@code null}
     */
    private static FormEditingSession boundSession(FormEditingSession session)
    {
        for (OpenFormSessions.Binding binding : OpenFormSessions.liveBindings())
        {
            if (binding.session() == session)
            {
                return binding.session();
            }
        }
        return null;
    }

    /**
     * @param session a session
     * @return how many registry entries point at that object
     */
    private static int countBindings(FormEditingSession session)
    {
        int count = 0;
        for (OpenFormSessions.Binding binding : OpenFormSessions.liveBindings())
        {
            if (binding.session() == session)
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Waits on a latch. A timeout or an interrupt fails the thread instead of waiting on.
     *
     * @param gate the latch
     */
    private static void awaitGate(CountDownLatch gate)
    {
        try
        {
            if (!gate.await(WAIT_MS, TimeUnit.MILLISECONDS))
            {
                throw new IllegalStateException("a barrier timed out"); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a barrier was interrupted"); //$NON-NLS-1$
        }
    }

    /**
     * Spins until {@code condition} is true or {@link #WAIT_MS} has passed.
     *
     * @param condition the condition
     * @param why the failure when the wait expires
     */
    private static void spinUntil(BooleanSupplier condition, String why)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (!condition.getAsBoolean())
        {
            if (System.nanoTime() >= deadline)
            {
                fail(why);
            }
            Thread.onSpinWait();
        }
    }

    /**
     * @param thread a thread
     * @return whether it is parked in a timed or untimed wait
     */
    private static boolean isParked(Thread thread)
    {
        Thread.State state = thread.getState();
        return state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING;
    }

    /**
     * Joins a thread for {@link #WAIT_MS}, then interrupts it and joins briefly. A thread that is
     * still alive stays alive; the caller decides whether the project can be deleted.
     *
     * @param thread the thread, or {@code null}
     * @throws InterruptedException when this wait is interrupted
     */
    private static void stop(Thread thread) throws InterruptedException
    {
        if (thread == null)
        {
            return;
        }
        thread.join(WAIT_MS);
        if (thread.isAlive())
        {
            thread.interrupt();
            thread.join(2_000L);
        }
    }
}
