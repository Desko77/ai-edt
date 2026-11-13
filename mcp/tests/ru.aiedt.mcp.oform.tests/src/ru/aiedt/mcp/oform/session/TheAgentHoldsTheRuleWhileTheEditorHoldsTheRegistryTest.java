/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.OrdinaryFormModule;
import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * The two locks a write meets, in the order the guard takes them. The agent's write holds the
 * workspace's rule and then waits for the registry lock; while the editor's thread holds that
 * lock, its edit runs - the editor asks for no workspace rule, so the rule the agent already
 * holds is no obstacle to it - and once the lock goes, the write enters, meets the session's
 * unsaved layout change and refuses. Both sides finish; nothing of the file moves.
 */
public class TheAgentHoldsTheRuleWhileTheEditorHoldsTheRegistryTest
{
    private static final String PROJECT = "AiEdtOformRuleAndRegistryProbe"; //$NON-NLS-1$

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
     * The agent's write takes the rule and waits for the registry lock the editor holds; the
     * editor's edit runs under that lock without asking for the rule; the write that enters once
     * the lock goes refuses on the session's unsaved layout change and the file keeps its bytes.
     */
    @Test
    public void theAgentWaitsForTheRegistryTheEditorDoesNotAskForTheRule() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                OpenFormSessions.SessionLock registry = OpenFormSessions.lockOf(probe.file());
                registry.lock();
                AtomicReference<IOException> refusalOf = new AtomicReference<>();
                Thread agent = new Thread(() -> {
                    try
                    {
                        OrdinaryFormModule module = OrdinaryFormModule.locate(probe.project(), MODULE_PATH);
                        module.writeEntries(null, anotherLayout(), null);
                    }
                    catch (IOException e)
                    {
                        refusalOf.set(e);
                    }
                }, "the-agent-writer"); //$NON-NLS-1$
                agent.start();
                assertTrue("the agent never came to wait for the registry", //$NON-NLS-1$
                    theAgentWaits(registry, agent));

                // Under the lock the editor holds, its edit runs: the edit asks for the registry
                // lock only, which this thread already holds, and never the workspace's rule.
                FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
                    Map.of("property", "title", "value", "Правка под замком")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(edit.message, edit.done);
                assertTrue("the session holds its layout changed", session.formChanged()); //$NON-NLS-1$
                assertTrue("the agent still waits behind the editor", theAgentWaits(registry, agent)); //$NON-NLS-1$

                registry.unlock();
                agent.join(10_000);
                assertFalse("the agent finished once the lock went", agent.isAlive()); //$NON-NLS-1$
                assertTrue("the agent's refusal names the unsaved changes", //$NON-NLS-1$
                    refusalOf.get() != null && refusalOf.get().getMessage().contains("unsaved changes")); //$NON-NLS-1$
                assertArrayEquals("the container keeps its bytes", probe.original(), probe.bytes()); //$NON-NLS-1$
                assertTrue("the session keeps its edit for its own save", session.formChanged()); //$NON-NLS-1$
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
     * Waits, boundedly, for the agent to queue on the registry lock.
     *
     * @param registry the registry lock
     * @param agent the agent's thread
     * @return whether the agent queued within the bound
     * @throws InterruptedException when the wait is broken
     */
    private static boolean theAgentWaits(OpenFormSessions.SessionLock registry, Thread agent) throws InterruptedException
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() - end < 0)
        {
            if (registry.hasQueuedThread(agent))
            {
                return true;
            }
            Thread.sleep(10);
        }
        return registry.hasQueuedThread(agent);
    }

    /** @return the layout with another title, the text an agent's layout write would carry */
    private static String anotherLayout()
    {
        try
        {
            String formText = ru.aiedt.mcp.oform.OrdinaryFormFile
                .read(probe.file().getLocation().toFile().toPath()).formText();
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
}
