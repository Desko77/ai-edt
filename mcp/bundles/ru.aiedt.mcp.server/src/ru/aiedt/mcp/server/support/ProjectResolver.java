/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Resolves an EDT project by name with a forgiving lookup, so callers accept
 * the short name of an extension project as well as its full name.
 * <p>
 * Extension projects are named {@code "<base>.<ext>"} (e.g.
 * {@code "Demo.MyExtension"}); agents frequently pass just {@code "MyExtension"}.
 * {@code IWorkspaceRoot.getProject(name)} is a case-sensitive exact match and
 * returns "not found" for the short form. This helper adds a case-insensitive
 * exact match and a unique {@code "<base>.<ext>"} suffix match.
 */
public final class ProjectResolver
{
    private ProjectResolver()
    {
        // Utility class
    }

    /**
     * Resolves a project by name. Order: exact name, then case-insensitive
     * exact (wins over suffix), then a unique {@code "<base>.<name>"} suffix.
     * Only accessible (existing AND open) projects are considered. An ambiguous
     * suffix (two or more matches) yields {@code null} - the caller should
     * require the full name. Returns {@code null} when nothing matches.
     * <p>Note: the suffix match is case-sensitive; the case-insensitive step
     * applies to the full project name only.
     *
     * @param projectName exact or short project name (may be null/empty)
     * @return the resolved project, or {@code null}
     */
    public static IProject resolve(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject exact = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (exact.isAccessible())
        {
            return exact;
        }
        IProject[] all = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject p : all)
        {
            if (p.isAccessible() && p.getName().equalsIgnoreCase(projectName))
            {
                return p;
            }
        }
        IProject match = null;
        for (IProject p : all)
        {
            if (p.isAccessible() && p.getName().endsWith("." + projectName)) //$NON-NLS-1$
            {
                if (match != null)
                {
                    return null; // ambiguous - require the full name
                }
                match = p;
            }
        }
        return match;
    }

    /**
     * Builds a human- and agent-friendly "project not found" message for the
     * case where {@link #resolve(String)} returned {@code null}. The message
     * names the closest open project (extension suffix match first, then a
     * small edit-distance), lists the open projects, and distinguishes the
     * "exists but closed" case (the agent passed the right name, the project
     * is just not open). Use this in place of a bare
     * {@code "Project not found: " + projectName}.
     *
     * @param projectName the name the caller passed (may be null/empty)
     * @return a single-line diagnostic message
     */
    public static String describeNotFound(String projectName)
    {
        IProject[] all = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        // "exists but closed" is the most actionable specific message.
        for (IProject p : all)
        {
            if (p.getName().equals(projectName) && p.exists() && !p.isOpen())
            {
                return "Project '" + projectName + "' exists but is closed. Open it in the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Navigator (right-click -> Open Project) and retry."; //$NON-NLS-1$
            }
        }
        List<String> open = new ArrayList<>();
        for (IProject p : all)
        {
            if (p.isAccessible())
            {
                open.add(p.getName());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Project not found: '").append(projectName).append("'."); //$NON-NLS-1$ //$NON-NLS-2$
        if (open.isEmpty())
        {
            sb.append(" No open EDT projects in the workspace."); //$NON-NLS-1$
            return sb.toString();
        }
        String suggestion = closest(projectName, open);
        if (suggestion != null)
        {
            sb.append(" Did you mean '").append(suggestion).append("'?"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(" Open projects: "); //$NON-NLS-1$
        int cap = 20;
        if (open.size() <= cap)
        {
            sb.append(String.join(", ", open)); //$NON-NLS-1$
        }
        else
        {
            sb.append(String.join(", ", open.subList(0, cap))) //$NON-NLS-1$
                .append(", ... (+").append(open.size() - cap).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /**
     * Picks the open project name closest to {@code input}: an extension
     * {@code "<base>.<input>"} suffix match wins outright, otherwise the
     * minimum case-insensitive Levenshtein distance within a length-scaled
     * threshold. Returns {@code null} when nothing is close enough.
     */
    private static String closest(String input, List<String> candidates)
    {
        if (input == null || input.isEmpty())
        {
            return null;
        }
        for (String c : candidates)
        {
            if (c.endsWith("." + input)) //$NON-NLS-1$
            {
                return c;
            }
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int threshold = Math.max(2, input.length() / 2);
        String lowerInput = input.toLowerCase();
        for (String c : candidates)
        {
            int d = levenshtein(lowerInput, c.toLowerCase());
            if (d < bestDistance)
            {
                bestDistance = d;
                best = c;
            }
        }
        return bestDistance <= threshold ? best : null;
    }

    /**
     * Classic two-row Levenshtein edit distance.
     */
    private static int levenshtein(String a, String b)
    {
        int n = a.length();
        int m = b.length();
        if (n == 0)
        {
            return m;
        }
        if (m == 0)
        {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++)
        {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++)
        {
            curr[0] = i;
            for (int j = 1; j <= m; j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
}
