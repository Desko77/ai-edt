/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Who is holding an infobase, named, when an operation on it will not go through.
 * <p>
 * A held infobase does not answer "held". It answers with a platform error, or with nothing at
 * all until a timeout, and both read as "the tool is broken" rather than "someone is using the
 * base". The reader is then left to guess between a running client, another EDT with the project
 * open, and a real defect - and guessing wrong sends them looking in the wrong place, which is
 * what happened often enough to be worth naming.
 * </p>
 * <p>
 * Only what is actually known is reported. This server sees the runtime clients EDT itself
 * launched, and - through the instance registry - the other AI-EDT servers on this machine and
 * which projects they have open. It cannot see a client somebody started from a shortcut, or a
 * Designer opened by hand, so an empty answer means "none of the holders I can see", never
 * "nobody". That distinction is written into the note rather than left for the reader to assume.
 * </p>
 */
public final class InfobaseHolders
{
    private InfobaseHolders()
    {
    }

    /**
     * Everything this server can see that might be holding an application's infobase.
     *
     * @param applicationId the application whose infobase is concerned.
     * @param projectNames the projects that own it - the extension and its parent, where both apply.
     * @return a block for the response, or null when there is nothing at all to report.
     */
    public static Map<String, Object> describe(String applicationId, Set<String> projectNames)
    {
        List<Map<String, Object>> clients = runningClients(applicationId);
        List<Map<String, Object>> neighbours = neighbouringInstances(projectNames);
        if (clients.isEmpty() && neighbours.isEmpty())
        {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        if (!clients.isEmpty())
        {
            block.put("clients", clients); //$NON-NLS-1$
        }
        if (!neighbours.isEmpty())
        {
            block.put("otherInstances", neighbours); //$NON-NLS-1$
        }
        block.put("note", note(clients, neighbours)); //$NON-NLS-1$
        return block;
    }

    /**
     * What to do about the holders, and what this list does not cover.
     *
     * @param clients the runtime clients found.
     * @param neighbours the other AI-EDT servers found.
     * @return one sentence per thing worth acting on.
     */
    private static String note(List<Map<String, Object>> clients, List<Map<String, Object>> neighbours)
    {
        StringBuilder sb = new StringBuilder();
        if (!clients.isEmpty())
        {
            sb.append("A running client keeps the infobase. Pass autoFreeClients=true to stop these "); //$NON-NLS-1$
            sb.append("before the update, or close them yourself. "); //$NON-NLS-1$
        }
        if (!neighbours.isEmpty())
        {
            sb.append("Another EDT on this machine has the project open, and EDT keeps a session on "); //$NON-NLS-1$
            sb.append("the infobase of an open project - close the project there, or run this from "); //$NON-NLS-1$
            sb.append("that instance instead. "); //$NON-NLS-1$
        }
        sb.append("This lists only holders this server can see: clients EDT itself launched and "); //$NON-NLS-1$
        sb.append("other AI-EDT servers. A client started outside EDT, or a Designer opened by "); //$NON-NLS-1$
        sb.append("hand, holds the base just as well and does not appear here."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * The runtime clients EDT launched against this application and has not seen die.
     *
     * @param applicationId the application concerned.
     * @return one row per live client, naming its launch configuration.
     */
    private static List<Map<String, Object>> runningClients(String applicationId)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (applicationId == null || applicationId.isEmpty())
        {
            return rows;
        }
        try
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            ILaunchManager manager = debugPlugin == null ? null : debugPlugin.getLaunchManager();
            if (manager == null)
            {
                return rows;
            }
            for (ILaunch launch : manager.getLaunches())
            {
                if (launch.isTerminated())
                {
                    continue;
                }
                if (!applicationId.equals(DebugSessionBook.findApplicationIdFor(launch)))
                {
                    continue;
                }
                ILaunchConfiguration cfg = launch.getLaunchConfiguration();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("launchConfiguration", cfg == null ? "(unnamed)" : cfg.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                row.put("mode", launch.getLaunchMode()); //$NON-NLS-1$
                if (cfg != null)
                {
                    String owner =
                        LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    if (!owner.isEmpty())
                    {
                        row.put("project", owner); //$NON-NLS-1$
                    }
                    row.put("launchType", LaunchConfigAccess.getConfigTypeId(cfg)); //$NON-NLS-1$
                }
                rows.add(row);
            }
        }
        catch (Exception e)
        {
            // Said out loud: an empty holder list is read as "nobody is holding it", and a failure
            // to look must not be dressed up as an answer.
            Activator.logWarning("Could not list the clients holding " + applicationId //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return rows;
    }

    /**
     * The other AI-EDT servers on this machine that have one of these projects open.
     *
     * @param projectNames the projects that own the infobase.
     * @return one row per neighbouring instance, naming it and its port.
     */
    private static List<Map<String, Object>> neighbouringInstances(Set<String> projectNames)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (projectNames == null || projectNames.isEmpty())
        {
            return rows;
        }
        for (JsonObject entry : InstanceRegistry.live())
        {
            if (entry.has("self") && entry.get("self").getAsBoolean()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            List<String> shared = sharedProjects(entry, projectNames);
            if (shared.isEmpty())
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            if (entry.has("title")) //$NON-NLS-1$
            {
                row.put("instance", entry.get("title").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (entry.has("port")) //$NON-NLS-1$
            {
                row.put("port", entry.get("port").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            row.put("projectsInCommon", shared); //$NON-NLS-1$
            rows.add(row);
        }
        return rows;
    }

    /**
     * The projects an instance has open that also own the infobase in question.
     *
     * @param entry one instance record.
     * @param projectNames the projects that own the infobase.
     * @return their names, empty when the instance shares none.
     */
    private static List<String> sharedProjects(JsonObject entry, Set<String> projectNames)
    {
        List<String> shared = new ArrayList<>();
        if (!entry.has("projects")) //$NON-NLS-1$
        {
            return shared;
        }
        try
        {
            for (com.google.gson.JsonElement open : entry.getAsJsonArray("projects")) //$NON-NLS-1$
            {
                String name = open.getAsString();
                if (projectNames.contains(name))
                {
                    shared.add(name);
                }
            }
        }
        catch (RuntimeException e)
        {
            // A record whose project list is not a list of strings tells us nothing about sharing.
            // Reported as sharing nothing rather than as a holder we invented.
            return shared;
        }
        return shared;
    }
}
