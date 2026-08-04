/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;

/**
 * An opt-in debug trace, written to a size-bounded file in the plugin's state location.
 *
 * <p>The server's hot paths call {@code logDebug} freely, so routing that to the Eclipse Error Log
 * would drown it. Instead these lines go to a private file that only exists when a developer turns the
 * preference on; by default the call is a cheap flag check and nothing is written. The file is capped
 * and restarted from empty when it grows past the cap, so it can never fill the disk.
 */
public final class DebugLog
{
    private static final String FILE_NAME = "debug.log"; //$NON-NLS-1$

    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final Object LOCK = new Object();

    private DebugLog()
    {
    }

    /**
     * @return whether the debug trace is switched on
     */
    public static boolean isEnabled()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return false;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        return store != null && store.getBoolean(PrefKeys.PREF_DEBUG_LOG_ENABLED);
    }

    /**
     * Appends one timestamped line to the trace file, if the trace is on. Never throws: a failure to
     * write a diagnostic must not disturb the caller.
     *
     * @param message the line to write; ignored if <code>null</code>
     */
    public static void write(String message)
    {
        if (message == null || !isEnabled())
        {
            return;
        }
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return;
        }
        IPath state = activator.getStateLocation();
        if (state == null)
        {
            return;
        }
        Path file = state.append(FILE_NAME).toFile().toPath();
        String line = Instant.now() + " " + message + System.lineSeparator(); //$NON-NLS-1$
        synchronized (LOCK)
        {
            try
            {
                if (Files.exists(file) && Files.size(file) > MAX_BYTES)
                {
                    Files.write(file, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
                }
                Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            catch (IOException | RuntimeException e)
            {
                // Diagnostics must never break the caller (a bad path, a SecurityException, anything).
            }
        }
    }
}
