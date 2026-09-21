/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.modules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;

/**
 * The module source providers the server knows: the OSGi services of
 * {@link IModuleSourceProvider} the activator tracks, and the ones a test registers directly.
 *
 * <p>Every query asks the providers in registration order and stops at the first answer. A
 * provider that throws is logged and skipped, so one bundle's failure does not take the module
 * tools down with it.</p>
 */
public final class ModuleSources
{
    private static final CopyOnWriteArrayList<IModuleSourceProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ModuleSources()
    {
        // static registry
    }

    /**
     * Adds a provider. A provider already present is not added twice.
     *
     * @param provider the provider
     */
    public static void register(IModuleSourceProvider provider)
    {
        if (provider != null)
        {
            PROVIDERS.addIfAbsent(provider);
            Activator.logInfo("module source provider registered: " + provider.kind()); //$NON-NLS-1$
        }
    }

    /**
     * Removes a provider.
     *
     * @param provider the provider
     */
    public static void unregister(IModuleSourceProvider provider)
    {
        if (provider != null && PROVIDERS.remove(provider))
        {
            Activator.logInfo("module source provider gone: " + provider.kind()); //$NON-NLS-1$
        }
    }

    /**
     * @return the providers, in registration order
     */
    public static List<IModuleSourceProvider> providers()
    {
        return List.copyOf(PROVIDERS);
    }

    /**
     * Resolves a module address through the providers.
     *
     * @param project the project
     * @param modulePath the address
     * @return the module, or {@code null} when no provider owns the address
     */
    public static IModuleSource locate(IProject project, String modulePath)
    {
        if (project == null || modulePath == null || modulePath.isEmpty())
        {
            return null;
        }
        for (IModuleSourceProvider provider : PROVIDERS)
        {
            try
            {
                IModuleSource module = provider.locate(project, modulePath);
                if (module != null)
                {
                    return module;
                }
            }
            catch (RuntimeException e)
            {
                Activator.logError("module source provider " + provider.kind() + " failed on " + modulePath, e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * Every provided module of a project.
     *
     * @param project the project
     * @return the modules of every provider, in provider order
     */
    public static List<IModuleSource> list(IProject project)
    {
        List<IModuleSource> out = new ArrayList<>();
        if (project == null)
        {
            return out;
        }
        for (IModuleSourceProvider provider : PROVIDERS)
        {
            try
            {
                out.addAll(provider.list(project));
            }
            catch (IOException | RuntimeException e)
            {
                Activator.logError("module source provider " + provider.kind() + " failed to list " //$NON-NLS-1$ //$NON-NLS-2$
                    + project.getName(), e);
            }
        }
        return out;
    }

    /**
     * The coverage lines of every provider for an answer built from the BSL index.
     *
     * @param projects the projects the answer spans
     * @param what what the answer is about
     * @return the lines, possibly none
     */
    public static List<String> coverage(Collection<IProject> projects, String what)
    {
        List<String> out = new ArrayList<>();
        if (projects == null || projects.isEmpty())
        {
            return out;
        }
        for (IModuleSourceProvider provider : PROVIDERS)
        {
            try
            {
                String line = provider.coverage(projects, what);
                if (line != null && !line.isEmpty())
                {
                    out.add(line);
                }
            }
            catch (RuntimeException e)
            {
                Activator.logError("module source provider " + provider.kind() + " failed to report coverage", e); //$NON-NLS-1$
            }
        }
        return out;
    }

    /**
     * Appends the coverage lines to a markdown answer as closing paragraphs.
     *
     * @param project the project the answer is about
     * @param what what the answer is about
     * @param markdown the answer; an error line is returned unchanged
     * @return the answer with the lines, when there are any
     */
    public static String appendCoverage(IProject project, String what, String markdown)
    {
        return appendCoverage(project == null ? List.of() : List.of(project), what, markdown);
    }

    /**
     * Appends the coverage lines over several projects to a markdown answer as closing paragraphs.
     *
     * @param projects the projects the answer spans
     * @param what what the answer is about
     * @param markdown the answer; an error line is returned unchanged
     * @return the answer with the lines, when there are any
     */
    public static String appendCoverage(Collection<IProject> projects, String what, String markdown)
    {
        if (markdown == null || markdown.startsWith("Error:")) //$NON-NLS-1$
        {
            return markdown;
        }
        String answer = markdown;
        for (String line : coverage(projects, what))
        {
            String separator = answer.endsWith("\n") ? "\n" : "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            answer = answer + separator + "**" + line + "**\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return answer;
    }
}
