/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;

/**
 * What an answer built on the BSL index has to say about the ordinary forms of a project.
 *
 * <p>EDT does not parse the module of an ordinary form, so nothing in the index - references,
 * callers, markers - covers it. A tool that answers from the index over a project with such
 * forms answers about part of the code, and the answer says so with a count rather than
 * standing as complete: a caller deciding whether a method is safe to change reads a bare
 * "nothing calls it" as the whole truth.</p>
 *
 * <p>The count is a walk of the {@code src} tree, cached for a short while per project, since
 * the answers this decorates are asked in bursts.</p>
 */
public final class OrdinaryFormCoverage
{
    private static final long CACHE_MS = 30_000L;

    private static final Map<String, long[]> CACHE = new ConcurrentHashMap<>();

    private OrdinaryFormCoverage()
    {
        // static utility
    }

    /**
     * How many ordinary forms a project has.
     *
     * @param project the project; {@code null} counts as none
     * @return the count; 0 when the tree cannot be walked
     */
    public static int count(IProject project)
    {
        if (project == null || project.getLocation() == null)
        {
            return 0;
        }
        String key = project.getLocation().toString();
        long now = System.currentTimeMillis();
        long[] cached = CACHE.get(key);
        if (cached != null && now - cached[0] < CACHE_MS)
        {
            return (int)cached[1];
        }
        int count;
        try
        {
            count = OrdinaryFormLocator.locate(project).size();
        }
        catch (IOException e)
        {
            Activator.logWarning("Ordinary forms of " + project.getName() + " were not counted: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            count = 0;
        }
        CACHE.put(key, new long[] { now, count });
        return count;
    }

    /** Forgets every cached count, for a test that changes a project between two answers. */
    public static void reset()
    {
        CACHE.clear();
    }

    /**
     * The coverage statement for an index-built answer, or {@code null} when the project has no
     * ordinary forms and the answer covers everything.
     *
     * @param project the project the answer is about
     * @param what what the answer lists - "references", "callers", "markers"
     * @return one sentence with the count, or {@code null}
     */
    public static String statement(IProject project, String what)
    {
        int count = count(project);
        if (count == 0)
        {
            return null;
        }
        return "Coverage: " + count + " ordinary form module" + (count == 1 ? "" : "s") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " of this project " + (count == 1 ? "is" : "are") + " outside the BSL index, so no " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + what + " from them " + (what.endsWith("s") ? "are" : "is") + " counted here. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "Their code lives in Form.oform containers; code_search text_search scans it, " //$NON-NLS-1$
            + "and read_module_source reads a module by its <object>/Forms/<form>/Module.bsl address."; //$NON-NLS-1$
    }

    /**
     * Appends the coverage statement to a markdown answer.
     *
     * @param project the project the answer is about
     * @param what what the answer lists
     * @param markdown the answer; an error line is returned unchanged
     * @return the answer with the statement as a closing paragraph, when there is one
     */
    public static String appendTo(IProject project, String what, String markdown)
    {
        if (markdown == null || markdown.startsWith("Error:")) //$NON-NLS-1$
        {
            return markdown;
        }
        String statement = statement(project, what);
        if (statement == null)
        {
            return markdown;
        }
        String separator = markdown.endsWith("\n") ? "\n" : "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return markdown + separator + "**" + statement + "**\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
