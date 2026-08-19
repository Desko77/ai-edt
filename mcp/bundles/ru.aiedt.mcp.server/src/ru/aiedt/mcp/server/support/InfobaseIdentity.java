/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Locale;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * A name for an infobase that means the same thing in two EDT instances.
 * <p>
 * Needed because the obvious names do not survive the crossing. A project name is a workspace's
 * word for something, and two workspaces can call the same infobase different things - or different
 * infobases the same thing. What both instances agree on is where the infobase actually is, so that
 * is what gets used: the connection string, reduced to the part that identifies it.
 * </p>
 */
public final class InfobaseIdentity
{
    private InfobaseIdentity()
    {
    }

    /**
     * The identity of an application's infobase.
     *
     * @param application the application, possibly not an infobase one at all.
     * @return a stable identity two processes will agree on, or {@code null} when the application
     *         has no infobase or does not say where it is - in which case nothing is claimed rather
     *         than something arbitrary
     */
    public static String of(IApplication application)
    {
        if (!(application instanceof IInfobaseApplication))
        {
            return null;
        }
        InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
        IConnectionString connection = infobase == null ? null : infobase.getConnectionString();
        if (connection instanceof FileConnectionString)
        {
            String file = ((FileConnectionString)connection).getFile();
            if (file == null || file.trim().isEmpty())
            {
                return null;
            }
            // Lowercased, because Windows will hand back the same directory spelled two ways and
            // two spellings of one infobase would be two locks over one thing - which is the same
            // as no lock, only harder to notice.
            return "file:" + file.trim().replace('\\', '/').toLowerCase(Locale.ROOT); //$NON-NLS-1$
        }
        if (connection instanceof ServerConnectionString)
        {
            ServerConnectionString server = (ServerConnectionString)connection;
            String host = server.getServer();
            String reference = server.getReference();
            if (host == null || reference == null)
            {
                return null;
            }
            return "server:" + host.trim().toLowerCase(Locale.ROOT) + "/" //$NON-NLS-1$ //$NON-NLS-2$
                + reference.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
