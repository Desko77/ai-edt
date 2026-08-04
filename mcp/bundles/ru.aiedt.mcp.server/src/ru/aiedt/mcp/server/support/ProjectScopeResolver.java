/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * 1.42 (RSV 4.2 parity, sister extensions / externals): builds a search
 * scope that mirrors the EDT project dependency model.
 *
 * <p>RSV 4.2 release notes describe the contract this resolver implements:
 * <ul>
 *   <li>Object owned by a main configuration is searched in the configuration
 *       itself plus every extension and external data processor / external
 *       report that references it.</li>
 *   <li>Object owned by an extension or external is searched in the
 *       owner project plus sister extensions / externals that share the
 *       same parent configuration. The parent configuration is excluded
 *       (an object defined in an extension cannot be referenced from the
 *       configuration without explicit borrowing).</li>
 *   <li>Unknown owner falls back to a single-project scope.</li>
 * </ul>
 *
 * <p>Implementation uses Eclipse-level project references
 * ({@link org.eclipse.core.resources.IProjectDescription#getReferencedProjects()})
 * which both EDT and the user's own project setup populate. We intentionally
 * do not depend on EDT-internal {@code IExtensionProject} APIs - the Eclipse
 * reference graph is enough for the standard layouts and stays stable across
 * EDT versions.
 */
public final class ProjectScopeResolver
{
    public static final class ScopeResult
    {
        /** Ordered list of projects to search. The owner is always first. */
        public final List<IProject> projects;
        /** Human-readable explanation of the scope decision. */
        public final String reason;
        /** Project names skipped during resolution, for diagnostics. */
        public final List<String> notes;

        public ScopeResult(List<IProject> projects, String reason, List<String> notes)
        {
            this.projects = projects;
            this.reason = reason;
            this.notes = notes;
        }
    }

    private ProjectScopeResolver()
    {
    }

    /**
     * Builds the search scope.
     *
     * @param ownerProject project that owns the FQN being searched.
     * @param ownerKind one of {@code "configuration"} / {@code "extension"} /
     *        {@code "external"}. When {@code null} or unknown, the scope
     *        collapses to {@code [ownerProject]}.
     * @return ordered scope list with explanation; never {@code null}.
     */
    public static ScopeResult resolveScope(IProject ownerProject, String ownerKind)
    {
        List<IProject> scope = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        scope.add(ownerProject);
        if (ownerKind == null || ownerKind.isEmpty())
        {
            return new ScopeResult(scope, "Unknown owner kind - scope collapsed to owner only.", //$NON-NLS-1$
                notes);
        }
        IProject[] all = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if ("configuration".equals(ownerKind)) //$NON-NLS-1$
        {
            // Add every workspace project whose Eclipse references include
            // the owner - that is the canonical signal that the project is
            // an extension / external attached to this configuration.
            for (IProject candidate : all)
            {
                if (candidate.equals(ownerProject) || !candidate.isAccessible())
                {
                    continue;
                }
                if (referencesProject(candidate, ownerProject))
                {
                    scope.add(candidate);
                }
            }
            return new ScopeResult(scope,
                "owner is a main configuration - scope includes the configuration plus " //$NON-NLS-1$
                    + (scope.size() - 1)
                    + " referencing extensions / external data processors / reports.", //$NON-NLS-1$
                notes);
        }
        // owner is an extension or external: walk to the parent configuration
        // through the Eclipse reference graph, then collect sister projects.
        IProject parent = findParentConfiguration(ownerProject);
        if (parent == null)
        {
            return new ScopeResult(scope,
                "owner kind is " + ownerKind + " but the parent configuration could not be " //$NON-NLS-1$ //$NON-NLS-2$
                    + "located via Eclipse project references - scope collapsed to owner only.", //$NON-NLS-1$
                notes);
        }
        notes.add("parent configuration: " + parent.getName()); //$NON-NLS-1$
        for (IProject candidate : all)
        {
            if (candidate.equals(ownerProject) || candidate.equals(parent)
                || !candidate.isAccessible())
            {
                continue;
            }
            if (referencesProject(candidate, parent))
            {
                scope.add(candidate);
            }
        }
        return new ScopeResult(scope,
            "owner is " + ownerKind + " of '" + parent.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "' - scope includes the owner plus " + (scope.size() - 1) //$NON-NLS-1$
                + " sister extensions / externals attached to the same configuration. " //$NON-NLS-1$
                + "The configuration project itself is excluded by design.", //$NON-NLS-1$
            notes);
    }

    /**
     * Walks {@code candidate.getReferencedProjects()} for a non-External 1C
     * project - the heuristic by which we identify the parent configuration
     * of an extension / external owner. The first match wins.
     *
     * <p>Returns {@code null} when no non-External reference exists. Standard
     * EDT layouts always have at least one - the configuration project the
     * extension was generated against.
     */
    private static IProject findParentConfiguration(IProject project)
    {
        try
        {
            IProject[] refs = project.getDescription().getReferencedProjects();
            for (IProject ref : refs)
            {
                if (ref == null || !ref.isAccessible())
                {
                    continue;
                }
                if (ExternalProjectResolver.detectExternalKind(ref) == null)
                {
                    return ref;
                }
            }
        }
        catch (CoreException ignored)
        {
            // Project description unavailable - fall through.
        }
        return null;
    }

    /**
     * @return {@code true} when {@code candidate} declares a static project
     *         reference to {@code target} via Eclipse project description.
     */
    private static boolean referencesProject(IProject candidate, IProject target)
    {
        try
        {
            IProject[] refs = candidate.getDescription().getReferencedProjects();
            for (IProject ref : refs)
            {
                if (target.equals(ref))
                {
                    return true;
                }
            }
        }
        catch (CoreException ignored)
        {
            // Treat as no reference.
        }
        return false;
    }

    /**
     * Compact comma-separated list of project names for diagnostics output.
     */
    public static String describe(List<IProject> projects)
    {
        Set<String> names = new LinkedHashSet<>();
        for (IProject p : projects)
        {
            names.add(p.getName());
        }
        return String.join(", ", names); //$NON-NLS-1$
    }
}
