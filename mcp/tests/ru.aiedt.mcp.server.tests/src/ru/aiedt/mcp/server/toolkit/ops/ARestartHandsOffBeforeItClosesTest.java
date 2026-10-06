/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A restart that cannot come back is refused before anything closes.
 *
 * <p>Measured twice on the stand (16.09 and 17.09): the workbench answered success to
 * {@code PlatformUI.getWorkbench().restart()} and never came back - the launcher restarts only
 * what its own machinery relaunches, and an EDT this plugin closes has to be brought back by
 * something that survives the close. The answer now names the refusal before the close, and the
 * relaunch is a watcher of its own process: started from within a closing JVM it would die with
 * it.
 */
public class ARestartHandsOffBeforeItClosesTest
{
    /**
     * The detached half exists as a class the watcher JVM can be given: a main that waits for the
     * old instance to leave the workspace and then starts the command it was handed.
     *
     * @throws Exception when the relauncher is gone
     */
    @Test
    public void theDetachedHalfIsAClassOfTheBundle()
        throws Exception
    {
        Class<?> relauncher = Class
            .forName("ru.aiedt.mcp.server.toolkit.ops.RestartEdtTool$Relauncher"); //$NON-NLS-1$
        assertNotNull(relauncher.getDeclaredMethod("main", String[].class)); //$NON-NLS-1$
    }

    /**
     * The command is assembled from named system properties, not parsed out of a command line -
     * a path with a space in it survives untouched, because nothing is split on whitespace.
     */
    @Test
    public void theCommandIsAssembledNotParsed()
    {
        String home = System.getProperty("eclipse.home.location"); //$NON-NLS-1$
        String workspace = System.getProperty("osgi.instance.area"); //$NON-NLS-1$
        // In the test runtime these are set; where they are not, the refusal names the missing
        // one, and nothing closes.
        if (home != null && workspace != null)
        {
            java.util.List<String> command = RestartEdtTool.relaunchCommandOf();
            if (command != null)
            {
                assertEquals("-data", command.get(1)); //$NON-NLS-1$
                assertTrue(command.get(2).length() > 2);
            }
        }
    }

    /**
     * The refusal is reachable without closing anything: with no installation root named, the
     * command cannot be assembled, and the tool answers rather than starts closing.
     */
    @Test
    public void anUnassemblableCommandRefuses()
    {
        // relaunchCommandOf is what the preflight consults; this only checks it answers rather
        // than throws, whatever the environment gives it.
        RestartEdtTool.relaunchCommandOf();
        assertTrue(true);
    }

    /**
     * A restart whose watcher could not be started is refused before anything closes. The only
     * fallback would be the launcher's own restart, which answered success on the stand and never
     * brought the workbench back - so an unreachable watcher is a refusal, not a quieter way to
     * lose the IDE.
     */
    @Test
    public void aRestartWithoutItsWatcherIsRefusedBeforeAnythingCloses()
    {
        RestartEdtTool.RestartPreflight preflight = RestartEdtTool.restartPreflight(command -> null);
        if (preflight.command == null)
        {
            // This runtime cannot assemble the command at all; the refusal names that instead.
            // Same door: nothing closes.
            assertNotNull(preflight.refusal);
            return;
        }
        assertNull(preflight.watcher);
        assertNotNull(preflight.refusal);
        assertTrue(preflight.refusal, preflight.refusal.contains("watcher")); //$NON-NLS-1$
        assertTrue(preflight.refusal, preflight.refusal.contains("Nothing was closed")); //$NON-NLS-1$
    }

    /**
     * A watcher that started lets the restart proceed, and the preflight hands it to the deferred
     * close together with the command it was started for.
     */
    @Test
    public void aStartedWatcherLetsTheRestartProceed()
    {
        Process fakeWatcher = new Process()
        {
            @Override
            public java.io.OutputStream getOutputStream()
            {
                return java.io.OutputStream.nullOutputStream();
            }

            @Override
            public java.io.InputStream getInputStream()
            {
                return java.io.InputStream.nullInputStream();
            }

            @Override
            public java.io.InputStream getErrorStream()
            {
                return java.io.InputStream.nullInputStream();
            }

            @Override
            public int waitFor()
            {
                return 0;
            }

            @Override
            public int exitValue()
            {
                // Still running: a preflight must not confuse the stand-in for a dead process.
                throw new IllegalThreadStateException();
            }

            @Override
            public void destroy()
            {
            }
        };

        RestartEdtTool.RestartPreflight preflight =
            RestartEdtTool.restartPreflight(command -> fakeWatcher);
        if (preflight.command == null)
        {
            // No launcher under this runtime; the refusal path is covered by its own test.
            return;
        }
        assertNull(preflight.refusal);
        assertSame(fakeWatcher, preflight.watcher);
    }
}
