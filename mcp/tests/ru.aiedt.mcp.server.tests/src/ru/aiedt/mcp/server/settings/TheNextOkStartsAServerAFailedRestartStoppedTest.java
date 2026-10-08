/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHttpEndpoint;

/**
 * What the settings page does on the OK that follows a restart it could not complete.
 * <p>
 * A restart stops before it starts, so a refusal leaves the endpoint stopped: the tools tab has
 * already written the store by then, and the next OK finds nothing changed to restart for. Without
 * the page telling a server the failure stopped apart from one the user stopped, that OK reads
 * "nothing is running to restart", settles the debt and closes over a server nothing is listening
 * on.
 * </p>
 * <p>
 * The page is built on a shell that is never opened, over the plugin's own store, and talks to an
 * endpoint that records what it is asked for instead of opening a socket. The endpoint the activator
 * holds is put back as it was afterwards.
 * </p>
 */
public class TheNextOkStartsAServerAFailedRestartStoppedTest
{
    /** The keys this test writes for the tools tab. The general tab's own are the fixture's. */
    private static final String[] TOOL_KEYS =
        {PrefKeys.PREF_TOOL_PRESET, PrefKeys.PREF_DISABLED_TOOLS, PrefKeys.PREF_UNLISTED_TOOLS};

    private Shell shell;

    private IPreferenceStore store;

    /** Stands in for the bookkeeping of the general tab's thirty-odd keys, which the page saves. */
    private AGeneralTabOnAShell generalKeys;

    private final Map<String, String> beforeTools = new LinkedHashMap<>();

    private McpHttpEndpoint endpoint;

    private McpHttpEndpoint installedBefore;

    /**
     * Builds the page's shell, sets the store the page is to read, and takes the endpoint the
     * activator holds out of the picture.
     *
     * @throws Exception when the activator's endpoint cannot be replaced
     */
    @Before
    public void takeOverTheEndpoint() throws Exception
    {
        Display display = Display.getDefault();
        assumeTrue("this test builds widgets, so it needs the thread that owns a display", //$NON-NLS-1$
            display.getThread() == Thread.currentThread());
        shell = new Shell(display);
        // No update site: an address the policy refuses would stop the page's save before it reached
        // the tools tab, and this test is not about that refusal.
        generalKeys = AGeneralTabOnAShell.open(PrefKeys.PREF_UPKEEP_SITE_URL, ""); //$NON-NLS-1$
        store = generalKeys.store();
        for (String key : TOOL_KEYS)
        {
            beforeTools.put(key, store.getString(key));
        }
        installedBefore = Activator.getDefault().getMcpServer();
        endpoint = new RecordingEndpoint();
        endpointField().set(Activator.getDefault(), endpoint);
    }

    /**
     * Puts the endpoint and every key this test wrote back, and disposes what it built.
     *
     * @throws Exception when the activator's endpoint cannot be put back
     */
    @After
    public void giveEverythingBack() throws Exception
    {
        if (endpoint != null)
        {
            endpointField().set(Activator.getDefault(), installedBefore);
        }
        if (shell != null)
        {
            shell.dispose();
        }
        if (store != null)
        {
            for (Map.Entry<String, String> entry : beforeTools.entrySet())
            {
                store.setValue(entry.getKey(), entry.getValue());
            }
        }
        if (generalKeys != null)
        {
            generalKeys.close();
        }
    }

    /** The OK after a restart that failed brings the server back, and is the last one that has to. */
    @Test
    public void theOkAfterAFailedRestartStartsTheServer()
    {
        RecordingEndpoint server = (RecordingEndpoint)endpoint;
        server.running = true;
        server.refusesRestart = true;
        McpSettingsPage page = pageOwingARestart();

        assertFalse("a restart that failed must not close the page", page.performOk()); //$NON-NLS-1$
        assertEquals("the page asked for the restart it owed", 1, server.restarts); //$NON-NLS-1$
        assertFalse("and the failure left the server stopped", server.running); //$NON-NLS-1$

        server.refusesRestart = false;
        assertTrue("the next OK has to bring the server back", page.performOk()); //$NON-NLS-1$
        assertEquals("by asking for a start", 1, server.starts); //$NON-NLS-1$
        assertTrue("which leaves it listening again", server.running); //$NON-NLS-1$

        assertTrue("and once it is up the page closes", page.performOk()); //$NON-NLS-1$
        assertEquals("with nothing further asked of the server", 1, server.starts); //$NON-NLS-1$
    }

