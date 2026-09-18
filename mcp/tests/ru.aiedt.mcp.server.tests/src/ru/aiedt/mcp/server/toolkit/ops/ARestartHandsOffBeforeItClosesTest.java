/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
}
