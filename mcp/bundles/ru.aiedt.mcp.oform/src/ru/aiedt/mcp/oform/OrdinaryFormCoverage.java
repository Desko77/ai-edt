/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.IOException;
import java.util.Collection;

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
 * <p>The count is a walk of the {@code src} tree, taken on every call, so a form created or
 * removed between two answers - by this plugin or on disk - is counted at once. The plugin
 * appends the statement to its answers through the provider.</p>
 */
public final class OrdinaryFormCoverage
{
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
        try
        {
            return OrdinaryFormLocator.locate(project).size();
        }
        catch (IOException e)
        {
            Activator.logWarning("Ordinary forms of " + project.getName() + " were not counted: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return 0;
        }
    }

    /**
     * Does nothing: the count is read afresh on every call, so there is nothing to forget. Kept
     * for the callers that reset between two answers of a test.
     */
    public static void reset()
    {
        // no cache: every count walks the tree
    }

    /**
     * How many ordinary forms a set of projects has between them.
     *
     * @param projects the projects; {@code null} entries count as none
     * @return the sum
     */
    public static int count(Collection<IProject> projects)
    {
        int total = 0;
        if (projects != null)
        {
            for (IProject project : projects)
            {
                total += count(project);
            }
        }
        return total;
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
        return statement(count(project), "this project", what); //$NON-NLS-1$
    }

    /**
     * The coverage statement for an answer built over several projects - an extension and the
     * configuration it extends, or the whole workspace.
     *
     * @param projects the projects the answer is about
     * @param what what the answer lists
     * @return one sentence with the summed count, or {@code null} when there is nothing to say
     */
    public static String statement(Collection<IProject> projects, String what)
    {
        return statement(count(projects), projects != null && projects.size() == 1 ? "this project" //$NON-NLS-1$
            : "the projects searched", what); //$NON-NLS-1$
    }

    private static String statement(int count, String where, String what)
    {
        if (count == 0)
        {
            return null;
        }
        return "Coverage: " + count + " ordinary form module" + (count == 1 ? "" : "s") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " of " + where + " " + (count == 1 ? "is" : "are") + " outside the BSL index, so no " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            + what + " from them " + (what.endsWith("s") ? "are" : "is") + " counted here. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "Their code lives in Form.oform containers; code_search text_search scans it, " //$NON-NLS-1$
            + "and read_module_source reads a module by its <object>/Forms/<form>/Module.bsl address."; //$NON-NLS-1$
    }
}