    /** A start that failed in its turn does not settle the debt: the OK after it tries again. */
    @Test
    public void aStartThatFailedLeavesTheDebtInPlace()
    {
        RecordingEndpoint server = (RecordingEndpoint)endpoint;
        server.running = true;
        server.refusesRestart = true;
        McpSettingsPage page = pageOwingARestart();
        assertFalse("the restart is refused first", page.performOk()); //$NON-NLS-1$

        server.refusesStart = true;
        assertFalse("a start that failed does not close the page either", page.performOk()); //$NON-NLS-1$
        assertEquals("and it was tried", 1, server.starts); //$NON-NLS-1$

        server.refusesStart = false;
        assertTrue("the OK after that one brings the server up", page.performOk()); //$NON-NLS-1$
        assertEquals("asking a second time", 2, server.starts); //$NON-NLS-1$
        assertTrue("and it is listening", server.running); //$NON-NLS-1$
    }

    /** A server the user stopped is left alone: nothing is owed to one that is not running. */
    @Test
    public void aServerStoppedOnPurposeIsNotStarted()
    {
        RecordingEndpoint server = (RecordingEndpoint)endpoint;
        server.running = false;
        McpSettingsPage page = pageOwingARestart();

        assertTrue("a stopped server owes nothing to a save", page.performOk()); //$NON-NLS-1$
        assertEquals("so no start is asked for", 0, server.starts); //$NON-NLS-1$
        assertFalse("and it stays stopped", server.running); //$NON-NLS-1$
    }

    /**
     * Builds the page over a tool selection the store is then moved past, so the page's first OK has
     * something to save and the tools tab is owed a restart.
     *
     * @return the page
     */
    private McpSettingsPage pageOwingARestart()
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        McpSettingsPage page = new McpSettingsPage();
        page.createContents(shell);
        // The tab took its selection as it was built; the store moves on without it, which is the
        // state a user leaves behind by hiding a tool - and the one the page's own save clears.
        ToolSettingsStore.getInstance().setUnlistedTools(ToolProfile.CANONICAL.getUnlistedTools());
        assertTrue("the page has to have a tool change to save", toolsTabOf(page).hasChanges()); //$NON-NLS-1$
        return page;
    }

    /**
     * The tools tab of a page, which is where the change the save reacts to is read from.
     *
     * @param page the page
     * @return the tab
     */
    private static ToolsPrefTab toolsTabOf(McpSettingsPage page)
    {
        try
        {
            Field field = McpSettingsPage.class.getDeclaredField("toolsTab"); //$NON-NLS-1$
            field.setAccessible(true);
            return (ToolsPrefTab)field.get(page);
        }
        catch (ReflectiveOperationException unreachable)
        {
            throw new IllegalStateException("the page no longer holds a tools tab", unreachable); //$NON-NLS-1$
        }
    }

    /**
     * The activator's endpoint field, reachable so this test can put a recording endpoint where the
     * page looks for the running server.
     *
     * @return the field, set accessible
     * @throws Exception when the field is gone
     */
    private static Field endpointField() throws Exception
    {
        Field field = Activator.class.getDeclaredField("mcpServer"); //$NON-NLS-1$
        field.setAccessible(true);
        return field;
    }

    /**
     * An endpoint that records what the page asks of it and can refuse either call, so the page's
     * handling of a refusal is exercised without a port being taken.
     */
    private static final class RecordingEndpoint
        extends McpHttpEndpoint
    {
        private boolean running;

        private boolean refusesRestart;

        private boolean refusesStart;

        private int starts;

        private int restarts;

        @Override
        public boolean isRunning()
        {
            return running;
        }

        @Override
        public void start(int serverPort) throws IOException
        {
            starts++;
            if (refusesStart)
            {
                throw new IOException("no port in the span could be taken"); //$NON-NLS-1$
            }
            running = true;
        }

        @Override
        public void restart(int serverPort) throws IOException
        {
            restarts++;
            if (refusesRestart)
            {
                // A restart stops first, so the refusal has to leave the endpoint down - the state
                // the page has to tell apart from a server the user stopped.
                running = false;
                throw new IOException("neither the port asked for nor the one in use could be taken"); //$NON-NLS-1$
            }
            running = true;
        }
    }
}
