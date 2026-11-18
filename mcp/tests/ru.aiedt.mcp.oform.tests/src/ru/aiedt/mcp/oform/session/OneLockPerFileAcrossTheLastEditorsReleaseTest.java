/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.OrdinaryFormModule;
import ru.aiedt.mcp.oform.Pacing;
import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * One lock per file, whatever closes and reopens around a write that holds it. The write looks
 * the file's registry entry up and takes its lock, and between those two steps the last editor
 * may close its session: the entry is pinned for the write from its lookup, so the closing
 * cannot drop it, and a session opened in that window takes the same lock the write is about to
 * take. The proof asks the locks themselves: a write stopped inside the guard holds the second
 * session's edit back, and when both go on, the write's form is in the file and the edit runs
 * over it - neither side works under a lock of its own.
 */
public class OneLockPerFileAcrossTheLastEditorsReleaseTest
{
    private static final String PROJECT = "AiEdtOformOneLockProbe"; //$NON-NLS-1$

    private static final String MODULE_PATH = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static SessionProbe probe;

    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, "Процедура ПриОткрытии()\r\nКонецПроцедуры\r\n"); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /**
     * The closing of the last editor inside the window between the write's lookup and its lock
     * keeps the entry: the write and a session opened in the window share one lock, so the
     * session's edit waits for the write and runs over what the write wrote.
     */
    @Test
    public void aReleaseInsideTheLookupWindowKeepsOneLockPerFile() throws Exception
    {
        try
        {
            FormEditingSession first = OpenFormSessions.open(probe.file());
            try
            {
                CountDownLatch windowReached = new CountDownLatch(1);
                CountDownLatch windowOver = new CountDownLatch(1);
                CountDownLatch sectionReached = new CountDownLatch(1);
                CountDownLatch sectionOver = new CountDownLatch(1);
                AtomicBoolean inWindow = new AtomicBoolean(true);
                AtomicBoolean inSection = new AtomicBoolean(true);
                AtomicReference<Exception> failureOf = new AtomicReference<>();
                AtomicReference<Boolean> wroteOf = new AtomicReference<>();
                Pacing.install(point -> {
                    if (Pacing.BEFORE_REGISTRY_LOCK.equals(point) && inWindow.compareAndSet(true, false))
                    {
                        windowReached.countDown();
                        await(windowOver);
                    }
                    else if (Pacing.BEFORE_CONTAINER_WRITE.equals(point) && inSection.compareAndSet(true, false))
                    {
                        sectionReached.countDown();
                        await(sectionOver);
                    }
                });
                Thread writer = new Thread(() -> {
                    try
                    {
                        OrdinaryFormModule module = OrdinaryFormModule.locate(probe.project(), MODULE_PATH);
                        wroteOf.set(module.writeEntries(null, anotherLayout(), null));
                    }
                    catch (Exception e)
                    {
                        failureOf.set(e);
                    }
                }, "the-agent-writer"); //$NON-NLS-1$
                try
                {
                    writer.start();
                    assertTrue("the write never reached the window before its lock", //$NON-NLS-1$
                        windowReached.await(10, TimeUnit.SECONDS));

                    // The last editor closes inside the window, and a new session opens in it.
                    OpenFormSessions.release(first);
                    assertFalse("the closed session is gone", OpenFormSessions.isHeld(probe.file())); //$NON-NLS-1$
                    FormEditingSession second = OpenFormSessions.open(probe.file());
                    windowOver.countDown();

                    assertTrue("the write never entered its guarded work", sectionReached.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
                    CountDownLatch editAnswered = new CountDownLatch(1);
                    AtomicReference<FormEditingSession.Edit> editOf = new AtomicReference<>();
                    Thread editor = new Thread(() -> {
                        editOf.set(second.edit("set_property", //$NON-NLS-1$
                            Map.of("property", "title", "value", "Правка второго сеанса"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        editAnswered.countDown();
                    }, "the-second-editor"); //$NON-NLS-1$
                    editor.start();
                    assertFalse("the edit finished while the write held the lock - two locks of one file", //$NON-NLS-1$
                        editAnswered.await(700, TimeUnit.MILLISECONDS));

                    sectionOver.countDown();
                    assertTrue("the edit never finished once the write went on", //$NON-NLS-1$
                        editAnswered.await(10, TimeUnit.SECONDS));
                    writer.join(10_000);
                    editor.join(10_000);
                    if (failureOf.get() != null)
                    {
                        throw failureOf.get();
                    }
                    assertTrue("the write answered", wroteOf.get() != null && wroteOf.get().booleanValue()); //$NON-NLS-1$

                    // The write's form is in the file, and the session's edit met it the honest
                    // way: refused with a reconciliation, applied again over what was written.
                    assertFalse("the write left the file as it was", //$NON-NLS-1$
                        java.util.Arrays.equals(probe.bytes(), probe.original()));
                    FormEditingSession.Edit refused = editOf.get();
                    assertFalse(refused.message, refused.done);
                    assertTrue(refused.message, refused.message.contains("changed outside")); //$NON-NLS-1$
                    FormEditingSession.Edit applied = second.edit("set_property", //$NON-NLS-1$
                        Map.of("property", "title", "value", "Правка второго сеанса")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    assertTrue(applied.message, applied.done);
                    assertTrue("the session holds its edit over the write's form", second.formChanged()); //$NON-NLS-1$
                    OrdinaryFormFile written = theContainersFile();
                    assertTrue(written.formText(), written.formText().contains("Заголовок агента")); //$NON-NLS-1$
                    OpenFormSessions.release(second);
                }
                finally
                {
                    Pacing.clear();
                }
            }
            finally
            {
                OpenFormSessions.release(first);
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

    /** @return the layout with another title, the text an agent's layout write would carry */
    private static String anotherLayout()
    {
        try
        {
            String formText = OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath()).formText();
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(formText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome set = editor.setTitle("Заголовок агента"); //$NON-NLS-1$
            assertTrue(set.message, set.done);
            return editor.text();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static OrdinaryFormFile theContainersFile() throws java.io.IOException
    {
        return OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath());
    }
}
